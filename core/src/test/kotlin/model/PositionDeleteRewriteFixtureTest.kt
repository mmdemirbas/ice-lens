package model

import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import service.PositionDeleteRewriteDrops

/**
 * `rewrite_position_delete_files` planned the way the action plans it and read the way it
 * rewrites, against the one such rewrite the fixtures ran: `maint`'s, under `rewrite-all`, whose
 * summary records three positions removed and one written. The plan on the snapshot before it
 * has to name the three files and the read has to keep one position and drop two — the two
 * dangling records the compaction before it left. `mor` holds the same shape unrewritten, `v3`
 * the refusal, and the size rules are planted, since no fixture's delete files reach 48 MB.
 */
class PositionDeleteRewriteFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun model(fixture: String) = UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/$fixture").absolutePath))

    private fun snapshots(m: UnifiedTableModel) =
        m.metadatas.flatMap { it.snapshots }.filter { !it.expired }.distinctBy { it.metadata.snapshotId }.sortedBy { it.metadata.sequenceNumber }

    private fun current(m: UnifiedTableModel): UnifiedSnapshot {
        val id = assertNotNull(m.metadatas.last().metadata.currentSnapshotId)
        return m.metadatas.asReversed().firstNotNullOf { um -> um.snapshots.firstOrNull { it.metadata.snapshotId == id } }
    }

    private fun options(m: UnifiedTableModel, rewriteAll: Boolean = false): PositionDeleteRewriteOptions {
        val latest = m.metadatas.last().metadata
        return PositionDeleteRewriteOptions.forTable(latest.properties, latest.formatVersion).copy(rewriteAll = rewriteAll)
    }

    private fun read(m: UnifiedTableModel, s: UnifiedSnapshot, plan: PositionDeleteRewritePlan): PositionDeleteRewriteDrops.Result {
        val live = liveFilesOf(s)
        return PositionDeleteRewriteDrops.read(plan, live, assertNotNull(m.rowLookupInputOf(s, live, deleteReach(s))))
    }

    @Test
    fun `the plan before maint's rewrite names its three delete files, and the read keeps the one position the rewrite wrote`() {
        val m = model("maint")
        val all = snapshots(m)
        val rewrite = all.first { it.metadata.summary?.get("removed-position-delete-files") != null }
        val before = all.last { (it.metadata.sequenceNumber ?: 0) < (rewrite.metadata.sequenceNumber ?: 0) }
        val live = liveFilesOf(before)

        // The script's call: rewrite-all. One unpartitioned group of the three live delete files.
        val plan = planPositionDeleteRewrite(live, options(m, rewriteAll = true))
        assertNull(plan.refused)
        assertEquals(1, plan.groups.size, plan.groups.toString())
        val group = plan.groups.single()
        assertEquals("", group.partition)
        assertEquals(3, group.files.size)
        assertEquals(listOf(RewriteGroupReason.REWRITE_ALL), group.reasons)
        assertEquals(1L, group.outputFiles)
        assertEquals(rewrite.metadata.summary?.get("removed-position-delete-files")?.toInt(), plan.rewrittenFiles.size)

        // Read: the summary of the rewrite is the oracle for the positions kept and dropped.
        val result = read(m, before, plan)
        assertTrue(result.complete, result.toString())
        assertEquals(rewrite.metadata.summary?.get("added-position-deletes")?.toLong(), result.kept, result.toString())
        assertEquals(rewrite.metadata.summary?.get("removed-position-deletes")?.toLong(), result.kept + result.dropped, result.toString())
        assertEquals(2L, result.dropped)
        // The two dangling files name the compacted-away data files; the live one names the compacted file.
        val dangling = result.files.filter { it.dropped > 0 }
        assertEquals(2, dangling.size)
        assertTrue(dangling.all { f -> f.targets.all { !it.kept && it.reason.startsWith("no live data file at this path") } }, dangling.toString())
        assertEquals(1, result.files.count { it.kept == 1L && it.dropped == 0L })

        // A bare call leaves the same three alone: all wrongly sized, three short of min-input-files.
        val bare = planPositionDeleteRewrite(live, options(m))
        assertEquals(3, bare.candidateCount)
        assertTrue(bare.rewrittenGroups.isEmpty(), bare.groups.toString())
    }

    @Test
    fun `mor's two dangling delete files drop every position and the live one keeps its one`() {
        val m = model("mor")
        val s = current(m)
        val plan = planPositionDeleteRewrite(liveFilesOf(s), options(m, rewriteAll = true))
        assertEquals(3, plan.rewrittenFiles.size, plan.groups.toString())
        val result = read(m, s, plan)
        assertTrue(result.complete, result.toString())
        assertEquals(1L, result.kept)
        assertEquals(2, result.files.count { it.kept == 0L && it.dropped > 0L }, result.toString())
        // The pairing's dangling verdict and the read's agree file by file.
        val reach = deleteReach(s).associateBy { normalizeFilePath(it.deletePath) }
        result.files.forEach { f ->
            assertEquals(reach.getValue(normalizeFilePath(f.delete.path)).isDangling, f.kept == 0L, f.toString())
        }
    }

    @Test
    fun `a v3 table is refused, whatever its delete files`() {
        val m = model("v3")
        val plan = planPositionDeleteRewrite(liveFilesOf(current(m)), options(m, rewriteAll = true))
        assertEquals(PositionDeleteRewriteOptions.REFUSED_V3, plan.refused)
        assertTrue(plan.deleteFileCount >= 1, plan.filesByPartition.toString())
        assertTrue(plan.groups.isEmpty())
    }

    @Test
    fun `on every table the read drops exactly the positions naming no live data file, and keeps the rest`() {
        var files = 0
        FixtureCatalog.iceberg.forEach { fixture ->
            val m = model(fixture)
            val s = current(m)
            val live = liveFilesOf(s)
            val plan = planPositionDeleteRewrite(live, options(m, rewriteAll = true))
            if (plan.refused != null || plan.rewrittenFiles.isEmpty()) return@forEach
            val result = read(m, s, plan)
            // The one file this cannot read is `orcfmt`'s ORC delete, named with the reason and not counted.
            if (fixture == "orcfmt") {
                assertTrue(result.files.all { it.error?.contains("ORC") == true }, result.toString())
                return@forEach
            }
            assertTrue(result.complete, "$fixture: $result")
            val livePaths = live.filter { it.content == DataFileContent.DATA }.map { normalizeFilePath(it.path) }.toSet()
            result.files.forEach { f ->
                files++
                f.targets.forEach { t -> assertEquals(normalizeFilePath(t.dataFilePath) in livePaths, t.kept, "$fixture/${f.delete.path}: $t") }
                assertEquals(f.delete.recordCount, f.kept + f.dropped, "$fixture/${f.delete.path}: the file's positions are its record_count")
            }
        }
        assertTrue(files >= 8, "$files delete files read")
    }

    @Test
    fun `the size rules are the data rewriter's three, planted`() {
        fun delete(name: String, bytes: Long, partition: String = "") = LiveFile("/wh/t/data/$name", DataFileContent.POSITION_DELETES, 1, bytes, partition = partition, specId = 0)
        val options = PositionDeleteRewriteOptions(targetFileSizeBytes = 1000)
        // Five small files in one partition: enough input files. Two in another: left alone.
        val five = (1..5).map { delete("a$it.parquet", 10) }
        val two = (1..2).map { delete("b$it.parquet", 10, partition = "p=y") }
        val plan = planPositionDeleteRewrite(five + two + listOf(delete("ok.parquet", 900)), options)
        assertEquals(listOf(RewriteGroupReason.ENOUGH_INPUT_FILES), plan.groups.first { it.partition == "" }.reasons)
        assertEquals(emptyList(), plan.groups.first { it.partition == "p=y" }.reasons)
        assertEquals(7, plan.candidateCount, "the 900-byte file is within 750–1800 and no candidate")
        // Two files over the target together: enough content. One file past the maximum: too much content.
        val content = planPositionDeleteRewrite(listOf(delete("c1.parquet", 600), delete("c2.parquet", 600)), options)
        assertEquals(listOf(RewriteGroupReason.ENOUGH_CONTENT), content.groups.single().reasons)
        val big = planPositionDeleteRewrite(listOf(delete("d.parquet", 2000)), options)
        assertEquals(listOf(RewriteGroupReason.TOO_MUCH_CONTENT), big.groups.single().reasons)
        assertEquals(2L, big.groups.single().outputFiles)
    }
}
