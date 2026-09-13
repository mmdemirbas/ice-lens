package model

import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `docs/fixtures/paimon-pe.sql` wrote a primary-key table with a changelog, a compaction and a
 * tag, copied it on disk, and ran `expire_snapshots(retain_max => 2, retain_min => 1)` on the
 * original in place — so `pe` is `pea` before, complete, with the same file names, and the plan
 * from the copy has to name exactly the files the expiry took out of the original, read off the
 * two directories. `px`/`pxa` is the same shape written twice, so it is checked by count.
 */
class PaimonExpiryFilePlanFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun dir(fixture: String) = File(repoRoot, "example/paimon/db.db/$fixture")
    private fun model(fixture: String) = PaimonUnifiedTableModel(Paths.get(dir(fixture).absolutePath))

    /** Every file under the table by name, minus the two hint files whose contents change and names do not. */
    private fun namesOnDisk(fixture: String): Set<String> =
        dir(fixture).walkTopDown().filter { it.isFile }.map { it.name }.filter { it != "EARLIEST" && it != "LATEST" }.toSet()

    private fun plan(fixture: String, retainMax: Int): Pair<PaimonExpiryPlan, PaimonExpiryFilePlan> {
        val m = model(fixture)
        val now = m.snapshots.maxOf { it.metadata.timeMillis ?: 0L } + 1000
        val expiry = m.expiryInput().planExpiry(PaimonExpiryOptions(nowMs = now, retainMax = retainMax, retainMin = 1))
        return expiry to m.expiryFileInput().planExpiryFiles(expiry.removed.map { it.snapshotId }.toSet())
    }

    @Test
    fun `the plan from the copy names exactly what the expiry took out of the original`() {
        val (expiry, plan) = plan("pe", retainMax = 2)
        assertEquals((1L..6L).toList(), expiry.removed.map { it.snapshotId })
        assertEquals(1L to 7L, plan.beginInclusive to plan.endExclusive)
        val gone = namesOnDisk("pe") - namesOnDisk("pea")
        assertEquals(gone, plan.names)
        assertEquals(setOf(), plan.names - gone)
        assertEquals("2 data files, 5 changelog files, 5 manifests, 15 manifest lists, 6 snapshot files", plan.describe)
    }

    @Test
    fun `the compaction's removals are freed except what the tag before it still holds`() {
        val (_, plan) = plan("pe", retainMax = 2)
        // The COMPACT at 6 removed f1..f5; the tag on 3 holds f1..f3.
        assertEquals(listOf(6L, 6L), plan.ofKind(PaimonExpiryFileKind.DATA_FILE).map { it.snapshotId })
        assertEquals(3, plan.protectedByTag.size)
        assertTrue(plan.protectedByTag.all { it.tag == "keep" && it.tagSnapshotId == 3L && it.removedBy == 6L })
        val m = model("pe")
        val tagHolds = replayPaimonSnapshot(m.tags.single().snapshot).liveEntries.keys
        assertEquals(tagHolds, plan.protectedByTag.map { it.name }.toSet())
        assertTrue(plan.ofKind(PaimonExpiryFileKind.DATA_FILE).none { it.name in tagHolds })
    }

    @Test
    fun `a tag keeps its data lists and manifests and not its changelog list`() {
        val (_, plan) = plan("pe", retainMax = 2)
        val m = model("pe")
        val tagged = m.tags.single().snapshot.metadata
        val lists = plan.ofKind(PaimonExpiryFileKind.MANIFEST_LIST).map { it.name }.toSet()
        assertTrue(tagged.baseManifestList !in lists && tagged.deltaManifestList !in lists, "the tag's data lists stay")
        assertTrue(tagged.changelogManifestList in lists, "the tag's changelog list goes")
        // and every changelog file of 1..5 goes, while the COMPACT at 6 wrote none.
        assertEquals((1L..5L).toList(), plan.ofKind(PaimonExpiryFileKind.CHANGELOG_FILE).map { it.snapshotId })
    }

    /**
     * Read in `expireUntil`, not run: the data pass covers `(begin, end]`, so the first retained
     * snapshot's own removals are freed — a file it removed was live only in the one before,
     * which expires. Keeping 6, 7 and 8 still frees what the compaction at 6 took out.
     */
    @Test
    fun `the first retained snapshot's removals are freed too`() {
        val (expiry, plan) = plan("pe", retainMax = 3)
        assertEquals((1L..5L).toList(), expiry.removed.map { it.snapshotId })
        assertEquals(6L, plan.endExclusive)
        assertEquals(listOf(6L, 6L), plan.ofKind(PaimonExpiryFileKind.DATA_FILE).map { it.snapshotId })
        assertEquals(3, plan.protectedByTag.size)
    }

    /** `px` and `pxa` are two writes of one script, so the names differ and the counts are the oracle. */
    @Test
    fun `on a write-only table the expiry frees lists and manifests and no data file`() {
        val (expiry, plan) = plan("px", retainMax = 2)
        assertEquals(listOf(1L, 2L, 3L), expiry.removed.map { it.snapshotId })
        assertEquals(emptyList(), plan.ofKind(PaimonExpiryFileKind.DATA_FILE), "write-only: nothing was ever removed, so nothing is freed")
        fun count(fixture: String, dir: String) = File(dir(fixture), dir).listFiles()!!.count { it.isFile && !it.name.startsWith(".") }
        assertEquals(count("px", "bucket-0"), count("pxa", "bucket-0"))
        val manifestsGone = plan.ofKind(PaimonExpiryFileKind.MANIFEST).size + plan.ofKind(PaimonExpiryFileKind.MANIFEST_LIST).size
        assertEquals(count("px", "manifest") - manifestsGone, count("pxa", "manifest"))
        assertEquals(count("px", "snapshot") - plan.ofKind(PaimonExpiryFileKind.SNAPSHOT).size, count("pxa", "snapshot"))
    }

    /**
     * The one wrong this planner must not do is free a file the latest snapshot or a tag still
     * reads. Every Paimon fixture, removing everything but the latest snapshot.
     */
    @Test
    fun `across every fixture, the plan never frees a file the latest snapshot or a tag reads`() {
        val fixtures = File(repoRoot, "example/paimon/db.db").listFiles()!!.filter { it.isDirectory }.map { it.name }.sorted()
        var checked = 0
        for (fixture in fixtures) {
            val m = model(fixture)
            if (m.snapshots.size < 2) continue
            val latest = m.snapshots.maxBy { it.metadata.id ?: 0L }
            val removed = m.snapshots.mapNotNull { it.metadata.id }.filter { it != latest.metadata.id }.toSet()
            val plan = m.expiryFileInput().planExpiryFiles(removed)
            val stillRead = (listOf(latest) + m.tags.map { it.snapshot }).flatMap { replayPaimonSnapshot(it).liveEntries.keys }.toSet()
            val freed = plan.ofKind(PaimonExpiryFileKind.DATA_FILE).map { it.name }.toSet()
            assertEquals(emptySet(), freed intersect stillRead, fixture)
            val latestManifests = (latest.baseManifests + latest.deltaManifests).map { paimonManifestKey(it) }.toSet()
            assertEquals(emptySet(), plan.ofKind(PaimonExpiryFileKind.MANIFEST).map { it.name }.toSet() intersect latestManifests, "$fixture: a manifest the latest snapshot lists")
            checked++
        }
        assertTrue(checked >= 15, "checked only $checked tables")
    }
}
