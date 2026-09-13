package model

import java.io.File
import java.nio.file.Paths
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `docs/fixtures/cleanup.sql` wrote two tables and copied each on disk before running
 * `expire_snapshots(older_than => 2099, retain_last => 1)` on the original in place — so `sweep`
 * is `swept` before, complete, with the same file names. The plan from the copy has to name
 * exactly the files the expiry took out of the original, which is read off the two directories.
 * `swept` has one ref and ran the incremental cleanup; `sweptb` has a branch and ran the
 * reachable one, and the difference between the two is the point: the same deleting commit
 * frees its file under one and not the other.
 */
class ExpiryFilePlanFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun dir(fixture: String) = File(repoRoot, "example/iceberg/default/$fixture")
    private fun model(fixture: String) = UnifiedTableModel(Paths.get(dir(fixture).absolutePath))

    /** Every file under the table, by name — names are unique, and the two copies share them. */
    private fun namesOnDisk(fixture: String): Set<String> =
        dir(fixture).walkTopDown().filter { it.isFile }.map { it.name }.toSet()

    private val y2099 = Instant.parse("2099-01-01T00:00:00Z").toEpochMilli()

    private fun planFor(fixture: String): Pair<ExpiryPlan, ExpiryFilePlan> {
        val m = model(fixture)
        val expiry = m.metadatas.last().metadata.planExpiry(ExpiryOptions(nowMs = y2099, olderThanMs = y2099, retainLast = 1))
        return expiry to m.expiryFileInput().planExpiryFiles(expiry.removed.toSet())
    }

    @Test
    fun `with one ref, the incremental cleanup frees a deleted file and a reverted one, and keeps the carried manifest`() {
        val (expiry, plan) = planFor("sweep")
        assertEquals(4, expiry.removed.size)
        assertEquals(ExpiryCleanup.INCREMENTAL, plan.cleanup)
        assertEquals(4, plan.ofKind(ExpiryFileKind.MANIFEST_LIST).size)
        assertEquals(3, plan.ofKind(ExpiryFileKind.MANIFEST).size)
        assertEquals(setOf(ExpiryFileReason.DELETED_BY_EXPIRED, ExpiryFileReason.REVERTED), plan.ofKind(ExpiryFileKind.DATA_FILE).map { it.reason }.toSet())
        assertEquals("4 manifest lists, 3 manifests, 2 data files", plan.describe)

        val gone = namesOnDisk("sweep") - namesOnDisk("swept")
        assertEquals(gone, plan.files.map { it.path.substringAfterLast('/') }.toSet())
        // and every metadata version stays — the expiry writes one more, and deletes none.
        assertTrue(gone.none { it.endsWith(".metadata.json") })
    }

    @Test
    fun `with a branch, the reachable cleanup frees the manifest and not the file it recorded as removed`() {
        val (expiry, plan) = planFor("sweepb")
        assertEquals(3, expiry.removed.size)
        assertEquals(ExpiryCleanup.REACHABLE, plan.cleanup)
        assertEquals(3, plan.ofKind(ExpiryFileKind.MANIFEST_LIST).size)
        assertEquals(1, plan.ofKind(ExpiryFileKind.MANIFEST).size)
        assertEquals(emptyList(), plan.ofKind(ExpiryFileKind.DATA_FILE))
        // The one manifest is the delete's rewrite: a DELETED entry and nothing live.
        val manifest = plan.ofKind(ExpiryFileKind.MANIFEST).single()
        val m = model("sweepb")
        val entries = m.expiryFileInput().entriesOf(manifest.path)!!
        assertEquals(listOf(ManifestEntryStatus.DELETED), entries.map { it.status })

        val gone = namesOnDisk("sweepb") - namesOnDisk("sweptb")
        assertEquals(gone, plan.files.map { it.path.substringAfterLast('/') }.toSet())
    }

    /**
     * The same deleting commit under the other strategy: had `sweepb` had no branch, its delete's
     * file would have gone too. Read in `IncrementalFileCleanup`, not run — the fixture has the
     * branch — and stated so.
     */
    @Test
    fun `the strategy is what decides whether a removed file is freed`() {
        val m = model("sweepb")
        val input = m.expiryFileInput()
        val ids = m.metadatas.last().metadata.snapshots.sortedBy { it.sequenceNumber }.map { it.snapshotId!! }
        // Drop the branch from the metadata: one ref, so the plan runs incremental over the same lists.
        val single = input.copy(metadata = input.metadata.copy(refs = input.metadata.refs.filterKeys { it == "main" }))
        val plan = single.planExpiryFiles(ids.dropLast(1).toSet())
        assertEquals(ExpiryCleanup.INCREMENTAL, plan.cleanup)
        assertEquals(1, plan.ofKind(ExpiryFileKind.DATA_FILE).size, "the deleted file goes once nothing but expired commits could read it")
        assertEquals(ExpiryFileReason.DELETED_BY_EXPIRED, plan.ofKind(ExpiryFileKind.DATA_FILE).single().reason)
        // m1 and m1' — the second's list carried m1 only as far as snapshot 3, and 5 lists m4, m3, m2.
        assertEquals(2, plan.ofKind(ExpiryFileKind.MANIFEST).size)
    }

    /** Read in `IncrementalFileCleanup`, not run: a snapshot picked into the live line is left entirely alone. */
    @Test
    fun `a cherry-picked snapshot keeps its files and even its manifest list when it expires`() {
        val m = model("wap")
        val meta = m.metadatas.last().metadata
        val staged = meta.snapshots.first { it.wapId != null && it.publishedWapId == null }
        val plan = m.expiryFileInput().planExpiryFiles(setOf(staged.snapshotId!!))
        assertEquals(ExpiryCleanup.INCREMENTAL, plan.cleanup)
        assertEquals(emptyList(), plan.files)
    }

    /**
     * The one wrong this planner must not do is free a file a retained snapshot still reads. Every
     * Iceberg fixture, expiring everything but the current snapshot and then everything but the
     * ref tips: no planned data or delete file is live in any retained snapshot. `promoted` and
     * `maint` carry a `rewrite_manifests`, which is where the ancestor rule earns its keep — the
     * original manifests are unlisted and expiring, and their files are live in the rewrite.
     */
    @Test
    fun `across every fixture, the plan never frees a file a retained snapshot reads`() {
        val fixtures = File(repoRoot, "example/iceberg/default").listFiles()!!.filter { it.isDirectory }.map { it.name }.sorted()
        var checked = 0
        for (fixture in fixtures) {
            val m = model(fixture)
            val meta = m.metadatas.last().metadata
            val live = m.metadatas.flatMap { it.snapshots }.filter { !it.expired }.associateBy { it.metadata.snapshotId }
            val ids = meta.snapshots.mapNotNull { it.snapshotId }.filter { it in live }.toSet()
            val tips = meta.refs.values.mapNotNull { it.snapshotId }.toSet()
            for (retained in listOf(setOfNotNull(meta.currentSnapshotId), tips)) {
                val removed = ids - retained
                if (removed.isEmpty()) continue
                val plan = m.expiryFileInput().planExpiryFiles(removed)
                val stillRead = retained.mapNotNull { live[it] }.flatMap { liveFilesOf(it) }.map { normalizeFilePath(it.path) }.toSet()
                val freed = (plan.ofKind(ExpiryFileKind.DATA_FILE) + plan.ofKind(ExpiryFileKind.DELETE_FILE)).map { normalizeFilePath(it.path) }.toSet()
                assertEquals(emptySet(), freed intersect stillRead, "$fixture retaining $retained under ${plan.cleanup}")
                checked++
            }
        }
        assertTrue(checked >= 30, "checked only $checked plans")
    }

    @Test
    fun `nothing removed plans nothing`() {
        val m = model("sweep")
        val plan = m.expiryFileInput().planExpiryFiles(emptySet())
        assertEquals(ExpiryCleanup.NONE, plan.cleanup)
        assertEquals("nothing", plan.describe)
    }
}
