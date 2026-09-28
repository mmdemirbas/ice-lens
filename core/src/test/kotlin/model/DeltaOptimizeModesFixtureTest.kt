package model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `OPTIMIZE`'s other two modes, held to the logs that ran them: every `ZORDER BY` of `dzo` and every
 * clustering `OPTIMIZE` of `dcl` against the plan at the version before it — the files it removed,
 * the bytes, the file count, and the vectors it counted — and `dcl`'s two runs that wrote no commit
 * against a plan of nothing. What one engine run cannot vary — the cube threshold, the packing
 * past the target, the statistics columns — is pinned on inputs built here.
 */
class DeltaOptimizeModesFixtureTest {

    /** The `zOrderBy` a commit records: a JSON list, written as a string. */
    private fun zOrderOf(commit: DeltaCommit): List<String> =
        (commit.commitInfo?.operationParameters?.get("zOrderBy") as? JsonPrimitive)?.contentOrNull
            ?.let { Json.parseToJsonElement(it).jsonArray.map { e -> e.jsonPrimitive.content } }
            .orEmpty()

    /** Each OPTIMIZE commit of [table] against the plan at the version before it; the versions checked. */
    private fun runsAgree(table: String, options: (Long) -> DeltaOptimizeOptions = { DeltaOptimizeOptions() }): List<Long> {
        val model = FixtureCatalog.deltaModel(table)
        val runs = model.commits.filter { it.commitInfo?.operation == "OPTIMIZE" }
        for (commit in runs) {
            val where = "$table v${commit.version}"
            val metrics = commit.commitInfo!!.operationMetrics!!
            val plan = model.planOptimize(zOrderOf(commit), options(commit.version), version = commit.version - 1).getOrThrow()
            assertNull(plan.refusal, where)
            assertEquals(commit.removes.map { it.key }.toSet(), plan.removed.map { it.add.key }.toSet(), where)
            assertEquals(metrics.getValue("numRemovedBytes").toLong(), plan.removedBytes, where)
            assertTrue(metrics.getValue("numAddedFiles").toInt() in plan.filesAdded, "$where: ${metrics["numAddedFiles"]} not in ${plan.filesAdded}")
            assertEquals(metrics.getValue("numDeletionVectorsRemoved").toInt(), plan.deletionVectorsCounted, where)
        }
        return runs.map { it.version }
    }

    @Test
    fun `every ZORDER BY on dzo removes what the plan at the version before it says, a lone file and a second run included`() {
        // v10 ran under optimize.maxFileSize = 300 bytes, which the log does not record.
        assertEquals(listOf(6L, 7L, 9L, 10L), runsAgree("dzo") { v -> if (v == 10L) DeltaOptimizeOptions(maxFileSize = 300) else DeltaOptimizeOptions() })
        val model = FixtureCatalog.deltaModel("dzo")
        val first = model.planOptimize(listOf("a", "b"), version = 5).getOrThrow()
        assertEquals(DeltaOptimizeMode.ZORDER, first.mode)
        // p=y holds one file and is rewritten all the same; each bin is one file under the default size.
        assertEquals(listOf(3, 1), first.bins.map { it.files.size })
        assertTrue(first.bins.all { it.rewritten })
        assertEquals(2..2, first.filesAdded)
        // Under 300 bytes p=x (905) asks for three and p=y (872) for two; the run wrote three and one.
        val small = model.planOptimize(listOf("a"), DeltaOptimizeOptions(maxFileSize = 300), version = 9).getOrThrow()
        assertEquals(listOf(3, 2), small.bins.map { it.outputFiles })
        assertEquals(2..5, small.filesAdded)
        assertEquals("rewritten into up to 3", small.bins.first().verdictText)
    }

    @Test
    fun `every clustering OPTIMIZE on dcl removes what the plan says, and the two that wrote nothing plan nothing`() {
        assertEquals(listOf(5L, 7L, 11L), runsAgree("dcl"))
        val model = FixtureCatalog.deltaModel("dcl")
        // The script's second OPTIMIZE ran at version 5 and its last at 11; neither wrote a commit.
        for (v in listOf(5L, 11L)) {
            val plan = model.planOptimize(version = v).getOrThrow()
            assertEquals(DeltaOptimizeMode.CLUSTERING, plan.mode, "v$v")
            assertFalse(plan.commits, "v$v: ${plan.headline}")
        }
        // At 5: one cube, no unclustered file.
        assertTrue(model.planOptimize(version = 5).getOrThrow().skipped.single().leftBecause!!.startsWith("in the only cube"))
        // At 6: the cube, far under the threshold, merged with the new file.
        val merge = model.planOptimize(version = 6).getOrThrow()
        assertEquals(listOf("a"), merge.columns)
        assertEquals(2, merge.removed.size)
        assertEquals(listOf("not clustered yet"), merge.removed.filter { it.add.zCube == null }.map { it.whyText })
        assertTrue(merge.removed.single { it.add.zCube != null }.whyText.contains("under the 100 GiB threshold"))
    }

