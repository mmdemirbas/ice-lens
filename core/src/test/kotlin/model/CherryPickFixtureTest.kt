package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `cherrypick_snapshot`, held to the runs in `docs/fixtures/cherrypick.sql`: `wap`'s staged
 * snapshot refused as a duplicate of the wap id its publish carried; `rolled`'s abandoned
 * commit published as a new snapshot with `source-snapshot-id` naming it, and refused the second
 * time as already picked; `branched`'s `v1` snapshot refused as already an ancestor, and after
 * `set_current_snapshot` to it the release snapshot fast-forwarded (no new snapshot, five
 * remain) while `audit`'s tip, whose parent was then no longer current, was published; `mor`'s
 * delete refused as not append, dynamic overwrite or fast-forward.
 */
class CherryPickFixtureTest {

    private fun latest(name: String) = FixtureCatalog.icebergModel(name).metadatas.last().metadata

    @Test
    fun `a staged wap snapshot whose id was published is a duplicate`() {
        val plan = latest("wap").planCherryPick(4204024477256586588L)
        assertEquals(CherryPickVerdict.DUPLICATE_WAP, plan.verdict)
        assertEquals("Duplicate request to cherry pick wap id that was published already: audit-1", plan.reason)
        assertEquals("audit-1", plan.wapId)
        // The published commit itself is an ancestor.
        assertEquals(CherryPickVerdict.ALREADY_ANCESTOR, latest("wap").planCherryPick(8273853679921106935L).verdict)
    }

    @Test
    fun `an abandoned append is published, and the same pick again is already picked`() {
        val rolled = latest("rolled")
        val plan = rolled.planCherryPick(8241983636156380084L)
        assertEquals(CherryPickVerdict.PUBLISH, plan.verdict)
        assertTrue(plan.writesSnapshot)
        assertEquals(1, plan.addedDataFiles)
        assertTrue(plan.reason.startsWith("a new commit on main carrying its 1 added data file"), plan.reason)
        // The run wrote 8545125033544548937 with source-snapshot-id 8241983636156380084; from there the pick is refused.
        val published = Snapshot(snapshotId = 8545125033544548937L, parentSnapshotId = rolled.currentSnapshotId, sequenceNumber = 5, timestampMs = 1L, summary = mapOf("operation" to "append", "source-snapshot-id" to "8241983636156380084", "added-data-files" to "1"))
        val after = rolled.copy(snapshots = rolled.snapshots + published, currentSnapshotId = published.snapshotId, refs = rolled.refs + ("main" to rolled.refs.getValue("main").copy(snapshotId = published.snapshotId)))
        val again = after.planCherryPick(8241983636156380084L)
        assertEquals(CherryPickVerdict.ALREADY_PICKED, again.verdict)
        assertEquals("Cannot cherrypick snapshot 8241983636156380084: already picked to create ancestor 8545125033544548937", again.reason)
        assertEquals(CherryPickVerdict.UNKNOWN_SNAPSHOT, rolled.planCherryPick(42L).verdict)
    }

    @Test
    fun `a snapshot whose parent is the current one fast-forwards, an ancestor is refused, a delete cannot be picked`() {
        val branched = latest("branched")
        assertEquals(CherryPickVerdict.ALREADY_ANCESTOR, branched.planCherryPick(1466525117601214788L).verdict)
        // audit's tip from main's own tip: its parent is not current, so it is published.
        assertEquals(CherryPickVerdict.PUBLISH, branched.planCherryPick(1183816113347240589L).verdict)
        // After set_current_snapshot to v1's snapshot, both children of it fast-forward — and the run
        // shows the second no longer does once the first has moved main.
        val atV1 = branched.planRollback(1466525117601214788L, nowMs = 0L).after!!
        val release = atV1.planCherryPick(3698023222460817889L)
        assertEquals(CherryPickVerdict.FAST_FORWARD, release.verdict)
        assertTrue(!release.writesSnapshot)
        assertEquals(CherryPickVerdict.FAST_FORWARD, atV1.planCherryPick(1183816113347240589L).verdict)
        val atRelease = atV1.planRollback(3698023222460817889L, nowMs = 1L).after!!
        assertEquals(CherryPickVerdict.PUBLISH, atRelease.planCherryPick(1183816113347240589L).verdict)
        val mor = latest("mor")
        val delete = mor.planCherryPick(4216642347117264083L)
        assertEquals(CherryPickVerdict.NOT_PICKABLE, delete.verdict)
        assertTrue(delete.reason.startsWith("Cannot cherry-pick snapshot 4216642347117264083: not append, dynamic overwrite, or fast-forward"), delete.reason)
        assertEquals(CherryPickVerdict.ALREADY_ANCESTOR, mor.planCherryPick(4747162675114470207L).verdict)
    }

    @Test
    fun `on every fixture a current ancestor is never published or fast-forwarded, and a fast-forward is a child of the current snapshot`() {
        var offLine = 0
        FixtureCatalog.iceberg.forEach { name ->
            val m = latest(name)
            val ancestors = m.currentAncestorIds()
            m.snapshots.mapNotNull { it.snapshotId }.forEach { id ->
                val plan = m.planCherryPick(id)
                if (id in ancestors) {
                    assertTrue(plan.verdict == CherryPickVerdict.ALREADY_ANCESTOR || plan.verdict == CherryPickVerdict.NOT_PICKABLE, "$name $id: ${plan.verdict}")
                } else {
                    offLine++
                    if (plan.verdict == CherryPickVerdict.FAST_FORWARD) assertEquals(m.currentSnapshotId, m.snapshots.first { it.snapshotId == id }.parentSnapshotId, "$name $id")
                }
            }
        }
        assertTrue(offLine >= 8, "snapshots off the current line across the corpus: $offLine")
    }
}
