package service

import model.FixtureCatalog
import model.PaimonRowKind
import model.ScanFilter
import model.ScanFilterParse
import model.parseScanFilter
import model.paimonChangelogInputs
import model.paimonChangelogLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What each commit published for a key, read from the changelog files its snapshot names, held
 * to the two producers' scripts: `lk` (`changelog-producer = lookup`) publishes the change the
 * lookup computed — `-U (2, b)` and `+U (2, B)` for the second INSERT of key 2, `-D (3, c)` for
 * the DELETE — in the COMPACT commit after each APPEND; `cl` (`input`) publishes the input as it
 * arrived, so the same INSERT is a bare `+I (2, B)` and the DELETE a `-D`, in the APPEND itself.
 * Unfiltered, every snapshot's records are its own `changelogRecordCount`, the writer's figure.
 */
class PaimonChangelogFixtureTest {

    private fun trace(fixture: String, filter: String?) = PaimonChangelogTrace.trace(
        assertNotNull(FixtureCatalog.paimonModel(fixture).paimonChangelogInputs(), fixture),
        filter?.let { (parseScanFilter(it) as ScanFilterParse.Parsed).filter } ?: ScanFilter.of(emptyList()),
    )

    private fun record(r: model.ChangelogRecord) = listOf(r.snapshotId, r.commitKind, PaimonRowKind.describe(r.kind!!).substringBefore(' '), r.cells["k"], r.cells["v"].toString())

    @Test
    fun `a lookup producer publishes the computed change in the COMPACT after the append, an input producer the input in the append`() {
        val lk = FixtureCatalog.paimonModel("lk")
        val lkInputs = assertNotNull(lk.paimonChangelogInputs())
        assertEquals("lookup", lkInputs.producer)
        assertEquals(listOf(2L, 4L, 6L), lkInputs.snapshots.map { it.snapshotId }, "the COMPACT commits carry the changelog")
        assertTrue(lkInputs.snapshots.all { it.commitKind == "COMPACT" && it.files.size == 1 })
        assertTrue(lkInputs.producerRule.contains("COMPACT commit after the APPEND"))
        val two = trace("lk", "k = 2")
        assertEquals(
            listOf(listOf(2L, "COMPACT", "+I", 2, "b"), listOf(4L, "COMPACT", "-U", 2, "b"), listOf(4L, "COMPACT", "+U", 2, "B")),
            two.records.map(::record),
        )
        assertEquals(listOf(2L, 4L), two.publishedAt)
        assertEquals(listOf(false, true, false), two.records.map { it.isRetraction })
        assertTrue(two.records.all { it.sequenceNumber != null && it.fileName.startsWith("changelog-") }, two.records.toString())
        assertEquals(3, two.filesRead.size); assertEquals(0, two.unreadable)
        val three = trace("lk", "k = 3")
        assertEquals(listOf(listOf(2L, "COMPACT", "+I", 3, "c"), listOf(6L, "COMPACT", "-D", 3, "c")), three.records.map(::record))

        val cl = FixtureCatalog.paimonModel("cl")
        val clInputs = assertNotNull(cl.paimonChangelogInputs())
        assertEquals("input", clInputs.producer)
        assertEquals(listOf(1L, 2L, 3L), clInputs.snapshots.map { it.snapshotId }, "the APPENDs carry it; the OVERWRITE committed none and ANALYZE wrote none")
        assertTrue(clInputs.producerRule.contains("+I with no before image"))
        assertEquals(
            listOf(listOf(1L, "APPEND", "+I", 2, "b"), listOf(2L, "APPEND", "+I", 2, "B")),
            trace("cl", "k = 2").records.map(::record),
            "the input producer publishes the write, not the change",
        )
        assertEquals(listOf(listOf(1L, "APPEND", "+I", 3, "c"), listOf(3L, "APPEND", "-D", 3, "c")), trace("cl", "k = 3").records.map(::record))
    }

