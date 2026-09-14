package model

import service.GraphLayoutService
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scan's two stages on Iceberg, held to the plans Iceberg itself made.
 *
 * `docs/fixtures/iceberg-scan-plans.scala` runs `table.newScan().filter(expr).planFiles()` over
 * the checked-in tables and prints the data files each plan opens; [ICEBERG_PLANS] is that
 * output. Every other Iceberg pruning test reads its expectation off the script that wrote the
 * table, which is an oracle for the rows and not for the planner — `ManifestEvaluator` over the
 * partition summaries and `InclusiveMetricsEvaluator` over the file bounds are what
 * `evaluateScan` reproduces, and a file this skips that Iceberg opens is the one pruning bug
 * that loses rows. The `no filter` plan is each table's live set, which the other cases are
 * judged over; a file the plan opens must be "would be read" here, whatever else is. Reading
 * more than Iceberg would be allowed — a proof declined is not a wrong skip — and as it stands
 * no case does: all 48 agree with Iceberg's plan file for file, which the second assertion pins
 * so a rule that stops proving something it proved is seen, with the case named.
 */
class IcebergScanPlanTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun graphOf(fixture: String): GraphModel = GraphLayoutService.layoutGraph(
        UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/$fixture").absolutePath)),
        showRows = false,
    )

    private fun opened(fate: FileFate?) = fate == FileFate.WOULD_BE_READ || fate == FileFate.UNEVALUATED

    private fun nameOf(node: GraphNode.FileNode) = node.data.filePath?.substringAfterLast('/') ?: ""

    @Test
    fun `no filter skips a file Iceberg's own plan opens`() {
        val graphs = ICEBERG_PLANS.map { it.fixture }.distinct().associateWith { graphOf(it) }
        val live = ICEBERG_PLANS.filter { it.filter == "no filter" }.associate { it.fixture to it.opened }
        val exact = mutableListOf<String>()
        val wider = mutableListOf<String>()
        for (case in ICEBERG_PLANS.filter { it.filter != "no filter" }) {
            val graph = graphs.getValue(case.fixture)
            val parsed = parseScanFilter(case.filter)
            assertTrue(parsed is ScanFilterParse.Parsed, "${case.fixture}: ${case.filter} — $parsed")
            val plan = evaluateScan(graph, parsed.filter)
            val liveNodes = graph.nodes.filterIsInstance<GraphNode.FileNode>()
                .filter { (it.data.content ?: 0) == 0 && nameOf(it) in live.getValue(case.fixture) }
            assertEquals(live.getValue(case.fixture), liveNodes.map { nameOf(it) }.toSet(), "${case.fixture}: the drawn live files")
            val read = liveNodes.filter { opened(plan.files[it.id]?.fate) }.map { nameOf(it) }.toSet()
            val wrongSkips = case.opened - read
            assertTrue(
                wrongSkips.isEmpty(),
                "${case.fixture}: ${case.filter} skips ${wrongSkips.size} file(s) Iceberg opens — " +
                    liveNodes.associate { nameOf(it) to plan.files[it.id]?.fate },
            )
            if (read == case.opened) exact += "${case.fixture}: ${case.filter}" else wider += "${case.fixture}: ${case.filter} reads ${read.size} where Iceberg reads ${case.opened.size}"
        }
        // And the plans agree exactly, every one: the count is pinned so a rule that starts
        // declining a proof it used to make is seen, and a case that reads more names itself.
        assertEquals(
            ICEBERG_PLANS.count { it.filter != "no filter" }, exact.size,
            "cases reading more than Iceberg's plan:\n${wider.joinToString("\n")}",
        )
    }

    @Test
    fun `the plan with no filter is the live set the graph draws`() {
        for (case in ICEBERG_PLANS.filter { it.filter == "no filter" }) {
            val graph = graphOf(case.fixture)
            val table = graph.nodes.filterIsInstance<GraphNode.TableNode>().single()
            val current = graph.nodeById["snap_${table.summary.currentSnapshotId}"] as GraphNode.SnapshotNode
            val liveNames = current.liveFiles?.filter { it.content == 0 }?.map { it.path.substringAfterLast('/') }?.toSet()
            assertEquals(case.opened, liveNames, case.fixture)
        }
    }
}

