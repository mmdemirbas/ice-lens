package model

import service.AggregationPolicy
import service.GraphLayoutService
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The maintenance summary and the snapshot panel's rewrite and merge sections plan from the
 * newest metadata and the current snapshot. Both used to be looked up on the drawn graph, and
 * both are the last of their siblings in drawn order — so at a small page size the lookup found
 * an older metadata version (a plan under the wrong options, nothing failing) and no current
 * snapshot (no section at all). The input rides the table node now, built off the builder's
 * full node set, and this is the page size that would have broken it.
 */
class MaintenanceInputTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun iceberg(fixture: String) = UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/$fixture").absolutePath))
    private fun paimon(fixture: String) = PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/$fixture").absolutePath))

    @Test
    fun `at a page size of one the drawn graph holds neither the newest metadata nor the current snapshot, and the input holds both`() {
        val m = iceberg("branched")
        val newest = m.metadatas.last()
        val currentId = assertNotNull(newest.metadata.currentSnapshotId)
        val graph = GraphLayoutService.layoutGraph(m, showRows = false, policy = AggregationPolicy(pageSize = 1))

        // What the old lookup would have answered: the oldest version drawn, and no snapshot.
        val drawnNewest = graph.nodes.filterIsInstance<GraphNode.MetadataNode>().maxByOrNull { it.simpleId }
        assertTrue(drawnNewest != null && drawnNewest.data != newest.metadata, "the page size should have folded the newest metadata out")
        assertEquals(null, graph.nodeById["snap_$currentId"], "the page size should have folded the current snapshot out")

        val table = graph.nodes.filterIsInstance<GraphNode.TableNode>().single()
        val input = table.maintenance.value as IcebergMaintenanceInput
        assertEquals(newest.metadata, input.metadata)
        assertEquals(newest.path.fileName.toString(), input.metadataFileName)
        val current = assertNotNull(input.current)
        assertEquals("snap_$currentId", current.id)
        assertTrue(!current.expired)
        // The walks are the snapshot's own deferred reads, so they answer here too.
        val unified = m.metadatas.asReversed().firstNotNullOf { um -> um.snapshots.firstOrNull { it.metadata.snapshotId == currentId } }
        assertEquals(liveFilesOf(unified).map { it.path }.toSet(), current.liveFiles!!.map { it.path }.toSet())
        assertEquals(unified.manifests.map { it.metadata }, current.manifestList)

        // The two evolutions on the table panel read the model too: every version's properties, every schema's step.
        assertEquals(m.metadatas.map { it.metadata.properties }, table.summary.metadataVersions.map { it.properties })
        assertEquals(newest.metadata.schemaEvolution(), table.schemaEvolution)
        assertTrue(table.summary.metadataVersions.size > 1 && table.summary.metadataVersions.all { it.properties.isNotEmpty() })
    }

    @Test
    fun `the current node is the drawn one when it is drawn, so the two panels share one walk`() {
        val m = iceberg("mor")
        val graph = GraphLayoutService.layoutGraph(m, showRows = false, policy = AggregationPolicy.NONE)
        val table = graph.nodes.filterIsInstance<GraphNode.TableNode>().single()
        val current = assertNotNull((table.maintenance.value as IcebergMaintenanceInput).current)
        assertSame(graph.nodeById[current.id], current)
    }

    @Test
    fun `the Paimon input names the latest snapshot on main, whatever the page size draws`() {
        val m = paimon("pc")
        val latestId = assertNotNull(m.snapshots.last().metadata.id)
        val graph = GraphLayoutService.layoutGraph(m, showRows = false, policy = AggregationPolicy(pageSize = 1))
        assertEquals(null, graph.nodeById["psnap_$latestId"], "the page size should have folded the latest snapshot out")

        val table = graph.nodes.filterIsInstance<GraphNode.TableNode>().single()
        val current = assertNotNull((table.maintenance.value as PaimonMaintenanceInput).current)
        assertEquals("psnap_$latestId", current.id)
        assertTrue(current.hasPrimaryKey)
        assertTrue(current.bucketLsms!!.isNotEmpty(), "the latest snapshot's trees should replay")
    }
}
