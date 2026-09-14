package model

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `rewrite_table_path`, held to the runs in `docs/fixtures/rewrite-table-path.sql`: the
 * `file-list` CSV each run wrote and the files it staged, saved under
 * `core/src/test/resources/rewrite-table-path/`, against the plan on the checked-in table with
 * the same options. Seven runs — `mor` whole, from `v4`, and to `v3`; `eqdel`; `stats`;
 * `expired`; `extdata` under the warehouse prefix — and the refusals the runs recorded.
 */
class RewriteTablePathFixtureTest {

    private val resources = File(FixtureCatalog.repoRoot, "core/src/test/resources/rewrite-table-path")

    private fun recordedList(run: String): Set<Pair<String, String>> =
        File(resources, "$run.file-list.csv").readLines().filter { it.isNotBlank() }.map { it.substringBefore(",") to it.substringAfter(",") }.toSet()

    private fun recordedStaging(run: String): Set<String> = File(resources, "$run.staging.txt").readLines().filter { it.isNotBlank() }.toSet()

    private fun options(table: String, staging: String, start: String? = null, end: String? = null) = RewriteTablePathOptions(
        sourcePrefix = "/wh/default/$table", targetPrefix = "/copy/default/$table",
        startVersion = start, endVersion = end, stagingLocation = "/wh/staging/$staging",
    )

    private fun heldToRun(table: String, run: String, options: RewriteTablePathOptions) {
        val plan = FixtureCatalog.icebergModel(table).planRewriteTablePath(options)
        assertNull(plan.refusal, "$run: ${plan.refusal}")
        assertEquals(recordedList(run), plan.copies.map { it.from to it.to }.toSet(), run)
        assertEquals(plan.copies.size, plan.copies.map { it.from to it.to }.toSet().size, "$run: no pair twice")
        // What lands in staging is every staged source but the statistics file the action lists and never writes.
        val staged = plan.copies.filter { it.staged && it !in plan.notStaged }.map { it.from.removePrefix(plan.stagingDir) }.toSet()
        assertEquals(recordedStaging(run), staged, run)
    }

    @Test
    fun `the whole of mor - every version, list and manifest, the live files of every manifest, the position deletes rewritten`() {
        val plan = FixtureCatalog.icebergModel("mor").planRewriteTablePath(options("mor", "mor"))
        heldToRun("mor", "mor", options("mor", "mor"))
        assertEquals("v7.metadata.json", plan.endVersion)
        assertEquals((7 downTo 1).map { "v$it.metadata.json" }, plan.versions)
        assertEquals(6, plan.snapshotIds.size)
        assertEquals(10, plan.manifests.size, "all_manifests of v7: every manifest any of its six snapshots lists")
        assertEquals(6, plan.copiesOf(RewriteTablePathCopyKind.DATA_FILE).size, "every data file live in any rewritten manifest — the two the compaction removed included")
        assertEquals(3, plan.copiesOf(RewriteTablePathCopyKind.POSITION_DELETE).size)
        assertTrue(plan.copiesOf(RewriteTablePathCopyKind.POSITION_DELETE).all { it.staged && it.from.startsWith("/wh/staging/mor/") })
        assertTrue(plan.copiesOf(RewriteTablePathCopyKind.DATA_FILE).none { it.staged })
        assertEquals(emptyList(), plan.notStaged)
    }

    @Test
    fun `a start version keeps its own snapshots and manifests out, and an end version stops there`() {
        heldToRun("mor", "mor-from-v4", options("mor", "mor-from-v4", start = "v4.metadata.json"))
        val from = FixtureCatalog.icebergModel("mor").planRewriteTablePath(options("mor", "mor-from-v4", start = "v4.metadata.json"))
        assertEquals(listOf("v7.metadata.json", "v6.metadata.json", "v5.metadata.json"), from.versions)
        assertEquals(3, from.snapshotIds.size)
        assertEquals(7, from.manifests.size, "the manifests added by the three snapshots v4 lacks")
        assertEquals(2, from.copiesOf(RewriteTablePathCopyKind.DATA_FILE).size)
        heldToRun("mor", "mor-to-v3", options("mor", "mor-to-v3", end = "v3.metadata.json"))
        val to = FixtureCatalog.icebergModel("mor").planRewriteTablePath(options("mor", "mor-to-v3", end = "v3.metadata.json"))
        assertEquals("v3.metadata.json", to.endVersion)
        assertEquals(listOf("v3.metadata.json", "v2.metadata.json", "v1.metadata.json"), to.versions)
        assertEquals(2, to.snapshotIds.size)
        assertEquals(4, to.copiesOf(RewriteTablePathCopyKind.DATA_FILE).size)
    }

