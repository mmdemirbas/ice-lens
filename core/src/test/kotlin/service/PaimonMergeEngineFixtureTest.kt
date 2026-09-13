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
        val grouped = paimonMergeRuleOf(mapOf("merge-engine" to "partial-update", "fields.b.sequence-group" to "s"), hasPrimaryKey = true)
        assertTrue(grouped.applied && grouped.sequenceGroups && !grouped.retractionsRejected && grouped.removingKinds.isEmpty() && grouped.sequenceGroupRemovals.isEmpty())
        val groupedRemoving = paimonMergeRuleOf(
            mapOf("merge-engine" to "partial-update", "fields.b.sequence-group" to "s", "partial-update.remove-record-on-sequence-group" to "b"),
            hasPrimaryKey = true,
        )
        assertEquals(listOf(listOf("b")), groupedRemoving.sequenceGroupRemovals)
        assertTrue(groupedRemoving.applied && groupedRemoving.removingKinds.isEmpty(), "a -D removes by its value on b, not by its kind")
        assertTrue(dedup.keyNeedsInsert && !paimonMergeRuleOf(mapOf("merge-engine" to "aggregation"), hasPrimaryKey = true).keyNeedsInsert)
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

    /**
     * `sg` and `sgd` are the multi-stream-join shape: `partial-update` with `fields.ga.sequence-group
     * = a` and `fields.gb.sequence-group = b`. Paimon printed three rows for each (the script's
     * header), and for `sgd`, whose `remove-record-on-sequence-group = ga` let Spark's DELETE
     * write a `-D`, two rows at snapshot 4 — the key removed, then written again at 5. The
     * `-D` carries the row's `ga` (10, at or above 10), which is what makes it remove.
     */
    @Test
    fun `sequence groups fold, and a -D removes the key only at or above the row's named sequence field`() {
        val sg = assertNotNull(model("sg").paimonRowLookupInput())
        assertTrue(sg.rule.sequenceGroups && sg.rule.applied && sg.rule.sequenceGroupRemovals.isEmpty())
        assertEquals(3L, PaimonMergedCount.count(sg).merged, "Paimon printed three rows")
        val keyOne = lookup("sg", "k", "1").hits
        assertEquals(listOf(RowFate.MERGED, RowFate.MERGED), keyOne.map { it.fate }.sortedBy { it.name }, "two inserts folded, whichever group each moved: $keyOne")

        val sgd = model("sgd")
        assertEquals(listOf(listOf("ga")), assertNotNull(sgd.paimonRowLookupInput()).rule.sequenceGroupRemovals)
        val expected = mapOf(1L to 2L, 2L to 2L, 3L to 3L, 4L to 2L, 5L to 3L, 6L to 3L)
        for ((id, rows) in expected) {
            val snapshot = sgd.snapshots.single { it.metadata.id == id }
            val result = PaimonMergedCount.count(assertNotNull(sgd.paimonReadInputOf(snapshot, replayPaimonSnapshot(snapshot))))
            assertTrue(result.applied, "snapshot $id")
            assertEquals(rows, result.merged, "snapshot $id: ${result.buckets}")
        }
        // At snapshot 5 the key's three records are all live files: the first insert dropped by
        // the -D, the -D that removed the key, and the insert after it as the row.
        val five = sgd.snapshots.single { it.metadata.id == 5L }
        val atFive = assertNotNull(sgd.paimonReadInputOf(five, replayPaimonSnapshot(five)))
        val keyTwo = PaimonRowLookup.lookup(atFive, ScanFilter.Term(ScanPredicate("k", PredicateOp.EQ, "2")), emptySet()).hits.sortedBy { it.cells["_SEQUENCE_NUMBER"].toString().toLong() }
        assertEquals(listOf(RowFate.SUPERSEDED, RowFate.RETRACTION, RowFate.LIVE), keyTwo.map { it.fate }, keyTwo.toString())
        assertTrue(assertNotNull(keyTwo[1].note).contains("removed the key"), keyTwo[1].note.orEmpty())
        assertEquals(keyTwo[1].filePath, keyTwo[0].by, "the dropped insert names the -D's file")
        assertEquals(0L, PaimonMergedCount.count(atFive).buckets.single().retracted, "no key's last record is that -D any more")
        val four = sgd.snapshots.single { it.metadata.id == 4L }
        assertEquals(1L, PaimonMergedCount.count(assertNotNull(sgd.paimonReadInputOf(four, replayPaimonSnapshot(four)))).buckets.single().retracted, "at snapshot 4 it is")
    }

    /** The fold alone, on the shapes the fixture cannot write: a `-D` below the row's value, a null one, a key never inserted. */
    @Test
    fun `the sequence-group fold removes at or above, skips null groups, and reports a key never inserted`() {
        fun rec(s: Long, kind: Int, ga: Int?) = PaimonSequenceGroups.Record(s, kind, "f$s", listOf(listOf(ga)))
        val notNull = listOf(listOf(false))
        val insert = PaimonRowKind.INSERT
        val delete = PaimonRowKind.DELETE
        assertTrue(PaimonSequenceGroups.fold(listOf(rec(1, insert, 10), rec(2, delete, 10)), notNull).gone, "at the row's value removes")
        assertTrue(!PaimonSequenceGroups.fold(listOf(rec(1, insert, 10), rec(2, delete, 9)), notNull).gone, "below it retracts columns only")
        assertTrue(!PaimonSequenceGroups.fold(listOf(rec(1, insert, 10), rec(2, delete, null)), notNull).gone, "a null group is skipped")
        val reinserted = PaimonSequenceGroups.fold(listOf(rec(1, insert, 10), rec(2, delete, 10), rec(3, insert, 1)), notNull)
        assertTrue(!reinserted.gone && reinserted.removals.map { it.sequence } == listOf(2L), "the row starts over after a removal, so a lower value inserts")
        assertTrue(PaimonSequenceGroups.fold(listOf(rec(1, delete, 5)), notNull).gone, "never inserted")
        assertTrue(PaimonSequenceGroups.fold(listOf(rec(1, insert, 10), rec(2, PaimonRowKind.UPDATE_BEFORE, 11)), notNull).let { !it.gone && it.removals.isEmpty() }, "a -U never removes")
    }
}
