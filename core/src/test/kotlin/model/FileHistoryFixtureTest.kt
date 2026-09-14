package model

import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A file's history is a third reading of the walks the suite already trusts. On Iceberg,
 * "live at this snapshot" has to agree with [liveFilesOf] and "added / removed here" with
 * [snapshotChangeOf], on every retained snapshot of every checked-in table; on Paimon, "live"
 * has to agree with [replayPaimonSnapshot], and an event has to explain every transition the
 * replay shows between one snapshot and the next. The named cases below are the ones a reader
 * opens the panel for: the compaction that took a file out, the file a tag alone still holds.
 */
class FileHistoryFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun icebergFixtures() = File(repoRoot, "example/iceberg/default").listFiles()!!.filter { it.isDirectory }.map { it.name }.sorted()
    private fun paimonFixtures() = File(repoRoot, "example/paimon/db.db").listFiles()!!.filter { it.isDirectory }.map { it.name }.sorted()
    private fun iceberg(fixture: String) = UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/$fixture").absolutePath))
    private fun paimon(fixture: String) = PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/$fixture").absolutePath))

    private fun retainedIceberg(m: UnifiedTableModel) =
        m.metadatas.asReversed().flatMap { it.snapshots }.filter { !it.expired && it.metadata.snapshotId != null }.distinctBy { it.metadata.snapshotId }

    @Test
    fun `on every Iceberg fixture, live agrees with the live-file walk and the events with the commit's change`() {
        var pairs = 0
        var events = 0
        for (fixture in icebergFixtures()) {
            val m = iceberg(fixture)
            val retained = retainedIceberg(m)
            val liveByWalk = retained.associate { s -> s.metadata.snapshotId!! to liveFilesOf(s).map { it.key }.toSet() }
            val changes = retained.associate { s -> s.metadata.snapshotId!! to snapshotChangeOf(s) }
            val keys = retained.flatMap { s -> s.manifests.flatMap { m -> m.dataFiles.map { it.ledgerFileKey() } } }.toSet()
            for (key in keys) {
                val history = m.fileHistoryOf(key)
                assertEquals(retained.size, history.retainedSnapshotCount, fixture)
                for (s in retained) {
                    val id = s.metadata.snapshotId!!
                    val entry = history.snapshots.firstOrNull { it.snapshotId == id }
                    assertEquals(key in liveByWalk.getValue(id), entry?.live ?: false, "$fixture $key live at $id")
                    val change = changes.getValue(id)
                    val added = change.added.any { it.key == key }
                    val removed = change.removed.any { it.key == key }
                    assertEquals(added, entry?.event == FileEvent.ADDED || entry?.event == FileEvent.REWRITTEN, "$fixture $key added at $id")
                    assertEquals(removed, entry?.event == FileEvent.REMOVED || entry?.event == FileEvent.REWRITTEN, "$fixture $key removed at $id")
                    if (entry?.event != null) events++
                    pairs++
                }
                assertEquals(history.snapshots.map { it.snapshotId }, history.snapshots.map { it.snapshotId }.distinct(), "$fixture $key: one entry per snapshot")
            }
        }
        assertTrue(pairs >= 500, "checked only $pairs pairs")
        assertTrue(events >= 100, "saw only $events events")
    }

    @Test
    fun `a compaction's inputs are removed by the replace and its output added by it`() {
        val m = iceberg("mor")
        val retained = retainedIceberg(m)
        val replace = retained.first { it.metadata.summary["operation"] == "replace" }
        val replaceId = replace.metadata.snapshotId!!
        val change = snapshotChangeOf(replace)
        assertTrue(change.removed.size >= 2 && change.added.size >= 1, "mor's replace should take files out and put one in")
        for (removed in change.removed) {
            val history = m.fileHistoryOf(removed.key)
            val by = assertNotNull(history.removedBy, removed.path)
            assertEquals(replaceId, by.snapshotId)
            assertEquals("replace", by.operation)
            assertTrue(!history.liveNow)
            assertTrue(history.liveIn.isNotEmpty() && history.liveIn.all { it.snapshotId != replaceId })
            assertTrue(history.describe.startsWith("removed by snapshot $replaceId (replace) — still listed live by"), history.describe)
        }
        for (added in change.added) {
            val history = m.fileHistoryOf(added.key)
            assertEquals(replaceId, assertNotNull(history.addedBy).snapshotId)
            assertTrue(history.liveNow)
            assertEquals("live now — added by snapshot $replaceId (replace)", history.describe)
        }
    }

    /**
     * The manifest outlives the commit that wrote it, and its `added_snapshot_id` says which
     * commit that was — so a file added by a snapshot the expiry removed is still credited, from
     * the summary the older metadata version keeps, and the credited commit is never live.
     */
    @Test
    fun `a file whose adding commit has expired is credited to it, from a manifest still carried`() {
        val m = iceberg("expired")
        val retained = retainedIceberg(m)
        val retainedIds = retained.map { it.metadata.snapshotId }.toSet()
        val current = m.metadatas.last().metadata.currentSnapshotId
        val carried = liveFilesOf(retained.first { it.metadata.snapshotId == current })
            .map { it.key }
            .map { m.fileHistoryOf(it) }
            .filter { h -> h.snapshots.any { it.expired } }
        assertTrue(carried.isNotEmpty(), "expired should carry a file added by a snapshot that is gone")
        carried.forEach { h ->
            assertTrue(h.liveNow)
            val added = assertNotNull(h.addedBy)
            assertTrue(added.expired && !added.live && added.snapshotId !in retainedIds)
            assertEquals("append", added.operation, "the older metadata version still keeps the expired commit's summary")
            assertEquals("live now — added by snapshot ${added.snapshotId} (append), since expired", h.describe)
            assertEquals(h.snapshots.size - 1, h.retainedListing.size)
            assertEquals(h.snapshots.first(), added, "the expired commit sorts first, by its sequence number")
        }
    }

    private fun namesOnDisk(dir: File): Set<String> = dir.walkTopDown().filter { it.isFile }.map { it.name }.toSet()

    /**
     * The history is what the panel's expiry line is read against, so the two have to agree
     * with the expiries Iceberg and Paimon actually ran: a data file the expiry deleted was not
     * live in the current snapshot, and a file live now is one no expiry deleted.
     */
    @Test
    fun `a file the expiry freed was not live now, and a file live now was not freed`() {
        val sweep = iceberg("sweep")
        val gone = namesOnDisk(File(repoRoot, "example/iceberg/default/sweep/data")) - namesOnDisk(File(repoRoot, "example/iceberg/default/swept/data"))
        assertTrue(gone.isNotEmpty(), "the Iceberg expiry should have deleted data files")
        val keys = retainedIceberg(sweep).flatMap { s -> s.manifests.flatMap { m -> m.dataFiles.map { it.ledgerFileKey() } } }.toSet()
        var freed = 0
        keys.forEach { key ->
            val h = sweep.fileHistoryOf(key)
            if (key.substringAfterLast('/') in gone) { assertTrue(!h.liveNow, key); freed++ } else if (h.liveNow) assertTrue(key.substringAfterLast('/') !in gone, key)
        }
        assertEquals(gone.size, freed)

        val pe = paimon("pe")
        val goneP = namesOnDisk(File(repoRoot, "example/paimon/db.db/pe/bucket-0")) - namesOnDisk(File(repoRoot, "example/paimon/db.db/pea/bucket-0"))
        val keysP = pe.snapshots.flatMap { s -> (s.baseManifests + s.deltaManifests).flatMap { mf -> mf.entries.map(::paimonDataFileKey) } }.toSet()
        // The changelog files it deleted have no history — they are the change stream, not the table's contents.
        val goneData = goneP.filter { it in keysP }
        assertTrue(goneData.isNotEmpty(), "the Paimon expiry should have deleted data files")
        goneData.forEach { name -> assertTrue(!pe.fileHistoryOf(name).liveNow, name) }
        keysP.filter { pe.fileHistoryOf(it).liveNow }.forEach { assertTrue(it !in goneP, it) }
    }

    private data class Line(val fixture: String, val branch: String?, val snapshots: List<PaimonUnifiedSnapshot>, val tagOnly: List<PaimonUnifiedSnapshot>)

    private fun lines(): List<Pair<PaimonUnifiedTableModel, Line>> = paimonFixtures().flatMap { fixture ->
        val m = paimon(fixture)
        // A long-lived changelog whose lists the expiry left is retained like a tag-only snapshot; one with a retired list holds nothing about data files.
        listOf(m to Line(fixture, null, m.snapshots, m.tagOnlySnapshots + m.changelogs.filter { it.retiredLists.isEmpty() })) +
            m.branches.map { b -> m to Line(fixture, b.name, b.snapshots, b.tagOnlySnapshots + b.changelogs.filter { it.retiredLists.isEmpty() }) }
    }

    @Test
    fun `on every Paimon fixture and branch, live agrees with the replay and an event explains every transition`() {
        var pairs = 0
        var transitions = 0
        for ((m, line) in lines()) {
            val retained = (line.snapshots + line.tagOnly).filter { it.metadata.id != null }.distinctBy { it.metadata.id }.sortedBy { it.metadata.id }
            val liveByReplay = retained.associate { s -> s.metadata.id!! to replayPaimonSnapshot(s).liveEntries.keys }
            val keys = retained.flatMap { s -> (s.baseManifests + s.deltaManifests).flatMap { mf -> mf.entries.map(::paimonDataFileKey) } }.toSet()
            val label = "${line.fixture}/${line.branch ?: "main"}"
            for (key in keys) {
                val history = m.fileHistoryOf(key, line.branch)
                assertEquals(retained.size, history.retainedSnapshotCount, label)
                // A transition is only a claim between adjacent commits: the earliest retained
                // snapshot's base carries what expired commits added, and a tag-only snapshot
                // stands apart from the next retained one with the expired commits between.
                var before: Pair<Long, Boolean>? = null
                for (s in retained) {
                    val id = s.metadata.id!!
                    val entry = history.snapshots.firstOrNull { it.snapshotId == id }
                    val live = key in liveByReplay.getValue(id)
                    assertEquals(live, entry?.live ?: false, "$label $key live at $id")
                    val adjacent = before?.takeIf { it.first == id - 1 }?.second
                    if (live && adjacent == false) { assertTrue(entry?.event == FileEvent.ADDED || entry?.event == FileEvent.REWRITTEN, "$label $key became live at $id without an add"); transitions++ }
                    if (!live && adjacent == true) { assertEquals(FileEvent.REMOVED, entry?.event, "$label $key stopped being live at $id without a removal"); transitions++ }
                    before = id to live
                    pairs++
                }
            }
        }
        assertTrue(pairs >= 300, "checked only $pairs pairs")
        assertTrue(transitions >= 30, "saw only $transitions transitions")
    }

    @Test
    fun `a level upgrade is a removal and an add in one compaction, and the file stays live`() {
        val m = paimon("dv")
        val rewritten = m.snapshots.flatMap { s -> s.deltaManifests.flatMap { mf -> mf.entries.map(::paimonDataFileKey) } }.toSet()
            .map { m.fileHistoryOf(it) }
            .filter { h -> h.snapshots.any { it.event == FileEvent.REWRITTEN } }
        assertTrue(rewritten.isNotEmpty(), "dv's compaction should re-add a file at another level")
        rewritten.forEach { h ->
            val at = h.snapshots.first { it.event == FileEvent.REWRITTEN }
            assertEquals("COMPACT", at.operation)
            assertTrue(at.live)
        }
    }

    @Test
    fun `a file only a tag still holds is live in the tag's snapshot and in no other`() {
        val m = paimon("tg")
        val tagOnly = m.tagOnlySnapshots.single()
        val tagged = replayPaimonSnapshot(tagOnly).liveEntries.keys
        val current = replayPaimonSnapshot(m.snapshots.last()).liveEntries.keys
        val onlyTagged = tagged - current
        assertTrue(onlyTagged.isNotEmpty(), "tg's tag should hold a file the current snapshot does not")
        onlyTagged.forEach { key ->
            val h = m.fileHistoryOf(key)
            assertEquals(listOf(tagOnly.metadata.id), h.liveIn.map { it.snapshotId })
            assertTrue(!h.liveNow)
            assertEquals(m.snapshots.size + 1, h.retainedSnapshotCount)
        }
    }

    @Test
    fun `a branch's file has a history on the branch and none on main`() {
        val m = paimon("br")
        val branch = m.branches.first { it.snapshots.isNotEmpty() }
        val onBranch = branch.snapshots.flatMap { s -> s.deltaManifests.flatMap { mf -> mf.entries.filter { it.metadata.kind != PaimonEntryKind.DELETE }.map(::paimonDataFileKey) } }.toSet()
        val onMain = m.snapshots.flatMap { s -> (s.baseManifests + s.deltaManifests).flatMap { mf -> mf.entries.map(::paimonDataFileKey) } }.toSet()
        val branchOnly = onBranch - onMain
        assertTrue(branchOnly.isNotEmpty(), "br's branch should have written a file main never lists")
        branchOnly.forEach { key ->
            assertTrue(m.fileHistoryOf(key, branch.name).liveNow, "$key on ${branch.name}")
            assertNull(m.fileHistoryOf(key).addedBy)
            assertEquals("no retained snapshot lists it", m.fileHistoryOf(key).describe)
        }
    }
}
