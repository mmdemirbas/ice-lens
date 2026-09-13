package model

import service.PaimonFileIndexReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The file index decoded, and the two hashes it is probed with.
 *
 * The bloom filter is only as good as the hash, and a hash is the one thing here a document
 * cannot settle: the xxHash64 vectors are the published ones, and the values the `fa` and `fi`
 * scripts wrote are held to *present* in the filters Paimon wrote over them — a wrong hash says
 * absent of a value that is there, which is the direction that loses rows, and a filter built
 * over a thousand items has one false positive in ten, so "absent" is asserted only through the
 * plan oracle in [PaimonFileIndexPruningTest].
 */
class PaimonFileIndexTest {

    @Test
    fun `xxHash64 matches the published vectors`() {
        fun h(s: String) = xxHash64(s.toByteArray(Charsets.UTF_8)).toULong().toString(16).uppercase()
        assertEquals("EF46DB3751D8E999", h(""))
        assertEquals("D24EC4F1A98C6E5B", h("a"))
        assertEquals("44BC2CF5AD770999", h("abc"))
        // 39 bytes: the 32-byte stripe once, then 7 bytes of tail through the 4-byte and 1-byte steps.
        assertEquals("FBCEA83C8A378BF1", h("Nobody inspects the spammish repetition"))
    }

    @Test
    fun `the index beside fa's first file names both columns as bloom filters, and holds what the file holds`() {
        val entry = FixtureCatalog.paimonModel("fa").snapshots.first { it.metadata.id == 1L }.deltaManifests.single().entries.single()
        val file = entry.metadata.file!!
        assertNull(file.embeddedFileIndex)
        val index = PaimonFileIndexReader.read(entry.path.resolveSibling(file.extraFiles!!.single()))
        // The head lists the columns in the writer's map order, which is v then k here — not the schema's.
        assertEquals(listOf("v", "k"), index.columns.keys.toList())
        assertEquals("bloom-filter on v, k", index.describe())
        assertEquals(1290, index.containerBytes)
        val v = assertNotNull(index.bloomFilter("v"))
        for (present in listOf("alpha", "beta", "the quick brown fox jumps over the lazy dog")) {
            assertTrue(v.mightContain(paimonFastHash("STRING", present)!!), present)
        }
        val k = assertNotNull(index.bloomFilter("k"))
        for (present in 1..3) assertTrue(k.mightContain(paimonFastHash("INT NOT NULL", present)!!), "k = $present")
        // 1,000 items at fpp 0.1: 4,800 bits and three hash functions, BloomFilter64's arithmetic.
        assertEquals(4800, v.numBits)
        assertEquals(3, v.numHashFunctions)
    }

    @Test
    fun `the index embedded in fa's second entry decodes from the entry's own bytes`() {
        val entry = FixtureCatalog.paimonModel("fa").snapshots.first { it.metadata.id == 2L }.deltaManifests.single().entries.single()
        val file = entry.metadata.file!!
        assertTrue(file.extraFiles.isNullOrEmpty())
        val index = PaimonFileIndexReader.decode(file.embeddedFileIndex!!)
        assertEquals("bloom-filter on v, k", index.describe())
        val v = assertNotNull(index.bloomFilter("v"))
        assertTrue(v.mightContain(paimonFastHash("STRING", "delta")!!))
        assertTrue(v.mightContain(paimonFastHash("STRING", "epsilon")!!))
        val k = assertNotNull(index.bloomFilter("k"))
        assertTrue(k.mightContain(paimonFastHash("INT", 4)!!))
        assertTrue(k.mightContain(paimonFastHash("INT", 6)!!))
        assertEquals(480, k.numBits)
    }

