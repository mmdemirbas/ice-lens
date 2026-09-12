package service

import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.alg.layered.options.NodePlacementStrategy
import org.eclipse.elk.alg.layered.options.CrossingMinimizationStrategy
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.data.LayoutMetaDataService
import org.eclipse.elk.core.math.KVector
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.Direction
import org.eclipse.elk.core.options.EdgeRouting
import org.eclipse.elk.core.options.SizeConstraint
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.eclipse.elk.graph.ElkNode
import org.eclipse.elk.graph.util.ElkGraphUtil
import org.junit.jupiter.api.Tag
import java.util.EnumSet
import kotlin.test.Test

/**
 * Throwaway scaling benchmark for ELK layered layout on a metadata-tree shape.
 * Not a regression test - prints timings only.
 *
 * Tagged out of `test` and run by `./gradlew :core:bench`. It lays out up to 64k nodes and
 * catches `Throwable` per configuration so a heap or stack exhaustion prints as a result — which
 * is the point of the benchmark and fatal to a shared test worker: an `OutOfMemoryError` lands on
 * whichever thread allocates next, and when that is Gradle's, the worker dies and every class
 * after it never runs. It killed the worker twice in a row on code that had just passed, with
 * one, three and eight configurations reporting OOM across three runs of the same bytes.
 */
@Tag("bench")
class ElkScalingBench {

    init {
        LayoutMetaDataService.getInstance()
            .registerLayoutMetaDataProviders(LayeredMetaDataProvider())
    }

