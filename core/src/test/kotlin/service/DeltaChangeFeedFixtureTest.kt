package service

import model.FixtureCatalog
import model.ScanFilterParse
import model.deltaChangeFeedInputs
import model.parseScanFilter
import model.readInputAt
import model.rowHistoryInputs
import model.RowChange
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Delta's change data feed held to `table_changes` — the lines each fixture script printed as
 * `change <version> <type> <id> <v> <p>`, which the engine produced and this code did not — and
 * the row history over the same versions.
 */
class DeltaChangeFeedFixtureTest {

    private fun filter(text: String) = (parseScanFilter(text) as ScanFilterParse.Parsed).filter

    /** The script's `table_changes` lines, in the order it sorted them: version, id, change type. */
    private fun oracle(name: String): List<String> =
        File(FixtureCatalog.repoRoot, "docs/fixtures/delta/$name.out").readLines()
            .filter { it.startsWith("change\t") }
            .map { it.removePrefix("change\t").replace('\t', ' ') }

    private fun traced(name: String, text: String = "id IS NOT NULL"): List<String> {
        val inputs = requireNotNull(FixtureCatalog.deltaModel(name).deltaChangeFeedInputs())
        val feed = DeltaChangeFeedTrace.trace(inputs, filter(text))
        assertEquals(0, feed.unreadable, "every source read")
        return feed.records.map { r ->
            val type = r.label!!.substringBefore(' ')
            Triple(r.snapshotId, (r.cells["id"] as Number).toInt(), type) to "${r.snapshotId} $type ${r.cells["id"]} ${r.cells["v"]} ${r.cells["p"]}"
        }.sortedWith(compareBy({ it.first.first }, { it.first.second }, { it.first.third })).map { it.second }
    }

    @Test
    fun `dcdf's feed is table_changes — cdc files for the rewrites, the files themselves for the insert and the whole-file delete`() {
        assertEquals(oracle("dcdf"), traced("dcdf"))
        val inputs = FixtureCatalog.deltaModel("dcdf").deltaChangeFeedInputs()!!
        assertEquals(listOf(1L, 2L, 3L, 4L), inputs.versions.map { it.version }, "the CREATE publishes nothing")
        assertEquals(setOf(model.DeltaChangeSourceKind.CHANGE_FILE), inputs.versions.single { it.version == 2L }.sources.map { it.kind }.toSet(), "the UPDATE is read from its cdc file alone")
        assertEquals(setOf(model.DeltaChangeSourceKind.DELETED), inputs.versions.single { it.version == 3L }.sources.map { it.kind }.toSet())
    }

    @Test
    fun `dcdfdv's feed takes the delete from the vector the DELETE added, since it wrote no cdc file`() {
        assertEquals(oracle("dcdfdv"), traced("dcdfdv"))
        val delete = FixtureCatalog.deltaModel("dcdfdv").deltaChangeFeedInputs()!!.versions.single { it.version == 2L }
        assertEquals(listOf(model.DeltaChangeSourceKind.VECTOR_CHANGED), delete.sources.map { it.kind })
    }

    @Test
    fun `the filter is the lookup's, so one key's feed is its own records`() {
        assertEquals(oracle("dcdf").filter { it.split(' ')[2] == "1" }, traced("dcdf", "id = 1"))
        assertEquals(oracle("dcdfdv").filter { it.split(' ')[2] == "4" }, traced("dcdfdv", "v IN ('d', 'D')"))
    }

    @Test
    fun `a table without the feed has none`() {
        assertNull(FixtureCatalog.deltaModel("ddv").deltaChangeFeedInputs())
    }

    @Test
    fun `the history names the version that changed a row, read under the newest schema`() {
        val trace = { name: String, text: String ->
            val history = RowHistoryTrace.trace(FixtureCatalog.deltaModel(name).rowHistoryInputs()!!, filter(text), emptySet())
            history.steps.zip(history.changes).map { (step, change) -> step.snapshot.snapshotId to change }.filter { it.second != null && it.second != RowChange.UNCHANGED }
        }
        // ddv: 1001 inserted at 2, updated at 5; 2 inserted at 1, deleted at 3.
        assertEquals(listOf(5L to RowChange.CHANGED, 2L to RowChange.APPEARED), trace("ddv", "id = 1001"))
        assertEquals(listOf(3L to RowChange.GONE, 1L to RowChange.APPEARED), trace("ddv", "id = 2"))
        // dcm: label is v renamed at version 2; the first file's row is found under the new name at every version.
        assertEquals(listOf(1L to RowChange.APPEARED), trace("dcm", "label = 'b'"))
        // dlc: only versions 5 and 6 are on disk, and only 6 can be rebuilt — 5 has no checkpoint under it.
        val dlc = FixtureCatalog.deltaModel("dlc").rowHistoryInputs()!!
        assertEquals(listOf(6L), dlc.snapshots.map { it.snapshotId })
        assertEquals(1, dlc.onMain)
        assertTrue(FixtureCatalog.deltaModel("dcm").readInputAt(1, FixtureCatalog.deltaModel("dcm").current!!.metadata!!.schema) != null)
    }
}