    /**
     * `ft`'s embedded index over its three temporal columns, held to the values the script wrote
     * and to the plan oracle's two negatives — `ts = 2024-03-07 00:00:00` and
     * `lz = 2024-03-06 11:30:00.001Z`, both inside the file's bounds and both skipped by Paimon's
     * plan, which a hash over milliseconds rather than microseconds would answer "may hold".
     */
    @Test
    fun `ft's index hashes a timestamp over its microseconds and a date over its epoch day`() {
        val entry = FixtureCatalog.paimonModel("ft").snapshots.single().deltaManifests.single().entries.single()
        val index = PaimonFileIndexReader.decode(entry.metadata.file!!.embeddedFileIndex!!)
        assertEquals(setOf("ts", "lz", "d"), index.columns.keys)
        val ts = assertNotNull(index.bloomFilter("ts"))
        val ldt = { s: String -> java.time.LocalDateTime.parse(s.replace(' ', 'T')) }
        for (present in listOf("2024-03-05 10:00:00.123456", "2024-03-06 11:30:00", "2024-03-07 00:00:00.000001")) {
            assertTrue(ts.mightContain(paimonFastHash("TIMESTAMP(6)", ldt(present))!!), present)
        }
        assertFalse(ts.mightContain(paimonFastHash("TIMESTAMP(6)", ldt("2024-03-07 00:00:00"))!!))
        assertFalse(ts.mightContain(paimonFastHash("TIMESTAMP(6)", ldt("2024-03-05 10:00:00.123"))!!))
        val lz = assertNotNull(index.bloomFilter("lz"))
        val lzType = "TIMESTAMP(6) WITH LOCAL TIME ZONE"
        for (present in listOf("2024-03-05T10:00:00.123456Z", "2024-03-06T11:30:00Z", "2024-03-07T00:00:00.000001Z")) {
            assertTrue(lz.mightContain(paimonFastHash(lzType, java.time.Instant.parse(present))!!), present)
        }
        assertFalse(lz.mightContain(paimonFastHash(lzType, java.time.Instant.parse("2024-03-06T11:30:00.001Z"))!!))
        // A zone-less literal against the zoned column is read at UTC, as the bounds comparison reads it.
        assertEquals(paimonFastHash(lzType, java.time.Instant.parse("2024-03-06T11:30:00Z")), paimonFastHash(lzType, ldt("2024-03-06 11:30:00")))
        val d = assertNotNull(index.bloomFilter("d"))
        for (present in 5..7) assertTrue(d.mightContain(paimonFastHash("DATE", java.time.LocalDate.of(2024, 3, present))!!), "d = $present")
        assertFalse(d.mightContain(paimonFastHash("DATE", java.time.LocalDate.of(2024, 3, 8))!!))
        // Precision 3 and below hash the milliseconds instead — FastHash's rule, with no writer in the corpus to hold it to.
        assertEquals(wangHash(1_709_632_800_123L), paimonFastHash("TIMESTAMP(3)", ldt("2024-03-05 10:00:00.123456")))
        assertEquals(wangHash(1_709_632_800_123_456L), paimonFastHash("TIMESTAMP", ldt("2024-03-05 10:00:00.123456")))
        assertEquals(wangHash(36_000_123L), paimonFastHash("TIME", java.time.LocalTime.parse("10:00:00.123456")))
    }

    /**
     * `fb`'s three bitmap indexes — one embedded, two in `.index` files — read as the dictionaries
     * they are, and held to `FileIndexPredicate` asked over every file for every operator
     * (`paimon-scan-plans.scala`, the `[index-all]` sections): `=` is membership, `<>` is empty
     * only when every row holds the value, nulls counted among the rows, `IS NULL` is the null
     * bitmap's existence, `IS NOT NULL` any value's.
     */
    @Test
    fun `fb's bitmap indexes are dictionaries, and answer the four operators as FileIndexPredicate does`() {
        val model = FixtureCatalog.paimonModel("fb")
        fun indexAt(snapshot: Long): PaimonFileIndex {
            val entry = model.snapshots.first { it.metadata.id == snapshot }.deltaManifests.single().entries.single()
            val file = entry.metadata.file!!
            return file.embeddedFileIndex?.let(PaimonFileIndexReader::decode)
                ?: PaimonFileIndexReader.read(entry.path.resolveSibling(file.extraFiles!!.single { it.endsWith(".index") }))
        }
        val f1 = indexAt(1); val f2 = indexAt(2); val f3 = indexAt(3)
        assertEquals("bitmap on c, n; bloom-filter on n", f1.describe())
        val red = paimonBitmapKey("STRING", "red")!!
        val green = paimonBitmapKey("STRING", "green")!!
        val orange = paimonBitmapKey("STRING", "orange")!!

        // File 1: red, green, red, null — the embedded one.
        val c1 = assertNotNull(f1.bitmapIndex("c", "STRING"))
        assertEquals(PaimonBitmapIndex.VERSION_2, c1.version)
        assertEquals(4, c1.rowCount); assertEquals(2, c1.distinctValues); assertTrue(c1.hasNull)
        assertTrue(c1.contains(red)); assertTrue(c1.contains(green)); assertFalse(c1.contains(orange))
        assertEquals(2, c1.cardinalityOf(red)); assertEquals(1, c1.cardinalityOf(green)); assertEquals(1, c1.nullCount)
        // File 2: red, red — `c <> 'red'` is the one thing only a dictionary rules out.
        val c2 = assertNotNull(f2.bitmapIndex("c", "STRING"))
        assertEquals(2, c2.rowCount); assertEquals(1, c2.distinctValues); assertFalse(c2.hasNull)
        assertEquals(c2.rowCount, c2.cardinalityOf(red), "every row is red")
        assertFalse(c2.contains(green))
        // File 3: null, null — no value at all, so `IS NOT NULL` finds nothing and `<>` still remains.
        val c3 = assertNotNull(f3.bitmapIndex("c", "STRING"))
        assertEquals(2, c3.rowCount); assertEquals(0, c3.distinctValues); assertTrue(c3.hasNull)
        assertEquals(2, c3.nullCount); assertFalse(c3.contains(red)); assertEquals(0, c3.cardinalityOf(red))
        // n carries both indexes; the bitmap's dictionary is exact where the bloom filter only may.
        val n1 = assertNotNull(f1.bitmapIndex("n", "INT"))
        assertEquals(4, n1.distinctValues); assertFalse(n1.hasNull)
        for (v in 1..4) assertTrue(n1.contains(paimonBitmapKey("INT", v)!!), "n = $v")
        assertFalse(n1.contains(paimonBitmapKey("INT", 5)!!))
        assertTrue(assertNotNull(f1.bloomFilter("n")).mightContain(paimonFastHash("INT", 3)!!))
        val n3 = assertNotNull(f3.bitmapIndex("n", "INT"))
        assertEquals(1, n3.cardinalityOf(paimonBitmapKey("INT", 7)!!)); assertEquals(1, n3.cardinalityOf(paimonBitmapKey("INT", 8)!!))
        // A key is typed the way the meta writes it, so a string is its bytes and a date its epoch day.
        assertTrue(red is ByteArray)
        assertEquals(19787, paimonBitmapKey("DATE", java.time.LocalDate.of(2024, 3, 5)))
        assertEquals(1_709_632_800_123_456L, paimonBitmapKey("TIMESTAMP(6)", java.time.LocalDateTime.parse("2024-03-05T10:00:00.123456")))
        assertNull(paimonBitmapKey("DECIMAL(9, 2)", java.math.BigDecimal.ONE))
        assertNull(PaimonBitmapIndex.decode(f1.columns.getValue("c").single { it.type == PaimonFileIndex.BITMAP }.bytes!!, "DECIMAL(9, 2)"))
    }

