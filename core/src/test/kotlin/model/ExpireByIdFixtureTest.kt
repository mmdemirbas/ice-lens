package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `expire_snapshots(snapshot_ids => array(id))`, held to `docs/fixtures/expire-by-id.sql`: on
 * copies of `branched`, `mor` and `rolled` under `older_than => TIMESTAMP '2000-01-01'`, so the
 * age rule removed nothing and each call's effect is the id's alone. `branched`'s tagged, branch-tip
 * and main-tip snapshots were refused with `Cannot expire <id>. Still referenced by refs: [...]`;
 * its first commit, held by nothing but its place under main's tip, went with its manifest list
 * alone; `mor`'s overwrite in the middle of main's line went with its manifest and the file the
 * compaction after it had already removed; `rolled`'s abandoned commit went with its file.
 */
class ExpireByIdFixtureTest {

    private fun newest(name: String) = FixtureCatalog.icebergModel(name).metadatas.last().metadata

    private fun plan(name: String, id: Long): Pair<ExpiryPlan, ExpiryFilePlan?> {
        val meta = newest(name)
        val plan = meta.planExpiry(ExpiryOptions(nowMs = meta.lastUpdatedMs ?: 0L, olderThanMs = 0L, snapshotIds = setOf(id)))
        val files = if (plan.refusal == null) FixtureCatalog.icebergModel(name).expiryFileInput().planExpiryFiles(setOf(id)) else null
        return plan to files
    }

    private fun refsIn(refusal: String) = refusal.substringAfter("[").substringBefore("]").split(", ").toSet()

    @Test
    fun `branched refuses the ids its refs name and removes its first commit with the manifest list alone`() {
        assertEquals(setOf("v1"), refsIn(plan("branched", 1466525117601214788L).first.refusal!!))
        assertEquals(setOf("audit"), refsIn(plan("branched", 1183816113347240589L).first.refusal!!))
        assertEquals(setOf("prod", "main"), refsIn(plan("branched", 8788892783725052840L).first.refusal!!), "the order Iceberg printed was [prod, main], a hash map's")
        assertEquals(emptyList(), plan("branched", 1466525117601214788L).first.removed, "a refused call removes nothing")

        val (plan, files) = plan("branched", 6979025444437793121L)
        assertNull(plan.refusal)
        assertEquals(listOf(6979025444437793121L), plan.removed)
        val verdict = plan.snapshots.single { it.snapshotId == 6979025444437793121L }
        assertTrue(verdict.expiredById)
        assertTrue(verdict.keptBy.any { it.rule == KeepRule.NEWER_THAN_CUTOFF && it.ref == "main" }, "a bare call under this cutoff keeps it: ${verdict.keptBy}")
        assertNotNull(files)
        assertEquals(mapOf(ExpiryFileKind.MANIFEST_LIST to 1), files.files.groupingBy { it.kind }.eachCount(), "0 0 0 0 1 0 — the manifest is carried by every later list")
        // The first entry of the log: nothing before it to cut, and `.history` afterwards holds the other three.
        assertEquals(listOf(1466525117601214788L, 3698023222460817889L, 8788892783725052840L), plan.logAfter.map { it.snapshotId })
        assertEquals(1, plan.logEntriesDropped)
        assertTrue(files.ofKind(ExpiryFileKind.MANIFEST_LIST).single().path.contains("snap-6979025444437793121-"))
    }

    @Test
    fun `mor's overwrite in the middle of main goes with its manifest and the file the compaction had removed`() {
        val (plan, files) = plan("mor", 6495533870975959056L)
        assertNull(plan.refusal)
        assertEquals(listOf(6495533870975959056L), plan.removed)
        assertNotNull(files)
        assertEquals(ExpiryCleanup.REACHABLE, files.cleanup, "the procedure's diff, whatever the ref count")
        assertEquals(
            setOf(
                "data/00000-7-a310efbe-6c53-43c4-813b-0dcce2b29751-00001.parquet",
                "metadata/2d4bf52e-9996-4609-8d91-c957af962214-m0.avro",
                "metadata/snap-6495533870975959056-1-2d4bf52e-9996-4609-8d91-c957af962214.avro",
            ),
            files.files.map { it.path.substringAfter("/mor/") }.toSet(),
            "1 0 0 1 1 0: one data file, one manifest, one list",
        )
        // Its children keep naming it: the line reads as starting at the replace after it.
        assertEquals(listOf(4129372135852686703L), newest("mor").snapshots.filter { it.parentSnapshotId == 6495533870975959056L }.map { it.snapshotId })
        // `.history` afterwards: the removed entry and the three before it are gone, the two after stay.
        assertEquals(6, plan.snapshotLog.size)
        assertEquals(listOf(4129372135852686703L, 6710179397072758000L), plan.logAfter.map { it.snapshotId })
        assertEquals(4, plan.logEntriesDropped)
        // The core API forces the reachable diff too once an id is specified; the incremental rule it
        // runs for an age-based expiry at one ref would leave that data file on disk: its ADDED
        // manifest is an ancestor's, its DELETED entry a retained commit's, and neither branch reaches it.
        val input = FixtureCatalog.icebergModel("mor").expiryFileInput()
        assertEquals(ExpiryCleanup.REACHABLE, input.coreApiCleanup(setOf(6495533870975959056L), byId = true))
        assertEquals(ExpiryCleanup.INCREMENTAL, input.coreApiCleanup(setOf(6495533870975959056L)), "one ref, an age-based call")
        val incremental = input.planExpiryFiles(setOf(6495533870975959056L), ExpiryCleanup.INCREMENTAL)
        assertEquals(emptyList(), incremental.ofKind(ExpiryFileKind.DATA_FILE), "the file the procedure freed, left as an orphan by the API")
        assertEquals(files.paths - incremental.paths, files.ofKind(ExpiryFileKind.DATA_FILE).map { normalizeFilePath(it.path) }.toSet())
    }

