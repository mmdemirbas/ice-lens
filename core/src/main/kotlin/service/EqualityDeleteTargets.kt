package service

import model.DeleteFileKind
import model.DeleteReach
import model.LookupDataFile
import model.LookupDeleteFile
import model.RowLookupInput
import model.normalizeFilePath
import model.quoteSqlIdentifier
import org.slf4j.LoggerFactory

/**
 * What an equality delete file removes, counted by reading it against each data file the pairing
 * leaves for it — [SampleRowReader.queryPositionalDeleteTargets]'s twin for the delete kind that
 * names no target at all.
 *
 * A positional delete lists its targets row by row, so one `GROUP BY file_path` over its own rows
 * says which files it touches. An equality delete holds values, and which rows those values match
 * is a fact about the data files: the metadata can rule a file *out* — sequence, partition, bounds
 * ([model.deleteReach]) — and never in, so every candidate it leaves at [DeleteReach.mayReach] is
 * opened and asked. Per candidate, one statement: the data file and the delete file each projected
 * onto the snapshot's schema by field id ([FileProjection], so a column renamed since the delete
 * was written still joins — `eqren`), and the rows of the one that some row of the other equals
 * on every equality column counted, nulls matching nulls as Iceberg compares them (`IS NOT
 * DISTINCT FROM`). It is the same join [LiveRowCount] runs from the data file's end, asked from
 * the delete's, which is the question a reader standing on a delete file has: does this still
 * remove anything, and from where. A delete every candidate answers zero for removes nothing at
 * this snapshot — the equality-delete reading of *dangling*, which the metadata alone cannot
 * reach (`fup`'s commit-1 delete is ruled out by sequence; `eqdel`'s removes one row from each of
 * two files). Behind a click, [MAX_FILES] candidates opened and the rest said.
 */
object EqualityDeleteTargets {
    private val logger = LoggerFactory.getLogger(EqualityDeleteTargets::class.java)

    /** Candidate data files opened in one run. */
    const val MAX_FILES = 64

    data class FileMatch(
        val file: LookupDataFile,
        /** Rows of the data file some row of the delete equals on every equality column; null where it was not read. */
        val matched: Long?,
        val error: String? = null,
    )

    data class Result(
        val delete: LookupDeleteFile,
        val reach: DeleteReach,
        val files: List<FileMatch>,
        /** Candidates [MAX_FILES] left unopened. */
        val filesLeft: Int,
    ) {
        val matched: Long get() = files.sumOf { it.matched ?: 0L }
        val filesWithMatches: Int get() = files.count { (it.matched ?: 0L) > 0L }
        val failed: Int get() = files.count { it.error != null }
        /** True when every candidate was read and none holds a matching row; null while any was not. */
        val removesNothing: Boolean? get() = if (failed == 0 && filesLeft == 0) matched == 0L else null
    }

    /**
     * The delete file with key [deleteKey] against the candidates the snapshot's pairing leaves
     * for it, or null when the snapshot does not pair it — not among its live delete files.
     */
    fun count(input: RowLookupInput, deleteKey: String): Result? {
        val delete = input.deleteFiles.firstOrNull { it.key == deleteKey } ?: return null
        val reach = input.reach.firstOrNull { it.deleteKey == deleteKey } ?: return null
        require(delete.kind == DeleteFileKind.EQUALITY) { "${delete.recordedPath.substringAfterLast('/')} is a ${delete.kind} delete, whose targets its own rows name" }
        if (delete.equalityColumns.isEmpty()) {
            throw IllegalStateException("${delete.recordedPath.substringAfterLast('/')} names equality fields the schema does not")
        }
        val byPath = input.dataFiles.associateBy { normalizeFilePath(it.recordedPath) }
        val candidates = (reach.reaches + reach.mayReach).mapNotNull { byPath[normalizeFilePath(it)] }
        var opened = 0
        var left = 0
        val matches = candidates.map { file ->
            if (opened >= MAX_FILES) {
                left++
                FileMatch(file, null, "left unopened by the cap")
            } else {
                opened++
                runCatching { FileMatch(file, matchedRows(delete, file, input)) }
                    .onFailure { logger.warn("Could not count {} against {}: {}", delete.localPath, file.localPath, it.message) }
                    .getOrElse { FileMatch(file, null, it.message ?: it.toString()) }
            }
        }
        return Result(delete, reach, matches, left)
    }

    private fun matchedRows(delete: LookupDeleteFile, file: LookupDataFile, input: RowLookupInput): Long {
        val (dataPath, ext) = SampleRowReader.resolveForQuery(file.localPath)
        val (deletePath, deleteExt) = SampleRowReader.resolveForQuery(delete.localPath)
        val data = FileProjection.of(ext, SampleRowReader.fileColumnTreeOf(file.localPath), input.schema, input.nameMapping, alias = "d")
        val equality = FileProjection.of(deleteExt, SampleRowReader.fileColumnTreeOf(delete.localPath), input.schema, input.nameMapping, alias = "e")
        val on = delete.equalityColumns.joinToString(" AND ") { column ->
            "e.${quoteSqlIdentifier(column)} IS NOT DISTINCT FROM d.${quoteSqlIdentifier(column)}"
        }
        val sql = "SELECT count(*) FROM ${data.sql} WHERE EXISTS (SELECT 1 FROM ${equality.sql} WHERE $on)"
        return DuckDb.withConnection { conn ->
            conn.prepareStatement(sql).use { pstmt ->
                val next = data.bind(pstmt, 1, dataPath)
                equality.bind(pstmt, next, deletePath)
                pstmt.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }
        }
    }
}
