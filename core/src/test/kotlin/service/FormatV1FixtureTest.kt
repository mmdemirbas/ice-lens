package service

import model.DataFileContent
import model.GraphNode
import model.ManifestEntryStatus
import model.UnifiedSnapshot
import model.UnifiedTableModel
import model.deleteReach
import model.effectiveMinSequenceNumber
import model.effectiveSequenceNumber
import model.snapshotChangeOf
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A format-version 1 table, and the same table after its upgrade to 2.
 *
 * Every other fixture is v2 or v3. v1 is what a table migrated from Hive years ago still is, and
 * it is the shape in which every sequence number is *absent*: no `sequence-number` on the
 * snapshot, no `sequence_number` or `min_sequence_number` or `content` on the manifest, no
 * column for one in the manifest. The reader's answer to all of that was written from the spec —
 * "default to 0" — and had never met bytes an engine wrote. The upgrade is the half worth having:
 * setting `format-version = 2` rewrites nothing, so the v1 manifests are carried into v2 metadata
 * as they are, and a merge-on-read delete written after the upgrade has to reach a data file whose
 * manifest records no number at all. It does, because 0 is below 2 — the answer a scan gives and
 * the answer "unknown" cannot.
 *
 * The expected figures come from `docs/fixtures/v1.sql` and the summaries Spark wrote.
 */
