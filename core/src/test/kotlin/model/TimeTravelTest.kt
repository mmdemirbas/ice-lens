package model

import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `rolled` is the table the rule has to be read on: its log is commit, commit, commit, a reset
 * back to the second, and a commit forked from it — so a time between the third commit and the
 * reset lands on the abandoned snapshot, and a time after the reset lands on the second through
 * the reset's own entry. Both are what Iceberg's `SnapshotUtil` answers, and neither is what a
 * reader who assumes "the newest snapshot before T" would expect.
 */
class TimeTravelTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun iceberg(fixture: String) = UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/$fixture").absolutePath)).metadatas.last().metadata
    private fun paimon(fixture: String) = PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/$fixture").absolutePath)).expiryInput()

    @Test
    fun `a time between an abandoned commit and the reset lands on the abandoned commit`() {
        val meta = iceberg("rolled")
        val log = meta.snapshotLog
        assertEquals(5, log.size)
        val abandoned = log[2]
        val reset = log[3]
        assertEquals(log[1].snapshotId, reset.snapshotId, "the fourth entry is the reset back to the second commit")

        val r = meta.snapshotAsOf(reset.timestampMs!! - 1)
        assertEquals(abandoned.snapshotId, r.snapshotId)
        assertEquals(abandoned.timestampMs, r.atMs)
        assertEquals(false, r.currentAncestor)
        assertEquals(reset, r.leftBehindAt)
        assertTrue(!r.viaReset)
    }

    @Test
    fun `a time after the reset lands on its target through the reset's own entry`() {
        val meta = iceberg("rolled")
        val log = meta.snapshotLog
        val reset = log[3]
        val r = meta.snapshotAsOf(reset.timestampMs!!)
        assertEquals(reset.snapshotId, r.snapshotId)
        assertEquals(reset.timestampMs, r.atMs, "at or before: the reset's own moment counts")
        assertEquals(true, r.currentAncestor)
        assertTrue(r.viaReset)
        assertNull(r.leftBehindAt)

        val after = meta.snapshotAsOf(log[4].timestampMs!! + 60_000)
        assertEquals(meta.currentSnapshotId, after.snapshotId)
        assertTrue(!after.viaReset)
    }

    @Test
    fun `a time before the first entry resolves to nothing and says where the log starts`() {
        val meta = iceberg("rolled")
        val first = meta.snapshotLog.first().timestampMs!!
        val r = meta.snapshotAsOf(first - 1)
        assertNull(r.snapshotId)
        assertEquals(first, r.earliestMs)
        assertEquals(meta.snapshotLog.first().snapshotId, meta.snapshotAsOf(first).snapshotId)
    }

    /** An expiry drops the removed snapshots' log entries, so the earliest resolvable time moves forward. */
    @Test
    fun `after an expiry the log starts at the earliest retained commit`() {
        val meta = iceberg("expired")
        assertEquals(meta.snapshots.map { it.snapshotId }.toSet(), meta.snapshotLog.map { it.snapshotId }.toSet())
        val r = meta.snapshotAsOf(Long.MAX_VALUE)
        assertEquals(meta.currentSnapshotId, r.snapshotId)
        assertEquals(meta.snapshotLog.first().timestampMs, r.earliestMs)
    }

    @Test
    fun `Paimon resolves to the latest snapshot at or before the time, and to nothing before the earliest`() {
        val input = paimon("px")
        val times = input.snapshotTimes.toSortedMap()
        val t2 = assertNotNull(times[2L])
        val t3 = assertNotNull(times[3L])
        assertTrue(t2 < t3)
        assertEquals(2L, input.snapshotAsOf(t3 - 1).snapshotId)
        assertEquals(3L, input.snapshotAsOf(t3).snapshotId)
        assertEquals(times.lastKey(), input.snapshotAsOf(Long.MAX_VALUE).snapshotId)
        val before = input.snapshotAsOf(assertNotNull(times[1L]) - 1)
        assertNull(before.snapshotId)
        assertEquals(times[1L], before.earliestMs)
    }
}
