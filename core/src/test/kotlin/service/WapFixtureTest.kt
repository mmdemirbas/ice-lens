package service

import model.GraphNode
import model.UnifiedTableModel
import model.publishedWapId
import model.GraphSearch
import model.sourceSnapshotId
import model.wapId
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `example/iceberg/default/wap`: a write-audit-publish flow, from `docs/fixtures/wap.sql`.
 *
 * Four snapshots and one relationship the lineage does not carry. Snapshot 2 was written under
 * `spark.wap.id = audit-1`: it is in the metadata, its parent is snapshot 1, and no ref names it.
 * Main moved on to snapshot 3 (also a child of 1), and `publish_changes` then wrote snapshot 4 on
 * main — parent 3, `source-snapshot-id` 2, `published-wap-id audit-1` — holding the staged
 * snapshot's data file under a manifest of its own. The expected values are the script's, and the
 * writer printed its own snapshot table at the end of it.
 */
class WapFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val tableDir: Path = Paths.get(File(repoRoot, "example/iceberg/default/wap").absolutePath)
    private val model = UnifiedTableModel(tableDir)
    private val current = model.metadatas.last()
    private val snapshots = current.snapshots.sortedBy { it.metadata.sequenceNumber }
    private val staged = snapshots[1]
    private val published = snapshots[3]

    @Test
    fun `the staged snapshot is on no ref, and the published one names it`() {
        assertEquals(4, snapshots.size)
        assertEquals(listOf(null, "audit-1", null, null), snapshots.map { it.metadata.wapId })
        assertEquals(listOf(null, null, null, "audit-1"), snapshots.map { it.metadata.publishedWapId })
        assertEquals(listOf(null, null, null, staged.metadata.snapshotId), snapshots.map { it.metadata.sourceSnapshotId })
        // Both 2 and 3 fork from 1; 4 continues main from 3.
        val first = snapshots[0].metadata.snapshotId
        assertEquals(listOf(null, first, first, snapshots[2].metadata.snapshotId), snapshots.map { it.metadata.parentSnapshotId })
        assertEquals(mapOf("main" to published.metadata.snapshotId), current.metadata.refs.mapValues { it.value.snapshotId })
        assertEquals(published.metadata.snapshotId, current.metadata.currentSnapshotId)
        assertEquals("3", published.metadata.summary["total-records"], "the published commit's closure holds all three rows")
    }

    /** The publish wrote its own manifest; what it shares with the staged snapshot is the data file. */
    @Test
    fun `the published commit carries the staged snapshot's file under a manifest of its own`() {
        val stagedOwn = staged.manifests.single { it.metadata.addedSnapshotId == staged.metadata.snapshotId }
        val publishedOwn = published.manifests.single { it.metadata.addedSnapshotId == published.metadata.snapshotId }
        assertTrue(stagedOwn.path != publishedOwn.path)
        assertEquals(
            stagedOwn.dataFiles.map { it.metadata.dataFile?.filePath },
            publishedOwn.dataFiles.map { it.metadata.dataFile?.filePath },
        )
        assertEquals(3, current.snapshots.flatMap { it.manifests }.flatMap { it.dataFiles }.map { it.metadata.dataFile?.filePath }.distinct().size)
    }

    @Test
    fun `the graph draws the source as an annotation edge and puts the staged commit in its own column`() {
        val graph = GraphLayoutService.layoutGraph(model, showRows = false)
        val stagedNode = graph.nodes.filterIsInstance<GraphNode.SnapshotNode>().single { it.data.wapId == "audit-1" }
        val publishedNode = graph.nodes.filterIsInstance<GraphNode.SnapshotNode>().single { it.data.publishedWapId == "audit-1" }
        val source = graph.edges.single { it.id.startsWith("e_source_") }
        assertEquals(stagedNode.id to publishedNode.id, source.fromId to source.toId)
        assertTrue(!source.affectsLayout, "between two snapshots, so ELK must not see it")
        assertTrue(stagedNode.refs.isEmpty())
        assertTrue(publishedNode.refs.any { it.name == "main" })
        // The lineage edge into the published commit comes from main's tip, not from the source.
        val lineageIn = graph.edges.filter { it.id.startsWith("e_lineage_") && it.toId == publishedNode.id }
        assertEquals(listOf("snap_${snapshots[2].metadata.snapshotId}"), lineageIn.map { it.fromId })
        // Trunk first: 1, 3, 4 in column 0; the staged fork in column 1.
        val tracks = snapshotTracks(graph.nodes.filterIsInstance<GraphNode.SnapshotNode>())
        assertEquals(1, tracks.getValue(stagedNode.id))
        assertEquals(setOf(0), tracks.filterKeys { it != stagedNode.id }.values.toSet())
        val columns = snapshotColumns(graph.nodes.filterIsInstance<GraphNode.SnapshotNode>()) { id -> graph.layoutPositions.getValue(id) }
        assertEquals(listOf("main"), columns.flatMap { column -> column.labels.map { it.name } }, "a staged commit names no column")
        assertTrue("audit-1" in GraphSearch.searchableText(stagedNode) && "audit-1" in GraphSearch.searchableText(publishedNode))
        assertNull(stagedNode.data.sourceSnapshotId)
        assertNotNull(publishedNode.data.sourceSnapshotId)
    }
}
