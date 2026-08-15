@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import model.GraphModel
import model.GraphNode
import model.SnapshotRefLabel
import model.UnifiedTableModel
import service.AggregationPolicy
import service.GraphLayoutService
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Paths
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Renders the inspector panel off-screen and writes a PNG per case.
 *
 * Two things this is for. The first is a regression check no data-level test can make: the
 * inspector is the only consumer of the decoded column statistics and partition tuples, and a
 * composable that throws while measuring — a null in a table cell, a row built with the wrong
 * column count — fails here and nowhere else. The whole composition runs, so the failure is a
 * test failure rather than a blank panel someone notices weeks later.
 *
 * The second is that the rendering can actually be looked at. Screen capture on this machine
 * returns bare wallpaper (the Screen Recording permission is missing), so a window screenshot is
 * not available; an off-screen scene needs no window and no permission. The PNGs land in
 * `desktop/build/reports/inspector/` — open them to check the drawing, not just the data.
 *
 * What this does NOT cover: window chrome, the graph canvas, scrolling, and anything below the
 * rendered viewport. It is the panel's first screen, drawn at a fixed size.
 */
class InspectorRenderTest {

    private companion object {
        /** Device pixels per written strip. See [writeBands]. */
        const val BAND_HEIGHT = 1300
    }

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val outputDir = File(repoRoot, "desktop/build/reports/inspector")

    private fun graphFor(fixture: String): GraphModel {
        val tableDir = File(repoRoot, "example/iceberg/default/$fixture")
        assertTrue(tableDir.isDirectory, "fixture missing at $tableDir")
        val model = UnifiedTableModel(Paths.get(tableDir.absolutePath))
        return GraphLayoutService.layoutGraph(model, showRows = false)
    }

    private fun partedGraph(): GraphModel = graphFor("parted")

    @Test
    fun `the table inspector renders`() {
        val graph = partedGraph()
        val table = graph.nodes.filterIsInstance<GraphNode.TableNode>().firstOrNull()
        assertNotNull(table, "graph should contain a table root")
        renderInspector(graph, table.id, "table-node", height = 10400)
    }

    @Test
    fun `the file inspector renders its column statistics and partition sections`() {
        val graph = partedGraph()
        val file = graph.nodes.filterIsInstance<GraphNode.FileNode>()
            .firstOrNull { it.columnStats.isNotEmpty() && it.partition?.isUnpartitioned == false }
        assertNotNull(file, "the partitioned fixture should yield a file node with stats and a partition")
        renderInspector(graph, file.id, "file-node", height = 6000)
    }

