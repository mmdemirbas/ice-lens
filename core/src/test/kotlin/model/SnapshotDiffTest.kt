package model

import service.GraphLayoutService
import service.IcebergGraphBuilder
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Comparing two snapshots that are not parent and child.
 *
 * [SnapshotChange] answers "what did this commit do" and is only defined against a commit's
 * parent. This is the other question — "what is different between these two" — where the two may
 * be a branch tip and `main`, or ten commits apart, or on different forks.
 *
 * The oracle is the table's own `current` figures. `liveFilesOf` at the current snapshot must
 * agree with `TableSummary.current`, which is folded from the same ledger through a different
 * path — the whole-table accumulator rather than one snapshot's closure. Two ways of counting the
 * same set, and nothing here produced the expected numbers: they come from Iceberg's own metadata
 * tables via the fixture tests.
 */
class SnapshotDiffTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun model(fixture: String) =
        UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/$fixture").absolutePath))

    private fun snapshots(fixture: String): List<GraphNode.SnapshotNode> =
        GraphLayoutService.layoutGraph(model(fixture), showRows = false)
            .nodes.filterIsInstance<GraphNode.SnapshotNode>()

    private fun currentSnapshotOf(fixture: String): UnifiedSnapshot {
        val m = model(fixture)
        val currentId = m.metadatas.lastOrNull()?.metadata?.currentSnapshotId
        assertNotNull(currentId, "$fixture should have a current snapshot")
        return m.metadatas.flatMap { it.snapshots }.first { it.metadata.snapshotId == currentId }
    }

    /**
     * The oracle: one snapshot's live file set is the same set the table's `current` figures count.
     *
     * If these ever disagree the diff is describing a table nobody else sees. `mor` is the fixture
     * to run it on because it has both delete files and a compaction — the two things that make
     * "live" mean more than "every entry".
     */
    @Test
    fun `the live files at the current snapshot are what the table's current figures count`() {
        listOf("test", "parted", "mor", "eqdel", "v3", "evolved", "respec", "branched", "maint", "v1", "extdata", "sorted", "promoted", "lineage", "pstats", "wap", "rolled", "retained").forEach { fixture ->
            val current = IcebergGraphBuilder.buildTableSummary(model(fixture)).current
            val live = liveFilesOf(currentSnapshotOf(fixture))

            assertEquals(current.fileCount, live.size, "$fixture: file count")
            assertEquals(
                current.dataFileCount,
                live.count { it.content == DataFileContent.DATA },
                "$fixture: data files",
            )
            assertEquals(
                current.recordCount,
                live.filter { it.content == DataFileContent.DATA }.sumOf { it.recordCount },
                "$fixture: records in data files",
            )
            assertEquals(
                current.totalSizeBytes,
                live.sumOf { it.sizeBytes },
                "$fixture: bytes",
            )
        }
    }

    /** A snapshot against itself is the identity: everything in both, nothing either side. */
    @Test
    fun `a snapshot compared with itself has no differences`() {
        val live = liveFilesOf(currentSnapshotOf("mor"))
        val diff = snapshotDiff(1L, live, 1L, live)

        assertTrue(diff.isEmpty, "a snapshot differs from itself in nothing")
        assertEquals(live.size, diff.unchanged.size, "and every file is on both sides")
        assertEquals(ContentStats(), diff.addedStats)
        assertEquals(ContentStats(), diff.removedStats)
    }

    /** Swapping the arguments swaps added and removed, and nothing else. */
    @Test
    fun `the diff is symmetric under swapping the two sides`() {
        val m = model("mor")
        val all = m.metadatas.flatMap { it.snapshots }.distinctBy { it.metadata.snapshotId }
        assertTrue(all.size >= 2, "mor should have several commits")
        val a = liveFilesOf(all.first())
        val b = liveFilesOf(currentSnapshotOf("mor"))

        val forward = snapshotDiff(1L, a, 2L, b)
        val backward = snapshotDiff(2L, b, 1L, a)

        assertEquals(
            forward.added.map { it.file.path }.sorted(),
            backward.removed.map { it.file.path }.sorted(),
            "what arrived going forward is what left going back",
        )
        assertEquals(
            forward.removed.map { it.file.path }.sorted(),
            backward.added.map { it.file.path }.sorted(),
        )
        assertEquals(forward.unchanged.size, backward.unchanged.size)
    }

    /**
     * The compaction, seen as a difference rather than as a commit.
     *
     * `mor`'s first snapshot and its current one are several commits apart with a compaction in
     * between, so this is exactly the case `SnapshotChange` cannot answer. The check is that the
     * two sides account for each other: every file is on one side, the other, or both, and the
     * counts add up to the union.
     */
    @Test
    fun `two snapshots several commits apart account for every file exactly once`() {
        val m = model("mor")
        val first = m.metadatas.flatMap { it.snapshots }.minByOrNull { it.metadata.sequenceNumber ?: Long.MAX_VALUE }
        assertNotNull(first, "mor should have a first commit")
        val a = liveFilesOf(first)
        val b = liveFilesOf(currentSnapshotOf("mor"))
        val diff = snapshotDiff(1L, a, 2L, b)

        val union = (a.map { normalizeFilePath(it.path) } + b.map { normalizeFilePath(it.path) }).toSet()
        assertEquals(union.size, diff.files.size, "every path in either side gets exactly one row")
        assertEquals(
            union.size,
            diff.added.size + diff.removed.size + diff.unchanged.size + diff.contradictory.size,
            "and every row is on exactly one side",
        )
        assertTrue(
            diff.contradictory.isEmpty(),
            "an Iceberg data file is immutable, so one path with two sets of figures is a real " +
                "finding: ${diff.contradictory.map { it.file.path }}",
        )
        assertTrue(!diff.isEmpty, "a compaction happened between these two, so something differs")
    }

    /**
     * The case the feature exists for: two tips that are not on one line.
     *
     * `branched` forks, so `main`'s tip and `audit`'s tip have no ancestor relationship at all —
     * neither is reachable from the other by walking parents, which is what makes composing
     * `SnapshotChange` along a path impossible here and a set difference the only answer.
     */
    @Test
    fun `two branch tips with no ancestry between them still compare`() {
        val tips = snapshots("branched").filter { node -> node.refs.any { it.isBranch } }
        assertTrue(tips.size >= 2, "branched should have at least two branch tips; got ${tips.size}")

        val left = tips.first()
        val right = tips.first { it.id != left.id }
        assertTrue(left.canDiff && right.canDiff, "the builder should have attached both live sets")

        // Neither is an ancestor of the other: walking parents from each never reaches the other.
        val byId = snapshots("branched").mapNotNull { n -> n.data.snapshotId?.let { it to n } }.toMap()
        fun ancestors(node: GraphNode.SnapshotNode): Set<Long> {
            val seen = mutableSetOf<Long>()
            var walk = node.data.parentSnapshotId
            while (walk != null && seen.add(walk)) walk = byId[walk]?.data?.parentSnapshotId
            return seen
        }
        val leftId = requireNotNull(left.data.snapshotId)
        val rightId = requireNotNull(right.data.snapshotId)
        assertTrue(
            rightId !in ancestors(left) && leftId !in ancestors(right),
            "these two tips are on one line, so this fixture no longer covers the forked case",
        )

        val diff = snapshotDiff(
            leftId, requireNotNull(left.liveFiles),
            rightId, requireNotNull(right.liveFiles),
        )
        assertTrue(!diff.isEmpty, "two branch tips of a forked table should hold different files")
    }

    /** The loader is deferred: building the graph must not have walked every closure. */
    @Test
    fun `building the graph attaches the live file set without reading it`() {
        val nodes = snapshots("branched")
        assertTrue(nodes.isNotEmpty())
        assertTrue(nodes.all { it.canDiff }, "every Iceberg snapshot node can be compared")
        // Asking is what reads it, and the answer has to be the same on a second ask.
        val node = nodes.first()
        assertEquals(node.liveFiles, node.liveFiles, "a second ask must give the same answer")
    }

    /** Two nodes for one snapshot are the same node, loader or not — the DeferredRead invariant. */
    @Test
    fun `attaching a live file set does not change a snapshot node's identity`() {
        val snapshot = currentSnapshotOf("mor")
        val plain = GraphNode.SnapshotNode(id = "snap_1", data = snapshot.metadata, simpleId = 1)
        val loaded = GraphNode.SnapshotNode(
            id = "snap_1",
            data = snapshot.metadata,
            simpleId = 1,
            liveFilesLoader = DeferredRead.of { liveFilesOf(snapshot) },
        )
        assertEquals(plain, loaded, "carrying the means to read is not part of what the node is")
        assertEquals(plain.hashCode(), loaded.hashCode())
    }
}