    @Test
    fun `an equality delete is copied as it is, a statistics file is listed and never staged, an expired table keeps every version`() {
        heldToRun("eqdel", "eqdel", options("eqdel", "eqdel"))
        val eqdel = FixtureCatalog.icebergModel("eqdel").planRewriteTablePath(options("eqdel", "eqdel"))
        assertEquals(1, eqdel.copiesOf(RewriteTablePathCopyKind.EQUALITY_DELETE).size)
        assertTrue(eqdel.copiesOf(RewriteTablePathCopyKind.EQUALITY_DELETE).single().let { !it.staged && it.from == "/wh/default/eqdel/data/00000-eq-deletes.parquet" })
        assertEquals(1, eqdel.copiesOf(RewriteTablePathCopyKind.POSITION_DELETE).size)
        heldToRun("stats", "stats", options("stats", "stats"))
        val stats = FixtureCatalog.icebergModel("stats").planRewriteTablePath(options("stats", "stats"))
        assertEquals(1, stats.notStaged.size)
        assertEquals(RewriteTablePathCopyKind.STATISTICS, stats.notStaged.single().kind)
        assertTrue(stats.notStaged.single().from.startsWith("/wh/staging/stats/") && stats.notStaged.single().from.endsWith(".stats"))
        heldToRun("expired", "expired", options("expired", "expired"))
        val expired = FixtureCatalog.icebergModel("expired").planRewriteTablePath(options("expired", "expired"))
        assertEquals(6, expired.versions.size, "every version in the log, the ones naming expired snapshots included")
        assertEquals(1, expired.snapshotIds.size, "one retained snapshot, one list")
        assertEquals(3, expired.manifests.size)
    }

    @Test
    fun `a write-data-path table is refused under the table prefix and moves under the warehouse prefix`() {
        val extdata = FixtureCatalog.icebergModel("extdata")
        val refused = extdata.planRewriteTablePath(options("extdata", "extdata"))
        assertEquals("Path /wh/extdata-files does not start with /wh/default/extdata/", refused.refusal)
        val whole = RewriteTablePathOptions("/wh", "/copy", stagingLocation = "/wh/staging/extdata2")
        val plan = extdata.planRewriteTablePath(whole)
        assertNull(plan.refusal)
        assertEquals(recordedList("extdata2"), plan.copies.map { it.from to it.to }.toSet())
        assertEquals(recordedStaging("extdata2"), plan.copies.filter { it.staged }.map { it.from.removePrefix(plan.stagingDir) }.toSet())
        assertTrue(plan.copiesOf(RewriteTablePathCopyKind.DATA_FILE).all { it.to.startsWith("/copy/extdata-files/") })
    }

    @Test
    fun `the other refusals - partition statistics, a deletion vector, the same prefix, a prefix nothing is under, a version not in the log`() {
        val mor = FixtureCatalog.icebergModel("mor")
        assertEquals("Partition statistics files are not supported yet.", FixtureCatalog.icebergModel("pstats").planRewriteTablePath(options("pstats", "pstats")).refusal)
        assertEquals("Content offset is required for DV", FixtureCatalog.icebergModel("v3").planRewriteTablePath(options("v3", "v3")).refusal)
        assertEquals("Source prefix cannot be the same as target prefix (/wh/default/mor)", mor.planRewriteTablePath(RewriteTablePathOptions("/wh/default/mor", "/wh/default/mor")).refusal)
        assertEquals(
            "Path /wh/default/mor/metadata/snap-2479080604789318646-1-14465ee4-06ff-4a51-a2f4-09545c681239.avro does not start with /other/",
            mor.planRewriteTablePath(RewriteTablePathOptions("/other", "/copy/default/mor", stagingLocation = "/wh/staging/mor-bad")).refusal,
        )
        assertEquals("Cannot find provided version file v9.metadata.json in metadata log.", mor.planRewriteTablePath(options("mor", "x", end = "v9.metadata.json")).refusal)
        assertEquals("Cannot find provided version file v0.metadata.json in metadata log.", mor.planRewriteTablePath(options("mor", "x", start = "v0.metadata.json")).refusal)
        // The default staging directory sits under the table's metadata directory.
        assertEquals("/wh/default/mor/metadata/copy-table-staging-<uuid>/", mor.planRewriteTablePath(RewriteTablePathOptions("/wh/default/mor", "/copy/default/mor")).stagingDir)
    }

    @Test
    fun `every fixture plans under its own recorded location, or is refused for a reason the runs recorded`() {
        var planned = 0
        for (name in FixtureCatalog.iceberg) {
            val model = FixtureCatalog.icebergModel(name)
            val location = model.metadatas.last().metadata.location ?: continue
            val plan = model.planRewriteTablePath(RewriteTablePathOptions(location, "/copy/$name", stagingLocation = "/staging/$name"))
            if (plan.refusal == null) {
                planned++
                assertTrue(plan.copies.isNotEmpty(), name)
                assertTrue(plan.copies.all { it.to.startsWith("/copy/$name/") }, name)
                assertEquals(plan.versions.size, plan.copiesOf(RewriteTablePathCopyKind.VERSION_FILE).size, name)
            } else {
                assertTrue(
                    plan.refusal.startsWith("Partition statistics") || plan.refusal == "Content offset is required for DV" || plan.refusal.startsWith("Path ") || plan.refusal.startsWith("Encountered data file file:/wh/"),
                    "$name: ${plan.refusal}",
                )
            }
        }
        assertTrue(planned >= 30, "$planned tables planned")
        // migrated and migdeep: add_files registered their files by a file: URI outside the table, which no table prefix covers.
    }
}
