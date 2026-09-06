package service

import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import model.GraphModel
import model.GraphNode
import model.UnifiedTableModel

/**
 * Every layout the reader can pick actually lays a real table out.
 *
 * This is not a formality. An ELK algorithm is a **separate artifact that registers itself through
 * a metadata service**, and the id is resolved at layout time — so an algorithm whose provider was
 * never registered, or whose jar is missing from the classpath, compiles cleanly and fails in front
 * of the reader with an ELK-worded message. Nothing but running it says otherwise.
 */
class GraphLayoutAlgorithmTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun model(name: String): UnifiedTableModel {
        val dir = File(repoRoot, "example/iceberg/default/$name")
        assertTrue(dir.isDirectory, "fixture missing at $dir")
        return UnifiedTableModel(Paths.get(dir.absolutePath))
    }

    private fun layout(name: String, algorithm: GraphLayoutAlgorithm): GraphModel =
        GraphLayoutService.layoutGraph(model(name), showRows = false, algorithm = algorithm)

    @Test
    fun `every algorithm lays out a real table and places every node`() {
        GraphLayoutAlgorithm.entries.forEach { algorithm ->
            val graph = layout("branched", algorithm)
            assertEquals(
                graph.nodes.size, graph.layoutPositions.size,
                "${algorithm.name} left a node without a position",
            )
            assertTrue(graph.width > 0 && graph.height > 0, "${algorithm.name} produced an empty extent")
            // Not all at the origin: an algorithm that failed silently and left every node at its
            // default position would satisfy every check above.
            assertTrue(
                graph.layoutPositions.values.map { it.x to it.y }.toSet().size > 1,
                "${algorithm.name} stacked every node in one place",
            )
        }
    }

    @Test
    fun `every algorithm draws the same nodes and edges, only elsewhere`() {
        val reference = layout("mor", GraphLayoutAlgorithm.DEFAULT)
        GraphLayoutAlgorithm.entries.forEach { algorithm ->
            val graph = layout("mor", algorithm)
            assertEquals(
                reference.nodes.map { it.id }.toSet(), graph.nodes.map { it.id }.toSet(),
                "${algorithm.name} changed which nodes exist, and a layout may only move them",
            )
            assertEquals(reference.edges.map { it.id }.toSet(), graph.edges.map { it.id }.toSet())
        }
    }

    /**
     * The default is still the layout this app was built around.
     *
     * Threading an algorithm through changed the call every existing caller makes, so this is the
     * check that the default did not move: the same table under `layoutGraph`'s default has to be
     * positioned exactly as it is under an explicit `LAYERED_RIGHT`.
     */
    @Test
    fun `the default is unchanged by the algorithm becoming a parameter`() {
        val implicit = GraphLayoutService.layoutGraph(model("branched"), showRows = false)
        val explicit = layout("branched", GraphLayoutAlgorithm.LAYERED_RIGHT)
        assertEquals(explicit.layoutPositions, implicit.layoutPositions)
        assertEquals(explicit.width, implicit.width)
    }

    /**
     * A layer shares one axis, and which axis it is *is* the direction.
     *
     * The manifests are one layer of this graph, so under a left-to-right layout they stand at a
     * single x and under a downward one at a single y. That is the check that the direction was
     * actually applied rather than accepted and ignored — an option set on the wrong property is
     * silently dropped by ELK, and the drawing that comes back is a perfectly good one facing the
     * wrong way.
     */
    @Test
    fun `the direction decides which axis a layer collapses onto`() {
        fun groups(algorithm: GraphLayoutAlgorithm): Pair<Int, Int> {
            val graph = layout("branched", algorithm)
            val manifests = graph.nodes.filterIsInstance<GraphNode.ManifestNode>()
                .mapNotNull { graph.layoutPositions[it.id] }
            assertTrue(manifests.size > 1, "need several manifests for this to mean anything")
            return manifests.map { Math.round(it.x / 8f) }.distinct().size to
                manifests.map { Math.round(it.y / 8f) }.distinct().size
        }

        val (acrossX, acrossY) = groups(GraphLayoutAlgorithm.LAYERED_RIGHT)
        assertEquals(1, acrossX, "left to right puts a layer at one x")
        assertTrue(acrossY > 1, "and spreads it down")

        val (downX, downY) = groups(GraphLayoutAlgorithm.LAYERED_DOWN)
        assertEquals(1, downY, "top to bottom puts a layer at one y")
        assertTrue(downX > 1, "and spreads it across")

        // Force has no layers at all, which is the whole reason it is offered: it answers "what is
        // clustered with what" rather than "what contains what", and neither axis collapses.
        val (forceX, forceY) = groups(GraphLayoutAlgorithm.FORCE)
        assertTrue(forceX > 1 && forceY > 1, "a force layout should not line a layer up on either axis")
    }

    /**
     * The layered refinements run under the left-to-right layout and under nothing else.
     *
     * Two observations, each unambiguous on this forked table. Under left-to-right the commits are
     * one layer — ELK alone would stand them at a single x — and they occupy **two**, which only
     * `spreadSnapshotBranches` does. Under the downward layout the commits sit at a single y,
     * meaning `enforceChronologicalVerticalOrder` never separated them: that pass orders down the
     * page, which under this direction is across a layer, and running it would have been ordering
     * the wrong axis.
     */
    @Test
    fun `only the left-to-right layout gets the layered refinements`() {
        fun snapshots(algorithm: GraphLayoutAlgorithm) =
            layout("branched", algorithm).let { graph ->
                graph.nodes.filterIsInstance<GraphNode.SnapshotNode>()
                    .mapNotNull { graph.layoutPositions[it.id] }
            }

        val across = snapshots(GraphLayoutAlgorithm.LAYERED_RIGHT)
        assertTrue(
            across.map { Math.round(it.x / 8f) }.distinct().size > 1,
            "the fork should have been given its own column, and a layer is otherwise one x",
        )

        val down = snapshots(GraphLayoutAlgorithm.LAYERED_DOWN)
        assertEquals(
            1, down.map { Math.round(it.y / 8f) }.distinct().size,
            "the commits are one layer under a downward layout; a vertical-ordering pass that ran " +
                "here would have pulled them apart on the axis it does not own",
        )
    }

    @Test
    fun `an unknown persisted name falls back to the default rather than failing`() {
        assertEquals(GraphLayoutAlgorithm.DEFAULT, GraphLayoutAlgorithm.byNameOrDefault(null))
        assertEquals(GraphLayoutAlgorithm.DEFAULT, GraphLayoutAlgorithm.byNameOrDefault("RADIAL_SPIRAL"))
        assertEquals(GraphLayoutAlgorithm.FORCE, GraphLayoutAlgorithm.byNameOrDefault("FORCE"))
    }
}
