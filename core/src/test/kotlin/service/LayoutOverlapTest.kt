package service

import model.AggregationKind
import model.FormatTableModel
import model.GraphNode
import model.PaimonUnifiedTableModel
import model.UnifiedTableModel
import model.aggregationKind
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * No two cards of the same layer may occupy the same space.
 *
 * `preventOverlaps` is the last thing layout does and it works per layer, which is where a group
 * node is easy to get wrong: it is not a `ManifestNode`, so every `filterIsInstance` that defines
 * a layer skips it, and it would float in a layer of its own — on top of the manifests it stands
 * for. That failure draws a valid graph with two cards in one place, which no data-level
 * assertion can see and which the render tests only catch if someone opens the file.
 *
 * A small page size is the point: it is the only way a checked-in fixture produces enough groups
 * to test them, and it is now a setting a reader can choose.
 */
class LayoutOverlapTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun iceberg(fixture: String): FormatTableModel =
        UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/$fixture").absolutePath))

    private fun paimon(): FormatTableModel =
        PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/test").absolutePath))

    /** Pairs of node ids in one layer whose rectangles intersect. */
    private fun overlaps(model: FormatTableModel, pageSize: Int): List<String> {
        val graph = GraphLayoutService.layoutGraph(
            model, showRows = false, policy = AggregationPolicy(pageSize = pageSize),
        )
        val layers = graph.nodes.groupBy { node ->
            if (node is GraphNode.GroupNode) node.kind else node.aggregationKind()
        }
        val found = mutableListOf<String>()
        layers.forEach { (kind, nodes) ->
            if (kind == null) return@forEach          // table roots and errors are not a layer
            nodes.forEachIndexed { index, a ->
                nodes.drop(index + 1).forEach { b ->
                    val pa = graph.layoutPositions.getValue(a.id)
                    val pb = graph.layoutPositions.getValue(b.id)
                    val intersects = pa.x < pb.x + b.width && pb.x < pa.x + a.width &&
                        pa.y < pb.y + b.height && pb.y < pa.y + a.height
                    if (intersects) found += "$kind: ${a.id} over ${b.id} (page size $pageSize)"
                }
            }
        }
        return found
    }

    @Test
    fun `no two nodes of one layer overlap, at any page size`() {
        listOf(1, 2, 3, 8, 24).forEach { pageSize ->
            listOf("mor", "parted", "branched", "respec").forEach { fixture ->
                assertEquals(emptyList(), overlaps(iceberg(fixture), pageSize), "in $fixture")
            }
            assertEquals(emptyList(), overlaps(paimon(), pageSize), "in the Paimon fixture")
        }
    }
}
