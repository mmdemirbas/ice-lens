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

    /**
     * The physical row position, which is the coordinate a positional delete and a v3 deletion
     * vector both address.
     *
     * Asked of DuckDB rather than taken from the order the rows came back in: a scan is free to
     * return them in any order, and nothing in the result would say that it had. The check is that
     * the position tracks the value written at that position, not merely that the column exists —
     * a column of zeroes would satisfy the weaker assertion.
     */
    @Test
    fun `each row carries its physical position in the file`() {
        val parquetFile = createParquetFile("positions.parquet", """
            SELECT 10 AS id UNION ALL SELECT 11 UNION ALL SELECT 12 UNION ALL SELECT 13
        """.trimIndent())

        val rows = SampleRowReader.querySampleRows(parquetFile.absolutePath)
        assertEquals(4, rows.size)
        rows.forEachIndexed { index, row ->
            val position = row[SampleRowReader.FILE_ROW_NUMBER]
            assertNotNull(position, "row $index carries no position")
            assertEquals(
                (row["id"] as Number).toLong() - 10L, (position as Number).toLong(),
                "the row written at position ${'$'}index says it sits somewhere else",
            )
        }
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
    fun `querySampleRows refuses an ORC file before any query, saying DuckDB has no ORC reader`() {
        // DuckDB 1.4 has no ORC table function, core or community, so the refusal is this
        // reader's own and names the reason; the fixture `orcfmt` holds it end to end.
        val file = File(tmpDir, "test.orc")
        file.writeText("fake orc")
        val e = assertFailsWith<IllegalArgumentException> { SampleRowReader.querySampleRows(file.absolutePath) }
        assertTrue(e.message!!.startsWith(ORC_UNREADABLE), e.message)
    }

    @Test
    fun `querySampleRows reads an Avro file through read_avro, and refuses a codec DuckDB cannot read by name`() {
        val schema = org.apache.avro.Schema.Parser().parse(
            """{"type":"record","name":"r","fields":[{"name":"k","type":"int"},{"name":"v","type":["null","string"],"default":null}]}""",
        )
        fun write(name: String, codec: org.apache.avro.file.CodecFactory): File {
            val f = File(tmpDir, name)
            org.apache.avro.file.DataFileWriter(org.apache.avro.generic.GenericDatumWriter<org.apache.avro.generic.GenericData.Record>(schema))
                .setCodec(codec).create(schema, f).use { w ->
                    repeat(3) { i -> w.append(org.apache.avro.generic.GenericData.Record(schema).apply { put("k", i); put("v", if (i == 1) null else "v$i") }) }
                }
            return f
        }
        val deflate = write("deflate.avro", org.apache.avro.file.CodecFactory.deflateCodec(6))
        val rows = SampleRowReader.querySampleRows(deflate.absolutePath)
        assertEquals(3, rows.size)
        assertEquals(listOf("0", "1", "2"), rows.map { it["k"].toString() })
        assertEquals("null", rows[1]["v"].toString(), "a null cell is the string \"null\", as for a Parquet row")
        assertTrue(rows.none { SampleRowReader.FILE_ROW_NUMBER in it }, "read_avro has no file_row_number: ${rows.first().keys}")

        val zstd = write("zstd.avro", org.apache.avro.file.CodecFactory.zstandardCodec(3))
        val e = assertFailsWith<IllegalArgumentException> { SampleRowReader.querySampleRows(zstd.absolutePath) }
        assertTrue(e.message!!.startsWith("DuckDB's Avro reader does not read the zstandard codec"), e.message)
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
