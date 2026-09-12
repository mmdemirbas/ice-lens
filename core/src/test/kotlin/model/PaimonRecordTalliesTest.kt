package model

import service.GraphLayoutService
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every record count every Paimon snapshot file records — `totalRecordCount`, `deltaRecordCount`,
 * `changelogRecordCount` — against the manifests it names, across every checked-in table and
 * every branch. Nothing here produced a snapshot file, which is what makes it an oracle: the
 * replay ending on the wrong rows, a `_KIND` misread or a changelog list folded into the table
 * shows up as a disagreement with what Flink or Spark wrote.
 */
class PaimonRecordTalliesTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val fixtures = listOf("test", "dv", "cl", "tg", "pt", "ao", "br")

    private fun model(name: String) =
        PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/$name").absolutePath))

    @Test
    fun `every recorded record count agrees with the manifests`() {
        var compared = 0
        val disagreements = mutableListOf<String>()
        fixtures.forEach { name ->
            val m = model(name)
            // A snapshot with a read error names a list that could not be read, so its count is
            // short by construction — the `tg` tag below is that case, and it is asserted apart.
            (m.snapshots + m.tagOnlySnapshots + m.branches.flatMap { it.snapshots })
                .filter { it.readErrors.isEmpty() }
                .forEach { snapshot ->
                val live = paimonLiveFilesOf(snapshot).sumOf { it.recordCount }
                paimonRecordTallies(snapshot, live).forEach { tally ->
                    if (tally.recorded == null) return@forEach
                    compared++
                    if (tally.agrees != true) disagreements += "$name snapshot ${snapshot.metadata.id} ${tally.label}: recorded ${tally.recorded}, counted ${tally.counted}"
                }
            }
        }
        assertEquals(emptyList(), disagreements)
        // The corpus yields 72 comparisons; the bound is under that so adding a fixture does not
        // move it, and far enough above zero that a sweep reading nothing cannot pass.
        assertTrue(compared >= 60, "the corpus should yield dozens of comparisons, got $compared")
    }

    /**
     * The one disagreement in the corpus, and it is the format's: `tg`'s tag names a changelog
     * manifest list that `expire_snapshots` deleted, so the tag's `changelogRecordCount` of 3 has
     * nothing on disk to count against. The tally says NO beside a read error that says why,
     * which is the right answer — a reader who follows the tag to its change stream finds none.
     */
    @Test
    fun `a tag whose changelog expiry deleted disagrees on the changelog count and says why`() {
        val tagged = model("tg").tagOnlySnapshots.single()
        assertTrue(tagged.readErrors.any { it.stage == "changelog-manifest-list" }, "${tagged.readErrors}")
        val tallies = paimonRecordTallies(tagged, paimonLiveFilesOf(tagged).sumOf { it.recordCount })
        val changelog = tallies.single { it.label == "Changelog records" }
        assertEquals(3L, changelog.recorded)
        assertEquals(0L, changelog.counted)
        assertEquals(false, changelog.agrees)
        assertEquals(listOf(true, true), tallies.filter { it.label != "Changelog records" }.map { it.agrees }, "the data survived and tallies")
    }

    /**
     * The cases where the three readings separate, read off the fixtures' own scripts: a
     * compaction whose delta is negative and whose total does not move, an overwrite that removes
     * more than it adds, and an `ANALYZE` that touches nothing.
     */
    @Test
    fun `a compaction, an overwrite and an analyze tally as their scripts say`() {
        val dv = model("dv").snapshots.associateBy { it.metadata.id }
        val compaction = paimonRecordTallies(dv.getValue(6L), paimonLiveFilesOf(dv.getValue(6L)).sumOf { it.recordCount })
        assertEquals(listOf(1500L, -3L, 0L), compaction.map { it.counted }, "total, delta, changelog")
        assertEquals(listOf(true, true, true), compaction.map { it.agrees })

        val cl = model("cl").snapshots.associateBy { it.metadata.id }
        val overwrite = paimonRecordTallies(cl.getValue(4L), paimonLiveFilesOf(cl.getValue(4L)).sumOf { it.recordCount })
        assertEquals(listOf(2L, -4L, 0L), overwrite.map { it.counted })
        val analyze = paimonRecordTallies(cl.getValue(5L), paimonLiveFilesOf(cl.getValue(5L)).sumOf { it.recordCount })
        assertEquals(listOf(2L, 0L, 0L), analyze.map { it.counted })
        val append = paimonRecordTallies(cl.getValue(2L), paimonLiveFilesOf(cl.getValue(2L)).sumOf { it.recordCount })
        assertEquals(listOf(5L, 2L, 2L), append.map { it.counted }, "changelog-producer = input writes the delta's rows to the changelog too")
    }

    /** The node carries them deferred, and one replay serves both the tallies and the comparison. */
    @Test
    fun `every Paimon snapshot node carries its tallies`() {
        fixtures.forEach { name ->
            val nodes = GraphLayoutService.layoutGraph(model(name), showRows = false)
                .nodes.filterIsInstance<GraphNode.PaimonSnapshotNode>()
            assertTrue(nodes.isNotEmpty())
            nodes.forEach { node ->
                val tallies = node.recordTallies
                assertTrue(tallies != null && tallies.size == 3, "$name ${node.id}")
                assertEquals(node.liveFiles?.sumOf { it.recordCount }, tallies!!.first().counted, "$name ${node.id}: the total is the comparison's rows")
            }
        }
    }
}
