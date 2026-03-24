package service

import model.*
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IcebergGraphBuilderTest {

    private fun minimalTable(): UnifiedTableModel {
        val metadata = TableMetadata(
            formatVersion = 2,
            tableUuid = "test-uuid",
            location = "/test/location",
            snapshots = listOf(
                Snapshot(snapshotId = 1, timestampMs = 1000, summary = mapOf("operation" to "append"))
            )
        )
        return UnifiedTableModel(
            path = Path.of("/test/table"),
            name = "table",
            versionHint = "1",
            metadatas = listOf(
                UnifiedMetadata(
                    path = Path.of("/test/table/metadata/v1.metadata.json"),
                    metadata = metadata,
                    snapshots = emptyList()
                )
            )
        )
    }

    @Test
    fun `buildGraph produces table and metadata nodes`() {
        val result = IcebergGraphBuilder.buildGraph(minimalTable(), showRows = false)

        val tableNodes = result.nodes.filterIsInstance<GraphNode.TableNode>()
        assertEquals(1, tableNodes.size)
        assertEquals("table", result.summary.tableName)

        val metaNodes = result.nodes.filterIsInstance<GraphNode.MetadataNode>()
        assertEquals(1, metaNodes.size)
        assertEquals("v1.metadata.json", metaNodes[0].fileName)
    }

    @Test
    fun `buildGraph produces edges between table and metadata`() {
        val result = IcebergGraphBuilder.buildGraph(minimalTable(), showRows = false)

        val tableId = result.nodes.filterIsInstance<GraphNode.TableNode>().first().id
        val metaId = result.nodes.filterIsInstance<GraphNode.MetadataNode>().first().id
        assertTrue(result.edges.any { it.fromId == tableId && it.toId == metaId })
    }

    @Test
    fun `buildGraph with snapshots creates snapshot nodes`() {
        val snap = Snapshot(snapshotId = 42, timestampMs = 2000)
        val metadata = TableMetadata(formatVersion = 2, snapshots = listOf(snap))
        val table = UnifiedTableModel(
            path = Path.of("/test/table"),
            name = "table",
            versionHint = "1",
            metadatas = listOf(
                UnifiedMetadata(
                    path = Path.of("/test/table/metadata/v1.metadata.json"),
                    metadata = metadata,
                    snapshots = listOf(
                        UnifiedSnapshot(
                            path = Path.of("/test/table/metadata/snap-42.avro"),
                            metadata = snap,
                            manifests = emptyList()
                        )
                    )
                )
            )
        )
        val result = IcebergGraphBuilder.buildGraph(table, showRows = false)

        val snapNodes = result.nodes.filterIsInstance<GraphNode.SnapshotNode>()
        assertEquals(1, snapNodes.size)
        assertEquals(42L, snapNodes[0].data.snapshotId)
    }

    @Test
    fun `buildGraph handles errors gracefully`() {
        val table = UnifiedTableModel(
            path = Path.of("/test/table"),
            name = "table",
            versionHint = "1",
            metadatas = emptyList(),
            readErrors = listOf(
                UnifiedReadError(stage = "test", path = "/test", message = "test error")
            )
        )
        val result = IcebergGraphBuilder.buildGraph(table, showRows = false)

        val errorNodes = result.nodes.filterIsInstance<GraphNode.ErrorNode>()
        assertEquals(1, errorNodes.size)
    }

    @Test
    fun `buildGraph edge IDs are unique`() {
        val result = IcebergGraphBuilder.buildGraph(minimalTable(), showRows = false)
        val edgeIds = result.edges.map { it.id }
        assertEquals(edgeIds.size, edgeIds.toSet().size)
    }

    @Test
    fun `buildTableSummary computes correct counts`() {
        val table = minimalTable()
        val summary = IcebergGraphBuilder.buildTableSummary(table)
        assertEquals("table", summary.tableName)
        assertEquals(1, summary.metadataFileCount)
    }
}
