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
}
