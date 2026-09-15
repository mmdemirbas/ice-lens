package model

import service.GraphLayoutService
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `rewrite_data_files(where => …)` and `remove-dangling-deletes`, held to
 * `docs/fixtures/rewrite-where.sql`: the procedure run on copies of `evolved`, `eqren`, `mor`
 * and `fupp` under `min-input-files => 2`. A `where` picks files the way a scan does —
 * `planFileGroups` is `newScan().filter(where).ignoreResiduals().planFiles()` — so the plan on
 * the checked-in table, with the files [evaluateScan] rules out taken away, has to name exactly
 * the files each run rewrote and leave alone what it left. The dangling rule is by sequence
 * against the partition's lowest data sequence number, never by target.
 */
class RewriteWhereFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun model(fixture: String) =
        UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/$fixture").absolutePath))

    private fun current(m: UnifiedTableModel): UnifiedSnapshot {
        val latest = m.metadatas.last()
        return latest.snapshots.first { it.metadata.snapshotId == latest.metadata.currentSnapshotId }
    }

    private fun graphOf(m: UnifiedTableModel): GraphModel = GraphLayoutService.layoutGraph(m, showRows = false)

    private fun options(m: UnifiedTableModel, minInputFiles: Int = 2): RewriteOptions {
        val latest = m.metadatas.last().metadata
        return RewriteOptions.forTable(latest.properties, latest.defaultSpecId).copy(minInputFiles = minInputFiles)
    }

    /** The plan for `where => filter` at the current snapshot: the scan's ruled-out files taken away. */
    private fun planWhere(fixture: String, filter: String): RewritePlan {
        val m = model(fixture)
        val snap = current(m)
        val parsed = parseScanFilter(filter)
        assertTrue(parsed is ScanFilterParse.Parsed, "$fixture: $filter — $parsed")
        val ruledOut = evaluateScan(graphOf(m), parsed.filter).ruledOutFileKeys(graphOf(m))
        return planRewrite(liveFilesOf(snap), deleteReach(snap), options(m), ruledOut)
    }

    private fun names(paths: Collection<String>) = paths.map { it.substringAfterLast('/') }.toSet()

    @Test
    fun `evolved under where note = fifth rewrites the two files the scan opens and leaves the third`() {
        val plan = planWhere("evolved", "note = 'fifth'")
        assertEquals(setOf("00000-1-8796d368-d573-43ff-8e88-77d8d19fd2b0-0-00001.parquet"), names(plan.filteredOut.map { it.path }), "ruled out: its note bounds exclude 'fifth'")
        assertEquals(
            setOf("00000-0-a6c9cccf-8be8-4bf0-b8b2-e04615ebabbb-0-00001.parquet", "00000-2-d97e21b7-7c26-4140-8572-bec8986b8299-0-00001.parquet"),
            names(plan.rewrittenPaths),
            "2 1 1869 0 — the run's replace: deleted-data-files 2, added 1, records 3 out and 3 in",
        )
        assertEquals(1, plan.rewrittenGroups.single().outputFiles)
    }

    @Test
    fun `evolved under where id = 2 leaves one file, which no group of two can hold`() {
        val plan = planWhere("evolved", "id = 2")
        assertEquals(2, plan.filteredOut.size)
        assertEquals(emptySet(), plan.rewrittenPaths, "0 0 0 0 — one candidate under min-input-files 2")
        assertEquals(1, plan.candidateCount, "the small file is still a candidate; it takes two")
    }

    @Test
    fun `evolved under where id gt 1000 rewrites the two newer files, and a second call finds one`() {
        val plan = planWhere("evolved", "id > 1000")
        assertEquals(
            setOf("00000-1-8796d368-d573-43ff-8e88-77d8d19fd2b0-0-00001.parquet", "00000-2-d97e21b7-7c26-4140-8572-bec8986b8299-0-00001.parquet"),
            names(plan.rewrittenPaths),
            "2 1 2198 0 — into one file of 3 rows; the second call on the result printed 0 0 0 0",
        )
    }

    @Test
    fun `eqren under where label = bravo rewrites two files with their deletes applied, and label gt f leaves one`() {
        val bravo = planWhere("eqren", "label = 'bravo'")
        assertEquals(
            setOf("00000-0-06075099-35fc-447c-a728-a46639430370-0-00001.parquet", "00000-0-533395c9-97e2-418a-a9cd-2005e4a43e6e-0-00001.parquet"),
            names(bravo.rewrittenPaths),
            "2 1 1884 0 — deleted-records 5, added-records 3: the equality and positional deletes applied in the rewrite",
        )
        val group = bravo.rewrittenGroups.single()
        assertEquals(mapOf("00000-0-06075099-35fc-447c-a728-a46639430370-0-00001.parquet" to 2, "00000-0-533395c9-97e2-418a-a9cd-2005e4a43e6e-0-00001.parquet" to 0), group.files.associate { it.path.substringAfterLast('/') to it.deleteFileCount }, "the row-8 file was written after both deletes, which the sequence rule keeps off it")
        val f = planWhere("eqren", "label > 'f'")
        assertEquals(emptySet(), f.rewrittenPaths, "0 0 0 0")
        assertEquals(2, f.filteredOut.size)
    }

    private fun unpartitionedSingleSpec(m: UnifiedTableModel): Boolean {
        val latest = m.metadatas.last().metadata
        return latest.partitionSpecs.size == 1 && latest.partitionSpecs.single().fields.isEmpty()
    }

    private fun dangling(fixture: String, rewriteAll: Boolean): Pair<RewritePlan, DanglingDeletePlan> {
        val m = model(fixture)
        val snap = current(m)
        val live = liveFilesOf(snap)
        val plan = planRewrite(live, deleteReach(snap), options(m).copy(rewriteAll = rewriteAll))
        return plan to planDanglingDeletes(live, plan, snap.metadata.sequenceNumber ?: 0L, unpartitionedSingleSpec(m))
    }

    @Test
    fun `fupp rewritten whole loses all five delete files, each below or at the new floor of its partition`() {
        val (plan, dangling) = dangling("fupp", rewriteAll = true)
        assertEquals(4, plan.rewrittenPaths.size, "4 2 3524 0")
        assertEquals(null, dangling.skipped)
        assertEquals(5, dangling.files.size)
        assertEquals(5, dangling.removed.size, "removed-delete-files 5 in a replace of its own; total-delete-files 0 after: ${dangling.files}")
        assertTrue(dangling.files.all { it.floor == 2L }, "both partitions' new files at the starting snapshot's sequence number 2: ${dangling.files}")
    }

    @Test
    fun `mor rewritten whole keeps its three delete files, because the action stands down on an unpartitioned table`() {
        val (plan, dangling) = dangling("mor", rewriteAll = true)
        assertEquals(1, plan.rewrittenPaths.size, "1 1 1286 0")
        assertTrue(dangling.skipped!!.contains("unpartitioned"), dangling.skipped)
        assertEquals(emptyList(), dangling.removed, "total-delete-files 3 after, the entries at 3, 4 and 6 all live")
        // The rule itself would have taken the two below the new file's sequence number 6.
        assertEquals(mapOf(3L to true, 4L to true, 6L to false), dangling.files.associate { it.sequenceNumber to it.removed }, dangling.files.toString())
        assertTrue(dangling.files.all { it.floor == 6L })
    }

    @Test
    fun `eqren rewritten under where keeps both delete files, and a bare call that rewrites nothing runs no removal`() {
        val m = model("eqren")
        val snap = current(m)
        val live = liveFilesOf(snap)
        val parsed = parseScanFilter("label = 'bravo'") as ScanFilterParse.Parsed
        val where = planRewrite(live, deleteReach(snap), options(m), evaluateScan(graphOf(m), parsed.filter).ruledOutFileKeys(graphOf(m)))
        val afterWhere = planDanglingDeletes(live, where, snap.metadata.sequenceNumber ?: 0L, unpartitionedSingleSpec(m))
        assertTrue(afterWhere.skipped!!.contains("unpartitioned"), "the run under remove-dangling-deletes kept both: total-delete-files 2")
        // The rule alone would keep them too: the file written after the rename stays at its own number, below both deletes.
        assertTrue(afterWhere.files.none { it.removed }, afterWhere.files.toString())
        val bare = planRewrite(live, deleteReach(snap), options(m, minInputFiles = 5))
        val afterBare = planDanglingDeletes(live, bare, snap.metadata.sequenceNumber ?: 0L, unpartitionedSingleSpec = false)
        assertTrue(afterBare.skipped!!.contains("nothing is rewritten"), afterBare.skipped)
    }

    /** A delete the rule removes reaches no data file the rewrite leaves in place — the rule is sound against the pairing, on every fixture and both plans. */
    @Test
    fun `a removed dangling delete reaches no kept data file, across every fixture`() {
        var removedSomewhere = 0
        FixtureCatalog.iceberg.forEach { name ->
            val m = FixtureCatalog.icebergModel(name)
            val latest = m.metadatas.last()
            val snap = latest.snapshots.firstOrNull { it.metadata.snapshotId == latest.metadata.currentSnapshotId } ?: return@forEach
            val live = runCatching { liveFilesOf(snap) }.getOrNull() ?: return@forEach
            val reach = deleteReach(snap)
            for (rewriteAll in listOf(false, true)) {
                val plan = planRewrite(live, reach, options(m).copy(rewriteAll = rewriteAll))
                val dangling = planDanglingDeletes(live, plan, snap.metadata.sequenceNumber ?: 0L, unpartitionedSingleSpec = false)
                val kept = live.filter { it.content == DataFileContent.DATA && normalizeFilePath(it.path) !in plan.rewrittenPaths.map(::normalizeFilePath).toSet() }.map { normalizeFilePath(it.path) }.toSet()
                dangling.files.filter { it.removed }.forEach { d ->
                    removedSomewhere++
                    val r = reach.firstOrNull { normalizeFilePath(it.deletePath) == normalizeFilePath(d.path) }
                    val stillReached = r?.let { (it.reaches + it.mayReach).map(::normalizeFilePath).filter { p -> p in kept } }.orEmpty()
                    assertEquals(emptyList(), stillReached, "$name (rewrite-all $rewriteAll): ${d.path.substringAfterLast('/')} removed as ${d.reason} but paired with a kept file")
                }
            }
        }
        assertTrue(removedSomewhere > 0, "the sweep should exercise the removal")
    }

    @Test
    fun `the where is rendered as Spark SQL, strings quoted and numbers bare`() {
        val parsed = parseScanFilter("label = 'bravo' AND id > 2 OR d = 2024-03-05 OR note IS NULL OR name = 'o''hara'") as ScanFilterParse.Parsed
        assertEquals("label = 'bravo' AND id > 2 OR d = '2024-03-05' OR note is null OR name = 'o\\'hara'", parsed.filter.renderSparkSql())
    }

    @Test
    fun `a where that rules nothing out is the bare plan, and one that rules everything out plans nothing`() {
        FixtureCatalog.iceberg.forEach { name ->
            val m = FixtureCatalog.icebergModel(name)
            val latest = m.metadatas.last()
            val snap = latest.snapshots.firstOrNull { it.metadata.snapshotId == latest.metadata.currentSnapshotId } ?: return@forEach
            val live = runCatching { liveFilesOf(snap) }.getOrNull() ?: return@forEach
            val reach = deleteReach(snap)
            val bare = planRewrite(live, reach, options(m))
            assertEquals(bare.copy(filteredOut = emptyList()), planRewrite(live, reach, options(m), emptySet()), name)
            val all = planRewrite(live, reach, options(m), live.map { normalizeFilePath(it.path) }.toSet())
            assertEquals(emptyList(), all.groups, "$name: nothing considered, nothing grouped")
            assertEquals(live.count { it.content == DataFileContent.DATA }, all.filteredOut.size, name)
        }
    }
}
