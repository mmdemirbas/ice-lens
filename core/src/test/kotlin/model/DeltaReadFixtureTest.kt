package model

import service.LiveRowCount
import service.RowLookup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A Delta version read through the Iceberg read path — see `DeltaRead.kt` — held to the rows
 * each fixture script's `SELECT` printed and to the rows every version held by the statements.
 */
class DeltaReadFixtureTest {

    private fun filter(text: String) = (parseScanFilter(text) as ScanFilterParse.Parsed).filter

    private fun lookup(name: String, version: Long, text: String): Map<Int, RowFate> {
        val input = FixtureCatalog.deltaModel(name).readInputAt(version)!!
        return RowLookup.lookup(input, filter(text), emptySet()).hits.associate { (it.cells["id"] as Number).toInt() to it.fate }
    }

    @Test
    fun `ddv's live count is the rows each version holds, the vectors applied and no file opened`() {
        val model = FixtureCatalog.deltaModel("ddv")
        // 1,000 inserted; 3 more; ids 2 and 1002 deleted; id 3 deleted; 1001 updated (one marked, one written).
        assertEquals(listOf(0L, 1000L, 1003L, 1001L, 1000L, 1000L), model.versions.map { LiveRowCount.count(model.readInputAt(it)!!).live })
        val current = LiveRowCount.count(model.readInputAt(5)!!)
        assertEquals(0, current.files.count { it.opened }, "a vector is counted from its cardinality, the data file left unopened")
    }

    @Test
    fun `ddv's rows are found with the fate the vectors give them`() {
        val hits = RowLookup.lookup(FixtureCatalog.deltaModel("ddv").readInputAt(5)!!, filter("id IN (1, 2, 3, 1001, 1002, 1003)"), emptySet()).hits
        val live = hits.filter { it.fate == RowFate.LIVE }.map { (it.cells["id"] as Number).toInt() to it.cells["v"] }.sortedBy { it.first }
        assertEquals(listOf(1 to "v1", 1001 to "updated", 1003 to "z"), live, "the script's SELECT")
        val deleted = hits.filter { it.fate == RowFate.VECTOR_DELETED }.map { (it.cells["id"] as Number).toInt() to it.cells["v"] }.sortedBy { it.first }
        assertEquals(listOf(2 to "v2", 3 to "v3", 1001 to "x", 1002 to "y"), deleted)
    }

    @Test
    fun `dpart reads its partition columns from the log, not from the file`() {
        assertEquals(mapOf(1 to RowFate.LIVE, 2 to RowFate.LIVE), lookup("dpart", 2, "region = 'eu'"))
        assertEquals(mapOf(4 to RowFate.LIVE), lookup("dpart", 2, "region IS NULL"))
        assertEquals(mapOf(4 to RowFate.LIVE, 5 to RowFate.LIVE), lookup("dpart", 2, "dt = 2024-03-07"))
        assertEquals(mapOf(3 to RowFate.LIVE), lookup("dpart", 2, "region = 'us' AND amount < 40"))
        assertEquals(5L, LiveRowCount.count(FixtureCatalog.deltaModel("dpart").readInputAt(2)!!).live)
    }

    @Test
    fun `dplain's copy-on-write rewrites leave only the rows the script printed`() {
        val hits = RowLookup.lookup(FixtureCatalog.deltaModel("dplain").readInputAt(5)!!, filter("id >= 1"), emptySet()).hits
        assertEquals(listOf(1 to "alpha", 3 to "charlie-updated", 4 to "delta"), hits.map { (it.cells["id"] as Number).toInt() to it.cells["name"] }.sortedBy { it.first })
        assertEquals(setOf(RowFate.LIVE), hits.map { it.fate }.toSet())
        // As of version 3, before the DELETE: bravo is still there.
        assertEquals(mapOf(2 to RowFate.LIVE), lookup("dplain", 3, "name = 'bravo'"))
    }

    private fun plan(name: String, text: String): Pair<Set<String>, Int> {
        val graph = service.GraphLayoutService.assembleGraph(FixtureCatalog.deltaModel(name), showRows = false, policy = service.AggregationPolicy.NONE)
            .let { GraphModel(it.nodes, it.edges, 0.0, 0.0) }
        val scan = evaluateScan(graph, filter(text))
        val read = scan.files.filterValues { it.fate == FileFate.WOULD_BE_READ }.keys
            .map { id -> (graph.nodeById[id] as GraphNode.DeltaFileNode).partitionValues.toString() }.toSet()
        return read to scan.files.size
    }

    @Test
    fun `a filter prunes dpart's files by their partition values and dplain's by their bounds`() {
        // Five live files, one per partition; a partition filter reads the files of its partitions only.
        val (eu, planned) = plan("dpart", "region = 'eu'")
        assertEquals(5, planned, "the scan plans over the live adds alone")
        assertEquals(setOf("{region=eu, dt=2024-03-05}", "{region=eu, dt=2024-03-06}"), eu)
        assertEquals(setOf("{region=null, dt=2024-03-07}"), plan("dpart", "region IS NULL").first)
        assertEquals(setOf("{region=null, dt=2024-03-07}", "{region=us, dt=2024-03-07}"), plan("dpart", "dt > 2024-03-06").first)
        // On the value column the bounds decide: amount 50.0 is in one file.
        assertEquals(setOf("{region=us, dt=2024-03-07}"), plan("dpart", "amount > 45").first)

        // The direction that loses rows: every file the lookup finds a row in is one the plan reads.
        for (text in listOf("id = 3", "name = 'alpha'", "id > 2", "name LIKE 'char%'")) {
            val graph = service.GraphLayoutService.assembleGraph(FixtureCatalog.deltaModel("dplain"), showRows = false, policy = service.AggregationPolicy.NONE)
                .let { GraphModel(it.nodes, it.edges, 0.0, 0.0) }
            val ruledOut = evaluateScan(graph, filter(text)).ruledOutFileKeys(graph)
            val input = FixtureCatalog.deltaModel("dplain").readInputAt(5)!!
            val all = RowLookup.lookup(input, filter(text), emptySet()).hits
            val pruned = RowLookup.lookup(input, filter(text), ruledOut).hits
            assertEquals(all.map { it.cells }, pruned.map { it.cells }, text)
            assertTrue(ruledOut.isNotEmpty() || text == "id > 2", "$text pruned nothing")
        }
    }
}
