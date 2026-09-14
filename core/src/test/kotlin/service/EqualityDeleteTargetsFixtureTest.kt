package service

import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import model.DeleteFileKind
import model.FixtureCatalog
import model.UnifiedSnapshot
import model.UnifiedTableModel
import model.deleteReach
import model.liveFilesOf
import model.rowLookupInputOf

/**
 * What each equality delete removes, read off the scripts: `eqdel`'s delete on `name` removes
 * one row from each of its two files; `eqren` the same through the rename; `eqpart`'s
 * partition-keyed delete removes one row from the `p=y` file alone and its global delete one
 * from each of three; `fup`'s commit-2 delete removes `(2, b)` from commit 1's file, and its
 * commit-1 delete is paired with nothing, being at the sequence number of the only file older
 * than the next commit's. And on every table, a delete removes from a file no more rows than
 * the count from the file's own end says every delete removes from it.
 */
class EqualityDeleteTargetsFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun model(fixture: String) = UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/$fixture").absolutePath))

    private fun current(model: UnifiedTableModel): UnifiedSnapshot {
        val id = assertNotNull(model.metadatas.last().metadata.currentSnapshotId)
        return model.metadatas.asReversed().firstNotNullOf { um -> um.snapshots.firstOrNull { it.metadata.snapshotId == id } }
    }

    private fun input(model: UnifiedTableModel) = current(model).let { assertNotNull(model.rowLookupInputOf(it, liveFilesOf(it), deleteReach(it))) }

    /** Every equality delete of the current snapshot, oldest first, as `(file name -> matched rows)` per candidate. */
    private fun targets(fixture: String): List<EqualityDeleteTargets.Result> {
        val input = input(model(fixture))
        return input.deleteFiles.filter { it.kind == DeleteFileKind.EQUALITY }
            .sortedBy { d -> input.reach.first { it.deleteKey == d.key }.sequenceNumber }
            .map { assertNotNull(EqualityDeleteTargets.count(input, it.key), it.recordedPath) }
    }

    private fun EqualityDeleteTargets.Result.byFile() = files.associate { it.file.recordedPath.substringAfterLast('/') to it.matched }

    @Test
    fun `eqdel's delete on name removes one row from each of its two files`() {
        val result = targets("eqdel").single()
        assertEquals(listOf(1L, 1L), result.files.map { it.matched }, result.byFile().toString())
        assertEquals(2L, result.matched)
        assertEquals(false, result.removesNothing)
        assertEquals(0, result.failed + result.filesLeft)
    }

    @Test
    fun `eqren's delete holds name and the schema calls it label, and it still removes the same two rows`() {
        val result = targets("eqren").single()
        assertEquals(listOf("label"), result.delete.equalityColumns)
        assertEquals(listOf(1L, 1L), result.files.map { it.matched }, result.byFile().toString())
        // Row 8 (label bravo) was written after the delete, at a higher sequence number: not a candidate.
        assertEquals(2, result.files.size)
    }

    @Test
    fun `eqpart's partition-keyed delete removes from the p=y file alone and its global delete from all three`() {
        val (partitioned, global) = targets("eqpart")
        assertEquals(1, partitioned.files.size, partitioned.byFile().toString())
        assertTrue(partitioned.files.single().file.recordedPath.contains("p=y"), partitioned.byFile().toString())
        assertEquals(1L, partitioned.matched)
        assertEquals(3, global.files.size, global.byFile().toString())
        assertEquals(listOf(1L, 1L, 1L), global.files.map { it.matched }, global.byFile().toString())
    }

    @Test
    fun `fup's commit-1 delete is paired with nothing and its commit-2 delete removes one row from commit 1's file`() {
        val (first, second) = targets("fup")
        assertEquals(0, first.files.size, first.byFile().toString())
        assertEquals(true, first.removesNothing)
        assertEquals(1, second.files.size, second.byFile().toString())
        assertEquals(1L, second.matched)
    }

    @Test
    fun `no equality delete removes more rows from a file than every delete removes from it, on any table`() {
        var deletes = 0
        FixtureCatalog.iceberg.forEach { fixture ->
            val model = model(fixture)
            val input = input(model)
            val equality = input.deleteFiles.filter { it.kind == DeleteFileKind.EQUALITY }
            if (equality.isEmpty()) return@forEach
            val removed = LiveRowCount.count(input).files.associate { it.file.recordedPath to it.removed }
            equality.forEach { delete ->
                val result = assertNotNull(EqualityDeleteTargets.count(input, delete.key))
                deletes++
                assertEquals(0, result.failed + result.filesLeft, "$fixture/${delete.recordedPath}: $result")
                result.files.forEach { match ->
                    assertTrue(assertNotNull(match.matched) <= removed.getValue(match.file.recordedPath), "$fixture/${delete.recordedPath} against ${match.file.recordedPath}: ${match.matched} matched, ${removed[match.file.recordedPath]} removed")
                }
            }
        }
        assertTrue(deletes >= 8, "$deletes equality deletes swept")
    }

    @Test
    fun `a delete file the snapshot does not pair, and a positional delete, are refused rather than counted`() {
        val input = input(model("eqdel"))
        assertNull(EqualityDeleteTargets.count(input, "no-such-delete"))
        val positional = input.deleteFiles.first { it.kind == DeleteFileKind.POSITIONAL }
        assertTrue(runCatching { EqualityDeleteTargets.count(input, positional.key) }.exceptionOrNull() is IllegalArgumentException)
    }
}
