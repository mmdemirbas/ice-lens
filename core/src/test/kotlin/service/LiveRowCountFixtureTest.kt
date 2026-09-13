package service

import model.DeleteFileKind
import model.UnifiedSnapshot
import model.UnifiedTableModel
import model.deleteReach
import model.liveFilesOf
import model.rowLookupInputOf
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `SELECT count(*)` as of a snapshot, read off the scripts — the rows their inserts left after
 * the deletes — against the count over the delete files the pairing reaches each data file
 * with. `mor` has two dangling positional deletes, which is where subtracting the delete files'
 * own record counts goes wrong; `eqdel` has both delete kinds on one table; `v3` a vector.
 * `maint` is walked snapshot by snapshot, because its position-delete rewrite and its manifest
 * rewrite change the files without changing the answer.
 */
class LiveRowCountFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun model(fixture: String) = UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/$fixture").absolutePath))

    private fun current(model: UnifiedTableModel): UnifiedSnapshot {
        val id = assertNotNull(model.metadatas.last().metadata.currentSnapshotId)
        return model.metadatas.asReversed().firstNotNullOf { um -> um.snapshots.firstOrNull { it.metadata.snapshotId == id } }
    }

    private fun countOf(model: UnifiedTableModel, snapshot: UnifiedSnapshot): LiveRowCount.Result =
        LiveRowCount.count(assertNotNull(model.rowLookupInputOf(snapshot, liveFilesOf(snapshot), deleteReach(snapshot))))

    private val expected = mapOf("mor" to 5, "eqdel" to 4, "v3" to 4, "maint" to 6, "lineage" to 5, "pstats" to 4, "fup" to 3)

    @Test
    fun `every table with deletes reads as the rows its script left`() {
        expected.forEach { (fixture, rows) ->
            val model = model(fixture)
            val result = countOf(model, current(model))
            assertEquals(rows.toLong(), result.live, "$fixture: $result")
            assertEquals(0, result.failed + result.filesLeft, "$fixture: $result")
        }
    }

    @Test
    fun `a table with no delete files reads as its record count, with no file opened`() {
        listOf("test", "parted", "branched", "sorted", "evolved").forEach { fixture ->
            val model = model(fixture)
            val result = countOf(model, current(model))
            assertEquals(result.recordCount, result.live, fixture)
            assertEquals(0, result.opened, "$fixture opened a file with no delete to apply")
            assertEquals(0, result.reached, fixture)
        }
    }

    @Test
    fun `a dangling delete removes nothing, and the delete that reaches a file removes one row`() {
        val mor = model("mor")
        val result = countOf(mor, current(mor))
        assertEquals(6L, result.recordCount, "six rows in the data files as written")
        assertEquals(1L, result.removed, "one row (id 7) removed by the delete written after the compaction")
        val reached = result.files.filter { it.kinds.isNotEmpty() }
        assertEquals(1, reached.size, "$reached")
        assertEquals(setOf(DeleteFileKind.POSITIONAL), reached.single().kinds)
        assertTrue(reached.single().opened)
    }

    @Test
    fun `a file only a vector reaches is counted from the vector without being opened`() {
        val v3 = model("v3")
        val result = countOf(v3, current(v3))
        val vectored = result.files.filter { DeleteFileKind.DELETION_VECTOR in it.kinds }
        assertTrue(vectored.isNotEmpty())
        assertTrue(vectored.none { it.opened }, "$vectored")
        assertEquals(vectored.size.toLong(), vectored.sumOf { it.removed }, "each vector marks one row")
    }

    @Test
    fun `equality and positional deletes on one file remove distinct rows`() {
        val eqdel = model("eqdel")
        val result = countOf(eqdel, current(eqdel))
        assertEquals(7L, result.recordCount)
        assertEquals(3L, result.removed, "2 and 6 by equality, 3 by position")
        assertTrue(result.files.any { DeleteFileKind.EQUALITY in it.kinds && it.opened })
    }

    /** Every commit of `maint`, in order: the rewrites change the files and not the answer. */
    @Test
    fun `the count follows every commit of a maintained table`() {
        val maint = model("maint")
        val snapshots = maint.metadatas.last().snapshots.sortedBy { it.metadata.sequenceNumber ?: 0L }
        val counts = snapshots.map { countOf(maint, it).live }
        // insert 4, insert 4, delete 2, delete 6, rewrite_data_files, delete 8, rewrite_position_delete_files, insert 2, delete 9, rewrite_manifests
        assertEquals(listOf<Long?>(4, 8, 7, 6, 6, 5, 5, 7, 6, 6), counts, snapshots.map { it.metadata.summary["operation"] }.toString())
    }
}
