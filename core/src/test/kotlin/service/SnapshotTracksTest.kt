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
        val fixtures = model.FixtureCatalog.iceberg
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

    /** A column is one line: nothing drawn between a commit and its parent may sit in it. */
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

    /**
     * Three branches open at once, which is where the column assignment's rules start to bite.
     *
     * `branched` has a single fork, and with one fork every rule here is satisfied by "put the
     * second line somewhere else". `branched3` forks three times at three different points and
     * commits to other lines in between, so a column reserved for a branch has to survive commits
     * that are not on it — the case the reservation exists for. The shape is in
     * `docs/fixtures/branched3.sql`; the tips are found by their refs rather than by position, so
     * this says nothing about where the layout put them.
     */
    @Test
    fun `three branches each get a column of their own`() {
        val graph = graphOf("branched3")
        val snapshots = graph.snapshots()
        assertEquals(9, snapshots.size, "the fixture is nine commits")

        val tracks = snapshotTracks(snapshots)
        assertEquals(
            4, tracks.values.distinct().size,
            "main and three branches, so four columns: $tracks",
        )

        fun tipOf(branch: String) = snapshots.single { node -> node.refs.any { it.name == branch } }
        val tipTracks = listOf("main", "audit", "wip", "staging").associateWith { tracks[tipOf(it).id] }
        assertEquals(
            4, tipTracks.values.distinct().size,
            "no two branch tips may share a column: $tipTracks",
        )
        assertEquals(0, tipTracks["main"], "the first line keeps the column it always had")
    }

    /**
     * A column reserved for a branch is still that branch's when the walk finally reaches it.
     *
     * `audit` forks at the second commit and its first commit is the fourth; two other branches
     * fork in between. If the reservation were dropped and the column simply taken by whoever
     * asked next, `audit` would land in a column `wip` or `staging` had already claimed — which
     * looks like a valid drawing and is a lie about the history.
     */
    @Test
    fun `a column reserved at a fork is still free when its branch commits`() {
        val graph = graphOf("branched3")
        val snapshots = graph.snapshots()
        val tracks = snapshotTracks(snapshots)
        val bySequence = snapshots.sortedBy { it.data.sequenceNumber ?: 0L }

        val auditTip = snapshots.single { node -> node.refs.any { it.name == "audit" } }
        val auditFirst = bySequence.single { it.data.snapshotId == auditTip.data.parentSnapshotId }
        assertEquals(
            tracks[auditFirst.id], tracks[auditTip.id],
            "audit's two commits are one line and belong in one column",
        )

        val others = listOf("wip", "staging").map { branch ->
            tracks[snapshots.single { node -> node.refs.any { it.name == branch } }.id]
        }
        assertTrue(
            tracks[auditFirst.id] !in others,
            "audit's column was reserved before wip and staging forked, so neither may hold it",
        )
    }

    /**
     * Every column is named, and the tag on main's tip names nothing.
     *
     * With one fork there were two columns and the tag case was a single coincidence. Here four
     * columns are named at once, which is the layout the header row was written for, and `release`
     * sits on the same commit as `main` — so a rule that took the tip's refs without filtering to
     * branches would print two names over one column.
     */
    @Test
    fun `four columns are each named by their branch, and a tag names none`() {
        val graph = graphOf("branched3")
        val snapshots = graph.snapshots()
        val columns = snapshotColumns(snapshots) { id -> graph.layoutPositions.getValue(id) }

        assertEquals(4, columns.size, "four lines, four columns")
        assertEquals(
            setOf("main", "audit", "wip", "staging"),
            columns.flatMap { column -> column.labels.map { it.name } }.toSet(),
        )
        assertTrue(
            columns.none { column -> column.labels.any { it.name == "release" } },
            "a tag never names a column, even sitting on the tip beside the branch that does",
        )
        assertEquals(
            columns.size, columns.map { it.x }.distinct().size,
            "and each column is at its own x",
        )
    }

    /**
     * A branch cut from another branch keeps the older branch's line where it was.
     *
     * `nested` (`docs/fixtures/nested.scala`) cuts `b2` from `b1`'s first commit and commits to
     * `b2` before `b1` commits again. On write time alone `b2`'s commit is the first child of the
     * fork, takes the column `b1` was drawn in, and `b1`'s own line moves sideways — the fork
     * commit, which is `b1`'s, ends up under a column labelled `b2`. That is the `branched3`
     * defect again one level down, and the trunk rule cannot reach it: neither child is on
     * `main`. What decides it is the metadata log — `b1` is listed by v4 and `b2` by v6 — so the
     * older branch keeps its column through the fork and every commit sits under the name of a
     * branch that reaches it.
     */
    @Test
    fun `a branch forked from a branch opens a column and leaves the older line where it was`() {
        val graph = graphOf("nested")
        val snapshots = graph.snapshots()
        assertEquals(7, snapshots.size, "the fixture is seven commits")
        val byId = snapshots.associateBy { it.data.snapshotId }
        fun tipOf(branch: String) = snapshots.single { node -> node.refs.any { it.isBranch && it.name == branch } }
        assertEquals(4, tipOf("b1").refs.single().createdInVersion, "b1 is first listed by the version CREATE BRANCH b1 wrote")
        assertEquals(6, tipOf("b2").refs.single().createdInVersion)

        val tracks = snapshotTracks(snapshots)
        assertEquals(3, tracks.values.distinct().size, "main and two branches: $tracks")
        val fork = byId.getValue(tipOf("b1").data.parentSnapshotId)
        assertEquals(tracks[tipOf("b1").id]!!, tracks[fork.id]!!, "b1's first commit is in b1's column, not b2's")
        assertTrue(tracks[tipOf("b2").id] != tracks[fork.id], "b2 opened a column of its own at the fork")
        assertEquals(0, tracks[tipOf("main").id])
        assertTrue(byId.getValue(tipOf("b2").data.parentSnapshotId).data.timestampMs!! < tipOf("b1").data.timestampMs!!, "the fixture's trap: b2 committed first")

        // And the columns are named by the branch whose line they are, all the way up.
        val columns = snapshotColumns(snapshots) { id -> graph.layoutPositions.getValue(id) }
        val named = columns.associate { column -> column.labels.single().name to column.x }
        assertEquals(setOf("main", "b1", "b2"), named.keys)
        assertEquals(named.getValue("b1"), graph.layoutPositions.getValue(fork.id).x.toDouble(), "the fork commit is drawn under b1")
    }
}
