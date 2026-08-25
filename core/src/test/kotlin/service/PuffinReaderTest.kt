package service

import model.GraphNode
import model.UnifiedTableModel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The Puffin reader, against a real Iceberg-written file and against the format's own edges.
 *
 * The checked-in `v3` fixture is the only genuine oracle here: Iceberg 1.8.1 wrote it, and it
 * settles the outer layout — where the blob starts, what the length prefix covers, and that the
 * checksum is a plain CRC-32. What it cannot settle is anything it does not contain, and it
 * contains exactly one shape: a single array container holding one position. The rest of the
 * containers are built here from the spec text, which is a different act from writing the decoder
 * and is the closest thing to an oracle available without a Roaring library on the classpath.
 */
class PuffinReaderTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun vectorFiles(): List<Path> =
        File(repoRoot, "example/iceberg/default/v3/data")
            .listFiles { f: File -> f.name.endsWith(".puffin") }
            .orEmpty()
            .sortedBy { it.name }
            .map { it.toPath() }

    @Test
    fun `the fixture's footer names one deletion vector and where it sits`() {
        val files = vectorFiles()
        assertTrue(files.size >= 2, "the v3 fixture should carry two deletion vectors, found ${files.size}")

        files.forEach { path ->
            val footer = PuffinReader.readFooter(path)
            val blob = footer.blobs.single()
            assertEquals(PuffinReader.DELETION_VECTOR_V1, blob.type)
            assertTrue(
                blob.referencedDataFile?.endsWith(".parquet") == true,
                "the spec requires referenced-data-file on a deletion vector: ${blob.properties}",
            )
            assertEquals(1L, blob.recordedCardinality, "each fixture vector deletes one row")
            assertEquals(
                null, blob.compressionCodec,
                "the spec requires deletion-vector-v1 to omit compression-codec",
            )
            assertEquals(-1L, blob.snapshotId, "Puffin v1 cannot know the snapshot, so it writes -1")
            assertTrue(footer.createdBy?.contains("Iceberg") == true, "written by ${footer.createdBy}")
        }
    }

    /**
     * The whole point: which rows a scan must drop.
     *
     * Both fixture vectors delete row 0 of the file they reference — `v3` is the format-version-3
     * representation of what `mor` carries as positional deletes, and each one covers a
     * single-row data file.
     */
    @Test
    fun `the fixture's vectors decode to the positions they mark`() {
        vectorFiles().forEach { path ->
            val blob = PuffinReader.readFooter(path).blobs.single()
            val vector = PuffinReader.readDeletionVector(path, blob.offset, blob.length, blob.referencedDataFile)

            assertEquals(listOf(0L), vector.positions, "in $path")
            assertEquals(1L, vector.cardinality)
            assertTrue(vector.checksumMatches, "the blob's own CRC-32 should agree with its bytes")
            assertEquals(blob.recordedCardinality, vector.cardinality, "decoded count against the writer's")
            assertTrue(!vector.truncated)
        }
    }

    /** The manifest records the same coordinates, so the footer is not on the critical path. */
    @Test
    fun `a vector reads the same from the manifest's coordinates as from the footer's`() {
        val path = vectorFiles().first()
        val blob = PuffinReader.readFooter(path).blobs.single()
        assertEquals(4L, blob.offset, "the first blob follows the four magic bytes")
        assertEquals(
            PuffinReader.readDeletionVector(path, blob.offset, blob.length).positions,
            PuffinReader.readDeletionVector(path, 4, blob.length).positions,
        )
    }

    /**
     * The wiring, not the reader: that a graph built from the fixture actually reaches the blob.
     *
     * Coverage measures code that runs in tests, not whether production calls it. The reader had a
     * passing suite of its own while nothing in the app opened a single vector, and the only thing
     * that would have said so is an assertion starting from a `GraphModel`.
     */
    @Test
    fun `a graph built from the fixture decodes the vectors its file nodes carry`() {
        val dir = File(repoRoot, "example/iceberg/default/v3")
        val graph = GraphLayoutService.layoutGraph(
            UnifiedTableModel(dir.toPath()), showRows = false,
        )
        val vectors = graph.nodes.filterIsInstance<GraphNode.FileNode>().filter { it.isDeletionVector }
        assertEquals(2, vectors.size, "the v3 fixture holds two deletion vectors")

        vectors.forEach { node ->
            val decoded = node.deletionVector
            assertTrue(decoded != null, "${node.id} carries a vector the graph never opened")
            assertEquals(listOf(0L), decoded.positions, "${node.id} deletes row 0")
            assertTrue(decoded.checksumMatches)
            assertEquals(
                1L, decoded.recordedCardinality,
                "the manifest's record_count reaches the panel, or it says 'not recorded' about a " +
                    "figure the writer did record",
            )
            assertTrue(decoded.cardinalityAgrees)

            // Two statements written independently of each other about the same vector: the
            // manifest's `record_count` and the Puffin footer's `cardinality` property. Nothing on
            // a read path compares them, and this is the suite's only place that does.
            val footer = PuffinReader.readFooter(Path.of(node.localPath!!)).blobs.single()
            assertEquals(
                footer.recordedCardinality, decoded.recordedCardinality,
                "the manifest and the Puffin footer disagree about ${node.id}",
            )
        }

        val ordinary = graph.nodes.filterIsInstance<GraphNode.FileNode>().filterNot { it.isDeletionVector }
        assertTrue(ordinary.isNotEmpty(), "and the data files are not mistaken for vectors")
        assertTrue(ordinary.all { it.deletionVector == null })
    }

    // ── Containers the fixture does not have, built from the spec ────────────────────────────

    /** Wraps a 32-bit Roaring stream in the blob framing: length, magic, vector, CRC-32. */
    private fun blobOf(roaring32: ByteArray, key: Int = 0): ByteArray {
        val vector = ByteBuffer.allocate(12 + roaring32.size).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(1)          // one 32-bit bitmap
            .putInt(key)         // its high-32 key
            .put(roaring32)
            .array()
        val magicAndVector = PUFFIN_VECTOR_MAGIC + vector
        val crc = CRC32().apply { update(magicAndVector) }.value.toInt()
        return ByteBuffer.allocate(4 + magicAndVector.size + 4).order(ByteOrder.BIG_ENDIAN)
            .putInt(magicAndVector.size)
            .put(magicAndVector)
            .putInt(crc)
            .array()
    }

    private val PUFFIN_VECTOR_MAGIC = byteArrayOf(0xD1.toByte(), 0xD3.toByte(), 0x39, 0x64)

    /** `SERIAL_COOKIE_NO_RUNCONTAINER`, so an offset header is present and must be skipped. */
    private fun arrayContainer(values: List<Int>): ByteArray {
        val body = ByteBuffer.allocate(16 + 2 * values.size).order(ByteOrder.LITTLE_ENDIAN)
        body.putInt(12346).putInt(1)
        body.putShort(0).putShort((values.size - 1).toShort())
        body.putInt(16)      // the container starts right after this offset header
        values.forEach { body.putShort(it.toShort()) }
        return body.array()
    }

    @Test
    fun `an array container yields every value it lists`() {
        val values = listOf(0, 1, 5, 4095)
        val vector = PuffinReader.decodeDeletionVector(blobOf(arrayContainer(values)))
        assertEquals(values.map { it.toLong() }, vector.positions)
        assertTrue(vector.checksumMatches)
    }

    /**
     * Above 4,096 the same bytes would mean something else. The spec decides by cardinality, and
     * this is the case where deciding by container size instead would silently be wrong.
     */
    @Test
    fun `a bitset container is read as 8 KiB of words, not as an array`() {
        val present = (0 until 5_000).toList()   // > 4096, so a bitset by the spec's rule
        val words = LongArray(1_024)
        present.forEach { words[it / 64] = words[it / 64] or (1L shl (it % 64)) }

        val body = ByteBuffer.allocate(16 + 8 * 1_024).order(ByteOrder.LITTLE_ENDIAN)
        body.putInt(12346).putInt(1)
        body.putShort(0).putShort((present.size - 1).toShort())
        body.putInt(16)
        words.forEach { body.putLong(it) }

        val vector = PuffinReader.decodeDeletionVector(blobOf(body.array()))
        assertEquals(present.size.toLong(), vector.cardinality)
        assertEquals(present.take(PuffinReader.MAX_POSITIONS).map { it.toLong() }, vector.positions)
        assertTrue(vector.truncated, "5,000 positions is past the cap, and the count still says 5,000")
    }

    /**
     * The exact boundary the spec puts the array/bitset decision on.
     *
     * Every other container test sits far from it — four values and five thousand — so a decoder
     * that used `< 4096` instead of `<= 4096` would pass all of them and misread precisely one
     * cardinality. 4,096 values is an array; 4,097 is a bitset; the bytes are indistinguishable
     * without the rule.
     */
    @Test
    fun `4096 values is an array and 4097 is a bitset`() {
        val array = (0 until 4_096).toList()
        assertEquals(
            array.size.toLong(),
            PuffinReader.decodeDeletionVector(blobOf(arrayContainer(array))).cardinality,
            "4,096 is the largest cardinality an array container holds",
        )

        val bitset = (0 until 4_097).toList()
        val words = LongArray(1_024)
        bitset.forEach { words[it / 64] = words[it / 64] or (1L shl (it % 64)) }
        val body = ByteBuffer.allocate(16 + 8 * 1_024).order(ByteOrder.LITTLE_ENDIAN)
        body.putInt(12346).putInt(1)
        body.putShort(0).putShort((bitset.size - 1).toShort())
        body.putInt(16)
        words.forEach { body.putLong(it) }
        assertEquals(
            bitset.size.toLong(),
            PuffinReader.decodeDeletionVector(blobOf(body.array())).cardinality,
            "one more than that is a bitset, and reading it as an array runs off the end",
        )
    }

    /**
     * A run container is only reachable through the `SERIAL_COOKIE` header and its run bitset —
     * the other cookie promises there are none.
     */
    @Test
    fun `a run container expands its runs`() {
        // Two runs, from the spec's own example: 1..11 and 31..33.
        val body = ByteBuffer.allocate(4 + 1 + 4 + 2 + 8).order(ByteOrder.LITTLE_ENDIAN)
        body.putInt(12347)                       // SERIAL_COOKIE, (1 - 1) << 16 = 0 containers-1
        body.put(1)                              // run bitset: container 0 is a run container
        body.putShort(0).putShort(13.toShort())  // key 0, cardinality 14
        body.putShort(2)                         // two runs
        body.putShort(1).putShort(10)            // 1, then 10 more → 1..11
        body.putShort(31).putShort(2)            // 31, then 2 more → 31..33

        val vector = PuffinReader.decodeDeletionVector(blobOf(body.array()))
        assertEquals((1L..11L).toList() + listOf(31L, 32L, 33L), vector.positions)
    }

    /** The high-32 key is what makes a position exceed `Int.MAX_VALUE`. */
    @Test
    fun `a position beyond 32 bits comes back whole`() {
        val vector = PuffinReader.decodeDeletionVector(blobOf(arrayContainer(listOf(7)), key = 3))
        assertEquals(listOf((3L shl 32) or 7L), vector.positions)
        assertTrue(vector.positions.single() > Int.MAX_VALUE)
    }

    // ── Refusals ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a corrupted vector is reported rather than believed`() {
        val blob = blobOf(arrayContainer(listOf(0, 1)))
        blob[blob.size - 1] = (blob[blob.size - 1] + 1).toByte()
        val vector = PuffinReader.decodeDeletionVector(blob)
        assertTrue(!vector.checksumMatches, "a wrong CRC-32 has to surface, not be dropped")
        assertEquals(listOf(0L, 1L), vector.positions, "and the positions are still offered, marked")
    }

    @Test
    fun `a blob without the vector magic is refused`() {
        val blob = blobOf(arrayContainer(listOf(0)))
        blob[5] = 0
        assertFailsWith<PuffinReader.PuffinFormatException> { PuffinReader.decodeDeletionVector(blob) }
    }

    @Test
    fun `a file that is not Puffin is refused by name`() {
        val notPuffin = File(repoRoot, "settings.gradle.kts").toPath()
        val thrown = assertFailsWith<PuffinReader.PuffinFormatException> { PuffinReader.readFooter(notPuffin) }
        assertTrue(thrown.message!!.contains("PFA1"), thrown.message!!)
    }
}
