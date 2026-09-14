package model

import service.GraphLayoutService
import service.LiveRowCount
import service.RowLookup
import service.StatsCheckReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `migrated`: a table whose first file plain Spark wrote and `add_files` registered — no field
 * ids in its footer, a `file:/wh/plain-files/…` path outside the table, and the name mapping
 * the procedure set, which a later `RENAME COLUMN name TO label` extended to `["name", "label"]`.
 * The script's own read is the oracle: `label` holds alpha, bravo, charlie.
 *
 * The same test holds the lookup to what a read returns on `evolved`, where a filter on a column
 * added after two files were written came back as a DuckDB error on those files before the
 * file was read under the schema's names.
 */
class MigratedFixtureTest {

    private val model = FixtureCatalog.icebergModel("migrated")

    @Test
    fun `a registered file's file-URI path is re-rooted beside the table, and its columns are placed by the name mapping`() {
        val graph = GraphLayoutService.layoutGraph(model, showRows = true)
        val plain = graph.nodes.filterIsInstance<GraphNode.FileNode>().first { it.data.filePath.orEmpty().startsWith("file:/wh/plain-files/") }
        assertEquals(PathResolution.REBUILT_BESIDE_TABLE, plain.pathResolution)
        assertTrue(java.io.File(assertNotNull(plain.localPath)).isFile, "${plain.localPath}")
        assertTrue(plain.localPath!!.replace('\\', '/').endsWith("/example/iceberg/plain-files/" + plain.data.filePath!!.substringAfterLast('/')))

        val mapping = assertNotNull(model.metadatas.last().metadata.nameMapping())
        assertEquals(listOf("name", "label"), mapping.fields.first { it.fieldId == 2 }.names, "the rename appended the new name")
        val rows = graph.nodes.filterIsInstance<GraphNode.RowNode>().filter { it.id.startsWith("row_${plain.id}_") }.sortedBy { it.resolvedData["id"].toString() }
        assertEquals(2, rows.size)
        val read = assertNotNull(rows.first().readAs.value)
        assertEquals(listOf("id", "label", "amount"), read.cells.map { it.name })
        assertEquals(listOf("1", "alpha", "10.50"), read.cells.map { it.value })
        assertTrue(read.cells.all { it.viaMapping && it.source == ProjectedCellSource.FILE }, "$read")
        assertEquals("name", read.cells[1].fileColumn)
        assertEquals(emptyList(), read.dropped + read.unmatched)
    }

    @Test
    fun `the lookup, the count and the check read a file under the schema's names`() {
        val input = assertNotNull(model.rowLookupInput())
        fun by(column: String, literal: String) = RowLookup.lookup(input, ScanFilter.Term(ScanPredicate(column, PredicateOp.EQ, literal)), emptySet())
        // `label` is the file's `name`, placed by the mapping; both files answer, neither errors.
        val alpha = by("label", "alpha")
        assertEquals(emptyList(), alpha.filesRead.mapNotNull { it.error })
        assertEquals(listOf("alpha"), alpha.hits.map { it.cells["label"].toString() })
        assertEquals(RowFate.LIVE, alpha.hits.single().fate)
        assertEquals(setOf("1", "2", "3"), by("id", "1").let { it.hits + by("id", "2").hits + by("id", "3").hits }.map { it.cells["id"].toString() }.toSet())
        assertTrue(by("id", "1").hits.single().cells.keys.containsAll(listOf("id", "label", "amount")), "${by("id", "1").hits}")

        val snapshot = model.metadatas.last().snapshots.last()
        val count = LiveRowCount.count(assertNotNull(model.rowLookupInputOf(snapshot, liveFilesOf(snapshot), deleteReach(snapshot))))
        assertEquals(3L, count.live, "$count")

        // The registered file's recorded statistics — computed by add_files from its footer — are placed by the mapping and agree.
        val targets = model.fileStatsTargets()
        assertEquals(2, targets.size)
        val plain = targets.first { it.name.startsWith("part-") }
        assertNotNull(plain.nameMapping)
        val checked = StatsCheckReader.check(plain.localPath, plain.recorded, plain.recordedRows, plain.nameMapping)
        assertEquals(emptyList(), checked.columns.filter { it.verdict != StatsVerdict.AGREES }.map { it.column to it.reason }, "$checked")
        assertEquals(3, checked.columns.size)
        // The manifest add_files wrote still names field 2 `name`, so the recorded statistic is
        // matched to the file's column by name even without the mapping; the mapping is what a
        // *read* needs, since a read asks for `label`.
        assertEquals(listOf("id", "name", "amount"), plain.recorded.map { it.name })
        val sweep = sweepFileStats(targets) { StatsCheckReader.check(it.localPath, it.recorded, it.recordedRows, it.nameMapping) }
        assertEquals(emptyList(), sweep.findings + sweep.unreadable)
    }

    @Test
    fun `a filter on a column added since a file was written reads that file as null there, not as an error`() {
        val evolved = FixtureCatalog.icebergModel("evolved")
        val input = assertNotNull(evolved.rowLookupInput())
        fun by(column: String, op: PredicateOp, literal: String) = RowLookup.lookup(input, ScanFilter.Term(ScanPredicate(column, op, literal)), emptySet())
        val fifth = by("note", PredicateOp.EQ, "fifth")
        assertEquals(emptyList(), fifth.filesRead.mapNotNull { it.error })
        assertEquals(3, fifth.filesRead.size)
        assertEquals(listOf("fifth"), fifth.hits.map { it.cells["note"].toString() })
        // `note IS NULL` is true of every row the first file holds — what Iceberg returns for a
        // file that predates the column — and of none in the second, whose rows carry a note.
        val nulls = by("note", PredicateOp.IS_NULL, "")
        assertEquals(emptyList(), nulls.filesRead.mapNotNull { it.error })
        assertEquals(setOf("1", "2"), nulls.hits.map { it.cells["id"].toString() }.toSet())
        // The first file's `name` reads under no name at all; a filter on `label` is not even a schema column now.
        assertTrue(nulls.hits.none { "name" in it.cells || "label" in it.cells }, "${nulls.hits.first().cells.keys}")

        // `defaults`: a row written before `region` existed matches `region = 'eu'` through its initial default.
        val defaults = assertNotNull(FixtureCatalog.icebergModel("defaults").rowLookupInput())
        val eu = RowLookup.lookup(defaults, ScanFilter.Term(ScanPredicate("region", PredicateOp.EQ, "eu")), emptySet())
        assertEquals(emptyList(), eu.filesRead.mapNotNull { it.error })
        assertEquals(setOf("1", "2"), eu.hits.map { it.cells["id"].toString() }.toSet(), "$eu")
        assertEquals(listOf("eu", "eu"), eu.hits.map { it.cells["region"].toString() })
        val us = RowLookup.lookup(defaults, ScanFilter.Term(ScanPredicate("region", PredicateOp.EQ, "us")), emptySet())
        assertEquals(setOf("3"), us.hits.map { it.cells["id"].toString() }.toSet(), "$us")
        assertNull(us.filesRead.firstOrNull { it.error != null })
    }
}
