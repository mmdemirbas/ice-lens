package model

import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `example/paimon/db.db/pc`: a primary-key table on every default, written to seven times by
 * `docs/fixtures/paimon-pc.sql`. The fifth flush is the one Paimon compacted — five one-row
 * files at level 0, and the four newer ones outweigh the oldest by 400% against a 200% bound —
 * so the plan for each snapshot is held to the commit that followed it: `compacts` exactly where
 * the next snapshot is a `COMPACT`.
 *
 * The same rule is then swept over every Paimon fixture, where the forced-up-level-0 tables
 * (`dv`, `lk`) compact after every append, the write-only table (`px`) never does, and the rest
 * never reach the trigger. An explicit `sys.compact` (`rt`, `se`) is not the writer's decision
 * and is left out of the sweep; the append-table rule has its own oracle there.
 */
class PaimonCompactionFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun model(name: String) = PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/$name").absolutePath))

    private fun plans(m: PaimonUnifiedTableModel): List<Pair<PaimonUnifiedSnapshot, List<PaimonCompactionVerdict>>> =
        m.snapshots.map { s ->
            val options = PaimonCompactionOptions.from(s.schema?.options.orEmpty())
            s to paimonBucketLsms(replayPaimonSnapshot(s).liveEntries.values).map { it.planCompaction(options) }
        }

    @Test
    fun `the fifth flush is the one the writer compacted, by size amplification, into level 5`() {
        val pc = model("pc")
        assertEquals(listOf("APPEND", "APPEND", "APPEND", "APPEND", "APPEND", "COMPACT", "APPEND", "APPEND"), pc.snapshots.map { it.metadata.commitKind })
        val byId = plans(pc).associate { (s, verdicts) -> s.metadata.id!! to verdicts.single() }

        (1L..4L).forEach { id ->
            val v = byId.getValue(id)
            assertFalse(v.compacts, "snapshot $id: ${v.describe()}")
            assertEquals("not yet — $id of 5 runs", v.describe())
        }
        val fifth = byId.getValue(5L)
        assertEquals("L0×5", fifth.lsm.describeLevels())
        assertEquals(PaimonCompactionRule.SIZE_AMPLIFICATION, fifth.rule)
        assertEquals(5, fifth.filesPicked)
        assertEquals(5, fifth.outputLevel, "num-levels defaults to trigger + 1, so the top level is 5")
        assertEquals("compacts 5 files in 5 runs into level 5 — size amplification", fifth.describe())
        assertFalse(fifth.stalls)

        val sixth = byId.getValue(6L)
        assertEquals("L5×1", sixth.lsm.describeLevels())
        assertEquals(5L, sixth.lsm.runs.single().files.single().rowCount)
        assertEquals("not yet — 1 of 5 runs", sixth.describe())
        assertEquals("L0×2, L5×1", byId.getValue(8L).lsm.describeLevels())
        assertEquals(3, byId.getValue(8L).lsm.sortedRunCount)
        // Level 0 newest first: the file of snapshot 8 (sequence 6) ahead of snapshot 7's (5).
        assertEquals(listOf(6L, 5L), byId.getValue(8L).lsm.runs.take(2).map { it.files.single().maxSequenceNumber })
    }

    /**
     * On every primary-key fixture, an `APPEND` the writer compacted after is one the plan says
     * it would, and one it did not is one the plan says it would not. Append tables are left
     * out: a Spark write never runs their compaction, `ad`'s `COMPACT` is a deletion-vector
     * maintenance commit that rewrote no file, and `rt`'s is an explicit `sys.compact` call —
     * the planner's side for it is what such a call would pack, tested below. `se` is the one
     * primary-key table whose `COMPACT` is a `sys.compact` call too, and is left out for it, as
     * is `psl`, whose full compaction upgrades a lone level-0 file.
     */
    @Test
    fun `across every fixture, the plan compacts exactly where the next commit is a COMPACT`() {
        val writerDriven = FixtureCatalog.paimon.filter { name -> name !in setOf("se", "psl") && model(name).schemas.any { it.primaryKeys.isNotEmpty() } }
        assertTrue(writerDriven.size >= 20, writerDriven.toString())
        var checked = 0
        for (name in writerDriven) {
            val m = model(name)
            val all = plans(m)
            all.zipWithNext().forEach { (current, next) ->
                val (snapshot, verdicts) = current
                if (snapshot.metadata.commitKind != "APPEND") return@forEach
                val predicted = verdicts.any { it.compacts }
                val happened = next.first.metadata.commitKind == "COMPACT"
                assertEquals(happened, predicted, "$name snapshot ${snapshot.metadata.id}: ${verdicts.map { it.describe() }}")
                checked++
            }
        }
        assertTrue(checked >= 20, "the sweep should have compared a good many commits, not $checked")
    }

    @Test
    fun `a lookup table forces level 0 up after every append, and a write-only table never compacts`() {
        val lk = plans(model("lk")).associate { (s, v) -> s.metadata.id!! to v.single() }
        assertEquals(PaimonCompactionRule.FORCE_UP_LEVEL_0, lk.getValue(5L).rule)
        assertEquals("compacts 1 file in 1 run into level 3 — level 0 forced up on every flush", lk.getValue(5L).describe())
        val px = plans(model("px")).associate { (s, v) -> s.metadata.id!! to v.single() }
        assertEquals("never — write-only", px.getValue(6L).describe())
        assertEquals(6, px.getValue(6L).lsm.sortedRunCount)
        assertTrue(px.getValue(6L).options.writeOnly)
    }

    /** `rt` set `compaction.min.file-num = 2` and `sys.compact` merged its two files; `ao` holds three under the default five. */
    @Test
    fun `an append table's small files are what sys compact would pack, by the table's min file num`() {
        val rt = model("rt")
        val before = rt.snapshots.first { it.metadata.id == 2L }
        val verdict = paimonAppendCompaction(replayPaimonSnapshot(before).liveEntries.values, before.schema?.options.orEmpty()).single()
        assertEquals(2, verdict.minFileNum)
        assertEquals(2, verdict.smallFileCount)
        assertTrue(verdict.wouldPack)
        assertEquals("sys.compact would merge 2 small files", verdict.describe())
        assertEquals(256L shl 20, verdict.targetBytes)

        val ao = model("ao")
        val last = ao.snapshots.last()
        val byPartition = paimonAppendCompaction(replayPaimonSnapshot(last).liveEntries.values, last.schema?.options.orEmpty())
            .associateBy { it.partition }
        assertEquals("2 of 5 small files — sys.compact would leave them", byPartition.getValue("dt=2024-03-05").describe())
        assertEquals("1 of 5 small files — sys.compact would leave them", byPartition.getValue("dt=2024-03-06").describe())
    }
}
