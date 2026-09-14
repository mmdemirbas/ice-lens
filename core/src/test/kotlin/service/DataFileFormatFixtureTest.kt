package service

import model.FixtureCatalog
import model.GraphNode
import model.PredicateOp
import model.RowFate
import model.ScanFilter
import model.ScanPredicate
import model.deleteReach
import model.fileStatsTargets
import model.liveFilesOf
import model.paimonRowLookupInput
import model.rowLookupInput
import model.rowLookupInputOf
import model.sweepFileStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The data file formats other than Parquet, on the three tables written for them: `avrofmt`
 * and `pav` are Avro, which DuckDB reads through `read_avro` and without row positions;
 * `orcfmt` is ORC, which DuckDB cannot read at all, so what is asserted there is that every
 * reader says so — the same reason, named — rather than a magic-bytes error or a blank card.
 * Every other fixture is Parquet, which is how `read_parquet` was handed every file for the
 * life of the project under a comment saying it detected the format.
 */
class DataFileFormatFixtureTest {

    private fun rowNodes(fixture: String, paimon: Boolean = false): List<GraphNode.RowNode> {
        val model = if (paimon) FixtureCatalog.paimonModel(fixture) else FixtureCatalog.icebergModel(fixture)
        return GraphLayoutService.layoutGraph(model, showRows = true).nodes.filterIsInstance<GraphNode.RowNode>()
    }

    @Test
    fun `an Avro table's rows are read, without positions, and its Avro delete file is read as a delete file`() {
        val rows = rowNodes("avrofmt")
        val data = rows.filter { it.content == 0 }
        assertEquals(5, data.size)
        data.forEach { row ->
            assertNull(row.readError, row.resolvedData.toString())
            assertNull(row.filePosition, "Avro has no file_row_number: ${row.resolvedData}")
            assertTrue(row.resolvedData.containsKey("name"), row.resolvedData.toString())
        }
        assertEquals(setOf("alpha", "bravo", "charlie", "delta", "echo"), data.map { it.resolvedData["name"] }.toSet())
        val delete = rows.single { it.content == 1 }
        assertEquals("1", delete.resolvedData["pos"].toString(), delete.resolvedData.toString())

        val deleteFile = FixtureCatalog.icebergModel("avrofmt").metadatas.last().snapshots.flatMap { it.manifests }.flatMap { it.dataFiles }
            .first { it.metadata.dataFile?.content == 1 }
        val targets = SampleRowReader.queryPositionalDeleteTargets(deleteFile.path.toString())
        assertEquals(1, targets.size)
        assertEquals(1L, targets.single().positions)
    }

    /**
     * Iceberg's Avro writer records no column metrics — no bounds, no counts — so the positional
     * delete has no `file_path` bounds and the metadata cannot aim it: the plan attaches it to
     * both data files (the oracle in `deletes.txt`), and so does the pairing here. That is why a
     * row of the file it does not actually touch is `not decided` too: without a position, the
     * delete file's `(file_path, pos)` cannot be asked for the row.
     */
    @Test
    fun `an Avro table's positional delete records no bounds, so every row's fate is not decided and no file is counted`() {
        val model = FixtureCatalog.icebergModel("avrofmt")
        val snapshot = model.metadatas.last().snapshots.last()
        val reach = deleteReach(snapshot).single()
        assertNull(reach.targets.low, "$reach")
        assertEquals(2, reach.mayReach.size, "$reach")
        assertEquals(emptyList(), reach.reaches)

        val input = assertNotNull(model.rowLookupInput())
        for (id in listOf(1, 2, 4)) {
            val hit = RowLookup.lookup(input, ScanFilter.Term(ScanPredicate("id", PredicateOp.EQ, "$id")), emptySet()).hits.single()
            assertEquals(RowFate.UNKNOWN, hit.fate, "$hit")
            assertNull(hit.position)
            assertTrue(hit.note.orEmpty().contains("position"), "$hit")
            assertEquals("${id}", hit.cells["id"].toString())
        }

        val count = LiveRowCount.count(assertNotNull(model.rowLookupInputOf(snapshot, liveFilesOf(snapshot), deleteReach(snapshot))))
        assertEquals(2, count.files.size)
        count.files.forEach { f ->
            assertTrue(f.kinds.isNotEmpty(), "$f")
            assertTrue(f.error.orEmpty().contains("Parquet only"), "$f")
        }
        assertNull(count.live, "$count")
    }

    @Test
    fun `an Avro file's recorded figures are its row count alone, and it agrees`() {
        val targets = FixtureCatalog.icebergModel("avrofmt").fileStatsTargets()
        assertEquals(3, targets.size)
        // No bounds, no null or value counts: `columnStatsFor` lists a column only with a statistic, and there is none.
        targets.forEach { assertEquals(emptyList(), it.recorded, it.name) }
        val sweep = sweepFileStats(targets) { StatsCheckReader.check(it.localPath, it.recorded, it.recordedRows) }
        assertEquals(3, sweep.filesRead, "$sweep")
        assertEquals(emptyList(), sweep.unreadable)
        assertEquals(emptyList(), sweep.findings)
        assertEquals(3, sweep.figures, "$sweep")
    }

