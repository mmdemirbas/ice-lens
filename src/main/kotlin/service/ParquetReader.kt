package service

import java.io.File
import java.sql.Connection
import java.sql.DriverManager

object ParquetReader {

    init {
        Class.forName("org.duckdb.DuckDBDriver")
    }

    private var connection: Connection? = null

    @Synchronized
    private fun getConnection(): Connection {
        val conn = connection
        if (conn != null && !conn.isClosed) return conn
        val newConn = DriverManager.getConnection("jdbc:duckdb:")
        connection = newConn
        return newConn
    }

    fun queryParquet(filePath: String): List<Map<String, Any>> {
        val rows = mutableListOf<Map<String, Any>>()

        // Resolve symlinks and pure native path for the OS
        val canonicalPath = File(filePath).canonicalPath
        // Escape single quotes just in case the path contains them
        val safePath = canonicalPath.replace("'", "''").replace("\\", "/")

        val conn = getConnection()
        val stmt = conn.createStatement()

        // Explicitly use read_parquet() to bypass auto-detect globbing issues
        val sql = "SELECT * FROM read_parquet('$safePath') LIMIT 50"
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
        stmt.close()
        return rows
    }
}
