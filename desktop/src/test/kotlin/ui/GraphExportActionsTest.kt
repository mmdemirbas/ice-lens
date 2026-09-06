package ui

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Paths
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import model.GraphModel
import model.Point
import model.UnifiedTableModel
import service.AggregationPolicy
import service.GraphLayoutService

/**
 * The half of the export that needs a renderer, and the naming that decides where it lands.
 *
 * The formats core produces are checked in `GraphExportTest` against parsers. What can only be
 * checked here is that the PNG is an image of the *whole graph* — the export renders through
 * `GraphCanvas`, which culls to its viewport, so a scene sized wrongly produces a valid PNG of a
 * blank field and nothing about it looks wrong.
 */
class GraphExportActionsTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun graphOf(name: String, policy: AggregationPolicy = AggregationPolicy.NONE): GraphModel {
        val dir = File(repoRoot, "example/iceberg/default/$name")
        assertTrue(dir.isDirectory, "fixture missing at $dir")
        return GraphLayoutService.layoutGraph(
            UnifiedTableModel(Paths.get(dir.absolutePath)), showRows = false, policy = policy,
        )
    }

    private fun positionsOf(graph: GraphModel): (String) -> Point? = { graph.layoutPositions[it] }

    /**
     * The scene covers every card, including the last one on the right.
     *
     * Sized from `GraphModel.width` at first, which is neither the rightmost card's right edge nor
     * where the canvas starts drawing — it puts the origin at `CANVAS_INITIAL_OFFSET` so nothing
     * sits flush in the corner. The first export taken lost the whole file column off the right,
     * and the file was a perfectly valid PNG.
     */
    @Test
    fun `the scene covers the rightmost and bottom-most card`() {
        val graph = graphOf("parted")
        val image = ImageIO.read(ByteArrayInputStream(renderGraphPng(graph, positionsOf(graph), scale = 1f)))

        val neededRight = graph.nodes.maxOf { graph.layoutPositions.getValue(it.id).x + it.width }
        val neededBottom = graph.nodes.maxOf { graph.layoutPositions.getValue(it.id).y + it.height }
        assertTrue(
            image.width >= neededRight + CANVAS_INITIAL_OFFSET.value,
            "the rightmost card ends at $neededRight dp and the scene is only ${image.width} px wide",
        )
        assertTrue(
            image.height >= neededBottom + CANVAS_INITIAL_OFFSET.value,
            "the bottom card ends at $neededBottom dp and the scene is only ${image.height} px tall",
        )
    }

    /**
     * The picture has the graph in it, not an empty field.
     *
     * `GraphCanvas` culls to its viewport, so this is the failure mode that produces a perfectly
     * valid file: the same trap `CanvasRenderPerformanceTest` documents, where a benchmark whose
     * nodes are off-screen measures drawing nothing. Ink is counted across the whole image and
     * required in the far corners, because a scene one node wide would pass a bare "something was
     * drawn" check.
     */
    @Test
    fun `the png holds the whole graph, corner to corner`() {
        val graph = graphOf("branched")
        val image = ImageIO.read(ByteArrayInputStream(renderGraphPng(graph, positionsOf(graph), scale = 1f)))

        val background = image.getRGB(1, 1)
        var left = image.width
        var right = -1
        var top = image.height
        var bottom = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (image.getRGB(x, y) == background) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        assertTrue(right > left && bottom > top, "the export drew nothing at all")
        // The graph's own extent is the scene, so the ink has to reach most of both axes. A
        // fraction rather than the exact edge, because a card's rounded corner and the layout's
        // own trailing space are both legitimately blank.
        assertTrue(
            (right - left) > image.width * 0.7,
            "the ink spans ${right - left} of ${image.width} px — the export is missing the right of the graph",
        )
        assertTrue(
            (bottom - top) > image.height * 0.5,
            "the ink spans ${bottom - top} of ${image.height} px — the export is missing the bottom of the graph",
        )
    }

    /**
     * A graph too large to rasterise is scaled down rather than allowed to allocate.
     *
     * A scene is one bitmap. At scale 2 a graph of a few thousand drawn nodes asks for tens of
     * gigabytes, and the reader gets an `OutOfMemoryError` where they asked for a file.
     */
    @Test
    fun `a huge graph is scaled down instead of exhausting memory`() {
        // Spread by moving the cards, not by editing `GraphModel.width` — the export measures where
        // the cards are, so a doctored width would be ignored and the test would check nothing.
        val graph = graphOf("mor")
        val far = graph.nodes.last().id
        val spread: (String) -> Point? = { id ->
            if (id == far) Point(59_000f, 39_000f) else graph.layoutPositions[id]
        }
        val image = ImageIO.read(ByteArrayInputStream(renderGraphPng(graph, spread, scale = 2f)))
        assertTrue(image.width <= 8_000 && image.height <= 8_000, "got ${image.width}x${image.height}")
        // And the aspect ratio survives, so the picture is squeezed rather than stretched.
        val expected = (59_000.0 + graph.nodes.last().width + 200) / (39_000.0 + graph.nodes.last().height + 200)
        val ratio = image.width.toDouble() / image.height
        assertTrue(kotlin.math.abs(ratio - expected) < 0.05, "aspect ratio $ratio, expected about $expected")
    }

    /**
     * Every format, written out where a person can open them.
     *
     * `desktop/build/reports/export/` alongside the inspector captures: an export is judged the
     * same way a render is, by looking at it. Whether the SVG's colours read on a white page and
     * whether the PNG is legible at the size it comes out are not things an assertion states.
     */
    @Test
    fun `every format produces bytes for a real table`() {
        val graph = graphOf("branched")
        val outputDir = File(repoRoot, "desktop/build/reports/export").also { it.mkdirs() }
        GraphExportFormat.entries.forEach { format ->
            val bytes = exportGraph(graph, format, positionsOf(graph))
            assertTrue(bytes.isNotEmpty(), "$format produced nothing")
            File(outputDir, "branched.${format.extension}").writeBytes(bytes)
        }
    }

    /** The SVG carries the app's own card colours, not a placeholder. */
    @Test
    fun `the svg is coloured from the card palette`() {
        val graph = graphOf("mor")
        val svg = String(exportGraph(graph, GraphExportFormat.SVG, positionsOf(graph)))
        val fills = Regex("""fill="(#[0-9A-F]{6})"""").findAll(svg).map { it.groupValues[1] }.toSet()
        assertTrue(
            fills.size > 2,
            "the export should use a colour per node kind, and used $fills",
        )
    }

    @Test
    fun `the suggested name carries the table and the extension`() {
        val name = suggestedExportName("/wh/default/orders", GraphExportFormat.SVG)
        assertTrue(name.startsWith("orders-"), name)
        assertTrue(name.endsWith(".svg"), name)
    }

    /**
     * A table path is arbitrary text, and a file name is not.
     *
     * The path comes from the workspace, which takes whatever the reader picked — a name with a
     * slash or a colon in it is a name the filesystem will refuse, and the export would fail at
     * the write rather than at the naming.
     */
    @Test
    fun `a table name that is not a file name is made into one`() {
        listOf(
            "/wh/db/my table:v2" to "my-table-v2",
            "/wh/db/../weird" to "weird",
            "/wh/db/" to "db",
            null to "table",
            "" to "table",
            "/wh/db/???" to "table",
        ).forEach { (path, expected) ->
            val name = suggestedExportName(path, GraphExportFormat.CSV)
            assertTrue(
                name.startsWith("$expected-") && name.endsWith(".csv"),
                "\"$path\" should suggest \"$expected-<date>.csv\", got \"$name\"",
            )
            assertTrue(name.none { it in "/\\:*?\"<>|" }, "\"$name\" still holds a character a path cannot")
        }
    }
}
