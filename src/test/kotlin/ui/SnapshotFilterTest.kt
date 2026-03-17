package ui

import model.GraphEdge
import model.GraphModel
import model.GraphNode
import model.Snapshot
import model.TableSummary
import model.FileTimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnapshotFilterTest {

    private fun tableSummary() = TableSummary(
        tableName = "test", tablePath = "/test", location = null, tableUuid = null,
        formatVersion = 2, currentSnapshotId = null, currentMetadataVersion = null,
        versionHintText = "1", tableCreationMs = null, tableLastUpdateMs = null,
        lastUpdatedMs = null, metadataFileCount = 0, snapshotCount = 0,
        snapshotManifestListFileCount = 0, manifestCount = 0, dataManifestCount = 0,
        deleteManifestCount = 0, manifestEntryCount = 0, uniqueDataFileCount = 0,
        dataFileCount = 0, posDeleteFileCount = 0, eqDeleteFileCount = 0,
        totalRecordCount = 0, metadataFileTimes = FileTimeRange(),
        snapshotManifestListFileTimes = FileTimeRange(),
        manifestFileTimes = FileTimeRange(), dataFileTimes = FileTimeRange()
    )

    private fun snapshot(id: Long) = Snapshot(snapshotId = id, timestampMs = id * 1000)

    @Test
    fun `empty selection returns all node ids`() {
        val graph = GraphModel(
            nodes = listOf(
                GraphNode.TableNode("t1", tableSummary()),
                GraphNode.SnapshotNode("s1", snapshot(1), 1),
            ),
            edges = listOf(GraphEdge("e1", "t1", "s1")),
            width = 100.0, height = 100.0
        )
        val result = computeVisibleNodeIdsForSnapshotFilter(graph, emptySet())
        assertEquals(setOf("t1", "s1"), result)
    }

    @Test
    fun `selecting a snapshot includes ancestors and descendants`() {
        val graph = GraphModel(
            nodes = listOf(
                GraphNode.TableNode("t1", tableSummary()),
                GraphNode.SnapshotNode("s1", snapshot(1), 1),
                GraphNode.SnapshotNode("s2", snapshot(2), 2),
            ),
            edges = listOf(
                GraphEdge("e1", "t1", "s1"),
                GraphEdge("e2", "t1", "s2"),
            ),
            width = 100.0, height = 100.0
        )
        val result = computeVisibleNodeIdsForSnapshotFilter(graph, setOf("s1"))
        assertTrue("t1" in result, "ancestor should be visible")
        assertTrue("s1" in result, "selected snapshot should be visible")
        assertTrue("s2" !in result, "unrelated snapshot should not be visible")
    }

    @Test
    fun `filteredGraphModel removes invisible nodes and edges`() {
        val graph = GraphModel(
            nodes = listOf(
                GraphNode.TableNode("t1", tableSummary()),
                GraphNode.SnapshotNode("s1", snapshot(1), 1),
                GraphNode.SnapshotNode("s2", snapshot(2), 2),
            ),
            edges = listOf(
                GraphEdge("e1", "t1", "s1"),
                GraphEdge("e2", "t1", "s2"),
            ),
            width = 100.0, height = 100.0
        )
        val filtered = filteredGraphModel(graph, setOf("t1", "s1"))
        assertEquals(2, filtered.nodes.size)
        assertEquals(1, filtered.edges.size)
        assertEquals("e1", filtered.edges[0].id)
    }

    @Test
    fun `filteredGraphModel returns same graph when all visible`() {
        val graph = GraphModel(
            nodes = listOf(GraphNode.TableNode("t1", tableSummary())),
            edges = emptyList(),
            width = 100.0, height = 100.0
        )
        val allIds = graph.nodes.map { it.id }.toSet()
        val filtered = filteredGraphModel(graph, allIds)
        assertTrue(filtered === graph, "should return same instance when no filtering needed")
    }
}