    /** Builds a table -> metadata -> snapshot -> manifest -> file tree with `files` leaf nodes. */
    private fun buildTree(
        manifests: Int,
        filesPerManifest: Int,
        routing: EdgeRouting = EdgeRouting.SPLINES,
        placement: NodePlacementStrategy? = null,
        crossMin: CrossingMinimizationStrategy? = null,
        thoroughness: Int? = null,
    ): Pair<ElkNode, Int> {
        val root = ElkGraphUtil.createGraph()
        root.setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered")
        root.setProperty(CoreOptions.DIRECTION, Direction.RIGHT)
        root.setProperty(CoreOptions.EDGE_ROUTING, routing)
        root.setProperty(CoreOptions.SPACING_NODE_NODE, 120.0)
        root.setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, 300.0)
        placement?.let { root.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, it) }
        crossMin?.let { root.setProperty(LayeredOptions.CROSSING_MINIMIZATION_STRATEGY, it) }
        thoroughness?.let { root.setProperty(LayeredOptions.THOROUGHNESS, it) }

        fun mk(w: Double, h: Double): ElkNode {
            val n = ElkGraphUtil.createNode(root)
            n.setProperty(CoreOptions.NODE_SIZE_CONSTRAINTS, EnumSet.of(SizeConstraint.MINIMUM_SIZE))
            n.setProperty(CoreOptions.NODE_SIZE_MINIMUM, KVector(w, h))
            n.width = w; n.height = h
            return n
        }

        var count = 0
        val table = mk(220.0, 90.0); count++
        // one metadata, a few snapshots, manifests fanning out to files
        val meta = mk(200.0, 80.0); count++
        ElkGraphUtil.createSimpleEdge(table, meta)
        val snapCount = maxOf(1, manifests / 8)
        val snaps = (0 until snapCount).map { mk(200.0, 80.0).also { s -> count++; ElkGraphUtil.createSimpleEdge(meta, s) } }
        for (m in 0 until manifests) {
            val man = mk(200.0, 70.0); count++
            ElkGraphUtil.createSimpleEdge(snaps[m % snapCount], man)
            for (f in 0 until filesPerManifest) {
                val file = mk(180.0, 60.0); count++
                ElkGraphUtil.createSimpleEdge(man, file)
            }
        }
        return root to count
    }

    private fun time(root: ElkNode): Long {
        val t0 = System.nanoTime()
        RecursiveGraphLayoutEngine().layout(root, BasicProgressMonitor())
        return (System.nanoTime() - t0) / 1_000_000
    }

    @Test
    fun `elk scaling by node count`() {
        // warm up the JIT thoroughly first
        repeat(5) { time(buildTree(20, 5).first) }

        println("=== ELK layered, SPLINES, defaults (warmed) ===")
        for ((man, fpm) in listOf(
            10 to 10,      // ~130
            40 to 10,      // ~450
            100 to 10,     // ~1.1k
            200 to 10,     // ~2.2k
            400 to 10,     // ~4.4k
            800 to 10,     // ~8.8k
            1600 to 10,    // ~17.6k
            3200 to 10,    // ~35k
        )) {
            val (root, n) = buildTree(man, fpm)
            val ms = time(root)
            println("nodes=$n  edges=${n - 1}  layout=${ms}ms")
        }
    }

    @Test
    fun `tuned config scaling on default stack`() {
        repeat(5) { time(buildTree(20, 5).first) }
        println("=== POLYLINE + thoroughness=1 + BRANDES_KOEPF (default JVM stack) ===")
        for (man in listOf(100, 400, 800, 1600, 3200, 6400)) {
            try {
                val (root, n) = buildTree(
                    man, 10,
                    routing = EdgeRouting.POLYLINE,
                    placement = NodePlacementStrategy.BRANDES_KOEPF,
                    thoroughness = 1,
                )
                println("nodes=$n  layout=${time(root)}ms")
            } catch (t: Throwable) {
                println("man=$man FAILED: ${t::class.simpleName}")
            }
        }
    }

    @Test
    fun `which placement strategy overflows default stack`() {
        for (p in listOf(
            NodePlacementStrategy.SIMPLE,
            NodePlacementStrategy.BRANDES_KOEPF,
            NodePlacementStrategy.NETWORK_SIMPLEX,
            NodePlacementStrategy.LINEAR_SEGMENTS,
        )) {
            try {
                val (root, n) = buildTree(400, 10, placement = p)
                println("$p nodes=$n layout=${time(root)}ms OK")
            } catch (t: Throwable) {
                println("$p CRASHED: ${t::class.simpleName}")
            }
        }
    }

    @Test
    fun `elk option sensitivity at 4400 nodes`() {
        repeat(5) { time(buildTree(20, 5).first) }
        println("=== option sensitivity, ~4.4k nodes ===")

        fun run(label: String, build: () -> Pair<ElkNode, Int>) {
            try {
                val (root, n) = build()
                println("$label  nodes=$n  layout=${time(root)}ms")
            } catch (t: Throwable) {
                println("$label  FAILED: ${t::class.simpleName}: ${t.message?.take(120)}")
            }
        }
        run("SPLINES  + defaults           ") { buildTree(400, 10, routing = EdgeRouting.SPLINES) }
        run("ORTHOGONAL + defaults         ") { buildTree(400, 10, routing = EdgeRouting.ORTHOGONAL) }
        run("POLYLINE + defaults           ") { buildTree(400, 10, routing = EdgeRouting.POLYLINE) }
        run("SPLINES  + SIMPLE placement   ") { buildTree(400, 10, placement = NodePlacementStrategy.SIMPLE) }
        run("SPLINES  + BRANDES_KOEPF      ") { buildTree(400, 10, placement = NodePlacementStrategy.BRANDES_KOEPF) }
        run("SPLINES  + NETWORK_SIMPLEX    ") { buildTree(400, 10, placement = NodePlacementStrategy.NETWORK_SIMPLEX) }
        run("SPLINES  + LINEAR_SEGMENTS    ") { buildTree(400, 10, placement = NodePlacementStrategy.LINEAR_SEGMENTS) }
        run("SPLINES  + thoroughness=1     ") { buildTree(400, 10, thoroughness = 1) }
        run("SPLINES  + crossMin=NONE      ") { buildTree(400, 10, crossMin = CrossingMinimizationStrategy.NONE) }
        run("ORTHO + SIMPLE + thorough=1   ") {
            buildTree(400, 10, routing = EdgeRouting.ORTHOGONAL,
                placement = NodePlacementStrategy.SIMPLE, thoroughness = 1)
        }
    }
}
