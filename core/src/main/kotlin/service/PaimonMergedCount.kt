package service

import model.DEFAULT_PAIMON_MERGE_ENGINE
import model.PaimonLookupFile
import model.PaimonReadInput
import model.PaimonRowKind
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import java.util.BitSet

/**
 * The rows a read of a Paimon snapshot returns — `SELECT count(*)` as of it — which nothing in
 * the metadata states. `totalRecordCount` sums file rows, so a key written twice counts twice
 * and a `-D` marker counts as a row; an `ANALYZE` writes `mergedRecordCount`, once, for the
 * snapshot it ran on. The figure a reader has in front of them is the sum, and the question
 * they bring is why `count(*)` disagrees with it.
 *
 * On a primary-key table under `deduplicate` the answer is the merge a read runs, per bucket:
 * one record per key, the one with the highest `_SEQUENCE_NUMBER`; minus the keys whose latest
 * record is a `-D` or `-U`; minus the keys whose latest record sits at a position the file's
 * deletion vector marks. That is one DuckDB statement over the bucket's live files for the
 * first two — the same `latestPerKeySql` the row lookup asks a key's state with — and, where a
 * file in the bucket has a vector, the latest records' positions in that file streamed back and
 * tested against the vector's bit set, decoded whole so the count is exact whatever the
 * cardinality. A merge engine that combines versions rather than picking one is reported, not
 * applied. An append table needs no file opened: its rows are the files' rows minus the
 * vectors' cardinalities, which the index manifest records, minus a data-evolution patch file's
 * rows, which are columns of rows another file holds.
 *
 * Behind a click, since it reads every live file of every bucket — the one thing on the
 * snapshot panel that scales with the data rather than the metadata — and capped at
 * [MAX_BUCKETS], stated on the result.
 */
object PaimonMergedCount {
    private val logger = LoggerFactory.getLogger(PaimonMergedCount::class.java)

    /** Buckets merged in one run. */
    const val MAX_BUCKETS = 64

    data class BucketCount(
        val partition: String,
        val bucket: Int,
        val files: Int,
        /** The files' recorded rows. */
        val fileRows: Long,
        /** Distinct keys — one record each after the merge. */
        val keys: Long,
        /** Keys whose latest record is a `-D` or `-U`. */
        val retracted: Long,
        /** Keys whose latest record a vector marks. */
        val vectorMarked: Long,
        val error: String? = null,
    ) {
        val merged: Long get() = keys - retracted - vectorMarked
    }

    data class Result(
        val snapshotId: Long,
        val mergeEngine: String,
        /** Whether the merge engine's rule was applied; false leaves [merged] null. */
        val applied: Boolean,
        /** Whether the answer came from the metadata alone — an append table's. */
        val fromMetadata: Boolean,
        val buckets: List<BucketCount>,
        /** Buckets left unread by [MAX_BUCKETS]. */
        val bucketsLeft: Int,
        val fileRows: Long,
    ) {
        val merged: Long? get() = if (applied && buckets.none { it.error != null } && bucketsLeft == 0) buckets.sumOf { it.merged } else null
        val retracted: Long get() = buckets.sumOf { it.retracted }
        val vectorMarked: Long get() = buckets.sumOf { it.vectorMarked }
        val failed: Int get() = buckets.count { it.error != null }
    }

