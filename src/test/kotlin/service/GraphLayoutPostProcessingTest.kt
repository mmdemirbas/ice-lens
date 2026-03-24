package service

import model.*
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Tests for GraphLayoutService post-processing: chronological ordering,
 * parent-child alignment, and overlap prevention.
 *
 * These tests build graph models, run full layout, then verify the
 * resulting y-positions satisfy the invariants that the post-processing
 * enforces.
 *
 * Note: `alignParentsWithChildren` runs AFTER `enforceChronologicalVerticalOrder`
 * and may reposition parent nodes based on their children, so global ordering
 * across different parent groups is not guaranteed. Ordering is only reliable
 * within children of the same parent, and for leaf-level nodes.
 */
class GraphLayoutPostProcessingTest {

    // ═══════════════════════════════════════════════════════════════
    //  Iceberg: Ordering Within Parent
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `Iceberg snapshots within same metadata ordered by timestamp`() {
        val snap1 = Snapshot(snapshotId = 1, timestampMs = 3000L, sequenceNumber = 1)
        val snap2 = Snapshot(snapshotId = 2, timestampMs = 1000L, sequenceNumber = 2)
        val snap3 = Snapshot(snapshotId = 3, timestampMs = 2000L, sequenceNumber = 3)

        val table = icebergTable(
            metadatas = listOf(
                icebergMetadata(
                    "v1.metadata.json",
                    snapshots = listOf(snap1, snap2, snap3),
                    unifiedSnapshots = listOf(
                        unifiedSnapshot(snap1),
                        unifiedSnapshot(snap2),
                        unifiedSnapshot(snap3),
                    )
                )
            )
        )
        val graph = GraphLayoutService.layoutGraph(table, showRows = false)

        val snapNodes = graph.nodes.filterIsInstance<GraphNode.SnapshotNode>()
            .sortedBy { graph.getPosition(it.id).y }
        assertTrue(snapNodes.size >= 3, "Expected 3 snapshot nodes")
        // Within one metadata parent, ordered by timestamp: snap2 (1000) < snap3 (2000) < snap1 (3000)
        assertEquals(2L, snapNodes[0].data.snapshotId)
        assertEquals(3L, snapNodes[1].data.snapshotId)
        assertEquals(1L, snapNodes[2].data.snapshotId)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Paimon: Ordering Within Parent
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `Paimon snapshots ordered by time then ID`() {
        val paimonTable = paimonTable(
            snapshots = listOf(
                paimonSnapshot(id = 3, timeMillis = 3000L),
                paimonSnapshot(id = 1, timeMillis = 1000L),
                paimonSnapshot(id = 2, timeMillis = 2000L),
            )
        )
        val graph = GraphLayoutService.layoutPaimonGraph(paimonTable, showRows = false)

        val snapNodes = graph.nodes.filterIsInstance<GraphNode.PaimonSnapshotNode>()
            .sortedBy { graph.getPosition(it.id).y }
        assertTrue(snapNodes.size >= 3, "Expected 3 snapshot nodes")
        assertEquals(1L, snapNodes[0].data.id)
        assertEquals(2L, snapNodes[1].data.id)
        assertEquals(3L, snapNodes[2].data.id)
    }

    @Test
    fun `Paimon data files within same manifest ordered by simpleId`() {
        val paimonTable = paimonTable(
            snapshots = listOf(
                paimonSnapshot(
                    id = 1, timeMillis = 1000L,
                    baseManifests = listOf(
                        paimonManifest("m1", entries = listOf(
                            paimonDataFile("f3"),
                            paimonDataFile("f1"),
                            paimonDataFile("f2"),
                        ))
                    ),
                )
            )
        )
        val graph = GraphLayoutService.layoutPaimonGraph(paimonTable, showRows = false)

        val fileNodes = graph.nodes.filterIsInstance<GraphNode.PaimonDataFileNode>()
            .sortedBy { graph.getPosition(it.id).y }
        assertTrue(fileNodes.size >= 3, "Expected 3 data file nodes, got ${fileNodes.size}")
        // Data files within same manifest are ordered by simpleId (assigned incrementally)
        assertTrue(fileNodes[0].simpleId < fileNodes[1].simpleId, "File nodes should be ordered by simpleId")
        assertTrue(fileNodes[1].simpleId < fileNodes[2].simpleId, "File nodes should be ordered by simpleId")
    }

    // ═══════════════════════════════════════════════════════════════
    //  Overlap Prevention
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `Iceberg metadata nodes do not overlap vertically`() {
        val snaps = (1..3).map { i ->
            Snapshot(snapshotId = i.toLong(), timestampMs = 1000L * i, sequenceNumber = i.toLong())
        }
        val table = icebergTable(
            metadatas = (1..3).map { i ->
                icebergMetadata("v$i.metadata.json",
                    snapshots = listOf(snaps[i - 1]),
                    unifiedSnapshots = listOf(unifiedSnapshot(snaps[i - 1])))
            }
        )
        val graph = GraphLayoutService.layoutGraph(table, showRows = false)
        assertNoOverlaps<GraphNode.MetadataNode>(graph)
    }

    @Test
    fun `Iceberg snapshot nodes do not overlap vertically`() {
        val snaps = (1..4).map { i ->
            Snapshot(snapshotId = i.toLong(), timestampMs = 1000L * i, sequenceNumber = i.toLong())
        }
        val table = icebergTable(
            metadatas = listOf(
                icebergMetadata(
                    "v1.metadata.json",
                    snapshots = snaps,
                    unifiedSnapshots = snaps.map { unifiedSnapshot(it) }
                )
            )
        )
        val graph = GraphLayoutService.layoutGraph(table, showRows = false)
        assertNoOverlaps<GraphNode.SnapshotNode>(graph)
    }

    @Test
    fun `Paimon snapshot nodes do not overlap vertically`() {
        val paimonTable = paimonTable(
            snapshots = (1..5).map { i -> paimonSnapshot(id = i.toLong(), timeMillis = 1000L * i) }
        )
        val graph = GraphLayoutService.layoutPaimonGraph(paimonTable, showRows = false)
        assertNoOverlaps<GraphNode.PaimonSnapshotNode>(graph)
    }

    @Test
    fun `Paimon manifest list nodes do not overlap vertically`() {
        val paimonTable = paimonTable(
            snapshots = listOf(
                paimonSnapshot(
                    id = 1, timeMillis = 1000L,
                    baseManifests = listOf(paimonManifest("b1", entries = listOf(paimonDataFile("bf1")))),
                    deltaManifests = listOf(paimonManifest("d1", entries = listOf(paimonDataFile("df1")))),
                    changelogManifests = listOf(paimonManifest("c1", entries = listOf(paimonDataFile("cf1")))),
                ),
                paimonSnapshot(
                    id = 2, timeMillis = 2000L,
                    baseManifests = listOf(paimonManifest("b2", entries = listOf(paimonDataFile("bf2")))),
                    deltaManifests = listOf(paimonManifest("d2", entries = listOf(paimonDataFile("df2")))),
                )
            )
        )
        val graph = GraphLayoutService.layoutPaimonGraph(paimonTable, showRows = false)
        assertNoOverlaps<GraphNode.PaimonManifestListNode>(graph)
    }

    @Test
    fun `Paimon manifest nodes do not overlap vertically`() {
        val paimonTable = paimonTable(
            snapshots = listOf(
                paimonSnapshot(
                    id = 1, timeMillis = 1000L,
                    baseManifests = listOf(
                        paimonManifest("m1", entries = listOf(paimonDataFile("f1"))),
                        paimonManifest("m2", entries = listOf(paimonDataFile("f2"))),
                    ),
                    deltaManifests = listOf(paimonManifest("m3", entries = listOf(paimonDataFile("f3")))),
                )
            )
        )
        val graph = GraphLayoutService.layoutPaimonGraph(paimonTable, showRows = false)
        assertNoOverlaps<GraphNode.PaimonManifestNode>(graph)
    }

    @Test
    fun `Paimon data file nodes do not overlap vertically`() {
        val paimonTable = paimonTable(
            snapshots = listOf(
                paimonSnapshot(
                    id = 1, timeMillis = 1000L,
                    baseManifests = listOf(
                        paimonManifest("m1", entries = listOf(paimonDataFile("f1"), paimonDataFile("f2"), paimonDataFile("f3")))
                    ),
                )
            )
        )
        val graph = GraphLayoutService.layoutPaimonGraph(paimonTable, showRows = false)
        assertNoOverlaps<GraphNode.PaimonDataFileNode>(graph)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Full Layout Invariants
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `full Iceberg layout has all positions populated and finite`() {
        val snaps = (1..3).map { i ->
            Snapshot(snapshotId = i.toLong(), timestampMs = 1000L * i, sequenceNumber = i.toLong())
        }
        val table = icebergTable(
            metadatas = listOf(
                icebergMetadata("v1.metadata.json", snapshots = snaps.take(2),
                    unifiedSnapshots = snaps.take(2).map { unifiedSnapshot(it) }),
                icebergMetadata("v2.metadata.json", snapshots = snaps,
                    unifiedSnapshots = snaps.map { unifiedSnapshot(it) })
            )
        )
        val graph = GraphLayoutService.layoutGraph(table, showRows = false)

        graph.nodes.forEach { node ->
            val pos = graph.getPosition(node.id)
            assertTrue(pos.x.isFinite() && pos.y.isFinite(),
                "Node ${node.id} (${node::class.simpleName}) has invalid position: $pos")
        }
    }

    @Test
    fun `full Paimon layout has all positions populated and finite`() {
        val paimonTable = paimonTable(
            schemas = listOf(PaimonSchema(id = 0), PaimonSchema(id = 1)),
            snapshots = listOf(
                paimonSnapshot(id = 1, timeMillis = 1000L, schemaId = 0,
                    baseManifests = listOf(paimonManifest("m1", entries = listOf(paimonDataFile("f1"))))),
                paimonSnapshot(id = 2, timeMillis = 2000L, schemaId = 1,
                    baseManifests = listOf(paimonManifest("m2", entries = listOf(paimonDataFile("f2")))))
            )
        )
        val graph = GraphLayoutService.layoutPaimonGraph(paimonTable, showRows = false)

        graph.nodes.forEach { node ->
            val pos = graph.getPosition(node.id)
            assertTrue(pos.x.isFinite() && pos.y.isFinite(),
                "Node ${node.id} (${node::class.simpleName}) has invalid position: $pos")
        }
    }

    @Test
    fun `layout handles single-node graph`() {
        val table = icebergTable(metadatas = emptyList())
        val graph = GraphLayoutService.layoutGraph(table, showRows = false)
        assertTrue(graph.nodes.isNotEmpty())
        val pos = graph.getPosition(graph.nodes[0].id)
        assertTrue(pos.x.isFinite() && pos.y.isFinite())
    }

    @Test
    fun `layout handles graph with error nodes only`() {
        val table = UnifiedTableModel(
            path = Path.of("/test"),
            name = "test",
            versionHint = "1",
            metadatas = emptyList(),
            readErrors = listOf(
                UnifiedReadError("stage1", "/path1", "Error 1"),
                UnifiedReadError("stage2", "/path2", "Error 2"),
                UnifiedReadError("stage3", "/path3", "Error 3"),
            )
        )
        val graph = GraphLayoutService.layoutGraph(table, showRows = false)
        val errorNodes = graph.nodes.filterIsInstance<GraphNode.ErrorNode>()
        assertEquals(3, errorNodes.size)
        assertNoOverlaps<GraphNode.ErrorNode>(graph)
    }

    @Test
    fun `Iceberg graph dimensions encompass all nodes`() {
        val snaps = (1..3).map { i ->
            Snapshot(snapshotId = i.toLong(), timestampMs = 1000L * i, sequenceNumber = i.toLong())
        }
        val table = icebergTable(
            metadatas = listOf(
                icebergMetadata("v1.metadata.json", snapshots = snaps,
                    unifiedSnapshots = snaps.map { unifiedSnapshot(it) })
            )
        )
        val graph = GraphLayoutService.layoutGraph(table, showRows = false)

        graph.nodes.forEach { node ->
            val pos = graph.getPosition(node.id)
            assertTrue(pos.x >= 0f, "Node ${node.id} has negative x: ${pos.x}")
            assertTrue(pos.y >= 0f, "Node ${node.id} has negative y: ${pos.y}")
        }
    }

    @Test
    fun `Paimon graph dimensions encompass all nodes`() {
        val paimonTable = paimonTable(
            snapshots = listOf(
                paimonSnapshot(id = 1, timeMillis = 1000L,
                    baseManifests = listOf(paimonManifest("m1", entries = listOf(paimonDataFile("f1"), paimonDataFile("f2"))))),
                paimonSnapshot(id = 2, timeMillis = 2000L,
                    deltaManifests = listOf(paimonManifest("m2", entries = listOf(paimonDataFile("f3")))))
            )
        )
        val graph = GraphLayoutService.layoutPaimonGraph(paimonTable, showRows = false)

        graph.nodes.forEach { node ->
            val pos = graph.getPosition(node.id)
            assertTrue(pos.x >= 0f, "Node ${node.id} has negative x: ${pos.x}")
            assertTrue(pos.y >= 0f, "Node ${node.id} has negative y: ${pos.y}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    private inline fun <reified T : GraphNode> assertNoOverlaps(graph: GraphModel) {
        val nodes = graph.nodes.filterIsInstance<T>().sortedBy { graph.getPosition(it.id).y }
        for (i in 1 until nodes.size) {
            val prev = nodes[i - 1]
            val curr = nodes[i]
            val prevBottom = graph.getPosition(prev.id).y + prev.height.toFloat()
            val currTop = graph.getPosition(curr.id).y
            assertTrue(currTop >= prevBottom - 0.1f,
                "${T::class.simpleName} overlap: ${prev.id} (bottom=$prevBottom) overlaps ${curr.id} (top=$currTop)")
        }
    }

    // --- Iceberg model builders ---

    private fun icebergTable(
        metadatas: List<UnifiedMetadata>,
    ) = UnifiedTableModel(
        path = Path.of("/test/table"),
        name = "test_table",
        versionHint = "1",
        metadatas = metadatas,
    )

    private fun icebergMetadata(
        fileName: String,
        snapshots: List<Snapshot> = emptyList(),
        unifiedSnapshots: List<UnifiedSnapshot> = emptyList(),
    ) = UnifiedMetadata(
        path = Path.of("/test/table/metadata/$fileName"),
        metadata = TableMetadata(formatVersion = 2, snapshots = snapshots),
        snapshots = unifiedSnapshots,
    )

    private fun unifiedSnapshot(snapshot: Snapshot) = UnifiedSnapshot(
        path = Path.of("/test/table/metadata/snap-${snapshot.snapshotId}.avro"),
        metadata = snapshot,
        manifests = emptyList(),
    )

    // --- Paimon model builders ---

    private fun paimonTable(
        schemas: List<PaimonSchema> = listOf(PaimonSchema(id = 0)),
        snapshots: List<PaimonUnifiedSnapshot>,
    ) = PaimonUnifiedTableModel(
        path = Path.of("/test/paimon-table"),
        name = "test-paimon-table",
        schemas = schemas,
        snapshots = snapshots,
    )

    private fun paimonSnapshot(
        id: Long,
        timeMillis: Long,
        schemaId: Int = 0,
        baseManifests: List<PaimonUnifiedManifest> = emptyList(),
        deltaManifests: List<PaimonUnifiedManifest> = emptyList(),
        changelogManifests: List<PaimonUnifiedManifest> = emptyList(),
    ) = PaimonUnifiedSnapshot(
        path = Path.of("/test/paimon-table/snapshot/snapshot-$id"),
        metadata = PaimonSnapshot(id = id, schemaId = schemaId, commitKind = "APPEND", timeMillis = timeMillis, totalRecordCount = 100),
        schema = PaimonSchema(id = schemaId),
        baseManifests = baseManifests,
        deltaManifests = deltaManifests,
        changelogManifests = changelogManifests,
    )

    private fun paimonManifest(
        fileName: String,
        entries: List<PaimonUnifiedDataFile> = emptyList(),
    ) = PaimonUnifiedManifest(
        path = Path.of("/test/paimon-table/manifest/$fileName"),
        metadata = PaimonManifestFileMeta(fileName = fileName, fileSize = 1024, numAddedFiles = entries.size.toLong()),
        entries = entries,
    )

    private fun paimonDataFile(
        fileName: String,
    ) = PaimonUnifiedDataFile(
        path = Path.of("/test/paimon-table/bucket-0/$fileName"),
        metadata = PaimonManifestEntry(
            kind = 0,
            bucket = 0,
            file = PaimonDataFileMeta(fileName = fileName, fileSize = 4096, rowCount = 50)
        ),
        rowsLoader = { emptyList() },
    )
}
