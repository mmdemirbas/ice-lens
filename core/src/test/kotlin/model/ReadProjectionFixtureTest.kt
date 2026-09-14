package model

import service.GraphLayoutService
import service.SampleRowReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A sampled row projected onto the current schema — what a read returns — held to what the
 * writers printed: Iceberg 1.10's read of `defaults` (`1 alpha eu 0 / 2 bravo eu 0 / 3 charlie
 * us 7`, the first two rows in a file written before `region` and `score` existed) and the
 * three schemas of `evolved`, where a file's `name` is read under no name at all.
 */
class ReadProjectionFixtureTest {

    private fun rowsByFile(fixture: String): Map<String, List<GraphNode.RowNode>> {
        val graph = GraphLayoutService.layoutGraph(FixtureCatalog.icebergModel(fixture), showRows = true)
        val files = graph.nodes.filterIsInstance<GraphNode.FileNode>()
        return graph.nodes.filterIsInstance<GraphNode.RowNode>().groupBy { row ->
            files.first { row.id.startsWith("row_${it.id}_") }.data.filePath.orEmpty().substringAfterLast('/')
        }
    }

    @Test
    fun `a row written before a column existed reads as the column's initial default, which Iceberg 1_10 also returns`() {
        val schema = FixtureCatalog.icebergModel("defaults").metadatas.last().metadata.let { m -> tableSchemaModel(m.schemas.first { it.schemaId == m.currentSchemaId }) }
        val region = schema.fieldsById.getValue(3)
        assertEquals("eu", region.showDefault(region.initialDefault))
        assertEquals("us", region.showDefault(region.writeDefault), "updateColumnDefault moves the write default and leaves the initial one")
        val score = schema.fieldsById.getValue(4)
        assertEquals("0", score.showDefault(score.initialDefault))
        assertEquals(null, schema.fieldsById.getValue(1).initialDefault)

        val byFile = rowsByFile("defaults")
        val old = byFile.getValue(byFile.keys.first { it.startsWith("00000-0-") }).sortedBy { it.resolvedData["id"].toString() }
        assertEquals(2, old.size)
        for ((row, expected) in old.zip(listOf(listOf("1", "alpha", "eu", "0"), listOf("2", "bravo", "eu", "0")))) {
            val read = assertNotNull(row.readAs.value, row.id)
            assertEquals(expected, read.cells.map { it.value }, "$read")
            assertEquals(listOf("id", "name", "region", "score"), read.cells.map { it.name })
            assertEquals(
                listOf(ProjectedCellSource.FILE, ProjectedCellSource.FILE, ProjectedCellSource.INITIAL_DEFAULT, ProjectedCellSource.INITIAL_DEFAULT),
                read.cells.map { it.source },
            )
            assertTrue(read.differsFromFile)
            assertEquals("a read returns 4 columns: 2 from an initial default", read.describe)
            assertFalse(row.resolvedData.containsKey("region"), "the card still shows the file's two columns")
        }
        val new = byFile.getValue(byFile.keys.first { it.startsWith("00000-1-") }).single()
        val read = assertNotNull(new.readAs.value)
        assertEquals(listOf("3", "charlie", "us", "7"), read.cells.map { it.value })
        assertTrue(read.cells.all { it.source == ProjectedCellSource.FILE })
        assertFalse(read.differsFromFile)
    }

    @Test
    fun `a renamed column is read under its new name, a dropped one not at all, and an added one as null below v3`() {
        val byFile = rowsByFile("evolved")
        val first = byFile.getValue(byFile.keys.first { it.startsWith("00000-0-") }).first()
        val read = assertNotNull(first.readAs.value)
        // Current schema: id long, amount double, note string — `name`, renamed to `label` and then dropped, is read under nothing.
        assertEquals(listOf("id", "amount", "note"), read.cells.map { it.name })
        assertEquals(listOf(ProjectedCellSource.FILE, ProjectedCellSource.FILE, ProjectedCellSource.ABSENT), read.cells.map { it.source })
        assertEquals("null", read.cells.last().value)
        assertEquals(listOf(DroppedCell("name", 2, first.resolvedData["name"].toString())), read.dropped)
        assertEquals("a read returns 3 columns: 1 absent from the file, read as null; 1 of the file's columns dropped from the table", read.describe)
        val second = byFile.getValue(byFile.keys.first { it.startsWith("00000-1-") }).first()
        assertEquals(listOf("label"), assertNotNull(second.readAs.value).dropped.map { it.fileColumn })
        val third = byFile.getValue(byFile.keys.first { it.startsWith("00000-2-") }).first()
        assertFalse(assertNotNull(third.readAs.value).differsFromFile)
    }

    @Test
    fun `field ids are read from a Parquet footer walking past a struct's children, and from an Avro header`() {
        val dir = java.nio.file.Files.createTempDirectory("fieldids").toFile()
        val parquet = java.io.File(dir, "nested.parquet")
        java.sql.DriverManager.getConnection("jdbc:duckdb:").use { conn ->
            conn.createStatement().use { st ->
                st.execute("COPY (SELECT 1 AS a, {'x': 1, 'y': 2} AS s, 3 AS b, 4 AS n) TO '${parquet.absolutePath.replace("'", "''")}' (FORMAT PARQUET, FIELD_IDS {a: 1, s: {__duckdb_field_id: 2, x: 3, y: 4}, b: 5})")
            }
        }
        // `n` records no id; `b` comes after the struct's two children and is top-level all the same.
        assertEquals(mapOf("a" to 1, "s" to 2, "b" to 5, "n" to null), SampleRowReader.fileColumnsOf(parquet.absolutePath))
        assertEquals(mapOf("a" to 1, "s" to 2, "b" to 5), SampleRowReader.fieldIdsOf(parquet.absolutePath))
        val avro = FixtureCatalog.icebergModel("avrofmt").metadatas.last().snapshots.first().manifests.first().dataFiles.first()
        assertEquals(mapOf("id" to 1, "name" to 2, "amount" to 3, "d" to 4), avro.fileColumns)
        assertEquals(emptyMap(), SampleRowReader.fieldIdsOf(java.io.File(dir, "missing.parquet").absolutePath), "a failure is an empty map, not a thrown card")
        dir.deleteRecursively()
    }

    @Test
    fun `a column without a field id is placed by the name mapping or left unmatched, and a metadata column is neither dropped nor unmatched`() {
        val schema = IcebergSchemaModel(0, IcebergType.StructType(listOf(NestedField(1, "id", IcebergType.LongType), NestedField(3, "amount", IcebergType.DoubleType))))
        val read = projectRow(mapOf("id" to 1, "name" to "alpha", "amount" to 1.5, "extra" to "x", "_row_id" to 7), mapOf("id" to 1, "name" to 2, "amount" to 3, "extra" to null), schema)
        assertEquals(listOf("1", "1.5"), read.cells.map { it.value })
        assertEquals(listOf(DroppedCell("name", 2, "alpha")), read.dropped)
        assertEquals(listOf("extra"), read.unmatched)
        assertTrue(read.differsFromFile)
        // The same cells from a file recording no ids at all, placed through a mapping that names `amount` as `amt` too.
        val mapping = NameMapping.parse("""[{"field-id": 1, "names": ["id"]}, {"field-id": 3, "names": ["amt", "amount"]}, {"field-id": 2, "names": ["name"]}]""")!!
        val mapped = projectRow(mapOf("id" to 1, "name" to "alpha", "amt" to 1.5, "extra" to "x"), mapOf("id" to null, "name" to null, "amt" to null, "extra" to null), schema, mapping)
        assertEquals(listOf("1", "1.5"), mapped.cells.map { it.value })
        assertTrue(mapped.cells.all { it.viaMapping })
        assertEquals("amt", mapped.cells.last().fileColumn)
        assertEquals(listOf(DroppedCell("name", 2, "alpha")), mapped.dropped)
        assertEquals(listOf("extra"), mapped.unmatched)
        assertEquals("a read returns 2 columns: 1 renamed since the file was written; 2 placed by the name mapping, the file recording no field ids; 1 of the file's columns dropped from the table; 1 of the file's columns without a field id", mapped.describe)
        // A file with ids is placed by them, whatever the mapping says.
        val ids = projectRow(mapOf("id" to 1, "amount" to 1.5), mapOf("id" to 1, "amount" to 3), schema, NameMapping.parse("""[{"field-id": 9, "names": ["id"]}]"""))
        assertEquals(1, ids.cells.first().fieldId)
        assertFalse(ids.differsFromFile)
    }
}
