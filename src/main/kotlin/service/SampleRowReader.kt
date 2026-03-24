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
 * Despite the historical name "ParquetReader", DuckDB supports multiple formats including
 * Parquet, ORC, and Avro. The `read_parquet()` function is used as it auto-detects the
 * actual file format.
 */
object SampleRowReader {

    init {
        Class.forName("org.duckdb.DuckDBDriver")
    }

    private var connection: Connection? = null
    private val lock = Any()

    private fun getConnection(): Connection {
        val conn = connection
        if (conn != null && !conn.isClosed) return conn
        val newConn = DriverManager.getConnection("jdbc:duckdb:")
        connection = newConn
        return newConn
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

        // Validate the path points to an actual regular file with an expected extension
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
                logger.warn("DuckDB connection failed, resetting and retrying: {}", e.message)
                // Reset and retry once on connection failure
                connection = null
                getConnection()
            }

            val rows = mutableListOf<Map<String, Any>>()
            val sql = "SELECT * FROM read_parquet(?) LIMIT ${GraphLayoutService.MAX_PARQUET_SAMPLE_ROWS}"
            val pstmt = conn.prepareStatement(sql)
            try {
                pstmt.setString(1, safePath)
                val rs = pstmt.executeQuery()
                val meta = rs.metaData
                val colCount = meta.columnCount

                while (rs.next()) {
                    val row = mutableMapOf<String, Any>()
                    for (i in 1..colCount) {
                        val colName = meta.getColumnName(i)
                        val value = rs.getObject(i) ?: "null"
                        row[colName] = value
                    }
                    rows.add(row)
                }
                rs.close()
                logger.debug("Sample rows queried: {} rows from {}", rows.size, safePath)
            } catch (e: Exception) {
                logger.error("DuckDB query failed for {}: {}", safePath, e.message, e)
                throw e
            } finally {
                pstmt.close()
            }
            return rows
        }
    }
}

/** @see SampleRowReader */
@Deprecated("Renamed to SampleRowReader", ReplaceWith("SampleRowReader"))
val ParquetReader = SampleRowReader
