package model

import service.PaimonFileIndexReader
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `frb`'s range bitmaps — one embedded, three in `.index` files, over an INT, a STRING, a
 * DECIMAL(10,2), a DATE, a TIMESTAMP(6), a DOUBLE and a BOOLEAN — decoded to the dictionary and
 * the slices the writer splits a column into, and held to `FileIndexPredicate` asked over every
 * file for every operator with the **rows** it answers (`paimon-scan-plans.scala`, the
 * `[index-rows]` sections on `frb`, run 2026-09-15 on the 1.3 jar): the filter's terms answered
 * by [PaimonRangeBitmapIndex.rowsMatching] and folded with [indexRows] the way the predicate
 * folds its readers' bitmaps, and the fold's cardinality compared with the one Paimon printed.
 * A count is a stronger oracle than a verdict: a dictionary search landing one code off keeps
 * the verdict and moves the count.
 */
class PaimonRangeBitmapIndexTest {

    private val model by lazy { FixtureCatalog.paimonModel("frb") }
    private val types = mapOf("n" to "INT", "s" to "STRING", "amt" to "DECIMAL(10, 2)", "d" to "DATE", "ts" to "TIMESTAMP(6)", "x" to "DOUBLE", "b" to "BOOLEAN")

    private fun indexAt(snapshot: Long): PaimonFileIndex {
        val entry = model.snapshots.first { it.metadata.id == snapshot }.deltaManifests.single().entries.single()
        val file = entry.metadata.file!!
        return file.embeddedFileIndex?.let(PaimonFileIndexReader::decode)
            ?: PaimonFileIndexReader.read(entry.path.resolveSibling(file.extraFiles!!.single { it.endsWith(".index") }))
    }

    private val files by lazy { listOf(indexAt(1), indexAt(2), indexAt(3), indexAt(4)) }

    private fun key(column: String, literal: Any): Any = paimonRangeBitmapKey(types.getValue(column), literal)!!

    private fun java.util.BitSet.bits(): Set<Int> = stream().toArray().toSet()

    @Test
    fun `the four files decode to the dictionary and the slices the writer cut, on every type`() {
        assertEquals("range-bitmap on b, s, d, x, amt, n, ts", files[0].describe(), "the head's own order, which is not the schema's")
        // File 1: -5, 3, 10, null, 3 — three keys over four non-null rows, one chunk each; a
        // boolean's default chunk size is `0b`, so every boolean key opens a chunk of its own.
        for ((column, type) in types) {
            val r = assertNotNull(files[0].rangeBitmapIndex(column, type), column)
            assertEquals(5, r.rowCount, column); assertEquals(4, r.nonNullCount, column)
            assertEquals(if (column == "b") 2 else 3, r.cardinality, column)
            assertEquals(if (column == "b") 2 else 1, r.chunkCount, column)
            assertEquals(setOf(3), r.isNull().bits(), column)
            assertEquals(0, r.codeOf(r.min!!), column); assertEquals(r.cardinality - 1, r.codeOf(r.max!!), column)
        }
        val n1 = files[0].rangeBitmapIndex("n", "INT")!!
        assertEquals(-5, n1.min); assertEquals(10, n1.max)
        assertEquals(listOf(-5, 3, 10), (0..2).map { n1.keyOf(it) })
        val s1 = files[0].rangeBitmapIndex("s", "STRING")!!
        assertEquals("alpha", String(s1.min as ByteArray)); assertEquals("charlie", String(s1.max as ByteArray))
        assertEquals(-1250L, files[0].rangeBitmapIndex("amt", "DECIMAL(10, 2)")!!.min, "a decimal is keyed unscaled")
        assertEquals(19783, files[0].rangeBitmapIndex("d", "DATE")!!.min, "a date is keyed as its epoch day")
        assertEquals(1_709_287_200_000_001L, files[0].rangeBitmapIndex("ts", "TIMESTAMP(6)")!!.min, "a timestamp at precision 6 as its microseconds")
        assertEquals(-1.5, files[0].rangeBitmapIndex("x", "DOUBLE")!!.min)
        assertEquals(false, files[0].rangeBitmapIndex("b", "BOOLEAN")!!.min)
        // File 2: a thousand distinct values a column — `n`'s dictionary cut into 32-byte chunks
        // (a first key in the header and eight more behind it) and `s`'s into 64-byte ones (the
        // first key, then seven `v0000`-shaped keys of nine bytes each behind sixteen offsets).
        val n2 = files[1].rangeBitmapIndex("n", "INT")!!
        assertEquals(1000, n2.rowCount); assertEquals(1000, n2.cardinality); assertEquals(112, n2.chunkCount)
        assertEquals(100, n2.min); assertEquals(1099, n2.max)
        assertEquals(400, n2.codeOf(500)); assertEquals(500, n2.keyOf(400))
        assertEquals(-1, n2.codeOf(99), "below the first key: the code it would take is 0")
        assertEquals(-1001, n2.codeOf(1100), "past the last key: it would take 1000")
        val s2 = files[1].rangeBitmapIndex("s", "STRING")!!
        assertEquals(125, s2.chunkCount)
        assertEquals(500, s2.codeOf(key("s", "v0500")))
        assertEquals(-502, s2.codeOf(key("s", "v0500a")), "between v0500 and v0501: it would take 501")
        assertEquals(-1, s2.codeOf(key("s", "a"))); assertEquals(-1001, s2.codeOf(key("s", "z")))
        assertEquals(1, files[1].rangeBitmapIndex("amt", "DECIMAL(10, 2)")!!.chunkCount, "a thousand longs fit the 16 KB default")
        assertEquals(2, files[1].rangeBitmapIndex("b", "BOOLEAN")!!.cardinality)
        // File 3: every row 7 — cardinality 1, min and max one key, no slice needed to answer.
        val n3 = files[2].rangeBitmapIndex("n", "INT")!!
        assertEquals(2, n3.rowCount); assertEquals(1, n3.cardinality); assertEquals(7, n3.min); assertEquals(7, n3.max)
        assertEquals(setOf(0, 1), n3.eq(7).bits()); assertEquals(setOf(0, 1), n3.gte(7).bits()); assertEquals(emptySet(), n3.gt(7).bits())
        // File 4: every value null over three rows — cardinality 0, no dictionary, no min or max.
        val n4 = files[3].rangeBitmapIndex("n", "INT")!!
        assertEquals(3, n4.rowCount); assertEquals(0, n4.cardinality); assertEquals(0, n4.chunkCount); assertNull(n4.min); assertNull(n4.max)
        assertEquals(setOf(0, 1, 2), n4.isNull().bits()); assertEquals(emptySet(), n4.isNotNull().bits())
        assertEquals(emptySet(), n4.lte(Int.MAX_VALUE).bits()); assertEquals(emptySet(), n4.notEq(0).bits())
    }

