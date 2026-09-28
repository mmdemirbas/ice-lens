package model

import service.AggregationPolicy
import service.GraphLayoutService
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A maintenance line's plan in full — what `icelens plan <table> <procedure>` prints. Every line
 * of every checked-in table is reachable by its procedure's name and by its key and planned
 * without failing, every row is as wide as its headers, and the plans are held to the runs of
 * the procedures the fixtures record: where a fixture pair is a table before and after a
 * procedure, the files the second lacks are the rows the plan says go.
 */
class MaintenanceDetailTest {

    private val y2099 = Instant.parse("2099-01-01T00:00:00Z").toEpochMilli()

    /** The summary and its details under the inputs the command line gives them. */
    private class Planned(dir: File, val nowMs: Long, val readFiles: Boolean = false) {
        val node = GraphLayoutService.assembleGraph(readTableModel(dir.toPath()), showRows = false, policy = AggregationPolicy.DEFAULT)
            .nodes.filterIsInstance<GraphNode.TableNode>().first()
        val orphans = if (node.unreferencedFiles.isPresent) node.unreferencedFiles.value else null
        val unexisting = if (node.missingFiles.isPresent && node.paimonRowLookup.isPresent) {
            node.missingFiles.value?.let { r -> node.paimonRowLookup.value?.let { planUnexistingFiles(r, it) } }
        } else null
        val vacuum = (node.maintenance.value as? DeltaMaintenanceInput)?.model?.planVacuum(nowMs)?.getOrNull()
        val lines = maintenanceSummary(node, nowMs, orphans, unexisting, vacuum)

        fun detail(line: MaintenanceLine) = maintenanceDetail(node, line, nowMs, orphans, unexisting, vacuum, readFiles)
        fun detail(procedure: String) = detail(assertNotNull(lines.forProcedure(procedure), "no $procedure line"))
    }

    private fun MaintenanceDetail.table(titlePrefix: String): PlanTable =
        tables.single { it.title.startsWith(titlePrefix) }

    private fun PlanTable.column(header: String): List<String> {
        val i = headers.indexOf(header)
        assertTrue(i >= 0, "$title has no $header column: $headers")
        return rows.map { it[i] }
    }

    private fun PlanTable.column(header: String, where: String, equals: String): List<String> {
        val w = headers.indexOf(where)
        return rows.filter { it[w] == equals }.map { it[headers.indexOf(header)] }
    }

    private fun relativeFiles(dir: File): Set<String> =
        dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.toSet()

    private fun newestMtime(dir: File): Long = dir.walkTopDown().filter { it.isFile }.maxOf { it.lastModified() }

    @Test
    fun `a procedure is named by its key, the way the summary spells it or bare`() {
        assertEquals("expire_snapshots", maintenanceKey("expire_snapshots"))
        assertEquals("compact", maintenanceKey("sys.compact"))
        assertEquals("compact_manifest", maintenanceKey("sys.compact_manifest"))
        assertEquals("vacuum", maintenanceKey("VACUUM"))
        assertEquals("optimize", maintenanceKey("OPTIMIZE"))
        assertEquals("log_cleanup", maintenanceKey("log cleanup"))
        assertEquals("manifest_merge", maintenanceKey("next append's manifest merge"))
        assertEquals("manifest_merge", maintenanceKey("next commit's manifest merge"))
        assertEquals("manifest_merge", maintenanceKey("manifest_merge"))
    }

    @Test
    fun `every line of every table is found by its procedure and its key, and planned with every row as wide as its headers`() {
        val dirs = FixtureCatalog.iceberg.map(FixtureCatalog::icebergDir) +
            FixtureCatalog.paimon.map(FixtureCatalog::paimonDir) +
            FixtureCatalog.delta.map(FixtureCatalog::deltaDir)
        var planned = 0
        for (dir in dirs) {
            val p = Planned(dir, y2099)
            assertEquals(p.lines.size, p.lines.map { it.key }.toSet().size, "${dir.name}: two lines share a key — ${p.lines.map { it.key }}")
            assertNull(p.lines.forProcedure("rewrite_everything"), dir.name)
            for (line in p.lines) {
                assertSame(line, p.lines.forProcedure(line.procedure), "${dir.name}: ${line.procedure}")
                assertSame(line, p.lines.forProcedure(line.key), "${dir.name}: ${line.key}")
                val detail = p.detail(line)
                assertSame(line, detail.line)
                detail.tables.forEach { t ->
                    assertTrue(t.rows.isNotEmpty(), "${dir.name} ${line.key}: ${t.title} drawn empty")
                    t.rows.forEach { r -> assertEquals(t.headers.size, r.size, "${dir.name} ${line.key}: ${t.title} row $r") }
                }
                assertTrue(detail.notes.none { "not printed" in it }, "${dir.name} ${line.key}: ${detail.notes}")
                planned++
            }
        }
        assertTrue(planned > 500, "$planned lines planned")
    }

