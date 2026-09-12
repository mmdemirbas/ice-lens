package service

import model.GraphNode
import model.UnifiedTableModel
import model.describe
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `example/iceberg/default/sorted`: an Iceberg table written under three sort orders, from
 * `docs/fixtures/sorted.sql`.
 *
 * Every other Iceberg fixture carries one sort order — id 0, no fields — so nothing exercised the
 * resolution from an id to the order it names. This table's three commits were written under
 * orders 0, 1 and 2: `WRITE ORDERED BY name` becomes order 1 over source-id 2, and
 * `WRITE ORDERED BY id DESC NULLS LAST, name` becomes order 2 over source-ids 1 and 2.
 *
 * What the engine then wrote is the thing this test pins, because the script's header predicted
 * otherwise: **every data file records `sort_order_id` 0, and the rows inside each file are in the
 * order that was in force.** Spark's writer sorts through the write's requested ordering and
 * builds its file writer factory without a data sort order, so `DataFiles.Builder` keeps
 * `SortOrder.unsorted().orderId()`. A file's 0 is therefore not a statement that its rows are
 * unordered, and the panel puts the table's default beside it for exactly that reason.
 */
class SortedFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val tableDir: Path = Paths.get(File(repoRoot, "example/iceberg/default/sorted").absolutePath)
    private val model = UnifiedTableModel(tableDir)
    private val current = model.metadatas.last().metadata

    /** The three commits oldest-first, each with the one manifest it wrote — later lists carry the earlier ones forward. */
    private val commits = model.metadatas.last().snapshots.sortedBy { it.metadata.sequenceNumber }
        .map { snapshot -> snapshot to snapshot.manifests.single { it.metadata.addedSnapshotId == snapshot.metadata.snapshotId } }

    @Test
    fun `the metadata accumulates one sort order per WRITE ORDERED BY and defaults to the newest`() {
        assertEquals(listOf(0, 1, 2), current.sortOrders.map { it.orderId })
        assertEquals(2, current.defaultSortOrderId)
        assertEquals(emptyList(), current.sortOrders[0].fields)
        assertEquals(listOf(2), current.sortOrders[1].fields.map { it.sourceId })
        assertEquals(listOf("asc"), current.sortOrders[1].fields.map { it.direction })
        assertEquals(listOf(1, 2), current.sortOrders[2].fields.map { it.sourceId })
        assertEquals(listOf("desc", "asc"), current.sortOrders[2].fields.map { it.direction })
        assertEquals(listOf("nulls-last", "nulls-first"), current.sortOrders[2].fields.map { it.nullOrder })
        assertTrue(current.sortOrders.flatMap { it.fields }.all { it.transformName == "identity" })
        assertTrue(model.readErrors.isEmpty(), "${model.readErrors}")
        // The default moved with each ALTER: 0 at v1–v2, 1 at v3–v4, 2 from v5.
        assertEquals(listOf(0, 0, 1, 1, 2, 2), model.metadatas.map { it.metadata.defaultSortOrderId })
    }

    @Test
    fun `an order reads the way WRITE ORDERED BY stated it`() {
        // The manifest's own schema, the same one the file panel resolves against.
        val schema = assertNotNull(commits.last().second.schema)
        val nameOf = { id: Int -> schema.nameOf(id) }
        assertEquals("unsorted", current.sortOrders[0].describe(nameOf))
        assertEquals("name ASC NULLS FIRST", current.sortOrders[1].describe(nameOf))
        assertEquals("id DESC NULLS LAST, name ASC NULLS FIRST", current.sortOrders[2].describe(nameOf))
        // A field no schema names is printed as its id rather than guessed at.
        assertEquals("field 1 DESC NULLS LAST, field 2 ASC NULLS FIRST", current.sortOrders[2].describe { null })
    }

    /**
     * The finding. Three files, three orders in force, and 0 on every one of them — while the rows
     * inside are in the order's order. The script inserted each batch out of order so a file in
     * insertion order and a file in sort order are distinguishable, and the first file is the
     * control: no order in force, and the rows are as inserted.
     */
    @Test
    fun `Spark sorts the rows and records sort order 0 on every file`() {
        assertEquals(3, commits.size)
        val files = commits.map { (_, manifest) -> manifest.dataFiles.single() }
        assertEquals(listOf(0L, 0L, 0L), files.map { it.metadata.dataFile?.sortOrderId })
        assertEquals(listOf(3L, 3L, 3L), files.map { it.metadata.dataFile?.recordCount })
        val rowsOf = { file: model.UnifiedDataFile ->
            SampleRowReader.querySampleRows(file.path.toString()).map { (it["id"] as Number).toInt() to it["name"].toString() }
        }
        assertEquals(listOf(3 to "gamma", 1 to "alpha", 2 to "beta"), rowsOf(files[0]), "no order in force: insertion order")
        assertEquals(listOf(4 to "delta", 5 to "epsilon", 6 to "zeta"), rowsOf(files[1]), "order 1: name ASC")
        assertEquals(listOf(9 to "iota", 8 to "theta", 7 to "eta"), rowsOf(files[2]), "order 2: id DESC")
    }

    /** The graph resolves both ids, and the panel needs nothing else to put one beside the other. */
    @Test
    fun `the file node carries the order it claims and the one the table defaults to`() {
        val graph = GraphLayoutService.layoutGraph(model, showRows = false)
        val fileNodes = graph.nodes.filterIsInstance<GraphNode.FileNode>()
        assertEquals(3, fileNodes.size)
        fileNodes.forEach { node ->
            assertEquals(0L, node.data.sortOrderId)
            assertEquals(current.sortOrders[0], node.sortOrder)
            assertEquals("unsorted", node.sortOrder?.describe { node.schema?.nameOf(it) })
            assertEquals(current.sortOrders[2], node.defaultSortOrder)
            assertEquals("id DESC NULLS LAST, name ASC NULLS FIRST", node.defaultSortOrder?.describe { node.schema?.nameOf(it) })
        }
        // On a table with one order, the file's claim and the default are the same order.
        val plain = GraphLayoutService.layoutGraph(UnifiedTableModel(tableDir.resolveSibling("test")), showRows = false)
        plain.nodes.filterIsInstance<GraphNode.FileNode>().forEach { node ->
            assertEquals(node.sortOrder, node.defaultSortOrder)
            assertEquals(0, node.defaultSortOrder?.orderId)
        }
    }
}
