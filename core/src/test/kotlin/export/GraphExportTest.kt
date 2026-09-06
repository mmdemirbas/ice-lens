package export

import java.io.File
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import model.GraphModel
import model.GraphNode
import model.UnifiedTableModel
import service.AggregationPolicy
import service.GraphLayoutService

/**
 * Taking a real table out of the app in four formats.
 *
 * Against the checked-in fixtures, because an export is only worth anything if it survives what a
 * table actually holds — a path with a comma in it, a name long enough to run off a card, a
 * partition tuple, a delete file among the data files. A hand-built two-node graph exports
 * correctly under any implementation.
 *
 * The SVG is parsed rather than string-matched. A generator that emits a stray `&` or an unclosed
 * tag produces a file that opens in a text editor and fails in every renderer, and a test that
 * greps for `<svg` cannot tell the difference.
 */
class GraphExportTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun icebergGraph(name: String, policy: AggregationPolicy = AggregationPolicy.NONE): GraphModel {
        val dir = File(repoRoot, "example/iceberg/default/$name")
        assertTrue(dir.isDirectory, "fixture missing at $dir")
        return GraphLayoutService.layoutGraph(
            UnifiedTableModel(Paths.get(dir.absolutePath)), showRows = false, policy = policy,
        )
    }

    private fun parseXml(text: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(text.byteInputStream())

    @Test
    fun `the svg is well-formed xml on every fixture`() {
        listOf("test", "parted", "mor", "v3", "branched", "respec", "evolved", "eqdel").forEach { name ->
            val svg = GraphExport.toSvg(icebergGraph(name))
            val document = parseXml(svg)
            assertEquals("svg", document.documentElement.tagName, "$name should produce an <svg> root")
            assertTrue(
                document.getElementsByTagName("rect").length > 1,
                "$name should draw a rect per node plus the background",
            )
        }
    }

    /**
     * A node with no position is left out rather than drawn at the origin.
     *
     * Every node the app draws has one, but the export takes `positionOf` from the caller so a
     * reader's drags are honoured — and a lambda that answers null for a node is the shape a
     * partially-populated drag map takes.
     */
    @Test
    fun `a node with no position is skipped, not stacked at zero`() {
        val graph = icebergGraph("test")
        val keep = graph.nodes.take(2).map { it.id }.toSet()
        val svg = GraphExport.toSvg(graph, positionOf = { id ->
            if (id in keep) graph.layoutPositions[id] else null
        })
        val document = parseXml(svg)
        // One background rect plus one per surviving node.
        assertEquals(1 + keep.size, document.getElementsByTagName("rect").length)
    }

    @Test
    fun `an empty graph produces a valid svg rather than a broken one`() {
        val empty = GraphModel(emptyList(), emptyList(), 0.0, 0.0)
        assertEquals("svg", parseXml(GraphExport.toSvg(empty)).documentElement.tagName)
    }

    /**
     * The characters XML cannot take, in the place they actually arrive.
     *
     * A label is the table's own text — a path, a name — so `&` and `<` reach the exporter from
     * data rather than from a developer, and an unescaped one makes the file unparseable.
     */
    @Test
    fun `label text is escaped, not pasted`() {
        val graph = icebergGraph("test")
        val svg = GraphExport.toSvg(graph, labelOf = { """a & b <c> "d"""" })
        val document = parseXml(svg)
        val texts = document.getElementsByTagName("text")
        assertTrue(texts.length > 0)
        assertEquals("""a & b <c> "d"""", texts.item(0).textContent)
    }

    @Test
    fun `an edge that does not affect layout is drawn dashed`() {
        // `v3` carries the deletion-vector edges, which are the annotation kind.
        val graph = icebergGraph("v3")
        assertTrue(graph.edges.any { !it.affectsLayout }, "the fixture should hold an annotation edge")
        val paths = parseXml(GraphExport.toSvg(graph)).getElementsByTagName("path")

        var dashed = 0
        for (i in 0 until paths.length) {
            if (paths.item(i).attributes.getNamedItem("stroke-dasharray") != null) dashed++
        }
        assertEquals(
            graph.edges.count { !it.affectsLayout && it.fromId in graph.nodeById && it.toId in graph.nodeById },
            dashed,
            "exactly the annotation edges should be dashed",
        )
    }

    @Test
    fun `the json parses and carries every node and edge`() {
        val graph = icebergGraph("mor")
        val json = GraphExport.toJson(graph)
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(json)
        val root = parsed as kotlinx.serialization.json.JsonObject
        assertEquals(graph.nodes.size, (root["nodes"] as kotlinx.serialization.json.JsonArray).size)
        assertEquals(graph.edges.size, (root["edges"] as kotlinx.serialization.json.JsonArray).size)
    }

    @Test
    fun `json escaping survives a quote and a newline in a label`() {
        val graph = icebergGraph("test")
        val json = GraphExport.toJson(graph, labelOf = { "he said \"hi\"\nthen \\ left" })
        val root = kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
        val first = (root["nodes"] as kotlinx.serialization.json.JsonArray).first() as kotlinx.serialization.json.JsonObject
        // The newline became a space before escaping — a label is one line in this format.
        assertEquals(
            "he said \"hi\" then \\ left",
            (first["label"] as kotlinx.serialization.json.JsonPrimitive).content,
        )
    }

    /**
     * The CSV holds one row per file, and delete files are among them and labelled.
     *
     * `mor` is the fixture to ask, because leaving delete files out would make the row count
     * disagree with every figure the app prints about the same table.
     */
    @Test
    fun `the csv lists every file node, delete files included`() {
        val graph = icebergGraph("mor")
        val expected = graph.nodes.count { it is GraphNode.FileNode }
        assertTrue(expected > 0)

        val lines = GraphExport.toCsv(graph).lines()
        assertEquals(GraphExport.CSV_HEADERS.joinToString(","), lines.first())
        assertEquals(expected, lines.size - 1, "one row per file node")

        val contents = lines.drop(1).map { it.split(",")[2] }.toSet()
        assertTrue("data" in contents)
        assertTrue(
            "position-deletes" in contents,
            "mor's positional deletes should be in the export and labelled, not silently dropped",
        )
    }

    @Test
    fun `every csv row has as many cells as the header`() {
        val graph = icebergGraph("parted")
        val expected = GraphExport.CSV_HEADERS.size
        GraphExport.toCsv(graph).lines().forEachIndexed { index, line ->
            assertEquals(expected, splitCsv(line).size, "row $index has the wrong cell count: $line")
        }
    }

    /**
     * A comma inside a cell must not become a column boundary.
     *
     * A partition tuple is the field that carries one first — `name=alpha,beta` is an ordinary
     * value — and a table whose columns shift by one row is worse than no export at all.
     */
    @Test
    fun `a cell holding a comma or a quote round-trips`() {
        val graph = icebergGraph("test")
        val file = graph.nodes.filterIsInstance<GraphNode.FileNode>().first()
        val nasty = """/wh/db/t/data/part,with"quotes".parquet"""
        val doctored = graph.copy(
            nodes = graph.nodes.map { node ->
                if (node.id != file.id) node
                else file.copy(entry = file.entry.copy(dataFile = file.data.copy(filePath = nasty)))
            }
        )
        val row = GraphExport.toCsv(doctored).lines().first { it.startsWith(file.id) }
        assertEquals(GraphExport.CSV_HEADERS.size, splitCsv(row).size)
        assertEquals(nasty, splitCsv(row)[4])
    }

    /** A minimal RFC 4180 reader, so the test parses the format rather than trusting the writer. */
    private fun splitCsv(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { current.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { cells += current.toString(); current.setLength(0) }
                else -> current.append(c)
            }
            i++
        }
        cells += current.toString()
        return cells
    }

    /** A paged graph exports what is drawn, group cards included, so the file matches the screen. */
    @Test
    fun `a paged graph exports the group cards it draws`() {
        val paged = icebergGraph("mor", AggregationPolicy(pageSize = 2))
        assertTrue(paged.groups.isNotEmpty())
        val svg = GraphExport.toSvg(paged)
        val document = parseXml(svg)
        assertEquals(1 + paged.nodes.size, document.getElementsByTagName("rect").length)
    }
}
