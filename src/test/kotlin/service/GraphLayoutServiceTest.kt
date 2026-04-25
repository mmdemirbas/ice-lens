package service

import model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.nio.file.Path

class GraphLayoutServiceTest {

    private fun minimalTable(name: String = "test_table"): UnifiedTableModel {
        val metadata = TableMetadata(
            formatVersion = 2,
            tableUuid = "test-uuid",
            location = "/test/location",
            snapshots = listOf(
                Snapshot(snapshotId = 1, timestampMs = 1000, summary = mapOf("operation" to "append"))
            )
        )
        return UnifiedTableModel(
            path = Path.of("/test/$name"),
            name = name,
            versionHint = "1",
            metadatas = listOf(
                UnifiedMetadata(
                    path = Path.of("/test/$name/metadata/v1.metadata.json"),
                    metadata = metadata,
                    snapshots = emptyList()
                )
            )
        )
    }

    @Test
    fun `layoutGraph produces table node`() {
        val table = minimalTable()
        val graph = GraphLayoutService.layoutGraph(table, showRows = false)

        assertTrue(graph.nodes.isNotEmpty(), "Graph should have nodes")
        val tableNodes = graph.nodes.filterIsInstance<GraphNode.TableNode>()
        assertEquals(1, tableNodes.size, "Should have exactly one table node")
        assertEquals("test_table", tableNodes[0].summary.tableName)
    }

    @Test
    fun `layoutGraph produces metadata nodes`() {
        val table = minimalTable()
        val graph = GraphLayoutService.layoutGraph(table, showRows = false)

        val metaNodes = graph.nodes.filterIsInstance<GraphNode.MetadataNode>()
        assertEquals(1, metaNodes.size)
        assertEquals("v1.metadata.json", metaNodes[0].fileName)
    }

    @Test
    fun `layoutGraph creates edges between table and metadata`() {
        val table = minimalTable()
        val graph = GraphLayoutService.layoutGraph(table, showRows = false)

        val tableNode = graph.nodes.filterIsInstance<GraphNode.TableNode>().first()
        val metaNode = graph.nodes.filterIsInstance<GraphNode.MetadataNode>().first()
        val edge = graph.edges.find { it.fromId == tableNode.id && it.toId == metaNode.id }
        assertTrue(edge != null, "Should have edge from table to metadata")
    }

    @Test
    fun `layoutGraph has no duplicate edge IDs`() {
        val table = minimalTable()
        val graph = GraphLayoutService.layoutGraph(table, showRows = false)

        val edgeIds = graph.edges.map { it.id }
        assertEquals(edgeIds.size, edgeIds.toSet().size, "Edge IDs should be unique")
    }

    @Test
    fun `layoutGraph populates positions for all nodes`() {
        val table = minimalTable()
        val graph = GraphLayoutService.layoutGraph(table, showRows = false)

        graph.nodes.forEach { node ->
            val pos = graph.getPosition(node.id)
            // The original assertion used `||`, which trivially passed (any non-negative coord).
            // ELK assigns both coordinates >= 0 within the layout area.
            assertTrue(pos.x >= 0f && pos.y >= 0f, "Node ${node.id} should have non-negative position, got $pos")
        }
    }

    @Test
    fun `layoutGraph with multiple metadata versions`() {
        val meta1 = TableMetadata(formatVersion = 2, snapshots = emptyList())
        val meta2 = TableMetadata(formatVersion = 2, snapshots = emptyList())
        val table = UnifiedTableModel(
            path = Path.of("/test/multi"),
            name = "multi",
            versionHint = "2",
            metadatas = listOf(
                UnifiedMetadata(path = Path.of("/test/multi/metadata/v1.metadata.json"), metadata = meta1, snapshots = emptyList()),
                UnifiedMetadata(path = Path.of("/test/multi/metadata/v2.metadata.json"), metadata = meta2, snapshots = emptyList()),
            )
        )
        val graph = GraphLayoutService.layoutGraph(table, showRows = false)

        val metaNodes = graph.nodes.filterIsInstance<GraphNode.MetadataNode>()
        assertEquals(2, metaNodes.size, "Should have two metadata nodes")
    }

    @Test
    fun `layoutGraph handles read errors gracefully`() {
        val table = UnifiedTableModel(
            path = Path.of("/test/errors"),
            name = "errors",
            versionHint = "1",
            metadatas = emptyList(),
            readErrors = listOf(
                UnifiedReadError(stage = "test", path = "/test", message = "test error")
            )
        )
        val graph = GraphLayoutService.layoutGraph(table, showRows = false)

        val errorNodes = graph.nodes.filterIsInstance<GraphNode.ErrorNode>()
        assertEquals(1, errorNodes.size)
        assertEquals("test error", errorNodes[0].message)
    }

    @Test
    fun `layoutGraph assigns unique manifest node IDs even for hashCode-colliding paths`() {
        // Create two snapshots pointing to manifests whose paths have different values
        // but could potentially collide with hashCode()
        val snap1 = Snapshot(snapshotId = 1, timestampMs = 1000, manifestList = "snap-1.avro")
        val snap2 = Snapshot(snapshotId = 2, timestampMs = 2000, manifestList = "snap-2.avro")

        val manifest1 = ManifestListEntry(manifestPath = "/table/metadata/manifest-aaa.avro", content = 0)
        val manifest2 = ManifestListEntry(manifestPath = "/table/metadata/manifest-bbb.avro", content = 0)

        val table = UnifiedTableModel(
            path = Path.of("/test/collision"),
            name = "collision",
            versionHint = "1",
            metadatas = listOf(
                UnifiedMetadata(
                    path = Path.of("/test/collision/metadata/v1.metadata.json"),
                    metadata = TableMetadata(formatVersion = 2, snapshots = listOf(snap1, snap2)),
                    snapshots = listOf(
                        UnifiedSnapshot(
                            path = Path.of("/test/collision/metadata/snap-1.avro"),
                            metadata = snap1,
                            manifests = listOf(
                                UnifiedManifest(
                                    path = Path.of("/test/collision/metadata/manifest-aaa.avro"),
                                    metadata = manifest1,
                                    dataFiles = emptyList()
                                )
                            )
                        ),
                        UnifiedSnapshot(
                            path = Path.of("/test/collision/metadata/snap-2.avro"),
                            metadata = snap2,
                            manifests = listOf(
                                UnifiedManifest(
                                    path = Path.of("/test/collision/metadata/manifest-bbb.avro"),
                                    metadata = manifest2,
                                    dataFiles = emptyList()
                                )
                            )
                        ),
                    )
                )
            )
        )
        val graph = GraphLayoutService.layoutGraph(table, showRows = false)

        val manifestNodes = graph.nodes.filterIsInstance<GraphNode.ManifestNode>()
        assertEquals(2, manifestNodes.size, "Should have 2 distinct manifest nodes")
        val ids = manifestNodes.map { it.id }.toSet()
        assertEquals(2, ids.size, "Manifest node IDs should be unique")
    }
}