class FormatV1FixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val model = UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/v1").absolutePath))

    /** Every snapshot in commit order — by timestamp, since the v1 ones carry no sequence number. */
    private val snapshots: List<UnifiedSnapshot> =
        model.metadatas.flatMap { it.snapshots }.distinctBy { it.metadata.snapshotId }.sortedBy { it.metadata.timestampMs }

    private val underV1 = snapshots.take(3)
    private val underV2 = snapshots.drop(3)

    @Test
    fun `four metadata versions are v1 and three are v2, and all of it reads`() {
        assertEquals(emptyList(), model.readErrors)
        assertEquals(listOf(1, 1, 1, 1, 2, 2, 2), model.metadatas.map { it.metadata.formatVersion })
        assertEquals(emptyList(), snapshots.flatMap { it.readErrors } + snapshots.flatMap { it.manifests }.flatMap { it.readErrors })
        assertEquals(
            listOf("append", "append", "overwrite", "append", "delete"),
            snapshots.map { it.metadata.summary["operation"] },
            "v1 cannot write a delete file, so its DELETE is a copy-on-write overwrite",
        )
    }

    /** What v1 leaves out, exactly, so that "read as 0" below is known to be the reader's and not the writer's. */
    @Test
    fun `a v1 snapshot and its manifests record no sequence numbers and no content`() {
        underV1.forEach { snapshot ->
            assertNull(snapshot.metadata.sequenceNumber, "snapshot ${snapshot.metadata.snapshotId}")
            snapshot.manifests.forEach { manifest ->
                assertNull(manifest.metadata.sequenceNumber, "${manifest.path.fileName}")
                assertNull(manifest.metadata.minSequenceNumber, "${manifest.path.fileName}")
                assertNull(manifest.metadata.content, "${manifest.path.fileName}")
                manifest.dataFiles.forEach { file ->
                    assertNull(file.metadata.sequenceNumber, "${file.path.fileName}")
                    assertNull(file.metadata.dataFile?.content, "${file.path.fileName}")
                }
            }
        }
        // And the upgrade rewrote none of them: the v2 snapshots still carry them, unchanged.
        val carried = underV2.last().manifests.filter { it.metadata.sequenceNumber == null }
        assertEquals(2, carried.size, "the two v1 data manifests still live after the upgrade")
        assertTrue(carried.all { it.metadata.content == null })
    }

    /** The spec's rule, applied at every level a number is read from. */
    @Test
    fun `everything v1 recorded no number for is read as 0`() {
        underV1.forEach { snapshot ->
            assertEquals(0L, snapshot.metadata.effectiveSequenceNumber)
            snapshot.manifests.forEach { manifest ->
                assertEquals(0L, manifest.metadata.effectiveSequenceNumber)
                assertEquals(0L, manifest.metadata.effectiveMinSequenceNumber)
                manifest.dataFiles.forEach { file ->
                    assertEquals(0L, effectiveSequenceNumber(file.metadata, manifest.metadata.sequenceNumber), "${file.path.fileName}")
                }
            }
        }
        // The v2 commits count from 1, and their own entries inherit as before.
        assertEquals(listOf(1L, 2L), underV2.map { it.metadata.sequenceNumber })
        underV2.forEach { snapshot ->
            val own = snapshot.manifests.filter { it.metadata.addedSnapshotId == snapshot.metadata.snapshotId }
            own.forEach { manifest ->
                assertEquals(snapshot.metadata.sequenceNumber, manifest.metadata.sequenceNumber)
                manifest.dataFiles.forEach { file ->
                    assertEquals(snapshot.metadata.sequenceNumber, effectiveSequenceNumber(file.metadata, manifest.metadata.sequenceNumber))
                }
            }
        }
    }

    /**
     * The delete written after the upgrade reaches a file written before it, by the sequence rule
     * and not by skipping it: 2 is at or above 0. The rule used to be skipped whenever a number was
     * missing, which gave the right answer here for the wrong reason and could give no answer at
     * all for an equality delete.
     */
    @Test
    fun `a delete after the upgrade reaches a v1-written data file at sequence 0`() {
        val reach = deleteReach(underV2.last()).single()
        assertEquals(2L, reach.sequenceNumber)
        assertEquals(1, reach.reaches.size)
        assertEquals(emptyList(), reach.mayReach)
        val target = reach.reaches.single()
        val targetEntry = underV2.last().manifests.flatMap { m -> m.dataFiles.map { m to it } }
            .single { (_, f) -> f.metadata.dataFile?.filePath == target }
        assertNull(targetEntry.first.metadata.sequenceNumber, "the file it reaches sits in a v1 manifest")
        assertEquals(0L, effectiveSequenceNumber(targetEntry.second.metadata, null))
    }

    /** The graph says the same and says why the number is 0. */
    @Test
    fun `a file node under a v1 manifest reports 0 and that it was defaulted`() {
        val graph = GraphLayoutService.layoutGraph(model, showRows = false)
        val files = graph.nodes.filterIsInstance<GraphNode.FileNode>().filter { it.data.content != DataFileContent.POSITION_DELETES }
        val (defaulted, numbered) = files.partition { it.sequenceDefaulted }
        assertTrue(defaulted.isNotEmpty(), "the v1-written files")
        assertTrue(defaulted.all { it.sequenceNumber == 0L && !it.sequenceInherited })
        assertTrue(numbered.all { it.sequenceNumber >= 1L && it.sequenceInherited }, "the v2-written file inherits its manifest's")

        val snapshotNodes = graph.nodes.filterIsInstance<GraphNode.SnapshotNode>()
        assertEquals(listOf(0L, 0L, 0L, 1L, 2L), snapshotNodes.sortedBy { it.data.timestampMs }.map { it.commitOrder })
    }

    /** The commit-summary oracle holds across the version boundary; the v1 overwrite is a removal and an addition. */
    @Test
    fun `every commit's tallies agree, v1 and v2 alike`() {
        snapshots.forEach { snapshot ->
            val change = snapshotChangeOf(snapshot)
            assertEquals(0, change.unattributedManifests, "v1 manifest lists still record added_snapshot_id")
            assertEquals(emptyList(), change.disagreements, "snapshot ${snapshot.metadata.snapshotId}")
        }
        val overwrite = snapshotChangeOf(underV1.last())
        assertEquals(1, overwrite.removed.size)
        assertEquals(1, overwrite.added.size)
        assertEquals(3L, overwrite.removed.single().recordCount)
        assertEquals(2L, overwrite.added.single().recordCount, "the same file without its deleted row")
        val entries = underV1.last().manifests.filter { it.metadata.addedSnapshotId == underV1.last().metadata.snapshotId }
            .flatMap { it.dataFiles }.map { it.metadata.status }
        assertEquals(setOf(ManifestEntryStatus.ADDED, ManifestEntryStatus.DELETED), entries.toSet())
    }

    /** The end state, from the final summary: three data files of eight rows and one position delete. */
    @Test
    fun `current stats match the final summary`() {
        val summary = IcebergGraphBuilder.buildTableSummary(model)
        assertEquals(2, summary.formatVersion, "the current metadata is v2")
        assertEquals(3, summary.current.dataFileCount, "total-data-files = 3")
        assertEquals(8L, summary.current.recordCount, "total-records = 8")
        assertEquals(1, summary.current.posDeleteFileCount, "total-delete-files = 1")
        assertEquals(1L, summary.current.deleteRecordCount, "total-position-deletes = 1")
        assertEquals(3, summary.current.dataManifestCount, "two v1 manifests carried, one v2")
        assertEquals(1, summary.current.deleteManifestCount)
        assertEquals(4, summary.history.dataFileCount, "the file the overwrite rewrote is history")
    }
}
