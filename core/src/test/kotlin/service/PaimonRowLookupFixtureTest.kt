package service

import model.PaimonUnifiedTableModel
import model.PredicateOp
import model.RowFate
import model.RowLookupResult
import model.ScanFilter
import model.ScanPredicate
import model.paimonRowLookupInput
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every fate a Paimon row can have, decided from the fixtures' own bytes and checked against
 * the scripts that wrote them. `lk` (k 1..3, then (2, 'B') and (4, 'd'), then DELETE k = 3)
 * has the update whose old record does not match a filter on the new value, which is why the
 * bucket is read for the key rather than the filter's files alone; `dv` and `ad` have the
 * vectors; `pc` is seven keys with one record each.
 */
class PaimonRowLookupFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun input(fixture: String) =
        assertNotNull(PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/$fixture").absolutePath)).paimonRowLookupInput(), fixture)

    private fun where(fixture: String, column: String, op: PredicateOp, literal: String): RowLookupResult =
        PaimonRowLookup.lookup(input(fixture), ScanFilter.Term(ScanPredicate(column, op, literal)), emptySet())

    /**
     * `pt` is partitioned by `dt` and `region`, and its files sit under `dt=19787/region=eu/` —
     * paths DuckDB reads as Hive partitions unless told not to, typing `dt` as a BIGINT from the
     * path over the DATE the file holds. That collision was an INTERNAL error in DuckDB 1.4.4
     * ("Vector::Reference used on vector of different type") which invalidated the connection
     * for every later query, so every read here passes `hive_partitioning = false`: the file is
     * the truth and the path is a layout.
     */
    @Test
    fun `a partitioned table is read from its files, not from the partition values in their paths`() {
        val byDate = where("pt", "dt", PredicateOp.EQ, "2024-03-07")
        assertEquals(listOf(9), byDate.hits.map { (it.cells.getValue("k") as Number).toInt() }, "$byDate")
        assertEquals(java.time.LocalDate.of(2024, 3, 7), byDate.hits.single().cells.getValue("dt"))
        assertTrue(byDate.filesRead.all { it.error == null }, "$byDate")
        val region = where("pt", "region", PredicateOp.EQ, "north-america")
        assertEquals(listOf(3, 5, 6, 10), region.hits.map { (it.cells.getValue("k") as Number).toInt() }.sorted())
    }

    @Test
    fun `a primary-key table's updated key has one live record, and the record before it is superseded by the file holding the update`() {
        val two = where("lk", "k", PredicateOp.EQ, "2").hits.sortedBy { it.cells["_SEQUENCE_NUMBER"] as Long }
        assertEquals(listOf(RowFate.SUPERSEDED, RowFate.LIVE), two.map { it.fate }, "$two")
        assertEquals("b", two[0].cells["v"])
        assertEquals("B", two[1].cells["v"])
        assertEquals(two[1].filePath, two[0].by, "the old record is superseded by the file holding the new one")
        assertEquals("sequence 1, then 3", two[0].note)
    }

    @Test
    fun `a filter on the old value finds the superseded record, because the bucket is read for the key`() {
        val hits = where("lk", "v", PredicateOp.EQ, "b").hits
        assertEquals(listOf(RowFate.SUPERSEDED), hits.map { it.fate }, "$hits")
        assertEquals(0, where("lk", "v", PredicateOp.EQ, "b").live)
        assertEquals(1, where("lk", "v", PredicateOp.EQ, "B").live)
    }

    @Test
    fun `a deleted key is a retraction and the record it removed is superseded by it`() {
        val three = where("lk", "k", PredicateOp.EQ, "3").hits.sortedBy { it.cells["_SEQUENCE_NUMBER"] as Long }
        assertEquals(listOf(RowFate.SUPERSEDED, RowFate.RETRACTION), three.map { it.fate }, "$three")
        assertEquals("-D (delete), not a row", three[1].note)
        assertEquals("sequence 2, then 5: -D (delete)", three[0].note)
        assertEquals(three[1].filePath, three[0].by)
        assertEquals(RowFate.LIVE, where("lk", "k", PredicateOp.EQ, "4").hits.single().fate)
    }

    @Test
    fun `a vector marks the deleted keys of a primary-key table and nothing else`() {
        for (k in listOf(2, 1001, 1500)) {
            val hit = where("dv", "k", PredicateOp.EQ, "$k").hits.single()
            assertEquals(RowFate.VECTOR_DELETED, hit.fate, "k $k: $hit")
            assertTrue(assertNotNull(hit.by).startsWith("index-"), hit.by.orEmpty())
        }
        for (k in listOf(1, 3, 1000, 1002, 1499)) {
            assertEquals(RowFate.LIVE, where("dv", "k", PredicateOp.EQ, "$k").hits.single().fate, "k $k")
        }
        val all = where("dv", "k", PredicateOp.GTE, "1")
        assertEquals(RowLookup.MAX_HITS_PER_FILE * 2, all.hits.size, "two files, each capped")
    }

    @Test
    fun `an append table's row is live unless its file's vector marks it`() {
        val all = where("ad", "id", PredicateOp.GTE, "1")
        assertEquals(7, all.hits.size)
        assertEquals(5, all.live)
        assertEquals(2, all.deleted)
        assertEquals(setOf(2, 6), all.hits.filter { it.fate == RowFate.VECTOR_DELETED }.map { it.cells["id"] }.toSet())
        assertEquals(0, all.undecided)
    }

    @Test
    fun `every key of a table with one record per key is live, whether compacted or not`() {
        for (k in 1..7) {
            val hit = where("pc", "k", PredicateOp.EQ, "$k").hits.single()
            assertEquals(RowFate.LIVE, hit.fate, "k $k: $hit")
        }
        val vs = where("pc", "v", PredicateOp.GTE, "a")
        assertEquals(7, vs.live)
    }

    /**
     * `pkr`: the primary key `k` renamed to `id` after the first file — `_KEY_k` in that file,
     * `_KEY_id` in the two after — and key 1 written again after the rename. Paimon reads
     * `1 A / 2 b / 3 c`; the bucket's merge has to see both names as one column.
     */
    @Test
    fun `a primary key renamed between writes is one key across the bucket's files`() {
        val hits = where("pkr", "id", PredicateOp.EQ, "1").hits.sortedBy { it.cells["_SEQUENCE_NUMBER"] as Long }
        assertEquals(listOf(RowFate.SUPERSEDED, RowFate.LIVE), hits.map { it.fate }, hits.toString())
        assertEquals(listOf("a", "A"), hits.map { it.cells["v"].toString() })
        assertEquals(listOf("1", "1"), hits.map { it.cells["id"].toString() }, "the old file's k reads as id")
        assertEquals(hits[1].filePath, hits[0].by, "superseded by the file holding the write after the rename")
        val old = where("pkr", "v", PredicateOp.EQ, "a").hits.single()
        assertEquals(RowFate.SUPERSEDED, old.fate)
        assertEquals(listOf(RowFate.LIVE, RowFate.LIVE), listOf(2, 3).map { where("pkr", "id", PredicateOp.EQ, "$it").hits.single().fate })
    }

    @Test
    fun `a file the filter ruled out is not opened, and the count says so`() {
        val input = input("ad")
        val all = input.files.map { it.fileName }
        val keep = all.first()
        val result = PaimonRowLookup.lookup(input, ScanFilter.Term(ScanPredicate("id", PredicateOp.GTE, "1")), (all - keep).toSet())
        assertEquals(listOf(keep), result.filesRead.map { it.filePath })
        assertEquals(all.size - 1, result.filesRuledOut)
        assertEquals(0, result.filesLeft)
    }

    /**
     * `de` is read stitched: `SELECT *` on the table prints `(1, 11, 1)`, `(2, 22, 2)`, `(3, 33, 0)`
     * (`docs/fixtures/paimon-de.sql`; re-run 2026-09-13 on a copy of the checked-in bytes), the
     * `b` of rows 0..1 coming from the patch file and every other column from the file it
     * patches. A filter on the patched value has to find the row whose other columns are in the
     * other file, and a filter on the value the patch replaced has to find nothing — which is
     * what Paimon 1.3.1's read answers, and not what the 1.3-SNAPSHOT that wrote the table did:
     * its filtered read pruned each file by its own bounds and returned `(1, 1, 1)` for `b = 1`.
     */
    @Test
    fun `a data-evolution split is read stitched, so a filter on a patched column finds the row and the old value finds nothing`() {
        val input = input("de")
        assertTrue(input.dataEvolution)
        assertEquals(listOf(2, 1), input.splits.map { it.size }.sortedDescending(), "the patched pair and the whole file for the inserted row")
        val stitched = input.splits.single { it.size == 2 }
        assertTrue(stitched.first().partial && !stitched.last().partial, "the patch is freshest and read first")

        val one = where("de", "id", PredicateOp.EQ, "1").hits.single()
        assertEquals(RowFate.LIVE, one.fate)
        assertEquals(listOf(1, 11, 1), listOf("id", "b", "c").map { (one.cells[it] as Number).toInt() })
        assertEquals(stitched.last().fileName, one.filePath, "the hit is reported at the file holding the row")
        assertEquals("b from ${stitched.first().fileName}", one.note)

        val patched = where("de", "b", PredicateOp.EQ, "11")
        assertTrue(patched.filesRead.none { it.error != null }, patched.filesRead.toString())
        assertEquals(listOf(1), patched.hits.map { (it.cells["id"] as Number).toInt() })
        assertEquals(emptyList(), where("de", "b", PredicateOp.EQ, "1").hits, "the value the patch replaced is no row's")
        val three = where("de", "id", PredicateOp.EQ, "3").hits.single()
        assertEquals(listOf(3, 33, 0), listOf("id", "b", "c").map { (three.cells[it] as Number).toInt() })
        assertEquals(null, three.note, "a whole file is not stitched")
    }

    @Test
    fun `a split is read whole when the filter ruled out one file of it`() {
        val input = input("de")
        val stitched = input.splits.single { it.size == 2 }
        val whole = stitched.last()
        val result = PaimonRowLookup.lookup(input, ScanFilter.Term(ScanPredicate("b", PredicateOp.EQ, "22")), setOf(whole.fileName))
        assertEquals(listOf(2), result.hits.map { (it.cells["id"] as Number).toInt() })
        assertEquals(0, result.filesRuledOut, "the ruled-out file holds the row's other columns and is opened for them")
        assertTrue(result.filesRead.any { it.filePath == whole.fileName })
    }

    @Test
    fun `the row at a position of a patched file reads stitched, and a whole file reads alone`() {
        val input = input("de")
        val stitched = input.splits.single { it.size == 2 }
        val whole = stitched.last()
        val row = assertNotNull(PaimonRowLookup.stitchedRowAt(input, whole, 0))
        assertEquals(listOf(1, 11, 1), listOf("id", "b", "c").map { (row.cells[it] as Number).toInt() })
        assertEquals(stitched.first().fileName, row.sourceOf["b"])
        assertEquals(whole.fileName, row.sourceOf["id"])
        val alone = input.splits.single { it.size == 1 }.single()
        assertEquals(null, PaimonRowLookup.stitchedRowAt(input, alone, 0))
        assertEquals(null, PaimonRowLookup.stitchedRowAt(input, whole, 99), "no row at that position")
    }
}
