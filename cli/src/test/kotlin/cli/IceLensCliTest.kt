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
import model.ScanFilterParse
import model.UnifiedTableModel
import model.evaluateScan
import model.integrityReport
import model.forProcedure
import model.key
import model.maintenanceDetail
import model.maintenanceSummary
import model.planUnexistingFiles
import model.paimonRowLookupInput
import model.parseScanFilter
import model.readTableModel
import model.rowLookupInput
import model.sweepFileStats
import service.AggregationPolicy
import service.PaimonRowLookup
import service.RowLookup
import service.StatsCheckReader
import service.GraphLayoutService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
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
    private val orph = fixture("example/iceberg/default/orph")
    private val pe = fixture("example/paimon/db.db/pe")
    private val dvac = fixture("example/delta/dvac")
    private val pru = fixture("example/paimon/db.db/pru")
    private val br = fixture("example/paimon/db.db/br")
    private val dzo = fixture("example/delta/dzo")
    private val dcl = fixture("example/delta/dcl")
    private val dow = fixture("example/delta/dow")

    private class Run(val code: Int, val out: String, val err: String)

    private fun icelens(vararg args: String, input: String = ""): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = IceLensCli.run(
            args.toList(), PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8),
            ByteArrayInputStream(input.toByteArray(Charsets.UTF_8)),
        )
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
    fun `lookup prints RowLookup's hits and fates for Iceberg and Paimon, and holds them to the core function`() {
        // Iceberg: mor id = 5 is echo-updated and live; the CLI's rows are RowLookup.lookup's hits.
        val filter = (parseScanFilter("id = 5") as ScanFilterParse.Parsed).filter
        val model = readTableModel(Paths.get(mor)) as UnifiedTableModel
        val graph = graphOf(mor, AggregationPolicy.NONE)
        val ruledOut = evaluateScan(graph, filter).ruledOutFileKeys(graph)
        val core = RowLookup.lookup(requireNotNull(model.rowLookupInput()), filter, ruledOut)
        assertEquals(1, core.hits.size); assertEquals("live", core.hits.single().fate.label)

        val run = icelens("lookup", mor, "id = 5")
        assertEquals(IceLensCli.EXIT_OK, run.code, run.err)
        assertEquals("mor  Iceberg  $mor", run.out.lineSequence().first())
        core.hits.forEach { hit ->
            assertTrue(run.out.contains(hit.fate.label), "fate ${hit.fate.label} in\n${run.out}")
            assertTrue(run.out.contains("name=echo-updated"), run.out)
            assertTrue(run.out.contains(hit.filePath.substringAfterLast('/')), run.out)
        }
        assertTrue(run.out.contains("1 row matches, 1 live"), run.out)
        assertEquals("", run.err)

        // A quoted clause and an unquoted one are the same filter (the positionals are joined).
        assertEquals(run.out, icelens("lookup", mor, "id", "=", "5").out)

        // Paimon: lk v = 'b' is superseded, held to PaimonRowLookup.lookup.
        val lk = fixture("example/paimon/db.db/lk")
        val pFilter = (parseScanFilter("v = 'b'") as ScanFilterParse.Parsed).filter
        val pModel = readTableModel(Paths.get(lk)) as PaimonUnifiedTableModel
        val pGraph = graphOf(lk, AggregationPolicy.NONE)
        val pRuledOut = evaluateScan(pGraph, pFilter).ruledOutFileKeys(pGraph)
        val pCore = PaimonRowLookup.lookup(requireNotNull(pModel.paimonRowLookupInput()), pFilter, pRuledOut)
        assertEquals("superseded", pCore.hits.single().fate.label)
        val pRun = icelens("lookup", lk, "v = 'b'")
        assertEquals(IceLensCli.EXIT_OK, pRun.code, pRun.err)
        assertTrue(pRun.out.contains("superseded"), pRun.out)

        // Delta: the Iceberg lookup over the latest version, ddv's vectors deciding — 2 marked,
        // 1001's old row marked and its updated row live in the UPDATE's file.
        val dRun = icelens("lookup", fixture("example/delta/ddv"), "id IN (2, 1001)")
        assertEquals(IceLensCli.EXIT_OK, dRun.code, dRun.err)
        assertTrue(dRun.out.contains("3 rows match, 1 live, 2 not live"), dRun.out)
        assertTrue(dRun.out.contains("deleted by a vector") && dRun.out.contains("deletion_vector_"), dRun.out)

        // JSON: the same counts and one hit per core hit.
        val json = Json.parseToJsonElement(icelens("lookup", mor, "id = 5", "--json").out).jsonObject
        assertEquals(core.hits.size, json.getValue("matched").jsonPrimitive.content.toInt())
        assertEquals(core.live, json.getValue("live").jsonPrimitive.content.toInt())
        assertEquals("id = 5", json.getValue("filter").jsonPrimitive.content)
        val hit = json.getValue("hits").jsonArray.single().jsonObject
        assertEquals("live", hit.getValue("fate").jsonPrimitive.content)
        assertEquals("echo-updated", hit.getValue("row").jsonObject.getValue("name").jsonPrimitive.content)

        // A filter that does not parse points at the offset and is a usage error, not a crash.
        val bad = icelens("lookup", mor, "id = = 4")
        assertEquals(IceLensCli.EXIT_USAGE, bad.code)
        assertTrue(bad.err.contains("^"), bad.err)
        assertEquals("", bad.out)

        val noFilter = icelens("lookup", mor)
        assertEquals(IceLensCli.EXIT_USAGE, noFilter.code)
        assertTrue(noFilter.err.contains("lookup needs a filter"), noFilter.err)
    }

    @Test
    fun `plan prints maintenanceSummary with the orphan walk and the missing-file stat run, as of the time given`() {
        val at = "2099-01-01T00:00:00Z"
        val atMs = java.time.Instant.parse(at).toEpochMilli()
        for (table in listOf(mor, orph, pe, pru)) {
            val node = graphOf(table, AggregationPolicy.DEFAULT).nodes.filterIsInstance<GraphNode.TableNode>().first()
            val orphans = if (node.unreferencedFiles.isPresent) node.unreferencedFiles.value else null
            val unexisting = if (node.missingFiles.isPresent && node.paimonRowLookup.isPresent) {
                node.missingFiles.value?.let { r -> node.paimonRowLookup.value?.let { planUnexistingFiles(r, it) } }
            } else null
            val expected = maintenanceSummary(node, atMs, orphans, unexisting)
            val json = icelens("plan", table, "--at", at, "--json")
            assertEquals(0, json.code, json.err)
            val procedures = Json.parseToJsonElement(json.out).jsonObject["procedures"]!!.jsonArray.map { it.jsonObject }
            assertEquals(expected.map { listOf(it.procedure, it.verdict, it.detail, it.where) },
                procedures.map { p -> listOf("procedure", "verdict", "detail", "where").map { p[it]!!.jsonPrimitive.content } }, table)
            val text = icelens("plan", table, "--at", at)
            assertEquals(0, text.code, text.err)
            expected.forEach { assertTrue(text.out.lines().any { l -> it.procedure in l && it.verdict in l }, "$table: ${it.procedure}") }
        }
        // The two lines the desktop plans behind a click are planned here: orph's ten files the
        // procedure deleted, and pru's two missing files, neither of which the batch scan opens.
        val orphLine = Json.parseToJsonElement(icelens("plan", orph, "--at", at, "--json").out).jsonObject["procedures"]!!.jsonArray
            .map { it.jsonObject }.single { it["procedure"]!!.jsonPrimitive.content == "remove_orphan_files" }
        assertEquals("would delete 10 files", orphLine["verdict"]!!.jsonPrimitive.content)
        assertTrue(orphLine["acts"]!!.jsonPrimitive.boolean)
        assertTrue(icelens("plan", pru, "--at", at).out.lines().any { "remove_unexisting_files" in it && "would remove 2 entries" in it })
        // The same from a path relative to the working directory, which the model once rendered
        // as given on one side of that match and absolute on the other, and planned nothing.
        val relative = Paths.get("").toAbsolutePath().relativize(Paths.get(pru))
        assertTrue(icelens("plan", relative.toString(), "--at", at).out.lines().any { "remove_unexisting_files" in it && "would remove 2 entries" in it })
        // purge_files is the one Paimon line in the alert tone, marked `!` in the text.
        assertTrue(icelens("plan", pe, "--at", at).out.lines().any { it.startsWith("!") && "purge_files" in it })
        // Delta's three: VACUUM over the listing the desktop runs behind a click, a week and a day past
        // the files — dvaca's six — OPTIMIZE and the log cleanup from the log.
        val dvacNewest = java.nio.file.Files.walk(Path.of(dvac)).use { s -> s.filter { java.nio.file.Files.isRegularFile(it) }.mapToLong { java.nio.file.Files.getLastModifiedTime(it).toMillis() }.max().asLong }
        val deltaPlan = icelens("plan", dvac, "--at", (dvacNewest + 8L * 24 * 3_600_000).toString()).out.lines()
        assertTrue(deltaPlan.any { it.startsWith("*") && "VACUUM" in it && "would delete 6 files" in it }, deltaPlan.joinToString("\n"))
        assertTrue(deltaPlan.any { "OPTIMIZE" in it && "left alone" in it }, deltaPlan.joinToString("\n"))
        assertTrue(deltaPlan.any { "log cleanup" in it }, deltaPlan.joinToString("\n"))
        val bad = icelens("plan", mor, "--at", "yesterday")
        assertEquals(2, bad.code)
        assertTrue("--at takes epoch milliseconds" in bad.err)
    }

    @Test
    fun `plan with a procedure prints maintenanceDetail's notes and tables, the same as JSON, and refuses one the table does not plan`() {
        val at = "2099-01-01T00:00:00Z"
        val atMs = java.time.Instant.parse(at).toEpochMilli()
        for ((table, procedure) in listOf(br to "purge_files", mor to "expire_snapshots", orph to "remove_orphan_files", mor to "rewrite_data_files", dv to "sys.compact")) {
            val node = graphOf(table, AggregationPolicy.DEFAULT).nodes.filterIsInstance<GraphNode.TableNode>().first()
            val orphans = if (node.unreferencedFiles.isPresent) node.unreferencedFiles.value else null
            val line = maintenanceSummary(node, atMs, orphans).forProcedure(procedure)!!
            val expected = maintenanceDetail(node, line, atMs, orphans)
            assertTrue(expected.tables.isNotEmpty(), "$table $procedure plans no table")

            val text = icelens("plan", table, procedure, "--at", at)
            assertEquals(IceLensCli.EXIT_OK, text.code, text.err)
            val lines = text.out.lines()
            assertTrue(lines[1].startsWith("${line.procedure} as of $at:") && line.verdict in lines[1], lines[1])
            expected.notes.forEach { assertTrue("  $it" in lines, "$table: note $it") }
            expected.tables.forEach { t ->
                val at0 = lines.indexOf("${t.title} (${t.rows.size})")
                assertTrue(at0 > 0, "$table: ${t.title}")
                // The header, the rule under it, then one line per row, the cells in order.
                t.rows.forEachIndexed { i, row ->
                    val printed = lines[at0 + 3 + i]
                    var from = 0
                    row.forEach { cell -> from = printed.indexOf(cell, from).also { assertTrue(it >= 0, "$table ${t.title}: `$cell` in `$printed`") } + cell.length }
                }
            }

            val json = Json.parseToJsonElement(icelens("plan", table, procedure, "--at", at, "--json").out).jsonObject
            assertEquals(line.key, json["key"]!!.jsonPrimitive.content)
            assertEquals(expected.notes, json["notes"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(
                expected.tables.map { t -> t.title to t.rows.map { row -> t.headers.zip(row).toMap() } },
                json["tables"]!!.jsonArray.map { it.jsonObject }.map { t ->
                    t["title"]!!.jsonPrimitive.content to t["rows"]!!.jsonArray.map { r -> r.jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content } }
                },
                table,
            )
        }
        // --files is the one read a plan starts: the delete files rewrite-all would rewrite, opened.
        val unread = icelens("plan", mor, "rewrite_position_delete_files", "--at", at).out.lines()
        assertTrue(unread.none { it.startsWith("What rewrite-all writes back") }, unread.joinToString("\n"))
        val read = icelens("plan", mor, "rewrite_position_delete_files", "--at", at, "--files")
        assertEquals(IceLensCli.EXIT_OK, read.code, read.err)
        assertTrue("What rewrite-all writes back (3)" in read.out.lines(), read.out)
        // br's purge takes the seventeen files brp lacks, and the procedure is found by its sys. name too.
        assertTrue("Taken (17)" in icelens("plan", br, "sys.purge_files", "--at", at).out.lines())
        // The summary names every key the second argument takes.
        val keys = maintenanceSummary(graphOf(pe, AggregationPolicy.DEFAULT).nodes.filterIsInstance<GraphNode.TableNode>().first(), atMs).map { it.key }
        assertTrue(icelens("plan", pe, "--at", at).out.lines().last { it.isNotBlank() } == "icelens plan <table> <procedure> prints one in full: ${keys.joinToString(", ")}")
        // A procedure the table does not plan is a usage error that lists the ones it does.
        val bad = icelens("plan", mor, "vacuum", "--at", at)
        assertEquals(IceLensCli.EXIT_USAGE, bad.code)
        assertTrue("this table plans no `vacuum`; it plans rewrite_data_files" in bad.err, bad.err)
    }

    @Test
    fun `plan optimize --zorder plans a Delta ZORDER BY, refused on a clustered table, and is OPTIMIZE's alone`() {
        val z = icelens("plan", dzo, "optimize", "--zorder", "a,b")
        assertEquals(IceLensCli.EXIT_OK, z.code, z.err)
        assertTrue(z.out.lines().any { it.contains("ZORDER BY a, b, in place of the bare call above: Would rewrite 4 files") }, z.out)
        val refused = icelens("plan", dcl, "optimize", "--zorder", "a")
        assertEquals(IceLensCli.EXIT_OK, refused.code, refused.err)
        assertTrue(refused.out.contains("Refused: OPTIMIZE command for Delta table with clustering cannot specify ZORDER BY. Please remove ZORDER BY (a)."), refused.out)
        // The bare call on the clustered table is clustering, and at the latest version writes nothing.
        assertTrue(icelens("plan", dcl).out.lines().any { it.contains("nothing to do") && it.contains("liquid clustering by b") }, icelens("plan", dcl).out)
        assertEquals(IceLensCli.EXIT_USAGE, icelens("plan", dzo, "vacuum", "--zorder", "a").code)
        assertEquals(IceLensCli.EXIT_USAGE, icelens("plan", dzo, "--zorder", "a").code)
    }

    @Test
    fun `plan optimize --where narrows the plan to the partitions the clause matches, read as Spark SQL`() {
        val w = icelens("plan", dow, "optimize", "--where", "p IS NULL OR p = 'y'", "--zorder", "a")
        assertEquals(IceLensCli.EXIT_OK, w.code, w.err)
        assertTrue(w.out.lines().any { it.contains("WHERE p IS NULL OR p = 'y' ZORDER BY a, in place of the bare call above: Would rewrite 2 files") }, w.out)
        assertTrue(w.out.contains("WHERE p IS NULL OR p = 'y' keeps 2 live files of 4"), w.out)
        val refused = icelens("plan", dow, "optimize", "--where", "a = 1")
        assertEquals(IceLensCli.EXIT_OK, refused.code, refused.err)
        assertTrue(refused.out.contains("Refused: Predicate references non-partition column 'a'. Only the partition columns may be referenced: [p, d]"), refused.out)
        // An unquoted date is arithmetic to Spark: refused where it is typed, the caret under it.
        val unquoted = icelens("plan", dow, "optimize", "--where", "d >= 2024-03-06")
        assertEquals(IceLensCli.EXIT_USAGE, unquoted.code)
        assertTrue(unquoted.err.contains("quote it: '2024-03-06'") && unquoted.err.lines().any { it == "       ^" }, unquoted.err)
        assertEquals(IceLensCli.EXIT_USAGE, icelens("plan", dow, "vacuum", "--where", "p = 'x'").code)
        assertEquals(IceLensCli.EXIT_USAGE, icelens("plan", dow, "--where", "p = 'x'").code)
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
        assertTrue(notATable.err.contains("is not an Iceberg, Paimon or Delta table"), notATable.err)

        val notADirectory = icelens("check", fixture("README.md"))
        assertEquals(IceLensCli.EXIT_UNREADABLE, notADirectory.code)
        assertTrue(notADirectory.err.contains("is not a directory"), notADirectory.err)

        val version = icelens("version")
        assertEquals(IceLensCli.EXIT_OK, version.code)
        assertTrue(Regex("icelens \\d+\\.\\d+\\.\\d+\\s*").matches(version.out), version.out)
    }

    /**
     * The storage options are refused before anything is opened when they cannot mean anything,
     * and a secret is never read from an argument — so none of these needs a store to answer.
     */
    @Test
    fun `the storage options refuse what they cannot use, and a secret given as an argument is not echoed`() {
        fun refused(vararg args: String, input: String = "", says: String) {
            val run = icelens(*args, input = input)
            assertEquals(IceLensCli.EXIT_USAGE, run.code, run.out + run.err)
            assertTrue(says in run.err, run.err)
        }
        val url = "s3://warehouse/db/mor"
        val asArgument = icelens("summary", url, "--key-id", "AKIAEXAMPLE", "--secret", "hunter2-7f3a")
        assertEquals(IceLensCli.EXIT_USAGE, asArgument.code)
        assertTrue("never taken as an argument" in asArgument.err, asArgument.err)
        assertTrue("hunter2-7f3a" !in asArgument.out + asArgument.err, "the refusal repeats the secret")
        refused("summary", url, "--key-id", "AKIAEXAMPLE", says = "add --secret-stdin")
        refused("summary", url, "--secret-stdin", input = "s\n", says = "add --key-id")
        refused("summary", url, "--credential-chain", "--key-id", "AKIAEXAMPLE", "--secret-stdin", input = "s\n", says = "not both")
        refused("summary", url, "--url-style", "virtual", says = "is path or vhost")
        refused("summary", mor, "--endpoint", "127.0.0.1:9000", says = "is not an object-storage URL")
        refused("summary", url, "--key-id", "AKIAEXAMPLE", "--secret-stdin", input = "", says = "found no secret")
        refused("summary", url, "--key-id", "AKIAEXAMPLE", "--secret-stdin", input = "\n", says = "found no secret")
        refused("summary", url, "--hdfs-user", "hadoop", says = "is not a webhdfs:// or swebhdfs:// URL")
        refused("summary", "webhdfs://127.0.0.1:9870/warehouse/db/mor", "--endpoint", "127.0.0.1:9000", says = "give --hdfs-user NAME")
        refused("summary", "webhdfs://127.0.0.1:9870/warehouse/db/mor", "--hdfs-user", "", says = "needs the user name")
    }

    /**
     * A table in a bucket read with the options, against the same table on disk. Needs
     * `docs/fixtures/minio-lab.sh up`, which seeds `mor`, `dv` and `dplain` under
     * `s3://warehouse/db/`; skipped without it, as `RemoteTableTest` is.
     */
    @Test
    fun `a table in object storage opens with a key on standard input and prints what the local one prints`() {
        val lab = listOf("--endpoint", "127.0.0.1:9000", "--no-ssl", "--url-style", "path", "--region", "us-east-1")
        val key = lab + listOf("--key-id", "minioadmin", "--secret-stdin")
        val probe = icelens("summary", "s3://warehouse/db/mor", *key.toTypedArray(), input = "minioadmin\n")
        assumeTrue(probe.code == IceLensCli.EXIT_OK, "MinIO is not serving s3://warehouse/db — start it with docs/fixtures/minio-lab.sh up")

        fun remoteMatchesLocal(local: String, remote: String, vararg command: String) {
            val here = icelens(command[0], local, *command.drop(1).toTypedArray())
            val there = icelens(command[0], remote, *command.drop(1).toTypedArray(), *key.toTypedArray(), input = "minioadmin\n")
            assertEquals(here.code, there.code, there.err)
            assertEquals(here.out.replace(local, "<table>"), there.out.replace(remote, "<table>"), command.joinToString(" "))
        }
        remoteMatchesLocal(mor, "s3://warehouse/db/mor", "summary")
        remoteMatchesLocal(mor, "s3://warehouse/db/mor", "check")
        remoteMatchesLocal(dv, "s3://warehouse/db/dv", "tree")
        remoteMatchesLocal(fixture("example/delta/dplain"), "s3://warehouse/db/dplain", "lookup", "id = 2")

        val wrong = icelens("summary", "s3://warehouse/db/mor", *lab.toTypedArray(), "--key-id", "minioadmin", "--secret-stdin", input = "not-the-secret-9c1e\n")
        assertEquals(IceLensCli.EXIT_UNREADABLE, wrong.code, wrong.out)
        assertTrue("Access denied" in wrong.err, wrong.err)
        assertTrue("not-the-secret-9c1e" !in wrong.out + wrong.err, "the store's refusal repeats the secret")

        val closed = icelens("summary", "s3://warehouse/db/mor", "--endpoint", "127.0.0.1:1", "--no-ssl", "--url-style", "path", "--key-id", "minioadmin", "--secret-stdin", input = "minioadmin\n")
        assertEquals(IceLensCli.EXIT_UNREADABLE, closed.code, closed.out)
        assertTrue("Could not reach the store" in closed.err, closed.err)
    }

    /**
     * A table on HDFS read over WebHDFS, against the same table on disk. Needs
     * `docs/fixtures/hdfs-lab.sh up`, which seeds `mor`, `dv` and `dplain` under `/warehouse/db`
     * and a second `mor` under `/warehouse/private`, readable by `hadoop` alone; skipped without it.
     */
    @Test
    fun `a table on HDFS opens as a named user and prints what the local one prints`() {
        val hdfs = "webhdfs://127.0.0.1:9870/warehouse"
        val probe = icelens("summary", "$hdfs/db/mor", "--hdfs-user", "icelens")
        assumeTrue(probe.code == IceLensCli.EXIT_OK, "HDFS is not serving $hdfs/db — start it with docs/fixtures/hdfs-lab.sh up")

        fun remoteMatchesLocal(local: String, remote: String, vararg command: String) {
            val here = icelens(command[0], local, *command.drop(1).toTypedArray())
            val there = icelens(command[0], remote, *command.drop(1).toTypedArray(), "--hdfs-user", "icelens")
            assertEquals(here.code, there.code, there.err)
            assertEquals(here.out.replace(local, "<table>"), there.out.replace(remote, "<table>"), command.joinToString(" "))
        }
        remoteMatchesLocal(mor, "$hdfs/db/mor", "summary")
        // --files opens every live data file, which on HDFS is a local copy of each for DuckDB.
        remoteMatchesLocal(mor, "$hdfs/db/mor", "check", "--files")
        remoteMatchesLocal(dv, "$hdfs/db/dv", "tree")
        remoteMatchesLocal(fixture("example/delta/dplain"), "$hdfs/db/dplain", "lookup", "id = 2")

        val refused = icelens("summary", "$hdfs/private/mor", "--hdfs-user", "intruder")
        assertEquals(IceLensCli.EXIT_UNREADABLE, refused.code, refused.out)
        assertTrue("Permission denied" in refused.err && "'intruder'" in refused.err, refused.err)
        assertEquals(IceLensCli.EXIT_OK, icelens("summary", "$hdfs/private/mor", "--hdfs-user", "hadoop").code)

        val closed = icelens("summary", "webhdfs://127.0.0.1:1/warehouse/db/mor")
        assertEquals(IceLensCli.EXIT_UNREADABLE, closed.code, closed.out)
        assertTrue("Could not reach the namenode" in closed.err, closed.err)
    }
}
