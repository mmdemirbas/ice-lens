package service

import model.DeleteFileKind
import model.DeletionVector
import model.LookupDataFile
import model.LookupDeleteFile
import model.RowLookupInput
import model.ScanFilter
import model.normalizeFilePath
import model.toSql
import org.slf4j.LoggerFactory
import java.nio.file.Paths

/**
 * Finding rows and deciding their fate, over the files a filter leaves — see [RowLookupInput]
 * for what it reads and why. Each data file is one DuckDB statement with the filter as its
 * `WHERE` and every literal bound; each hit is then put to the delete files a scan pairs with
 * its file, in the order a scan would find them decisive: a vector by the position's bit, a
 * positional delete by `(file_path, pos)` with the path as the manifest recorded it, an
 * equality delete by the row's own values in its `equality_ids` columns — the one delete kind
 * the metadata cannot resolve and the bytes can. A hit no delete claims is live.
 *
 * Only a Parquet file has positions to decide by (`file_row_number`); an ORC or Avro hit is
 * reported with its fate unknown rather than guessed. The caps are stated on the result.
 */
object RowLookup {
    private val logger = LoggerFactory.getLogger(RowLookup::class.java)

    /** Data files opened in one lookup; the filter is supposed to have narrowed them. */
    const val MAX_FILES = 64
    /** Hits kept per data file. */
    const val MAX_HITS_PER_FILE = 20

    enum class RowFate(val label: String) {
        LIVE("live"),
        VECTOR_DELETED("deleted by a vector"),
        POSITION_DELETED("deleted by position"),
        EQUALITY_DELETED("deleted by equality"),
        UNKNOWN("not decided"),
    }

    data class Hit(
        val file: LookupDataFile,
        /** `file_row_number`; null for a format DuckDB gives no position for. */
        val position: Long?,
        val cells: Map<String, Any?>,
        val fate: RowFate,
        /** The delete file that decided it, when one did. */
        val by: String? = null,
        val note: String? = null,
    )

    data class FileOutcome(val file: LookupDataFile, val hits: Int, val error: String? = null)

    data class Result(
        val filesRead: List<FileOutcome>,
        /** Live data files the filter ruled out before any was opened. */
        val filesRuledOut: Int,
        /** Live data files left unread by [MAX_FILES]. */
        val filesLeft: Int,
        val hits: List<Hit>,
    ) {
        val live: Int get() = hits.count { it.fate == RowFate.LIVE }
        val deleted: Int get() = hits.count { it.fate != RowFate.LIVE && it.fate != RowFate.UNKNOWN }
    }

    /**
     * Reads the files the filter leaves — every live data file whose normalised recorded path is
     * not in [ruledOut] — and decides each hit.
     */
    fun lookup(input: RowLookupInput, filter: ScanFilter, ruledOut: Set<String>): Result {
        val candidates = input.dataFiles.filter { normalizeFilePath(it.recordedPath) !in ruledOut }
        val toRead = candidates.take(MAX_FILES)
        val predicate = filter.toSql { column -> input.schema?.struct?.fields?.firstOrNull { it.name == column }?.type }
        val vectors = mutableMapOf<String, DeletionVector?>()
        val outcomes = mutableListOf<FileOutcome>()
        val hits = mutableListOf<Hit>()
        for (file in toRead) {
            val rows = runCatching { readMatches(file, predicate.sql, predicate.params) }
            val error = rows.exceptionOrNull()
            if (error != null) {
                logger.warn("Could not read {}: {}", file.localPath, error.message)
                outcomes += FileOutcome(file, 0, error.message ?: error.toString())
                continue
            }
            val matched = rows.getOrThrow()
            outcomes += FileOutcome(file, matched.size)
            val deletes = input.deletesFor(file.recordedPath)
            matched.forEach { cells ->
                val position = (cells[SampleRowReader.FILE_ROW_NUMBER] as? Number)?.toLong()
                hits += decide(file, position, cells - SampleRowReader.FILE_ROW_NUMBER, deletes, vectors)
            }
        }
        return Result(outcomes, candidates.size.let { input.dataFiles.size - it }, candidates.size - toRead.size, hits)
    }

    private fun readMatches(file: LookupDataFile, where: String, params: List<String>): List<Map<String, Any?>> {
        val (safePath, ext) = SampleRowReader.resolveForQuery(file.localPath)
        val source = if (ext == "parquet") "read_parquet(?, file_row_number = true)" else "read_parquet(?)"
        return DuckDb.withConnection { conn ->
            conn.prepareStatement("SELECT * FROM $source WHERE $where LIMIT $MAX_HITS_PER_FILE").use { pstmt ->
                pstmt.setString(1, safePath)
                params.forEachIndexed { i, p -> pstmt.setString(i + 2, p) }
                pstmt.executeQuery().use { rs ->
                    val meta = rs.metaData
                    val rows = mutableListOf<Map<String, Any?>>()
                    while (rs.next()) rows += (1..meta.columnCount).associate { meta.getColumnName(it) to rs.getObject(it) }
                    rows
                }
            }
        }
    }

