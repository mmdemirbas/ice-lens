package cli

import export.GraphExport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import model.GraphModel
import model.GraphNode
import model.GraphTree
import model.PaimonUnifiedTableModel
import model.UnifiedTableModel
import model.integrityReport
import model.readTableModel
import model.sweepFileStats
import service.AggregationPolicy
import service.StatsCheckReader
import service.GraphLayoutService
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Every command run over the checked-in tables, its output held to the core function it prints —
 * `GraphTree` for the rows and the tree, `integrityReport` for the check, `GraphExport` for the
 * exports — since the command line is a shell over those and must say nothing of its own.
 */
class IceLensCliTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun fixture(relative: String): String = File(repoRoot, relative).absolutePath

    private val mor = fixture("example/iceberg/default/mor")
    private val dv = fixture("example/paimon/db.db/dv")
    private val pbk = fixture("example/paimon/db.db/pbk")

    private class Run(val code: Int, val out: String, val err: String)

    private fun icelens(vararg args: String): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = IceLensCli.run(args.toList(), PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8))
        return Run(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    private fun graphOf(table: String, policy: AggregationPolicy, rows: Boolean = false): GraphModel {
        val assembled = GraphLayoutService.assembleGraph(readTableModel(Paths.get(table)), showRows = rows, policy = policy)
        return GraphModel(assembled.nodes, assembled.edges, 0.0, 0.0)
    }

    @Test
    fun `summary prints the table node's strip rows under a header, and the same as JSON`() {
        val run = icelens("summary", mor)
        assertEquals(IceLensCli.EXIT_OK, run.code, run.err)
        val lines = run.out.lines()
        assertEquals("mor  Iceberg  $mor", lines.first())
        val item = GraphTree.build(graphOf(mor, AggregationPolicy.DEFAULT)).first { it.node is GraphNode.TableNode }
        val rows = GraphTree.details(item.node, newest = item.newest)
        rows.forEach { (field, value) ->
            assertTrue(lines.any { it.trim().startsWith(field) && it.trim().endsWith(value) }, "$field: $value in\n${run.out}")
        }
        assertTrue(run.out.contains("Records (current)"))
        assertEquals("", run.err)

        val json = Json.parseToJsonElement(icelens("summary", mor, "--json").out).jsonObject
        assertEquals("Iceberg", json.getValue("format").jsonPrimitive.content)
        assertEquals("table_root", json.getValue("id").jsonPrimitive.content)
        assertEquals(rows.toMap(), json.getValue("details").jsonObject.mapValues { it.value.jsonPrimitive.content })
        assertEquals(0, json.getValue("readErrors").jsonArray.size)
    }

    @Test
    fun `tree lists GraphTree's items one per line, indented by depth, each with its id, folded unless --all`() {
        val run = icelens("tree", dv)
        assertEquals(IceLensCli.EXIT_OK, run.code, run.err)
        val items = GraphTree.build(graphOf(dv, AggregationPolicy.DEFAULT)).flatMap { it.flatten() }
        val lines = run.out.trimEnd().lines()
        assertEquals(items.size, lines.size)
        items.zip(lines).forEach { (item, line) ->
            assertTrue(line.endsWith("  [${item.node.id}]"), line)
            assertTrue(line.trimStart().startsWith(item.toString()), line)
        }
        // Indentation is depth: the root at none, its children at one.
        assertTrue(lines.first().startsWith("Paimon") || !lines.first().startsWith(" "), lines.first())
        assertTrue(lines.drop(1).any { it.startsWith("  ") && !it.startsWith("    ") })

        val whole = icelens("tree", dv, "--all").out.trimEnd().lines()
        assertTrue(whole.size >= lines.size)
        assertTrue(whole.none { it.contains("more ") && it.contains("[grp_") }, "nothing folded under --all")

        // Roots and their children only. A Paimon schema is a sibling of the snapshots, reached
        // by no structural edge, so it is a root of its own beside the table.
        val roots = GraphTree.build(graphOf(dv, AggregationPolicy.DEFAULT))
        val shallow = icelens("tree", dv, "--depth", "1").out.trimEnd().lines()
        assertEquals(roots.size + roots.sumOf { it.children.size }, shallow.size)

        val json = Json.parseToJsonElement(icelens("tree", dv, "--json").out).jsonArray
        assertEquals("table_root", json.first().jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(items.size, countNodes(json))
    }

    private fun countNodes(array: JsonArray): Int = array.sumOf { 1 + countNodes(it.jsonObject["children"]?.jsonArray ?: JsonArray(emptyList())) }

    @Test
    fun `show prints one node's rows with the deferred ones read, and refuses an id the table lacks`() {
        val run = icelens("show", mor, "table_root")
        assertEquals(IceLensCli.EXIT_OK, run.code, run.err)
        assertTrue(run.out.lines().first().endsWith("[table_root]"), run.out)
        assertTrue(run.out.contains(GraphTree.MISSING_FILES), "the deferred row is read, not drawn as pending:\n${run.out}")
        assertTrue(run.out.contains("Expiry (older_than = now)"))

        val snapshot = graphOf(mor, AggregationPolicy.NONE).nodes.filterIsInstance<GraphNode.SnapshotNode>().first()
        val json = Json.parseToJsonElement(icelens("show", mor, snapshot.id, "--json").out).jsonObject
        assertEquals(snapshot.id, json.getValue("id").jsonPrimitive.content)
        assertEquals(snapshot.data.snapshotId.toString(), json.getValue("details").jsonObject.getValue("Snapshot id").jsonPrimitive.content)

        val missing = icelens("show", mor, "snap_0")
        assertEquals(IceLensCli.EXIT_UNREADABLE, missing.code)
        assertTrue(missing.err.contains("no node `snap_0`") && missing.err.contains("icelens tree --all"), missing.err)
        assertEquals("", missing.out)
    }

    @Test
    fun `check exits 0 on a consistent table and 1 with the findings listed where a figure disagrees`() {
        val clean = icelens("check", mor)
        assertEquals(IceLensCli.EXIT_OK, clean.code, clean.err)
        val report = UnifiedTableModel(Paths.get(mor)).integrityReport()
        assertTrue(clean.out.contains(report.describe), clean.out)

        val findings = icelens("check", pbk)
        assertEquals(IceLensCli.EXIT_FINDINGS, findings.code)
        val expected = PaimonUnifiedTableModel(Paths.get(pbk)).integrityReport()
        assertEquals(3, expected.findings.size, "pbk's three files under the old bucket count")
        expected.findings.forEach { f ->
            assertTrue(findings.out.lines().any { it.startsWith(f.check.label) && it.contains(f.where) && it.contains(f.recorded) && it.contains(f.counted) }, "$f in\n${findings.out}")
        }

        val json = Json.parseToJsonElement(icelens("check", pbk, "--json").out).jsonObject
        assertEquals(false, json.getValue("consistent").jsonPrimitive.boolean)
        assertEquals(expected.checked, json.getValue("checked").jsonPrimitive.content.toInt())
        assertEquals(List(3) { "bucket_count" }, json.getValue("findings").jsonArray.map { it.jsonObject.getValue("check").jsonPrimitive.content })
        assertEquals(true, Json.parseToJsonElement(icelens("check", mor, "--json").out).jsonObject.getValue("consistent").jsonPrimitive.boolean)
    }

    /**
     * `--files` is the desktop's second click over the whole table: every live data file read
     * against its recorded figures, and on Iceberg the statistics files against their records —
     * held to the same sweep run here. `orcfmt` is the table whose files cannot be read at all,
     * which is exit 1 with each file named, not a pass.
     */
    @Test
    fun `check --files reads every live data file and the statistics files, and a file not read is a failure`() {
        val run = icelens("check", mor, "--files")
        assertEquals(IceLensCli.EXIT_OK, run.code, run.err)
        val node = graphOf(mor, AggregationPolicy.DEFAULT).nodes.filterIsInstance<GraphNode.TableNode>().first()
        val targets = node.fileStats.value.orEmpty()
        val sweep = sweepFileStats(targets, max = targets.size) { StatsCheckReader.check(it) }
        assertTrue(targets.size > 1 && sweep.filesRead == targets.size && sweep.findings.isEmpty(), sweep.describe)
        assertTrue(run.out.contains(sweep.describe), run.out)

        val stats = icelens("check", fixture("example/iceberg/default/pstats"), "--files", "--json")
        assertEquals(IceLensCli.EXIT_OK, stats.code, stats.err)
        val json = Json.parseToJsonElement(stats.out).jsonObject
        assertEquals(true, json.getValue("consistent").jsonPrimitive.boolean)
        val statisticsFiles = json.getValue("statisticsFiles").jsonArray
        assertTrue(statisticsFiles.isNotEmpty() && statisticsFiles.all { it.jsonObject.getValue("read").jsonPrimitive.boolean }, stats.out)
        assertEquals(json.getValue("files").jsonObject.getValue("total").jsonPrimitive.content, json.getValue("files").jsonObject.getValue("read").jsonPrimitive.content, "the whole table, not a page")

        val orc = icelens("check", fixture("example/iceberg/default/orcfmt"), "--files")
        assertEquals(IceLensCli.EXIT_FINDINGS, orc.code, "every file unread is not a pass")
        assertTrue(orc.out.lines().count { it.startsWith("not read: ") } >= 2, orc.out)
        assertEquals(IceLensCli.EXIT_OK, icelens("check", fixture("example/iceberg/default/orcfmt")).code, "the metadata alone agrees")
    }

    @Test
    fun `export writes GraphExport's text to standard output or to the file named, the whole table unless paged`(@TempDir dir: Path) {
        val csv = icelens("export", mor, "--format", "csv")
        assertEquals(IceLensCli.EXIT_OK, csv.code, csv.err)
        assertEquals(GraphExport.toCsv(graphOf(mor, AggregationPolicy.NONE)), csv.out)

        val target = dir.resolve("mor.svg")
        val svg = icelens("export", mor, "--format", "svg", "--out", target.toString())
        assertEquals(IceLensCli.EXIT_OK, svg.code, svg.err)
        assertEquals("", svg.out, "the file took it all")
        assertTrue(svg.err.contains("wrote "), svg.err)
        assertTrue(Files.readString(target).contains("<svg"), "an SVG on disk")

        val json = icelens("export", mor, "--format", "json", "--page-size", "1")
        assertEquals(IceLensCli.EXIT_OK, json.code, json.err)
        assertTrue(json.out.contains("grp_"), "a page size of one folds siblings into groups")
    }

    @Test
    fun `the command line is refused with the usage on stderr, and a directory that is no table with the reason`() {
        val none = icelens()
        assertEquals(IceLensCli.EXIT_USAGE, none.code)
        assertTrue(none.out.startsWith("icelens —"), none.out)

        val help = icelens("--help")
        assertEquals(IceLensCli.EXIT_OK, help.code)

        val unknown = icelens("frobnicate", mor)
        assertEquals(IceLensCli.EXIT_USAGE, unknown.code)
        assertTrue(unknown.err.contains("unknown command `frobnicate`"), unknown.err)

        val badFlag = icelens("summary", mor, "--depth", "2")
        assertEquals(IceLensCli.EXIT_USAGE, badFlag.code)
        assertTrue(badFlag.err.contains("summary takes --json only"), badFlag.err)

        val badFormat = icelens("export", mor, "--format", "xml")
        assertEquals(IceLensCli.EXIT_USAGE, badFormat.code)
        assertTrue(badFormat.err.contains("--format is svg, json or csv"), badFormat.err)

        val valueless = icelens("export", mor, "--format")
        assertEquals(IceLensCli.EXIT_USAGE, valueless.code)

        val notATable = icelens("summary", fixture("docs"))
        assertEquals(IceLensCli.EXIT_UNREADABLE, notATable.code)
        assertTrue(notATable.err.contains("is not an Iceberg or Paimon table"), notATable.err)

        val notADirectory = icelens("check", fixture("README.md"))
        assertEquals(IceLensCli.EXIT_UNREADABLE, notADirectory.code)
        assertTrue(notADirectory.err.contains("is not a directory"), notADirectory.err)

        val version = icelens("version")
        assertEquals(IceLensCli.EXIT_OK, version.code)
        assertTrue(Regex("icelens \\d+\\.\\d+\\.\\d+\\s*").matches(version.out), version.out)
    }
}
