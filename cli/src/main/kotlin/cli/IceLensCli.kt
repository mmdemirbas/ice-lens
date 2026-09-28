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
import model.DeltaMaintenanceInput
import model.DeltaOptimizeWhere
import model.DeltaUnifiedTableModel
import model.FormatTableModel
import model.GraphModel
import model.GraphNode
import model.FileStatsSweep
import model.GraphTree
import model.IntegrityFinding
import model.IntegrityReport
import model.LookupInput
import model.MaintenanceDetail
import model.MaintenanceLine
import model.MaintenanceTone
import model.key
import model.forProcedure
import model.maintenanceDetail
import model.formatCount
import model.maintenanceSummary
import model.formatCounted
import model.planUnexistingFiles
import model.planVacuum
import model.PaimonReadInput
import model.PaimonUnifiedTableModel
import model.RowLookupInput
import model.RowLookupResult
import model.ScanFilter
import model.ScanFilterParse
import model.UnifiedTableModel
import model.displayLabel
import model.evaluateScan
import model.integrityReport
import model.paimonRowLookupInput
import model.parseScanFilter
import model.readTableModel
import model.render
import model.rowLookupInput
import model.readInputAt
import model.StatisticsFileCheck
import model.sweepFileStats
import service.AggregationPolicy
import service.GraphLayoutService
import service.PaimonRowLookup
import service.RowLookup
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
 * Seven commands, each the narrow shells' vocabulary and nothing of its own: `summary` and `show`
 * print the rows `GraphTree.details` gives a node — the same rows the IDE strip draws — `tree`
 * prints `GraphTree.build`, `check` is `integrityReport` with the findings as an exit code,
 * `lookup` is `RowLookup`/`PaimonRowLookup` — the rows a filter matches and each one's fate, the
 * desktop's row-lookup section — `plan` is `maintenanceSummary`, the table panel's `Maintenance`
 * lines with the orphan walk and the missing-file stat run rather than left for a click — and
 * `export` writes what the desktop's export menu writes. Text by default, `--json` where a script is the reader. What goes to standard output is the answer
 * and only the answer; the engine's logging goes to standard error at `WARN`, so a pipe carries
 * nothing it did not ask for.
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
        |  lookup  <table> <filter> [--json]     the rows the filter matches, read from the current
        |                                        snapshot's live files it did not rule out, each with
        |                                        its fate — live, or deleted/superseded by what
        |  plan    <table> [<procedure>]         what each maintenance procedure would do if run now —
        |          [--at TIME] [--json]          rewrite, manifest merge, expiry, compaction, orphans —
        |          [--files] [--zorder COLS]     a verdict per procedure, planned the way the engine
        |          [--where CLAUSE]              plans it; with a procedure named, its plan in full,
        |                                        every file or group it would act on; --at plans as of
        |                                        an epoch-ms or ISO instant; --files reads the delete
        |                                        files rewrite_position_delete_files would rewrite,
        |                                        for which positions it keeps; --zorder a,b and
        |                                        --where "p = 'x'" plan a Delta OPTIMIZE ZORDER BY
        |                                        those columns, over the partitions the clause matches
        |  export  <table> --format svg|json|csv [--out FILE] [--page-size N]
        |                                        the graph as a drawing, as structure, or the file
        |                                        inventory; the whole table unless a page size folds it
        |  version                               the build
        |
        |<table> is the directory holding the table — Iceberg's `metadata/`, Paimon's `snapshot/` and
        |`schema/` — or an object-storage URL the environment's credentials open.
        |
        |<filter> for `lookup` is one clause — `"id = 4"`, `"amount >= 10 AND region = 'eu'"`,
        |`"id IN (1, 2)"`, `"name LIKE 'al%'"` — quoted so the shell keeps it as one argument.
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
                "lookup" -> lookup(parsed, out, err)
                "plan" -> plan(parsed, out, err)
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
            is DeltaUnifiedTableModel -> model.integrityReport()
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

    private fun lookup(parsed: Parsed, out: PrintStream, err: PrintStream): Int {
        parsed.allow("json") ?: return usageError(err, "lookup takes --json only")
        val table = parsed.positional(0) ?: return usageError(err, "lookup needs a table and a filter")
        // The filter is the rest of the positionals joined, so `lookup t id = 4` works unquoted
        // and `lookup t "id = 4"` works quoted — the same clause either way.
        val filterText = parsed.positionals.drop(1).joinToString(" ")
        if (filterText.isBlank()) return usageError(err, "lookup needs a filter, e.g. `icelens lookup <table> \"id = 4\"`")
        val filter = when (val p = parseScanFilter(filterText)) {
            is ScanFilterParse.Parsed -> p.filter
            is ScanFilterParse.Failed -> return filterError(err, filterText, p)
        }
        val model = open(table)
        // The whole table's pruning, so no file is folded out of the ruled-out set — the reason
        // this draws every node (NONE) where the desktop scopes to the page it drew.
        val graph = graphOf(model, showRows = false, policy = AggregationPolicy.NONE)
        val ruledOut = evaluateScan(graph, filter).ruledOutFileKeys(graph)
        val input: LookupInput? = when (model) {
            is UnifiedTableModel -> model.rowLookupInput()
            is PaimonUnifiedTableModel -> model.paimonRowLookupInput()
            // The Iceberg lookup over the latest version — a vector is Iceberg's, see DeltaRead.kt.
            is DeltaUnifiedTableModel -> model.latestVersion?.let(model::readInputAt)
        }
        val result = input?.let { readAllPages(it, filter, ruledOut) }
        if (parsed.has("json")) {
            out.println(pretty(lookupJson(model, filter, result)))
            return EXIT_OK
        }
        out.println("${model.name}  ${formatName(model)}  ${model.path}")
        if (result == null) {
            out.println("no current snapshot to read")
            return EXIT_OK
        }
        out.println(
            "${filter.render()} — ${countedRows(result.hits.size)} ${if (result.hits.size == 1) "matches" else "match"}, ${result.live} live" +
                (if (result.deleted > 0) ", ${result.deleted} not live" else "") +
                (if (result.undecided > 0) ", ${result.undecided} not decided" else "") +
                "; ${result.filesRead.size} file(s) read, ${result.filesRuledOut} ruled out by pruning",
        )
        result.rule?.let { out.println(it) }
        if (result.skippedFiles > 0) {
            out.println("${result.skippedFiles} live file(s) at level 0, holding ${result.skippedRows} row(s), are not read by a batch read of this table; a record found there is marked not read")
        }
        if (result.bucketFilesRead + result.bucketFilesPruned > 0) {
            out.println("the hits' keys were asked of their buckets' other files: ${result.bucketFilesRead} opened" +
                (if (result.bucketFilesPruned > 0) ", ${result.bucketFilesPruned} left unopened, their key range excluding every key asked" else ""))
        }
        result.filesRead.filter { it.error != null }.forEach { out.println("could not read ${it.filePath.substringAfterLast('/')}: ${it.error}") }
        if (result.hits.isNotEmpty()) {
            out.println()
            printTable(
                listOf("fate", "note", "by", "file", "position", "row"),
                result.hits.map { hit ->
                    listOf(
                        hit.fate.label,
                        hit.note ?: "",
                        hit.by?.substringAfterLast('/') ?: "",
                        hit.filePath.substringAfterLast('/'),
                        hit.position?.toString() ?: "",
                        // A Paimon key-value row leads with system columns; the row's own come first, as on the card.
                        hit.cells.entries.sortedBy { it.key.startsWith("_") }.joinToString(", ") { "${it.key}=${it.value ?: "null"}" },
                    )
                },
                out,
            )
            if (result.filesRead.any { it.hits >= RowLookup.MAX_HITS_PER_FILE }) {
                out.println()
                out.println("a file's hits stop at ${RowLookup.MAX_HITS_PER_FILE}; narrow the filter to see the rest")
            }
        }
        return EXIT_OK
    }

    /**
     * Every non-ruled-out file, folded — a terminal has no next page to click for, so `lookup`
     * reads to the end (`filesLeft == 0`) the way `check --files` sweeps every file, where the
     * desktop reads a page a click. `RowLookup.lookup` caps a file's hits at [RowLookup.MAX_HITS_PER_FILE]
     * whichever shell asks, which the headline says when it bites.
     */
    /**
     * The table panel's `Maintenance` section as a table: [maintenanceSummary] at [nowMs], with the
     * lines the desktop plans only behind a click — `remove_orphan_files` over the directory walk,
     * `remove_unexisting_files` over the stat of every needed file, Delta's `VACUUM` over its
     * listing — run here, since a command asked has nothing to click. Informational: the exit
     * code is 0 whatever it says.
     */
    private fun plan(parsed: Parsed, out: PrintStream, err: PrintStream): Int {
        parsed.allow("at", "json", "files", "zorder", "where") ?: return usageError(err, "plan takes --at, --json, --files, --zorder and --where")
        val table = parsed.positional(0) ?: return usageError(err, "plan needs a table")
        val procedure = parsed.positional(1)
        val nowMs = parsed.value("at")?.let { at ->
            at.toLongOrNull() ?: runCatching { java.time.Instant.parse(at).toEpochMilli() }.getOrNull()
                ?: return usageError(err, "--at takes epoch milliseconds or an ISO instant such as 2026-01-31T12:00:00Z, not `$at`")
        } ?: System.currentTimeMillis()
        val model = open(table)
        val node = graphOf(model, showRows = false, policy = AggregationPolicy.DEFAULT).nodes.filterIsInstance<GraphNode.TableNode>().first()
        val orphans = if (node.unreferencedFiles.isPresent) node.unreferencedFiles.value else null
        val unexisting = if (node.missingFiles.isPresent && node.paimonRowLookup.isPresent) {
            node.missingFiles.value?.let { report -> node.paimonRowLookup.value?.let { planUnexistingFiles(report, it) } }
        } else null
        val vacuum = (node.maintenance.value as? DeltaMaintenanceInput)?.model?.planVacuum(nowMs)?.getOrNull()
        val lines = maintenanceSummary(node, nowMs, orphans, unexisting, vacuum)
        if (procedure != null) {
            val line = lines.forProcedure(procedure)
                ?: return usageError(err, "this table plans no `$procedure`; it plans ${lines.joinToString(", ") { it.key }}")
            val zOrderBy = parsed.value("zorder")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            if (parsed.has("zorder") && (line.key != "optimize" || zOrderBy.isEmpty())) {
                return usageError(err, "--zorder takes the columns of a Delta OPTIMIZE ZORDER BY, as `plan <table> optimize --zorder a,b`")
            }
            val whereText = parsed.value("where")
            if (parsed.has("where") && (line.key != "optimize" || whereText.isNullOrBlank())) {
                return usageError(err, "--where takes the partition predicate of a Delta OPTIMIZE, as `plan <table> optimize --where \"p = 'x'\"`")
            }
            val where = whereText?.let { text ->
                when (val parse = parseScanFilter(text, sparkLiterals = true)) {
                    is ScanFilterParse.Failed -> return filterError(err, text, parse)
                    is ScanFilterParse.Parsed -> DeltaOptimizeWhere(text, parse.filter)
                }
            }
            val detail = maintenanceDetail(node, line, nowMs, orphans, unexisting, vacuum, readFiles = parsed.has("files"), zOrderBy = zOrderBy, where = where)
            if (parsed.has("json")) out.println(pretty(detailJson(model, nowMs, detail))) else printDetail(model, nowMs, detail, out)
            return EXIT_OK
        }
        if (parsed.has("zorder") || parsed.has("where")) return usageError(err, "--zorder and --where take a procedure: `plan <table> optimize --zorder a,b`")
        if (parsed.has("json")) {
            out.println(pretty(buildJsonObject {
                put("table", model.name)
                put("format", formatName(model))
                put("path", model.path.toString())
                put("at", nowMs)
                put("procedures", JsonArray(lines.map { l ->
                    buildJsonObject {
                        put("procedure", l.procedure)
                        put("key", l.key)
                        put("verdict", l.verdict)
                        put("acts", l.tone != MaintenanceTone.PLAIN)
                        put("tone", l.tone.name.lowercase())
                        put("detail", l.detail)
                        put("where", l.where)
                    }
                }))
            }))
        } else {
            out.println("${model.name}  ${formatName(model)}  ${model.path}")
            val acting = lines.count { it.tone != MaintenanceTone.PLAIN }
            out.println("${formatCounted(lines.size, "procedure")} planned as of ${java.time.Instant.ofEpochMilli(nowMs)}; " + if (acting == 0) "none would act" else "$acting would act")
            out.println()
            printTable(listOf("", "procedure", "verdict", "detail"), lines.map { l ->
                listOf(when (l.tone) { MaintenanceTone.PLAIN -> ""; MaintenanceTone.ACTS -> "*"; MaintenanceTone.ALERT -> "!" }, l.procedure, l.verdict, l.detail)
            }, out)
            out.println()
            out.println("* would act   ! would destroy data, be refused, or block a writer")
            out.println("icelens plan <table> <procedure> prints one in full: ${lines.joinToString(", ") { it.key }}")
        }
        return EXIT_OK
    }

    /** One procedure's plan: its summary line, what it was planned under, and every table, uncapped. */
    private fun printDetail(model: FormatTableModel, nowMs: Long, detail: MaintenanceDetail, out: PrintStream) {
        val l = detail.line
        out.println("${model.name}  ${formatName(model)}  ${model.path}")
        out.println("${l.procedure} as of ${java.time.Instant.ofEpochMilli(nowMs)}: ${toneMark(l)}${l.verdict} — ${l.detail}")
        detail.notes.forEach { out.println("  $it") }
        detail.tables.forEach { t ->
            out.println()
            out.println("${t.title} (${formatCount(t.rows.size)})")
            printTable(t.headers, t.rows, out)
        }
    }

    private fun toneMark(line: MaintenanceLine): String = when (line.tone) {
        MaintenanceTone.PLAIN -> ""
        MaintenanceTone.ACTS -> "* "
        MaintenanceTone.ALERT -> "! "
    }

    private fun detailJson(model: FormatTableModel, nowMs: Long, detail: MaintenanceDetail): JsonObject = buildJsonObject {
        val l = detail.line
        put("table", model.name)
        put("format", formatName(model))
        put("path", model.path.toString())
        put("at", nowMs)
        put("procedure", l.procedure)
        put("key", l.key)
        put("verdict", l.verdict)
        put("tone", l.tone.name.lowercase())
        put("detail", l.detail)
        put("where", l.where)
        put("notes", JsonArray(detail.notes.map { JsonPrimitive(it) }))
        put("tables", JsonArray(detail.tables.map { t ->
            buildJsonObject {
                put("title", t.title)
                put("rows", JsonArray(t.rows.map { row -> buildJsonObject { t.headers.zip(row).forEach { (h, c) -> put(h, c) } } }))
            }
        }))
    }

    private fun readAllPages(input: LookupInput, filter: ScanFilter, ruledOut: Set<String>): RowLookupResult {
        fun page(from: Int) = when (input) {
            is RowLookupInput -> RowLookup.lookup(input, filter, ruledOut, from = from)
            is PaimonReadInput -> PaimonRowLookup.lookup(input, filter, ruledOut, from = from)
        }
        var folded = page(0)
        while (folded.filesLeft > 0) folded += page(folded.filesRead.size)
        return folded
    }

    /** A parse failure points at the offset in the reader's own filter text, not the command grammar. */
    private fun filterError(err: PrintStream, text: String, failure: ScanFilterParse.Failed): Int {
        err.println("icelens: ${failure.message}")
        err.println("  $text")
        err.println("  " + " ".repeat(failure.at.coerceIn(0, text.length)) + "^")
        return EXIT_USAGE
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
        // A local path is made absolute here, as the desktop's workspace and the IDE's virtual
        // files already are: the model renders paths as strings, some readers absolute and some
        // as given, and a relative root made `pru`'s two missing files match no live file.
        val path = runCatching { StorageLocation.pathOf(location) }.getOrElse { throw TableNotOpened(it.message ?: "cannot open $location") }
            .let { if (it.fileSystem == java.nio.file.FileSystems.getDefault()) it.toAbsolutePath().normalize() else it }
        if (!Files.isDirectory(path)) throw TableNotOpened("$location is not a directory")
        if (TableFormatDetector.detect(path) == TableFormat.UNKNOWN) {
            throw TableNotOpened("$location is not an Iceberg, Paimon or Delta table: no metadata/ holding a *.metadata.json, no snapshot/ with schema/, and no _delta_log/ holding a commit")
        }
        return readTableModel(path)
    }

    private fun graphOf(model: FormatTableModel, showRows: Boolean, policy: AggregationPolicy): GraphModel {
        val assembled = GraphLayoutService.assembleGraph(model, showRows = showRows, policy = policy)
        return GraphModel(assembled.nodes, assembled.edges, 0.0, 0.0)
    }

    private fun countedRows(n: Int): String = "$n row" + if (n == 1) "" else "s"

    private fun formatName(model: FormatTableModel): String = when (model.format) {
        TableFormat.ICEBERG -> "Iceberg"
        TableFormat.PAIMON -> "Paimon"
        TableFormat.DELTA -> "Delta"
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

    private fun lookupJson(model: FormatTableModel, filter: ScanFilter, result: RowLookupResult?): JsonObject = buildJsonObject {
        put("table", model.name)
        put("format", formatName(model))
        put("path", model.path.toString())
        put("filter", filter.render())
        if (result == null) {
            put("snapshot", JsonPrimitive(null as String?))
            put("hits", JsonArray(emptyList()))
            return@buildJsonObject
        }
        put("matched", result.hits.size)
        put("live", result.live)
        put("deleted", result.deleted)
        put("undecided", result.undecided)
        put("filesRead", result.filesRead.size)
        put("filesRuledOut", result.filesRuledOut)
        result.rule?.let { put("rule", it) }
        if (result.skippedFiles > 0) {
            put("skippedFiles", result.skippedFiles)
            put("skippedRows", result.skippedRows)
        }
        if (result.bucketFilesRead + result.bucketFilesPruned > 0) {
            put("bucketFilesRead", result.bucketFilesRead)
            put("bucketFilesPruned", result.bucketFilesPruned)
        }
        put("hits", JsonArray(result.hits.map { hit ->
            buildJsonObject {
                put("fate", hit.fate.name.lowercase())
                hit.note?.let { put("note", it) }
                hit.by?.let { put("by", it) }
                put("file", hit.filePath)
                hit.position?.let { put("position", it) }
                put("row", JsonObject(hit.cells.entries.associate { (k, v) -> k to JsonPrimitive(v?.toString()) }))
            }
        }))
        put("readErrors", JsonArray(result.filesRead.filter { it.error != null }.map { o ->
            buildJsonObject { put("file", o.filePath); put("error", o.error) }
        }))
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
    private val VALUED = setOf("at", "depth", "format", "out", "page-size", "where", "zorder")

    private fun usageError(err: PrintStream, message: String): Int {
        err.println("icelens: $message")
        err.print(USAGE)
        return EXIT_USAGE
    }
}
