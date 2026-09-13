package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The rules of [planExpiry] one at a time, on snapshots made up for the purpose. The fixture
 * test is where they meet Paimon's own expiry; this is where each bound is shown to be the one
 * `ExpireSnapshotsImpl.expire()` applies, in the case the fixture cannot reach.
 */
class PaimonExpiryPlanTest {

    private val hour = 3_600_000L
    private val now = 100 * hour

    /** Snapshots `1..count`, one an hour, the last one [lastAgeMs] before [now]. */
    private fun input(
        count: Int,
        lastAgeMs: Long = 10 * hour,
        consumers: Map<String, Long?> = emptyMap(),
        options: Map<String, String> = emptyMap(),
    ) = PaimonExpiryInput(
        snapshotTimes = (1..count).associate { it.toLong() to now - lastAgeMs - (count - it) * hour },
        consumerNext = consumers,
        tagsBySnapshotId = emptyMap(),
        tableOptions = options,
    )

    @Test
    fun `at most snapshot expire limit go in one run`() {
        val plan = input(100).planExpiry(PaimonExpiryOptions(nowMs = now, retainMin = 1))
        assertEquals((1L..50L).toList(), plan.removed.map { it.snapshotId })
        assertEquals(listOf(PaimonKeep(PaimonKeepRule.EXPIRE_LIMIT)), plan.snapshots.first { it.snapshotId == 51L }.keptBy)
        val limited = input(100).planExpiry(PaimonExpiryOptions(nowMs = now, retainMin = 1, maxDeletes = 3))
        assertEquals(listOf(1L, 2L, 3L), limited.removed.map { it.snapshotId })
    }

    @Test
    fun `retain_max removes without asking the age, and the age rule stops the walk above it`() {
        // Six snapshots, the newest three within the last hour; retain_max = 4 puts min at 3.
        val fresh = input(6, lastAgeMs = 0).let { i ->
            i.copy(snapshotTimes = i.snapshotTimes.mapValues { (id, _) -> if (id >= 4) now - (6 - id) * 60_000 else now - (10 + 6 - id) * hour })
        }
        val plan = fresh.planExpiry(PaimonExpiryOptions(nowMs = now, retainMax = 4, retainMin = 1))
        // 1 and 2 are beyond retain_max; 3 is old and within the walk; 4 is the first young one.
        assertEquals(listOf(1L, 2L, 3L), plan.removed.map { it.snapshotId })
        val by = plan.snapshots.associateBy { it.snapshotId }
        assertEquals(listOf(PaimonKeep(PaimonKeepRule.YOUNGER_THAN_CUTOFF)), by.getValue(4L).keptBy)
        assertEquals(listOf(PaimonKeep(PaimonKeepRule.BEHIND_STOP)), by.getValue(5L).keptBy)
        assertEquals(listOf(PaimonKeep(PaimonKeepRule.RETAIN_MIN)), by.getValue(6L).keptBy)
    }

    @Test
    fun `the table's own options are what a bare procedure call runs under`() {
        val options = mapOf(
            "snapshot.num-retained.max" to "3",
            "snapshot.num-retained.min" to "2",
            "snapshot.time-retained" to "30 min",
            "snapshot.expire.limit" to "5",
        )
        val plan = input(10, options = options).planExpiry(PaimonExpiryOptions(nowMs = now))
        assertEquals(3, plan.retainMax)
        assertEquals(2, plan.retainMin)
        assertEquals(5, plan.maxDeletes)
        assertEquals(now - 30 * 60_000, plan.cutoffMs)
        // min = 10 - 3 + 1 = 8; maxExclusive = min(9, 1 + 5 = 6) = 6 — the limit, below min, so the walk is empty.
        assertEquals((1L..5L).toList(), plan.removed.map { it.snapshotId })
        assertEquals(listOf(PaimonKeep(PaimonKeepRule.EXPIRE_LIMIT)), plan.snapshots.first { it.snapshotId == 6L }.keptBy)
        // The procedure's arguments win over the table's.
        assertEquals(1, input(10, options = options).planExpiry(PaimonExpiryOptions(nowMs = now, retainMin = 1, retainMax = 1)).retainMin)
    }

    @Test
    fun `a consumer with no next snapshot recorded holds nothing back, and several are named together`() {
        val none = input(6, consumers = mapOf("idle" to null)).planExpiry(PaimonExpiryOptions(nowMs = now, retainMin = 1))
        assertEquals((1L..5L).toList(), none.removed.map { it.snapshotId })

        val two = input(6, consumers = mapOf("b" to 3L, "a" to 5L, "idle" to null))
            .planExpiry(PaimonExpiryOptions(nowMs = now, retainMin = 1))
        assertEquals(listOf(1L, 2L), two.removed.map { it.snapshotId })
        val by = two.snapshots.associateBy { it.snapshotId }
        assertEquals("not yet consumed by b", by.getValue(3L).describeKeptBy())
        assertEquals("not yet consumed by a, b", by.getValue(5L).describeKeptBy())
    }

    @Test
    fun `a gap in snapshot directory is walked over as a snapshot that cannot be young`() {
        val minute = 60_000L
        val gapped = PaimonExpiryInput(
            snapshotTimes = mapOf(1L to now - 50 * minute, 3L to now - 30 * minute, 4L to now - 20 * minute, 5L to now - 10 * minute, 6L to now),
            consumerNext = emptyMap(),
            tagsBySnapshotId = emptyMap(),
            tableOptions = emptyMap(),
        )
        // Everything is young; the walk starts at 1, which is young, so it stops there.
        val plan = gapped.planExpiry(PaimonExpiryOptions(nowMs = now, retainMin = 1))
        assertEquals(emptyList(), plan.removed)
        // Make 1 old: the walk passes the gap at 2 without stopping and stops at 3.
        val oldFirst = gapped.copy(snapshotTimes = gapped.snapshotTimes + (1L to now - 5 * hour))
        val plan2 = oldFirst.planExpiry(PaimonExpiryOptions(nowMs = now, retainMin = 1))
        assertEquals(listOf(1L), plan2.removed.map { it.snapshotId })
        assertEquals(listOf(PaimonKeep(PaimonKeepRule.YOUNGER_THAN_CUTOFF)), plan2.snapshots.first { it.snapshotId == 3L }.keptBy)
    }

    @Test
    fun `durations parse the way TimeUtils parses them`() {
        assertEquals(1_000L, parsePaimonDurationMs("1000"))
        assertEquals(1_000L, parsePaimonDurationMs("1 s"))
        assertEquals(90_000L, parsePaimonDurationMs("90s"))
        assertEquals(30 * 60_000L, parsePaimonDurationMs("30 min"))
        assertEquals(2 * 60_000L, parsePaimonDurationMs("2m"))
        assertEquals(hour, parsePaimonDurationMs("1h"))
        assertEquals(hour, parsePaimonDurationMs(" 1 Hour "))
        assertEquals(3 * 86_400_000L, parsePaimonDurationMs("3 days"))
        assertEquals(5L, parsePaimonDurationMs("5 ms"))
        assertNull(parsePaimonDurationMs("h"))
        assertNull(parsePaimonDurationMs("1 fortnight"))
        assertNull(parsePaimonDurationMs(""))
    }
}