    @Test
    fun `a literal is keyed the way KeyFactory converts it, and a type the factory refuses is not keyed`() {
        assertEquals(1250L, paimonRangeBitmapKey("DECIMAL(10, 2)", BigDecimal("12.50")))
        assertNull(paimonRangeBitmapKey("DECIMAL(10, 2)", BigDecimal("7.005")), "a literal the scale cannot hold is not compared")
        assertNull(paimonRangeBitmapKey("DECIMAL(20, 2)", BigDecimal("1")), "the factory refuses a decimal past precision 18")
        assertEquals(19787, paimonRangeBitmapKey("DATE", LocalDate.of(2024, 3, 5)))
        assertEquals(1_709_632_800_123_456L, paimonRangeBitmapKey("TIMESTAMP(6)", LocalDateTime.parse("2024-03-05T10:00:00.123456")))
        assertEquals(1_709_632_800_123L, paimonRangeBitmapKey("TIMESTAMP(3)", LocalDateTime.parse("2024-03-05T10:00:00.123456")), "millis at precision 3 and below")
        assertNull(paimonRangeBitmapKey("TIMESTAMP(9)", LocalDateTime.parse("2024-03-05T10:00:00")), "the factory refuses a timestamp past precision 6")
        assertTrue(paimonRangeBitmapKey("STRING", "x") is ByteArray)
        assertEquals(1.5, paimonRangeBitmapKey("DOUBLE", 1.5)); assertEquals(true, paimonRangeBitmapKey("BOOLEAN", true))
        assertEquals(7.toByte(), paimonRangeBitmapKey("TINYINT", 7)); assertEquals(7.toShort(), paimonRangeBitmapKey("SMALLINT", 7))
        assertNull(paimonRangeBitmapKey("ARRAY<INT>", listOf(1)))
        assertNull(PaimonBitmapKeyType.ofRangeBitmap("MAP<INT, INT>"))
        assertEquals(PaimonBitmapKeyType.LONG, PaimonBitmapKeyType.ofRangeBitmap("DECIMAL(18, 4)"))
        assertNull(PaimonRangeBitmapIndex.decode(files[0].columns.getValue("n").single().bytes!!, "DECIMAL(19, 0)"))
    }

