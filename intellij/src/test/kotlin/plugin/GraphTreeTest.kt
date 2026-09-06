package plugin

import java.io.File
import java.nio.file.Paths
import javax.swing.tree.DefaultMutableTreeNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import model.GraphModel
import model.GraphNode
import model.displayLabel
import model.UnifiedTableModel
import service.AggregationPolicy
import service.GraphAggregation
import service.IcebergGraphBuilder

/**
 * The tree the tool window draws, over the checked-in tables.
 *
 * Against real fixtures rather than a hand-built graph, because the shape this has to survive is
 * the one a real table has: a manifest referenced by several snapshots, a deletion-vector edge
 * that must *not* be followed, and a group card standing for what was not drawn. A two-node graph
 * is laid out correctly by any implementation.
 */
class GraphTreeTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    /**
     * The graph, built and aggregated but **not laid out**.
     *
     * The tree reads nodes and edges and never a position, so running ELK here would be testing
     * something this class does not use — and it would start EMF's reference-cleaner thread, which
     * the IntelliJ platform's own leak detector (on the classpath of every test in this module)
     * correctly reports as still running when the session ends.
     */
    private fun graphOf(name: String, policy: AggregationPolicy = AggregationPolicy.NONE): GraphModel {
        val dir = File(repoRoot, "example/iceberg/default/$name")
        assertTrue(dir.isDirectory, "fixture missing at $dir")
        val built = IcebergGraphBuilder.buildGraph(UnifiedTableModel(Paths.get(dir.absolutePath)))
        val aggregated = GraphAggregation.apply(built.nodes, built.edges, policy = policy)
        return GraphModel(aggregated.nodes, aggregated.edges, 0.0, 0.0)
    }

    private fun flatten(nodes: List<DefaultMutableTreeNode>): List<GraphNode> =
        nodes.flatMap { root ->
            root.depthFirstEnumeration().toList()
                .filterIsInstance<DefaultMutableTreeNode>()
                .mapNotNull { (it.userObject as? GraphTree.Item)?.node }
        }

    @Test
    fun `the table is the single root, and everything hangs off it`() {
        val roots = GraphTree.build(graphOf("mor"))
        assertEquals(1, roots.size, "a table has one root, and got ${roots.size}")
        assertTrue((roots.single().userObject as GraphTree.Item).node is GraphNode.TableNode)
    }

    /**
     * Every node the graph holds is reachable in the tree.
     *
     * The tool window has no canvas to fall back on, so a node that is in the graph and not in the
     * tree is a node the reader cannot get to at all — and nothing about the panel would look
     * wrong. Rows may repeat, because a manifest carried forward genuinely is a child of every
     * snapshot that still needs it, so this is a set comparison and not a count.
     */
    @Test
    fun `every node in the graph appears somewhere in the tree`() {
        listOf("test", "mor", "branched", "v3").forEach { name ->
            val graph = graphOf(name)
            val reached = flatten(GraphTree.build(graph)).map { it.id }.toSet()
            val missing = graph.nodes.map { it.id }.toSet() - reached
            assertTrue(missing.isEmpty(), "$name leaves ${missing.size} node(s) unreachable: ${missing.take(5)}")
        }
    }

    /**
     * Annotation edges are not followed, and `branched` is the fixture that proves it.
     *
     * `e_lineage_*` runs from a snapshot to its parent commit — both in the same layer — so
     * following it would make every commit a child of the one before it and hang the whole history
     * under itself, once per metadata version. The depth is the check: a table's containment is
     * table → metadata → snapshot → manifest → file, five levels, and a lineage-following walk
     * grows with the commit count instead.
     */
    @Test
    fun `snapshot lineage is not treated as containment`() {
        val graph = graphOf("branched")
        assertTrue(graph.edges.any { !it.affectsLayout }, "the fixture should hold annotation edges")

        fun depth(node: DefaultMutableTreeNode): Int =
            node.children().toList().filterIsInstance<DefaultMutableTreeNode>()
                .maxOfOrNull { depth(it) + 1 } ?: 0

        val deepest = GraphTree.build(graph).maxOf { depth(it) }
        assertTrue(deepest in 1..6, "containment is at most six levels deep, and the tree went $deepest")
    }

    /** A group card stands for what was not drawn, and it is a row like any other. */
    @Test
    fun `a paged graph shows its group cards in the tree`() {
        val graph = graphOf("mor", AggregationPolicy(pageSize = 2))
        assertTrue(
            graph.nodes.any { it is GraphNode.GroupNode },
            "the policy should have folded something away",
        )
        val groups = flatten(GraphTree.build(graph)).filterIsInstance<GraphNode.GroupNode>()
        assertTrue(groups.isNotEmpty(), "the group cards should be reachable in the tree")
        assertTrue(GraphTree.details(groups.first()).any { it.first == "Not drawn" })
    }

    /**
     * Every node kind has a label and details, checked against the sealed hierarchy.
     *
     * A node type added without either is a blank row rather than a compile error, since both are
     * `when` expressions over the sealed type — which does fail to compile, but only once someone
     * has not written an `else`. This is the assertion that the branch actually says something.
     */
    @Test
    fun `every node kind names itself and lists something`() {
        val kinds = GraphNode::class.sealedSubclasses.mapNotNull { it.simpleName }.toSet()
        val seen = mutableSetOf<String>()
        listOf("mor", "v3", "branched").forEach { name ->
            val graph = graphOf(name, AggregationPolicy(pageSize = 2))
            flatten(GraphTree.build(graph)).forEach { node ->
                seen += node::class.simpleName.orEmpty()
                assertTrue(node.displayLabel().isNotBlank(), "${node::class.simpleName} has no label")
                val details = GraphTree.details(node)
                assertTrue(details.isNotEmpty(), "${node::class.simpleName} lists nothing")
                assertTrue(
                    details.all { it.first.isNotBlank() && it.second.isNotBlank() },
                    "${node::class.simpleName} has a blank field or value: $details",
                )
            }
        }
        // Rows are not in these fixtures (showRows = false) and Paimon is a different builder, so
        // this checks the Iceberg kinds are covered rather than claiming all thirteen are.
        listOf("TableNode", "MetadataNode", "SnapshotNode", "ManifestNode", "FileNode", "GroupNode")
            .forEach { assertTrue(it in seen, "$it never appeared, so its branch was never exercised") }
        assertTrue(kinds.size >= seen.size)
    }
}
