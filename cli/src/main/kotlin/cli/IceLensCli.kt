package cli

import export.GraphExport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import model.FormatTableModel
import model.GraphModel
import model.GraphNode
import model.FileStatsSweep
import model.GraphTree
import model.IntegrityFinding
import model.IntegrityReport
import model.PaimonUnifiedTableModel
import model.UnifiedTableModel
import model.displayLabel
import model.integrityReport
import model.readTableModel
import model.StatisticsFileCheck
import model.sweepFileStats
import service.AggregationPolicy
import service.GraphLayoutService
import service.StatsCheckReader
import service.StorageLocation
import service.TableFormat
import service.TableFormatDetector
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * The engine from a terminal.
 *
 * Five commands, each the narrow shells' vocabulary and nothing of its own: `summary` and `show`
 * print the rows `GraphTree.details` gives a node — the same rows the IDE strip draws — `tree`
 * prints `GraphTree.build`, `check` is `integrityReport` with the findings as an exit code, and
 * `export` writes what the desktop's export menu writes. Text by default, `--json` where a script
 * is the reader. What goes to standard output is the answer and only the answer; the engine's
 * logging goes to standard error at `WARN`, so a pipe carries nothing it did not ask for.
 *
 * [run] takes the streams so a test can run a command and read what it printed; `main` hands it
 * the process's own and exits with what it returns.
 */
object IceLensCli {

    const val EXIT_OK = 0

    /** `check` found a disagreement or a read error — the table is not consistent. */
    const val EXIT_FINDINGS = 1

    /** The command line could not be read: an unknown command, a missing argument, a bad flag. */
    const val EXIT_USAGE = 2

    /** The path is not a table this can open, or a node id names nothing in it. */
    const val EXIT_UNREADABLE = 3

    val USAGE: String = """
        |icelens — inspect an Apache Iceberg or Apache Paimon table from the command line
        |
        |usage: icelens <command> <table> [options]
        |
        |  summary <table> [--json]              the table: versions, snapshots, the current figures
        |  tree    <table> [--all] [--rows]      the structure — metadata, snapshots, manifests, files —
        |                  [--depth N] [--json]  one line per node with its id, folded past the page
        |                                        size like the app unless --all; --rows samples rows
        |  show    <table> <node-id> [--json]    one node's rows, deferred ones read — an id from `tree`
        |  check   <table> [--files] [--json]    every recorded figure against the same figure counted;
        |                                        --files opens every live data file and statistics file
        |                                        too; exit 1 on a disagreement or a file not read
        |  export  <table> --format svg|json|csv [--out FILE] [--page-size N]
        |                                        the graph as a drawing, as structure, or the file
        |                                        inventory; the whole table unless a page size folds it
        |  version                               the build
        |
        |<table> is the directory holding the table — Iceberg's `metadata/`, Paimon's `snapshot/` and
        |`schema/` — or an object-storage URL the environment's credentials open.
        |
        |exit codes: 0 done; 1 `check` found a disagreement; 2 the command line could not be read;
        |3 the path is not a table this opens, or the node id names nothing
        |""".trimMargin()

    fun run(args: List<String>, out: PrintStream, err: PrintStream): Int {
        val command = args.firstOrNull()
        if (command == null || command == "help" || command == "--help" || command == "-h") {
            out.print(USAGE)
            return if (command == null) EXIT_USAGE else EXIT_OK
        }
        if (command == "version" || command == "--version") {
            out.println("icelens ${version()}")
            return EXIT_OK
        }
        val parsed = parse(args.drop(1)) ?: return usageError(err, "an option needs a value, or a value was given to a bare flag")
        return try {
            when (command) {
                "summary" -> summary(parsed, out, err)
                "tree" -> tree(parsed, out, err)
                "show" -> show(parsed, out, err)
                "check" -> check(parsed, out, err)
                "export" -> export(parsed, out, err)
                else -> usageError(err, "unknown command `$command`")
            }
        } catch (e: TableNotOpened) {
            err.println("icelens: ${e.message}")
            EXIT_UNREADABLE
        }
    }

