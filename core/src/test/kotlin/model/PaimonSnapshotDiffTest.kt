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

    private fun model(name: String): PaimonUnifiedTableModel {
        val dir = File(repoRoot, "example/paimon/db.db/$name")
        assertTrue(dir.isDirectory, "the Paimon fixture should be checked in at $dir")
        return PaimonUnifiedTableModel(Paths.get(dir.absolutePath))
    }

    /**
     * Every checked-in Paimon table. `test` is one Flink commit; `dv` is six Spark commits whose
     * compactions remove files the base still lists, and `cl` has an overwrite that removes them
     * all — the cases where "replay" and "sum the ADDs" give different answers, so the oracles
     * below mean something only on those two.
     */
    /** One model at a time: every table decoded at once is what the worker's heap is sized against. */
    private fun models(): Sequence<PaimonUnifiedTableModel> = FixtureCatalog.paimon.asSequence().map { model(it) }

    private fun snapshotNodes(model: PaimonUnifiedTableModel): List<GraphNode.PaimonSnapshotNode> =
        GraphLayoutService.layoutGraph(model, showRows = false)
            .nodes.filterIsInstance<GraphNode.PaimonSnapshotNode>()

    /** One walk, two readings: the file set and the figures folded from it must agree. */
    @Test
    fun `the live files at the latest snapshot are what the current figures count`() {
        models().forEach { m ->
            val current = PaimonGraphBuilder.buildTableSummary(m).current
            val live = paimonLiveFilesOf(m.snapshots.lastOrNull())

            assertEquals(current.dataFileCount, live.size, "data file count")
            assertEquals(current.recordCount, live.sumOf { it.recordCount }, "records")
            assertEquals(current.dataSizeBytes, live.sumOf { it.sizeBytes }, "bytes")
        }
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
        models().forEach { m ->
            (m.snapshots + m.branches.flatMap { it.snapshots }).forEach { snapshot ->
                val replay = replayPaimonSnapshot(snapshot)
                val folded = StatsDerivation(replay.contributions).total

                assertEquals(
                    folded.dataFileCount,
                    replay.liveFiles.size,
                    "at snapshot ${snapshot.metadata.id}: the contributions add up to the number of files the walk ended holding",
                )
                assertEquals(
                    folded.recordCount,
                    replay.liveFiles.values.sumOf { it?.rowCount ?: 0L },
                    "at snapshot ${snapshot.metadata.id}: and to the records in them",
                )
            }
        }
    }

    /** Paimon has no delete files by definition — a removal is an entry kind, not a file. */
    @Test
    fun `every live Paimon file is a data file`() {
        models().forEach { m ->
            val live = paimonLiveFilesOf(m.snapshots.lastOrNull())
            assertTrue(live.isNotEmpty(), "the fixture should hold files")
            assertTrue(
                live.all { it.content == DataFileContent.DATA },
                "Paimon records no positional or equality deletes, so no live file can be one",
            )
        }
    }

    /** The builder attaches the walk without performing it, same as the Iceberg side. */
    @Test
    fun `every Paimon snapshot node can be compared`() {
        models().forEach { m ->
            val nodes = snapshotNodes(m)
            assertTrue(nodes.isNotEmpty(), "the fixture should draw snapshots")
            assertTrue(nodes.all { it.canDiff }, "every Paimon snapshot should carry its live file set")
            assertEquals(nodes.first().liveFiles, nodes.first().liveFiles, "a second ask gives the same answer")
        }
    }

    /**
     * Paimon's live files go through the shared `snapshotDiff`, against an empty other side.
     *
     * An empty other side is a real comparison, not a synthetic one — it is what a table's first
     * commit is compared against — and the Flink table has exactly that one commit. The pair is
     * covered on the Spark table below.
     */
    @Test
    fun `Paimon live files go through the shared difference`() {
        val m = model("test")
        val only = snapshotNodes(m).single()
        val live = requireNotNull(only.liveFiles)
        assertTrue(live.isNotEmpty(), "the fixture should hold files")

        val diff = snapshotDiff(null, emptyList(), only.commitId, live)
        assertEquals(live.size, diff.added.size, "against nothing, every file is an addition")
        assertTrue(diff.removed.isEmpty() && diff.unchanged.isEmpty() && diff.contradictory.isEmpty())

        val current = PaimonGraphBuilder.buildTableSummary(m).current
        assertEquals(current.dataFileCount, diff.addedStats.dataFileCount, "data files")
        assertEquals(current.recordCount, diff.addedStats.recordCount, "records")
        assertEquals(current.dataSizeBytes, diff.addedStats.dataSizeBytes, "bytes")
        assertEquals(current.dataFileCount, diff.netDataFileCount, "and the net is the whole of it")
    }

    /**
     * Two Paimon snapshots compared as sets, on a table with six commits behind it.
     *
     * Through the graph nodes rather than the model, because that is the panel's route. Each
     * adjacent pair of `dv` is one outcome: an upgrade compaction leaves the set exactly as it
     * was — the file left and came back inside one delta, and a set comparison cannot and should
     * not see that — an append adds one file, and the vector's commit removes one. The pair
     * across the whole history is the property the design note states: **the diff is not a replay
     * of the commits between**, so the three-row file that arrived at commit 5 and left at commit
     * 6 is in neither set and appears nowhere.
     */
    @Test
    fun `two Paimon snapshots are compared as sets, and a file that came and went between them is in neither`() {
        val byId = snapshotNodes(model("dv")).associateBy { it.commitId }
        assertEquals(6, byId.size, "the fixture should draw six commits")
        fun diff(from: Long, to: Long): SnapshotDiff {
            val a = byId.getValue(from)
            val b = byId.getValue(to)
            return snapshotDiff(a.commitId, requireNotNull(a.liveFiles), b.commitId, requireNotNull(b.liveFiles))
        }
        fun shape(d: SnapshotDiff) = listOf(d.added.size, d.removed.size, d.unchanged.size, d.contradictory.size)

        // 1 → 2, upgrade compaction: same one file, nothing else.
        assertEquals(listOf(0, 0, 1, 0), shape(diff(1, 2)))
        assertTrue(diff(1, 2).isEmpty)

        // 2 → 3, append: the 500-row file arrives.
        assertEquals(listOf(1, 0, 1, 0), shape(diff(2, 3)))
        assertEquals(500L, diff(2, 3).addedStats.recordCount)
        assertEquals(1, diff(2, 3).netDataFileCount)

        // 5 → 6, the vector's commit: the 3-row delete file goes, both marked files stay.
        assertEquals(listOf(0, 1, 2, 0), shape(diff(5, 6)))
        assertEquals(3L, diff(5, 6).removedStats.recordCount)
        assertEquals(-1, diff(5, 6).netDataFileCount)

        // 1 → 6, across everything: one file added, none removed — the 3-row file is invisible.
        val whole = diff(1, 6)
        assertEquals(listOf(1, 0, 1, 0), shape(whole))
        assertEquals(500L, whole.addedStats.recordCount)
        assertTrue(whole.removed.isEmpty(), "a file that came and went between the two is in neither set")

        // And the direction is the comparison's, not the table's.
        val reversed = diff(6, 1)
        assertEquals(listOf(0, 1, 1, 0), shape(reversed))
        assertEquals(500L, reversed.removedStats.recordCount)
    }

    /** A snapshot compared with itself differs in nothing, whichever format it is. */
    @Test
    fun `a Paimon snapshot compared with itself has no differences`() {
        models().forEach { m ->
            val live = paimonLiveFilesOf(m.snapshots.lastOrNull())
            val diff = snapshotDiff(1L, live, 1L, live)
            assertTrue(diff.isEmpty)
            assertEquals(live.size, diff.unchanged.size)
        }
    }

    /**
     * Paimon records no parent on a snapshot, and the node says so rather than inferring one.
     *
     * `id - 1` is the convention and nothing states it; a rolled-back table breaks the arithmetic.
     * The comparison does not need lineage, so reporting none is the honest answer.
     */
    @Test
    fun `a Paimon snapshot reports no parent rather than guessing one`() {
        val node = snapshotNodes(model("dv")).lastOrNull()
        assertNotNull(node, "the fixture should draw a snapshot")
        assertEquals(null, node.parentCommitId)
        assertNotNull(node.commitId, "but it does have its own id")
    }
}
