package service

import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

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

    init {
        Class.forName("org.duckdb.DuckDBDriver")
    }

    @Volatile private var connection: Connection? = null
    private val lock = Any()

    /**
     * Returns a live DuckDB connection. Must be called while holding [lock] —
     * the function reads and writes [connection] without re-acquiring the lock,
     * so callers must serialize access. [querySampleRows] does this correctly;
     * future callers should not invoke this directly outside the synchronized block.
     */
    private fun getConnection(): Connection {
        val conn = connection
        if (conn != null && !conn.isClosed) return conn
        logger.debug("Creating new DuckDB connection")
        val newConn = DriverManager.getConnection("jdbc:duckdb:")
        connection = newConn
        return newConn
    }

    /** Closes the DuckDB connection if open. Safe to call multiple times. */
    fun closeConnection() {
        synchronized(lock) {
            connection?.let { conn ->
                runCatching { conn.close() }
                    .onFailure { logger.warn("Error closing DuckDB connection: {}", it.message) }
            }
            connection = null
        }
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
        val file = File(filePath)
        val canonicalFile = file.canonicalFile

        require(canonicalFile.isFile) { "Not a regular file: $canonicalFile" }
        val ext = canonicalFile.extension.lowercase()
        require(ext in ALLOWED_DATA_FILE_EXTENSIONS) {
            "Unsupported file extension '$ext'. Allowed: $ALLOWED_DATA_FILE_EXTENSIONS"
        }

        val safePath = canonicalFile.path.replace("\\", "/")

        synchronized(lock) {
            val conn = try {
                getConnection()
            } catch (e: Exception) {
                logger.error("DuckDB connection failed, resetting: {}", e.message)
                runCatching { connection?.close() }
                connection = null
                getConnection() // retry once
            }

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
                return rows
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
    fun queryPositionalDeleteTargets(filePath: String): List<PositionalDeleteTally> {
        val canonicalFile = File(filePath).canonicalFile
        require(canonicalFile.isFile) { "Not a regular file: $canonicalFile" }
        val ext = canonicalFile.extension.lowercase()
        require(ext in ALLOWED_DATA_FILE_EXTENSIONS) {
            "Unsupported file extension '$ext'. Allowed: $ALLOWED_DATA_FILE_EXTENSIONS"
        }
        val safePath = canonicalFile.path.replace("\\", "/")

        synchronized(lock) {
            val conn = getConnection()
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
                    return tallies
                }
            }
        }
    }
}

/** @see SampleRowReader */
@Deprecated("Renamed to SampleRowReader", ReplaceWith("SampleRowReader"))
val ParquetReader = SampleRowReader