    // ---- the commands --------------------------------------------------------------------------

    private fun summary(parsed: Parsed, out: PrintStream, err: PrintStream): Int {
        parsed.allow("json") ?: return usageError(err, "summary takes --json only")
        val table = parsed.positional(0) ?: return usageError(err, "summary needs a table")
        val model = open(table)
        val graph = graphOf(model, showRows = false, policy = AggregationPolicy.DEFAULT)
        val item = GraphTree.build(graph).first { it.node is GraphNode.TableNode }
        val errors = graph.nodes.filterIsInstance<GraphNode.ErrorNode>()
        if (parsed.has("json")) {
            val node = nodeJson(item, deferred = false)
            out.println(pretty(JsonObject(node + mapOf("format" to JsonPrimitive(formatName(model)), "path" to JsonPrimitive(model.path.toString()), "readErrors" to errorsJson(errors)))))
        } else {
            out.println("${model.name}  ${formatName(model)}  ${model.path}")
            printRows(GraphTree.details(item.node, newest = item.newest), out)
            printErrors(errors, out)
        }
        return EXIT_OK
    }

    private fun tree(parsed: Parsed, out: PrintStream, err: PrintStream): Int {
        parsed.allow("all", "rows", "depth", "json") ?: return usageError(err, "tree takes --all, --rows, --depth N and --json")
        val table = parsed.positional(0) ?: return usageError(err, "tree needs a table")
        val maxDepth = parsed.value("depth")?.let { it.toIntOrNull()?.takeIf { d -> d >= 0 } ?: return usageError(err, "--depth takes a whole number") } ?: Int.MAX_VALUE
        val model = open(table)
        val graph = graphOf(model, showRows = parsed.has("rows"), policy = if (parsed.has("all")) AggregationPolicy.NONE else AggregationPolicy.DEFAULT)
        val roots = GraphTree.build(graph)
        if (parsed.has("json")) {
            out.println(pretty(buildJsonArray { roots.forEach { add(treeJson(it, maxDepth)) } }))
        } else {
            fun print(item: GraphTree.Item, depth: Int) {
                out.println("  ".repeat(depth) + item.node.displayLabel() + "  [" + item.node.id + "]")
                if (depth < maxDepth) item.children.forEach { print(it, depth + 1) }
            }
            roots.forEach { print(it, 0) }
        }
        return EXIT_OK
    }

    private fun show(parsed: Parsed, out: PrintStream, err: PrintStream): Int {
        parsed.allow("json") ?: return usageError(err, "show takes --json only")
        val table = parsed.positional(0) ?: return usageError(err, "show needs a table and a node id")
        val id = parsed.positional(1) ?: return usageError(err, "show needs a node id — `icelens tree` lists them")
        val model = open(table)
        // Every node, so an id past the page size is found; rows only when one is asked for,
        // since they are a DuckDB read per drawn data file.
        val graph = graphOf(model, showRows = id.startsWith("row_"), policy = AggregationPolicy.NONE)
        val item = GraphTree.build(graph).flatMap { it.flatten() }.firstOrNull { it.node.id == id }
            ?: throw TableNotOpened("no node `$id` in ${model.name} — `icelens tree --all` lists the ids")
        if (parsed.has("json")) {
            out.println(pretty(nodeJson(item, deferred = true)))
        } else {
            out.println(item.node.displayLabel() + "  [" + item.node.id + "]")
            printRows(rowsOf(item, deferred = true), out)
        }
        return EXIT_OK
    }

