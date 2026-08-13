package ui

import model.GraphModel
import model.GraphNode
import model.Point
import model.TableSummary
import model.FileTimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drag state is the shell's, layout is the core's. These pin the seam between them, because the
 * whole point of splitting them is that a [GraphModel] stays immutable and cacheable.
 */
class NodePositionsTest {

    private fun summary() = TableSummary(
        tableName = "orders", tablePath = "/w/orders", location = null, tableUuid = null,
        formatVersion = 2, currentSnapshotId = null, currentMetadataVersion = null,
        versionHintText = null, tableCreationMs = null, tableLastUpdateMs = null,
        lastUpdatedMs = null, metadataFileCount = 0, snapshotCount = 0,
        snapshotManifestListFileCount = 0, metadataFileTimes = FileTimeRange(),
        snapshotManifestListFileTimes = FileTimeRange(),
        manifestFileTimes = FileTimeRange(), dataFileTimes = FileTimeRange()
    )

    private fun graphWith(vararg placed: Pair<String, Point>): GraphModel {
        val nodes = placed.map { GraphNode.TableNode(it.first, summary()) }
        return GraphModel(nodes, emptyList(), 500.0, 500.0, layoutPositions = placed.toMap())
    }

    @Test
    fun `an undragged node sits where layout put it`() {
        val graph = graphWith("table_root" to Point(120f, 40f))
        val positions = NodePositions(graph)

        assertEquals(120f, positions.of("table_root").x)
        assertEquals(40f, positions.of("table_root").y)
    }

    @Test
    fun `a drag overrides the layout position without mutating the graph`() {
        val graph = graphWith("table_root" to Point(120f, 40f))
        val positions = NodePositions(graph)

        positions.moveBy("table_root", dx = 30f, dy = -10f)

        assertEquals(150f, positions.of("table_root").x)
        assertEquals(30f, positions.of("table_root").y)
        // The core model is untouched — that is what makes it safe to cache and re-use.
        assertEquals(Point(120f, 40f), graph.layoutPosition("table_root"))
    }

    @Test
    fun `only dragged nodes appear in the undo snapshot`() {
        val graph = graphWith("a" to Point(0f, 0f), "b" to Point(10f, 10f))
        val positions = NodePositions(graph)

        positions.moveBy("a", dx = 5f, dy = 5f)

        val snapshot = positions.draggedSnapshot()
        assertEquals(setOf("a"), snapshot.keys)
        assertTrue(snapshot["a"] == 5.0 to 5.0, "expected (5.0, 5.0), got ${snapshot["a"]}")
    }

    @Test
    fun `restoring an undo snapshot puts the node back`() {
        val graph = graphWith("a" to Point(0f, 0f))
        val positions = NodePositions(graph)
        positions.moveBy("a", dx = 5f, dy = 5f)
        val before = positions.draggedSnapshot()

        positions.moveBy("a", dx = 100f, dy = 100f)
        positions.restore(before)

        assertEquals(5f, positions.of("a").x)
        assertEquals(5f, positions.of("a").y)
    }

    @Test
    fun `effective covers every node, dragged or not`() {
        val graph = graphWith("a" to Point(1f, 2f), "b" to Point(3f, 4f))
        val positions = NodePositions(graph)
        positions.moveBy("a", dx = 9f, dy = 0f)

        val effective = positions.effective()
        assertEquals(setOf("a", "b"), effective.keys)
        assertEquals(10f, effective.getValue("a").x)
        assertEquals(3f, effective.getValue("b").x)
    }
}
