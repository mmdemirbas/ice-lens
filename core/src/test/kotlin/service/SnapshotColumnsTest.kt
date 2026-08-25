package service

import model.GraphNode
import model.Point
import model.Snapshot
import model.SnapshotRefLabel
import model.UnifiedTableModel
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which branch names each column of snapshots.
 *
 * The rule is that the bottom-most commit names the column and only if a ref points at it. Every
 * test here is about a case where a looser rule — "any ref in the column" — would give a different
 * and wrong answer.
 */
class SnapshotColumnsTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun node(id: String, refs: List<SnapshotRefLabel> = emptyList()) =
        GraphNode.SnapshotNode(id = id, data = Snapshot(snapshotId = id.hashCode().toLong()), simpleId = 1, refs = refs)

    private fun at(vararg placed: Pair<String, Point>): (String) -> Point {
        val map = placed.toMap()
        return { id -> map.getValue(id) }
    }

    /** The case the feature exists for, on the fixture that has it. */
    @Test
    fun `the branched fixture names its two columns after their tips`() {
        val dir = File(repoRoot, "example/iceberg/default/branched")
        assertTrue(dir.isDirectory, "fixture missing at $dir")
        val graph = GraphLayoutService.layoutGraph(
            UnifiedTableModel(Paths.get(dir.absolutePath)), showRows = false,
        )
        val snapshots = graph.nodes.filterIsInstance<GraphNode.SnapshotNode>()
        val columns = snapshotColumns(snapshots) { graph.layoutPosition(it) }

        assertEquals(2, columns.size, "the fork should draw two columns")
        val named = columns.flatMap { column -> column.labels.map { it.name } }
        assertTrue("main" in named, "one column is main; got $named")
        assertTrue("audit" in named, "the other is the branch that forked; got $named")

        // The tags sit partway up a column and must not have named it.
        assertTrue("v1" !in named && "release" !in named, "a tag marks a commit, not a line: $named")
    }

    /**
     * A tag three commits back does not name the column.
     *
     * This is the case that separates "the tip's refs" from "any ref in the column", and the two
     * only disagree here — which is why the fixture test above is not enough on its own.
     */
    @Test
    fun `a ref partway up a column does not name it`() {
        val tagged = node("s1", listOf(SnapshotRefLabel("release", isBranch = false)))
        val tip = node("s2", listOf(SnapshotRefLabel("main", isBranch = true)))
        val other = node("s3", listOf(SnapshotRefLabel("audit", isBranch = true)))

        val columns = snapshotColumns(
            listOf(tagged, tip, other),
            at("s1" to Point(0f, 0f), "s2" to Point(0f, 200f), "s3" to Point(300f, 100f)),
        )

        assertEquals(2, columns.size)
        assertEquals(listOf("main"), columns[0].labels.map { it.name }, "the tip names the column")
        assertEquals(listOf("audit"), columns[1].labels.map { it.name })
        assertEquals(0.0, columns[0].topY, "and the label goes above the topmost commit in it")
    }

    /**
     * A tag on the tip does not name the column either.
     *
     * The other half of the rule, and the half a reading of "the tip's refs" would miss. `prod`
     * pointing at the same commit `main` does is today's coincidence, not a statement about the
     * line — and drawn over the column it prints a second name for something that has one. The
     * `branched` fixture has exactly this shape, which is how it was found: the first render put
     * `main  prod (tag)` over the left column.
     */
    @Test
    fun `a tag sharing the tip with a branch does not name the column`() {
        val tip = node(
            "s2",
            listOf(SnapshotRefLabel("main", isBranch = true), SnapshotRefLabel("prod", isBranch = false)),
        )
        val columns = snapshotColumns(
            listOf(node("s1"), tip, node("s3", listOf(SnapshotRefLabel("audit", isBranch = true)))),
            at("s1" to Point(0f, 0f), "s2" to Point(0f, 200f), "s3" to Point(300f, 100f)),
        )
        assertEquals(listOf("main"), columns[0].labels.map { it.name }, "the branch names it, alone")
    }

    /** A column no branch points into is left unnamed rather than named after something else. */
    @Test
    fun `a column whose tip carries no ref is unnamed`() {
        val tagged = node("s1", listOf(SnapshotRefLabel("release", isBranch = false)))
        val untaggedTip = node("s2")
        val other = node("s3", listOf(SnapshotRefLabel("main", isBranch = true)))

        val columns = snapshotColumns(
            listOf(tagged, untaggedTip, other),
            at("s1" to Point(0f, 0f), "s2" to Point(0f, 200f), "s3" to Point(300f, 0f)),
        )

        assertTrue(columns[0].labels.isEmpty(), "no ref is on the tip, so nothing names this column")
        assertEquals(listOf("main"), columns[1].labels.map { it.name })
    }

    /**
     * A linear history gets no labels. It is one line; a name floating over the only column says
     * nothing the graph did not already say, and `spreadSnapshotBranches` stands down for the same
     * reason.
     */
    @Test
    fun `one column is not labelled`() {
        val columns = snapshotColumns(
            listOf(node("s1", listOf(SnapshotRefLabel("main", isBranch = true))), node("s2")),
            at("s1" to Point(0f, 0f), "s2" to Point(0f, 200f)),
        )
        assertTrue(columns.isEmpty())
    }

    /** A drag leaves a fraction of a dp between two nodes of one column; they are still one. */
    @Test
    fun `a nudged node stays in its column`() {
        val columns = snapshotColumns(
            listOf(node("s1"), node("s2", listOf(SnapshotRefLabel("main", isBranch = true))), node("s3")),
            at("s1" to Point(0.4f, 0f), "s2" to Point(0f, 200f), "s3" to Point(300f, 0f)),
        )
        assertEquals(2, columns.size, "0.4dp of drag is not a third column")
        assertEquals(listOf("main"), columns[0].labels.map { it.name })
    }
}
