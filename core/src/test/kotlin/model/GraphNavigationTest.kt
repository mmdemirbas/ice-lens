package model

import service.GraphLayoutService
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Arrow-key navigation, checked against real drawings rather than a hand-built graph.
 *
 * The rule under test is that a keystroke moves where the reader expects **on screen**, so the
 * fixtures matter: `parted` for an ordinary tree, `branched` for the case the geometry rule exists
 * for — a fork whose column sits beside the main line at a similar height.
 */
class GraphNavigationTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun graphFor(fixture: String): GraphModel {
        val dir = File(repoRoot, "example/iceberg/default/$fixture")
        assertTrue(dir.isDirectory, "fixture missing at $dir")
        return GraphLayoutService.layoutGraph(
            UnifiedTableModel(Paths.get(dir.absolutePath)), showRows = false,
        )
    }

    private fun GraphModel.at(id: String): Point = layoutPositions.getValue(id)

    private fun GraphModel.step(fromId: String, direction: GraphDirection): String? =
        stepFrom(this, fromId, direction) { node -> layoutPositions.getValue(node.id) }

    @Test
    fun `right moves to a child and left comes back`() {
        val graph = graphFor("parted")
        val root = graph.nodes.filterIsInstance<GraphNode.TableNode>().single()

        val child = graph.step(root.id, GraphDirection.RIGHT)
        assertNotNull(child, "the table root should have a child to step into")
        assertTrue(
            graph.nodeById.getValue(child) is GraphNode.MetadataNode,
            "the table's children are its metadata versions, not $child",
        )
        assertEquals(root.id, graph.step(child, GraphDirection.LEFT), "left should come back")
    }

    @Test
    fun `left from the root and up from the topmost node go nowhere`() {
        val graph = graphFor("parted")
        val root = graph.nodes.filterIsInstance<GraphNode.TableNode>().single()

        assertNull(graph.step(root.id, GraphDirection.LEFT), "the root has no parent")

        val topmostMetadata = graph.nodes.filterIsInstance<GraphNode.MetadataNode>()
            .minByOrNull { graph.at(it.id).y }!!
        assertNull(
            graph.step(topmostMetadata.id, GraphDirection.UP),
            "nothing is drawn above the first metadata node in its column",
        )
    }

    @Test
    fun `down walks a column in the order it is drawn`() {
        val graph = graphFor("parted")
        val column = graph.nodes.filterIsInstance<GraphNode.MetadataNode>()
            .sortedBy { graph.at(it.id).y }
        assertTrue(column.size >= 3, "parted should have at least three metadata versions")

        val walked = generateSequence(column.first().id) { graph.step(it, GraphDirection.DOWN) }
            .take(column.size)
            .toList()
        assertEquals(column.map { it.id }, walked, "down should visit the column top to bottom")
    }

    /**
     * The reason up and down are defined by horizontal overlap rather than by layer.
     *
     * `branched` forks, and `spreadSnapshotBranches` puts the fork in a column of its own. Walking
     * down the main line must stay on the main line: a branch commit drawn at a similar height is
     * a different column, and stepping sideways into it without a sideways keystroke is exactly
     * the surprise this rule prevents.
     */
    @Test
    fun `down stays inside a branch column`() {
        val graph = graphFor("branched")
        val snapshots = graph.nodes.filterIsInstance<GraphNode.SnapshotNode>()
        val columns = snapshots.groupBy { graph.at(it.id).x }
        assertTrue(columns.size >= 2, "branched should draw its fork in a column of its own")

        val mainX = columns.keys.min()
        val mainLine = columns.getValue(mainX).map { it.id }.toSet()
        val branch = columns.filterKeys { it != mainX }.values.flatten().map { it.id }.toSet()

        val start = columns.getValue(mainX).minByOrNull { graph.at(it.id).y }!!.id
        val walked = generateSequence(start) { graph.step(it, GraphDirection.DOWN) }.toList()

        assertEquals(mainLine.size, walked.size, "the walk should cover the main line and stop")
        assertTrue(walked.none { it in branch }, "the walk stepped into the branch column: $walked")
    }

    @Test
    fun `a fan of children is entered at the one drawn across from you`() {
        val graph = graphFor("parted")
        val parent = graph.nodes.filterIsInstance<GraphNode.ManifestNode>()
            .maxByOrNull { manifest ->
                graph.edges.count { it.affectsLayout && it.fromId == manifest.id }
            }!!
        val children = graph.edges.filter { it.affectsLayout && it.fromId == parent.id }
            .mapNotNull { graph.nodeById[it.toId] }
        assertTrue(children.size >= 2, "the busiest manifest should have more than one file under it")

        val centre = graph.at(parent.id).y + parent.height.toFloat() / 2f
        val nearest = children.minByOrNull {
            kotlin.math.abs(graph.at(it.id).y + it.height.toFloat() / 2f - centre)
        }!!
        assertEquals(nearest.id, graph.step(parent.id, GraphDirection.RIGHT))
    }

    @Test
    fun `with nothing selected the first arrow lands on the root`() {
        val graph = graphFor("parted")
        val root = graph.nodes.filterIsInstance<GraphNode.TableNode>().single()
        assertEquals(root.id, firstNode(graph) { graph.layoutPositions.getValue(it.id) })
    }

    /**
     * Lineage and deletion-vector edges are annotations, not structure — they are withheld from
     * ELK and drawn dashed for that reason, and following them here would make one keystroke mean
     * two different things by "parent".
     */
    @Test
    fun `an edge withheld from layout is not a step`() {
        val graph = graphFor("branched")
        val lineage = graph.edges.filter { !it.affectsLayout }
        assertTrue(lineage.isNotEmpty(), "branched should carry lineage edges")

        lineage.forEach { edge ->
            assertTrue(
                graph.step(edge.toId, GraphDirection.LEFT) != edge.fromId,
                "left followed the lineage edge ${edge.id}",
            )
            assertTrue(
                graph.step(edge.fromId, GraphDirection.RIGHT) != edge.toId,
                "right followed the lineage edge ${edge.id}",
            )
        }
    }
}
