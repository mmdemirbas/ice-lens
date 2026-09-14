package model

import service.GraphLayoutService
import service.AggregationPolicy
import service.StatsCheckReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The table-wide statistics sweep: the fold over injected reads, and the targets every fixture
 * hands it — which have to be the current snapshot's live files, every one of them, whether or
 * not the graph draws them.
 */
class FileStatsSweepTest {

    private fun target(name: String, lower: Long, rows: Long) = FileStatsTarget(
        name = name, localPath = "/wh/$name",
        recorded = listOf(RecordedColumnStats(1, "id", lower, 9L, "$lower", "9", nullCount = 0L, valueCount = rows, nanCount = null)),
        recordedRows = rows,
    )

    /** What a read of a file holding ids 1..9 in nine rows answers. */
    private fun readOf(rows: Long = 9L) = { t: FileStatsTarget -> checkStats(t.recorded, mapOf("id" to ActualColumnStats(1L, 9L, 0L, null)), rows, t.recordedRows) }

    @Test
    fun `a read that throws is one unreadable file, a figure the file contradicts is one finding, and the cap is stated`() {
        val targets = listOf(target("a.parquet", 1, 9), target("b.parquet", 2, 9), target("c.parquet", 1, 10), target("d.parquet", 1, 9), target("e.parquet", 1, 9))
        val sweep = sweepFileStats(targets, max = 4) { t ->
            if (t.name == "d.parquet") throw IllegalStateException("no such file") else readOf()(t)
        }
        assertEquals(5, sweep.filesTotal)
        assertEquals(4, sweep.filesRead)
        assertEquals(listOf("d.parquet" to "no such file"), sweep.unreadable)
        // a, b and c were read: nulls, values, lower and upper bound on `id`, and the row count — five figures each.
        assertEquals(3 * 5, sweep.figures)
        assertEquals(
            listOf(
                IntegrityFinding(IntegrityCheck.FILE_STATISTICS, "b.parquet", "id lower bound", "2", "a smaller 1"),
                IntegrityFinding(IntegrityCheck.FILE_STATISTICS, "c.parquet", "row count", "10", "9"),
                IntegrityFinding(IntegrityCheck.FILE_STATISTICS, "c.parquet", "id values", "10", "9"),
            ),
            sweep.findings,
        )
        assertEquals("4 of 5 live files read, 3 of their 15 figures disagree", sweep.describe)
        assertEquals("every one of the 10 figures agrees on all 2 live files", sweepFileStats(targets.take(2).map { target(it.name, 1, 9) }, read = readOf()).describe)
        assertEquals("no live data file to read", sweepFileStats(emptyList(), read = readOf()).describe)
    }

    @Test
    fun `every Iceberg fixture's targets are the current snapshot's live files, and every one agrees with its rows`() {
        var files = 0
        for (fixture in FixtureCatalog.iceberg) {
            // orcfmt's files are unreadable by design — DuckDB has no ORC reader — and DataFileFormatFixtureTest holds what the sweep says of them.
            if (fixture == "orcfmt") continue
            val model = FixtureCatalog.icebergModel(fixture)
            val targets = model.fileStatsTargets()
            val current = model.metadatas.last().metadata.currentSnapshotId
            // Every live file but a deletion vector, whose Puffin container has no rows to count.
            val live = current?.let { id -> model.metadatas.asReversed().firstNotNullOfOrNull { um -> um.snapshots.firstOrNull { it.metadata.snapshotId == id } } }
                ?.takeIf { !it.expired }?.let { liveFilesOf(it).map { f -> normalizeFilePath(f.path) }.filterNot { it.endsWith(".puffin") }.toSet() } ?: emptySet()
            assertEquals(live.map { it.substringAfterLast('/') }.toSet(), targets.map { it.name }.toSet(), fixture)
            assertEquals(live.size, targets.size, "$fixture lists a file twice or not at all")
            val sweep = sweepFileStats(targets, max = Int.MAX_VALUE) { StatsCheckReader.check(it.localPath, it.recorded, it.recordedRows) }
            assertEquals(emptyList(), sweep.unreadable, fixture)
            assertEquals(emptyList(), sweep.findings, fixture)
            files += sweep.filesRead
        }
        assertTrue(files >= 90, "$files files")
    }

    @Test
    fun `every Paimon fixture's targets are the latest snapshot's live files, and every one agrees with its rows`() {
        var files = 0
        for (fixture in FixtureCatalog.paimon) {
            val model = FixtureCatalog.paimonModel(fixture)
            val targets = model.fileStatsTargets()
            val live = model.snapshots.lastOrNull()?.let { replayPaimonSnapshot(it).liveEntries.values.map { e -> e.path.toString() }.toSet() } ?: emptySet()
            assertEquals(live, targets.map { it.localPath }.toSet(), fixture)
            val sweep = sweepFileStats(targets, max = Int.MAX_VALUE) { StatsCheckReader.check(it.localPath, it.recorded, it.recordedRows) }
            assertEquals(emptyList(), sweep.unreadable, fixture)
            assertEquals(emptyList(), sweep.findings, fixture)
            files += sweep.filesRead
        }
        assertTrue(files >= 60, "$files files")
    }

    /** The targets come from the model: at a page size of one the graph draws one file per manifest and the sweep still reads them all. */
    @Test
    fun `the targets cover the files aggregation folds out of the graph`() {
        val model = FixtureCatalog.icebergModel("maint")
        val graph = GraphLayoutService.layoutGraph(model, showRows = false, policy = AggregationPolicy(pageSize = 1))
        val table = graph.nodes.filterIsInstance<GraphNode.TableNode>().single()
        val drawn = graph.nodes.filterIsInstance<GraphNode.FileNode>().mapNotNull { it.localPath }.toSet()
        val targets = requireNotNull(table.fileStats.value)
        assertTrue(targets.size > drawn.size, "${targets.size} targets, ${drawn.size} drawn")
        assertEquals(model.fileStatsTargets().map { it.localPath }.toSet(), targets.map { it.localPath }.toSet())
    }
}
