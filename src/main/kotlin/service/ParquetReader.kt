package service

import java.io.File
import java.sql.Connection
import java.sql.DriverManager

object ParquetReader {

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

    fun queryParquet(filePath: String): List<Map<String, Any>> {
        // Resolve symlinks and pure native path for the OS (done outside lock — no shared state)
        val canonicalPath = File(filePath).canonicalPath
        val safePath = canonicalPath.replace("'", "''").replace("\\", "/")

        synchronized(lock) {
            val conn = try {
                getConnection()
            } catch (e: Exception) {
                // Reset and retry once on connection failure
                connection = null
                getConnection()
            }

            val rows = mutableListOf<Map<String, Any>>()
            val stmt = conn.createStatement()
            try {
                val sql = "SELECT * FROM read_parquet('$safePath') LIMIT ${GraphLayoutService.MAX_PARQUET_SAMPLE_ROWS}"
                val rs = stmt.executeQuery(sql)
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
            } finally {
                stmt.close()
            }
            return rows
        }
    }
}
