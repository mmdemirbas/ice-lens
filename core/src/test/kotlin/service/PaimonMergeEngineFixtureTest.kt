package service

import model.PaimonMergeKeeps
import model.PaimonRowKind
import model.PaimonUnifiedTableModel
import model.PredicateOp
import model.RowFate
import model.ScanFilter
import model.ScanPredicate
import model.paimonMergeRuleOf
import model.paimonReadInputOf
import model.paimonRowLookupInput
import model.replayPaimonSnapshot
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The three merge engines other than `deduplicate`, on tables written under each
 * (`docs/fixtures/paimon-me.sql`), the count and the lookup held to what Paimon's own read of
 * each table printed. `fr` is the one that surprised: Spark refuses an upsert delete on a
 * first-row table and rewrites the file instead, to level 0 — and a batch read of a first-row
 * table skips level 0, so the row the rewrite kept is not read.
 */
class PaimonMergeEngineFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun model(fixture: String) = PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/$fixture").absolutePath))

    private fun lookup(fixture: String, column: String, literal: String) =
        PaimonRowLookup.lookup(assertNotNull(model(fixture).paimonRowLookupInput()), ScanFilter.Term(ScanPredicate(column, PredicateOp.EQ, literal)), emptySet())

    @Test
    fun `the rules are read off the options`() {
        val dedup = paimonMergeRuleOf(emptyMap(), hasPrimaryKey = true)
        assertEquals(PaimonMergeKeeps.LATEST, dedup.keeps)
        assertEquals(setOf(PaimonRowKind.UPDATE_BEFORE, PaimonRowKind.DELETE), dedup.removingKinds)
        assertTrue(!dedup.skipsLevel0)
        assertTrue(paimonMergeRuleOf(mapOf("deletion-vectors.enabled" to "true"), hasPrimaryKey = true).skipsLevel0)
        assertTrue(!paimonMergeRuleOf(mapOf("deletion-vectors.enabled" to "true"), hasPrimaryKey = false).skipsLevel0)
        val first = paimonMergeRuleOf(mapOf("merge-engine" to "first-row"), hasPrimaryKey = true)
        assertEquals(PaimonMergeKeeps.FIRST, first.keeps)
        assertTrue(first.skipsLevel0 && first.retractionsRejected && first.removingKinds.isEmpty())
        assertTrue(paimonMergeRuleOf(mapOf("merge-engine" to "first-row", "first-row.ignore-delete" to "true"), hasPrimaryKey = true).retractionsIgnored)
        val partial = paimonMergeRuleOf(mapOf("merge-engine" to "partial-update"), hasPrimaryKey = true)
        assertTrue(partial.retractionsRejected && partial.removingKinds.isEmpty() && partial.applied)
        val partialRemoving = paimonMergeRuleOf(mapOf("merge-engine" to "partial-update", "partial-update.remove-record-on-delete" to "true"), hasPrimaryKey = true)
        assertEquals(setOf(PaimonRowKind.DELETE), partialRemoving.removingKinds)
        assertTrue(!partialRemoving.retractionsRejected)
        assertTrue(!paimonMergeRuleOf(mapOf("merge-engine" to "partial-update", "fields.b.sequence-group" to "s"), hasPrimaryKey = true).applied)
        val agg = paimonMergeRuleOf(mapOf("merge-engine" to "aggregation"), hasPrimaryKey = true)
        assertTrue(agg.removingKinds.isEmpty() && !agg.retractionsRejected && agg.keeps == PaimonMergeKeeps.COMBINED)
    }

    @Test
    fun `partial-update folds a key's records into one row, and a -D removes the key until the next write`() {
        val two = lookup("pu", "k", "2").hits.sortedBy { it.cells["_SEQUENCE_NUMBER"] as Long }
        assertEquals(listOf(RowFate.MERGED, RowFate.MERGED), two.map { it.fate }, "$two")
        assertEquals(2, lookup("pu", "k", "2").live)
        val three = lookup("pu", "k", "3").hits.sortedBy { it.cells["_SEQUENCE_NUMBER"] as Long }
        // (3, a3, b3) then the -D, then (3, a3-again): the first is superseded by the delete, the delete is a retraction, the re-insert is the row
        assertEquals(listOf(RowFate.SUPERSEDED, RowFate.RETRACTION, RowFate.LIVE), three.map { it.fate }, "$three")
        assertTrue(assertNotNull(three[0].note).endsWith("-D (delete)"), three[0].note.orEmpty())
        assertEquals(1, lookup("pu", "k", "4").hits.size)
        assertEquals(RowFate.LIVE, lookup("pu", "k", "4").hits.single().fate)
    }

    @Test
    fun `aggregation folds every record, and a -D under remove-record-on-delete removes the key`() {
        val one = lookup("ag", "k", "1").hits
        assertEquals(setOf(RowFate.MERGED), one.map { it.fate }.toSet(), "$one")
        val two = lookup("ag", "k", "2").hits.sortedBy { it.cells["_SEQUENCE_NUMBER"] as Long }
        assertEquals(listOf(RowFate.SUPERSEDED, RowFate.RETRACTION), two.map { it.fate }, "$two")
    }

    @Test
    fun `first-row keeps the earliest record, and the rewrite a DELETE left at level 0 is not read`() {
        val fr = model("fr")
        val input = assertNotNull(fr.paimonRowLookupInput())
        assertTrue(input.rule.skipsLevel0)
        assertEquals(1, input.skippedFiles.size, "the DELETE's rewritten file, at level 0: ${input.skippedFiles}")
        val keyOne = lookup("fr", "k", "1").hits
        assertEquals(listOf(RowFate.SKIPPED), keyOne.map { it.fate }, "k 1's only live record is in the skipped file: $keyOne")
        assertEquals(RowFate.LIVE, lookup("fr", "k", "3").hits.single().fate)
        assertEquals(1L, PaimonMergedCount.count(input).merged, "what Paimon's own read printed: `3 first` and nothing else")
        // Before the DELETE, at snapshot 4, the second write of k 1 had been dropped by the compaction: three rows.
        val four = fr.snapshots.single { it.metadata.id == 4L }
        assertEquals(3L, PaimonMergedCount.count(assertNotNull(fr.paimonReadInputOf(four, replayPaimonSnapshot(four)))).merged)
        // And at snapshot 3, before that compaction, the first-row rule decides it: k 1 'second' is superseded by 'first'.
        val snapThree = fr.snapshots.single { it.metadata.id == 3L }
        val atThree = assertNotNull(fr.paimonReadInputOf(snapThree, replayPaimonSnapshot(snapThree)))
        assertEquals(1, atThree.skippedFiles.size, "the append's level-0 file, before the compaction moved it up")
        assertEquals(2L, PaimonMergedCount.count(atThree).merged, "k 1 and k 2 from the compacted file; the level-0 append is not read — Paimon read `1 first, 2 first` there")
        // Snapshot 1 is one append at level 0 and nothing else: Paimon's read of it returned no rows at all.
        val one = fr.snapshots.single { it.metadata.id == 1L }
        val atOne = PaimonMergedCount.count(assertNotNull(fr.paimonReadInputOf(one, replayPaimonSnapshot(one))))
        assertEquals(0L, atOne.merged)
        assertEquals(2L, atOne.skippedRows)
    }

    @Test
    fun `the file node says when a batch read would skip it`() {
        val fr = GraphLayoutService.layoutGraph(model("fr"), showRows = false)
        val files = fr.nodes.filterIsInstance<model.GraphNode.PaimonDataFileNode>()
        val unread = files.filter { it.unreadByBatchRead }.map { it.entry.file?.fileName }.toSet()
        assertTrue(unread.isNotEmpty(), "the appends and the rewrite were written to level 0")
        assertTrue(files.filter { it.unreadByBatchRead }.all { it.level == 0 })
        assertTrue(files.filter { (it.level ?: 0) > 0 }.none { it.unreadByBatchRead })
        val lk = GraphLayoutService.layoutGraph(model("lk"), showRows = false)
        assertTrue(lk.nodes.filterIsInstance<model.GraphNode.PaimonDataFileNode>().none { it.unreadByBatchRead }, "deduplicate under lookup reads level 0")
    }
}
