@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import model.DataFile
import model.GraphModel
import model.GraphNode
import model.ManifestEntry
import model.ManifestEntryStatus
import model.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How long the canvas takes to draw, as a function of how many nodes are on it.
 *
 * ELK's layout was benchmarked to 4,000 nodes; what had never been measured is the drawing. The
 * question the roadmap asks is whether Compose becomes the bottleneck before ELK does, and whether
 * the answer justifies level-of-detail rendering or node virtualisation.
 *
 * **What is measured, and what it is not.** `ImageComposeScene.render()` is composition, layout and
 * draw into a Skia surface on this thread. It is not a frame on screen: there is no vsync, no
 * present, and no GPU compositing. So these numbers are the CPU work a frame costs, which is the
 * half that would make a large graph feel slow — and they are a floor for on-screen frame time,
 * never a ceiling.
 *
 * **First frame against steady frame.** The first `render()` of a scene pays composition and layout
 * for every node; later ones with no state change pay draw alone. Opening a table is the first, and
 * panning is the second, so both are reported. They are different questions and one number for both
 * would answer neither.
 *
 * **The count is verified, not assumed.** `GraphCanvas` culls to the viewport, so a benchmark that
 * placed nodes off-screen would report the cost of drawing nothing. Every node here is inside the
 * viewport by construction and [assertAllVisible] checks the arithmetic that makes it so.
 */
class CanvasRenderPerformanceTest {

    private companion object {
        const val SCENE_WIDTH = 1600
        const val SCENE_HEIGHT = 1000

        /** Enough frames that one trial is milliseconds rather than noise. */
        const val STEADY_FRAMES = 5

        /** A frame at 60Hz. Not a bound anything here asserts — a unit the numbers are read in. */
        const val FRAME_BUDGET_MS = 16.0
    }

    private fun fileNode(index: Int, x: Double, y: Double) = GraphNode.FileNode(
        id = "file_$index",
        entry = ManifestEntry(
            status = ManifestEntryStatus.ADDED,
            dataFile = DataFile(
                filePath = "/wh/default/bench/data/part-$index.parquet",
                fileFormat = "PARQUET",
                recordCount = 1_000L,
                fileSizeInBytes = 50_000L,
            ),
        ),
        simpleId = index,
        initialX = x,
        initialY = y,
    )

    /**
     * [count] file nodes on a grid, wide enough to stay roughly square so no single axis dominates
     * the extent — a long thin graph would be culled on one axis at any zoom that fits the other.
     */
    private fun grid(count: Int): GraphModel {
        val columns = Math.ceil(Math.sqrt(count.toDouble())).toInt().coerceAtLeast(1)
        val stepX = 260.0
        val stepY = 130.0
        val nodes = (0 until count).map { i ->
            fileNode(i, (i % columns) * stepX, (i / columns) * stepY)
        }
        val rows = Math.ceil(count.toDouble() / columns).toInt().coerceAtLeast(1)
        return GraphModel(
            nodes = nodes,
            edges = emptyList(),
            width = columns * stepX,
            height = rows * stepY,
            layoutPositions = nodes.associate { it.id to Point(it.initialX.toFloat(), it.initialY.toFloat()) },
        )
    }

    /**
     * The zoom at which the whole graph sits inside the scene, so culling keeps every node.
     *
     * `GraphCanvas` converts model dp to device pixels with `zoom * density`, so a node at logical
     * x is at `x * zoom * density` pixels. Fitting means `extent * zoom * density <= scene`.
     */
    private fun fittingZoom(graph: GraphModel, density: Float): Float {
        val byWidth = SCENE_WIDTH / (graph.width * density)
        val byHeight = SCENE_HEIGHT / (graph.height * density)
        return (minOf(byWidth, byHeight) * 0.9).toFloat()
    }

    private fun assertAllVisible(graph: GraphModel, zoom: Float, density: Float) {
        assertTrue(
            graph.width * zoom * density <= SCENE_WIDTH && graph.height * zoom * density <= SCENE_HEIGHT,
            "the benchmark must place every node inside the viewport, or it measures drawing nothing: " +
                "${graph.width}x${graph.height} at zoom $zoom density $density does not fit " +
                "${SCENE_WIDTH}x$SCENE_HEIGHT",
        )
    }

