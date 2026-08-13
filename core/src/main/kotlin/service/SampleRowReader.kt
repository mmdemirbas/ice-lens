package service

import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

private val logger = LoggerFactory.getLogger(SampleRowReader::class.java)

/** Allowed file extensions for data file queries. */
private val ALLOWED_DATA_FILE_EXTENSIONS = setOf("parquet", "orc", "avro")

/**
 * Reads sample rows from data files using DuckDB.
 *
 * DuckDB supports Parquet, ORC, and Avro via `read_parquet()` which auto-detects format.
 */
object SampleRowReader {

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

            val sql = "SELECT * FROM read_parquet(?) LIMIT ${GraphLayoutService.MAX_PARQUET_SAMPLE_ROWS}"
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
}

/** @see SampleRowReader */
@Deprecated("Renamed to SampleRowReader", ReplaceWith("SampleRowReader"))
val ParquetReader = SampleRowReader
