package plugin

import java.io.File
import java.nio.file.Paths
import javax.swing.tree.DefaultMutableTreeNode
import kotlin.test.Test
import kotlin.test.assertEquals
import model.GraphModel
import model.GraphTree
import model.UnifiedTableModel
import service.AggregationPolicy
import service.GraphAggregation
import service.IcebergGraphBuilder

/**
 * The Swing tree the IDE draws is core's [GraphTree] wrapped node for node: the same items, in
 * the same order, each still the `userObject` a selection hands the details panel. The tree's
 * own rules — structural edges only, a node expanded under every parent — are `GraphTreeTest`'s,
 * in core with the tree.
 */
class SwingTreeTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun `the swing nodes mirror the items, with each item as the node's user object`() {
        val dir = File(repoRoot, "example/iceberg/default/mor")
        val built = IcebergGraphBuilder.buildGraph(UnifiedTableModel(Paths.get(dir.absolutePath)))
        val aggregated = GraphAggregation.apply(built.nodes, built.edges, policy = AggregationPolicy.NONE)
        val roots = GraphTree.build(GraphModel(aggregated.nodes, aggregated.edges, 0.0, 0.0))

        fun walk(node: DefaultMutableTreeNode): List<GraphTree.Item> =
            listOf(node.userObject as GraphTree.Item) + node.children().toList().filterIsInstance<DefaultMutableTreeNode>().flatMap { walk(it) }

        val swing = roots.map { it.toSwingNode() }
        assertEquals(roots.flatMap { it.flatten() }.map { it.node.id }, swing.flatMap { walk(it) }.map { it.node.id })
        assertEquals(roots.map { it.depth }, swing.map { it.depth })
    }
}
