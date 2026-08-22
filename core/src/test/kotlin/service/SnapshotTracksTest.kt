package service

import model.GraphModel
import model.GraphNode
import model.UnifiedTableModel
import java.io.File
import java.nio.file.Paths
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A branch gets its own column, and a table without branches gets exactly what it had.
 *
 * The second half is the one worth guarding: every table in the wild has one branch, so a track
 * assignment that quietly moved those graphs would be a change to every user's screen in exchange
 * for nothing. The pass returns before touching a node when the highest track is 0.
 */
class SnapshotTracksTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun graphOf(name: String): GraphModel {
        val tableDir = File(repoRoot, "example/iceberg/default/$name")
        assertTrue(tableDir.isDirectory, "fixture missing at $tableDir")
        return GraphLayoutService.layoutGraph(
            UnifiedTableModel(Paths.get(tableDir.absolutePath)), showRows = false,
        )
    }

    private fun GraphModel.snapshots() = nodes.filterIsInstance<GraphNode.SnapshotNode>()

    @Test
    fun `a history without branches draws in one column`() {
        // mor has six commits including a compaction, so it is a long linear chain rather than
        // the one-commit case where any assignment happens to look right.
        val graph = graphOf("mor")
        val snapshots = graph.snapshots()
        assertTrue(snapshots.size > 3, "expected a long chain, got ${snapshots.size} commits")

        assertEquals(
            setOf(0), snapshotTracks(snapshots).values.toSet(),
            "every commit on a single chain belongs in the same column",
        )
        assertEquals(
            1, snapshots.map { it.x }.distinct().size,
            "and the layout should leave that column exactly where ELK put it",
        )
    }

    @Test
    fun `a fork draws its branch in a column of its own`() {
        val graph = graphOf("branched")
        val snapshots = graph.snapshots()
        val tracks = snapshotTracks(snapshots)
        val audit = snapshots.single { node -> node.refs.any { it.name == "audit" } }

        assertEquals(
            2, tracks.values.distinct().size,
            "one fork open at a time is two columns: $tracks",
        )
        assertEquals(0, tracks.getValue(audit.id) - 1, "the branch that forked off takes column 1")
        assertEquals(
            setOf(0), snapshots.filter { it.id != audit.id }.map { tracks.getValue(it.id) }.toSet(),
            "main keeps the column it started in",
        )

        // 210 of card plus the 60 that separates two halves of one fork.
        val pitch = snapshots.maxOf { it.width } + 60.0
        val mainX = snapshots.first { it.id != audit.id }.x
        assertEquals(
            pitch, audit.x - mainX, 0.5,
            "the branch column sits one pitch to the right of main's",
        )
    }

    @Test
    fun `the layers right of the snapshots move over by what the branch took`() {
        val graph = graphOf("branched")
        val snapshots = graph.snapshots()
        val manifests = graph.nodes.filterIsInstance<GraphNode.ManifestNode>()
        assertTrue(manifests.isNotEmpty(), "the fixture should have manifests to push over")

        val widestSnapshotEdge = snapshots.maxOf { it.x + it.width }
        assertTrue(
            manifests.minOf { it.x } > widestSnapshotEdge,
            "the branch column would sit on top of the manifests: snapshots reach " +
                "$widestSnapshotEdge, the first manifest starts at ${manifests.minOf { it.x }}",
        )

        // And the layers to the LEFT stayed put — a shift applied to everything would move the
        // table and metadata as well, which looks identical on screen and is not.
        val metadata = graph.nodes.filterIsInstance<GraphNode.MetadataNode>()
        assertTrue(
            metadata.all { it.x + it.width < snapshots.minOf { snapshot -> snapshot.x } },
            "metadata should still sit left of the snapshot layer",
        )
    }

    /**
     * The assignment rule itself, over every checked-in table.
     *
     * A commit keeps its parent's column exactly when it is the first of that parent's children
     * to be drawn, and two children of one parent never share a column. Swap which child is
     * "first", drop the reservation, or free a column too early and one of these fails — which is
     * what makes them worth asserting rather than the column numbers themselves.
     */
    @Test
    fun `a commit keeps its parent's column only when it is the first child drawn`() {
        val fixtures = listOf("test", "parted", "mor", "eqdel", "v3", "evolved", "respec", "branched")
        var forks = 0
        fixtures.forEach { name ->
            val snapshots = graphOf(name).snapshots()
            val tracks = snapshotTracks(snapshots)
            if (tracks.isEmpty()) return@forEach
            val children = lineageChildren(snapshots)

            children.forEach { (parentCommit, kids) ->
                val parent = snapshots.single { it.data.snapshotId == parentCommit }
                if (kids.size > 1) forks++
                assertEquals(
                    kids.size, kids.map { tracks.getValue(it.id) }.distinct().size,
                    "$name: two children of ${parent.simpleId} share a column",
                )
                kids.forEachIndexed { index, kid ->
                    val sameColumn = tracks.getValue(kid.id) == tracks.getValue(parent.id)
                    assertEquals(
                        index == 0, sameColumn,
                        "$name: child $index of commit ${parent.simpleId} " +
                            (if (sameColumn) "took" else "left") + " its parent's column",
                    )
                }
            }
        }
        assertTrue(forks > 0, "no fixture forked, so the rule was never exercised")
    }

    /** A column may only be reused once nothing drawn later is still waiting for it. */
    @Test
    fun `a column is never held by two branches at once`() {
        val snapshots = graphOf("branched").snapshots()
        val tracks = snapshotTracks(snapshots)
        val order = GraphLayoutService.snapshotLineageOrder(snapshots)
        val byId = snapshots.associateBy { it.data.snapshotId }

        snapshots.forEach { node ->
            val parent = byId[node.data.parentSnapshotId] ?: return@forEach
            if (tracks.getValue(node.id) != tracks.getValue(parent.id)) return@forEach
            // Same column as its parent: nothing drawn between them may be in it, or the column
            // was handed out while this branch still had a claim on it.
            val from = order.getValue(parent.id)
            val to = order.getValue(node.id)
            val between = snapshots.filter { order.getValue(it.id) in (from + 1) until to }
            assertTrue(
                between.none { tracks.getValue(it.id) == tracks.getValue(node.id) },
                "commit ${node.simpleId} shares its column with something drawn between it and " +
                    "its parent",
            )
        }
    }

    private fun assertEquals(expected: Double, actual: Double, tolerance: Double, message: String) {
        assertTrue(abs(expected - actual) <= tolerance, "$message (expected $expected, was $actual)")
    }
}
