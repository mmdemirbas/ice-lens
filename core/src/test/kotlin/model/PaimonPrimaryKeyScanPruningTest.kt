package model

import service.GraphLayoutService
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The file stage of a Paimon primary-key table's scan, held to the plans Paimon itself made.
 *
 * `docs/fixtures/paimon-scan-plans.scala` runs `newReadBuilder().withFilter(…).newScan().plan()`
 * over the checked-in tables and prints the files each plan opens; the sets below are that
 * output. They are what a reading of `KeyValueFileStoreScan` predicts and could not settle: a
 * key predicate prunes per file, the whole filter per bucket, a bucket at several levels or with
 * a level-0 file is read whole when any file may match, `partial-update` and `aggregation`
 * never prune by value, and a deletion-vector table prunes per file with level 0 dropped. The
 * `no filter` plan is each table's live set, which is what the other cases are judged over.
 */
class PaimonPrimaryKeyScanPruningTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun graphOf(fixture: String): GraphModel = GraphLayoutService.layoutGraph(
        PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/$fixture").absolutePath)),
        showRows = false,
    )

    private class Case(val fixture: String, val filter: String, val opened: Set<String>)

    /** What Paimon's plan opened, by table and filter — `paimon-scan-plans.scala`, run 2026-09-14. */
    private val cases = listOf(
        Case("pc", "no filter", setOf("data-440cf6fe-f3d2-4028-8553-d6d43db8cf5a-0.parquet", "data-91a56f32-fe11-4b98-aafc-a79632c48623-0.parquet", "data-542001a6-5e55-4b63-86d7-32391fcfb16e-0.parquet")),
        // the level-5 file (keys 1..5) and two level-0 files (6, 7): a match in one opens all three
        Case("pc", "v = 'g'", setOf("data-440cf6fe-f3d2-4028-8553-d6d43db8cf5a-0.parquet", "data-91a56f32-fe11-4b98-aafc-a79632c48623-0.parquet", "data-542001a6-5e55-4b63-86d7-32391fcfb16e-0.parquet")),
        Case("pc", "v = 'z'", setOf()),
        Case("pc", "k = 7", setOf("data-542001a6-5e55-4b63-86d7-32391fcfb16e-0.parquet")),
        // the key stage leaves one file, which no longer overlaps anything, so its own bounds decide `v`
        Case("pc", "k = 7 AND v = 'a'", setOf()),
        Case("pc", "k = 3", setOf("data-440cf6fe-f3d2-4028-8553-d6d43db8cf5a-0.parquet")),
        // three files at levels 3, 4 and 5 — different levels overlap, so a value match anywhere opens all
        Case("lk", "no filter", setOf("data-c9fecb02-68f6-47ee-85d4-b42cb04c81ff-0.parquet", "data-9e1aa64e-90dd-4146-8589-558284b4d7fa-0.parquet", "data-c9d1e9d4-4e0f-47ef-9390-303168312a35-0.parquet")),
        Case("lk", "v = 'b'", setOf("data-c9fecb02-68f6-47ee-85d4-b42cb04c81ff-0.parquet", "data-9e1aa64e-90dd-4146-8589-558284b4d7fa-0.parquet", "data-c9d1e9d4-4e0f-47ef-9390-303168312a35-0.parquet")),
        Case("lk", "v = 'B'", setOf("data-c9fecb02-68f6-47ee-85d4-b42cb04c81ff-0.parquet", "data-9e1aa64e-90dd-4146-8589-558284b4d7fa-0.parquet", "data-c9d1e9d4-4e0f-47ef-9390-303168312a35-0.parquet")),
        Case("lk", "k = 4", setOf("data-9e1aa64e-90dd-4146-8589-558284b4d7fa-0.parquet")),
        // partial-update: never by value, still by key
        Case("pu", "no filter", setOf("data-99c9d107-0870-4135-b286-338aaa8a6c4c-0.parquet", "data-9a83376b-fd3a-4677-a9b2-c2c9a2425e4f-0.parquet", "data-a78a9e55-1e0d-4cf2-a0bf-65efeafdb974-0.parquet", "data-95e505c9-3414-4b68-bc78-9b032b403c64-0.parquet")),
        Case("pu", "a = 'zzz'", setOf("data-99c9d107-0870-4135-b286-338aaa8a6c4c-0.parquet", "data-9a83376b-fd3a-4677-a9b2-c2c9a2425e4f-0.parquet", "data-a78a9e55-1e0d-4cf2-a0bf-65efeafdb974-0.parquet", "data-95e505c9-3414-4b68-bc78-9b032b403c64-0.parquet")),
        Case("pu", "k = 1", setOf("data-99c9d107-0870-4135-b286-338aaa8a6c4c-0.parquet", "data-95e505c9-3414-4b68-bc78-9b032b403c64-0.parquet")),
        Case("ag", "no filter", setOf("data-eb754592-a94a-4886-9522-211d1b2d2238-0.parquet", "data-784f14a2-4a10-4a85-ad9d-271001c33fc6-0.parquet", "data-4b3212ca-442a-41de-9ff2-e8a09965fb45-0.parquet")),
        Case("ag", "latest = 'zzz'", setOf("data-eb754592-a94a-4886-9522-211d1b2d2238-0.parquet", "data-784f14a2-4a10-4a85-ad9d-271001c33fc6-0.parquet", "data-4b3212ca-442a-41de-9ff2-e8a09965fb45-0.parquet")),
        // deletion vectors: level 0 dropped from the plan, the rest pruned each on its own
        Case("dv", "no filter", setOf("data-14c31b3c-7fe8-4195-9702-ebe02bb6a8e0-0.parquet", "data-55edd615-45a1-4dac-aa0d-296dc71399b4-0.parquet")),
        Case("dv", "v = 'v2'", setOf("data-14c31b3c-7fe8-4195-9702-ebe02bb6a8e0-0.parquet")),
        Case("dv", "k = 1500", setOf("data-55edd615-45a1-4dac-aa0d-296dc71399b4-0.parquet")),
        Case("dv", "v = 'zzz'", setOf()),
        // stats-mode = none on v: no value bound to prune on, the key bound still there
        Case("sm", "no filter", setOf("data-b504dca4-198b-4191-82c4-03e08f1f6356-0.parquet")),
        Case("sm", "v = 'zzz'", setOf("data-b504dca4-198b-4191-82c4-03e08f1f6356-0.parquet")),
        Case("sm", "k = 99", setOf()),
        // partitioned, `k` the trimmed key; a two-file bucket at level 0 is skipped whole on `v`
        Case("pt", "no filter", setOf("data-783ed9fc-aa14-4175-813b-a954781ebe47-0.parquet", "data-23d56f02-c706-4f17-a6aa-2c07e95c282a-0.parquet", "data-aa4886f3-3f94-4209-a413-0a718d4e9ff4-0.parquet", "data-86810cb4-adeb-4b60-968a-2081768c6ed4-0.parquet", "data-5bfe465d-20c4-44d5-beb0-66f3e47741f1-0.parquet", "data-5cd37195-6938-4199-acb7-ce9b10081559-0.parquet", "data-69aee50c-75a2-4eaf-ac04-051b5d6eef57-0.parquet")),
        Case("pt", "k = 3", setOf("data-86810cb4-adeb-4b60-968a-2081768c6ed4-0.parquet")),
        Case("pt", "v = 'zzz'", setOf()),
    )

    private fun opened(fate: FileFate?) = fate == FileFate.WOULD_BE_READ || fate == FileFate.UNEVALUATED

    @Test
    fun `every filter opens the files Paimon's own plan opened, and no other live file`() {
        val graphs = cases.map { it.fixture }.distinct().associateWith { graphOf(it) }
        val live = cases.filter { it.filter == "no filter" }.associate { it.fixture to it.opened }
        for (case in cases.filter { it.filter != "no filter" }) {
            val graph = graphs.getValue(case.fixture)
            val filter = (parseScanFilter(case.filter) as ScanFilterParse.Parsed).filter
            val plan = evaluateScan(graph, filter)
            val liveNodes = graph.nodes.filterIsInstance<GraphNode.PaimonDataFileNode>()
                .filter { it.operationKind == PaimonEntryKind.ADD && it.entry.file?.fileName in live.getValue(case.fixture) }
            assertTrue(liveNodes.isNotEmpty(), case.fixture)
            val actual = liveNodes.filter { opened(plan.files[it.id]?.fate) }.mapNotNull { it.entry.file?.fileName }.toSet()
            assertEquals(case.opened, actual, "${case.fixture}: ${case.filter} — ${liveNodes.associate { it.entry.file?.fileName to plan.files[it.id] }}")
        }
    }

    /** The live set is what the graph draws as ADD entries of the latest snapshot's files — the plan with no filter. */
    @Test
    fun `the plan with no filter is the live set the graph draws, less level 0 where a read skips it`() {
        for (case in cases.filter { it.filter == "no filter" }) {
            val graph = graphOf(case.fixture)
            val input = requireNotNull(PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/${case.fixture}").absolutePath)).paimonRowLookupInput())
            assertEquals(case.opened, input.readFiles.map { it.fileName }.toSet(), case.fixture)
            assertTrue(graph.nodes.filterIsInstance<GraphNode.PaimonDataFileNode>().any { it.entry.file?.fileName in case.opened }, case.fixture)
        }
    }

    /**
     * `dv`'s live level-0 file — the DELETE's `-D` rows — is drawn, and a scan never opens it.
     * The level-0 entries of the two files the forced compactions moved up are drawn too, and
     * those are not live: the note says so, and the bucket's rule leaves them alone.
     */
    @Test
    fun `a level-0 file of a deletion-vector table is not read, whatever the filter`() {
        val graph = graphOf("dv")
        val plan = evaluateScan(graph, listOf(ScanPredicate("k", PredicateOp.EQ, "2")))
        val live = requireNotNull(graph.nodes.filterIsInstance<GraphNode.TableNode>().single().paimonRowLookup.value).files.map { it.fileName }.toSet()
        val levelZero = graph.nodes.filterIsInstance<GraphNode.PaimonDataFileNode>().filter { it.unreadByBatchRead }
        val (liveZero, goneZero) = levelZero.partition { it.operationKind == PaimonEntryKind.ADD && it.entry.file?.fileName in live }
        assertTrue(liveZero.isNotEmpty() && goneZero.isNotEmpty(), "live ${liveZero.size}, gone ${goneZero.size}")
        liveZero.forEach { assertEquals(FileFate.NOT_READ, plan.files[it.id]?.fate, it.entry.file?.fileName.orEmpty()) }
        goneZero.forEach { assertTrue(requireNotNull(plan.files[it.id]?.note).startsWith("not live in the latest snapshot"), it.entry.file?.fileName.orEmpty()) }
        assertEquals(liveZero.size, plan.unreadFiles)
        assertTrue(requireNotNull(plan.primaryKeyRule).contains("skip level 0"))
    }

    /**
     * The row a reader sees for a file its own bounds rule out but the bucket keeps: the fate
     * says read, and the note says why — the contradiction `summarise` would otherwise print.
     */
    @Test
    fun `a file read with its bucket carries the bucket's reason, and a skipped bucket its own`() {
        val pc = graphOf("pc")
        val filter = (parseScanFilter("v = 'g'") as ScanFilterParse.Parsed).filter
        val plan = evaluateScan(pc, filter)
        val levelFive = pc.nodes.filterIsInstance<GraphNode.PaimonDataFileNode>()
            .single { it.operationKind == PaimonEntryKind.ADD && it.entry.file?.fileName == "data-440cf6fe-f3d2-4028-8553-d6d43db8cf5a-0.parquet" }
        val result = requireNotNull(plan.files[levelFive.id])
        assertEquals(FileFate.WOULD_BE_READ, result.fate)
        assertTrue(result.outcomes.any { it.effect == TermEffect.SKIPS }, "its own bounds (a..e) rule 'g' out: $result")
        assertTrue(requireNotNull(result.note).startsWith("read with its bucket"), result.note.orEmpty())
        val none = evaluateScan(pc, (parseScanFilter("v = 'z'") as ScanFilterParse.Parsed).filter)
        assertEquals(FileFate.SKIPPED, none.files[levelFive.id]?.fate)
        assertTrue(requireNotNull(none.files[levelFive.id]?.note).contains("skipped whole"))
        assertTrue(requireNotNull(plan.primaryKeyRule).contains("per bucket"))
    }

    /** `pu`: a value predicate is not evaluated and says why; a key predicate still is. */
    @Test
    fun `a partial-update table's value bounds are not consulted and the outcome says so`() {
        val pu = graphOf("pu")
        val byValue = evaluateScan(pu, listOf(ScanPredicate("a", PredicateOp.EQ, "zzz")))
        val live = pu.nodes.filterIsInstance<GraphNode.PaimonDataFileNode>().filter { it.operationKind == PaimonEntryKind.ADD }
        live.forEach { node ->
            val result = requireNotNull(byValue.files[node.id])
            assertEquals(FileFate.UNEVALUATED, result.fate, node.entry.file?.fileName.orEmpty())
            assertTrue(result.outcomes.single().reason.startsWith("not consulted: under merge-engine = partial-update"), result.outcomes.single().reason)
        }
        assertTrue(requireNotNull(byValue.primaryKeyRule).contains("no value bound is consulted"))
        val byKey = evaluateScan(pu, listOf(ScanPredicate("k", PredicateOp.EQ, "1")))
        assertEquals(2, live.count { byKey.files[it.id]?.fate == FileFate.SKIPPED }, "two of the four files record no key 1")
    }
}
