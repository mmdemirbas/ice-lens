package service

import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger(SampleRowReader::class.java)

/** Allowed file extensions for data file queries. */
private val ALLOWED_DATA_FILE_EXTENSIONS = setOf("parquet", "orc", "avro")

/**
 * What one positional delete file removes from one data file.
 *
 * [positions] is every row the delete file names in that data file, not a sample — the counting is
 * done by DuckDB, so a delete file with four hundred thousand positions costs the same round trip
 * as one with a single position and nothing large crosses back.
 *
 * [lowestPosition] and [highestPosition] are there because a delete file's *shape* is the
 * interesting part after its size: positions clustered in one run mean a range of the data file
 * was deleted, spread across the whole file means scattered single-row deletes, and the two have
 * very different costs at read time.
 */
data class PositionalDeleteTally(
    val dataFilePath: String,
    val positions: Long,
    val lowestPosition: Long,
    val highestPosition: Long,
)

/**
 * Reads sample rows from data files using DuckDB.
 *
 * DuckDB supports Parquet, ORC, and Avro via `read_parquet()` which auto-detects format.
 */
object SampleRowReader {

    /**
     * The column DuckDB adds for a row's physical position in its Parquet file.
     *
     * A file of its own carrying a column by this name would collide with it; nothing in Iceberg
     * writes one, and the alternative — a generated alias — cannot be told apart from a real
     * column either.
     */
    const val FILE_ROW_NUMBER = "file_row_number"

    /**
     * How many delete files one [queryDeletedRowCount] will open in a single statement.
     *
     * A cap rather than a limit anybody should reach: the candidates are already narrowed by the
     * sequence and target rules, and a data file that a hundred delete files can reach is a table
     * whose problem is not this query.
     */
    const val MAX_DELETE_FILES_PER_COUNT = 64

    /**
     * Closes the shared DuckDB connection if open. Safe to call multiple times.
     *
     * The connection itself moved to [DuckDb], because once a table can live in object storage the
     * *metadata* is read through DuckDB too, and two connections would be two places to configure
     * one set of credentials.
     */
    fun closeConnection() = DuckDb.close()

    /**
     * The path DuckDB should be given for [filePath], with what can be checked, checked.
     *
     * A local file is canonicalised and required to be a regular file — that resolves `..` before
     * the extension is looked at, so a path that climbs out of the table cannot arrive wearing a
     * `.parquet` suffix. A remote one cannot be canonicalised and is not stat-ed either: an
     * existence check would be a round trip to learn what the read is about to report anyway. What
     * *is* still checked is the extension, because it decides which query is built.
     */
    internal fun resolveForQuery(filePath: String): Pair<String, String> {
        if (StorageLocation.isRemote(filePath)) {
            val name = filePath.substringAfterLast('/')
            val ext = name.substringAfterLast('.', "").lowercase()
            require(ext in ALLOWED_DATA_FILE_EXTENSIONS) {
                "Unsupported file extension '$ext'. Allowed: $ALLOWED_DATA_FILE_EXTENSIONS"
            }
            return filePath to ext
        }
        val canonicalFile = File(filePath).canonicalFile
        require(canonicalFile.isFile) { "Not a regular file: $canonicalFile" }
        val ext = canonicalFile.extension.lowercase()
        require(ext in ALLOWED_DATA_FILE_EXTENSIONS) {
            "Unsupported file extension '$ext'. Allowed: $ALLOWED_DATA_FILE_EXTENSIONS"
        }
        return canonicalFile.path.replace("\\", "/") to ext
    }

    /**
     * Queries sample rows from a data file (Parquet, ORC, or Avro) using DuckDB.
     *
     * For a Parquet file each row map also carries [FILE_ROW_NUMBER] — DuckDB's own count of the
     * row's physical position in the file, which is what an Iceberg positional delete and a v3
     * deletion vector both address. Callers that show the row to a person should lift it out of
     * the map rather than draw it as a column.
     *
     * @param filePath path to the data file on the local filesystem
     * @return list of row maps (column name → value), up to [GraphLayoutService.MAX_PARQUET_SAMPLE_ROWS] rows
     * @throws IllegalArgumentException if the file doesn't exist or has an unsupported extension
     */
    fun querySampleRows(filePath: String): List<Map<String, Any>> {
        val (safePath, ext) = resolveForQuery(filePath)

        return DuckDb.withConnection { conn ->
            // Parquet gets `file_row_number`, which is the row's physical position in the file
            // and so the coordinate a positional delete and a deletion vector both address. It is
            // asked for rather than inferred from the result order: a scan may return rows in any
            // order it likes, and the reader would have no way to tell that it had.
            val sql = if (ext == "parquet") {
                "SELECT * FROM read_parquet(?, file_row_number = true) " +
                    "LIMIT ${GraphLayoutService.MAX_PARQUET_SAMPLE_ROWS}"
            } else {
                "SELECT * FROM read_parquet(?) LIMIT ${GraphLayoutService.MAX_PARQUET_SAMPLE_ROWS}"
            }
            conn.prepareStatement(sql).use { pstmt ->
                pstmt.setString(1, safePath)
                val rs = pstmt.executeQuery()
                val meta = rs.metaData
                val colCount = meta.columnCount
                val rows = mutableListOf<Map<String, Any>>()

                while (rs.next()) {
                    val row = mutableMapOf<String, Any>()
                    for (i in 1..colCount) {
                        row[meta.getColumnName(i)] = rs.getObject(i) ?: "null"
                    }
                    rows.add(row)
                }
                rs.close()
                logger.debug("Sample rows queried: {} rows from {}", rows.size, safePath)
                rows
            }
        }
    }

