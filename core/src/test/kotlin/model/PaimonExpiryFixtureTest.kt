package model

import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `example/paimon/db.db/px` and `pxa`: one table written twice by `docs/fixtures/paimon-px.sql`,
 * six commits, a tag on 2 and a consumer at 4 — `px` left as it is, `pxa` expired with
 * `retain_max = 2, retain_min = 1`. The plan for `px` is checked against what Paimon actually
 * left in `pxa/snapshot`, which is the only oracle a planner can have: the survivors of the
 * same run on the same commits.
 *
 * `px` carries its own oracle for the age rule. The script ran `expire_snapshots(retain_min = 1)`
 * on it too, and that removed nothing, because with `retain_max` unbounded the walk starts at
 * snapshot 1 and snapshot 1 was seconds old against a one-hour `snapshot.time-retained`.
 */
class PaimonExpiryFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun model(name: String) = PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/$name").absolutePath))

    private val px by lazy { model("px") }
    private val pxa by lazy { model("pxa") }

    /** Any moment after the last commit and within the hour: the age rule is not what decides `pxa`. */
    private fun nowFor(input: PaimonExpiryInput) = input.snapshotTimes.values.filterNotNull().max() + 1_000

    @Test
    fun `the two tables agree up to the expiry`() {
        assertEquals((1L..6L).toList(), px.snapshots.map { it.metadata.id })
        assertEquals(listOf(4L, 5L, 6L), pxa.snapshots.map { it.metadata.id })
        for (m in listOf(px, pxa)) {
            assertEquals(mapOf(2L to listOf("keep")), m.tagNamesBySnapshotId)
            assertEquals(4L, m.consumers.single().metadata.nextSnapshot)
            assertTrue(m.readErrors.isEmpty(), "${m.readErrors}")
        }
    }

    @Test
    fun `the plan for px with retain_max 2 names exactly the snapshots pxa kept`() {
        val input = px.expiryInput()
        val plan = input.planExpiry(PaimonExpiryOptions(nowMs = nowFor(input), retainMax = 2, retainMin = 1))

        assertEquals(listOf(1L, 2L, 3L), plan.removed.map { it.snapshotId })
        assertEquals(pxa.snapshots.map { it.metadata.id }, plan.snapshots.filter { it.retained }.map { it.snapshotId })

        // The consumer is what stops the run at 4 — retain_min = 1 alone would have taken 5 too,
        // and retain_max would have taken everything below 5 whatever its age.
        val by = plan.snapshots.associateBy { it.snapshotId }
        assertEquals("not yet consumed by reader", by.getValue(4L).describeKeptBy())
        assertEquals("not yet consumed by reader", by.getValue(5L).describeKeptBy())
        assertEquals("within num-retained.min; not yet consumed by reader", by.getValue(6L).describeKeptBy())
        assertEquals(listOf("keep"), by.getValue(2L).tags, "a removed snapshot's tag is what survives of it")
        assertEquals(emptyList(), by.getValue(1L).tags)
    }

    /**
     * `snapshot.time-retained` bites only between `min` and `maxExclusive`, and the run stops at
     * the first snapshot it keeps. With `retain_max` unbounded that is snapshot 1 — so nothing
     * goes, which is what happened to `px` itself.
     */
    @Test
    fun `with retain_max unbounded the age rule stops the run at snapshot 1, as it did on px`() {
        val input = px.expiryInput()
        val plan = input.planExpiry(PaimonExpiryOptions(nowMs = nowFor(input), retainMin = 1))

        assertEquals(emptyList(), plan.removed)
        assertEquals(Int.MAX_VALUE, plan.retainMax)
        assertEquals(1, plan.retainMin)
        assertEquals(PAIMON_DEFAULT_EXPIRE_LIMIT, plan.maxDeletes)
        val by = plan.snapshots.associateBy { it.snapshotId }
        assertEquals("younger than the cutoff, where the run stops", by.getValue(1L).describeKeptBy())
        assertEquals("behind where the run stopped", by.getValue(2L).describeKeptBy())
        assertEquals("behind where the run stopped", by.getValue(3L).describeKeptBy())
        assertEquals("not yet consumed by reader", by.getValue(4L).describeKeptBy())
    }

    /** Read in `ExpireSnapshotsImpl`, not run: an hour later the same call removes 1..3, the consumer still holding 4. */
    @Test
    fun `an hour on, the age rule lets go and the consumer is what remains`() {
        val input = px.expiryInput()
        val plan = input.planExpiry(PaimonExpiryOptions(nowMs = nowFor(input) + 2 * PAIMON_DEFAULT_TIME_RETAINED_MS, retainMin = 1))
        assertEquals(listOf(1L, 2L, 3L), plan.removed.map { it.snapshotId })
        // The procedure's older_than is the same cutoff written as a moment.
        val byMoment = input.planExpiry(PaimonExpiryOptions(nowMs = nowFor(input), olderThanMs = nowFor(input), retainMin = 1))
        assertEquals(plan.removed, byMoment.removed)
    }

    @Test
    fun `an expiry already run is a fixed point - the same options on pxa remove nothing more`() {
        val input = pxa.expiryInput()
        val plan = input.planExpiry(PaimonExpiryOptions(nowMs = nowFor(input), retainMax = 2, retainMin = 1))
        assertEquals(emptyList(), plan.removed)
        assertEquals(4L, plan.earliestId)
        assertEquals(6L, plan.latestId)
    }

    @Test
    fun `retain_max below retain_min is rejected, as Paimon rejects it`() {
        val input = px.expiryInput()
        assertFailsWith<IllegalArgumentException> {
            input.planExpiry(PaimonExpiryOptions(nowMs = nowFor(input), retainMax = 2))
        }
    }

    @Test
    fun `an empty table plans nothing`() {
        val plan = PaimonExpiryInput(emptyMap(), emptyMap(), emptyMap(), emptyMap())
            .planExpiry(PaimonExpiryOptions(nowMs = 0L))
        assertNull(plan.earliestId)
        assertEquals(emptyList(), plan.snapshots)
    }
}
