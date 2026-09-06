package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import export.GraphExport
import java.io.File
import model.GraphModel
import model.GraphNode
import model.Point
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ui.GraphExportActions")

/** What the reader can take away, and the extension each lands under. */
enum class GraphExportFormat(val label: String, val extension: String, val description: String) {
    PNG("PNG image", "png", "the drawing, rasterised"),
    SVG("SVG image", "svg", "the drawing, scalable and editable"),
    JSON("JSON", "json", "the graph as structure"),
    CSV("CSV", "csv", "one row per data or delete file"),
}

/**
 * The biggest raster this will produce, per side, in device pixels.
 *
 * A table with a few thousand drawn nodes lays out several tens of thousands of dp wide, and a
 * scene is allocated as one bitmap — at scale 2 that is tens of gigabytes and an `OutOfMemoryError`
 * where the reader expected a file. The export scales down to fit instead and says so, because a
 * smaller picture of the whole graph is what was asked for and a crash is not.
 */
private const val MAX_PNG_SIDE = 8_000

/** Below this the scene is a rounding error rather than a picture; Skia rejects a zero side. */
private const val MIN_PNG_SIDE = 8

/**
 * The graph as a PNG, drawn by the same canvas the window draws.
 *
 * Through `GraphCanvas` rather than a second drawing of the same graph: an export that laid the
 * cards out itself would be a fork of the thing it is exporting, and would drift the first time a
 * card changed. The scene is sized to the whole graph and the zoom left at 1, so the canvas's own
 * viewport culling keeps everything — the trap the render benchmark documents, used here on
 * purpose. `chromeVisible = false` drops the mini-map, which is a navigation aid for a viewport
 * and means nothing in a picture that has no viewport.
 */
fun renderGraphPng(
    graph: GraphModel,
    positionOf: (String) -> Point?,
    scale: Float = 2f,
): ByteArray {
    // Measured from where the cards actually are, not from `GraphModel.width`. Two things make
    // those differ, and both cost the export the right-hand edge of the graph: the canvas starts
    // its content at CANVAS_INITIAL_OFFSET so nothing sits flush in the corner, and a node's own
    // width extends past the position the layout records for it. Sizing to the bare extent clipped
    // every file card off the first export taken, which the picture showed and no assertion did.
    val extentX = graph.nodes.maxOfOrNull { node ->
        (positionOf(node.id)?.x?.toDouble() ?: 0.0) + node.width
    } ?: graph.width
    val extentY = graph.nodes.maxOfOrNull { node ->
        (positionOf(node.id)?.y?.toDouble() ?: 0.0) + node.height
    } ?: graph.height
    val contentWidth = extentX + CANVAS_INITIAL_OFFSET.value * 2
    val contentHeight = extentY + CANVAS_INITIAL_OFFSET.value * 2

    val requestedWidth = (contentWidth * scale).toInt().coerceAtLeast(MIN_PNG_SIDE)
    val requestedHeight = (contentHeight * scale).toInt().coerceAtLeast(MIN_PNG_SIDE)
    val shrink = minOf(
        1f,
        MAX_PNG_SIDE.toFloat() / requestedWidth,
        MAX_PNG_SIDE.toFloat() / requestedHeight,
    )
    val density = scale * shrink
    if (shrink < 1f) {
        logger.info(
            "Graph is {}x{}dp; exporting at density {} to stay under {}px a side",
            contentWidth.toInt(), contentHeight.toInt(), density, MAX_PNG_SIDE,
        )
    }

    val width = (contentWidth * density).toInt().coerceIn(MIN_PNG_SIDE, MAX_PNG_SIDE)
    val height = (contentHeight * density).toInt().coerceIn(MIN_PNG_SIDE, MAX_PNG_SIDE)

    val positions = NodePositions(graph)
    // The reader's drags, applied to the export's own position holder so the picture matches the
    // screen. `NodePositions.set` takes model dp, which is what `positionOf` answers in.
    graph.nodes.forEach { node ->
        positionOf(node.id)?.let { positions.set(node.id, it.x, it.y) }
    }

    val scene = ImageComposeScene(width = width, height = height, density = Density(density)) {
        MaterialTheme(colorScheme = IceLensLightColorScheme) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                GraphCanvas(
                    graph = graph,
                    positions = positions,
                    selectedNodeIds = emptySet(),
                    isSelectMode = false,
                    zoom = 1f,
                    onZoomChange = {},
                    onSelectionChange = {},
                    chromeVisible = false,
                )
            }
        }
    }
    return try {
        // Twice, for the same reason every capture renders twice: a control whose visibility is
        // decided by state that layout writes is absent from the first frame.
        scene.render()
        scene.render().encodeToData()?.bytes ?: ByteArray(0)
    } finally {
        scene.close()
    }
}

/**
 * Produces the bytes for [format]. PNG needs a renderer and so lives here; the rest are core's.
 *
 * [colorOf] and [labelOf] hand the shell's palette and card text to the SVG, which core has no
 * business knowing — the same seam `positionOf` is.
 */
fun exportGraph(
    graph: GraphModel,
    format: GraphExportFormat,
    positionOf: (String) -> Point?,
    colorOf: (GraphNode) -> String = ::svgColorOf,
    labelOf: (GraphNode) -> String = GraphExport::defaultLabel,
): ByteArray = when (format) {
    GraphExportFormat.PNG -> renderGraphPng(graph, positionOf)
    GraphExportFormat.SVG -> GraphExport.toSvg(graph, positionOf, colorOf, labelOf).toByteArray()
    GraphExportFormat.JSON -> GraphExport.toJson(graph, positionOf, labelOf).toByteArray()
    GraphExportFormat.CSV -> GraphExport.toCsv(graph).toByteArray()
}

/**
 * Asks where to put it. Returns null when the reader cancelled.
 *
 * `FileDialog` in SAVE mode rather than `JFileChooser`, because on macOS it is the sheet the reader
 * expects and it handles "this file exists, replace it?" itself — a confirmation this code should
 * not be reimplementing, and the one thing an export must not get wrong.
 */
fun chooseSaveFile(suggestedName: String, initialDir: File?): File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Export", java.awt.FileDialog.SAVE)
    dialog.directory = (initialDir?.takeIf { it.isDirectory } ?: File(System.getProperty("user.home"))).absolutePath
    dialog.file = suggestedName
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val name = dialog.file ?: return null
    return File(dir, name)
}

/**
 * A file name that says which table and which day, so a folder of exports stays readable.
 *
 * Anything the filesystem or a shell would argue about becomes `-`; a table path is arbitrary text
 * and a name with a `/` in it is not a name.
 */
fun suggestedExportName(tablePath: String?, format: GraphExportFormat): String {
    val table = tablePath?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "table"
    val safe = table.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '-' }
        .joinToString("")
        .trim('-')
        .ifBlank { "table" }
    val day = java.time.LocalDate.now().toString()
    return "$safe-$day.${format.extension}"
}

/**
 * A node's card colour as a CSS hex string, for the SVG.
 *
 * The light palette whatever the window is showing: an exported picture is read on a white page in
 * a ticket or a document, and the dark cards were chosen against a dark canvas that will not be
 * there. `getGraphNodeColor` is the one place those colours live, so this converts rather than
 * restating them — a second palette would be a second set of colours to keep in step.
 */
fun svgColorOf(node: GraphNode): String {
    val argb = getGraphNodeColor(node, dark = false).value.toLong() ushr 32
    return "#%06X".format(argb and 0xFFFFFF)
}
