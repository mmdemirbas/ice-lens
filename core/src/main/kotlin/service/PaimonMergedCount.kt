package service

import model.PaimonLookupFile
import model.PaimonReadInput
import org.slf4j.LoggerFactory
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
        /** Keys whose latest record is a `-D` or `-U` that removes them — or, under sequence groups, a `-D` at or above the row's value. */
        val retracted: Long,
        /** Keys whose latest record a vector marks. */
        val vectorMarked: Long,
        val error: String? = null,
        /** Keys with no insert record at all, not already counted in [retracted] — not a row where [PaimonMergeRule.keyNeedsInsert]. */
        val insertless: Long = 0,
    ) {
        val merged: Long get() = keys - retracted - vectorMarked - insertless
    }

    data class Result(
        val snapshotId: Long,
        val mergeEngine: String,
        /** Whether the answer came from the metadata alone — an append table's. */
        val fromMetadata: Boolean,
        val buckets: List<BucketCount>,
        /** Buckets left unread by [MAX_BUCKETS]. */
        val bucketsLeft: Int,
        val fileRows: Long,
        /** Live level-0 files a batch read of this table skips, and the rows in them — see [PaimonMergeRule.skipsLevel0]. */
        val skippedFiles: Int = 0,
        val skippedRows: Long = 0,
        /** The rule, for the panel to state. */
        val rule: String = "",
    ) {
        val merged: Long? get() = if (buckets.none { it.error != null } && bucketsLeft == 0) buckets.sumOf { it.merged } else null
        val retracted: Long get() = buckets.sumOf { it.retracted }
        val insertless: Long get() = buckets.sumOf { it.insertless }
        val vectorMarked: Long get() = buckets.sumOf { it.vectorMarked }
        val failed: Int get() = buckets.count { it.error != null }
    }

    fun count(input: PaimonReadInput): Result {
        val fileRows = input.files.sumOf { it.recordCount ?: 0L }
        val rule = input.rule
        val skipped = input.skippedFiles
        val buckets = input.readFiles.groupBy { it.partition to it.bucket }.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
        if (!input.hasPrimaryKey) {
            // No merge: every file row is a row, less the vectors and the patch files.
            val counted = buckets.map { (scope, files) ->
                val vectors = files.sumOf { f -> input.vectorFor(f.fileName)?.cardinality ?: 0L }
                val partial = files.filter { it.partial }.sumOf { it.recordCount ?: 0L }
                val rows = files.sumOf { it.recordCount ?: 0L }
                BucketCount(scope.first, scope.second, files.size, rows, rows - partial, 0, vectors)
            }
            return Result(input.snapshotId, input.mergeEngine, fromMetadata = true, buckets = counted, bucketsLeft = 0, fileRows = fileRows)
        }
        val keyColumns = input.trimmedPrimaryKeys.map { PaimonRowLookup.KEY_PREFIX + it }
        val toRead = buckets.take(MAX_BUCKETS)
        val vectors = mutableMapOf<String, BitSet?>()
        val counted = toRead.map { (scope, files) ->
            runCatching { countBucket(input, scope.first, scope.second, files, keyColumns, vectors) }
                .onFailure { logger.warn("Could not merge bucket {}: {}", scope, it.message) }
                .getOrElse { BucketCount(scope.first, scope.second, files.size, files.sumOf { f -> f.recordCount ?: 0L }, 0, 0, 0, it.message ?: it.toString()) }
        }
        return Result(
            input.snapshotId, input.mergeEngine, fromMetadata = false, buckets = counted, bucketsLeft = buckets.size - toRead.size,
            fileRows = fileRows, skippedFiles = skipped.size, skippedRows = skipped.sumOf { it.recordCount ?: 0L }, rule = rule.describe(),
        )
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
        val sources = PaimonRowLookup.bucketSources(input, files)
        val latest = PaimonRowLookup.latestPerKeySql(sources, keyColumns, input.rule.removingKinds)
        val rule = input.rule
        // The kinds that, as a key's latest record, remove it under this merge engine — none under
        // first-row, or under ignore-delete; and the keys a read would fail on, where a retraction
        // is rejected, are counted rather than folded.
        val removing = rule.removingKinds.takeIf { it.isNotEmpty() }?.joinToString(", ", "(", ")") ?: "(-1)"
        // `first` is null for a key with no `+I`/`+U` record: every engine but aggregation answers
        // no row for it, whatever its retractions did.
        val (keys, latestRemoving, rejected, insertless) = DuckDb.withConnection { conn ->
            conn.prepareStatement(
                "SELECT count(*), count(*) FILTER (WHERE kind IN $removing), count(*) FILTER (WHERE retracted), " +
                    "count(*) FILTER (WHERE first IS NULL AND kind NOT IN $removing) FROM ($latest)",
            ).use { pstmt ->
                sources.bind(pstmt, 1)
                pstmt.executeQuery().use { rs -> rs.next(); listOf(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)) }
            }
        }
        if (rule.retractionsRejected && rejected > 0) {
            throw IllegalStateException("$rejected keys carry a retraction, which a read of a ${rule.engine} table fails on")
        }
        // Under remove-record-on-sequence-group a `-D` removes by its value, not its kind: the keys
        // holding one are folded record by record, and a key whose last record removed it counts
        // as retracted — unless it never had an insert, which `insertless` already holds.
        val retracted = if (rule.sequenceGroupRemovals.isEmpty()) latestRemoving else {
            val notNull = PaimonRowLookup.groupNullability(input)
            PaimonRowLookup.sequenceGroupRecords(input, sources, keyColumns, casts = emptyList(), keys = emptyList()).values
                .map { PaimonSequenceGroups.fold(it, notNull) }
                .count { it.removedNow && it.hasInsert }.toLong()
        }
        // Keys whose latest record a vector marks: only the files with a vector are asked, and
        // only for the records that are not already retractions, streamed rather than listed.
        val vectored = files.filter { input.vectorFor(it.fileName) != null }
        var marked = 0L
        if (vectored.isNotEmpty()) {
            vectored.firstOrNull { !SampleRowReader.hasRowPositions(it.extension) }?.let {
                throw IllegalStateException("the vector on ${it.fileName} marks positions, which DuckDB numbers in Parquet only")
            }
            val bits = vectored.associate { file ->
                val range = requireNotNull(input.vectorFor(file.fileName))
                file.fileName to vectors.getOrPut(file.fileName) {
                    runCatching { PaimonDeletionVectorReader.readPositions(StorageLocation.pathOf(range.indexLocalPath), range.offset, range.length) }
                        .onFailure { logger.warn("Could not read the vector in {}: {}", range.indexLocalPath, it.message) }
                        .getOrNull()
                }
            }
            if (bits.values.any { it == null }) throw IllegalStateException("a vector in the bucket could not be read")
            val holders = vectored.joinToString(", ") { "?" }
            DuckDb.withConnection { conn ->
                conn.prepareStatement(
                    "SELECT holder, pos FROM ($latest) WHERE kind NOT IN $removing AND holder IN ($holders)",
                ).use { pstmt ->
                    var i = sources.bind(pstmt, 1)
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
        return BucketCount(partition, bucket, files.size, files.sumOf { it.recordCount ?: 0L }, keys, retracted, marked, insertless = if (rule.keyNeedsInsert) insertless else 0)
    }
}
