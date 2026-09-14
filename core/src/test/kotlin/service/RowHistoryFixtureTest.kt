package service

import model.MAX_HISTORY_SNAPSHOTS
import model.PaimonUnifiedTableModel
import model.PredicateOp
import model.RowChange
import model.RowFate
import model.RowHistory
import model.ScanFilter
import model.ScanPredicate
import model.UnifiedTableModel
import model.rowHistoryInputs
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The scripts that wrote `mor`, `eqdel` and `lk` say what each commit did to each row — put in,
 * updated, deleted, rewritten by a compaction that changed nothing a read sees — and the trace
 * has to name the same commits: the lookup at every retained snapshot on `main`, and what each
 * commit did to the matching rows against the one before it.
 */
class RowHistoryFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun iceberg(fixture: String, column: String, literal: String): RowHistory {
        val inputs = assertNotNull(UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/$fixture").absolutePath)).rowHistoryInputs(), fixture)
        return RowHistoryTrace.trace(inputs, ScanFilter.Term(ScanPredicate(column, PredicateOp.EQ, literal)), emptySet())
    }

    private fun paimon(fixture: String, column: String, literal: String): RowHistory {
        val inputs = assertNotNull(PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/$fixture").absolutePath)).rowHistoryInputs(), fixture)
        return RowHistoryTrace.trace(inputs, ScanFilter.Term(ScanPredicate(column, PredicateOp.EQ, literal)), emptySet())
    }

    /** Operations oldest first with the change each made, the way the script lists the commits. */
    private fun RowHistory.timeline(): List<Pair<String?, RowChange?>> =
        steps.map { it.snapshot.operation }.zip(changes).asReversed()

    @Test
    fun `mor traces a row through its insert, the compaction that kept it, and the delete that removed it`() {
        // Script: 1 INSERT 1..4, 2 INSERT 5..7, 3 DELETE 2, 4 UPDATE 5, 5 rewrite_data_files, 6 DELETE 7.
        val seven = iceberg("mor", "id", "7")
        assertEquals(6, seven.onMain)
        assertEquals(false, seven.capped)
        assertEquals(
            listOf("append" to null, "append" to RowChange.APPEARED, "delete" to RowChange.UNCHANGED, "overwrite" to RowChange.UNCHANGED, "replace" to RowChange.UNCHANGED, "delete" to RowChange.GONE),
            seven.timeline(),
        )
        // The newest step is the current snapshot's fate; the oldest holds no such row at all.
        assertEquals(RowFate.POSITION_DELETED, seven.steps.first().result.hits.single().fate)
        assertEquals(0, seven.steps.last().result.hits.size)
        assertEquals(listOf("delete", "append"), seven.changedSteps.map { it.snapshot.operation })
    }

    @Test
    fun `mor's updated row changes at the update and not at the compaction that rewrote its file`() {
        val five = iceberg("mor", "id", "5")
        assertEquals(
            listOf("append" to null, "append" to RowChange.APPEARED, "delete" to RowChange.UNCHANGED, "overwrite" to RowChange.CHANGED, "replace" to RowChange.UNCHANGED, "delete" to RowChange.UNCHANGED),
            five.timeline(),
        )
        // At the update both versions are found: the old one deleted by position, the new one live.
        val atUpdate = five.steps.first { it.snapshot.operation == "overwrite" }
        assertEquals(setOf(RowFate.POSITION_DELETED, RowFate.LIVE), atUpdate.result.hits.map { it.fate }.toSet())
        assertEquals(listOf("echo-updated"), atUpdate.liveRows.map { it["name"] })
        // Before it, one live row; the compaction after it keeps the updated value.
        assertEquals(listOf("echo"), five.steps.first { it.snapshot.operation == "delete" && it.snapshot.snapshotId != five.steps.first().snapshot.snapshotId }.liveRows.map { it["name"] })
        assertEquals(listOf("echo-updated"), five.steps.first().liveRows.map { it["name"] })
    }

    @Test
    fun `a row deleted before the compaction is gone at the delete, and the compaction leaves nothing to find`() {
        val two = iceberg("mor", "id", "2")
        assertEquals(
            listOf("append" to null, "append" to RowChange.UNCHANGED, "delete" to RowChange.GONE, "overwrite" to RowChange.UNCHANGED, "replace" to RowChange.UNCHANGED, "delete" to RowChange.UNCHANGED),
            two.timeline(),
        )
        assertEquals(RowFate.POSITION_DELETED, two.steps.first { it.snapshot.operation == "delete" && it.changesHere(two) == RowChange.GONE }.result.hits.single().fate)
        assertEquals(0, two.steps.first().result.hits.size, "compacted away")
    }

    private fun model.RowHistoryStep.changesHere(history: RowHistory): RowChange? = history.changes[history.steps.indexOf(this)]

    @Test
    fun `eqdel traces the equality delete to the commit that wrote it`() {
        // Script: two inserts, an equality delete of 2 and 6 written by hand, a DELETE of 3 by position.
        val two = iceberg("eqdel", "id", "2")
        val gone = two.steps.filterIndexed { i, _ -> two.changes[i] == RowChange.GONE }.single()
        assertEquals(RowFate.EQUALITY_DELETED, gone.result.hits.single().fate)
        // In the first insert, so the oldest snapshot already holds it: nothing to appear at.
        assertEquals(listOf("bravo"), two.steps.last().liveRows.map { it["name"] })
        assertEquals(listOf(gone), two.changedSteps)
        val four = iceberg("eqdel", "id", "4")
        assertEquals(emptyList(), four.changedSteps, "${four.timeline()}")
        assertTrue(four.steps.all { it.liveRows.size == 1 }, "${four.timeline()}")
    }

    @Test
    fun `lk traces a key through the append that superseded its value and the compaction that did not`() {
        // Script: INSERT 1,2,3; INSERT 2 (new value), 4; DELETE 3 — each APPEND followed by the lookup producer's COMPACT.
        val two = paimon("lk", "k", "2")
        assertEquals(6, two.onMain)
        assertEquals(
            listOf("APPEND" to null, "COMPACT" to RowChange.UNCHANGED, "APPEND" to RowChange.CHANGED, "COMPACT" to RowChange.UNCHANGED, "APPEND" to RowChange.UNCHANGED, "COMPACT" to RowChange.UNCHANGED),
            two.timeline(),
        )
        assertEquals(listOf("b"), two.steps.last().liveRows.map { it["v"] })
        assertEquals(listOf("B"), two.steps.first().liveRows.map { it["v"] })
        val three = paimon("lk", "k", "3")
        assertEquals(RowChange.GONE, three.changes[1], "${three.timeline()}")
        assertEquals(RowFate.RETRACTION, three.steps[1].result.hits.first { it.fate != RowFate.SUPERSEDED }.fate)
    }

    @Test
    fun `the trace is capped and says so`() {
        assertTrue(MAX_HISTORY_SNAPSHOTS in 5..100)
        val history = iceberg("mor", "id", "1")
        assertEquals(history.steps.size < history.onMain, history.capped)
        assertEquals(6, history.steps.size)
    }
}
