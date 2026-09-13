package model

import service.GraphLayoutService
import service.StatsCheckReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every recorded column statistic of every engine-written data file, against the file's rows.
 *
 * The sweep is the assertion: a bound counted the wrong way — a NaN inside a float's range, a
 * zoned timestamp against a local one, a truncated string read as exact — disagrees on some
 * checked-in file, and there are a few hundred of them across every type the corpus carries.
 * Then one bound is moved by hand to see the disagreement named, since a check that never
 * disagrees is a check that may not be looking.
 */
class StatsCheckFixtureTest {

    private fun checkedIceberg(name: String): List<Pair<String, StatsCheckResult>> {
        val graph = GraphLayoutService.layoutGraph(FixtureCatalog.icebergModel(name), showRows = false)
        return graph.nodes.filterIsInstance<GraphNode.FileNode>()
            .filter { it.localPath?.endsWith(".parquet") == true && java.nio.file.Files.isRegularFile(java.nio.file.Paths.get(it.localPath!!)) }
            .map { node -> node.data.filePath.orEmpty().substringAfterLast('/') to StatsCheckReader.check(node.localPath!!, node.recordedColumnStats(), node.data.recordCount) }
    }

    private fun checkedPaimon(name: String): List<Pair<String, StatsCheckResult>> {
        val graph = GraphLayoutService.layoutGraph(FixtureCatalog.paimonModel(name), showRows = false)
        return graph.nodes.filterIsInstance<GraphNode.PaimonDataFileNode>()
            .filter { it.localPath?.endsWith(".parquet") == true && java.nio.file.Files.isRegularFile(java.nio.file.Paths.get(it.localPath!!)) }
            .distinctBy { it.localPath }
            .map { node -> node.entry.file?.fileName.orEmpty() to StatsCheckReader.check(node.localPath!!, node.recordedColumnStats(), node.entry.file?.rowCount) }
    }

    @Test
    fun `every Iceberg fixture's recorded statistics agree with its files`() {
        var files = 0; var checked = 0
        val unchecked = mutableMapOf<String, Int>()
        for (fixture in FixtureCatalog.iceberg) {
            for ((file, result) in checkedIceberg(fixture)) {
                files++; checked += result.checked
                val bad = result.columns.filter { it.verdict == StatsVerdict.DISAGREES }
                assertTrue(bad.isEmpty() && result.rowsAgree != false, "$fixture/$file: rows ${result.rows} vs ${result.recordedRows}; $bad")
                result.columns.filter { it.verdict == StatsVerdict.NOT_CHECKED }.forEach { unchecked.merge(it.reason.replace(Regex("named \\S+"), "named …"), 1, Int::plus) }
            }
        }
        assertTrue(files >= 90 && checked >= 400, "$files files, $checked figures checked")
        // What could not be compared is a column the file does not hold — a dropped one under a rewritten manifest — never a type.
        assertTrue(unchecked.keys.all { it.startsWith("the file has no column named") }, "$unchecked")
    }

    @Test
    fun `every Paimon fixture's recorded statistics agree with its files`() {
        var files = 0; var checked = 0
        val unchecked = mutableMapOf<String, Int>()
        for (fixture in FixtureCatalog.paimon) {
            for ((file, result) in checkedPaimon(fixture)) {
                files++; checked += result.checked
                val bad = result.columns.filter { it.verdict == StatsVerdict.DISAGREES }
                assertTrue(bad.isEmpty() && result.rowsAgree != false, "$fixture/$file: rows ${result.rows} vs ${result.recordedRows}; $bad")
                result.columns.filter { it.verdict == StatsVerdict.NOT_CHECKED }.forEach { unchecked.merge(it.reason.replace(Regex("named \\S+"), "named …").replace(Regex("for \\S+$"), "for …"), 1, Int::plus) }
            }
        }
        assertTrue(files >= 60 && checked >= 150, "$files files, $checked figures checked")
        println("Paimon stats check: $files files, $checked figures; not checked: $unchecked")
    }

    @Test
    fun `a bound moved past a row, a null count off by one and a row count off are each named`() {
        val graph = GraphLayoutService.layoutGraph(FixtureCatalog.icebergModel("parted"), showRows = false)
        val node = graph.nodes.filterIsInstance<GraphNode.FileNode>().first { deleteKindOf(it.data) == null }
        val recorded = node.recordedColumnStats()
        val id = recorded.first { it.name == "id" }
        val name = recorded.first { it.name == "name" }
        val moved = recorded.map {
            when (it.name) {
                "id" -> it.copy(lower = (it.lower as Number).toLong() + 1, lowerShown = "${(it.lower as Number).toLong() + 1}")
                "name" -> it.copy(nullCount = (it.nullCount ?: 0L) + 1)
                else -> it
            }
        }
        val result = StatsCheckReader.check(node.localPath!!, moved, (node.data.recordCount ?: 0L) + 1)
        assertEquals(false, result.rowsAgree)
        val byColumn = result.columns.associateBy { it.column }
        assertEquals(StatsVerdict.DISAGREES, byColumn.getValue("id").verdict)
        assertTrue(byColumn.getValue("id").reason.contains("lower bound"), byColumn.getValue("id").reason)
        assertEquals(StatsVerdict.DISAGREES, byColumn.getValue("name").verdict)
        assertTrue(byColumn.getValue("name").reason.contains("nulls"), byColumn.getValue("name").reason)
        assertEquals(3, result.disagreements)
        // The untouched columns still agree, and the moved ones did before the move.
        assertTrue(result.columns.filter { it.column != "id" && it.column != "name" }.all { it.verdict == StatsVerdict.AGREES }, "$result")
        val before = StatsCheckReader.check(node.localPath!!, listOf(id, name), node.data.recordCount)
        assertEquals(0, before.disagreements, "$before")
    }
}