    @Test
    fun `expire_snapshots on sweep frees the files swept lacks`() {
        // swept is sweep after expire_snapshots(older_than => 2099, retain_last => 1): the
        // `older_than = now` column at 2099 is that call.
        val detail = Planned(FixtureCatalog.icebergDir("sweep"), y2099).detail("expire_snapshots")
        val gone = FixtureCatalog.icebergDir("sweep").walkTopDown().filter { it.isFile }.map { it.name }.toSet() -
            FixtureCatalog.icebergDir("swept").walkTopDown().filter { it.isFile }.map { it.name }.toSet()
        assertEquals(gone, detail.table("Files freed").column("File").map { it.substringAfterLast('/') }.toSet())
        assertEquals(4, detail.table("Snapshots").column("With older_than = now").count { it == "REMOVED" })
    }

    @Test
    fun `purge_files on br takes the files brp lacks`() {
        val br = FixtureCatalog.paimonDir("br")
        val detail = Planned(br, y2099).detail("sys.purge_files")
        val before = relativeFiles(br)
        val after = relativeFiles(FixtureCatalog.paimonDir("brp"))
        // The branch directories go whole and are named as branches; the two hint files are
        // rewritten under the same names.
        val gone = (before - after).filterNot { it.startsWith("branch/") }.map { it.substringAfterLast('/') }
            .filterNot { it == "EARLIEST" || it == "LATEST" }.toSet()
        val taken = detail.table("Taken").column("File")
        assertEquals(17, taken.size)
        assertEquals(gone, taken.map { it.substringAfterLast('/') }.toSet())
        assertTrue(detail.notes.any { "branches dropped: dev, empty" in it }, detail.notes.toString())
    }

    @Test
    fun `remove_orphan_files on orph removes the files orpha lacks, once past the three-day cutoff`() {
        val orph = FixtureCatalog.icebergDir("orph")
        val nowMs = newestMtime(orph) + 4L * 24 * 3_600_000
        val table = Planned(orph, nowMs).detail("remove_orphan_files").table("Files named by nothing")
        val gone = relativeFiles(orph) - relativeFiles(FixtureCatalog.icebergDir("orpha"))
        assertEquals(gone, table.column("File", where = "Verdict", equals = OrphanFate.REMOVED.label).toSet())
        assertEquals(listOf("data/_stray"), table.column("File", where = "Verdict", equals = OrphanFate.UNLISTED.label))
    }

    @Test
    fun `VACUUM on dvac a week and a day past its files deletes what dvaca lacks`() {
        val dvac = FixtureCatalog.deltaDir("dvac")
        val nowMs = newestMtime(dvac) + 8L * 24 * 3_600_000
        val table = Planned(dvac, nowMs).detail("VACUUM").table("Listing")
        fun dataFiles(dir: File) = dir.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") }.map { it.name }.toSet()
        val gone = dataFiles(dvac) - dataFiles(FixtureCatalog.deltaDir("dvaca"))
        assertEquals(6, gone.size)
        assertEquals(gone, table.column("Path", where = "Fate", equals = VacuumFate.DELETED.label).toSet())
        // Deleted first: the table leads with the answer.
        assertEquals(VacuumFate.DELETED.label, table.rows.first()[0])
    }

