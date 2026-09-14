package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `sys.compact` on a primary-key table, held to three oracles. Two are in the corpus: `psl`'s
 * one level-0 file was upgraded to level 5 without a rewrite (`paimon-psm.sql`), and `se`'s two
 * overlapping level-0 files were rewritten into one (`paimon-se.sql`) — each a `COMPACT`
 * snapshot whose delta names what went and what came, so the plan on the snapshot before is
 * held to it. The third is `docs/fixtures/paimon-compact.sql`: the procedure run on copies of
 * eight checked-in tables, the `$files` rows before and after recorded in its header, so the plan
 * on each checked-in table is held to which names went, which were renamed to the top level, and
 * how many new files arrived.
 */
class PaimonFullCompactionFixtureTest {

    private data class Step(val before: PaimonUnifiedSnapshot, val compact: PaimonUnifiedSnapshot)

    private fun plan(s: PaimonUnifiedSnapshot, vectored: Set<String> = emptySet()): List<PaimonFullCompactionVerdict> {
        val options = PaimonFullCompactionOptions.from(s.schema?.options.orEmpty())
        return paimonBucketLsms(replayPaimonSnapshot(s).liveEntries.values).map { it.planFullCompaction(options, vectored) }
    }

    /** The compaction's delta: the names it removed at their level, and the names it added at theirs. */
    private fun delta(s: PaimonUnifiedSnapshot): Pair<Map<String, Int>, Map<String, Int>> {
        val entries = s.deltaManifests.flatMap { it.entries }
        fun of(kind: Int) = entries.filter { it.metadata.kind == kind }.associate { paimonEntryFileName(it) to (it.metadata.file?.level ?: 0) }
        return of(PaimonEntryKind.DELETE) to of(PaimonEntryKind.ADD)
    }

    private fun step(table: String, compactId: Long): Step {
        val m = FixtureCatalog.paimonModel(table)
        val byId = m.snapshots.associateBy { it.metadata.id }
        return Step(byId.getValue(compactId - 1), byId.getValue(compactId))
    }

    @Test
    fun `psl's lone level-0 file is upgraded to level 5, renamed and not rewritten`() {
        val (before, compact) = step("psl", 2L)
        assertEquals("COMPACT", compact.metadata.commitKind)
        val v = plan(before).single()
        assertEquals(PaimonFullCompactionRule.FULL, v.rule)
        assertEquals(5, v.outputLevel)
        val only = v.files.single()
        assertEquals(PaimonFullCompactionAction.UPGRADE, only.action)
        assertEquals(0, only.file.level)
        assertEquals("upgrades 1 at level 5 — full compaction", v.describe())
        val (removed, added) = delta(compact)
        assertEquals(mapOf(only.file.fileName!! to 0), removed, "the DELETE at level 0")
        assertEquals(mapOf(only.file.fileName!! to 5), added, "the ADD of the same name at level 5")
    }

    @Test
    fun `se's two overlapping level-0 files are rewritten into one at level 5`() {
        val (before, compact) = step("se", 3L)
        assertEquals("COMPACT", compact.metadata.commitKind)
        val v = plan(before).single()
        assertEquals(PaimonFullCompactionRule.FULL, v.rule)
        assertEquals(2, v.files.size)
        assertTrue(v.files.all { it.action == PaimonFullCompactionAction.REWRITE && it.group == 1 }, v.files.toString())
        assertEquals("rewrites 2 files in 1 group at level 5 — full compaction", v.describe())
        val (removed, added) = delta(compact)
        assertEquals(v.files.map { it.file.fileName!! }.toSet(), removed.keys)
        assertEquals(1, added.size, "one output file for one group")
        assertEquals(setOf(5), added.values.toSet())
        assertTrue(added.keys.none { it in removed.keys }, "a rewrite writes a new name")
    }

    /** `$files` after `sys.compact` on a copy of each checked-in table, by name → level, and how many of them are new — `paimon-compact.sql`. */
    private data class Run(val after: Map<String, Int>, val newFiles: Int)