    private fun check(parsed: Parsed, out: PrintStream, err: PrintStream): Int {
        parsed.allow("files", "json") ?: return usageError(err, "check takes --files and --json")
        val table = parsed.positional(0) ?: return usageError(err, "check needs a table")
        val model = open(table)
        val report = when (model) {
            is UnifiedTableModel -> model.integrityReport()
            is PaimonUnifiedTableModel -> model.integrityReport()
        }
        val files = if (parsed.has("files")) readFiles(model) else null
        if (parsed.has("json")) {
            out.println(pretty(reportJson(model, report, files)))
        } else {
            out.println("${model.name}  ${formatName(model)}  ${model.path}")
            out.println(report.describe + closureNote(report) + (if (report.readErrors > 0) "; ${report.readErrors} artifacts could not be read" else ""))
            if (files != null) {
                out.println(files.sweep.describe)
                files.describeStatistics?.let { out.println(it) }
            }
            val findings = report.findings + files?.findings.orEmpty()
            if (findings.isNotEmpty()) {
                out.println()
                printTable(listOf("check", "where", "figure", "recorded", "counted"), findings.map { listOf(it.check.label, it.where, it.figure, it.recorded, it.counted) }, out)
            }
            files?.unreadable?.forEach { (name, reason) -> out.println("not read: $name: $reason") }
            model.readErrors.forEach { out.println("read error: ${it.path}: ${it.message}") }
        }
        val consistent = report.findings.isEmpty() && report.readErrors == 0 && (files == null || files.consistent)
        return if (consistent) EXIT_OK else EXIT_FINDINGS
    }

    /**
     * The reads behind the desktop's second click under `Integrity`, off the table node the
     * builders fill: every live data file's statistics, layout and row count against its rows —
     * the whole table, not the panel's page, since a script asked — and, on Iceberg, the
     * statistics files against the records `metadata.json` keeps of them.
     */
    private class FileReads(val sweep: FileStatsSweep, val statistics: List<StatisticsFileCheck>) {
        val findings: List<IntegrityFinding> get() = sweep.findings + statistics.flatMap { it.findings }
        val unreadable: List<Pair<String, String>> get() = sweep.unreadable + statistics.filter { !it.read }.map { it.name to (it.problem ?: "not read") }
        val consistent: Boolean get() = findings.isEmpty() && unreadable.isEmpty()
        val describeStatistics: String? get() {
            if (statistics.isEmpty()) return null
            val read = statistics.count { it.read }
            val figures = statistics.sumOf { it.figures }
            val disagreeing = statistics.sumOf { it.findings.size }
            return "$read of ${statistics.size} statistics file(s) read" + when {
                read == 0 -> ""
                disagreeing == 0 -> ", every one of their $figures figures agrees"
                else -> ", $disagreeing of their $figures figures disagree"
            }
        }
    }

    private fun readFiles(model: FormatTableModel): FileReads {
        val node = graphOf(model, showRows = false, policy = AggregationPolicy.DEFAULT).nodes.filterIsInstance<GraphNode.TableNode>().first()
        val targets = node.fileStats.value.orEmpty()
        val sweep = sweepFileStats(targets, max = targets.size) { StatsCheckReader.check(it) }
        return FileReads(sweep, node.statisticsFiles.value.orEmpty())
    }

    private fun export(parsed: Parsed, out: PrintStream, err: PrintStream): Int {
        parsed.allow("format", "out", "page-size") ?: return usageError(err, "export takes --format, --out and --page-size")
        val table = parsed.positional(0) ?: return usageError(err, "export needs a table")
        val format = parsed.value("format")?.lowercase() ?: return usageError(err, "export needs --format svg, json or csv")
        if (format !in setOf("svg", "json", "csv")) return usageError(err, "--format is svg, json or csv, not `$format`")
        val policy = parsed.value("page-size")?.let { text ->
            text.toIntOrNull()?.takeIf { it > 0 }?.let { AggregationPolicy(it) } ?: return usageError(err, "--page-size takes a whole number above zero")
        } ?: AggregationPolicy.NONE
        val model = open(table)
        // The inventory needs no positions; a drawing and the structure carry them, so those two
        // are laid out the way the canvas lays the table out.
        val text = when (format) {
            "csv" -> GraphExport.toCsv(graphOf(model, showRows = false, policy = policy))
            "svg" -> GraphExport.toSvg(GraphLayoutService.layoutGraph(model, showRows = false, policy = policy))
            else -> GraphExport.toJson(GraphLayoutService.layoutGraph(model, showRows = false, policy = policy))
        }
        val target = parsed.value("out")
        if (target == null) out.print(text) else {
            Files.writeString(Path.of(target), text)
            err.println("wrote ${Path.of(target).toAbsolutePath()}")
        }
        return EXIT_OK
    }

