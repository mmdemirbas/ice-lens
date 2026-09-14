package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The next commit's base manifest list, held to the base list the next commit actually wrote.
 *
 * Every commit rebuilds the base list from the previous snapshot's base and delta manifests
 * through `ManifestFileMerger.merge`, so on every Paimon fixture and every branch, for every
 * pair of adjacent retained snapshots, the plan from the older one must land on the newer one's
 * base list: the manifests it keeps are exactly the older names the newer list still holds, and
 * the manifests it merges account for exactly the new names. `pmm` is the table built to merge,
 * at `manifest.merge-min-count = 5`; every other fixture pins the direction that never merges.
 */
class PaimonManifestMergeFixtureTest {

    private data class Line(val table: String, val branch: String?, val snapshots: List<PaimonUnifiedSnapshot>, val schemas: List<PaimonSchema>)

    private fun lines(): List<Line> = FixtureCatalog.paimon.flatMap { name ->
        val model = FixtureCatalog.paimonModel(name)
        listOf(Line(name, null, model.snapshots, model.schemas)) + model.branches.map { Line(name, it.name, it.snapshots, it.schemas) }
    }

    private fun optionsAt(line: Line, snapshot: PaimonUnifiedSnapshot): PaimonManifestMergeOptions {
        val schema = snapshot.schema ?: line.schemas.maxByOrNull { it.id ?: -1 }
        return PaimonManifestMergeOptions.forTable(schema?.options.orEmpty())
    }

    @Test
    fun `every adjacent pair of retained snapshots on every fixture lands where the plan says`() {
        var pairs = 0
        var merges = 0
        lines().forEach { line ->
            val byId = line.snapshots.filter { it.metadata.id != null }.sortedBy { it.metadata.id }
            byId.zipWithNext().forEach { (older, newer) ->
                if (newer.metadata.id != older.metadata.id!! + 1) return@forEach
                pairs++
                // A `sys.compact_manifest` commit is a COMPACT with an empty delta list that changes
                // nothing but the base list — `ad`'s vector-writing COMPACT has an empty delta too,
                // and a new index manifest. Such a snapshot is held to the procedure's plan instead.
                val manifestCompaction = newer.metadata.commitKind == "COMPACT" && newer.deltaManifests.isEmpty() &&
                    newer.metadata.indexManifest == older.metadata.indexManifest
                val plan = if (manifestCompaction) planPaimonManifestCompaction(older.manifestMergeInput(), optionsAt(line, newer))
                else planPaimonManifestMerge(older.manifestMergeInput(), optionsAt(line, newer))
                val where = "${line.table}${line.branch?.let { " on $it" } ?: ""}: snapshot ${older.metadata.id} → ${newer.metadata.id}"
                val inputNames = plan.input.map { it.name }.toSet()
                val newerBase = newer.baseManifests.map { paimonManifestKey(it) }
                assertEquals(plan.kept, newerBase.filter { it in inputNames }, "$where: the manifests kept")
                assertEquals(plan.outputCount, newerBase.size, "$where: the base list's length — ${plan.describe}")
                if (plan.mergedBins.isNotEmpty()) {
                    merges++
                    val written = newer.baseManifests.filter { paimonManifestKey(it) !in inputNames }
                    assertEquals(plan.mergedBins.sumOf { it.outputCount }, written.size, "$where: the merged manifests written")
                    if (plan.mergedBins.size == 1 && written.size == 1) {
                        assertEquals(plan.mergedBins.single().mergedEntries, written.single().entries.size, "$where: the merged manifest's entries")
                    }
                }
            }
        }
        assertTrue(pairs >= 120, "pairs checked: $pairs")
        assertTrue(merges >= 2, "merges seen: $merges — pmm should show two")
    }