    /**
     * Every `[index-rows]` case the oracle printed, the rows over files 1..4. The one place the
     * two disagree is `IS NULL` on file 4: at cardinality 0 `RangeBitmap.isNull` answers
     * `bitmapOf(0, rid - 1)`, the first and last of its three rows, where the file holds three
     * nulls — a verdict either way, and this reads the three.
     */
    @Test
    fun `every operator's rows agree with FileIndexPredicate over every file, but the all-null file's IS NULL`() {
        val oracle = listOf(
            "n < 0" to "1/0/0/0", "n <= -5" to "1/0/0/0", "n < -5" to "0/0/0/0",
            "n = 3" to "2/0/0/0", "n <> 3" to "2/1000/2/0", "n = 4" to "0/0/0/0",
            "n > 3" to "1/1000/2/0", "n >= 4" to "1/1000/2/0", "n > 4" to "1/1000/2/0",
            "n <= 6" to "3/0/0/0", "n < 4" to "3/0/0/0",
            "n BETWEEN 4 AND 6" to "0/0/0/0", "n BETWEEN 4 AND 8" to "0/0/2/0",
            "n > 1000" to "0/99/0/0", "n >= 1099" to "0/1/0/0", "n > 1099" to "0/0/0/0", "n BETWEEN 250 AND 749" to "0/500/0/0",
            "n = 7" to "0/0/2/0", "n <> 7" to "4/1000/0/0", "n IS NULL" to "1/0/0/2", "n IS NOT NULL" to "4/1000/2/0",
            "n IN (3, 10)" to "3/0/0/0", "n IN (4, 5, 6)" to "0/0/0/0",
            "n = 3 AND s = 'charlie'" to "0/0/0/0", "n = 3 OR s = 'charlie'" to "3/0/0/0",
            "s = 'bravo'" to "2/0/0/0", "s <> 'bravo'" to "2/1000/2/0", "s > 'bravo'" to "1/1000/2/0", "s >= 'bravo'" to "3/1000/2/0", "s < 'bravo'" to "1/0/0/0",
            "s > 'b'" to "3/1000/2/0", "s < 'b'" to "1/0/0/0", "s BETWEEN 'b' AND 'c'" to "2/0/0/0",
            "s > 'v0500'" to "0/499/0/0", "s >= 'v0500a'" to "0/499/0/0", "s BETWEEN 'v0123' AND 'v0456'" to "0/334/0/0", "s = 'v0999'" to "0/1/0/0",
            "s = 'seven'" to "0/0/2/0", "s <> 'seven'" to "4/1000/0/0", "s IS NULL" to "1/0/0/2", "s IS NOT NULL" to "4/1000/2/0",
            "amt > 50" to "1/0/0/0", "amt < 0" to "1/0/0/0", "amt = 0.75" to "2/0/0/0", "amt = 7.00" to "0/1/2/0", "amt BETWEEN 1 AND 5" to "0/401/0/0",
            "d < '2024-03-05'" to "1/0/0/0", "d = '2024-03-05'" to "2/0/0/0", "d > '2024-03-07'" to "1/1000/0/0", "d BETWEEN '2024-03-02' AND '2024-03-04'" to "0/0/0/0",
            "ts < '2024-03-01 10:00:00.000005'" to "1/0/0/0", "ts = '2024-03-01 10:00:00.000005'" to "2/0/0/0", "ts >= '2024-03-01 10:00:00.000009'" to "1/1000/2/0",
            "ts BETWEEN '2024-03-01 10:00:00.000002' AND '2024-03-01 10:00:00.000003'" to "0/0/0/0",
            "x < 0" to "1/80/0/0", "x <= 0.25" to "3/83/0/0", "x = 0.25" to "2/1/0/0", "x > 100" to "0/119/0/0", "x BETWEEN -1 AND 1" to "2/17/0/0", "x <> 7.0" to "4/999/0/0",
            "b = true" to "2/500/2/0", "b = false" to "2/500/0/0", "b <> true" to "2/500/0/0", "b > false" to "2/500/2/0", "b IS NULL" to "1/0/0/2",
        )
        var quirks = 0
        for ((filter, expected) in oracle) {
            val parsed = (parseScanFilter(filter) as ScanFilterParse.Parsed).filter.pushNegation()
            val actual = files.map { index ->
                val rows = parsed.indexRows { p ->
                    val type = types.getValue(p.column)
                    val range = index.rangeBitmapIndex(p.column, type) ?: return@indexRows IndexRows.Remain
                    val value = if (p.op.takesLiteral) parseLiteral(p.literal, paimonTypeAsIceberg(type)!!)!!.let { paimonRangeBitmapKey(type, it)!! } else null
                    IndexRows.Rows(range.rowsMatching(p.op, value)!!)
                }
                when (rows) {
                    is IndexRows.Rows -> rows.set.cardinality()
                    else -> error("every term here is indexed: $filter")
                }
            }
            val paimon = expected.split("/").map { it.toInt() }
            if (filter.endsWith("IS NULL")) {
                quirks++
                assertEquals(paimon.dropLast(1), actual.dropLast(1), filter)
                assertEquals(2, paimon.last(), "$filter: Paimon's two of three")
                assertEquals(3, actual.last(), "$filter: the three the file holds")
            } else {
                assertEquals(paimon, actual, filter)
            }
        }
        assertEquals(3, quirks)
    }
}