    /**
     * Paimon's default `file.compression` is zstd, and DuckDB's Avro reader answers "File header
     * contains an unknown codec" to a `zstandard` file — a line that does not name the codec. So
     * the header is read first and the refusal names it, on the row cards, the merged count and
     * the statistics sweep alike.
     */
    @Test
    fun `a Paimon Avro table on the default codec is refused by the codec's name everywhere`() {
        val rows = rowNodes("paz", paimon = true)
        assertEquals(2, rows.size)
        rows.forEach { row ->
            val error = assertNotNull(row.readError, row.resolvedData.toString())
            assertTrue(error.startsWith("DuckDB's Avro reader does not read the zstandard codec"), error)
        }
        val model = FixtureCatalog.paimonModel("paz")
        val count = PaimonMergedCount.count(assertNotNull(model.paimonRowLookupInput()))
        assertTrue(count.buckets.single().error.orEmpty().contains("zstandard codec"), "$count")
        val sweep = sweepFileStats(model.fileStatsTargets()) { StatsCheckReader.check(it.localPath, it.recorded, it.recordedRows) }
        assertEquals(1, sweep.unreadable.size, "$sweep")
        assertTrue(sweep.unreadable.single().second.contains("zstandard codec"), "$sweep")
        val lookup = PaimonRowLookup.lookup(assertNotNull(model.paimonRowLookupInput()), ScanFilter.Term(ScanPredicate("k", PredicateOp.EQ, "1")), emptySet())
        assertEquals(emptyList(), lookup.hits)
        assertTrue(lookup.filesRead.single().error.orEmpty().contains("zstandard codec"), "$lookup")
    }

    @Test
    fun `an ORC table says on every card, count and check that DuckDB has no ORC reader`() {
        val rows = rowNodes("orcfmt")
        assertEquals(6, rows.size)
        rows.forEach { row ->
            val error = assertNotNull(row.readError, row.resolvedData.toString())
            assertTrue(error.startsWith(ORC_UNREADABLE), error)
            assertEquals(setOf("file_no", "row_idx", GraphNode.RowNode.ROW_READ_ERROR_KEY), row.resolvedData.keys)
        }

        val model = FixtureCatalog.icebergModel("orcfmt")
        val snapshot = model.metadatas.last().snapshots.last()
        val count = LiveRowCount.count(assertNotNull(model.rowLookupInputOf(snapshot, liveFilesOf(snapshot), deleteReach(snapshot))))
        val reached = count.files.single { it.kinds.isNotEmpty() }
        assertTrue(reached.error.orEmpty().startsWith(ORC_UNREADABLE), "$reached")
        assertEquals(2L, count.files.single { it.kinds.isEmpty() }.live, "a file no delete reaches needs no read")
        assertNull(count.live)

        val lookup = RowLookup.lookup(assertNotNull(model.rowLookupInput()), ScanFilter.Term(ScanPredicate("id", PredicateOp.EQ, "1")), emptySet())
        assertEquals(emptyList(), lookup.hits)
        assertTrue(lookup.filesRead.isNotEmpty() && lookup.filesRead.all { it.error.orEmpty().startsWith(ORC_UNREADABLE) }, "$lookup")

        val sweep = sweepFileStats(model.fileStatsTargets()) { StatsCheckReader.check(it.localPath, it.recorded, it.recordedRows) }
        assertEquals(3, sweep.unreadable.size, "$sweep")
        assertTrue(sweep.unreadable.all { (_, why) -> why.startsWith(ORC_UNREADABLE) }, "$sweep")
        assertEquals(emptyList(), sweep.findings)
    }

    @Test
    fun `a Paimon Avro table merges, looks up and checks through read_avro`() {
        val model = FixtureCatalog.paimonModel("pav")
        val input = assertNotNull(model.paimonRowLookupInput())
        fun byKey(k: Int) = PaimonRowLookup.lookup(input, ScanFilter.Term(ScanPredicate("k", PredicateOp.EQ, "$k")), emptySet())
        val updated = byKey(2).hits.sortedBy { it.cells["v"].toString() }
        assertEquals(listOf(RowFate.SUPERSEDED, RowFate.LIVE), updated.map { it.fate }, "$updated")
        assertEquals(listOf("bravo", "bravo-updated"), updated.map { it.cells["v"] })
        updated.forEach { assertNull(it.position, "$it") }
        val removed = byKey(3).hits.sortedBy { it.cells["_VALUE_KIND"].toString() }
        assertEquals(setOf(RowFate.SUPERSEDED, RowFate.RETRACTION), removed.map { it.fate }.toSet(), "$removed")
        assertEquals(RowFate.LIVE, byKey(1).hits.single().fate)

        val rows = rowNodes("pav", paimon = true)
        assertEquals(5, rows.size)
        rows.forEach { assertNull(it.readError, it.resolvedData.toString()); assertNull(it.filePosition) }

        val sweep = sweepFileStats(model.fileStatsTargets()) { StatsCheckReader.check(it.localPath, it.recorded, it.recordedRows) }
        assertEquals(3, sweep.filesRead)
        assertEquals(emptyList(), sweep.unreadable)
        assertEquals(emptyList(), sweep.findings)
    }
}
