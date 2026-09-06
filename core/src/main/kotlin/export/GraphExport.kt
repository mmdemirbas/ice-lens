package export

import model.DataFileContent
import model.GraphModel
import model.GraphNode
import model.Point

/**
 * Taking the drawing and the table out of the window.
 *
 * Four formats, because they answer four different questions and none of them substitutes for
 * another: **SVG** is the picture at any size and stays editable, **PNG** is the picture where only
 * a raster will do (a ticket, a chat message) and is the shell's job since it needs a renderer,
 * **JSON** is the graph as structure for something else to read, and **CSV** is the file inventory,
 * which is the row-shaped thing in this domain and the one that goes into a spreadsheet.
 *
 * Everything here is a pure function of a [GraphModel] and takes no toolkit type, which is why it
 * is core. Two things it cannot know are passed in, on the same principle `GraphNavigation` follows:
 * where a node is *drawn* (the reader may have dragged it) and what colour the shell paints it.
 */
object GraphExport {

    /** A node's box, in the model's dp, at wherever it is drawn. */
    private fun boxOf(node: GraphNode, positionOf: (String) -> Point?): Box? {
        val p = positionOf(node.id) ?: return null
        return Box(p.x.toDouble(), p.y.toDouble(), node.width, node.height)
    }

    private data class Box(val x: Double, val y: Double, val w: Double, val h: Double) {
        val centerX get() = x + w / 2
        val centerY get() = y + h / 2
        val right get() = x + w
    }