    @Test
    fun `pmm merges its base list at five manifests, and the merge cancels a DELETE against the ADD it meets`() {
        val model = FixtureCatalog.paimonModel("pmm")
        val byId = model.snapshots.associateBy { it.metadata.id!! }
        assertEquals(listOf(0, 1, 2, 3, 4, 1, 2, 3, 4, 1, 2, 3), (1L..12L).map { byId.getValue(it).baseManifests.size })
        val options = PaimonManifestMergeOptions.forTable(model.schemas.single().options)
        assertEquals(5, options.mergeMinCount)
        // Snapshot 5's list is five manifests: the plan merges them into one holding five ADDs.
        val at5 = planPaimonManifestMerge(byId.getValue(5).manifestMergeInput(), options)
        assertEquals(PaimonManifestMergeKind.MINOR, at5.kind)
        assertEquals(1, at5.bins.size)
        assertEquals(5, at5.mergedBins.single().mergedEntries)
        assertEquals(5, byId.getValue(6).baseManifests.single().entries.size)
        // Snapshot 9's list is the merged manifest, the DELETE of the second file (a whole-file
        // delete removes the file rather than rewriting it) and three more ADDs: nine entries in,
        // seven out, the DELETE cancelling the ADD it meets.
        val at9 = planPaimonManifestMerge(byId.getValue(9).manifestMergeInput(), options)
        assertEquals(PaimonManifestMergeKind.MINOR, at9.kind)
        assertEquals(9, at9.mergedBins.single().manifests.sumOf { it.entries.size })
        assertEquals(7, at9.mergedBins.single().mergedEntries)
        assertEquals(7, byId.getValue(10).baseManifests.single().entries.size)
        assertEquals(0L, byId.getValue(10).baseManifests.single().metadata.numDeletedFiles)
        // Snapshot 6's list is two: kept, under the count.
        val at6 = planPaimonManifestMerge(byId.getValue(6).manifestMergeInput(), options)
        assertEquals(PaimonManifestMergeKind.NONE, at6.kind)
        assertEquals(listOf(2), at6.bins.map { it.manifests.size })
        assertEquals(2, byId.getValue(7).baseManifests.size)
    }

    @Test
    fun `sys_compact_manifest on pmm lands where pmma's snapshot 13 is, and a second call writes nothing`() {
        val before = FixtureCatalog.paimonModel("pmm")
        val after = FixtureCatalog.paimonModel("pmma")
        val options = PaimonManifestMergeOptions.forTable(before.schemas.single().options)
        val latest = before.snapshots.maxBy { it.metadata.id!! }
        assertEquals(12L, latest.metadata.id)
        // Four small manifests — a merged one of seven entries, three of one ADD — under the count of
        // five: the next commit keeps them, and the procedure rewrites all four into one of ten.
        assertEquals(PaimonManifestMergeKind.NONE, planPaimonManifestMerge(latest.manifestMergeInput(), options).kind)
        val plan = planPaimonManifestCompaction(latest.manifestMergeInput(), options)
        assertEquals(PaimonManifestMergeKind.FULL, plan.kind)
        assertTrue(plan.writesNewList)
        assertEquals(listOf(4), plan.mergedBins.map { it.manifests.size })
        assertEquals(10, plan.mergedBins.single().mergedEntries)
        assertEquals(emptyList(), plan.kept)
        assertEquals(1, plan.outputCount)
        val written = after.snapshots.single { it.metadata.id == 13L }
        assertEquals("COMPACT", written.metadata.commitKind)
        assertEquals(emptyList(), written.deltaManifests)
        assertEquals(listOf(10), written.baseManifests.map { it.entries.size })
        assertEquals(0L, written.baseManifests.single().metadata.numDeletedFiles)
        assertEquals(latest.metadata.totalRecordCount, written.metadata.totalRecordCount)
        assertEquals(0L, written.metadata.deltaRecordCount)
        // The second call in the script found the same set and wrote no snapshot 14.
        assertEquals(13L, after.snapshots.maxOf { it.metadata.id!! })
        val again = planPaimonManifestCompaction(written.manifestMergeInput(), options)
        assertTrue(!again.writesNewList, again.describeCompaction)
        assertEquals(listOf(written.baseManifests.single().let { paimonManifestKey(it) }), again.kept)
    }