    @Test
    fun `rolled's abandoned commit goes with its file`() {
        val (plan, files) = plan("rolled", 8241983636156380084L)
        assertNull(plan.refusal)
        assertNotNull(files)
        assertEquals(
            setOf(
                "data/00000-2-80b7a28d-e0b5-4714-a449-a065002c2a65-0-00001.parquet",
                "metadata/77c89a5a-181d-467b-871a-5c3c3772fde8-m0.avro",
                "metadata/snap-8241983636156380084-1-77c89a5a-181d-467b-871a-5c3c3772fde8.avro",
            ),
            files.files.map { it.path.substringAfter("/rolled/") }.toSet(),
        )
        assertEquals(ExpiryFileReason.UNREACHABLE, files.ofKind(ExpiryFileKind.DATA_FILE).single().reason, "the procedure's diff: live in no retained manifest")
        // The incremental rule — an age-based core-API expiry at one ref — frees the same file, as a reverted commit's addition.
        val input = FixtureCatalog.icebergModel("rolled").expiryFileInput()
        val incremental = input.planExpiryFiles(setOf(8241983636156380084L), input.coreApiCleanup(setOf(8241983636156380084L)))
        assertEquals(ExpiryCleanup.INCREMENTAL, incremental.cleanup)
        assertEquals(files.paths, incremental.paths, "the two rules agree on rolled")
        assertEquals(ExpiryFileReason.REVERTED, incremental.ofKind(ExpiryFileKind.DATA_FILE).single().reason)
    }

    /**
     * The log rule against the four expiries Iceberg ran in the corpus, with what each removed
     * read off the two metadata files. `sweepb` is the one that shows the cut on a bare call: the
     * branch keeps 8337608489946395297, whose entry sits before three removed ones, and `sweptb`'s
     * log holds the tip alone.
     */
    @Test
    fun `the log is cut before a removed entry, which the four expiries the fixtures ran all show`() {
        val log = (1L..5L).map { SnapshotLogEntry(timestampMs = it * 1000, snapshotId = it) }
        assertEquals(listOf(4L, 5L), log.afterRemoving(setOf(3L)).map { it.snapshotId }, "the entries before 3 go with it")
        assertEquals(listOf(3L, 4L, 5L), log.afterRemoving(setOf(1L, 2L)).map { it.snapshotId }, "a prefix removed cuts nothing more")
        assertEquals(listOf(5L), log.afterRemoving(setOf(2L, 4L)).map { it.snapshotId }, "cut at the last removed entry")
        assertEquals(log, log.afterRemoving(emptySet()))
        assertEquals(log, log.afterRemoving(setOf(9L)), "an id with no entry cuts nothing")
        fun check(name: String, before: TableMetadata, after: TableMetadata) {
            val removed = before.snapshots.map { it.snapshotId }.toSet() - after.snapshots.map { it.snapshotId }.toSet()
            assertTrue(removed.isNotEmpty(), name)
            assertEquals(after.snapshotLog.map { it.snapshotId }, before.snapshotLog.afterRemoving(removed.filterNotNull().toSet()).map { it.snapshotId }, name)
        }
        check("sweep → swept", newest("sweep"), newest("swept"))
        check("sweepb → sweptb", newest("sweepb"), newest("sweptb"))
        for (name in listOf("expired", "retained")) {
            val versions = FixtureCatalog.icebergModel(name).metadatas
            check(name, versions[versions.size - 2].metadata, versions.last().metadata)
        }
        assertEquals(listOf(1069833806646654058L), newest("sweptb").snapshotLog.map { it.snapshotId }, "sweptb: the branch-kept 8337608489946395297's entry was cut")
    }

    /** Every snapshot of every fixture: refused exactly when a ref names it, else removed alone under a cutoff at the epoch. */
    @Test
    fun `every snapshot is refused exactly when a ref names it, and otherwise goes alone`() {
        FixtureCatalog.iceberg.forEach { name ->
            val meta = newest(name)
            val named = meta.refs.values.map { it.snapshotId }.toSet()
            meta.snapshots.mapNotNull { it.snapshotId }.forEach { id ->
                val plan = meta.planExpiry(ExpiryOptions(nowMs = meta.lastUpdatedMs ?: 0L, olderThanMs = 0L, snapshotIds = setOf(id)))
                if (id in named) {
                    assertNotNull(plan.refusal, "$name: $id is named by a ref")
                    assertEquals(meta.refs.filterValues { it.snapshotId == id }.keys, refsIn(plan.refusal!!), "$name: $id")
                    assertEquals(emptyList(), plan.removed, name)
                } else {
                    assertNull(plan.refusal, "$name: $id")
                    assertEquals(listOf(id), plan.removed, "$name: $id alone")
                }
            }
        }
    }
}