    @Test
    fun `OPTIMIZE on dopt rewrites into one the files dvac's OPTIMIZE removed`() {
        val detail = Planned(FixtureCatalog.deltaDir("dopt"), y2099).detail("OPTIMIZE")
        val commit = FixtureCatalog.deltaModel("dvac").commitByVersion.getValue(7)
        val bins = detail.table("Bins")
        val rewrittenBins = bins.column("Bin").withIndex().filter { it.value == "rewritten into one" }.map { "${it.index + 1}" }.toSet()
        assertEquals(1, rewrittenBins.size, bins.rows.toString())
        val files = detail.table("Files")
        val binColumn = files.column("Bin")
        assertEquals(
            commit.removes.map { it.path }.toSet(),
            files.column("File").filterIndexed { i, _ -> binColumn[i] in rewrittenBins }.toSet(),
        )
    }

    @Test
    fun `compact_manifest on pmm rewrites its four manifests into the one of ten entries pmma's snapshot 13 lists`() {
        val detail = Planned(FixtureCatalog.paimonDir("pmm"), y2099).detail("sys.compact_manifest")
        val written = FixtureCatalog.paimonModel("pmma").snapshots.single { it.metadata.id == 13L }
        val bins = detail.table("Bins")
        assertEquals(listOf("MERGED — 4 into 1"), bins.column("Verdict"))
        assertEquals(listOf("${written.baseManifests.single().entries.size}"), bins.column("Entries Out"))
        val merged = detail.table("Manifests").column("Manifest", where = "Merged", equals = "yes")
        assertEquals(4, merged.size)
        assertTrue(merged.none { it in written.baseManifests.map { m -> m.path.fileName.toString() } }, merged.toString())
    }

    @Test
    fun `fast_forward on br leaves named by nothing what the orphan check finds on brf, and refuses the empty branch`() {
        val detail = Planned(FixtureCatalog.paimonDir("br"), y2099).detail("fast_forward")
        val orphans = findUnreferencedFiles(FixtureCatalog.paimonModel("brf")).unreferenced.map { it.path.fileName.toString() }.toSet()
        assertEquals(orphans, detail.table("Left named by nothing").column("File", where = "After", equals = "dev").map { it.substringAfterLast('/') }.toSet())
        assertEquals(listOf("REFUSED"), detail.table("Branches").column("Verdict", where = "Branch", equals = "empty"))
    }

    @Test
    fun `fast_forward on sweepb moves dev onto main and refuses the other way, as the run did`() {
        val pairs = Planned(FixtureCatalog.icebergDir("sweepb"), y2099).detail("fast_forward").table("Pairs")
        fun verdict(branch: String, to: String) = pairs.rows.single { it[pairs.headers.indexOf("Branch")] == branch && it[pairs.headers.indexOf("To")] == to }[0]
        assertEquals(IcebergFastForwardVerdict.MOVES.label, verdict("dev", "main"))
        assertEquals(IcebergFastForwardVerdict.NOT_AN_ANCESTOR.label, verdict("main", "dev"))
    }

    @Test
    fun `on mor remove-dangling-deletes keeps all three delete files, and rewrite-all read keeps one position and drops two files`() {
        // rewrite-where.sql ran rewrite_data_files with remove-dangling-deletes on mor, which kept all
        // three delete files; the script's two dangling deletes are what rewrite-all drops.
        val rewrite = Planned(FixtureCatalog.icebergDir("mor"), y2099).detail("rewrite_data_files")
        val dangling = rewrite.table("Delete files after the rewrite").column("Verdict")
        assertEquals(3, dangling.size)
        assertTrue(dangling.all { it.startsWith("kept") }, dangling.toString())

        val unread = Planned(FixtureCatalog.icebergDir("mor"), y2099).detail("rewrite_position_delete_files")
        assertTrue(unread.tables.none { it.title.startsWith("What rewrite-all writes back") })
        assertTrue(unread.notes.any { it.startsWith("not read:") }, unread.notes.toString())
        val read = Planned(FixtureCatalog.icebergDir("mor"), y2099, readFiles = true).detail("rewrite_position_delete_files")
        val back = read.table("What rewrite-all writes back")
        assertEquals(listOf("1"), back.column("Positions", where = "Verdict", equals = "kept"))
        assertEquals(2, back.column("Delete File", where = "Verdict", equals = "DROPPED").toSet().size)
    }
}