    // ---- opening a table ------------------------------------------------------------------------

    /** A path this cannot open as a table, or a node id it does not hold — [EXIT_UNREADABLE], the message the reader's. */
    private class TableNotOpened(message: String) : RuntimeException(message)

    private fun open(location: String): FormatTableModel {
        val path = runCatching { StorageLocation.pathOf(location) }.getOrElse { throw TableNotOpened(it.message ?: "cannot open $location") }
        if (!Files.isDirectory(path)) throw TableNotOpened("$location is not a directory")
        if (TableFormatDetector.detect(path) == TableFormat.UNKNOWN) {
            throw TableNotOpened("$location is not an Iceberg or Paimon table: no metadata/ holding a *.metadata.json, and no snapshot/ with schema/")
        }
        return readTableModel(path)
    }

    private fun graphOf(model: FormatTableModel, showRows: Boolean, policy: AggregationPolicy): GraphModel {
        val assembled = GraphLayoutService.assembleGraph(model, showRows = showRows, policy = policy)
        return GraphModel(assembled.nodes, assembled.edges, 0.0, 0.0)
    }

    private fun formatName(model: FormatTableModel): String = when (model.format) {
        TableFormat.ICEBERG -> "Iceberg"
        TableFormat.PAIMON -> "Paimon"
        TableFormat.UNKNOWN -> "unknown format"
    }

    private fun version(): String = runCatching {
        val props = java.util.Properties()
        IceLensCli::class.java.classLoader.getResourceAsStream("version.properties")?.use { props.load(it) } ?: return@runCatching "dev"
        props.getProperty("version", "dev")
    }.getOrDefault("dev")

    // ---- what a node prints --------------------------------------------------------------------

    private fun rowsOf(item: GraphTree.Item, deferred: Boolean): List<Pair<String, String>> =
        GraphTree.details(item.node, newest = item.newest) +
            if (deferred && GraphTree.hasDeferredDetails(item.node)) GraphTree.deferredDetails(item.node, item.newest) else emptyList()

    private fun printRows(rows: List<Pair<String, String>>, out: PrintStream) {
        val width = rows.maxOfOrNull { it.first.length } ?: 0
        rows.forEach { (field, value) -> out.println("  " + field.padEnd(width) + "  " + value) }
    }

    private fun printErrors(errors: List<GraphNode.ErrorNode>, out: PrintStream) {
        if (errors.isEmpty()) return
        out.println("  ${errors.size} read error(s):")
        errors.forEach { out.println("    ${it.stage}: ${it.message}") }
    }

    /** Columns padded to their widest cell, a header and a rule above the rows — what a finding reads as in a terminal. */
    private fun printTable(header: List<String>, rows: List<List<String>>, out: PrintStream) {
        val widths = header.indices.map { i -> (rows.map { it[i].length } + header[i].length).max() }
        fun line(cells: List<String>) = cells.mapIndexed { i, c -> c.padEnd(widths[i]) }.joinToString("  ").trimEnd()
        out.println(line(header))
        out.println(widths.joinToString("  ") { "-".repeat(it) })
        rows.forEach { out.println(line(it)) }
    }

    private fun closureNote(report: IntegrityReport): String =
        if (report.closuresChecked < report.snapshotCount) " (the closure checks reached ${report.closuresChecked} of ${report.snapshotCount} snapshots)" else ""

    // ---- JSON ---------------------------------------------------------------------------------

    private val json = Json { prettyPrint = true }

    private fun pretty(element: JsonElement): String = json.encodeToString(JsonElement.serializer(), element)

    private fun nodeJson(item: GraphTree.Item, deferred: Boolean): JsonObject = buildJsonObject {
        put("id", item.node.id)
        put("label", item.node.displayLabel())
        put("details", JsonObject(rowsOf(item, deferred).associate { (field, value) -> field to JsonPrimitive(value) }))
    }

