package service

import model.DeleteFileKind
import model.IcebergType
import model.PredicateOp
import model.RowFate
import model.RowLookupResult
import model.ScanFilter
import model.ScanPredicate
import model.UnifiedTableModel
import model.normalizeFilePath
import model.rowLookupInput
import model.toSql
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The scripts that wrote `mor`, `eqdel` and `v3` say which rows each table ends with — the
 * rows their inserts put in, minus the ones their deletes and updates took out — and the
 * lookup has to agree row by row: found live, found deleted with the delete file that did it,
 * or not found at all where a compaction rewrote the file without it. Three delete kinds, one
 * per fixture, decided from the bytes.
 */
class RowLookupFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun input(fixture: String) =
        assertNotNull(UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/$fixture").absolutePath)).rowLookupInput(), fixture)

    private fun byId(fixture: String, id: Int): RowLookupResult =
        RowLookup.lookup(input(fixture), ScanFilter.Term(ScanPredicate("id", PredicateOp.EQ, "$id")), emptySet())

    /**
     * `parted`'s files sit under `amount=98765.43/d=1969-06-11/…`, and DuckDB's Hive-partition
     * autodetection typed `amount` as text from the path over the file's DECIMAL(9,2) — so a
     * lookup on the decimal compared text with a decimal cast and found nothing. Every read
     * passes `hive_partitioning = false` now; the file's column is the one asked.
     */
    @Test
    fun `a partitioned table's columns are read from the file, typed as the file types them`() {
        val hit = RowLookup.lookup(input("parted"), ScanFilter.Term(ScanPredicate("amount", PredicateOp.EQ, "98765.43")), emptySet())
        assertEquals(listOf(3), hit.hits.map { (it.cells.getValue("id") as Number).toInt() }, "$hit")
        assertTrue(hit.hits.single().cells.getValue("amount") is java.math.BigDecimal, "${hit.hits.single().cells}")
        // Nothing from the path is added as a column: the transformed partition fields are not columns of the row.
        assertTrue(hit.hits.single().cells.keys.none { it.endsWith("_bucket") || it.endsWith("_day") || it.endsWith("_year") }, "${hit.hits.single().cells.keys}")
    }

    @Test
    fun `a merge-on-read table's positional delete decides the row it marks, and a compaction leaves no trace of the rows before it`() {
        // Script: rows 1..7; 2 deleted and 5 updated before the compaction, 7 deleted after it.
        val seven = byId("mor", 7).hits.single()
        assertEquals(RowFate.POSITION_DELETED, seven.fate)
        assertTrue(assertNotNull(seven.by).endsWith("-deletes.parquet"), seven.by.orEmpty())
        assertEquals(emptyList(), byId("mor", 2).hits, "the compaction applied the delete and dropped the row")
        val five = byId("mor", 5).hits.single()
        assertEquals(RowFate.LIVE, five.fate)
        assertEquals("echo-updated", five.cells["name"])
        assertEquals(RowFate.LIVE, byId("mor", 1).hits.single().fate)
    }

    @Test
    fun `an equality delete decides by the row's values, across both files it spans`() {
        // Script: 1..4 in one file, 5..7 in another; 3 deleted by position, 2 and 6 by equality on id.
        val equality = input("eqdel").deleteFiles.single { it.kind == DeleteFileKind.EQUALITY }
        assertEquals(listOf("id"), equality.equalityColumns)
        for (id in listOf(2, 6)) {
            val hit = byId("eqdel", id).hits.single()
            assertEquals(RowFate.EQUALITY_DELETED, hit.fate, "id $id")
            assertEquals(equality.recordedPath, hit.by)
        }
        assertEquals(RowFate.POSITION_DELETED, byId("eqdel", 3).hits.single().fate)
        for (id in listOf(1, 4, 5, 7)) assertEquals(RowFate.LIVE, byId("eqdel", id).hits.single().fate, "id $id")
    }

    /** `eqren`: `eqdel`'s shape with `name` renamed to `label` after the equality delete on it was written, and id 8 (`label` bravo) inserted after — Spark reads 1, 4, 5, 7, 8. */
    @Test
    fun `an equality delete on a column renamed since decides by field id, and does not reach the row written after it`() {
        val input = input("eqren")
        val equality = input.deleteFiles.single { it.kind == DeleteFileKind.EQUALITY }
        assertEquals(listOf("label"), equality.equalityColumns)
        val bravos = RowLookup.lookup(input, ScanFilter.Term(ScanPredicate("label", PredicateOp.EQ, "bravo")), emptySet())
        assertTrue(bravos.filesRead.all { it.error == null }, bravos.filesRead.toString())
        assertEquals(mapOf("2" to RowFate.EQUALITY_DELETED, "8" to RowFate.LIVE), bravos.hits.associate { it.cells["id"].toString() to it.fate })
        assertEquals(RowFate.EQUALITY_DELETED, byId("eqren", 6).hits.single().fate)
        assertEquals(RowFate.POSITION_DELETED, byId("eqren", 3).hits.single().fate)
        for (id in listOf(1, 4, 5, 7, 8)) assertEquals(RowFate.LIVE, byId("eqren", id).hits.single().fate, "id $id")
    }

    @Test
    fun `a v3 vector decides by the position's bit, and an update leaves the old row marked beside the new one live`() {
        // Script: 1..3 then 4..5; 2 deleted by a vector; 4 updated — a vector on the old row, a new file with the new one.
        val two = byId("v3", 2).hits.single()
        assertEquals(RowFate.VECTOR_DELETED, two.fate)
        assertTrue(assertNotNull(two.by).endsWith(".puffin"), two.by.orEmpty())
        val four = byId("v3", 4).hits.sortedBy { it.fate.name }
        assertEquals(listOf(RowFate.LIVE, RowFate.VECTOR_DELETED), four.map { it.fate })
        assertEquals("delta-updated", four.first { it.fate == RowFate.LIVE }.cells["name"])
        assertEquals("delta", four.first { it.fate == RowFate.VECTOR_DELETED }.cells["name"])
    }

    /** What each script's final table holds, by id — the engine's own `SELECT *` would list exactly these. */
    @Test
    fun `across the three tables, a row is found live exactly when the script left it`() {
        val expected = mapOf(
            "mor" to setOf(1, 3, 4, 5, 6),
            "eqdel" to setOf(1, 4, 5, 7),
            "v3" to setOf(1, 3, 4, 5),
        )
        var checked = 0
        for ((fixture, live) in expected) {
            val input = input(fixture)
            for (id in 1..7) {
                val hits = RowLookup.lookup(input, ScanFilter.Term(ScanPredicate("id", PredicateOp.EQ, "$id")), emptySet()).hits
                assertEquals(if (id in live) 1 else 0, hits.count { it.fate == RowFate.LIVE }, "$fixture id $id: $hits")
                assertTrue(hits.none { it.fate == RowFate.UNKNOWN }, "$fixture id $id")
                checked++
            }
        }
        assertEquals(21, checked)
    }

    @Test
    fun `the files the filter ruled out are not opened, and the cap is stated`() {
        val input = input("eqdel")
        val all = input.dataFiles.map { normalizeFilePath(it.recordedPath) }
        assertTrue(all.size >= 2)
        val keep = all.first()
        val result = RowLookup.lookup(input, ScanFilter.Term(ScanPredicate("id", PredicateOp.GTE, "0")), (all - keep).toSet())
        assertEquals(listOf(keep), result.filesRead.map { normalizeFilePath(it.filePath) })
        assertEquals(all.size - 1, result.filesRuledOut)
        assertEquals(0, result.filesLeft)
    }

    @Test
    fun `a filter renders as a WHERE clause with every literal bound and cast to its column's type`() {
        val filter = ScanFilter.And(listOf(
            ScanFilter.Term(ScanPredicate("id", PredicateOp.EQ, "42")),
            ScanFilter.Or(listOf(
                ScanFilter.Term(ScanPredicate("name", PredicateOp.LIKE, "al%")),
                ScanFilter.Not(ScanFilter.Term(ScanPredicate("d", PredicateOp.IS_NULL))),
            )),
        ))
        val sql = filter.toSql { column -> mapOf("id" to IcebergType.IntType, "name" to IcebergType.StringType, "d" to IcebergType.DateType)[column] }
        assertEquals("""("id" = CAST(? AS INTEGER)) AND ((CAST("name" AS VARCHAR) LIKE ?) OR (NOT ("d" IS NULL)))""", sql.sql)
        assertEquals(listOf("42", "al%"), sql.params)
        assertEquals("""("x y" = ?)""", ScanFilter.And(listOf(ScanFilter.Term(ScanPredicate("x y", PredicateOp.EQ, "1")))).toSql { null }.sql)
    }
}
