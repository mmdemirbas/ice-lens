package service

import java.io.File
import java.sql.DriverManager
import kotlin.test.*

/**
 * Tests for SampleRowReader with real data files created via DuckDB.
 * Validates the actual query execution path, not just input validation.
 */
class SampleRowReaderTest {

    private lateinit var tmpDir: File

    @BeforeTest
    fun setUp() {
        tmpDir = kotlin.io.path.createTempDirectory("sample-reader-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `querySampleRows reads Parquet file created by DuckDB`() {
        val parquetFile = createParquetFile("test.parquet", """
            SELECT 1 AS id, 'hello' AS name
            UNION ALL
            SELECT 2, 'world'
            UNION ALL
            SELECT 3, 'foo'
        """.trimIndent())

        val rows = SampleRowReader.querySampleRows(parquetFile.absolutePath)

        assertEquals(3, rows.size)
        assertEquals(1, rows[0]["id"])
        assertEquals("hello", rows[0]["name"])
        assertEquals(2, rows[1]["id"])
        assertEquals("world", rows[1]["name"])
    }

    @Test
    fun `querySampleRows handles NULL values`() {
        val parquetFile = createParquetFile("nulls.parquet", """
            SELECT 1 AS id, NULL AS name
            UNION ALL
            SELECT NULL AS id, 'hello' AS name
        """.trimIndent())

        val rows = SampleRowReader.querySampleRows(parquetFile.absolutePath)

        assertEquals(2, rows.size)
        assertEquals("null", rows[0]["name"]) // NULL becomes string "null"
        assertEquals("null", rows[1]["id"])
    }

    @Test
    fun `querySampleRows respects row limit`() {
        // Create a file with more rows than the limit (50)
        val rowExprs = (1..100).joinToString(" UNION ALL ") { "SELECT $it AS id" }
        val parquetFile = createParquetFile("many-rows.parquet", rowExprs)

        val rows = SampleRowReader.querySampleRows(parquetFile.absolutePath)

        assertTrue(rows.size <= GraphLayoutService.MAX_PARQUET_SAMPLE_ROWS,
            "Should limit to ${GraphLayoutService.MAX_PARQUET_SAMPLE_ROWS} rows, got ${rows.size}")
    }

    @Test
    fun `querySampleRows handles multiple column types`() {
        val parquetFile = createParquetFile("types.parquet", """
            SELECT
                42 AS int_col,
                3.14 AS double_col,
                true AS bool_col,
                'text' AS string_col
        """.trimIndent())

        val rows = SampleRowReader.querySampleRows(parquetFile.absolutePath)

        assertEquals(1, rows.size)
        assertEquals(42, rows[0]["int_col"])
        assertEquals("text", rows[0]["string_col"])
    }

    @Test
    fun `querySampleRows handles empty Parquet file`() {
        val parquetFile = createParquetFile("empty.parquet",
            "SELECT 1 AS id WHERE false")

        val rows = SampleRowReader.querySampleRows(parquetFile.absolutePath)
        assertEquals(0, rows.size)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Input Validation
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `querySampleRows rejects non-existent file`() {
        assertFailsWith<IllegalArgumentException> {
            SampleRowReader.querySampleRows("/nonexistent/file.parquet")
        }
    }

    @Test
    fun `querySampleRows rejects directory`() {
        assertFailsWith<IllegalArgumentException> {
            SampleRowReader.querySampleRows(tmpDir.absolutePath)
        }
    }

    @Test
    fun `querySampleRows rejects unsupported extension`() {
        val file = File(tmpDir, "test.txt")
        file.writeText("not a data file")
        assertFailsWith<IllegalArgumentException> {
            SampleRowReader.querySampleRows(file.absolutePath)
        }
    }

    @Test
    fun `querySampleRows accepts orc extension`() {
        // We can't easily create a real ORC file, but we can verify the extension is accepted
        // and it fails at query time (not validation time)
        val file = File(tmpDir, "test.orc")
        file.writeText("fake orc") // Not a real ORC file
        // Should pass extension validation but fail at DuckDB query
        assertFailsWith<Exception> {
            SampleRowReader.querySampleRows(file.absolutePath)
        }.also { e ->
            // Should be a DuckDB/SQL error, not IllegalArgumentException
            assertFalse(e is IllegalArgumentException,
                "ORC extension should pass validation; error should be from DuckDB: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    private fun createParquetFile(fileName: String, selectQuery: String): File {
        val file = File(tmpDir, fileName)
        DriverManager.getConnection("jdbc:duckdb:").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("COPY ($selectQuery) TO '${file.absolutePath.replace("\\", "/")}' (FORMAT PARQUET)")
            }
        }
        assertTrue(file.exists(), "Parquet file should be created: ${file.absolutePath}")
        return file
    }
}