    private fun decide(
        file: LookupDataFile,
        position: Long?,
        cells: Map<String, Any?>,
        deletes: List<LookupDeleteFile>,
        vectors: MutableMap<String, DeletionVector?>,
    ): Hit {
        if (deletes.isEmpty()) return Hit(file, position, cells, RowFate.LIVE)
        if (position == null && deletes.any { it.kind != DeleteFileKind.EQUALITY }) {
            return Hit(file, position, cells, RowFate.UNKNOWN, note = "no position: DuckDB numbers rows in Parquet only")
        }
        var note: String? = null
        for (delete in deletes) {
            when (delete.kind) {
                DeleteFileKind.DELETION_VECTOR -> {
                    val vector = vectors.getOrPut(delete.recordedPath) {
                        runCatching {
                            PuffinReader.readDeletionVector(
                                Paths.get(delete.localPath), requireNotNull(delete.contentOffset), requireNotNull(delete.contentSizeInBytes),
                                file.recordedPath, delete.recordCount,
                            )
                        }.onFailure { logger.warn("Could not read the vector in {}: {}", delete.localPath, it.message) }.getOrNull()
                    }
                    if (vector == null) { note = "a vector could not be read"; continue }
                    if (position!! in vector.positions) return Hit(file, position, cells, RowFate.VECTOR_DELETED, delete.recordedPath)
                    if (vector.truncated) note = "the vector holds more positions than were decoded"
                }
                DeleteFileKind.POSITIONAL -> {
                    val marked = runCatching { positionMarked(delete, file.recordedPath, position!!) }
                        .onFailure { logger.warn("Could not read {}: {}", delete.localPath, it.message) }.getOrNull()
                    if (marked == null) { note = "a positional delete could not be read"; continue }
                    if (marked) return Hit(file, position, cells, RowFate.POSITION_DELETED, delete.recordedPath)
                }
                DeleteFileKind.EQUALITY -> {
                    if (delete.equalityColumns.isEmpty()) { note = "an equality delete names fields the schema does not"; continue }
                    val matched = runCatching { equalityMatches(delete, cells) }
                        .onFailure { logger.warn("Could not read {}: {}", delete.localPath, it.message) }.getOrNull()
                    if (matched == null) { note = "an equality delete could not be read"; continue }
                    if (matched) return Hit(file, position, cells, RowFate.EQUALITY_DELETED, delete.recordedPath)
                }
            }
        }
        return Hit(file, position, cells, RowFate.LIVE, note = note)
    }

    /** Whether the positional delete holds `(file_path, pos)` — the path as the manifest recorded the data file. */
    private fun positionMarked(delete: LookupDeleteFile, recordedDataPath: String, position: Long): Boolean {
        val (safePath, _) = SampleRowReader.resolveForQuery(delete.localPath)
        return DuckDb.withConnection { conn ->
            conn.prepareStatement("SELECT 1 FROM read_parquet(?) WHERE file_path = ? AND pos = ? LIMIT 1").use { pstmt ->
                pstmt.setString(1, safePath)
                pstmt.setString(2, recordedDataPath)
                pstmt.setLong(3, position)
                pstmt.executeQuery().use { it.next() }
            }
        }
    }

    /** Whether the equality delete holds a row equal to [cells] on every one of its columns; a null matches only a null. */
    private fun equalityMatches(delete: LookupDeleteFile, cells: Map<String, Any?>): Boolean {
        val (safePath, _) = SampleRowReader.resolveForQuery(delete.localPath)
        val where = delete.equalityColumns.joinToString(" AND ") { column ->
            val quoted = model.quoteSqlIdentifier(column)
            if (cells[column] == null) "$quoted IS NULL" else "CAST($quoted AS VARCHAR) = ?"
        }
        val bound = delete.equalityColumns.filter { cells[it] != null }.map { cells[it].toString() }
        return DuckDb.withConnection { conn ->
            conn.prepareStatement("SELECT 1 FROM read_parquet(?) WHERE $where LIMIT 1").use { pstmt ->
                pstmt.setString(1, safePath)
                bound.forEachIndexed { i, v -> pstmt.setString(i + 2, v) }
                pstmt.executeQuery().use { it.next() }
            }
        }
    }
}
