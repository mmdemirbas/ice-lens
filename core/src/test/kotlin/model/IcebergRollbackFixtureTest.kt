package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Setting `main` back, held to the resets the fixtures' own metadata logs record and to the
 * runs in `docs/fixtures/rollback.sql`.
 *
 * `sweep` and `rolled` each carry one reset — a metadata version whose `snapshots` are the
 * version before's and whose current snapshot moved to an ancestor — so the plan from the
 * version before, aimed at the version after's current snapshot, must land on the version after:
 * the same current line, and the log's own reading of what was left behind. The runs settle the
 * refusals: `rollback_to_snapshot` to a snapshot off the current line fails with `Cannot roll
 * back to snapshot, not an ancestor of the current state` on `sweep`, `branched` and `rolled`,
 * while `set_current_snapshot` moves `branched`'s main onto `audit`'s tip all the same.
 */
class IcebergRollbackFixtureTest {

    private data class Reset(val table: String, val before: TableMetadata, val after: TableMetadata)

    /** Every adjacent pair of metadata versions where main moved to another snapshot the table already held. */
    private fun resets(): List<Reset> = FixtureCatalog.iceberg.flatMap { name ->
        val versions = FixtureCatalog.icebergModel(name).metadatas.map { it.metadata }
        versions.zipWithNext().mapNotNull { (a, b) ->
            val same = a.snapshots.map { it.snapshotId } == b.snapshots.map { it.snapshotId }
            if (same && a.currentSnapshotId != b.currentSnapshotId) Reset(name, a, b) else null
        }
    }

    @Test
    fun `the plan from before each recorded reset lands on the version after it`() {
        val seen = resets()
        assertEquals(setOf("rolled", "sweep", "swept"), seen.map { it.table }.toSet(), "the corpus's resets — swept keeps sweep's versions")
        seen.forEach { (table, before, after) ->
            val target = after.currentSnapshotId!!
            val plan = before.planRollback(target, nowMs = after.lastUpdatedMs ?: 0L)
            assertEquals(IcebergRollbackVerdict.MOVES, plan.verdict, table)
            assertTrue(plan.setCurrentSnapshotWould, table)
            val planned = plan.after!!
            assertEquals(after.currentAncestorIds(), planned.currentAncestorIds(), "$table: the current line after")
            assertEquals(after.refs.getValue("main").snapshotId, planned.refs.getValue("main").snapshotId, "$table: main")
            assertEquals(after.snapshotLog.map { it.snapshotId }, planned.snapshotLog.map { it.snapshotId }, "$table: the log")
            // Two readings of what the reset left behind: the planner's, and the log reader's on the version after.
            val reset = after.snapshotHistory().last()
            assertEquals(SnapshotLogKind.RESET, reset.kind, table)
            assertEquals(reset.leftBehind.toSet(), plan.leftBehind.map { it.snapshotId }.toSet(), "$table: left behind")
            assertEquals(before.currentAncestorIds() - after.currentAncestorIds(), plan.leftBehind.map { it.snapshotId }.toSet(), table)
            assertTrue(plan.leftBehind.all { it.heldBy.isEmpty() }, "$table: one ref, so nothing holds them")
        }
    }

    @Test
    fun `sweep's reset leaves one commit whose file the expiry after it frees`() {
        val (_, before, after) = resets().single { it.table == "sweep" }
        val plan = before.planRollback(after.currentSnapshotId!!, nowMs = after.lastUpdatedMs!!)
        assertEquals(listOf(3500867974852930453L), plan.leftBehind.map { it.snapshotId })
        // The expiry under older_than = now, planned on what the rollback writes: the left-behind
        // commit goes, and its one data file with it. At this version the tag `keep` still stands
        // on the target, so two refs are left and the cleanup is the reachable one — which frees
        // that file because no retained manifest lists it live, and with it the file the third
        // commit removed, live only in the two expiring commits before it: two data files.
        val nowMs = after.lastUpdatedMs!! + 1
        val expiry = plan.after!!.planExpiry(ExpiryOptions(nowMs = nowMs, olderThanMs = nowMs))
        assertTrue(3500867974852930453L in expiry.removed)
        val files = FixtureCatalog.icebergModel("sweep").expiryFileInput().copy(metadata = plan.after!!).planExpiryFiles(expiry.removed.toSet())
        assertEquals(ExpiryCleanup.REACHABLE, files.cleanup)
        assertEquals(2, files.files.count { it.kind == ExpiryFileKind.DATA_FILE })
        // Without the tag — the table as it stands now — the same rollback's expiry is incremental,
        // and reverts the left-behind commit's file alone: the removed file's DELETED entry sits in
        // the retained third commit, which that rule leaves alone.
        val bare = plan.after!!.copy(refs = plan.after!!.refs.filterKeys { it == "main" })
        val bareFiles = FixtureCatalog.icebergModel("sweep").expiryFileInput().copy(metadata = bare)
            .planExpiryFiles(bare.planExpiry(ExpiryOptions(nowMs = nowMs, olderThanMs = nowMs)).removed.toSet())
        assertEquals(ExpiryCleanup.INCREMENTAL, bareFiles.cleanup)
        assertEquals(1, bareFiles.files.count { it.kind == ExpiryFileKind.DATA_FILE && it.reason == ExpiryFileReason.REVERTED })
    }

