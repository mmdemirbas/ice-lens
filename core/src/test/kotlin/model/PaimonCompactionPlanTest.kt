package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `UniversalCompaction.pick()` one branch at a time, on trees made up for the purpose — the
 * branches a batch writer never reaches, because it compacts at the trigger and so never sees a
 * sixth run: the run-count rule, a size-ratio pick that lands below the top, the stop trigger.
 */
class PaimonCompactionPlanTest {

    private fun file(name: String, level: Int, size: Long, seq: Long) =
        PaimonDataFileMeta(fileName = name, fileSize = size, level = level, minSequenceNumber = seq, maxSequenceNumber = seq, rowCount = 1)

    private fun lsm(vararg runs: PaimonSortedRun) = PaimonBucketLsm("", 0, runs.toList())
    private fun l0(size: Long, seq: Long) = PaimonSortedRun(0, listOf(file("f$seq", 0, size, seq)))
    private fun level(level: Int, size: Long) = PaimonSortedRun(level, listOf(file("L$level", level, size, 0)))

    private val defaults = PaimonCompactionOptions.from(emptyMap())

    @Test
    fun `defaults are the ones CoreOptions ships, and the stop trigger never sits below the trigger`() {
        assertEquals(PaimonCompactionOptions(5, 8, 6, 200, 1, writeOnly = false, forceUpLevel0 = false), defaults)
        val tuned = PaimonCompactionOptions.from(mapOf("num-sorted-run.compaction-trigger" to "10", "num-sorted-run.stop-trigger" to "4", "num-levels" to "4"))
        assertEquals(10, tuned.trigger)
        assertEquals(10, tuned.stopTrigger)
        assertEquals(4, tuned.numLevels)
        assertTrue(PaimonCompactionOptions.from(mapOf("changelog-producer" to "lookup")).forceUpLevel0)
        assertTrue(PaimonCompactionOptions.from(mapOf("merge-engine" to "first-row")).forceUpLevel0)
        assertTrue(PaimonCompactionOptions.from(mapOf("compaction.force-up-level-0" to "true")).forceUpLevel0)
        assertTrue(PaimonCompactionOptions.from(mapOf("write-only" to "true")).writeOnly)
    }

    @Test
    fun `size ratio gathers the newest runs that are within a percent of each other and lands below the first run left out`() {
        // Five runs, at the trigger: three small level-0 files, a level-3 run of 10 MB and a
        // level-5 run of 100 MB. Amplification: 3 KB + 10 MB against 100 MB — no. Size ratio:
        // the three small files gather, the 10 MB run does not, so they land in level 2.
        val tree = lsm(l0(1_000, 3), l0(1_000, 2), l0(1_000, 1), level(3, 10_000_000), level(5, 100_000_000))
        val verdict = tree.planCompaction(defaults)
        assertEquals(PaimonCompactionRule.SIZE_RATIO, verdict.rule)
        assertEquals(3, verdict.filesPicked)
        assertEquals(2, verdict.outputLevel)
        assertEquals("compacts 3 files in 3 runs into level 2 — size ratio", verdict.describe())
    }

    @Test
    fun `above the trigger the run count rule takes the newest runs whatever their sizes`() {
        // Six runs, each ten times the one before, so neither amplification nor ratio bites.
        val tree = lsm(l0(1, 6), l0(10, 5), l0(100, 4), l0(1_000, 3), level(4, 10_000), level(5, 100_000))
        val verdict = tree.planCompaction(defaults)
        assertEquals(PaimonCompactionRule.RUN_COUNT, verdict.rule)
        // runs - trigger + 1 = 2 newest, then the ratio walk stops at the 100-byte file; the
        // first run left out is level 0, and a pick never lands in level 0, so createUnit sweeps
        // the rest of level 0 and the level-4 run into it and lands there.
        assertEquals(listOf(1L, 10L, 100L, 1_000L, 10_000L), verdict.runsPicked.map { it.sizeBytes })
        assertEquals(4, verdict.outputLevel)
        assertEquals("compacts 5 files in 5 runs into level 4 — run count", verdict.describe())
        assertFalse(verdict.stalls, "six runs is under the stop trigger of eight")
    }

    @Test
    fun `past the stop trigger the verdict says the writer waits`() {
        val runs = (9 downTo 1).map { l0(1_000, it.toLong()) }.toTypedArray()
        val verdict = lsm(*runs).planCompaction(defaults)
        assertTrue(verdict.stalls)
        assertEquals(PaimonCompactionRule.SIZE_AMPLIFICATION, verdict.rule)
        assertTrue(verdict.describe().endsWith("past the stop trigger (8), the writer waits for it"))
    }

    @Test
    fun `a lone level-0 file on a lookup table is forced up, and a lone file already at its level is not rewritten`() {
        val lookup = PaimonCompactionOptions.from(mapOf("deletion-vectors.enabled" to "true"))
        val fresh = lsm(l0(1_000, 1)).planCompaction(lookup)
        assertEquals(PaimonCompactionRule.FORCE_UP_LEVEL_0, fresh.rule)
        assertEquals(5, fresh.outputLevel, "the only run takes the whole tree to the top level")
        val settled = lsm(level(5, 1_000)).planCompaction(lookup)
        assertFalse(settled.compacts)
        assertNull(settled.outputLevel)
    }

    @Test
    fun `a tree deeper than num-levels widens the ladder rather than failing`() {
        val deep = lsm(*(5 downTo 1).map { l0(1_000, it.toLong()) }.toTypedArray(), level(9, 1_000))
        val verdict = deep.planCompaction(defaults)
        assertEquals(9, verdict.outputLevel)
    }

    @Test
    fun `memory sizes parse the way MemorySize parses them`() {
        assertEquals(128L shl 20, parsePaimonMemoryBytes("128 mb"))
        assertEquals(128L shl 20, parsePaimonMemoryBytes("128MB"))
        assertEquals(1L shl 30, parsePaimonMemoryBytes("1g"))
        assertEquals(4096L, parsePaimonMemoryBytes("4 kb"))
        assertEquals(512L, parsePaimonMemoryBytes("512"))
        assertNull(parsePaimonMemoryBytes("lots"))
        assertNull(parsePaimonMemoryBytes("1 parsec"))
    }
}
