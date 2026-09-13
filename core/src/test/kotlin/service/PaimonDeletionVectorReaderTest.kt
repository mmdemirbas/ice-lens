package service

import model.PaimonUnifiedTableModel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Paimon vector decoded from the bytes Paimon wrote, against the two things its index
 * manifest says about each: which data file, and how many rows. The positions themselves are
 * checked against the scripts — `dv` deleted k IN (2, 1001, 1500) from files holding 1..1000
 * and 1001..1500 in order, `ad` deleted id IN (2, 6) from files holding 1..5 and 6..7.
 */
class PaimonDeletionVectorReaderTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun vectorsOf(fixture: String): Map<String, model.DeletionVector> {
        val model = PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/$fixture").absolutePath))
        val snapshot = model.snapshots.last()
        val index = snapshot.indexFiles.single { it.isDeletionVectorIndex }
        val path = model.path.resolve("index").resolve(assertNotNull(index.fileName))
        return index.deletionVectorRanges.orEmpty().filterNotNull().associate { range ->
            val vector = PaimonDeletionVectorReader.read(
                path, assertNotNull(range.offset).toLong(), assertNotNull(range.length).toLong(), range.dataFileName, range.cardinality,
            )
            assertNotNull(range.dataFileName) to vector
        }
    }

    private fun rowCountsOf(fixture: String): Map<String?, Long?> {
        val model = PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/$fixture").absolutePath))
        return model.snapshots.flatMap { it.baseManifests + it.deltaManifests }.flatMap { it.entries }
            .associate { it.metadata.file?.fileName to it.metadata.file?.rowCount }
    }

    @Test
    fun `a primary-key table's vectors mark the positions of the deleted keys`() {
        val vectors = vectorsOf("dv")
        val rows = rowCountsOf("dv")
        val positionsByRows = vectors.entries.associate { (file, v) -> rows[file] to v.positions }
        // k 2 is the second row of the 1..1000 file; 1001 and 1500 are the first and last of the other.
        assertEquals(mapOf<Long?, List<Long>>(1000L to listOf(1L), 500L to listOf(0L, 499L)), positionsByRows)
        vectors.values.forEach { v ->
            assertTrue(v.checksumMatches, "the CRC-32 after the bitmap should agree with the magic and bitmap")
            assertTrue(v.cardinalityAgrees, "decoded ${v.cardinality} against the manifest's ${v.recordedCardinality}")
            assertFalse(v.truncated)
        }
    }

    @Test
    fun `an append table's vectors mark the positions of the deleted ids`() {
        val vectors = vectorsOf("ad")
        val rows = rowCountsOf("ad")
        val positionsByRows = vectors.entries.associate { (file, v) -> rows[file] to v.positions }
        assertEquals(mapOf<Long?, List<Long>>(5L to listOf(1L), 2L to listOf(0L)), positionsByRows)
        assertTrue(vectors.values.all { it.checksumMatches && it.cardinalityAgrees })
    }

    /** A byte changed in the bitmap is caught by the CRC the writer left after it. */
    @Test
    fun `a corrupted bitmap fails its checksum`() {
        val model = PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/dv").absolutePath))
        val index = model.snapshots.last().indexFiles.single()
        val range = index.deletionVectorRanges.orEmpty().filterNotNull().first()
        val bytes = Files.readAllBytes(model.path.resolve("index").resolve(assertNotNull(index.fileName)))
        val offset = assertNotNull(range.offset)
        val length = assertNotNull(range.length)
        val blob = bytes.copyOfRange(offset, offset + 4 + length + 4)
        assertTrue(PaimonDeletionVectorReader.decode(blob).checksumMatches)
        blob[blob.size - 5] = (blob[blob.size - 5].toInt() xor 0x01).toByte() // the bitmap's last byte
        assertFalse(PaimonDeletionVectorReader.decode(blob).checksumMatches)
    }

    /**
     * The 64-bit form is Iceberg's blob layout copied over, so Iceberg's own writer is its
     * oracle: the `v3` fixture's Puffin blob, read as a Paimon range, decodes to what
     * [PuffinReader] decodes from it.
     */
    @Test
    fun `a Bitmap64 vector is Iceberg's blob and decodes through the same code`() {
        val puffin = File(repoRoot, "example/iceberg/default/v3/data").walkTopDown().filter { it.name.endsWith(".puffin") }.first().toPath()
        val blob = PuffinReader.readFooter(puffin).blobs.single()
        val bytes = Files.newByteChannel(puffin).use { ch ->
            ch.position(blob.offset)
            ByteBuffer.allocate(blob.length.toInt()).also { while (it.hasRemaining()) ch.read(it) }.array()
        }
        assertEquals(1681511377, ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt(), "Iceberg's magic is Paimon's v2 magic read little-endian")
        val theirs = PuffinReader.decodeDeletionVector(bytes)
        val ours = PaimonDeletionVectorReader.decode(bytes, "x", theirs.cardinality)
        assertEquals(theirs.positions, ours.positions)
        assertEquals(theirs.cardinality, ours.cardinality)
        assertTrue(ours.checksumMatches)
    }
}
