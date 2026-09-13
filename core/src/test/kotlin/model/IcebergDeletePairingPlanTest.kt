package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The delete pairing, held to what Iceberg's own plan attaches to each data file.
 *
 * `deleteReach` decides from the metadata which delete files a scan pairs with which data files
 * — sequence number, and a positional delete's `file_path` bounds or a vector's
 * `referenced_data_file` — and deliberately leaves the partition out, which can only leave a
 * pair *unsettled* (`mayReach`) and never rule one in. `FileScanTask.deletes()` is the same
 * pairing decided by `DeleteFileIndex`, partition included, over every checked-in table's
 * current snapshot (`src/test/resources/iceberg-scan-plans/deletes.txt`, printed by
 * `docs/fixtures/iceberg-scan-plans.scala`). The relation between the two is one-sided both
 * ways, and both sides are asserted: a delete Iceberg applies to a file must be reached or
 * unsettled here — missing one is the wrong that returns a deleted row — and a reach *proved*
 * here must be one Iceberg applies, since a proof the engine disagrees with is a wrong proof.
 * Every other test of the pairing reads its expectation off the script that wrote the table;
 * this one reads it off the planner.
 *
 * What the corpus separates: a target rule weakened to "unsettled" is caught (`eqdel`'s
 * positional delete would be left unsettled for the file it does not name), and so is a proof
 * that names the wrong file; and the sequence rule's boundary is caught both ways on `fup`, the
 * Flink-written table whose commit 1 holds a data file, a positional delete for it and an
 * equality delete beside it at one sequence number — an equality delete applied at `>=` pairs
 * with the file Iceberg does not attach it to, and a positional delete applied at `>` misses
 * the one Iceberg does.
 */
class IcebergDeletePairingPlanTest {

    private class Pairing(val table: String, val deletesByFile: Map<String, Set<String>>?, val error: String?)

    private fun oracle(): List<Pairing> {
        val text = javaClass.getResource("/iceberg-scan-plans/deletes.txt")!!.readText()
        val pairings = mutableListOf<Pairing>()
        var table: String? = null
        var files = linkedMapOf<String, Set<String>>()
        var error: String? = null
        fun close() { table?.let { pairings += Pairing(it, if (error == null) files else null, error) } }
        for (line in text.lines()) {
            when {
                line.startsWith("#") || line.isBlank() -> Unit
                line.startsWith("-- ") -> { close(); table = line.removePrefix("-- "); files = linkedMapOf(); error = null }
                line.startsWith("!") -> error = line
                " ->" in line -> {
                    val (file, deletes) = line.split(" ->", limit = 2)
                    files[file.trim()] = deletes.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
                }
            }
        }
        close()
        return pairings
    }

    private fun tail(path: String) = path.substringAfterLast('/')

    /** The snapshot `main` points at — not the last one written, which on a branched table is a branch tip. */
    private fun currentSnapshot(table: String): UnifiedSnapshot {
        val newest = FixtureCatalog.icebergModel(table).metadatas.last()
        return newest.snapshots.single { it.metadata.snapshotId == newest.metadata.currentSnapshotId }
    }

    @Test
    fun `every delete Iceberg applies is reached or unsettled here, and every reach proved here is one Iceberg applies`() {
        val pairings = oracle()
        assertEquals(FixtureCatalog.iceberg.toSet(), pairings.map { it.table }.toSet(), "the oracle covers every checked-in table")
        val checked = mutableListOf<String>()
        for (pairing in pairings) {
            // `test` records its manifest list at the path it was written to, which is not the
            // one the copies sit at, so this Iceberg cannot open it; nothing is asserted about it.
            val expected = pairing.deletesByFile ?: continue
            val snapshot = currentSnapshot(pairing.table)
            val reach = deleteReach(snapshot)
            val reaches = mutableMapOf<String, MutableSet<String>>()
            val mayReach = mutableMapOf<String, MutableSet<String>>()
            for (d in reach) {
                d.reaches.forEach { reaches.getOrPut(tail(it)) { mutableSetOf() } += tail(d.deletePath) }
                d.mayReach.forEach { mayReach.getOrPut(tail(it)) { mutableSetOf() } += tail(d.deletePath) }
            }
            val live = liveFilesOf(snapshot).filter { it.content == 0 }.map { tail(it.path) }.toSet()
            assertEquals(expected.keys, live, "${pairing.table}: the plan's data files are the live set")
            for ((file, applied) in expected) {
                val proved = reaches[file].orEmpty()
                val unsettled = mayReach[file].orEmpty()
                assertTrue((applied - proved - unsettled).isEmpty(), "${pairing.table}: $file — Iceberg applies ${applied - proved - unsettled}, which nothing here reaches")
                assertTrue((proved - applied).isEmpty(), "${pairing.table}: $file — proved to reach ${proved - applied}, which Iceberg does not apply")
                checked += "${pairing.table}/$file"
            }
        }
        // Twenty-nine tables' current snapshots, eighty-two data files — pinned so a table
        // quietly answering nothing is seen.
        assertTrue(checked.size >= 82, "only ${checked.size} files checked")
    }

    @Test
    fun `the pairing is exact where the partition decides nothing, and unsettled only for equality deletes`() {
        // On every checked-in table the metadata settles every positional delete and vector —
        // none is left unsettled — so the plan's deletes are exactly the proved ones, plus an
        // equality delete, which no metadata can aim at a file and which the plan attaches by
        // sequence and partition alone.
        for (pairing in oracle()) {
            val expected = pairing.deletesByFile ?: continue
            val reach = deleteReach(currentSnapshot(pairing.table))
            for (d in reach) {
                if (d.kind != DeleteFileKind.EQUALITY) assertEquals(emptyList(), d.mayReach, "${pairing.table}: ${tail(d.deletePath)} is a ${d.kind} the metadata left unsettled")
            }
            for ((file, applied) in expected) {
                val proved = reach.filter { d -> d.reaches.any { tail(it) == file } }.map { tail(it.deletePath) }.toSet()
                val equality = reach.filter { it.kind == DeleteFileKind.EQUALITY && it.mayReach.any { p -> tail(p) == file } }.map { tail(it.deletePath) }.toSet()
                assertEquals(applied, proved + equality, "${pairing.table}: $file")
            }
        }
    }
}