    private val runs = mapOf(
        "dv" to Run(mapOf("data-4d98f31d-dda4-4d53-8e49-372d95d23b02-0.parquet" to 5), 1),
        "pc" to Run(mapOf("data-845d483a-334b-417d-840e-af20f30590e2-0.parquet" to 5), 1),
        "lk" to Run(mapOf("data-cc82d002-4c66-43f7-a38c-64289cd9170c-0.parquet" to 5), 1),
        "pu" to Run(mapOf("data-5cfbcaab-b557-4616-8b5e-4d90487d1fb1-0.parquet" to 5), 1),
        "sgd" to Run(mapOf("data-53877684-da50-484d-933c-6644bb4316f9-0.parquet" to 5), 0),
        "fr" to Run(mapOf("data-e738a1c0-fc3f-4f21-807d-4fc6aa2aa392-0.parquet" to 5), 1),
        "pt" to Run(
            mapOf(
                "data-5cd37195-6938-4199-acb7-ce9b10081559-0.parquet" to 5,
                "data-783ed9fc-aa14-4175-813b-a954781ebe47-0.parquet" to 5,
                "data-69aee50c-75a2-4eaf-ac04-051b5d6eef57-0.parquet" to 5,
                "data-d321f687-7c75-4e0f-a0d6-fdfca370d840-0.parquet" to 5,
                "data-b0b3752a-80ec-4666-b2f3-255dffd474c6-0.parquet" to 5,
            ),
            2,
        ),
    )

    /**
     * The seven runs: every file the plan rewrites is gone afterwards, every file it upgrades or
     * keeps is there at the top level under its own name, and the new names number the rewrite
     * groups — `pt`'s three lone files upgraded and its two pairs rewritten into one each, `pc`'s
     * three lone small files rewritten together, `dv`'s two vectored files rewritten into one of
     * 1,497 rows, `sgd`'s one top-level file left alone and no snapshot written.
     */
    @Test
    fun `the plan on each checked-in table is what the procedure did to its copy`() {
        runs.forEach { (name, run) ->
            val m = FixtureCatalog.paimonModel(name)
            val latest = m.snapshots.last()
            val vectored = m.paimonRowLookupInput()?.vectors?.map { it.dataFileName }?.toSet().orEmpty()
            val verdicts = plan(latest, vectored)
            val rewritten = verdicts.flatMap { v -> v.rewritten.map { it.file.fileName!! } }
            val stayed = verdicts.flatMap { v -> (v.upgraded + v.kept).map { it.file.fileName!! } }
            rewritten.forEach { assertTrue(it !in run.after, "$name: $it rewritten and gone") }
            stayed.forEach { assertEquals(5, run.after[it], "$name: $it stays at the top") }
            assertEquals(run.after.keys - stayed.toSet(), run.after.keys.filter { it !in stayed }.toSet(), name)
            assertEquals(run.newFiles, (run.after.keys - stayed.toSet()).size, "$name: the new names")
            assertEquals(run.newFiles, verdicts.sumOf { it.groups }, "$name: one new file per rewrite group")
            if (name == "sgd") assertEquals(PaimonFullCompactionRule.FULLY_COMPACTED, verdicts.single().rule)
            if (name == "dv") assertEquals(setOf(4, 5), verdicts.single().rewritten.map { it.file.level }.toSet(), "two vectored files at 4 and 5, neither overlapping, both under compaction.file-size")
            if (name == "pt") assertEquals(3, verdicts.sumOf { it.upgraded.size })
        }
    }

    @Test
    fun `every primary-key fixture's latest snapshot plans one action per live file, and nothing is upgraded with a -D row in it`() {
        for (name in FixtureCatalog.paimon) {
            val m = FixtureCatalog.paimonModel(name)
            val latest = m.snapshots.lastOrNull() ?: continue
            if (latest.schema?.primaryKeys.isNullOrEmpty()) continue
            val live = replayPaimonSnapshot(latest).liveEntries.values.map { paimonEntryFileName(it) }.toSet()
            val verdicts = plan(latest)
            val planned = verdicts.flatMap { v -> v.files.map { it.file.fileName!! } }
            assertEquals(live, planned.toSet(), "$name: every live file has an action")
            assertEquals(live.size, planned.size, "$name: exactly one")
            verdicts.forEach { v ->
                v.files.forEach { f ->
                    val level = f.file.level ?: 0
                    when (f.action) {
                        PaimonFullCompactionAction.KEEP -> assertEquals(v.outputLevel, level, "$name: a kept file is at the top: ${f.reason}")
                        PaimonFullCompactionAction.UPGRADE -> {
                            assertTrue(level < v.outputLevel, "$name: an upgrade moves up: ${f.reason}")
                            assertEquals(0L, f.file.deleteRowCount, "$name: an upgraded file holds no -D row: ${f.reason}")
                        }
                        PaimonFullCompactionAction.REWRITE -> assertTrue(f.group != null, name)
                        PaimonFullCompactionAction.REWRITE_IN_PLACE -> assertEquals(v.outputLevel, level, name)
                    }
                }
                if (v.rule == PaimonFullCompactionRule.FULLY_COMPACTED) assertTrue(v.files.all { it.action == PaimonFullCompactionAction.KEEP }, name)
            }
        }
    }
}