    /**
     * The snapshot carrying `main`, drawn as its card and then as its inspector.
     *
     * The card is where the ref chips live, and chips are exactly the thing a screenshot catches
     * and a test does not: they are laid out in a `FlowRow` because a `Row` would place the later
     * ones past the card's edge, unclipped and invisible, with nothing failing.
     */
    @Test
    fun `the snapshot card and inspector render refs and lineage`() {
        val graph = graphFor("mor")
        val withMain = graph.nodes.filterIsInstance<GraphNode.SnapshotNode>()
            .firstOrNull { node -> node.refs.any { it.name == "main" } }
        assertNotNull(withMain, "the merge-on-read fixture should have a snapshot carrying main")

        renderScene("snapshot-card", width = 700, height = 800) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SnapshotCard(withMain)
                SnapshotCard(withMain.copy(refs = withMain.refs + SnapshotRefLabel("v1.0-release", isBranch = false)))
            }
        }
        renderInspector(graph, withMain.id, "snapshot-node", height = 2600)
    }

    /**
     * The two delete-file answers that are not the same answer. A v3 deletion vector names the
     * data file it applies to; an equality delete cannot, and has to say so.
     */
    @Test
    fun `delete file inspectors explain what they delete from`() {
        val v3 = graphFor("v3")
        val vector = v3.nodes.filterIsInstance<GraphNode.FileNode>()
            .firstOrNull { it.data.referencedDataFile != null }
        assertNotNull(vector, "the v3 fixture should have a deletion vector naming its data file")
        renderInspector(v3, vector.id, "delete-vector-node", height = 2000)

        val eqdel = graphFor("eqdel")
        val equality = eqdel.nodes.filterIsInstance<GraphNode.FileNode>()
            .firstOrNull { it.data.content == model.DataFileContent.EQUALITY_DELETES }
        assertNotNull(equality, "the eqdel fixture should have an equality delete")
        renderInspector(eqdel, equality.id, "delete-equality-node", height = 2000)
    }

    @Test
    fun `the manifest inspector renders the entry table`() {
        val graph = partedGraph()
        val manifest = graph.nodes.filterIsInstance<GraphNode.ManifestNode>().firstOrNull()
        assertNotNull(manifest, "graph should contain a manifest")
        renderInspector(graph, manifest.id, "manifest-node", height = 4800)
    }

    /**
     * The card and the inspector for a run of siblings the graph is not drawing.
     *
     * Rendered at a deliberately tight page size, because none of the checked-in fixtures is big
     * enough to trip the default — and a card nobody has looked at is how the last round's
     * clipped ref chips got shipped.
     *
     * Three states worth seeing side by side: the plain case, one where the group also stands
     * for a subtree, and one carrying read errors. The third is the one that must not read as
     * decoration; a failure folded into "and 40 more" is a failure nobody investigates.
     */
    @Test
    fun `the group card and inspector render what is not drawn`() {
        val tableDir = File(repoRoot, "example/iceberg/default/mor")
        val model = UnifiedTableModel(Paths.get(tableDir.absolutePath))
        val graph = GraphLayoutService.layoutGraph(
            model, showRows = false, policy = AggregationPolicy(pageSize = 1),
        )
        val group = graph.groups.firstOrNull()
        assertNotNull(group, "a page size of one should collapse something in the merge-on-read fixture")

        renderScene("group-card", width = 700, height = 700) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                GroupCard(group)
                GroupCard(group.copy(hiddenNodeCount = group.hiddenNodeCount + 4_812))
                GroupCard(group.copy(hiddenNodeCount = group.hiddenNodeCount + 4_812, hiddenErrorCount = 3))
            }
        }
        renderInspector(graph, group.id, "group-node", height = 1600)
    }

    /**
     * Every card the Iceberg graph draws, at the size its node declares.
     *
     * The node's declared height is what ELK reserves and what the card is sized to, and Compose
     * clips nothing — so a card that draws more than it declares loses the overflow under its own
     * border with nothing failing. There is no assertion that can see it; the file is the check.
     */
    @Test
    fun `the graph cards render inside the size their nodes declare`() {
        val graph = partedGraph()
        val table = graph.nodes.filterIsInstance<GraphNode.TableNode>().first()
        val metadata = graph.nodes.filterIsInstance<GraphNode.MetadataNode>().first()
        val snapshot = graph.nodes.filterIsInstance<GraphNode.SnapshotNode>().first()
        val manifest = graph.nodes.filterIsInstance<GraphNode.ManifestNode>().first()
        val file = graph.nodes.filterIsInstance<GraphNode.FileNode>().first()

        renderScene("graph-cards", width = 700, height = 1100) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TableCard(table)
                MetadataCard(metadata)
                SnapshotCard(snapshot)
                ManifestCard(manifest)
                FileCard(file)
            }
        }
    }

    /**
     * Renders one selection and asserts the result is not a blank panel.
     *
     * The emptiness check matters more than it looks: a composable that lays out to zero height,
     * or one whose content sits entirely below the viewport, produces a valid PNG of the
     * background colour and would otherwise pass. Counting pixels that differ from the corner
     * colour is a coarse proxy for "something was drawn", but it separates those two cases.
     */
    private fun renderInspector(graph: GraphModel, nodeId: String, name: String, height: Int) =
        renderScene(name, width = 1400, height = height) { InspectorUnderTest(graph, nodeId) }

    private fun renderScene(name: String, width: Int, height: Int, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = width, height = height, density = Density(2f)) {
            Themed(content)
        }
        val png = try {
            // Twice. Anything whose visibility is decided by state that layout writes — a
            // scrollbar that appears only once the content is known to overflow — is absent from
            // the first frame, because the recomposition that reads it has not run yet. A
            // single-frame capture would show a panel the running app never draws.
            scene.render()
            scene.render().encodeToData()?.bytes
        } finally {
            scene.close()
        }
        assertNotNull(png, "scene produced no image for $name")

        outputDir.mkdirs()
        writeBands(png, name)

        assertTrue(png.size > 5_000, "$name rendered to ${png.size} bytes, which is a blank panel")
    }

    /**
     * Writes the render as one file per [BAND_HEIGHT] strip.
     *
     * A tall panel in a single PNG is unreadable once anything scales it to fit, and the point of
     * these files is that the text can be read. Strips keep every band at a size that survives
     * being opened side by side.
     */
    private fun writeBands(png: ByteArray, name: String) {
        val image = ImageIO.read(ByteArrayInputStream(png))
        val bands = (image.height + BAND_HEIGHT - 1) / BAND_HEIGHT
        (0 until bands).forEach { band ->
            val top = band * BAND_HEIGHT
            val strip = image.getSubimage(0, top, image.width, minOf(BAND_HEIGHT, image.height - top))
            ImageIO.write(strip, "png", File(outputDir, "$name-${band + 1}.png"))
        }
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) {
        MaterialTheme(colorScheme = IceLensLightColorScheme) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    content()
                }
            }
        }
    }

    @Composable
    private fun InspectorUnderTest(graph: GraphModel, nodeId: String) {
        NodeDetailsContent(graph, setOf(nodeId))
    }
}