    private val branched by lazy { FixtureCatalog.icebergModel("branched").metadatas.last().metadata }
    private val rolled by lazy { FixtureCatalog.icebergModel("rolled").metadatas.last().metadata }

    @Test
    fun `a snapshot off the current line is refused by rollback_to_snapshot and taken by set_current_snapshot`() {
        val audit = 1183816113347240589L
        val plan = branched.planRollback(audit, nowMs = 0L)
        assertEquals(IcebergRollbackVerdict.NOT_AN_ANCESTOR, plan.verdict)
        assertTrue(plan.reason.startsWith("Cannot roll back to snapshot, not an ancestor of the current state: $audit"), plan.reason)
        assertTrue(plan.setCurrentSnapshotWould)
        assertNull(plan.timestampWindow)
        // set_current_snapshot moved main onto audit's tip: the two commits above the fork are left, each held by a tag.
        assertEquals(listOf(8788892783725052840L to listOf("prod"), 3698023222460817889L to listOf("release")), plan.leftBehind.map { it.snapshotId to it.heldBy })
        assertEquals(audit, plan.after!!.refs.getValue("main").snapshotId)
        assertEquals(audit, plan.after!!.currentSnapshotId)
        // From there, v1's snapshot is an ancestor again and rollback_to_snapshot moved main to it.
        val then = plan.after!!.planRollback(1466525117601214788L, nowMs = 1L)
        assertEquals(IcebergRollbackVerdict.MOVES, then.verdict)
        assertEquals(listOf(audit), then.leftBehind.map { it.snapshotId })
        assertEquals(listOf("audit"), then.leftBehind.single().heldBy)
        // rolled: the commit the reset abandoned is not an ancestor, and the first commit is.
        assertEquals(IcebergRollbackVerdict.NOT_AN_ANCESTOR, rolled.planRollback(8241983636156380084L, 0L).verdict)
        assertEquals(IcebergRollbackVerdict.MOVES, rolled.planRollback(7121122354710552183L, 0L).verdict)
        assertEquals(IcebergRollbackVerdict.NOTHING_TO_DO, rolled.planRollback(rolled.currentSnapshotId!!, 0L).verdict)
        assertEquals(IcebergRollbackVerdict.UNKNOWN_SNAPSHOT, rolled.planRollback(42L, 0L).verdict)
    }

    @Test
    fun `rollback_to_timestamp picks the newest current ancestor before the time, and each ancestor's window says so`() {
        // The run: a millisecond after sweep's second commit landed on it (`1593994005910419243 6454228146597789500`),
        // and the first commit's own moment was refused (`Cannot roll back, no valid snapshot older than: 1789313183295`).
        val sweep = FixtureCatalog.icebergModel("sweep").metadatas.last().metadata
        assertEquals(6454228146597789500L, sweep.rollbackTargetForTime(1789313183518L))
        assertNull(sweep.rollbackTargetForTime(1789313183295L))
        FixtureCatalog.iceberg.forEach { name ->
            val m = FixtureCatalog.icebergModel(name).metadatas.last().metadata
            val byId = m.snapshots.associateBy { it.snapshotId }
            val line = m.currentAncestorIds().filter { it in byId }
            line.forEach { id ->
                val ts = byId.getValue(id).timestampMs!!
                assertEquals(id, m.rollbackTargetForTime(ts + 1), "$name: a millisecond after $id")
                val window = m.planRollback(id, 0L).timestampWindow
                assertNotNull(window, "$name: $id is on the line")
                assertEquals(ts + 1, window.first, "$name: the window opens after $id's own time")
                assertEquals(id, m.rollbackTargetForTime(window.last), "$name: the window's end still lands on $id")
                if (window.last != Long.MAX_VALUE) assertTrue(m.rollbackTargetForTime(window.last + 1) != id, "$name: past the window the next ancestor wins")
            }
            val first = line.lastOrNull()?.let { byId.getValue(it).timestampMs!! }
            if (first != null) assertNull(m.rollbackTargetForTime(first), "$name: nothing is older than the first commit")
        }
    }
}
