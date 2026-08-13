package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GraphTypesTest {

    private fun tableSummary() = TableSummary(
        tableName = "test", tablePath = "/test", location = null, tableUuid = null,
        formatVersion = 2, currentSnapshotId = null, currentMetadataVersion = null,
        versionHintText = "1", tableCreationMs = null, tableLastUpdateMs = null,
        lastUpdatedMs = null, metadataFileCount = 0, snapshotCount = 0,
        snapshotManifestListFileCount = 0, metadataFileTimes = FileTimeRange(),
        snapshotManifestListFileTimes = FileTimeRange(),
        manifestFileTimes = FileTimeRange(), dataFileTimes = FileTimeRange()
    )

    @Test
    fun `GraphNode x and y are excluded from data class equals`() {
        val node1 = GraphNode.TableNode("id1", tableSummary())
        val node2 = GraphNode.TableNode("id1", tableSummary())
        node1.x = 100.0
        node1.y = 200.0
        node2.x = 300.0
        node2.y = 400.0
        // data class equals only considers constructor params (id, summary)
        assertEquals(node1, node2, "Nodes with same id/summary should be equal regardless of x/y")
    }

    @Test
    fun `GraphModel nodeById provides fast lookup`() {
        val node = GraphNode.TableNode("t1", tableSummary())
        val model = GraphModel(listOf(node), emptyList(), 100.0, 100.0)
        assertEquals(node, model.nodeById["t1"])
        assertEquals(null, model.nodeById["nonexistent"])
    }

    @Test
    fun `layoutPosition returns where layout put the node`() {
        val node = GraphNode.TableNode("t1", tableSummary())
        val model = GraphModel(
            listOf(node), emptyList(), 100.0, 100.0,
            layoutPositions = mapOf("t1" to Point(42f, 99f))
        )

        assertEquals(Point(42f, 99f), model.layoutPosition("t1"))
    }

    @Test
    fun `layoutPosition falls back to the origin for a node layout never placed`() {
        val node = GraphNode.TableNode("t1", tableSummary())
        val model = GraphModel(listOf(node), emptyList(), 100.0, 100.0)

        assertEquals(Point.ORIGIN, model.layoutPosition("t1"))
    }

    @Test
    fun `RowNode resolvedData falls back to data when no loader`() {
        val node = GraphNode.RowNode("r1", mapOf("key" to "value"))
        assertEquals("value", node.resolvedData["key"])
    }

    @Test
    fun `RowNode resolvedData uses loader when provided`() {
        val node = GraphNode.RowNode(
            id = "r1",
            data = mapOf("placeholder" to true),
            dataLoader = { mapOf("loaded" to "yes") }
        )
        assertEquals("yes", node.resolvedData["loaded"])
        assertTrue("placeholder" !in node.resolvedData)
    }

    @Test
    fun `RowNode resolvedData falls back to data when loader returns empty map`() {
        val node = GraphNode.RowNode(
            id = "r1",
            data = mapOf("file_no" to 1, "row_idx" to 0),
            dataLoader = { emptyMap() }
        )
        // Empty loader result should fall back to initial data
        assertEquals(1, node.resolvedData["file_no"])
        assertEquals(0, node.resolvedData["row_idx"])
    }
}