/** One plan Iceberg made: the table, the filter as this app's clause editor spells it, the data files opened. */
class IcebergPlanCase(val fixture: String, val filter: String, val opened: Set<String>)

private fun Case(fixture: String, filter: String, opened: Set<String>) = IcebergPlanCase(fixture, filter, opened)

/** `iceberg-scan-plans.scala`, run 2026-09-14 on Iceberg 1.8.1 — the file names each plan printed. */
val ICEBERG_PLANS: List<IcebergPlanCase> = listOf(
    Case("parted", "no filter", setOf("00000-2-01dcc36f-1af9-45c9-b0fa-491db9c64f3f-0-00001.parquet", "00000-2-01dcc36f-1af9-45c9-b0fa-491db9c64f3f-0-00002.parquet", "00000-2-01dcc36f-1af9-45c9-b0fa-491db9c64f3f-0-00003.parquet", "00000-4-08c9aa36-9136-4172-b2b8-7347018f6a82-0-00001.parquet")),
    Case("parted", "name = 'alpha'", setOf("00000-2-01dcc36f-1af9-45c9-b0fa-491db9c64f3f-0-00001.parquet", "00000-4-08c9aa36-9136-4172-b2b8-7347018f6a82-0-00001.parquet")),
    Case("parted", "amount < 0", setOf("00000-4-08c9aa36-9136-4172-b2b8-7347018f6a82-0-00001.parquet")),
    Case("parted", "id = 3", setOf("00000-2-01dcc36f-1af9-45c9-b0fa-491db9c64f3f-0-00003.parquet")),
    Case("parted", "id IN (1, 2)", setOf("00000-2-01dcc36f-1af9-45c9-b0fa-491db9c64f3f-0-00001.parquet", "00000-2-01dcc36f-1af9-45c9-b0fa-491db9c64f3f-0-00002.parquet")),
    Case("parted", "id > 2", setOf("00000-2-01dcc36f-1af9-45c9-b0fa-491db9c64f3f-0-00003.parquet", "00000-4-08c9aa36-9136-4172-b2b8-7347018f6a82-0-00001.parquet")),
    Case("parted", "name LIKE 'br%'", setOf("00000-2-01dcc36f-1af9-45c9-b0fa-491db9c64f3f-0-00002.parquet")),
    Case("parted", "name LIKE 'alp%'", setOf("00000-2-01dcc36f-1af9-45c9-b0fa-491db9c64f3f-0-00001.parquet", "00000-4-08c9aa36-9136-4172-b2b8-7347018f6a82-0-00001.parquet")),
    Case("parted", "d = '2024-03-05'", setOf("00000-2-01dcc36f-1af9-45c9-b0fa-491db9c64f3f-0-00001.parquet", "00000-4-08c9aa36-9136-4172-b2b8-7347018f6a82-0-00001.parquet")),
    Case("parted", "d < '1970-01-01'", setOf("00000-2-01dcc36f-1af9-45c9-b0fa-491db9c64f3f-0-00003.parquet")),
    Case("parted", "ts_h >= '2024-03-05 07:00:00'", setOf("00000-2-01dcc36f-1af9-45c9-b0fa-491db9c64f3f-0-00001.parquet", "00000-2-01dcc36f-1af9-45c9-b0fa-491db9c64f3f-0-00002.parquet", "00000-4-08c9aa36-9136-4172-b2b8-7347018f6a82-0-00001.parquet")),
    Case("parted", "ts_m = '2024-03-31 07:15:00'", setOf("00000-4-08c9aa36-9136-4172-b2b8-7347018f6a82-0-00001.parquet")),
    Case("parted", "ts_y < '2000-01-01 00:00:00'", setOf("00000-2-01dcc36f-1af9-45c9-b0fa-491db9c64f3f-0-00003.parquet")),
    Case("parted", "NOT (name = 'alpha')", setOf("00000-2-01dcc36f-1af9-45c9-b0fa-491db9c64f3f-0-00002.parquet", "00000-2-01dcc36f-1af9-45c9-b0fa-491db9c64f3f-0-00003.parquet")),
    Case("parted", "name = 'alpha' OR id = 2", setOf("00000-2-01dcc36f-1af9-45c9-b0fa-491db9c64f3f-0-00001.parquet", "00000-2-01dcc36f-1af9-45c9-b0fa-491db9c64f3f-0-00002.parquet", "00000-4-08c9aa36-9136-4172-b2b8-7347018f6a82-0-00001.parquet")),
    Case("parted", "name IS NULL", setOf()),
    Case("parted", "name IS NOT NULL", setOf("00000-2-01dcc36f-1af9-45c9-b0fa-491db9c64f3f-0-00001.parquet", "00000-2-01dcc36f-1af9-45c9-b0fa-491db9c64f3f-0-00002.parquet", "00000-2-01dcc36f-1af9-45c9-b0fa-491db9c64f3f-0-00003.parquet", "00000-4-08c9aa36-9136-4172-b2b8-7347018f6a82-0-00001.parquet")),
    Case("parted", "amount BETWEEN 0 AND 100", setOf("00000-2-01dcc36f-1af9-45c9-b0fa-491db9c64f3f-0-00001.parquet", "00000-2-01dcc36f-1af9-45c9-b0fa-491db9c64f3f-0-00002.parquet")),
    Case("parted", "name = 'alpha' AND amount > 0", setOf("00000-2-01dcc36f-1af9-45c9-b0fa-491db9c64f3f-0-00001.parquet")),
    Case("parted", "name NOT IN ('alpha', 'bravo')", setOf("00000-2-01dcc36f-1af9-45c9-b0fa-491db9c64f3f-0-00003.parquet")),
    Case("parted", "name = 'zulu'", setOf()),
    Case("respec", "no filter", setOf("00000-1-20f66297-7efb-488b-89aa-0fdbbeabf87f-0-00001.parquet", "00000-1-20f66297-7efb-488b-89aa-0fdbbeabf87f-0-00002.parquet", "00000-3-76c3ef4b-3349-4a15-baf1-c081c83b8eb5-0-00001.parquet", "00000-3-76c3ef4b-3349-4a15-baf1-c081c83b8eb5-0-00002.parquet")),
    Case("respec", "name = 'alpha'", setOf("00000-1-20f66297-7efb-488b-89aa-0fdbbeabf87f-0-00001.parquet")),
    Case("respec", "id = 3", setOf("00000-3-76c3ef4b-3349-4a15-baf1-c081c83b8eb5-0-00002.parquet")),
    Case("respec", "id = 1", setOf("00000-1-20f66297-7efb-488b-89aa-0fdbbeabf87f-0-00001.parquet")),
    Case("respec", "d = '2024-03-06'", setOf("00000-1-20f66297-7efb-488b-89aa-0fdbbeabf87f-0-00002.parquet")),
    Case("respec", "d >= '2025-01-01'", setOf("00000-3-76c3ef4b-3349-4a15-baf1-c081c83b8eb5-0-00002.parquet")),
    Case("respec", "d < '1970-01-01'", setOf("00000-3-76c3ef4b-3349-4a15-baf1-c081c83b8eb5-0-00001.parquet")),
    Case("evolved", "no filter", setOf("00000-0-a6c9cccf-8be8-4bf0-b8b2-e04615ebabbb-0-00001.parquet", "00000-1-8796d368-d573-43ff-8e88-77d8d19fd2b0-0-00001.parquet", "00000-2-d97e21b7-7c26-4140-8572-bec8986b8299-0-00001.parquet")),
    Case("evolved", "id > 1000", setOf("00000-1-8796d368-d573-43ff-8e88-77d8d19fd2b0-0-00001.parquet", "00000-2-d97e21b7-7c26-4140-8572-bec8986b8299-0-00001.parquet")),
    Case("evolved", "id = 2", setOf("00000-0-a6c9cccf-8be8-4bf0-b8b2-e04615ebabbb-0-00001.parquet")),
    Case("evolved", "amount < 2", setOf("00000-0-a6c9cccf-8be8-4bf0-b8b2-e04615ebabbb-0-00001.parquet")),
    Case("evolved", "note = 'fifth'", setOf("00000-0-a6c9cccf-8be8-4bf0-b8b2-e04615ebabbb-0-00001.parquet", "00000-2-d97e21b7-7c26-4140-8572-bec8986b8299-0-00001.parquet")),
    Case("evolved", "note IS NULL", setOf("00000-0-a6c9cccf-8be8-4bf0-b8b2-e04615ebabbb-0-00001.parquet")),
    // eqren: `name` renamed to `label` after two files were written — the filter binds to field 2
    // and prunes files whose manifest still calls it `name`
    Case("eqren", "no filter", setOf("00000-0-06075099-35fc-447c-a728-a46639430370-0-00001.parquet", "00000-0-533395c9-97e2-418a-a9cd-2005e4a43e6e-0-00001.parquet", "00000-1-590e6e55-db01-4573-bfef-721a3ffbc54d-0-00001.parquet")),
    Case("eqren", "label = 'alpha'", setOf("00000-0-06075099-35fc-447c-a728-a46639430370-0-00001.parquet")),
    Case("eqren", "label = 'bravo'", setOf("00000-0-06075099-35fc-447c-a728-a46639430370-0-00001.parquet", "00000-0-533395c9-97e2-418a-a9cd-2005e4a43e6e-0-00001.parquet")),
    Case("eqren", "label > 'f'", setOf("00000-1-590e6e55-db01-4573-bfef-721a3ffbc54d-0-00001.parquet")),
    Case("eqren", "label IS NULL", setOf()),
    // deep: bounds per leaf id, a filter naming the leaf by its path; `addr.city` became
    // `addr.town` and `addr.country` was added after the first file, which records no stats for it
    Case("deep", "no filter", setOf("00000-0-0248dde9-6dcb-4e95-972d-38314364f440-0-00001.parquet", "00000-1-f5bffcac-dc11-4c99-9c5e-3fd644479cef-0-00001.parquet")),
    Case("deep", "addr.town = 'Izmir'", setOf("00000-1-f5bffcac-dc11-4c99-9c5e-3fd644479cef-0-00001.parquet")),
    Case("deep", "addr.town = 'Ankara'", setOf("00000-0-0248dde9-6dcb-4e95-972d-38314364f440-0-00001.parquet")),
    Case("deep", "addr.zip < 7000", setOf("00000-0-0248dde9-6dcb-4e95-972d-38314364f440-0-00001.parquet")),
    Case("deep", "addr.country = 'TR'", setOf("00000-0-0248dde9-6dcb-4e95-972d-38314364f440-0-00001.parquet", "00000-1-f5bffcac-dc11-4c99-9c5e-3fd644479cef-0-00001.parquet")),
    Case("deep", "addr.country IS NULL", setOf("00000-0-0248dde9-6dcb-4e95-972d-38314364f440-0-00001.parquet")),
    Case("deep", "name = 'delta'", setOf("00000-1-f5bffcac-dc11-4c99-9c5e-3fd644479cef-0-00001.parquet")),
    Case("pstats", "no filter", setOf("00000-1-2cc41757-1f8d-4ef2-8688-0f1d5e578bd0-0-00001.parquet", "00000-1-2cc41757-1f8d-4ef2-8688-0f1d5e578bd0-0-00002.parquet", "00000-3-164ae112-35b3-43cc-9c8b-7004f2d5f39c-0-00001.parquet", "00000-3-164ae112-35b3-43cc-9c8b-7004f2d5f39c-0-00002.parquet")),
    Case("pstats", "p = 'eu'", setOf("00000-1-2cc41757-1f8d-4ef2-8688-0f1d5e578bd0-0-00002.parquet", "00000-3-164ae112-35b3-43cc-9c8b-7004f2d5f39c-0-00001.parquet")),
    Case("pstats", "p IN ('us', 'apac')", setOf("00000-1-2cc41757-1f8d-4ef2-8688-0f1d5e578bd0-0-00001.parquet", "00000-3-164ae112-35b3-43cc-9c8b-7004f2d5f39c-0-00002.parquet")),
    Case("pstats", "id > 3", setOf("00000-3-164ae112-35b3-43cc-9c8b-7004f2d5f39c-0-00001.parquet", "00000-3-164ae112-35b3-43cc-9c8b-7004f2d5f39c-0-00002.parquet")),
    Case("mor", "no filter", setOf("00000-8-8bb56de0-bda3-4465-be13-0a3f8fd26149-0-00001.parquet")),
    Case("mor", "id = 7", setOf("00000-8-8bb56de0-bda3-4465-be13-0a3f8fd26149-0-00001.parquet")),
    Case("mor", "id < 3", setOf("00000-8-8bb56de0-bda3-4465-be13-0a3f8fd26149-0-00001.parquet")),
)
