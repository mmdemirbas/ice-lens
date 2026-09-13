package model

import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The expiry planner against two expiries Iceberg actually ran: `retained`'s, whose branch and
 * tag retention decided what stayed, and `expired`'s `retain_last = 1`. Each is planned from the
 * metadata *before* the procedure and held to the snapshots the metadata *after* it lists.
 */
class ExpiryPlanTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun metadatas(name: String) = UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/$name").absolutePath)).metadatas

    @Test
    fun `retained's expiry is predicted from the metadata before it, ref by ref`() {
        val versions = metadatas("retained")
        val before = versions[versions.size - 2].metadata
        val after = versions.last().metadata
        val now = after.lastUpdatedMs!!
        val plan = before.planExpiry(ExpiryOptions(nowMs = now, olderThanMs = 4_070_908_800_000L)) // 2099-01-01
        assertEquals(after.snapshots.map { it.snapshotId }.toSet(), plan.snapshots.filter { it.retained }.map { it.snapshotId }.toSet())
        assertEquals(2, plan.removed.size)
        assertTrue(plan.refs.all { it.retained }, "${plan.refs}")
        val byId = plan.snapshots.associateBy { it.snapshotId }
        val ordered = before.snapshots.sortedBy { it.sequenceNumber }.map { it.snapshotId!! }
        assertEquals(listOf(Keep(KeepRule.REF, "release")), byId.getValue(ordered[1]).keptBy)
        assertEquals(listOf(Keep(KeepRule.REF, "audit")), byId.getValue(ordered[4]).keptBy)
        assertEquals(listOf(Keep(KeepRule.WITHIN_MIN, "audit")), byId.getValue(ordered[3]).keptBy)
        assertEquals("within the minimum kept by audit", byId.getValue(ordered[3]).describeKeptBy())
        assertEquals(listOf(Keep(KeepRule.REF, "main")), byId.getValue(ordered[5]).keptBy)
        assertEquals(emptyList(), byId.getValue(ordered[0]).keptBy)
        assertEquals(emptyList(), byId.getValue(ordered[2]).keptBy)
    }

    /** With a 7-day age on the branch, nothing goes: the age replaces older_than for what the branch reaches. */
    @Test
    fun `a branch age shields every snapshot the branch reaches`() {
        val versions = metadatas("retained")
        val before = versions[versions.size - 2].metadata
        val audit = before.refs.getValue("audit")
        val shielded = before.copy(refs = before.refs + ("audit" to audit.copy(maxSnapshotAgeMs = 7L * 86_400_000L)))
        val plan = shielded.planExpiry(ExpiryOptions(nowMs = before.lastUpdatedMs!!, olderThanMs = 4_070_908_800_000L))
        assertEquals(emptyList(), plan.removed, "what the first run of the fixture observed")
        assertTrue(plan.snapshots.all { it.retained })
    }

    @Test
    fun `expired's retain_last = 1 keeps only the tip`() {
        val versions = metadatas("expired")
        val before = versions[versions.size - 2].metadata
        val after = versions.last().metadata
        val plan = before.planExpiry(ExpiryOptions(nowMs = after.lastUpdatedMs!!, olderThanMs = 4_070_908_800_000L, retainLast = 1))
        assertEquals(after.snapshots.map { it.snapshotId }.toSet(), plan.snapshots.filter { it.retained }.map { it.snapshotId }.toSet())
        assertEquals(listOf(listOf(Keep(KeepRule.REF, "main"))), plan.snapshots.filter { it.retained }.map { it.keptBy })
    }

    /** Under the table's defaults — five days, one snapshot — a table written today keeps everything. */
    @Test
    fun `under the defaults a young table keeps everything`() {
        val newest = metadatas("branched3").last().metadata
        val plan = newest.planExpiry(ExpiryOptions(nowMs = newest.lastUpdatedMs!!))
        assertEquals(newest.lastUpdatedMs!! - 5L * 86_400_000L, plan.defaultCutoffMs)
        assertEquals(1, plan.defaultMinSnapshotsToKeep)
        assertTrue(plan.removed.isEmpty())
        // Every branch reaches the root commit, and the clause lists them all under the one rule.
        val root = plan.snapshots.first { it.snapshotId == newest.snapshots.first { s -> s.parentSnapshotId == null }.snapshotId }
        val branches = newest.refs.filter { it.value.type == "branch" }.keys
        assertEquals(branches, root.keptBy.map { it.ref }.toSet())
        assertTrue(root.keptBy.all { it.rule == KeepRule.NEWER_THAN_CUTOFF })
        assertTrue(root.describeKeptBy().startsWith("newer than the cutoff of ") && !root.describeKeptBy().contains(";"), root.describeKeptBy())
    }
}