    /**
     * Which data files a positional delete file deletes from, and how many rows out of each.
     *
     * Nothing in the metadata answers this. A manifest entry for a positional delete file records
     * its `record_count` — how many positions it holds — and, since v2.1, an optional
     * `referenced_data_file` when the writer happened to produce one file per target. It never
     * records the breakdown, because the breakdown is the file's contents: one row per deleted
     * position, `file_path` naming the data file and `pos` the row in it. So "3 delete files" can
     * only become "and they remove 412 rows from these two files" by opening them.
     *
     * **Which is why this is behind an explicit action and not on the graph-build path.** A table
     * with a thousand delete files would pay a thousand file opens to draw a graph, which is the
     * cost aggregation exists to avoid.
     *
     * The aggregation is DuckDB's, not this process's: what comes back is one row per targeted
     * data file however many positions the file holds. Reading the rows and grouping them here
     * would pull the whole delete file into memory to produce a handful of counts.
     *
     * `file_path` and `pos` are the Iceberg spec's own names for the two required fields of a
     * positional delete file (field ids 2147483546 and 2147483545). A file missing them is not a
     * positional delete file, and the SQL error says so more usefully than a silent empty result.
     */
    /**
     * How many distinct rows of one data file a set of positional delete files marks.
     *
     * The other direction from [queryPositionalDeleteTargets], and the one that answers the
     * question a reader standing on a data file has: not "which files does this delete from" but
     * "how many of *my* rows are gone". It is the number that makes a live row count possible at
     * all — `record_count` counts rows before deletes, and subtracting a delete file's own
     * `record_count` is wrong whenever a delete is dangling, which is why the merge-on-read
     * fixture's naive subtraction gives 3 where the table holds 5.
     *
     * **Distinct, and across all the files at once.** Two delete files may mark the same position
     * of the same data file — a second commit deleting a row an earlier one already did — so
     * summing per-file counts can exceed the row count, which is a plausible wrong number of
     * exactly the kind a reader cannot catch. One `UNION ALL` over the candidates and one
     * `count(DISTINCT pos)` gives the union, and DuckDB does it: what crosses back is a long.
     *
     * [dataFilePath] is the path as the **table recorded it**, not where the file is read from. A
     * delete file's `file_path` column holds the writer's own spelling, and so does the data
     * file's manifest entry, so the two agree even for a table copied down from object storage
     * where neither matches the local path.
     *
     * The candidate list is expected to be short — [model.deleteCandidatesFor] narrows it by the
     * sequence and target rules before anything is opened — and [MAX_DELETE_FILES_PER_COUNT] caps
     * it rather than building SQL of unbounded length.
     */
    fun queryDeletedRowCount(deleteFilePaths: List<String>, dataFilePath: String): Long {
        if (deleteFilePaths.isEmpty()) return 0L
        require(deleteFilePaths.size <= MAX_DELETE_FILES_PER_COUNT) {
            "Too many delete files to count at once: ${deleteFilePaths.size}"
        }
        val resolved = deleteFilePaths.map { resolveForQuery(it).first }

        return DuckDb.withConnection { conn ->
            // One branch per delete file, every value bound. The text is generated because
            // `read_parquet` takes a file per call, not because anything here is interpolated.
            val branches = resolved.joinToString(" UNION ALL ") {
                "SELECT pos FROM read_parquet(?) WHERE file_path = ?"
            }
            conn.prepareStatement("SELECT count(DISTINCT pos) FROM ($branches)").use { pstmt ->
                resolved.forEachIndexed { index, path ->
                    pstmt.setString(index * 2 + 1, path)
                    pstmt.setString(index * 2 + 2, dataFilePath)
                }
                pstmt.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }
        }
    }

    fun queryPositionalDeleteTargets(filePath: String): List<PositionalDeleteTally> {
        val (safePath, _) = resolveForQuery(filePath)

        return DuckDb.withConnection { conn ->
            val sql = "SELECT file_path, count(*) AS positions, min(pos) AS lowest, max(pos) AS highest " +
                "FROM read_parquet(?) GROUP BY file_path ORDER BY positions DESC, file_path"
            conn.prepareStatement(sql).use { pstmt ->
                pstmt.setString(1, safePath)
                pstmt.executeQuery().use { rs ->
                    val tallies = mutableListOf<PositionalDeleteTally>()
                    while (rs.next()) {
                        tallies += PositionalDeleteTally(
                            dataFilePath = rs.getString("file_path") ?: "",
                            positions = rs.getLong("positions"),
                            lowestPosition = rs.getLong("lowest"),
                            highestPosition = rs.getLong("highest"),
                        )
                    }
                    logger.debug("Delete targets queried: {} data files from {}", tallies.size, safePath)
                    tallies
                }
            }
        }
    }
}

/** @see SampleRowReader */
@Deprecated("Renamed to SampleRowReader", ReplaceWith("SampleRowReader"))
val ParquetReader = SampleRowReader
