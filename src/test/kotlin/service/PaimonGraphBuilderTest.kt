package service

import model.*
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaimonGraphBuilderTest {

    private fun minimalTableModel(
        snapshots: List<PaimonUnifiedSnapshot> = emptyList(),
        schemas: List<PaimonSchema> = emptyList(),
    ) = PaimonUnifiedTableModel(
        path = Paths.get("/tmp/test-paimon-table"),
        name = "test-paimon-table",
        schemas = schemas,
        snapshots = snapshots,
    )

    private fun minimalSnapshot(
        id: Long = 1,
        schemaId: Int = 0,
        commitKind: String = "APPEND",
        baseManifests: List<PaimonUnifiedManifest> = emptyList(),
        deltaManifests: List<PaimonUnifiedManifest> = emptyList(),
    ) = PaimonUnifiedSnapshot(
        path = Paths.get("/tmp/test-paimon-table/snapshot/snapshot-$id"),
        metadata = PaimonSnapshot(
            id = id,
            schemaId = schemaId,
            commitKind = commitKind,
            timeMillis = 1700000000000L + id * 1000,
            totalRecordCount = 100,
        ),
        schema = PaimonSchema(id = schemaId),
        baseManifests = baseManifests,
        deltaManifests = deltaManifests,
        changelogManifests = emptyList(),
    )

    private fun minimalManifest(
        fileName: String = "manifest-test-0",
        entries: List<PaimonUnifiedDataFile> = emptyList(),
    ) = PaimonUnifiedManifest(
        path = Paths.get("/tmp/test-paimon-table/manifest/$fileName"),
        metadata = PaimonManifestFileMeta(
            fileName = fileName,
            fileSize = 1024,
            numAddedFiles = entries.size.toLong(),
            numDeletedFiles = 0,
        ),
        entries = entries,
    )

    private fun minimalDataFile(
        fileName: String = "data-test-0.orc",
        kind: Int = 0,
        bucket: Int = 0,
        level: Int = 0,
        rowCount: Long = 50,
    ) = PaimonUnifiedDataFile(
        path = Paths.get("/tmp/test-paimon-table/bucket-$bucket/$fileName"),
        metadata = PaimonManifestEntry(
            kind = kind,
            bucket = bucket,
            totalBuckets = 1,
            file = PaimonDataFileMeta(
                fileName = fileName,
                fileSize = 4096,
                rowCount = rowCount,
                level = level,
                schemaId = 0,
            ),
        ),
    )

    @Test
    fun `empty table produces only table node`() {
        val model = minimalTableModel()
        val result = PaimonGraphBuilder.buildGraph(model, showRows = false)

        assertEquals(1, result.nodes.size)
        assertTrue(result.nodes[0] is GraphNode.TableNode)
        assertTrue(result.edges.isEmpty())
    }

    @Test
    fun `single snapshot produces table and snapshot nodes`() {
        val model = minimalTableModel(
            snapshots = listOf(minimalSnapshot()),
        )
        val result = PaimonGraphBuilder.buildGraph(model, showRows = false)

        val tableNodes = result.nodes.filterIsInstance<GraphNode.TableNode>()
        val snapshotNodes = result.nodes.filterIsInstance<GraphNode.PaimonSnapshotNode>()
        assertEquals(1, tableNodes.size)
        assertEquals(1, snapshotNodes.size)
        assertEquals("APPEND", snapshotNodes[0].commitKind)
        assertEquals(1L, snapshotNodes[0].data.id)
    }

    @Test
    fun `snapshot with schema creates schema node and sibling edge`() {
        val schema = PaimonSchema(id = 0, fields = listOf(PaimonField(0, "k", "INT")))
        val model = minimalTableModel(
            schemas = listOf(schema),
            snapshots = listOf(minimalSnapshot(schemaId = 0)),
        )
        val result = PaimonGraphBuilder.buildGraph(model, showRows = false)

        val schemaNodes = result.nodes.filterIsInstance<GraphNode.PaimonSchemaNode>()
        assertEquals(1, schemaNodes.size)
        assertEquals(0, schemaNodes[0].data.id)

        val siblingEdges = result.edges.filter { it.isSibling }
        assertEquals(1, siblingEdges.size)
    }

    @Test
    fun `base and delta manifest lists create manifest list nodes`() {
        val baseManifest = minimalManifest("manifest-base-0")
        val deltaManifest = minimalManifest("manifest-delta-0")
        val model = minimalTableModel(
            snapshots = listOf(
                minimalSnapshot(
                    baseManifests = listOf(baseManifest),
                    deltaManifests = listOf(deltaManifest),
                )
            ),
        )
        val result = PaimonGraphBuilder.buildGraph(model, showRows = false)

        val mlNodes = result.nodes.filterIsInstance<GraphNode.PaimonManifestListNode>()
        assertEquals(2, mlNodes.size)
        assertTrue(mlNodes.any { it.kind == "base" })
        assertTrue(mlNodes.any { it.kind == "delta" })
    }

    @Test
    fun `manifest entries create manifest and data file nodes`() {
        val dataFile = minimalDataFile("data-0.orc", kind = 0, bucket = 0, level = 1)
        val manifest = minimalManifest("manifest-0", entries = listOf(dataFile))
        val model = minimalTableModel(
            snapshots = listOf(
                minimalSnapshot(deltaManifests = listOf(manifest))
            ),
        )
        val result = PaimonGraphBuilder.buildGraph(model, showRows = false)

        val manifestNodes = result.nodes.filterIsInstance<GraphNode.PaimonManifestNode>()
        assertEquals(1, manifestNodes.size)
        assertEquals("manifest-0", manifestNodes[0].data.fileName)

        val fileNodes = result.nodes.filterIsInstance<GraphNode.PaimonDataFileNode>()
        assertEquals(1, fileNodes.size)
        assertEquals(0, fileNodes[0].operationKind) // ADD
        assertEquals(1, fileNodes[0].level)
        assertEquals(0, fileNodes[0].bucket)
    }

    @Test
    fun `DELETE operation kind is propagated to file node`() {
        val dataFile = minimalDataFile("data-del-0.orc", kind = 1)
        val manifest = minimalManifest("manifest-del", entries = listOf(dataFile))
        val model = minimalTableModel(
            snapshots = listOf(
                minimalSnapshot(deltaManifests = listOf(manifest))
            ),
        )
        val result = PaimonGraphBuilder.buildGraph(model, showRows = false)

        val fileNodes = result.nodes.filterIsInstance<GraphNode.PaimonDataFileNode>()
        assertEquals(1, fileNodes.size)
        assertEquals(1, fileNodes[0].operationKind) // DELETE
    }

    @Test
    fun `multiple snapshots produce correct node counts`() {
        val model = minimalTableModel(
            snapshots = listOf(
                minimalSnapshot(id = 1, commitKind = "APPEND"),
                minimalSnapshot(id = 2, commitKind = "COMPACT"),
                minimalSnapshot(id = 3, commitKind = "OVERWRITE"),
            ),
        )
        val result = PaimonGraphBuilder.buildGraph(model, showRows = false)

        val snapshotNodes = result.nodes.filterIsInstance<GraphNode.PaimonSnapshotNode>()
        assertEquals(3, snapshotNodes.size)
        assertEquals(setOf("APPEND", "COMPACT", "OVERWRITE"), snapshotNodes.map { it.commitKind }.toSet())
    }

    @Test
    fun `table summary has correct counts`() {
        val dataFile1 = minimalDataFile("data-1.orc", rowCount = 50)
        val dataFile2 = minimalDataFile("data-2.orc", rowCount = 30)
        val manifest = minimalManifest("manifest-0", entries = listOf(dataFile1, dataFile2))
        val model = minimalTableModel(
            snapshots = listOf(
                minimalSnapshot(deltaManifests = listOf(manifest))
            ),
        )
        val result = PaimonGraphBuilder.buildGraph(model, showRows = false)

        assertEquals("test-paimon-table", result.summary.tableName)
        assertEquals(1, result.summary.snapshotCount)
        assertEquals(1, result.summary.manifestCount)
        assertEquals(2, result.summary.dataFileCount)
        assertEquals(80L, result.summary.totalRecordCount)
    }

    @Test
    fun `error nodes are created for read errors`() {
        val model = PaimonUnifiedTableModel(
            path = Paths.get("/tmp/test-paimon-table"),
            name = "test-paimon-table",
            schemas = emptyList(),
            snapshots = emptyList(),
            readErrors = listOf(UnifiedReadError("test-stage", "/test/path", "test error")),
        )
        val result = PaimonGraphBuilder.buildGraph(model, showRows = false)

        val errorNodes = result.nodes.filterIsInstance<GraphNode.ErrorNode>()
        assertEquals(1, errorNodes.size)
        assertEquals("TABLE READ ERROR", errorNodes[0].title)
        assertEquals("test error", errorNodes[0].message)
    }

    @Test
    fun `node IDs follow Paimon conventions`() {
        val schema = PaimonSchema(id = 0)
        val dataFile = minimalDataFile()
        val manifest = minimalManifest(entries = listOf(dataFile))
        val model = minimalTableModel(
            schemas = listOf(schema),
            snapshots = listOf(
                minimalSnapshot(deltaManifests = listOf(manifest))
            ),
        )
        val result = PaimonGraphBuilder.buildGraph(model, showRows = false)

        val nodeIds = result.nodes.map { it.id }
        assertTrue(nodeIds.any { it == "table_root" })
        assertTrue(nodeIds.any { it.startsWith("psnap_") })
        assertTrue(nodeIds.any { it.startsWith("pschema_") })
        assertTrue(nodeIds.any { it.startsWith("pml_") })
        assertTrue(nodeIds.any { it.startsWith("pman_") })
        assertTrue(nodeIds.any { it.startsWith("pdf_") })
    }

    @Test
    fun `edges connect correct node pairs`() {
        val manifest = minimalManifest("manifest-0")
        val model = minimalTableModel(
            snapshots = listOf(
                minimalSnapshot(deltaManifests = listOf(manifest))
            ),
        )
        val result = PaimonGraphBuilder.buildGraph(model, showRows = false)

        // table_root -> psnap_1
        assertTrue(result.edges.any { it.fromId == "table_root" && it.toId.startsWith("psnap_") })
        // psnap_1 -> pml_1_delta
        assertTrue(result.edges.any { it.fromId.startsWith("psnap_") && it.toId.startsWith("pml_") })
        // pml_* -> pman_*
        assertTrue(result.edges.any { it.fromId.startsWith("pml_") && it.toId.startsWith("pman_") })
    }
}
