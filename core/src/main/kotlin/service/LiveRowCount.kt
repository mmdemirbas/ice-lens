package service

import model.DeleteFileKind
import model.LookupDataFile
import model.LookupDeleteFile
import model.RowLookupInput
import model.quoteSqlIdentifier
import org.slf4j.LoggerFactory
import java.util.BitSet

/**
 * The rows a read of an Iceberg snapshot returns — `SELECT count(*)` as of it — which the
 * metadata states only before the delete files: `total-records` and every `record_count` count
 * rows as written, and a merge-on-read delete removes rows without touching either. Subtracting
 * the delete files' own `record_count` is wrong the moment one is dangling, which `mor` has two
 * of; the answer is per data file, over the delete files the scan pairs with it.
 *
 * A data file no delete reaches contributes its `record_count` unopened. A file only a vector
 * reaches contributes `record_count` less the vector's cardinality, decoded exactly and unopened
 * too — a vector's positions are distinct by construction. A file a positional or an equality
 * delete reaches is opened once: DuckDB streams back the positions the positional deletes name
 * for it (`file_path` as the manifest recorded it) or an equality delete's rows match on the
 * delete's columns, nulls matching nulls the way Iceberg compares them; those positions and the
 * vector's, if any, go into one bit set, because a row two deletes both remove is one row gone.
 * The pairing carries the sequence rule, so a delete written before the file was is never
 * applied to it. Behind a click, capped at [MAX_FILES] files opened, and said so.
 */
object LiveRowCount {
    private val logger = LoggerFactory.getLogger(LiveRowCount::class.java)

    /** Data files opened in one run; files no delete reaches and files only a vector reaches cost nothing. */
    const val MAX_FILES = 256

    data class FileCount(
        val file: LookupDataFile,
        val recordCount: Long,
        val removed: Long,
        /** The delete kinds that reached it, as the pairing found them. */
        val kinds: Set<DeleteFileKind>,
        /** Whether the file was opened, or the answer came from the metadata and the vector alone. */
        val opened: Boolean,
        val error: String? = null,
    ) {
        val live: Long get() = recordCount - removed
    }

    data class Result(
        val snapshotId: Long?,
        val files: List<FileCount>,
        /** Data files a delete reaches that [MAX_FILES] left unopened. */
        val filesLeft: Int,
    ) {
        val recordCount: Long get() = files.sumOf { it.recordCount }
        val removed: Long get() = files.sumOf { it.removed }
        val reached: Int get() = files.count { it.kinds.isNotEmpty() }
        val opened: Int get() = files.count { it.opened }
        val failed: Int get() = files.count { it.error != null }
        /** Null while a file could not be read or was left unopened: a sum with a hole is not a count. */
        val live: Long? get() = if (failed == 0 && filesLeft == 0) recordCount - removed else null
    }

    fun count(input: RowLookupInput): Result {
        val vectors = mutableMapOf<String, BitSet?>()
        var opened = 0
        var left = 0
        val counted = input.dataFiles.map { file ->
            val deletes = input.deletesFor(file.recordedPath)
            val kinds = deletes.map { it.kind }.toSet()
            val records = file.recordCount ?: 0L
            when {
                deletes.isEmpty() -> FileCount(file, records, 0, kinds, opened = false)
                kinds == setOf(DeleteFileKind.DELETION_VECTOR) -> {
                    val marked = deletes.sumOf { vectorCardinality(it, file) ?: -1L }
                    if (marked < 0) FileCount(file, records, 0, kinds, opened = false, error = "a vector could not be read")
                    else FileCount(file, records, marked, kinds, opened = false)
                }
                opened >= MAX_FILES -> { left++; FileCount(file, records, 0, kinds, opened = false, error = "left unopened by the cap") }
                else -> {
                    opened++
                    runCatching { FileCount(file, records, removedFrom(file, deletes, vectors), kinds, opened = true) }
                        .onFailure { logger.warn("Could not count {}: {}", file.localPath, it.message) }
                        .getOrElse { FileCount(file, records, 0, kinds, opened = true, error = it.message ?: it.toString()) }
                }
            }
        }
        return Result(input.snapshotId, counted, left)
    }

