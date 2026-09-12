package service

import model.DataFileContent
import model.ManifestContent
import model.ManifestEntryStatus
import model.UnifiedSnapshot
import model.UnifiedTableModel
import model.deleteReach
import model.snapshotChangeOf
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two maintenance procedures that rewrite what earlier commits wrote, on a real table.
 *
 * `mor` shows a compaction leaving two delete files dangling; nothing had ever seen
 * `rewrite_position_delete_files`, whose job is to drop them, nor `rewrite_manifests`, the one
 * commit whose summary counts *manifests* rather than files. Both are `replace` snapshots, and
 * both are the shape that tests the attribution rule `SnapshotChange` rests on from a new side:
 * the delete rewrite writes manifests whose entries are `DELETED` for files it never added, and
 * the manifest rewrite writes a manifest whose every entry is `EXISTING`.
 *
 * The expected figures are the summaries Spark wrote and the procedure outputs it printed
 * (`3 1 4166 1389` for the delete rewrite, `4 2` for the manifest rewrite), read off
 * `docs/fixtures/maint.sql` before the assertions were written.
 */
class MaintenanceFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val model = UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/maint").absolutePath))

    /** Every snapshot of the table, in commit order. */
    private val snapshots: List<UnifiedSnapshot> =
        model.metadatas.flatMap { it.snapshots }.distinctBy { it.metadata.snapshotId }.sortedBy { it.metadata.sequenceNumber }

    private fun snapshot(sequence: Long) = snapshots.single { it.metadata.sequenceNumber == sequence }

    @Test
    fun `ten commits, no read errors, and the operations the script ran`() {
        assertEquals(emptyList(), model.readErrors)
        assertEquals(emptyList(), snapshots.flatMap { it.readErrors } + snapshots.flatMap { it.manifests }.flatMap { it.readErrors })
        assertEquals(
            listOf("append", "append", "delete", "delete", "replace", "delete", "replace", "append", "delete", "replace"),
            snapshots.map { it.metadata.summary["operation"] },
        )
    }

    /**
     * The compaction leaves the two delete files pointing at data files it removed, the commit
     * after it adds a live one, and the delete rewrite is what takes the dangling pair out.
     *
     * This is the procedure's own definition of its job — "removes dangling deletes" — used as
     * the oracle for `deleteReach`: before it runs the pairing must find exactly two proved
     * dangling, and after it runs none.
     */
    @Test
    fun `the delete rewrite removes the two deletes the compaction left dangling`() {
        val afterCompaction = deleteReach(snapshot(5))
        assertEquals(listOf(true, true), afterCompaction.map { it.isDangling }.sorted())

        val beforeRewrite = deleteReach(snapshot(6))
        assertEquals(3, beforeRewrite.size)
        assertEquals(2, beforeRewrite.count { it.isDangling }, "the two from before the compaction")
        assertEquals(1, beforeRewrite.count { it.reaches.size == 1 }, "the one written against the compacted file")

        val afterRewrite = deleteReach(snapshot(7))
        assertEquals(1, afterRewrite.size, "one delete file left")
        assertEquals(emptyList(), afterRewrite.filter { it.isDangling }, "nothing dangling after the rewrite")
        assertEquals(1L, afterRewrite.single().recordCount, "the one live position, rewritten")
        assertEquals(setOf(6L), afterRewrite.map { it.sequenceNumber }.toSet(), "see the next test")
    }

    /**
     * The rewritten delete file keeps the sequence number of the delete it replaces.
     *
     * A delete applies to data files at or below its own number, so a rewrite that gave the new
     * file the rewrite commit's number would make it apply to data files committed *between* the
     * original delete and the rewrite — rows the delete never meant. Iceberg records the number
     * explicitly on the entry, which is the one case where an entry in a commit's own manifest
     * does not inherit the manifest's; `effectiveSequenceNumber` reads it as written.
     */
    @Test
    fun `the rewritten delete file carries its original sequence number, not the rewrite's`() {
        val rewrite = snapshot(7)
        val written = rewrite.manifests.filter { it.metadata.addedSnapshotId == rewrite.metadata.snapshotId }
        assertEquals(4, written.size, "one manifest per delete file touched: three removed, one added")
        assertTrue(written.all { it.metadata.content == ManifestContent.DELETES })
        assertTrue(written.all { it.metadata.sequenceNumber == 7L })

        val added = written.flatMap { it.dataFiles }.single { it.metadata.status == ManifestEntryStatus.ADDED }
        assertEquals(DataFileContent.POSITION_DELETES, added.metadata.dataFile?.content)
        assertEquals(6L, added.metadata.sequenceNumber, "recorded on the entry, not inherited from the manifest")
    }

    /** Both rows figures the summary records about delete files now have a count beside them. */
    @Test
    fun `the delete rewrite's position counts agree with its manifests`() {
        val change = snapshotChangeOf(snapshot(7))
        val byLabel = change.tallies.associateBy { it.label }
        assertEquals(1L to 1L, byLabel.getValue("Delete files added").let { it.recorded to it.counted })
        assertEquals(3L to 3L, byLabel.getValue("Delete files removed").let { it.recorded to it.counted })
        assertEquals(1L to 1L, byLabel.getValue("Position deletes added").let { it.recorded to it.counted })
        assertEquals(3L to 3L, byLabel.getValue("Position deletes removed").let { it.recorded to it.counted })
        assertEquals(null, byLabel.getValue("Equality deletes added").recorded, "Spark writes none")
        assertEquals(emptyList(), change.disagreements)
    }

    /**
     * `rewrite_manifests` found two data manifests and two delete manifests and rewrote each pair
     * into one, and its summary says so in manifests: created 2, kept 0, replaced 4. The count
     * beside each is the attribution rule — `added_snapshot_id` is this commit, or it is not —
     * which every other tally only uses. The two figures are deliberately different (the script
     * says how that took three runs), so a count that read the rule backwards cannot pass.
     *
     * It also settles what happens to a manifest holding only `DELETED` entries: the delete
     * rewrite wrote three of them, and the very next commit's list no longer carries them, so by
     * the manifest rewrite there are four manifests to replace and not seven.
     */
    @Test
    fun `the manifest rewrite's created and kept counts agree with added_snapshot_id`() {
        val rewrite = snapshot(10)
        val change = snapshotChangeOf(rewrite)
        val byLabel = change.tallies.associateBy { it.label }
        assertEquals(2L to 2L, byLabel.getValue("Manifests written").let { it.recorded to it.counted })
        assertEquals(0L to 0L, byLabel.getValue("Manifests kept").let { it.recorded to it.counted })
        assertEquals(0, change.unattributedManifests)
        assertEquals(emptyList(), change.disagreements)

        // What it wrote is one manifest per kind, every entry EXISTING, so the commit changed no file.
        val written = rewrite.manifests.filter { it.metadata.addedSnapshotId == rewrite.metadata.snapshotId }
        assertEquals(2, written.size)
        assertTrue(written.all { it.path.fileName.toString().startsWith("optimized-m-") }, "${written.map { it.path }}")
        assertEquals(setOf(ManifestContent.DATA, ManifestContent.DELETES), written.map { it.metadata.content }.toSet())
        assertEquals(setOf(ManifestEntryStatus.EXISTING), written.flatMap { it.dataFiles }.map { it.metadata.status }.toSet())
        assertEquals(4, written.sumOf { it.dataFiles.size }, "two data files and two delete files, all carried")
        assertEquals(emptyList(), change.files)
        assertEquals(2, rewrite.manifests.size, "nothing was kept")

        // The commit before it listed four: the DELETED-only manifests from snapshot 7 are gone.
        val before = snapshot(9)
        assertEquals(4, before.manifests.size)
        assertTrue(before.manifests.none { m -> m.dataFiles.all { it.metadata.status == ManifestEntryStatus.DELETED } })
    }

    /** Ordinary commits record no manifest figures, and the pair stays out of their tallies. */
    @Test
    fun `commits that are not a manifest rewrite record no manifest counts`() {
        snapshots.filter { it.metadata.sequenceNumber != 10L }.forEach { snapshot ->
            val byLabel = snapshotChangeOf(snapshot).tallies.associateBy { it.label }
            assertEquals(null, byLabel.getValue("Manifests written").recorded, "seq ${snapshot.metadata.sequenceNumber}")
            assertEquals(null, byLabel.getValue("Manifests kept").recorded, "seq ${snapshot.metadata.sequenceNumber}")
        }
        // The compaction wrote three manifests and carried two: figures that are still folded from
        // the same list, with nothing recorded to check them against.
        val compaction = snapshotChangeOf(snapshot(5))
        assertEquals(3, compaction.manifestsWritten)
        assertEquals(2, compaction.manifestsCarried)
    }

    /** The end state: two data files of eight rows, two delete files of one position each. */
    @Test
    fun `current stats match the final summary`() {
        val summary = IcebergGraphBuilder.buildTableSummary(model)
        assertEquals(2, summary.current.dataFileCount, "total-data-files = 2")
        assertEquals(8L, summary.current.recordCount, "total-records = 8")
        assertEquals(2, summary.current.posDeleteFileCount, "total-delete-files = 2")
        assertEquals(2L, summary.current.deleteRecordCount, "total-position-deletes = 2")
        assertEquals(1, summary.current.dataManifestCount)
        assertEquals(1, summary.current.deleteManifestCount)
    }
}
