package service

import model.PaimonUnifiedSnapshot
import model.PaimonUnifiedTableModel
import model.paimonReadInputOf
import model.replayPaimonSnapshot
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `SELECT count(*)` as of every fixture's latest snapshot, read off the script that wrote it —
 * the rows its inserts left after its updates, deletes and overwrites — against the merge this
 * runs over the bucket files. `cl` carries the one figure an engine wrote: the `ANALYZE` commit's
 * `mergedRecordCount`, for the snapshot before it, which is checked at that snapshot.
 */
class PaimonMergedCountFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun model(fixture: String) = PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/$fixture").absolutePath))

    private fun countOf(model: PaimonUnifiedTableModel, snapshot: PaimonUnifiedSnapshot): PaimonMergedCount.Result =
        PaimonMergedCount.count(assertNotNull(model.paimonReadInputOf(snapshot, replayPaimonSnapshot(snapshot))))

    /** Fixture to the rows its script's final table holds, and whether that took a read. */
    private val expected = mapOf(
        // primary-key tables, merged
        "lk" to 3, "dv" to 1497, "pc" to 7, "px" to 6, "pxa" to 6, "cs" to 4, "se" to 3, "cl" to 2, "tg" to 2,
        "pt" to 10, "br" to 5, "fi" to 5, "ep" to 3, "sm" to 3, "pea" to 7,
        // the other merge engines: partial-update, aggregation, and first-row — whose one row is
        // what Paimon's own read printed, the DELETE's rewritten file sitting at level 0 unread
        "pu" to 4, "ag" to 2, "fr" to 1,
        // append tables, from the metadata
        "ad" to 5, "ao" to 6, "rt" to 5, "de" to 3,
    )

    @Test
    fun `every fixture's latest snapshot reads as the rows its script left`() {
        expected.forEach { (fixture, rows) ->
            val model = model(fixture)
            val result = countOf(model, model.snapshots.last())
            assertEquals(rows.toLong(), result.merged, "$fixture: $result")
            assertTrue(result.applied && result.failed == 0 && result.bucketsLeft == 0, "$fixture: $result")
        }
    }

    @Test
    fun `an append table's count comes from the metadata, and a primary-key table's from the files`() {
        assertTrue(countOf(model("ad"), model("ad").snapshots.last()).fromMetadata)
        val ad = countOf(model("ad"), model("ad").snapshots.last())
        assertEquals(2L, ad.vectorMarked, "id 2 and 6 are vector-marked")
        assertEquals(7L, ad.fileRows)
        val dv = countOf(model("dv"), model("dv").snapshots.last())
        assertTrue(!dv.fromMetadata)
        assertEquals(1500L, dv.fileRows)
        assertEquals(3L, dv.vectorMarked, "k 2, 1001 and 1500")
        assertEquals(0L, dv.retracted, "the -D file was compacted away, its work is in the vectors")
    }

    @Test
    fun `a deleted key's latest record is its retraction, and an updated key merges to one`() {
        val lk = countOf(model("lk"), model("lk").snapshots.last())
        val bucket = lk.buckets.single()
        assertEquals(6L, bucket.fileRows, "six records across three files")
        assertEquals(4L, bucket.keys, "keys 1, 2, 3, 4")
        assertEquals(1L, bucket.retracted, "k 3's latest record is its -D")
        assertEquals(3L, bucket.merged)
    }

    /** The one engine-written oracle: `ANALYZE` recorded the merged count for the snapshot before it. */
    @Test
    fun `the count at the analyzed snapshot is what ANALYZE recorded`() {
        val cl = model("cl")
        val statistics = assertNotNull(cl.snapshots.last().statistics)
        val analyzed = cl.snapshots.single { it.metadata.id == statistics.snapshotId }
        assertEquals(statistics.mergedRecordCount, countOf(cl, analyzed).merged)
        // And before the overwrite, the three inserts and the delete left keys 1, 2 and 4.
        assertEquals(3L, countOf(cl, cl.snapshots.single { it.metadata.id == 3L }).merged)
    }

    @Test
    fun `a branch's snapshot merges the branch's files`() {
        val br = model("br")
        val dev = br.branches.single { it.name == "dev" }
        assertEquals(4L, countOf(br, dev.snapshots.last()).merged, "keys 1, 2, 3 from the tag and 5 from the branch")
    }

    @Test
    fun `partial-update with sequence groups is reported, not applied`() {
        val pc = model("pc")
        val input = assertNotNull(pc.paimonReadInputOf(pc.snapshots.last(), replayPaimonSnapshot(pc.snapshots.last())))
        val options = input.schema.options + mapOf("merge-engine" to "partial-update", "fields.v.sequence-group" to "k")
        val result = PaimonMergedCount.count(input.copy(rule = model.paimonMergeRuleOf(options, hasPrimaryKey = true)))
        assertTrue(!result.applied)
        assertNull(result.merged)
        assertEquals("partial-update", result.mergeEngine)
    }
}
