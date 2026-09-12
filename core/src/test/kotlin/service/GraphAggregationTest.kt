package service

import model.AggregationKind
import model.GraphEdge
import model.GraphNode
import model.ManifestEntry
import model.ManifestListEntry
import model.UnifiedTableModel
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Aggregation: the graph draws a page of siblings and folds the rest into a node that says how
 * many there are and opens.
 *
 * The property every test here is ultimately about is that **nothing disappears silently**. A
 * cap that stops drawing at ten files makes a manifest with 5,000 look exactly like one with 10,
 * and no amount of correctness elsewhere recovers that — the reader has no way to know a
 * question was answered about a tenth of the data.
 */
class GraphAggregationTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    // --- Synthetic graphs: one parent, many children, no table format involved ---

    private fun manifest(id: String) = GraphNode.ManifestNode(id, ManifestListEntry(), simpleId = 1)
    private fun file(id: String) = GraphNode.FileNode(id, ManifestEntry(status = 1), simpleId = 1)
    private fun error(id: String) = GraphNode.ErrorNode(id, "ERR", "read", "/p", "boom")

    /** `parent` with [count] manifests hanging off it, in order. */
    private fun fanOut(count: Int, parentId: String = "snap_1"): Pair<List<GraphNode>, List<GraphEdge>> {
        val parent = GraphNode.SnapshotNode(parentId, model.Snapshot(snapshotId = 1L), simpleId = 1)
        val children = (0 until count).map { manifest("man_$it") }
        val edges = children.map { GraphEdge("e_${parentId}_${it.id}", parentId, it.id) }
        return (listOf(parent) + children) to edges
    }

    private fun policy(pageSize: Int) = AggregationPolicy(pageSize = pageSize)

    private fun List<GraphNode>.groups() = filterIsInstance<GraphNode.GroupNode>()

    @Test
    fun `a run longer than the page becomes a page plus one group`() {
        val (nodes, edges) = fanOut(30)
        val result = GraphAggregation.apply(nodes, edges, policy = policy(10))

        assertEquals(10, result.nodes.filterIsInstance<GraphNode.ManifestNode>().size)
        val group = result.nodes.groups().single()
        assertEquals(20, group.memberCount)
        assertEquals(AggregationKind.MANIFEST, group.kind)
        assertEquals("snap_1", group.parentId)
        assertEquals(1, result.edges.count { it.toId == group.id }, "the parent points at the group")
    }

    @Test
    fun `a run that fits is left alone, and so is a tail of one`() {
        listOf(10, 11).forEach { count ->
            val (nodes, edges) = fanOut(count)
            val result = GraphAggregation.apply(nodes, edges, policy = policy(10))
            assertEquals(emptyList(), result.nodes.groups(), "$count children should all be drawn")
            assertEquals(count, result.nodes.filterIsInstance<GraphNode.ManifestNode>().size)
        }
    }

    /**
     * A group of one would occupy the space of the node it stands for and cost a click on top —
     * which is why the eleventh child above is drawn. Twelve is where a group starts paying.
     */
    @Test
    fun `a tail of two is worth a group`() {
        val (nodes, edges) = fanOut(12)
        val result = GraphAggregation.apply(nodes, edges, policy = policy(10))
        assertEquals(2, result.nodes.groups().single().memberCount)
    }

    @Test
    fun `expanding reveals the next page and hands the rest to the next group`() {
        val (nodes, edges) = fanOut(35)
        val first = GraphAggregation.apply(nodes, edges, policy = policy(10)).nodes.groups().single()
        assertEquals(1, first.pageIndex)

        val opened = GraphAggregation.apply(nodes, edges, setOf(first.id), policy(10))
        assertEquals(20, opened.nodes.filterIsInstance<GraphNode.ManifestNode>().size)
        val second = opened.nodes.groups().single()
        assertEquals(2, second.pageIndex)
        assertEquals(15, second.memberCount)

        val fully = GraphAggregation.apply(nodes, edges, setOf(first.id, second.id), policy(10))
        assertEquals(30, fully.nodes.filterIsInstance<GraphNode.ManifestNode>().size)
        assertEquals(5, fully.nodes.groups().single().memberCount)
    }

    @Test
    fun `expanding every page leaves no group behind`() {
        val (nodes, edges) = fanOut(25)
        val open = mutableSetOf<String>()
        repeat(5) {
            val group = GraphAggregation.apply(nodes, edges, open, policy(10)).nodes.groups().singleOrNull()
            if (group != null) open += group.id
        }
        val result = GraphAggregation.apply(nodes, edges, open, policy(10))
        assertEquals(emptyList(), result.nodes.groups())
        assertEquals(25, result.nodes.filterIsInstance<GraphNode.ManifestNode>().size)
    }

    /**
     * The id has to survive a rebuild of the graph, because that is exactly what expanding one
     * causes. It is derived from the parent, the kind and the page — never from a position in a
     * list, which would move the moment a commit landed.
     */
    @Test
    fun `a group keeps its id when the graph is rebuilt`() {
        val first = GraphAggregation.apply(fanOut(30).first, fanOut(30).second, policy = policy(10))
            .nodes.groups().single()
        val again = GraphAggregation.apply(fanOut(40).first, fanOut(40).second, policy = policy(10))
            .nodes.groups().single()
        assertEquals(first.id, again.id, "same parent, same kind, same page")
    }

    @Test
    fun `read errors are never folded into a group`() {
        val parent = GraphNode.SnapshotNode("snap_1", model.Snapshot(snapshotId = 1L), simpleId = 1)
        val errors = (0 until 30).map { error("err_$it") }
        val edges = errors.map { GraphEdge("e_snap_1_${it.id}", "snap_1", it.id) }

        val result = GraphAggregation.apply(listOf(parent) + errors, edges, policy = policy(5))
        assertEquals(emptyList(), result.nodes.groups())
        assertEquals(30, result.nodes.filterIsInstance<GraphNode.ErrorNode>().size)
    }

    @Test
    fun `kinds are grouped apart, so a card never has to say 37 things`() {
        val parent = GraphNode.SnapshotNode("snap_1", model.Snapshot(snapshotId = 1L), simpleId = 1)
        val manifests = (0 until 20).map { manifest("man_$it") }
        val files = (0 until 20).map { file("file_$it") }
        val children = manifests + files
        val edges = children.map { GraphEdge("e_snap_1_${it.id}", "snap_1", it.id) }

        val groups = GraphAggregation.apply(listOf(parent) + children, edges, policy = policy(5))
            .nodes.groups()
        assertEquals(2, groups.size)
        assertEquals(
            setOf(AggregationKind.MANIFEST, AggregationKind.FILE),
            groups.map { it.kind }.toSet(),
        )
        groups.forEach { assertEquals(15, it.memberCount) }
    }

    /**
     * The graph is not a tree. One Iceberg manifest is a child of every snapshot that carries it
     * forward, so collapsing one snapshot's manifests must not delete a manifest another
     * snapshot is still drawing — only the edge from the snapshot that collapsed it.
     *
     * Without this the removal is by subtree and the second snapshot renders with a hole in it.
     */
    @Test
    fun `a child two parents share survives when only one of them collapses it`() {
        val busy = GraphNode.SnapshotNode("snap_busy", model.Snapshot(snapshotId = 1L), simpleId = 1)
        val quiet = GraphNode.SnapshotNode("snap_quiet", model.Snapshot(snapshotId = 2L), simpleId = 2)
        val shared = manifest("man_shared")
        val others = (0 until 20).map { manifest("man_$it") }

        val nodes = listOf(busy, quiet, shared) + others
        val edges = (others + shared).map { GraphEdge("e_busy_${it.id}", "snap_busy", it.id) } +
            GraphEdge("e_quiet_shared", "snap_quiet", shared.id)

        val result = GraphAggregation.apply(nodes, edges, policy = policy(10))

        assertNotNull(
            result.nodes.find { it.id == "man_shared" },
            "the quiet snapshot still draws it, so it must stay in the graph",
        )
        assertNull(
            result.edges.find { it.fromId == "snap_busy" && it.toId == "man_shared" },
            "but the snapshot that collapsed it must not still point at it",
        )
        assertEquals(1, result.edges.count { it.toId == "man_shared" })
    }

    /**
     * Files under a collapsed manifest leave with it, and the group says how many nodes went —
     * not how many manifests, which would understate it by the size of everything below.
     */
    @Test
    fun `a collapsed subtree is counted whole`() {
        val parent = GraphNode.SnapshotNode("snap_1", model.Snapshot(snapshotId = 1L), simpleId = 1)
        val manifests = (0 until 20).map { manifest("man_$it") }
        val files = manifests.flatMap { man -> (0 until 3).map { file("${man.id}_file_$it") } }
        val nodes = listOf(parent) + manifests + files
        val edges = manifests.map { GraphEdge("e_snap_${it.id}", "snap_1", it.id) } +
            manifests.flatMap { man ->
                (0 until 3).map { GraphEdge("e_${man.id}_$it", man.id, "${man.id}_file_$it") }
            }

        val result = GraphAggregation.apply(nodes, edges, policy = policy(10))
        val group = result.nodes.groups().single()

        assertEquals(10, group.memberCount)
        assertEquals(40, group.hiddenNodeCount, "10 manifests and the 30 files under them")
        assertEquals(nodes.size, result.nodes.count { it !is GraphNode.GroupNode } + group.hiddenNodeCount)
    }

    /**
     * An error under a collapsed manifest is still gone from the drawing, and a failure the
     * reader never learns about is the one kind of hiding aggregation must not do. The group
     * carries the count so the card can say it.
     */
    @Test
    fun `errors inside a collapsed subtree are counted on the group`() {
        val parent = GraphNode.SnapshotNode("snap_1", model.Snapshot(snapshotId = 1L), simpleId = 1)
        val manifests = (0 until 20).map { manifest("man_$it") }
        val broken = manifests.takeLast(4).map { error("err_${it.id}") }
        val nodes = listOf(parent) + manifests + broken
        val edges = manifests.map { GraphEdge("e_snap_${it.id}", "snap_1", it.id) } +
            manifests.takeLast(4).map { GraphEdge("e_${it.id}_err", it.id, "err_${it.id}") }

        val group = GraphAggregation.apply(nodes, edges, policy = policy(10)).nodes.groups().single()
        assertEquals(4, group.hiddenErrorCount)
    }

    /**
     * The page a reader is given is the head of the run *as layout will draw it*, not of the
     * order the builder emitted. The two differ exactly where it matters: a manifest that several
     * snapshots carry forward is emitted once, under whichever snapshot first wrote it, and every
     * later snapshot inherits that position.
     */
    @Test
    fun `the drawn page is the head of the order layout uses`() {
        val parent = GraphNode.SnapshotNode("snap_1", model.Snapshot(snapshotId = 1L), simpleId = 1)
        // Emitted newest-first, which is what inheriting another snapshot's order looks like.
        val manifests = (20 downTo 1).map { seq ->
            GraphNode.ManifestNode(
                "man_$seq",
                ManifestListEntry(sequenceNumber = seq.toLong()),
                simpleId = seq,
            )
        }
        val edges = manifests.map { GraphEdge("e_snap_1_${it.id}", "snap_1", it.id) }

        val result = GraphAggregation.apply(listOf(parent) + manifests, edges, policy = policy(5))

        val drawn = result.nodes.filterIsInstance<GraphNode.ManifestNode>()
            .mapNotNull { it.data.sequenceNumber }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), drawn.sorted(), "the five oldest, not the five emitted first")
        assertEquals(15, result.nodes.groups().single().memberCount)
    }

    /**
     * One page at a time is the right default and the wrong only option: a parent with 5,000
     * manifests is 208 double-clicks from being drawn whole.
     */
    @Test
    fun `expanding all of one group draws every sibling in one rebuild`() {
        val (nodes, edges) = fanOut(97)
        val first = GraphAggregation.apply(nodes, edges, policy = policy(10)).nodes.groups().single()

        val fromFirst = GraphAggregation.apply(
            nodes, edges, GraphAggregation.pageIdsToRevealAll(first, policy(10)), policy(10),
        )
        assertEquals(97, fromFirst.nodes.filterIsInstance<GraphNode.ManifestNode>().size)
        assertEquals(emptyList(), fromFirst.nodes.groups())

        // And from a group that is already past the first page, where the page numbering starts
        // at something other than one.
        val second = GraphAggregation.apply(nodes, edges, setOf(first.id), policy(10)).nodes.groups().single()
        val fromSecond = GraphAggregation.apply(
            nodes, edges, setOf(first.id) + GraphAggregation.pageIdsToRevealAll(second, policy(10)), policy(10),
        )
        assertEquals(97, fromSecond.nodes.filterIsInstance<GraphNode.ManifestNode>().size)
        assertEquals(emptyList(), fromSecond.nodes.groups())
    }

    @Test
    fun `rows are bounded by the page size like any other kind`() {
        val parent = file("file_1")
        val rows = (0 until 8).map { GraphNode.RowNode("row_file_1_$it", mapOf("row_idx" to it)) }
        val edges = rows.map { GraphEdge("e_row_${it.id}", parent.id, it.id) }

        val result = GraphAggregation.apply(listOf(parent) + rows, edges, policy = policy(3))

        val group = result.nodes.groups().single()
        assertEquals(AggregationKind.ROW, group.kind)
        assertEquals(5, group.memberCount)
        assertEquals(3, result.nodes.filterIsInstance<GraphNode.RowNode>().size)
    }

    /**
     * Rows are attached *after* the pass that bounds every other kind, so the pass runs again
     * over them. Five rows per file sits below the shipped page size of 24, so the rule does not
     * fire today — and would have been silently wrong the moment the page size became something
     * a reader can set. This goes through [GraphLayoutService.layoutGraph] because the second
     * pass is that function's decision; a test of [GraphAggregation] alone cannot see it.
     */
    @Test
    fun `sample rows go through the pass rather than around it`() {
        val dir = File(repoRoot, "example/iceberg/default/test")
        val model = UnifiedTableModel(Paths.get(dir.absolutePath))

        val graph = GraphLayoutService.layoutGraph(model, showRows = true, policy = policy(3))

        val rowGroup = graph.groups.singleOrNull { it.kind == AggregationKind.ROW }
        assertNotNull(rowGroup, "five sample rows against a page size of three should leave a group")
        assertEquals(2, rowGroup.memberCount)
        assertEquals(3, graph.nodes.filterIsInstance<GraphNode.RowNode>().size)
    }

    @Test
    fun `a sibling edge does not make its target a child`() {
        val snapshot = GraphNode.PaimonSnapshotNode(
            "psnap_1", model.PaimonSnapshot(id = 1L), simpleId = 1,
        )
        val schemas = (0 until 20).map {
            GraphNode.PaimonSchemaNode("pschema_$it", model.PaimonSchema(id = it), simpleId = it)
        }
        val edges = schemas.map { GraphEdge("e_s_${it.id}", "psnap_1", it.id, isSibling = true) }

        val result = GraphAggregation.apply(listOf(snapshot) + schemas, edges, policy = policy(5))
        assertEquals(emptyList(), result.nodes.groups())
    }

    // --- Against a real table ---

    private fun morModel() =
        UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/mor").absolutePath))

    /**
     * A group stands beside the siblings it pages, under their parent — so a parent that was
     * itself folded away leaves nothing for its groups to stand beside. Such a group used to be
     * emitted anyway: its edge came from a node no longer in the graph, ELK laid it out with no
     * edge at all, and it landed in the first column over the table root. `mor` at page size 3
     * is where it showed — eight metadata versions, so `meta_v7` is in the metadata group, and
     * the snapshot group under `meta_v7` was drawn on top of the table card.
     *
     * The dropped group's members are not lost: they are counted under the group that hid the
     * parent, which is the invariant the test above holds at every page size.
     */
    @Test
    fun `a group under a parent that is not drawn is not drawn either`() {
        val built = IcebergGraphBuilder.buildGraph(morModel())
        val result = GraphAggregation.apply(built.nodes, built.edges, policy = policy(3))
        val drawnIds = result.nodes.mapTo(mutableSetOf()) { it.id }
        val orphaned = result.nodes.groups().filter { it.parentId !in drawnIds }
        assertEquals(emptyList(), orphaned.map { it.id }, "groups whose parent is not in the graph")
        assertTrue(result.edges.all { it.fromId in drawnIds && it.toId in drawnIds }, "every edge joins two drawn nodes")

        // The case is real on this table: a metadata version was folded away, and it has
        // enough snapshots to page.
        val metadataGroup = result.nodes.groups().single { it.parentId == "table_root" }
        val hiddenMetadata = metadataGroup.memberIds.mapNotNull { id -> built.nodes.find { it.id == id } as? GraphNode.MetadataNode }
        assertTrue(hiddenMetadata.any { it.data.snapshots.size > 3 }, "a hidden metadata version with more snapshots than a page")
    }

    /**
     * The invariant, on a table Spark actually wrote: every node the builder produced is either
     * drawn or accounted for by exactly one group. Nothing is dropped, and nothing is counted
     * twice — which is what lets the canvas state a hidden-node figure without deriving it a
     * second way.
     */
    @Test
    fun `on a real table every node is either drawn or claimed by one group`() {
        val built = IcebergGraphBuilder.buildGraph(morModel())
        listOf(1, 2, 3, 5, 8).forEach { pageSize ->
            val result = GraphAggregation.apply(built.nodes, built.edges, policy = policy(pageSize))
            val drawn = result.nodes.count { it !is GraphNode.GroupNode }
            val hidden = result.nodes.groups().sumOf { it.hiddenNodeCount }
            assertEquals(
                built.nodes.size, drawn + hidden,
                "page size $pageSize lost or double-counted nodes",
            )
        }
    }

    @Test
    fun `a real table shrinks under aggregation and returns when everything is expanded`() {
        val built = IcebergGraphBuilder.buildGraph(morModel())
        val collapsed = GraphAggregation.apply(built.nodes, built.edges, policy = policy(2))
        assertTrue(
            collapsed.nodes.groups().isNotEmpty(),
            "the merge-on-read fixture has runs longer than two",
        )
        assertTrue(collapsed.nodes.size < built.nodes.size, "and the drawing got smaller")

        val open = mutableSetOf<String>()
        repeat(60) {
            val groups = GraphAggregation.apply(built.nodes, built.edges, open, policy(2)).nodes.groups()
            if (groups.isEmpty()) return@repeat
            open += groups.map { it.id }
        }
        val expanded = GraphAggregation.apply(built.nodes, built.edges, open, policy(2))
        assertEquals(emptyList(), expanded.nodes.groups(), "expanding everything ends somewhere")
        assertEquals(built.nodes.size, expanded.nodes.size)
        assertEquals(built.edges.size, expanded.edges.size)
    }

    @Test
    fun `the default policy leaves the checked-in fixtures untouched`() {
        listOf("test", "parted", "mor", "v3", "eqdel", "evolved", "respec", "branched").forEach { fixture ->
            val dir = File(repoRoot, "example/iceberg/default/$fixture")
            val built = IcebergGraphBuilder.buildGraph(UnifiedTableModel(Paths.get(dir.absolutePath)))
            val result = GraphAggregation.apply(built.nodes, built.edges)
            assertEquals(
                emptyList(), result.nodes.groups(),
                "$fixture is small enough to draw whole; a group here means the default is too tight",
            )
        }
    }

    @Test
    fun `the none policy draws everything`() {
        val (nodes, edges) = fanOut(5_000)
        val result = GraphAggregation.apply(nodes, edges, policy = AggregationPolicy.NONE)
        assertEquals(nodes.size, result.nodes.size)
        assertEquals(edges.size, result.edges.size)
    }
}