    private data class Timing(val firstFrameMs: Double, val steadyFrameMs: Double)

    private fun time(graph: GraphModel, density: Float = 1f): Timing {
        val zoom = fittingZoom(graph, density)
        assertAllVisible(graph, zoom, density)

        val scene = ImageComposeScene(SCENE_WIDTH, SCENE_HEIGHT, Density(density)) {
            MaterialTheme(colorScheme = IceLensLightColorScheme) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    GraphCanvas(
                        graph = graph,
                        positions = NodePositions(graph),
                        selectedNodeIds = emptySet(),
                        isSelectMode = false,
                        zoom = zoom,
                        onZoomChange = {},
                        onSelectionChange = {},
                    )
                }
            }
        }
        return try {
            val firstStarted = System.nanoTime()
            scene.render()
            val first = (System.nanoTime() - firstStarted) / 1_000_000.0

            // The canvas reads its own constraints to cull and to clamp, so the second frame is
            // the first one that draws the settled viewport. Steady state starts after it.
            scene.render()
            val steady = (1..STEADY_FRAMES).minOf {
                val started = System.nanoTime()
                scene.render()
                (System.nanoTime() - started) / 1_000_000.0
            }
            Timing(first, steady)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `the canvas is drawn, and the cost is reported per node`() {
        // A whole throwaway scene first, so the numbers below are not the JIT warming up.
        time(grid(200))

        val results = listOf(100, 500, 1_000, 2_000, 4_000).map { n -> n to time(grid(n)) }

        println("Canvas render cost — ${SCENE_WIDTH}x$SCENE_HEIGHT, density 1, every node in view")
        println(String.format("%7s %14s %15s %14s %18s", "nodes", "first frame", "steady frame", "us/node", "nodes in 16ms"))
        results.forEach { (n, t) ->
            println(
                String.format(
                    "%7d %11.1f ms %12.2f ms %11.1f us %18d",
                    n,
                    t.firstFrameMs,
                    t.steadyFrameMs,
                    t.steadyFrameMs * 1000.0 / n,
                    (FRAME_BUDGET_MS / (t.steadyFrameMs / n)).toLong(),
                )
            )
        }

        // The assertion is a *ratio*, not a duration. A wall-clock bound says as much about how
        // busy this machine is as about the code, and has to be set so loose it catches nothing;
        // cost per node is dimensionless and stays put across machines. What it catches is the
        // regression that would matter — a pass that goes quadratic, or a draw path that stopped
        // culling — because either one makes the later rows cost more per node, not less.
        fun perNode(n: Int) = results.first { it.first == n }.second.steadyFrameMs * 1000.0 / n
        val atThousand = perNode(1_000)
        val atFourThousand = perNode(4_000)
        assertTrue(
            atFourThousand <= atThousand * 3,
            "drawing should stay about linear in the node count: ${"%.1f".format(atThousand)}us a node " +
                "at 1,000 and ${"%.1f".format(atFourThousand)}us at 4,000 is superlinear",
        )
    }

    /**
     * What the application actually draws, which aggregation caps.
     *
     * The page size bounds siblings per parent, so the drawn node count does not grow with the
     * table — this is the number that decides whether the canvas is ever the bottleneck in the
     * running app, as distinct from what it could be asked to draw in principle.
     */
    @Test
    fun `the drawn node count a page size produces is cheap to draw`() {
        val drawn = grid(AppState.GRAPH_PAGE_SIZE_CHOICES.max() * 3)
        assertEquals(600, drawn.nodes.size, "the largest page size, three parents' worth")
        val timing = time(drawn)
        println("Canvas render cost at 600 drawn nodes: first ${timing.firstFrameMs}ms, steady ${timing.steadyFrameMs}ms")
        assertTrue(
            timing.steadyFrameMs < 5_000,
            "600 nodes took ${timing.steadyFrameMs}ms a frame",
        )
    }
}
