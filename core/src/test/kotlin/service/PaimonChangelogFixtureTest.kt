package service

import model.FixtureCatalog
import model.PaimonRowKind
import model.ScanFilter
import model.ScanFilterParse
import model.parseScanFilter
import model.paimonChangelogInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    /** Unfiltered, the records read from each snapshot's files are the count the writer recorded — on every fixture that names a changelog. */
    @Test
    fun `every changelog snapshot's records read are its recorded changelogRecordCount`() {
        var checked = 0
        for (fixture in FixtureCatalog.paimon) {
            val inputs = FixtureCatalog.paimonModel(fixture).paimonChangelogInputs() ?: continue
            val all = PaimonChangelogTrace.trace(inputs, ScanFilter.of(emptyList()))
            assertEquals(0, all.unreadable, "$fixture: ${all.filesRead.filter { it.error != null }}")
            for (snapshot in inputs.snapshots) {
                val read = all.records.count { it.snapshotId == snapshot.snapshotId }.toLong()
                assertEquals(snapshot.recordCount, read, "$fixture snapshot ${snapshot.snapshotId}")
                checked++
            }
            assertEquals(inputs.snapshots.map { it.snapshotId }, all.publishedAt, "$fixture: every traced snapshot published something")
        }
        assertTrue(checked >= 6, "lk's three and cl's three at least: $checked")
    }
}