    private fun vectorCardinality(delete: LookupDeleteFile, file: LookupDataFile): Long? =
        runCatching {
            PuffinReader.readDeletionVector(
                StorageLocation.pathOf(delete.localPath), requireNotNull(delete.contentOffset), requireNotNull(delete.contentSizeInBytes),
                file.recordedPath, delete.recordCount,
            ).cardinality
        }.onFailure { logger.warn("Could not read the vector in {}: {}", delete.localPath, it.message) }.getOrNull()

    /** The distinct positions every reaching delete removes from one data file, the file opened once. */
    private fun removedFrom(file: LookupDataFile, deletes: List<LookupDeleteFile>, vectors: MutableMap<String, BitSet?>): Long {
        val (dataPath, ext) = SampleRowReader.resolveForQuery(file.localPath)
        require(ext == "parquet") { "positions are known for Parquet only; this file is $ext" }
        val bits = BitSet()
        deletes.filter { it.kind == DeleteFileKind.DELETION_VECTOR }.forEach { vector ->
            val positions = vectors.getOrPut(vector.recordedPath) {
                runCatching { PuffinReader.readDeletionVectorPositions(StorageLocation.pathOf(vector.localPath), requireNotNull(vector.contentOffset), requireNotNull(vector.contentSizeInBytes)) }
                    .onFailure { logger.warn("Could not read the vector in {}: {}", vector.localPath, it.message) }
                    .getOrNull()
            } ?: throw IllegalStateException("the vector in ${vector.recordedPath.substringAfterLast('/')} could not be read")
            bits.or(positions)
        }
        val positional = deletes.filter { it.kind == DeleteFileKind.POSITIONAL }
        val equality = deletes.filter { it.kind == DeleteFileKind.EQUALITY }
        equality.firstOrNull { it.equalityColumns.isEmpty() }?.let {
            throw IllegalStateException("${it.recordedPath.substringAfterLast('/')} names equality fields the schema does not")
        }
        if (positional.isEmpty() && equality.isEmpty()) return bits.cardinality().toLong()

        val conditions = mutableListOf<String>()
        if (positional.isNotEmpty()) {
            val branches = positional.joinToString(" UNION ALL ") { "SELECT pos FROM read_parquet(?, hive_partitioning = false) WHERE file_path = ?" }
            conditions += "d.${SampleRowReader.FILE_ROW_NUMBER} IN ($branches)"
        }
        equality.forEach { delete ->
            val on = delete.equalityColumns.joinToString(" AND ") { column ->
                "e.${quoteSqlIdentifier(column)} IS NOT DISTINCT FROM d.${quoteSqlIdentifier(column)}"
            }
            conditions += "EXISTS (SELECT 1 FROM read_parquet(?, hive_partitioning = false) e WHERE $on)"
        }
        val sql = "SELECT d.${SampleRowReader.FILE_ROW_NUMBER} FROM read_parquet(?, file_row_number = true, hive_partitioning = false) d " +
            "WHERE ${conditions.joinToString(" OR ")}"
        DuckDb.withConnection { conn ->
            conn.prepareStatement(sql).use { pstmt ->
                var i = 1
                pstmt.setString(i++, dataPath)
                positional.forEach {
                    pstmt.setString(i++, SampleRowReader.resolveForQuery(it.localPath).first)
                    pstmt.setString(i++, file.recordedPath)
                }
                equality.forEach { pstmt.setString(i++, SampleRowReader.resolveForQuery(it.localPath).first) }
                pstmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val position = rs.getLong(1)
                        if (position < 0 || position > Int.MAX_VALUE) throw IllegalStateException("position $position is past what a bit set holds")
                        bits.set(position.toInt())
                    }
                }
            }
        }
        return bits.cardinality().toLong()
    }
}
