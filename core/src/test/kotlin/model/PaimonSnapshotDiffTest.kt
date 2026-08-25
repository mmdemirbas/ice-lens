package model

import service.GraphLayoutService
import service.PaimonGraphBuilder
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Paimon half of the comparison, and the oracle that keeps its replay honest.
 *
 * Paimon reaches "what does this snapshot hold" by a completely different route from Iceberg. A
 * snapshot's *delta* manifest list applies over its *base*: a `_KIND=1` entry removes a file the
 * base still lists, so an entry's meaning depends on the entries before it and the shared
 * per-entry ledger cannot express it. `replayPaimonSnapshot` is that replay, and it emits both the
 * figures and the file set from one walk — which is the property this pins, because two walks
 * would be two implementations that agree today.
 *
 * The oracle is `PaimonGraphBuilder.buildTableSummary(...).current`, folded from the same replay's
 * contributions. If the file set and the figures ever disagree, one of the two readings is
 * describing a table nobody else sees.
 */
class PaimonSnapshotDiffTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun model(): PaimonUnifiedTableModel {
        val dir = File(repoRoot, "example/paimon/db.db/test")
        assertTrue(dir.isDirectory, "the Paimon fixture should be checked in at $dir")
        return PaimonUnifiedTableModel(Paths.get(dir.absolutePath))
    }

    private fun snapshotNodes(): List<GraphNode.PaimonSnapshotNode> =
        GraphLayoutService.layoutGraph(model(), showRows = false)
            .nodes.filterIsInstance<GraphNode.PaimonSnapshotNode>()

    /** One walk, two readings: the file set and the figures folded from it must agree. */
    @Test
    fun `the live files at the latest snapshot are what the current figures count`() {
        val m = model()
        val current = PaimonGraphBuilder.buildTableSummary(m).current
        val live = paimonLiveFilesOf(m.snapshots.lastOrNull())

        assertEquals(current.dataFileCount, live.size, "data file count")
        assertEquals(current.recordCount, live.sumOf { it.recordCount }, "records")
        assertEquals(current.dataSizeBytes, live.sumOf { it.sizeBytes }, "bytes")
    }

    /**
     * The replay is one walk, and both halves come out of it.
     *
     * Asserted rather than trusted to the source, because the failure this prevents is somebody
     * later giving `paimonLiveFilesOf` its own loop "for clarity": the two would agree on this
     * fixture and drift on the first table where a delta removes a file the base carried.
     */
    @Test
    fun `the replay's contributions and its file set describe the same walk`() {
        val replay = replayPaimonSnapshot(model().snapshots.lastOrNull())
        val folded = StatsDerivation(replay.contributions).total

        assertEquals(
            folded.dataFileCount,
            replay.liveFiles.size,
            "the contributions add up to the number of files the walk ended holding",
        )
        assertEquals(
            folded.recordCount,
            replay.liveFiles.values.sumOf { it?.rowCount ?: 0L },
            "and to the records in them",
        )
    }

    /** Paimon has no delete files by definition — a removal is an entry kind, not a file. */
    @Test
    fun `every live Paimon file is a data file`() {
        val live = paimonLiveFilesOf(model().snapshots.lastOrNull())
        assertTrue(live.isNotEmpty(), "the fixture should hold files")
        assertTrue(
            live.all { it.content == DataFileContent.DATA },
            "Paimon records no positional or equality deletes, so no live file can be one",
        )
    }

    /** The builder attaches the walk without performing it, same as the Iceberg side. */
    @Test
    fun `every Paimon snapshot node can be compared`() {
        val nodes = snapshotNodes()
        assertTrue(nodes.isNotEmpty(), "the fixture should draw snapshots")
        assertTrue(nodes.all { it.canDiff }, "every Paimon snapshot should carry its live file set")
        assertEquals(nodes.first().liveFiles, nodes.first().liveFiles, "a second ask gives the same answer")
    }

    /**
     * Paimon's live files go through the shared `snapshotDiff`, against an empty other side.
     *
     * **The two-snapshot case cannot be exercised here: the checked-in Paimon table has exactly
     * one commit.** Writing a second one needs Flink in docker, which is the same gap that leaves
     * the Paimon card heights unmeasured — see TODO. So this covers the seam with real Paimon
     * data rather than pretending to cover the pair: every file is on one side, the figures match
     * what the table's own summary counts, and the shared code path is genuinely run rather than
     * compiled and left. An empty other side is a real comparison, not a synthetic one — it is
     * what a table's first commit is compared against.
     */
    @Test
    fun `Paimon live files go through the shared difference`() {
        val nodes = snapshotNodes()
        assertEquals(1, nodes.size, "if the fixture gained a commit, cover the real pair here instead")
        val only = nodes.single()
        val live = requireNotNull(only.liveFiles)
        assertTrue(live.isNotEmpty(), "the fixture should hold files")

        val diff = snapshotDiff(null, emptyList(), only.commitId, live)
        assertEquals(live.size, diff.added.size, "against nothing, every file is an addition")
        assertTrue(diff.removed.isEmpty() && diff.unchanged.isEmpty() && diff.contradictory.isEmpty())

        val current = PaimonGraphBuilder.buildTableSummary(model()).current
        assertEquals(current.dataFileCount, diff.addedStats.dataFileCount, "data files")
        assertEquals(current.recordCount, diff.addedStats.recordCount, "records")
        assertEquals(current.dataSizeBytes, diff.addedStats.dataSizeBytes, "bytes")
        assertEquals(current.dataFileCount, diff.netDataFileCount, "and the net is the whole of it")
    }

    /** A snapshot compared with itself differs in nothing, whichever format it is. */
    @Test
    fun `a Paimon snapshot compared with itself has no differences`() {
        val live = paimonLiveFilesOf(model().snapshots.lastOrNull())
        val diff = snapshotDiff(1L, live, 1L, live)
        assertTrue(diff.isEmpty)
        assertEquals(live.size, diff.unchanged.size)
    }

    /**
     * Paimon records no parent on a snapshot, and the node says so rather than inferring one.
     *
     * `id - 1` is the convention and nothing states it; a rolled-back table breaks the arithmetic.
     * The comparison does not need lineage, so reporting none is the honest answer.
     */
    @Test
    fun `a Paimon snapshot reports no parent rather than guessing one`() {
        val node = snapshotNodes().lastOrNull()
        assertNotNull(node, "the fixture should draw a snapshot")
        assertEquals(null, node.parentCommitId)
        assertNotNull(node.commitId, "but it does have its own id")
    }
}