    /**
     * The graph as SVG: one rect and one label per node, one path per edge.
     *
     * Edges are routed the way the canvas routes them — a horizontal cubic from the right edge of
     * the parent to the left edge of the child — rather than from ELK's own sections, because the
     * canvas does not use those either and an export that disagreed with the screen would be a
     * second drawing of the same graph. An `affectsLayout = false` edge is dashed here for the same
     * reason it is dashed there: both of its ends can sit side by side, and drawn solid it is
     * indistinguishable from the containment edges crossing the same gap.
     *
     * [colorOf] returns a fill for a node as a CSS colour. Core has no palette — node colours are
     * the shell's, chosen against its theme — so the caller supplies them, and an exporter with no
     * opinion can pass a constant.
     */
    fun toSvg(
        graph: GraphModel,
        positionOf: (String) -> Point? = { graph.layoutPositions[it] },
        colorOf: (GraphNode) -> String = { "#eef2f7" },
        labelOf: (GraphNode) -> String = ::defaultLabel,
        padding: Double = 24.0,
    ): String {
        val boxes = graph.nodes.mapNotNull { node -> boxOf(node, positionOf)?.let { node to it } }
        if (boxes.isEmpty()) return emptySvg()

        val minX = boxes.minOf { it.second.x } - padding
        val minY = boxes.minOf { it.second.y } - padding
        val maxX = boxes.maxOf { it.second.x + it.second.w } + padding
        val maxY = boxes.maxOf { it.second.y + it.second.h } + padding
        val byId = boxes.toMap().mapKeys { it.key.id }

        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine(
                """<svg xmlns="http://www.w3.org/2000/svg" width="${fmt(maxX - minX)}" """ +
                    """height="${fmt(maxY - minY)}" viewBox="${fmt(minX)} ${fmt(minY)} """ +
                    """${fmt(maxX - minX)} ${fmt(maxY - minY)}">"""
            )
            appendLine("""<rect x="${fmt(minX)}" y="${fmt(minY)}" width="${fmt(maxX - minX)}" """ +
                """height="${fmt(maxY - minY)}" fill="#f5f7fa"/>""")

            // Edges first, so a node's box covers the ends rather than the other way round.
            appendLine("<g fill=\"none\" stroke=\"#8a97a8\" stroke-width=\"1.5\">")
            graph.edges.forEach { edge ->
                val from = byId[edge.fromId] ?: return@forEach
                val to = byId[edge.toId] ?: return@forEach
                val x1 = from.right
                val y1 = from.centerY
                val x2 = to.x
                val y2 = to.centerY
                val bend = ((x2 - x1) / 2).coerceAtLeast(12.0)
                val dash = if (edge.affectsLayout) "" else """ stroke-dasharray="6 4""""
                appendLine(
                    """<path d="M ${fmt(x1)} ${fmt(y1)} C ${fmt(x1 + bend)} ${fmt(y1)}, """ +
                        """${fmt(x2 - bend)} ${fmt(y2)}, ${fmt(x2)} ${fmt(y2)}"$dash/>"""
                )
            }
            appendLine("</g>")

            appendLine("""<g font-family="Helvetica, Arial, sans-serif" font-size="11">""")
            boxes.forEach { (node, box) ->
                appendLine(
                    """<rect x="${fmt(box.x)}" y="${fmt(box.y)}" width="${fmt(box.w)}" """ +
                        """height="${fmt(box.h)}" rx="6" fill="${escape(colorOf(node))}" """ +
                        """stroke="#5b6673" stroke-width="1"/>"""
                )
                // One line per label line, because SVG text does not wrap and a long path drawn as
                // one line would run across the whole export.
                labelOf(node).lines().take(MAX_LABEL_LINES).forEachIndexed { index, line ->
                    appendLine(
                        """<text x="${fmt(box.x + 8)}" y="${fmt(box.y + 18 + index * 13)}" """ +
                            """fill="#1a1d21">${escape(line.take(MAX_LABEL_CHARS))}</text>"""
                    )
                }
            }
            appendLine("</g>")
            append("</svg>")
        }
    }

    /**
     * The graph as JSON: every drawn node with where it is and what it is, and every edge.
     *
     * Hand-written rather than serialised from the node classes, and that is deliberate — the node
     * types carry lambdas, lazily-read blobs and layout scratch that have no place in an export,
     * and `@Serializable` on them would make an internal shape into a published one. This is a
     * stated format that happens to be derived from them.
     */
    fun toJson(
        graph: GraphModel,
        positionOf: (String) -> Point? = { graph.layoutPositions[it] },
        labelOf: (GraphNode) -> String = ::defaultLabel,
    ): String = buildString {
        appendLine("{")
        appendLine("""  "nodes": [""")
        val nodeLines = graph.nodes.map { node ->
            val p = positionOf(node.id)
            val fields = listOfNotNull(
                """"id": ${jsonString(node.id)}""",
                """"kind": ${jsonString(node.javaClass.simpleName)}""",
                """"label": ${jsonString(labelOf(node).replace('\n', ' '))}""",
                p?.let { """"x": ${fmt(it.x.toDouble())}, "y": ${fmt(it.y.toDouble())}""" },
                """"width": ${fmt(node.width)}, "height": ${fmt(node.height)}""",
            )
            "    { ${fields.joinToString(", ")} }"
        }
        appendLine(nodeLines.joinToString(",\n"))
        appendLine("  ],")
        appendLine("""  "edges": [""")
        val edgeLines = graph.edges.map { edge ->
            "    { " + listOf(
                """"id": ${jsonString(edge.id)}""",
                """"from": ${jsonString(edge.fromId)}""",
                """"to": ${jsonString(edge.toId)}""",
                """"sibling": ${edge.isSibling}""",
                """"affectsLayout": ${edge.affectsLayout}""",
            ).joinToString(", ") + " }"
        }
        appendLine(edgeLines.joinToString(",\n"))
        appendLine("  ]")
        append("}")
    }

    /** The columns [toCsv] emits, in order. Stated once so a reader can see the shape. */
    val CSV_HEADERS = listOf(
        "node_id", "format", "content", "file_format", "path", "records", "size_bytes", "partition",
    )

    /**
     * The table's files as a spreadsheet: one row per data or delete file the graph holds.
     *
     * A CSV of *every* node would be a table whose columns are empty for most rows — a snapshot has
     * no file size and a metadata version has no partition. Files are the one kind in this model
     * that is genuinely row-shaped and the one a reader wants outside the app: to sum sizes, to
     * find the small-file problem, to diff against a query engine's own file listing.
     *
     * Delete files are included and labelled, because leaving them out would make the row count
     * disagree with every figure in the app, and quietly changing what "file" means is worse than
     * a column the reader can filter on.
     */
    fun toCsv(graph: GraphModel): String {
        val rows = graph.nodes.mapNotNull { node ->
            when (node) {
                is GraphNode.FileNode -> listOf(
                    node.id,
                    "iceberg",
                    when (node.data.content) {
                        DataFileContent.POSITION_DELETES -> "position-deletes"
                        DataFileContent.EQUALITY_DELETES -> "equality-deletes"
                        else -> "data"
                    },
                    node.data.fileFormat ?: "",
                    node.data.filePath ?: "",
                    (node.data.recordCount ?: 0L).toString(),
                    (node.data.fileSizeInBytes ?: 0L).toString(),
                    node.partition?.takeIf { !it.isUnpartitioned }?.path ?: "",
                )

                is GraphNode.PaimonDataFileNode -> listOf(
                    node.id,
                    "paimon",
                    if (node.operationKind == 1) "delete-entry" else "data",
                    "",
                    node.entry.file?.fileName ?: "",
                    (node.entry.file?.rowCount ?: 0L).toString(),
                    (node.entry.file?.fileSize ?: 0L).toString(),
                    listOfNotNull(
                        node.bucket?.let { "bucket=$it" },
                        node.level?.let { "level=$it" },
                    ).joinToString("/"),
                )

                else -> null
            }
        }
        return (listOf(CSV_HEADERS) + rows).joinToString("\n") { row -> row.joinToString(",", transform = ::csvCell) }
    }

    /**
     * A label for a node when the caller has no better one.
     *
     * The shell passes its own, which is what the cards print. This exists so an export from a
     * headless caller — a future CLI, a server — is not blank.
     */
    fun defaultLabel(node: GraphNode): String = when (node) {
        is GraphNode.TableNode -> "TABLE\n${node.summary.tableName}"
        is GraphNode.MetadataNode -> "METADATA ${node.simpleId}\n${node.fileName}"
        is GraphNode.SnapshotNode -> "SNAPSHOT ${node.simpleId}\n${node.data.snapshotId ?: ""}"
        is GraphNode.ManifestNode -> "MANIFEST ${node.simpleId}\n${fileName(node.data.manifestPath)}"
        is GraphNode.FileNode -> "FILE ${node.simpleId}\n${fileName(node.data.filePath)}"
        is GraphNode.RowNode -> "ROW ${node.data.values.firstOrNull() ?: ""}"
        is GraphNode.ErrorNode -> "ERROR\n${node.title}"
        is GraphNode.PaimonSnapshotNode -> "PAIMON SNAPSHOT ${node.simpleId}\n${node.data.id ?: ""}"
        is GraphNode.PaimonSchemaNode -> "PAIMON SCHEMA ${node.simpleId}"
        is GraphNode.PaimonManifestListNode -> "MANIFEST LIST\n${node.kind}"
        is GraphNode.PaimonManifestNode -> "PAIMON MANIFEST ${node.simpleId}\n${fileName(node.data.fileName)}"
        is GraphNode.PaimonDataFileNode -> "PAIMON FILE ${node.simpleId}\n${fileName(node.entry.file?.fileName)}"
        is GraphNode.GroupNode -> "${node.kind.plural}\n${node.memberCount} not drawn"
    }

    private const val MAX_LABEL_LINES = 3
    private const val MAX_LABEL_CHARS = 34

    private fun fileName(path: String?): String = path?.substringAfterLast('/').orEmpty()

    private fun emptySvg(): String =
        """<?xml version="1.0" encoding="UTF-8"?>""" + "\n" +
            """<svg xmlns="http://www.w3.org/2000/svg" width="1" height="1" viewBox="0 0 1 1"></svg>"""

    /** Trims the trailing `.0` a whole number would otherwise carry into every coordinate. */
    private fun fmt(value: Double): String =
        if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString()
        else String.format(java.util.Locale.ROOT, "%.2f", value)

    private fun escape(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun jsonString(text: String): String = buildString {
        append('"')
        text.forEach { c ->
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    /**
     * RFC 4180: quote when the cell holds a comma, a quote or a newline, and double an inner quote.
     *
     * A path is the field most likely to carry one, and a table whose columns shift by one row
     * because a file name held a comma is worse than no export.
     */
    private fun csvCell(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value
}