    @Test
    fun `after CLUSTER BY b the cube clustered by a is left, and its vector is counted though no removed file carries one`() {
        val model = FixtureCatalog.deltaModel("dcl")
        val plan = model.planOptimize(version = 10).getOrThrow()
        assertEquals(listOf("b"), plan.columns)
        val left = plan.skipped.single()
        assertTrue(left.leftBecause!!.contains("clustered by a, not by b"), left.leftBecause)
        assertNotNull(left.add.deletionVector)
        // The new file alone, rewritten: a cube of one.
        assertEquals(1, plan.bins.single().files.size)
        assertTrue(plan.bins.single().rewritten)
        assertTrue(plan.removed.none { it.add.deletionVector != null })
        assertEquals(1, plan.deletionVectorsCounted)

        // The commit check counts the same, where counting the removes said 0 against a recorded 1.
        val commit = model.commitByVersion.getValue(11)
        val tally = deltaCommitTallies(commit, optimizeBefore = { model.planOptimizeBefore(commit) }).single { it.label == "numDeletionVectorsRemoved" }
        assertEquals(1, tally.recorded)
        assertEquals(1, tally.counted)
        // Without the plan the figure is not compared, rather than compared with the removes.
        assertTrue(deltaCommitTallies(commit).none { it.label == "numDeletionVectorsRemoved" })
        // Neither vector figure OPTIMIZE's run computes reaches the commit.
        assertTrue(commit.commitInfo!!.operationMetrics!!.keys.none { it.startsWith("numDeletionVectorRows") || it.startsWith("numDeletionVectorBytes") })
    }

    @Test
    fun `ZORDER BY is refused on a clustered table, a partition column, a missing column, and columns without statistics`() {
        val dcl = FixtureCatalog.deltaModel("dcl").planOptimize(listOf("a", "b")).getOrThrow()
        assertEquals("OPTIMIZE command for Delta table with clustering cannot specify ZORDER BY. Please remove ZORDER BY (a, b).", dcl.refusal)
        assertFalse(dcl.commits)
        val dzo = FixtureCatalog.deltaModel("dzo")
        assertEquals("p is a partition column. Z-Ordering can only be performed on data columns", dzo.planOptimize(listOf("a", "p")).getOrThrow().refusal)
        assertEquals("Z-Ordering column zz does not exist in data schema.", dzo.planOptimize(listOf("zz")).getOrThrow().refusal)
        assertNull(dzo.planOptimize(listOf("A", "b")).getOrThrow().refusal, "the resolver ignores case")

        val metadata = dzo.stateAt(dzo.latestVersion!!).getOrThrow().metadata!!
        fun refusal(config: Map<String, String>, columns: List<String>) =
            deltaZOrderRefusal(metadata.copy(configuration = metadata.configuration + config), null, columns)
        // The data schema is id, a, b — p is the partition column — so one indexed column is id.
        assertTrue(refusal(mapOf("delta.dataSkippingNumIndexedCols" to "1"), listOf("a", "b"))!!.startsWith("Z-Ordering on [a, b] will be ineffective"))
        assertTrue(refusal(mapOf("delta.dataSkippingStatsColumns" to "b"), listOf("a", "b"))!!.startsWith("Z-Ordering on [a] will be"))
        // A column that is not there is refused as missing before the statistics are asked.
        assertEquals("Z-Ordering column zz does not exist in data schema.", refusal(mapOf("delta.dataSkippingNumIndexedCols" to "0"), listOf("zz")))
    }