    @Test
    fun `a branch publishes a stream of its own, read under the branch's name`() {
        val pbc = FixtureCatalog.paimonModel("pbc")
        val lines = pbc.paimonChangelogLines()
        assertEquals(listOf("main", "dev"), lines.map { it.branch })
        val all = ScanFilter.of(emptyList())
        fun stream(line: String) = PaimonChangelogTrace.trace(lines.single { it.branch == line }, all).also { assertEquals(line, it.line) }.records.map(::record)
        // Paimon's own read of each line's changelog, one snapshot at a time (paimon-pbc.sql).
        assertEquals(
            listOf(listOf(1L, "APPEND", "+I", 1, "a"), listOf(1L, "APPEND", "+I", 2, "b"), listOf(2L, "APPEND", "+I", 2, "x")),
            stream("main"),
        )
        // The branch's snapshot 1 is main's, copied from the tag with its changelog list.
        assertEquals(
            listOf(
                listOf(1L, "APPEND", "+I", 1, "a"), listOf(1L, "APPEND", "+I", 2, "b"),
                listOf(2L, "APPEND", "+I", 2, "B"), listOf(2L, "APPEND", "+I", 3, "c"),
                listOf(3L, "APPEND", "-D", 1, "a"),
            ),
            stream("dev"),
        )
        assertNull(pbc.paimonChangelogInputs("nope"))
        // The table node carries both lines, main first.
        val table = GraphLayoutService.assembleGraph(pbc, showRows = false).nodes.filterIsInstance<model.GraphNode.TableNode>().single()
        assertEquals(listOf("main", "dev"), table.paimonChangelog.value?.map { it.branch })
    }

    @Test
    fun `a long-lived changelog is read in place of the snapshot it outlived`() {
        // pcl: seven inserts under changelog-producer = input, a COMPACT at 6, snapshot/ holding 7
        // and 8 and changelog/ holding 5 and 6. The COMPACT publishes nothing under input, so the
        // stream is the fifth, sixth and seventh insert — the fifth read from changelog/.
        val pcl = assertNotNull(FixtureCatalog.paimonModel("pcl").paimonChangelogInputs())
        assertEquals(listOf(5L to true, 7L to false, 8L to false), pcl.snapshots.map { it.snapshotId to it.longLived })
        val traced = PaimonChangelogTrace.trace(pcl, ScanFilter.of(emptyList()))
        assertEquals(
            listOf(listOf(5L, "APPEND", "+I", 4, "d"), listOf(7L, "APPEND", "+I", 5, "e"), listOf(8L, "APPEND", "+I", 2, "B")),
            traced.records.map(::record),
        )
        assertEquals(listOf(5L), traced.longLived)
    }

    /** Unfiltered, the records read from each commit's files are the count the writer recorded — on every line of every fixture that names a changelog. */
    @Test
    fun `every changelog snapshot's records read are its recorded changelogRecordCount`() {
        var checked = 0
        val lines = mutableSetOf<String>()
        for (fixture in FixtureCatalog.paimon) {
            for (inputs in FixtureCatalog.paimonModel(fixture).paimonChangelogLines()) {
                val all = PaimonChangelogTrace.trace(inputs, ScanFilter.of(emptyList()))
                val at = "$fixture ${inputs.branch}"
                assertEquals(0, all.unreadable, "$at: ${all.filesRead.filter { it.error != null }}")
                for (snapshot in inputs.snapshots) {
                    val read = all.records.count { it.snapshotId == snapshot.snapshotId }.toLong()
                    assertEquals(snapshot.recordCount, read, "$at snapshot ${snapshot.snapshotId}")
                    checked++
                }
                assertEquals(inputs.snapshots.map { it.snapshotId }, all.publishedAt, "$at: every traced snapshot published something")
                lines += at
            }
        }
        assertTrue(checked >= 14, "lk's, cl's, pcl's and pbc's two lines at least: $checked")
        assertTrue("pbc dev" in lines, lines.toString())
    }
}
