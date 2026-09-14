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
        "pt" to 10, "br" to 5, "fi" to 5, "ep" to 3, "sm" to 3, "pe" to 7, "pea" to 7,
        // the table that also writes Iceberg metadata beside its own — the merge is the ordinary one
        "pic" to 3, "pih" to 3, "pid" to 1497,
        // partial-update under sequence groups: inserts only, a -D removing by a single field, and by one of two
        "sg" to 3, "sgd" to 3, "sgm" to 2,
        // the other merge engines: partial-update, aggregation, and first-row — whose one row is
        // what Paimon's own read printed, the DELETE's rewritten file sitting at level 0 unread
        "pu" to 4, "ag" to 2, "fr" to 1,
        // Avro data files, read through read_avro — the merge needs no row position; `paz` the same table under zstd, read through a copy
        "pav" to 2, "paz" to 2,
        // a primary-key column renamed between writes: `_KEY_k` in one file, `_KEY_id` in two
        "pkr" to 3,
        // stats modes: a counts default with per-column overrides, and a per-level none whose file was upgraded
        "psm" to 4, "psl" to 2, "pcl" to 5, "pcn" to 5, "ppx" to 4, "ppxa" to 2, "ptt" to 3, "ptta" to 3, "prb" to 4, "prba" to 2, "pbk" to 3, "pbka" to 4, "po" to 1, "poa" to 1, "pmm" to 10, "pmma" to 10,
        // append tables, from the metadata
        "ad" to 5, "ao" to 6, "rt" to 5, "de" to 3, "der" to 2, "fa" to 5, "ft" to 3, "fb" to 8, "fbs" to 1008, "pse" to 4, "pne" to 3, "psk" to 2,
    )

    @Test
    fun `every fixture's latest snapshot reads as the rows its script left`() {
        expected.forEach { (fixture, rows) ->
            val model = model(fixture)
            val result = countOf(model, model.snapshots.last())
            assertEquals(rows.toLong(), result.merged, "$fixture: $result")
            assertTrue(result.failed == 0 && result.bucketsLeft == 0, "$fixture: $result")
        }
    }

    /** A table added under `example/` gets a figure here or fails here — `test` is Flink-written, and no script printed its read. */
    @Test
    fun `every Paimon fixture but the Flink-written one has a figure`() {
        assertEquals(model.FixtureCatalog.paimon.toSet() - "test", expected.keys)
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
}
