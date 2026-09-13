package model

import service.LiveRowCount
import service.RowLookup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `fup`: a merge-on-read table written by Flink's upsert sink — the one writer in the corpus that
 * puts a delete file in the same commit as the data file it can apply to. See
 * `docs/fixtures/flink-upsert.sql` for what each commit wrote, and why: the sink deletes a key
 * before writing its row, as a positional delete when the key is already in the open data file
 * and as an equality delete otherwise.
 *
 * What it settles that every Spark-written fixture could not: the sequence rule's boundary. A
 * positional delete applies at or below its number — commit 1's reaches commit 1's data file —
 * and an equality delete strictly below — commit 1's reaches nothing at all, and commit 2's
 * reaches commit 1's file and not its own. Flink's own read printed `(1, a2), (2, b2), (4, d)`.
 */
class FlinkUpsertFixtureTest {

    private val model by lazy { FixtureCatalog.icebergModel("fup") }
    private val current by lazy { model.metadatas.last().let { m -> m.snapshots.single { it.metadata.snapshotId == m.metadata.currentSnapshotId } } }

    private fun tail(path: String) = path.substringAfterLast('/')

    @Test
    fun `each commit wrote its deletes beside its data at one sequence number`() {
        val entries = current.manifests.flatMap { m -> m.dataFiles.map { effectiveSequenceNumber(it.metadata, m.metadata.sequenceNumber) to (it.metadata.dataFile!!.content ?: 0) } }
        val bySequence = entries.groupBy({ it.first }, { it.second })
        assertEquals(setOf(1L, 2L), bySequence.keys)
        // Commit 1: data, an equality delete, a positional delete. Commit 2: data and an equality delete.
        assertEquals(listOf(0, 1, 2), bySequence.getValue(1L).sorted())
        assertEquals(listOf(0, 2), bySequence.getValue(2L).sorted())
    }

    @Test
    fun `a positional delete reaches the data file of its own commit, an equality delete only an earlier one`() {
        val reach = deleteReach(current)
        val data = liveFilesOf(current).filter { it.content == 0 }.associate { effectiveSeq(it) to tail(it.path) }
        val first = data.getValue(1L)
        val second = data.getValue(2L)

        val positional = reach.single { it.kind == DeleteFileKind.POSITIONAL }
        assertEquals(1L, positional.sequenceNumber)
        assertEquals(listOf(first), positional.reaches.map(::tail))
        assertEquals(emptyList(), positional.mayReach)

        val equality1 = reach.single { it.kind == DeleteFileKind.EQUALITY && it.sequenceNumber == 1L }
        assertTrue(equality1.isDangling, "an equality delete written with the only file it could name applies to nothing: $equality1")

        val equality2 = reach.single { it.kind == DeleteFileKind.EQUALITY && it.sequenceNumber == 2L }
        assertEquals(listOf(first), equality2.mayReach.map(::tail))
        assertEquals(emptyList(), equality2.reaches)
        assertTrue(second !in equality2.mayReach.map(::tail))
    }

    @Test
    fun `the table reads as the three rows Flink read back`() {
        val input = assertNotNull(model.rowLookupInputOf(current, liveFilesOf(current), deleteReach(current)))
        val count = LiveRowCount.count(input)
        assertEquals(3L, count.live, "$count")
        assertEquals(0, count.failed + count.filesLeft)

        fun hits(id: Int) = RowLookup.lookup(input, ScanFilter.Term(ScanPredicate("id", PredicateOp.EQ, "$id")), emptySet()).hits
        // id 1: `a` at position 0 of commit 1's file, deleted by the positional delete of the same commit; `a2` live.
        assertEquals(mapOf("a" to RowFate.POSITION_DELETED, "a2" to RowFate.LIVE), hits(1).associate { it.cells.getValue("name").toString() to it.fate })
        // id 2: `b` in commit 1's file, deleted by commit 2's equality delete; `b2` in commit 2's own file, which that delete does not reach.
        assertEquals(mapOf("b" to RowFate.EQUALITY_DELETED, "b2" to RowFate.LIVE), hits(2).associate { it.cells.getValue("name").toString() to it.fate })
        assertEquals(listOf(RowFate.LIVE), hits(4).map { it.fate })
        assertEquals(emptyList(), hits(3))
    }

    private fun effectiveSeq(file: LiveFile): Long = current.manifests
        .flatMap { m -> m.dataFiles.map { m to it } }
        .first { (_, f) -> tail(f.metadata.dataFile?.filePath.orEmpty()) == tail(file.path) }
        .let { (m, f) -> effectiveSequenceNumber(f.metadata, m.metadata.sequenceNumber) }
}
