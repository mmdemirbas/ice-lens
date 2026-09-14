package service

import model.DeleteFileKind
import model.DeletionVector
import model.LookupDataFile
import model.LookupDeleteFile
import model.LookupFileOutcome
import model.RowFate
import model.RowHit
import model.RowLookupInput
import model.RowLookupResult
import model.IcebergSchemaModel
import model.NameMapping
import model.ScanFilter
import model.normalizeFilePath
import model.toSql
import org.slf4j.LoggerFactory

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

    /**
     * Reads the files the filter leaves — every live data file whose normalised recorded path is
     * not in [ruledOut] — and decides each hit. [reads] holds each file's matching rows by local
     * path, for a caller looking the same files up at several snapshots ([RowHistoryTrace]):
     * the rows a file holds do not change with the snapshot listing it, only their fates do.
     */
    fun lookup(
        input: RowLookupInput, filter: ScanFilter, ruledOut: Set<String>,
        reads: MutableMap<String, Result<List<Map<String, Any?>>>> = mutableMapOf(),
    ): RowLookupResult {
        val candidates = input.dataFiles.filter { normalizeFilePath(it.recordedPath) !in ruledOut }
        val toRead = candidates.take(MAX_FILES)
        val predicate = filter.toSql { column -> input.schema?.let { s -> s.idOfPath(column)?.let(s::typeOf) } }
        val vectors = mutableMapOf<String, DeletionVector?>()
        val outcomes = mutableListOf<LookupFileOutcome>()
        val hits = mutableListOf<RowHit>()
        for (file in toRead) {
            val rows = reads.getOrPut(file.localPath) { runCatching { readMatches(file, predicate.sql, predicate.params, input.schema, input.nameMapping) } }
            val error = rows.exceptionOrNull()
            if (error != null) {
                logger.warn("Could not read {}: {}", file.localPath, error.message)
                outcomes += LookupFileOutcome(file.recordedPath, 0, error.message ?: error.toString())
                continue
            }
            val matched = rows.getOrThrow()
            outcomes += LookupFileOutcome(file.recordedPath, matched.size)
            val deletes = input.deletesFor(file.recordedPath)
            matched.forEach { cells ->
                val position = (cells[SampleRowReader.FILE_ROW_NUMBER] as? Number)?.toLong()
                hits += decide(file, position, cells - SampleRowReader.FILE_ROW_NUMBER, deletes, vectors, input.schema, input.nameMapping)
            }
        }
        return RowLookupResult(outcomes, input.dataFiles.size - candidates.size, candidates.size - toRead.size, hits)
    }

    private fun readMatches(file: LookupDataFile, where: String, params: List<String>, schema: IcebergSchemaModel?, mapping: NameMapping?): List<Map<String, Any?>> {
        val (safePath, ext) = SampleRowReader.resolveForQuery(file.localPath)
        // The file under the schema's names, so a column renamed or added since it was written
        // still answers the filter — see FileProjection.
        val source = FileProjection.of(ext, SampleRowReader.fileColumnsOf(file.localPath), schema, mapping, rowNumber = true)
        return DuckDb.withConnection { conn ->
            conn.prepareStatement("SELECT * FROM ${source.sql} WHERE $where LIMIT $MAX_HITS_PER_FILE").use { pstmt ->
                var i = source.bind(pstmt, 1, safePath)
                params.forEach { p -> pstmt.setString(i++, p) }
                pstmt.executeQuery().use { rs ->
                    val meta = rs.metaData
                    val rows = mutableListOf<Map<String, Any?>>()
                    while (rs.next()) rows += (1..meta.columnCount).associate { meta.getColumnName(it) to rs.getObject(it) }
                    rows
                }
            }
        }
    }

    /**
     * One row's fate under [deletes], the delete files a scan pairs with its file — what the
     * row panel asks for a sampled row, with its position and cells already in hand. The same
     * decision the lookup makes for a hit, without the read that found it. [cells] are under
     * the schema's names, and [schema] and [mapping] are what an equality delete file is read
     * under — its own columns are named as the schema named them when it was written.
     */
    fun fateOf(
        file: LookupDataFile, position: Long?, cells: Map<String, Any?>, deletes: List<LookupDeleteFile>,
        schema: IcebergSchemaModel? = null, mapping: NameMapping? = null,
    ): RowHit = decide(file, position, cells, deletes, mutableMapOf(), schema, mapping)

    private fun decide(
        file: LookupDataFile,
        position: Long?,
        cells: Map<String, Any?>,
        deletes: List<LookupDeleteFile>,
        vectors: MutableMap<String, DeletionVector?>,
        schema: IcebergSchemaModel?,
        mapping: NameMapping?,
    ): RowHit {
        val path = file.recordedPath
        if (deletes.isEmpty()) return RowHit(path, position, cells, RowFate.LIVE)
        if (position == null && deletes.any { it.kind != DeleteFileKind.EQUALITY }) {
            return RowHit(path, position, cells, RowFate.UNKNOWN, note = "no position: DuckDB numbers rows in Parquet only")
        }
        // A delete that could not be applied leaves the row undecided: "no delete proved it gone"
        // is not "live" when one of them was never read.
        var note: String? = null
        for (delete in deletes) {
            when (delete.kind) {
                DeleteFileKind.DELETION_VECTOR -> {
                    val vector = vectors.getOrPut(delete.recordedPath) {
                        runCatching {
                            PuffinReader.readDeletionVector(
                                StorageLocation.pathOf(delete.localPath), requireNotNull(delete.contentOffset), requireNotNull(delete.contentSizeInBytes),
                                file.recordedPath, delete.recordCount,
                            )
                        }.onFailure { logger.warn("Could not read the vector in {}: {}", delete.localPath, it.message) }.getOrNull()
                    }
                    if (vector == null) { note = "a vector could not be read"; continue }
                    if (position!! in vector.positions) return RowHit(path, position, cells, RowFate.VECTOR_DELETED, delete.recordedPath)
                    if (vector.truncated) note = "the vector holds more positions than were decoded"
                }
                DeleteFileKind.POSITIONAL -> {
                    val marked = runCatching { positionMarked(delete, file.recordedPath, position!!) }
                        .onFailure { logger.warn("Could not read {}: {}", delete.localPath, it.message) }.getOrNull()
                    if (marked == null) { note = "a positional delete could not be read"; continue }
                    if (marked) return RowHit(path, position, cells, RowFate.POSITION_DELETED, delete.recordedPath)
                }
                DeleteFileKind.EQUALITY -> {
                    if (delete.equalityColumns.isEmpty()) { note = "an equality delete names fields the schema does not"; continue }
                    val matched = runCatching { equalityMatches(delete, cells, schema, mapping) }
                        .onFailure { logger.warn("Could not read {}: {}", delete.localPath, it.message) }.getOrNull()
                    if (matched == null) { note = "an equality delete could not be read"; continue }
                    if (matched) return RowHit(path, position, cells, RowFate.EQUALITY_DELETED, delete.recordedPath)
                }
            }
        }
        return RowHit(path, position, cells, if (note == null) RowFate.LIVE else RowFate.UNKNOWN, note = note)
    }

    /** Whether the positional delete holds `(file_path, pos)` — the path as the manifest recorded the data file. */
    private fun positionMarked(delete: LookupDeleteFile, recordedDataPath: String, position: Long): Boolean {
        val (safePath, ext) = SampleRowReader.resolveForQuery(delete.localPath)
        return DuckDb.withConnection { conn ->
            conn.prepareStatement("SELECT 1 FROM ${SampleRowReader.readerCall(ext)} WHERE file_path = ? AND pos = ? LIMIT 1").use { pstmt ->
                pstmt.setString(1, safePath)
                pstmt.setString(2, recordedDataPath)
                pstmt.setLong(3, position)
                pstmt.executeQuery().use { it.next() }
            }
        }
    }

    /**
     * Whether the equality delete holds a row equal to [cells] on every one of its columns; a
     * null matches only a null. The delete file is read projected onto the schema like a data
     * file, because its columns are named as the schema named them when it was written and a
     * scan matches it by field id: `eqren`'s delete holds `name`, the table calls it `label`,
     * and asked for `label` by name the file answers with an error rather than the row.
     */
    private fun equalityMatches(delete: LookupDeleteFile, cells: Map<String, Any?>, schema: IcebergSchemaModel?, mapping: NameMapping?): Boolean {
        val (safePath, ext) = SampleRowReader.resolveForQuery(delete.localPath)
        val source = FileProjection.of(ext, SampleRowReader.fileColumnsOf(delete.localPath), schema, mapping)
        val where = delete.equalityColumns.joinToString(" AND ") { column ->
            val quoted = model.quoteSqlIdentifier(column)
            if (cells[column] == null) "$quoted IS NULL" else "CAST($quoted AS VARCHAR) = ?"
        }
        val bound = delete.equalityColumns.filter { cells[it] != null }.map { cells[it].toString() }
        return DuckDb.withConnection { conn ->
            conn.prepareStatement("SELECT 1 FROM ${source.sql} WHERE $where LIMIT 1").use { pstmt ->
                var i = source.bind(pstmt, 1, safePath)
                bound.forEach { v -> pstmt.setString(i++, v) }
                pstmt.executeQuery().use { it.next() }
            }
        }
    }
}