    fun count(input: PaimonReadInput): Result {
        val fileRows = input.files.sumOf { it.recordCount ?: 0L }
        val buckets = input.files.groupBy { it.partition to it.bucket }.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
        if (!input.hasPrimaryKey) {
            // No merge: every file row is a row, less the vectors and the patch files.
            val counted = buckets.map { (scope, files) ->
                val vectors = files.sumOf { f -> input.vectorFor(f.fileName)?.cardinality ?: 0L }
                val partial = files.filter { it.partial }.sumOf { it.recordCount ?: 0L }
                val rows = files.sumOf { it.recordCount ?: 0L }
                BucketCount(scope.first, scope.second, files.size, rows, rows - partial, 0, vectors)
            }
            return Result(input.snapshotId, input.mergeEngine, applied = true, fromMetadata = true, buckets = counted, bucketsLeft = 0, fileRows = fileRows)
        }
        if (input.mergeEngine != DEFAULT_PAIMON_MERGE_ENGINE) {
            return Result(input.snapshotId, input.mergeEngine, applied = false, fromMetadata = false, buckets = emptyList(), bucketsLeft = buckets.size, fileRows = fileRows)
        }
        val keyColumns = input.trimmedPrimaryKeys.map { PaimonRowLookup.KEY_PREFIX + it }
        val toRead = buckets.take(MAX_BUCKETS)
        val vectors = mutableMapOf<String, BitSet?>()
        val counted = toRead.map { (scope, files) ->
            runCatching { countBucket(input, scope.first, scope.second, files, keyColumns, vectors) }
                .onFailure { logger.warn("Could not merge bucket {}: {}", scope, it.message) }
                .getOrElse { BucketCount(scope.first, scope.second, files.size, files.sumOf { f -> f.recordCount ?: 0L }, 0, 0, 0, it.message ?: it.toString()) }
        }
        return Result(input.snapshotId, input.mergeEngine, applied = true, fromMetadata = false, buckets = counted, bucketsLeft = buckets.size - toRead.size, fileRows = fileRows)
    }

    private fun countBucket(
        input: PaimonReadInput,
        partition: String,
        bucket: Int,
        files: List<PaimonLookupFile>,
        keyColumns: List<String>,
        vectors: MutableMap<String, BitSet?>,
    ): BucketCount {
        require(keyColumns.isNotEmpty()) { "a primary-key table with no key column outside the partition" }
        val paths = files.map { SampleRowReader.resolveForQuery(it.localPath).first }
        val latest = PaimonRowLookup.latestPerKeySql(files, keyColumns)
        val retractedKinds = "(${PaimonRowKind.UPDATE_BEFORE}, ${PaimonRowKind.DELETE})"
        val (keys, retracted) = DuckDb.withConnection { conn ->
            conn.prepareStatement("SELECT count(*), count(*) FILTER (WHERE kind IN $retractedKinds) FROM ($latest)").use { pstmt ->
                paths.forEachIndexed { i, p -> pstmt.setString(i + 1, p) }
                pstmt.executeQuery().use { rs -> rs.next(); rs.getLong(1) to rs.getLong(2) }
            }
        }
        // Keys whose latest record a vector marks: only the files with a vector are asked, and
        // only for the records that are not already retractions, streamed rather than listed.
        val vectored = files.filter { input.vectorFor(it.fileName) != null }
        var marked = 0L
        if (vectored.isNotEmpty()) {
            val bits = vectored.associate { file ->
                val range = requireNotNull(input.vectorFor(file.fileName))
                file.fileName to vectors.getOrPut(file.fileName) {
                    runCatching { PaimonDeletionVectorReader.readPositions(Paths.get(range.indexLocalPath), range.offset, range.length) }
                        .onFailure { logger.warn("Could not read the vector in {}: {}", range.indexLocalPath, it.message) }
                        .getOrNull()
                }
            }
            if (bits.values.any { it == null }) throw IllegalStateException("a vector in the bucket could not be read")
            val holders = vectored.joinToString(", ") { "?" }
            DuckDb.withConnection { conn ->
                conn.prepareStatement(
                    "SELECT holder, pos FROM ($latest) WHERE kind NOT IN $retractedKinds AND holder IN ($holders)",
                ).use { pstmt ->
                    var i = 1
                    paths.forEach { pstmt.setString(i++, it) }
                    vectored.forEach { pstmt.setString(i++, SampleRowReader.resolveForQuery(it.localPath).first) }
                    pstmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val name = rs.getString(1).substringAfterLast('/')
                            val pos = rs.getLong(2)
                            if (pos in 0..Int.MAX_VALUE && bits[name]?.get(pos.toInt()) == true) marked++
                        }
                    }
                }
            }
        }
        return BucketCount(partition, bucket, files.size, files.sumOf { it.recordCount ?: 0L }, keys, retracted, marked)
    }
}