    /**
     * A v2 meta with two blocks, written here the way `BitmapFileIndexMetaV2.serialize` writes
     * one — `fb`'s dictionaries fit one block each, so the directory search is exercised only
     * by hand: a key before the first block, inside each block, between two keys, past the last.
     * Single-row entries carry no bitmap, so the body is empty and the offsets are `-1 - row`.
     */
    @Test
    fun `a v2 bitmap index is searched block by block`() {
        val out = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(out)
        fun key(s: String) { val b = s.toByteArray(); dos.writeInt(b.size); dos.write(b) }
        dos.writeByte(2)
        dos.writeInt(4); dos.writeInt(4); dos.writeBoolean(false)
        // Two blocks: "apple","banana" then "cherry","date"; each block is 4 + 2 * (4 + len + 8) bytes.
        val block1 = 4 + (4 + 5 + 8) + (4 + 6 + 8)
        dos.writeInt(2); key("apple"); dos.writeInt(0); key("cherry"); dos.writeInt(block1)
        val block2 = 4 + (4 + 6 + 8) + (4 + 4 + 8)
        dos.writeInt(block1 + block2)
        dos.writeInt(2); key("apple"); dos.writeInt(-1 - 0); dos.writeInt(-1); key("banana"); dos.writeInt(-1 - 1); dos.writeInt(-1)
        dos.writeInt(2); key("cherry"); dos.writeInt(-1 - 2); dos.writeInt(-1); key("date"); dos.writeInt(-1 - 3); dos.writeInt(-1)
        val index = assertNotNull(PaimonBitmapIndex.decode(out.toByteArray(), "STRING"))
        assertEquals(4, index.rowCount); assertEquals(4, index.distinctValues); assertFalse(index.hasNull)
        for (present in listOf("apple", "banana", "cherry", "date")) assertTrue(index.contains(paimonBitmapKey("STRING", present)!!), present)
        for (absent in listOf("aardvark", "apples", "blueberry", "cabbage", "d", "elderberry")) assertFalse(index.contains(paimonBitmapKey("STRING", absent)!!), absent)
        assertEquals(1, index.cardinalityOf(paimonBitmapKey("STRING", "date")!!))
        assertEquals(3, index.find(paimonBitmapKey("STRING", "date")!!)!!.singleRow)
        assertEquals(0, index.nullCount)
    }

    @Test
    fun `a type FastHash does not hash is not hashed here either`() {
        assertNull(paimonFastHash("BOOLEAN", true))
        assertNull(paimonFastHash("DECIMAL(9, 2)", java.math.BigDecimal("1.00")))
        assertNull(paimonFastHash("ARRAY<INT>", listOf(1)))
        // An int is widened to a long before Wang's hash, so the two spellings of one value agree.
        assertEquals(paimonFastHash("INT", 7), paimonFastHash("BIGINT", 7L))
        assertEquals(wangHash(7L), paimonFastHash("INT", 7))
    }

    @Test
    fun `a container that is not an index is refused, not misread`() {
        val bad = ByteArray(32)
        val e = runCatching { PaimonFileIndexReader.decode(bad) }.exceptionOrNull()
        assertNotNull(e)
        assertTrue(e.message!!.contains("magic"), e.message)
    }

    @Test
    fun `a bloom filter that was never given a value holds nothing`() {
        // One hash function, 8 bits, all clear.
        val empty = PaimonBloomFilter.decode(byteArrayOf(0, 0, 0, 1, 0))!!
        assertFalse(empty.mightContain(wangHash(1L)))
        assertNull(PaimonBloomFilter.decode(byteArrayOf(0, 0, 0, 0, 0)))
    }
}
