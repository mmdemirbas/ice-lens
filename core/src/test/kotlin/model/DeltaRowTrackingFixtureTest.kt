package model

import service.GraphLayoutService
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Row tracking on `drt`, held to what Spark printed for `_metadata.row_id` and
 * `_metadata.row_commit_version` (`docs/fixtures/delta/drt.out`): the rows of the files the
 * inserts wrote take their file's base plus their position, and the three rows the `UPDATE`
 * rewrote keep their ids through the materialised column of a file whose own base is fresh.
 */
class DeltaRowTrackingFixtureTest {

    @Test
    fun `every live row carries the id and commit version Spark reads for it`() {
        val oracle = File(FixtureCatalog.repoRoot, "docs/fixtures/delta/drt.out").readLines()
            .filter { it.startsWith("rows\t") }
            .map { it.split('\t').let { f -> listOf(f[1], f[2], f[3], f[4]) } }
        val graph = GraphLayoutService.layoutGraph(FixtureCatalog.deltaModel("drt"), showRows = true)
        val liveFiles = graph.nodes.filterIsInstance<GraphNode.DeltaFileNode>().filter { it.liveNow }.map { it.id }.toSet()
        val rows = graph.nodes.filterIsInstance<GraphNode.RowNode>()
            .filter { r -> liveFiles.any { r.id.startsWith("row_${it}_") } }
            .map { it.resolvedData }
            .filter { it["id"] != null }
            .map { listOf(it["id"].toString(), it["v"].toString(), it[DELTA_ROW_ID].toString(), it[DELTA_ROW_COMMIT_VERSION].toString()) }
            .sortedBy { it[0].toInt() }
        assertEquals(oracle, rows)
        // The materialised columns are read under the name a reader asks for, not the table's internal one.
        assertTrue(graph.nodes.filterIsInstance<GraphNode.RowNode>().none { r -> r.resolvedData.keys.any { it.startsWith("_row-id-col-") } })
    }

    @Test
    fun `the high-water mark is the last id each commit handed out`() {
        val model = FixtureCatalog.deltaModel("drt")
        val tallies = (1L..3L).map { v -> deltaRowIdTallies(model.commitByVersion.getValue(v)) { model.stateAt(v - 1).getOrNull() }.single() }
        assertEquals(listOf(2L, 4L, 7L), tallies.map { it.counted })
        assertTrue(tallies.all { it.agrees == true })
        // A mark one short of the ids the UPDATE's file took is named.
        val update = model.commitByVersion.getValue(3)
        val planted = update.copy(actions = update.actions.map { a ->
            a.domainMetadata?.let { a.copy(domainMetadata = it.copy(configuration = """{"rowIdHighWaterMark":6}""")) } ?: a
        })
        assertEquals(false, deltaRowIdTallies(planted) { model.stateAt(2).getOrNull() }.single().agrees)
    }

    @Test
    fun `a file without row tracking keeps its cells as they are`() {
        val cells = mapOf("id" to 1, "v" to "a")
        assertEquals(cells, deltaRowLineage(cells, 0, null, null, null))
    }
}
