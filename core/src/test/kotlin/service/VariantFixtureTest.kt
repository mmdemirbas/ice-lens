package service

import model.FixtureCatalog
import model.GraphNode
import model.IcebergType
import model.ScanFilterParse
import model.TermEffect
import model.UnifiedTableModel
import model.evaluateScan
import model.parseIcebergType
import model.parseScanFilter
import model.rowLookupInput
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `example/iceberg/default/variant`: a format-version 3 table with a `variant` column, written
 * by Iceberg 1.10.0's Spark 4.0 module (`docs/fixtures/variant.sql`). What the engine wrote is
 * the point: the column is a Parquet group of two `BYTE_ARRAY`s, `metadata` and `value`, with no
 * field ids and no logical type under a group that carries the schema's id, and the manifest
 * records value and null counts for it and no column size and no bounds.
 */
class VariantFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val model = UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/variant").absolutePath))

    @Test
    fun `the table opens, the schema names the variant, and the manifest records counts and no bounds for it`() {
        assertEquals(emptyList(), model.readErrors)
        val newest = model.metadatas.last()
        assertEquals(3, newest.metadata.formatVersion)
        assertEquals(IcebergType.VariantType, parseIcebergType(JsonPrimitive("variant")))
        assertEquals(listOf(IcebergType.IntType, IcebergType.VariantType), newest.metadata.schemas.single().fields.map { parseIcebergType(requireNotNull(it.type)) })
        val files = newest.snapshots.last().manifests.flatMap { it.dataFiles }
        assertEquals(2, files.size)
        files.forEach { f ->
            val df = requireNotNull(f.metadata.dataFile)
            assertEquals(setOf(1), df.columnSizes?.map { it.key }?.toSet(), "a column size for id alone")
            assertEquals(setOf(1, 2), df.valueCounts?.map { it.key }?.toSet(), "value counts for both")
            assertEquals(setOf(1, 2), df.nullValueCounts?.map { it.key }?.toSet())
            assertEquals(setOf(1), df.lowerBounds?.map { it.key }?.toSet(), "no bound for the variant")
            assertEquals(setOf(1), df.upperBounds?.map { it.key }?.toSet())
        }
        assertTrue("variant" in FixtureCatalog.iceberg)
    }

    /**
     * DuckDB 1.4.4 decodes a Parquet variant to JSON text (`org.duckdb.JsonNode`, printed as the
     * JSON), which is what every row card and lookup gets — the script's `to_json(v)` is the
     * oracle, with the spellings DuckDB chooses noted: a decimal is quoted (`"2.5"`, `"12.34"`), a
     * binary is base64 (`"yv4="`), a timestamp with zone is rendered in this machine's zone, and
     * `1e300` stays a number.
     */
    @Test
    fun `the rows read as JSON through every reader, and no card is an error`() {
        val files = model.metadatas.last().snapshots.last().manifests.flatMap { it.dataFiles }
        val byId = files.flatMap { f -> SampleRowReader.querySampleRows(f.path.toString()).map { r -> (r["id"] as Number).toInt() to r["v"] } }.toMap()
        assertEquals((1..18).toSet(), byId.keys)
        assertEquals("{\"n\":1,\"name\":\"alpha\",\"ok\":true,\"tags\":[\"x\",\"y\"]}", byId.getValue(1).toString())
        assertEquals("{\"n\":-7,\"name\":\"bravo\",\"nested\":{\"d\":[1,{\"e\":\"f\"}],\"k\":null},\"ratio\":\"1.5\"}", byId.getValue(2).toString())
        assertEquals("\"a string of more than sixty-three characters, long enough to leave the short-string encoding behind\"", byId.getValue(3).toString())
        assertEquals("[1,\"2.5\",30000,3000000000,1e300,false,null]", byId.getValue(4).toString())
        assertEquals("null", byId.getValue(5), "a SQL NULL, spelled as DuckDB's nulls are on every card")
        assertEquals("{}", byId.getValue(6).toString())
        assertEquals("[]", byId.getValue(7).toString())
        assertEquals("\"2024-03-05\"", byId.getValue(8).toString())
        assertTrue(Regex("\"2024-03-05 \\d\\d:\\d\\d:\\d\\d[+-]\\d\\d\"").matches(byId.getValue(9).toString()), "the instant in this machine's zone: ${byId.getValue(9)}")
        assertEquals("\"2024-03-05 10:00:00\"", byId.getValue(10).toString())
        assertEquals("\"12.34\"", byId.getValue(11).toString())
        assertEquals("1.25", byId.getValue(12).toString())
        assertEquals("\"yv4=\"", byId.getValue(13).toString(), "X'CAFE' as base64")
        assertEquals("12345678901234", byId.getValue(14).toString())
        assertEquals("-1", byId.getValue(15).toString())
        assertEquals("300", byId.getValue(16).toString())
        assertEquals("[1,2]", byId.getValue(17).toString())
        assertEquals("{\"a\":1,\"b\":\"two\"}", byId.getValue(18).toString())
        assertTrue(byId.values.filter { it != "null" }.all { it is org.duckdb.JsonNode }, byId.values.map { it?.javaClass?.simpleName }.toString())

        val graph = GraphLayoutService.layoutGraph(model, showRows = true)
        assertEquals(emptyList(), graph.nodes.filterIsInstance<GraphNode.ErrorNode>().map { it.message })
        val cards = graph.nodes.filterIsInstance<GraphNode.RowNode>()
        assertEquals(10, cards.size, "five cards per file, the cap")
        assertTrue(cards.all { it.readError == null && it.resolvedData["v"] != null }, cards.map { it.resolvedData }.toString())

        val input = requireNotNull(model.rowLookupInput())
        assertEquals(18L, LiveRowCount.count(input).live)
        val hit = RowLookup.lookup(input, (parseScanFilter("id = 4") as ScanFilterParse.Parsed).filter, emptySet()).hits.single()
        assertEquals("[1,\"2.5\",30000,3000000000,1e300,false,null]", hit.cells["v"].toString(), "a lookup's cell is the same JSON")
    }

    /** The file panel's `Metrics Modes` and the scan's file stage both know the shape a variant records. */
    @Test
    fun `a variant under truncate(16) is counts only by rule, and a filter on it says why it was not evaluated`() {
        val graph = GraphLayoutService.layoutGraph(model, showRows = false)
        val checks = graph.nodes.filterIsInstance<GraphNode.FileNode>().map { it.metricsModes.single { c -> c.column == "v" } }
        assertEquals(2, checks.size)
        checks.forEach { c ->
            assertEquals("truncate(16)", c.configured.mode.spelled)
            assertEquals("counts", c.recorded)
            assertEquals(true, c.agrees, c.reason)
            assertEquals("counts only: an unshredded variant records no bounds under any mode (bounds come only with shredded fields, typed_value)", c.reason)
        }
        val reasons = evaluateScan(graph, (parseScanFilter("v = 'x'") as ScanFilterParse.Parsed).filter).files.values.flatMap { r -> r.outcomes.map { it.effect to it.reason } }
        assertEquals(2, reasons.size)
        assertTrue(reasons.all { it.first == TermEffect.NOT_EVALUATED && it.second.endsWith(" — an unshredded variant records no bounds under any mode (bounds come only with shredded fields, typed_value)") }, reasons.toString())
    }
}
