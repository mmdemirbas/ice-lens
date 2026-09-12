package service

import model.GraphNode
import model.ManifestEntryStatus
import model.UnifiedTableModel
import model.findUnreferencedFiles
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * A table whose snapshots have been expired, which is what every production table looks like and
 * what no other fixture does.
 *
 * `expire_snapshots` deletes the manifest lists of the snapshots it drops and rewrites
 * `metadata.json` without them — but the older metadata versions stay on disk
 * (`write.metadata.previous-versions-max`, a hundred by default) and still list them. Opening the
 * table read every version, found three snapshots whose manifest list was gone, and drew three
 * `SNAPSHOT READ ERROR` nodes for a table that is entirely healthy. An expired snapshot is a
 * state the format defines, not a failure to read, and the rule that tells the two apart needs
 * both halves: the manifest list is missing **and** the current metadata no longer lists the
 * snapshot. A listed snapshot with no manifest list is a broken table and stays an error, which
 * `a snapshot the current metadata lists is a read error when its manifest list is missing` pins by
 * deleting the surviving list from a copy.
 *
 * The expected figures come from `docs/fixtures/expired.sql` and the summaries Spark wrote:
 * three one-row inserts, a copy-on-write delete of the first row that dropped its file outright,
 * then `expire_snapshots(retain_last => 1)`.
 */
class ExpiredSnapshotsFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val tableDir = File(repoRoot, "example/iceberg/default/expired")

    private fun model(dir: Path = Paths.get(tableDir.absolutePath)) = UnifiedTableModel(dir)

    private val expiredIds = setOf(4903423488017157533L, 3021540075634696424L, 8270666647477094113L)
    private val retainedId = 109929048088262785L

    /** Every snapshot of the table, deduplicated — each is re-listed by every later metadata version. */
    private fun snapshotsOf(model: UnifiedTableModel) =
        model.metadatas.flatMap { it.snapshots }.distinctBy { it.metadata.snapshotId }

    @Test
    fun `the six metadata versions read, and only the last one still lists every snapshot as gone`() {
        val model = model()
        assertEquals(emptyList(), model.readErrors)
        assertEquals((1..6).map { "v$it.metadata.json" }, model.metadatas.map { it.path.name })
        assertEquals(
            listOf(0, 1, 2, 3, 4, 1), model.metadatas.map { it.snapshots.size },
            "v2..v5 list the snapshots they had; v6 lists only the one expiry retained",
        )
    }

    @Test
    fun `an expired snapshot is a state, not a read error`() {
        val snapshots = snapshotsOf(model())
        assertEquals(expiredIds + retainedId, snapshots.map { it.metadata.snapshotId }.toSet())
        snapshots.forEach { snapshot ->
            assertEquals(emptyList(), snapshot.readErrors, "snapshot ${snapshot.metadata.snapshotId}")
            assertEquals(
                snapshot.metadata.snapshotId in expiredIds, snapshot.expired,
                "snapshot ${snapshot.metadata.snapshotId}",
            )
        }
        val (expired, retained) = snapshots.partition { it.expired }
        assertEquals(3, expired.size)
        expired.forEach { assertTrue(it.manifests.isEmpty(), "nothing under an expired snapshot can be read") }
        assertEquals(3, retained.single().manifests.size, "the retained commit carries every manifest still on disk")
    }

    /**
     * Expiry deletes the manifest lists and whatever data no retained snapshot names — here the
     * first row's file — while a manifest carried forward into the retained snapshot survives,
     * along with the entry it holds for the deleted file. The manifest is what still says the
     * file existed; the walk is what says it no longer does.
     */
    @Test
    fun `expiry removed the manifest lists and the unreachable data file, and nothing else`() {
        val onDisk = tableDir.walk().filter { it.isFile && !it.name.startsWith(".") }
            .map { it.name }.toSet()
        assertEquals(1, onDisk.count { it.startsWith("snap-") }, "one manifest list left: the retained snapshot's")
        assertEquals(3, onDisk.count { it.endsWith("-m0.avro") }, "three manifests: rows 2 and 3, and the delete")
        assertEquals(2, onDisk.count { it.endsWith(".parquet") }, "two data files: rows 2 and 3")

        val retained = snapshotsOf(model()).single { !it.expired }
        val files = retained.manifests.flatMap { it.dataFiles }
        assertEquals(3, files.size, "the delete commit's manifest still lists the file it removed")
        val (present, missing) = files.partition { Files.exists(it.path) }
        assertEquals(2, present.size)
        assertTrue(present.all { it.path.name in onDisk })
        assertEquals(
            listOf(ManifestEntryStatus.DELETED), missing.map { it.metadata.status },
            "the one file expiry removed is the one the delete commit's manifest marks DELETED",
        )
    }

    @Test
    fun `the graph draws four snapshots and no error node, three of them expired with nothing to walk`() {
        val graph = GraphLayoutService.layoutGraph(model(), showRows = false)
        assertEquals(emptyList(), graph.nodes.filterIsInstance<GraphNode.ErrorNode>().map { it.message })

        val snapshots = graph.nodes.filterIsInstance<GraphNode.SnapshotNode>()
        assertEquals(4, snapshots.size)
        snapshots.forEach { node ->
            val expected = node.data.snapshotId in expiredIds
            assertEquals(expected, node.expired, "snap ${node.data.snapshotId}")
            // The summary is all that is left of an expired commit: no manifests to read a change
            // from, and no closure to compare or to pair deletes across.
            assertEquals(!expected, node.change != null, "change of snap ${node.data.snapshotId}")
            assertEquals(!expected, node.canDiff, "canDiff of snap ${node.data.snapshotId}")
            assertEquals(!expected, node.deleteReach != null, "deleteReach of snap ${node.data.snapshotId}")
            assertTrue(node.data.summary?.get("operation") != null, "the writer's summary is still there")
        }
        val retained = snapshots.single { !it.expired }
        assertEquals("delete", retained.change?.operation)
        assertEquals(retainedId, retained.data.snapshotId)
    }

    /** The current figures are the retained snapshot's own summary; history counts the file the delete removed. */
    @Test
    fun `current stats match the retained snapshot's summary`() {
        val summary = IcebergGraphBuilder.buildTableSummary(model())
        assertEquals(retainedId, summary.currentSnapshotId)
        assertEquals(6, summary.currentMetadataVersion)
        assertEquals(2, summary.current.dataFileCount, "total-data-files = 2 in the summary Spark wrote")
        assertEquals(2L, summary.current.recordCount, "total-records = 2")
        assertEquals(3, summary.current.dataManifestCount)
        assertEquals(1, summary.current.deletedEntryCount)
        assertEquals(3, summary.history.dataFileCount, "every file that ever existed, the deleted one included")
        assertEquals(3L, summary.history.recordCount)
    }

    /** Expiry is not an orphan: everything left on disk is named by some metadata version. */
    @Test
    fun `nothing on disk is unreferenced after expiry`() {
        val report = findUnreferencedFiles(model())
        assertEquals(emptyList(), report.problems)
        assertEquals(emptyList(), report.unreferenced)
        assertEquals(13, report.filesOnDisk)
    }

    /**
     * The other half of the rule. Remove the retained snapshot's manifest list from a copy, and
     * the table is broken: v6 lists a snapshot that cannot be read. That is a read error, and
     * reading it as "expired" would make a corrupt table look like a tidy one.
     */
    @Test
    fun `a snapshot the current metadata lists is a read error when its manifest list is missing`(@TempDir tmp: Path) {
        val copy = tmp.resolve("expired")
        tableDir.copyRecursively(copy.toFile())
        val list = Files.list(copy.resolve("metadata")).use { s -> s.filter { it.name.startsWith("snap-") }.findFirst().get() }
        Files.delete(list)

        val retained = snapshotsOf(model(copy)).single { it.metadata.snapshotId == retainedId }
        assertFalse(retained.expired, "listed by the current metadata, so missing means broken")
        assertEquals(1, retained.readErrors.size, "${retained.readErrors}")
        assertTrue(retained.readErrors.single().stage.contains("manifest-list"), retained.readErrors.single().stage)

        val graph = GraphLayoutService.layoutGraph(model(copy), showRows = false)
        val errors = graph.nodes.filterIsInstance<GraphNode.ErrorNode>()
        assertEquals(1, errors.size, "the broken snapshot is the one error; the expired ones stay expired")
        assertEquals(3, graph.nodes.filterIsInstance<GraphNode.SnapshotNode>().count { it.expired })
    }
}
