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
