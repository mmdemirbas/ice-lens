package model

import service.DeltaGraphBuilder
import service.GraphLayoutService
import service.TableFormat
import service.TableFormatDetector
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Delta graph and its checks over every checked-in Delta table. The checks' oracle is that an
 * engine-written log agrees with itself: `operationMetrics` with the commit's actions, a
 * checkpoint with the replay of the commits before it, `_last_checkpoint` with the checkpoint.
 */
class DeltaGraphFixtureTest {

    @Test
    fun `every Delta fixture is detected, drawn, and agrees with itself`() {
        assertTrue(FixtureCatalog.delta.size >= 3)
        for (name in FixtureCatalog.delta) {
            val dir = Paths.get(FixtureCatalog.deltaDir(name).absolutePath)
            assertEquals(TableFormat.DELTA, TableFormatDetector.detect(dir), name)
            val model = readTableModel(dir) as DeltaUnifiedTableModel
            val graph = GraphLayoutService.layoutGraph(model, showRows = false)
            val versions = graph.nodes.filterIsInstance<GraphNode.DeltaVersionNode>()
            assertEquals(model.versions, versions.map { it.version }.sorted(), name)
            assertTrue(graph.nodes.none { it is GraphNode.ErrorNode }, "$name: ${graph.nodes.filterIsInstance<GraphNode.ErrorNode>()}")

            for (v in versions) {
                assertTrue(v.tallies.all { it.agrees != false }, "$name v${v.version}: ${v.tallies.filter { it.agrees == false }}")
            }
            for (cp in graph.nodes.filterIsInstance<GraphNode.DeltaCheckpointNode>()) {
                val check = cp.check.value!!
                assertTrue(check.fromCommits && check.agrees, "$name checkpoint ${cp.checkpoint.version}: $check")
                assertEquals(model.stateAt(cp.checkpoint.version).getOrThrow().files.size, check.checkpointFileCount)
            }
            val last = model.lastCheckpointTallies()
            assertTrue(last.all { it.agrees != false }, "$name: $last")

            val report = model.integrityReport()
            assertEquals(emptyList(), report.findings, name)
            assertEquals(0, report.readErrors, name)
            assertTrue(report.checked > 0, name)

            val orphans = findUnreferencedFiles(model)
            assertEquals(emptyList(), orphans.unreferenced.map { orphans.relativePathOf(it) }, name)
        }
    }

    @Test
    fun `the tallies compare what the fixtures recorded`() {
        // Each figure the commits recorded, compared: a sweep that compared nothing would pass the test above.
        val ddv = DeltaGraphBuilder.buildGraph(FixtureCatalog.deltaModel("ddv")).nodes.filterIsInstance<GraphNode.DeltaVersionNode>().associateBy { it.version }
        val labels = ddv.getValue(5).tallies.map { it.label }.toSet()
        assertTrue(setOf("numAddedFiles", "numRemovedFiles", "numRemovedBytes", "numDeletionVectorsAdded", "numDeletionVectorsRemoved", "numDeletionVectorsUpdated").all { it in labels }, "$labels")
        assertEquals(2L, ddv.getValue(3).tallies.single { it.label == "numDeletedRows" }.counted)
        assertEquals(1L, ddv.getValue(4).tallies.single { it.label == "numDeletedRows" }.counted)

        // A changed figure is named: the check is not blind.
        val commit = FixtureCatalog.deltaModel("dplain").commitByVersion.getValue(4)
        val planted = commit.copy(actions = commit.actions.map { a -> a.commitInfo?.let { ci -> a.copy(commitInfo = ci.copy(operationMetrics = ci.operationMetrics!! + ("numRemovedBytes" to "1"))) } ?: a })
        assertEquals(listOf("numRemovedBytes"), deltaCommitTallies(planted).filter { it.agrees == false }.map { it.label })
    }

    @Test
    fun `ddv's rows are marked by the vectors their files carry`() {
        val model = FixtureCatalog.deltaModel("ddv")
        val graph = GraphLayoutService.assembleGraph(model, showRows = true)
        val live = graph.nodes.filterIsInstance<GraphNode.DeltaFileNode>().filter { it.liveNow }
        assertEquals(3, live.size)
        val big = live.single { it.stats?.numRecords == 1000L }
        val vector = big.deletionVector.value!!
        assertEquals(listOf(1L, 2L), vector.positions, "ids 2 and 3, at positions 1 and 2")
        assertTrue(vector.checksumMatches && vector.cardinalityAgrees)
        val rows = graph.nodes.filterIsInstance<GraphNode.RowNode>().filter { it.id.startsWith("row_${big.id}_") }
        assertEquals(listOf(false, true, true, false, false), rows.sortedBy { it.filePosition }.map { it.isDeletedByVector })
    }

    @Test
    fun `dpart's rows carry the partition values the file does not`() {
        val graph = GraphLayoutService.assembleGraph(FixtureCatalog.deltaModel("dpart"), showRows = true)
        val rows = graph.nodes.filterIsInstance<GraphNode.RowNode>()
        assertEquals(5, rows.size)
        assertEquals(setOf("eu", "us", "null"), rows.map { it.resolvedData["region"].toString() }.toSet())
        assertTrue(rows.all { it.resolvedData["dt"] != null })
    }
}