    @Test
    fun `the statistics columns count a struct's fields as leaves, and a named struct keeps every field under it`() {
        val schema = """{"type":"struct","fields":[
            {"name":"id","type":"integer","nullable":true,"metadata":{}},
            {"name":"s","type":{"type":"struct","fields":[
                {"name":"x","type":"integer","nullable":true,"metadata":{}},
                {"name":"y","type":"integer","nullable":true,"metadata":{}}]},"nullable":true,"metadata":{}},
            {"name":"z","type":"integer","nullable":true,"metadata":{}}]}"""
        fun paths(config: Map<String, String>) = deltaStatsColumnPaths(DeltaMetadata(schemaString = schema, configuration = config)).map { it.joinToString(".") }.toSet()
        assertEquals(setOf("id", "s", "s.x"), paths(mapOf("delta.dataSkippingNumIndexedCols" to "2")))
        assertEquals(setOf("id", "s", "s.x", "s.y", "z"), paths(emptyMap()))
        assertEquals(setOf("id", "s", "s.x", "s.y", "z"), paths(mapOf("delta.dataSkippingNumIndexedCols" to "-1")))
        assertEquals(setOf("s", "s.x", "s.y"), paths(mapOf("delta.dataSkippingStatsColumns" to "s")))
        assertEquals(setOf("s", "s.y", "z"), paths(mapOf("delta.dataSkippingStatsColumns" to "S.Y, z")))
        assertEquals(listOf("s", "x.y"), deltaColumnNameParts("s.`x.y`"))
    }

    private fun add(path: String, size: Long, cube: String? = null, columns: String = "[\"k\"]", provider: String? = if (cube != null) "liquid" else null, records: Long = 10, marked: Long? = null) = DeltaAddFile(
        path = path,
        size = size,
        stats = """{"numRecords":$records}""",
        tags = cube?.let { mapOf("ZCUBE_ID" to it, "ZCUBE_ZORDER_BY" to columns) },
        clusteringProvider = provider,
        deletionVector = marked?.let { DeltaDeletionVector(storageType = "u", pathOrInlineDv = "ab^-aqEH.-t@S}K{vb[*k^", offset = 1, sizeInBytes = 34, cardinality = it) },
    )

    private val cubeOptions = DeltaOptimizeOptions(maxFileSize = 1000, minCubeSize = 100, targetCubeSize = 150)

    @Test
    fun `clustering leaves a full cube, another provider's file and another columns' cube, and takes the unclustered first`() {
        val plan = planDeltaClustering(1, listOf(
            add("c2", 30, cube = "B"),
            add("full1", 60, cube = "A"),
            add("u1", 40),
            add("full2", 50, cube = "A"),
            add("other", 20, cube = "C", columns = "[\"j\"]"),
            add("prov", 10, provider = "zorder"),
        ), listOf("k"), cubeOptions)
        assertEquals(listOf(listOf("u1", "c2")), plan.bins.map { b -> b.files.map { it.add.path } })
        val left = plan.skipped.associate { it.add.path to it.leftBecause!! }
        assertEquals(setOf("full1", "full2", "other", "prov"), left.keys)
        assertTrue(left.getValue("full1").contains("at or over"), left["full1"])
        assertTrue(left.getValue("other").contains("clustered by j, not by k"), left["other"])
        assertTrue(left.getValue("prov").contains("another provider"), left["prov"])
    }

    @Test
    fun `a cube is sized by the rows its vectors leave, a lone cube with nothing unclustered is left, and cubes pack to the target`() {
        // 120 bytes with half the rows marked is 60 logical — under the threshold, so merged.
        val halved = planDeltaClustering(1, listOf(add("half", 120, cube = "A", marked = 5), add("u", 10)), listOf("k"), cubeOptions)
        assertEquals(setOf("half", "u"), halved.removed.map { it.add.path }.toSet())
        // The same cube alone has nothing to merge with.
        val lone = planDeltaClustering(1, listOf(add("half", 120, cube = "A", marked = 5)), listOf("k"), cubeOptions)
        assertFalse(lone.commits)
        // Two small cubes are not lone, and merge.
        assertEquals(2, planDeltaClustering(1, listOf(add("a", 10, cube = "A"), add("b", 10, cube = "B")), listOf("k"), cubeOptions).removed.size)
        // 100 then 60 would pass 150, so the second file opens a second cube; a lone file is rewritten.
        val packed = planDeltaClustering(1, listOf(add("x", 100), add("y", 60)), listOf("k"), cubeOptions)
        assertEquals(listOf(listOf("x"), listOf("y")), packed.bins.map { b -> b.files.map { it.add.path } })
        assertTrue(packed.bins.all { it.rewritten })
    }
}