    private fun treeJson(item: GraphTree.Item, maxDepth: Int, depth: Int = 0): JsonObject = buildJsonObject {
        put("id", item.node.id)
        put("label", item.node.displayLabel())
        if (depth < maxDepth) put("children", JsonArray(item.children.map { treeJson(it, maxDepth, depth + 1) }))
    }

    private fun errorsJson(errors: List<GraphNode.ErrorNode>): JsonArray = JsonArray(errors.map { e ->
        buildJsonObject { put("stage", e.stage); put("message", e.message) }
    })

    private fun reportJson(model: FormatTableModel, report: IntegrityReport, files: FileReads?): JsonObject = buildJsonObject {
        put("table", model.name)
        put("format", formatName(model))
        put("path", model.path.toString())
        put("consistent", report.findings.isEmpty() && report.readErrors == 0 && (files == null || files.consistent))
        put("checked", report.checked)
        put("closuresChecked", report.closuresChecked)
        put("snapshots", report.snapshotCount)
        put("readErrors", report.readErrors)
        put("findings", findingsJson(report.findings))
        if (files != null) {
            put("files", buildJsonObject {
                put("total", files.sweep.filesTotal)
                put("read", files.sweep.filesRead)
                put("figures", files.sweep.figures)
                put("findings", findingsJson(files.sweep.findings))
                put("unreadable", JsonArray(files.sweep.unreadable.map { (name, reason) -> buildJsonObject { put("file", name); put("reason", reason) } }))
            })
            put("statisticsFiles", JsonArray(files.statistics.map { s ->
                buildJsonObject {
                    put("file", s.name)
                    put("kind", s.kind.name.lowercase())
                    put("read", s.read)
                    s.problem?.let { put("problem", it) }
                    put("figures", s.figures)
                    put("findings", findingsJson(s.findings))
                }
            }))
        }
    }

    private fun findingsJson(findings: List<IntegrityFinding>): JsonArray = JsonArray(findings.map { f ->
        buildJsonObject {
            put("check", f.check.name.lowercase())
            put("where", f.where)
            put("figure", f.figure)
            put("recorded", f.recorded)
            put("counted", f.counted)
        }
    })

    // ---- the command line ----------------------------------------------------------------------

    /**
     * Positionals and `--flag [value]` options, in any order. A flag's value is the next argument
     * unless that is itself a flag, so `--json` and `--depth 2` both parse; `--depth --json` is
     * the caller's mistake and reads as `depth` with no value, which the command refuses.
     */
    private class Parsed(val positionals: List<String>, val options: Map<String, String?>) {
        fun positional(i: Int): String? = positionals.getOrNull(i)
        fun has(flag: String): Boolean = flag in options
        fun value(flag: String): String? = options[flag]

        /** Null when a flag not in [flags] was given — the command's usage error. */
        fun allow(vararg flags: String): Unit? = if (options.keys.all { it in flags }) Unit else null
    }

    private fun parse(args: List<String>): Parsed? {
        val positionals = mutableListOf<String>()
        val options = mutableMapOf<String, String?>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a.startsWith("--")) {
                val name = a.removePrefix("--")
                val eq = name.indexOf('=')
                if (eq >= 0) {
                    options[name.substring(0, eq)] = name.substring(eq + 1)
                } else if (name in VALUED && i + 1 < args.size && !args[i + 1].startsWith("--")) {
                    options[name] = args[i + 1]; i++
                } else if (name in VALUED) {
                    return null
                } else {
                    options[name] = null
                }
            } else {
                positionals += a
            }
            i++
        }
        return Parsed(positionals, options)
    }

    /** The flags that take a value; every other flag is bare. */
    private val VALUED = setOf("depth", "format", "out", "page-size")

    private fun usageError(err: PrintStream, message: String): Int {
        err.println("icelens: $message")
        err.print(USAGE)
        return EXIT_USAGE
    }
}
