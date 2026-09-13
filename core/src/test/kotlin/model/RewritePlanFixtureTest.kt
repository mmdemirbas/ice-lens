package model

import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Three fixtures ran `rewrite_data_files(options => map('min-input-files', '2'))` — `mor` (a
 * merge-on-read table, five files and two delete files), `maint` (two files, each with a
 * delete), `sorted` (three files, the sort strategy) — and each left a `replace` snapshot whose
 * live set is the one before it minus the files rewritten. The plan on the snapshot *before*,
 * under the same option, has to name exactly those files: that is the oracle, and the sort
 * strategy shares the size-based planner, so `sorted` is one too.
 *
 * What the fixtures did not run is read in `SizeBasedDataRewriter` and stated as such: under
 * the defaults `maint` and `sorted` would have been left alone (two and three files, no delete
 * marking a third of any), while `mor` would still have gone — five files is the minimum, and
 * one of them has a file-scoped delete over half its rows.
 */
class RewritePlanFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun model(fixture: String) =
        UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/$fixture").absolutePath))

    private fun dataPaths(s: UnifiedSnapshot) =
        liveFilesOf(s).filter { it.content == DataFileContent.DATA }.map { normalizeFilePath(it.path) }.toSet()

    /** The first `replace` that took data files out, and the snapshot it replaced them in. */
    private fun rewriteOf(fixture: String): Triple<UnifiedTableModel, UnifiedSnapshot, UnifiedSnapshot> {
        val m = model(fixture)
        val byId = m.metadatas.flatMap { it.snapshots }.filter { !it.expired }.associateBy { it.metadata.snapshotId }
        val replace = byId.values.sortedBy { it.metadata.sequenceNumber }.first { s ->
            s.metadata.summary["operation"] == "replace" &&
                byId[s.metadata.parentSnapshotId]?.let { dataPaths(it).size > dataPaths(s).size } == true
        }
        return Triple(m, assertNotNull(byId[replace.metadata.parentSnapshotId]), replace)
    }

    private fun options(m: UnifiedTableModel, minInputFiles: Int? = null): RewriteOptions {
        val latest = m.metadatas.last().metadata
        val base = RewriteOptions.forTable(latest.properties, latest.defaultSpecId)
        return if (minInputFiles == null) base else base.copy(minInputFiles = minInputFiles)
    }

    @Test
    fun `with min-input-files 2 the plan names exactly the files each rewrite took out`() {
        for (fixture in listOf("mor", "maint", "sorted")) {
            val (m, before, replace) = rewriteOf(fixture)
            val removed = dataPaths(before) - dataPaths(replace)
            assertTrue(removed.size >= 2, "$fixture: the replace should have taken files out")

            val plan = planRewrite(liveFilesOf(before), deleteReach(before), options(m, minInputFiles = 2))
            assertEquals(removed, plan.rewrittenPaths.map(::normalizeFilePath).toSet(), fixture)
            assertEquals(1, plan.rewrittenGroups.size, "$fixture: one partition, one group")
            assertTrue(RewriteGroupReason.ENOUGH_INPUT_FILES in plan.rewrittenGroups.single().reasons, fixture)
            assertEquals(1, plan.rewrittenGroups.single().outputFiles, "$fixture: kilobytes become one file")
        }
    }

    /** Read in `SizeBasedDataRewriter`, not run: what a bare call would have done on the same snapshots. */
    @Test
    fun `under the defaults, five files or a delete over a third of one is what it takes`() {
        val (mor, morBefore, _) = rewriteOf("mor")
        val morPlan = planRewrite(liveFilesOf(morBefore), deleteReach(morBefore), options(mor))
        val group = morPlan.rewrittenGroups.single()
        assertEquals(5, group.files.size)
        assertEquals(listOf(RewriteGroupReason.ENOUGH_INPUT_FILES, RewriteGroupReason.HIGH_DELETE_RATIO), group.reasons)
        // Every file is a candidate for its size alone; the ratio is what two of them add.
        assertTrue(group.files.all { RewriteFileReason.WRONGLY_SIZED in it.reasons })
        assertEquals(2, group.files.count { RewriteFileReason.HIGH_DELETE_RATIO in it.reasons })
        assertEquals(setOf(1.0, 0.5), group.files.filter { RewriteFileReason.HIGH_DELETE_RATIO in it.reasons }.map { it.deleteRatio }.toSet())

        for (fixture in listOf("maint", "sorted")) {
            val (m, before, _) = rewriteOf(fixture)
            val plan = planRewrite(liveFilesOf(before), deleteReach(before), options(m))
            assertEquals(emptyList(), plan.rewrittenGroups, fixture)
            assertTrue(plan.candidateCount >= 2, "$fixture: the files are still candidates, only the group is too small")
        }
    }

    @Test
    fun `a file under a spec that is not current is planned as unpartitioned, with every other such file`() {
        val m = model("respec")
        val latest = m.metadatas.last().metadata
        val current = m.metadatas.flatMap { it.snapshots }.first { it.metadata.snapshotId == latest.currentSnapshotId }
        val live = liveFilesOf(current)
        assertTrue(live.any { it.specId != latest.defaultSpecId }, "respec should carry files under an older spec")
        val plan = planRewrite(live, deleteReach(current), options(m, minInputFiles = 2))
        val unpartitioned = plan.groups.filter { it.partition == "" }
        assertEquals(1, unpartitioned.size)
        assertEquals(live.count { it.specId != latest.defaultSpecId }, unpartitioned.single().files.size)
    }

    @Test
    fun `a bare call's options come from the table`() {
        val fromTable = RewriteOptions.forTable(mapOf("write.target-file-size-bytes" to "134217728"), currentSpecId = 2)
        assertEquals(128L shl 20, fromTable.targetFileSizeBytes)
        assertEquals((128L shl 20) * 3 / 4, fromTable.minFileSizeBytes)
        assertEquals(2, fromTable.currentSpecId)
        assertEquals(RewriteOptions.DEFAULT_TARGET_FILE_SIZE_BYTES, RewriteOptions.forTable(emptyMap(), null).targetFileSizeBytes)
    }
}