    @Test
    fun `the bins close on size, a bin of one is kept, and a full compaction rewrites what a delete touches`() {
        fun m(name: String, size: Long, vararg entries: Pair<String, Boolean>) =
            PaimonManifestMergeInput(name, size, entries.count { !it.second }.toLong(), entries.count { it.second }.toLong(), entries.map { PaimonMergeEntry(it.first, "p", it.second) })
        val options = PaimonManifestMergeOptions(targetSizeBytes = 100, mergeMinCount = 3, fullCompactionThresholdBytes = 1_000)
        // Two bins close on size (60 + 50 ≥ 100; 70 + 40 ≥ 100) — the second folds to nothing and
        // writes nothing — and the leftover of one is kept alone.
        val sized = planPaimonManifestMerge(listOf(m("a", 60, "f1" to false), m("b", 50, "f2" to false), m("c", 70, "f3" to false), m("d", 40, "f3" to true), m("e", 10, "f5" to false)), options)
        assertEquals(PaimonManifestMergeKind.MINOR, sized.kind)
        assertEquals(listOf(true, true, false), sized.bins.map { it.merged })
        assertEquals(listOf(2, 0, null), sized.bins.map { it.mergedEntries })
        assertEquals(listOf("e"), sized.kept)
        assertEquals(2, sized.outputCount)
        // A DELETE meeting no ADD in its bin stays; meeting one, both go, and a bin folding to nothing writes nothing.
        val cancel = planPaimonManifestMerge(listOf(m("a", 10, "f1" to false), m("b", 10, "f1" to true), m("c", 10, "f2" to false)), options)
        assertEquals(1, cancel.mergedBins.single().mergedEntries)
        val nothing = planPaimonManifestMerge(listOf(m("a", 10, "f1" to false), m("b", 10, "f1" to true), m("c", 10, "f2" to true)), options)
        assertEquals(1, nothing.mergedBins.single().mergedEntries) // f2's DELETE met no ADD and stays
        val empty = planPaimonManifestMerge(listOf(m("a", 10, "f1" to false, "f2" to false), m("b", 10, "f1" to true), m("c", 10, "f2" to true)), options)
        assertEquals(0, empty.mergedBins.single().mergedEntries)
        assertEquals(0, empty.outputCount)
        // Full compaction: the two manifests under the target size sum past the threshold; of the
        // two at size, the one holding no deleted file is kept whole and the one that does is
        // rewritten with the DELETE and the ADD it cancels dropped.
        val full = planPaimonManifestMerge(
            listOf(m("big1", 800, "g1" to false), m("big2", 800, "g2" to false), m("s1", 600, "g3" to false), m("s2", 600, "g2" to true)),
            PaimonManifestMergeOptions(targetSizeBytes = 700, mergeMinCount = 30, fullCompactionThresholdBytes = 1_000),
        )
        assertEquals(PaimonManifestMergeKind.FULL, full.kind)
        assertEquals(listOf("big1"), full.kept)
        assertEquals(1, full.mergedBins.size)
        assertEquals(listOf("big2", "s1", "s2"), full.mergedBins.single().manifests.map { it.name })
        assertEquals(1, full.mergedBins.single().mergedEntries) // g3; g2's ADD and DELETE both dropped
    }

    @Test
    fun `a memory size parses the way Paimon parses it`() {
        assertEquals(8L * 1024 * 1024, parsePaimonMemorySize("8mb"))
        assertEquals(8L * 1024 * 1024, parsePaimonMemorySize("8 MB"))
        assertEquals(16L * 1024 * 1024, parsePaimonMemorySize("16m"))
        assertEquals(1024L, parsePaimonMemorySize("1 kb"))
        assertEquals(512L, parsePaimonMemorySize("512"))
        assertEquals(null, parsePaimonMemorySize("eight"))
    }
}
