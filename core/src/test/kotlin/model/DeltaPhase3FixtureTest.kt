package model

import service.DeltaCheckpointNaming
import service.LiveRowCount
import service.RowLookup
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Delta tables written for column mapping, the three checkpoint namings, log cleanup and
 * `RESTORE`, each held to what its script printed (`docs/fixtures/delta/<script>.out`) — lines the
 * engine produced, not this code.
 */
class DeltaPhase3FixtureTest {

    private fun filter(text: String) = (parseScanFilter(text) as ScanFilterParse.Parsed).filter

    private fun ids(name: String, text: String): Set<Int> {
        val model = FixtureCatalog.deltaModel(name)
        return RowLookup.lookup(model.readInputAt(model.latestVersion!!)!!, filter(text), emptySet()).hits
            .filter { it.fate == RowFate.LIVE }.map { (it.cells["id"] as Number).toInt() }.toSet()
    }

    private fun tally(name: String, version: Long, label: String): CommitTally {
        val model = FixtureCatalog.deltaModel(name)
        return deltaCommitTallies(model.commitByVersion.getValue(version)) { model.stateAt(version).getOrNull() }.single { it.label == label }
    }

    @Test
    fun `a column-mapped table is read by physical name in name mode and by id in id mode, renames and a dropped column included`() {
        // dcm.out / dcmid.out: `label=b 2`, `town=Bursa 3`, `part=x 1, 3`, `w null 1, 2`.
        for (name in listOf("dcm", "dcmid")) {
            assertEquals(setOf(2), ids(name, "label = 'b'"), name)
            assertEquals(setOf(3), ids(name, "addr.town = 'Bursa'"), name)
            assertEquals(setOf(1, 3), ids(name, "part = 'x'"), "$name: the partition column renamed, its directories named by physical name")
            assertEquals(setOf(1, 2), ids(name, "w IS NULL"), "$name: the column added after the first files")
            assertEquals(setOf(1, 2, 3, 4), ids(name, "id >= 1"), name)
            val model = FixtureCatalog.deltaModel(name)
            assertEquals(4L, LiveRowCount.count(model.readInputAt(model.latestVersion!!)!!).live, name)
        }
        val physical = FixtureCatalog.deltaModel("dcm").current!!.metadata!!.schema!!.fields.map { it.physicalName }
        assertTrue(physical.all { it.startsWith("col-") }, "$physical")
    }

    @Test
    fun `a multi-part checkpoint and a V2 checkpoint with sidecars both read as the replay of the commits`() {
        val dmp = FixtureCatalog.deltaModel("dmp")
        val parts = dmp.checkpoints.single()
        assertEquals(DeltaCheckpointNaming.MULTI_PART, parts.naming)
        assertEquals(2, parts.parts.size, "partSize = 2 over four files")
        assertEquals(4L, dmp.stateAt(4).getOrThrow().fromCheckpoint)
        assertEquals(4, dmp.stateAt(4).getOrThrow().files.size)

        val dv2 = FixtureCatalog.deltaModel("dv2")
        assertEquals(listOf(2L, 4L), dv2.checkpoints.map { it.version })
        assertTrue(dv2.checkpoints.all { it.naming == DeltaCheckpointNaming.UUID })
        val read = dv2.checkpointRead(dv2.checkpoints.last())!!
        assertEquals(1, read.sidecars.size, "the files are in a sidecar under _delta_log/_sidecars/")
        assertTrue(read.actions.none { it.add != null }, "the top-level file holds no file action of its own")
        assertEquals(2, dv2.stateAt(4).getOrThrow().files.size, "dv2.out: rows 2 and 3")
    }

    @Test
    fun `_last_checkpoint's v2Checkpoint is checked against the file it names and the sidecars`() {
        val tallies = FixtureCatalog.deltaModel("dv2").lastCheckpointTallies().associateBy { it.label }
        // sizeInBytes is the top-level file and its sidecars together: 765 + 9,865.
        assertEquals(10630L, tallies.getValue("sizeInBytes").counted)
        for (label in listOf("v2Checkpoint names the checkpoint", "v2Checkpoint sizeInBytes", "v2Checkpoint nonFileActions", "v2Checkpoint sidecars")) {
            assertEquals(true, tallies.getValue(label).agrees, "$label: ${tallies[label]}")
        }
        assertEquals(3L, tallies.getValue("v2Checkpoint nonFileActions").counted, "protocol, metaData and checkpointMetadata")
    }

    @Test
    fun `log cleanup leaves versions below the checkpoint unavailable, which is a state and not an error`() {
        val dlc = FixtureCatalog.deltaModel("dlc")
        assertEquals(listOf(5L, 6L), dlc.versions, "0..4 and checkpoints 2 and 4 cleaned up; 5 kept")
        assertTrue(dlc.stateAt(5).exceptionOrNull() is DeltaVersionUnavailable, "5 has no checkpoint under it")
        assertEquals(6L, dlc.stateAt(6).getOrThrow().fromCheckpoint)
        assertEquals(6, dlc.stateAt(6).getOrThrow().files.size)
        // dlcb is the same table copied before the last two inserts: everything there.
        val dlcb = FixtureCatalog.deltaModel("dlcb")
        assertEquals((0L..4L).toList(), dlcb.versions)
        assertTrue(dlcb.versions.all { dlcb.stateAt(it).isSuccess })
    }

    @Test
    fun `RESTORE records what it brought back, what it took out, and the table after`() {
        // drs.out's RESTORE row: numRestoredFiles 1, restoredFilesSize 657, numRemovedFiles 1,
        // removedFilesSize 657, numOfFilesAfterRestore 2, tableSizeAfterRestore 1314.
        val expected = mapOf(
            "numRestoredFiles" to 1L, "restoredFilesSize" to 657L, "numRemovedFiles" to 1L,
            "removedFilesSize" to 657L, "numOfFilesAfterRestore" to 2L, "tableSizeAfterRestore" to 1314L,
        )
        for ((label, value) in expected) {
            val t = tally("drs", 5, label)
            assertEquals(value, t.counted, label)
            assertEquals(true, t.agrees, "$label: $t")
        }
        val rows = File(FixtureCatalog.repoRoot, "docs/fixtures/delta/drs.out").readLines().filter { it.startsWith("rows\t") }.map { it.split('\t')[1].toInt() }
        assertEquals(rows.toSet(), ids("drs", "id >= 1"))
    }

    @Test
    fun `an UPDATE counts its change files among the removed bytes, and a MERGE counts only files it wrote as output`() {
        // dcdf v2: the rewritten file's 660 bytes and the cdc file's 1,003 — UpdateCommand splits its actions into adds and the rest.
        val update = tally("dcdf", 2, "numRemovedBytes")
        assertEquals(1663L, update.counted)
        assertEquals(true, update.agrees)
        // dcdfdv v3: with deletion vectors nothing is removed by bytes, so the figure is the cdc file's alone.
        assertEquals(1003L, tally("dcdfdv", 3, "numRemovedBytes").counted)
        // dcdfdv v4 MERGE: two new rows written; the three a vector re-add carries are not output.
        val merge = tally("dcdfdv", 4, "numOutputRows")
        assertEquals(2L, merge.counted)
        assertEquals(true, merge.agrees)
    }
}
