package service

import kotlinx.serialization.json.Json
import model.DeletionVector
import model.PuffinFileMetadata
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.zip.CRC32

/**
 * Reads Puffin files — the container Iceberg v3 keeps a deletion vector in.
 *
 * Everything here is against the published format rather than against what one file happened to
 * contain, because the checked-in fixture exercises exactly one shape: a single array container
 * holding one position. A decoder written from that alone agrees with itself and fails on the
 * first table with more than 4,096 deleted rows in a 64k block.
 *
 * Sources, both read directly:
 * - Puffin spec, <https://iceberg.apache.org/puffin-spec/> — file and footer structure,
 *   `deletion-vector-v1` blob layout. Read 2026-08-22.
 * - Roaring format spec, <https://github.com/RoaringBitmap/RoaringFormatSpec> — the 32-bit
 *   serialization and the 64-bit "portable" extension. Read 2026-08-22.
 *
 * One correction the fixture itself supplied: the blob's checksum is a plain **CRC-32**, not
 * CRC-32C. The spec says so, and computing both over the fixture's bytes agrees with the spec —
 * which is why [DeletionVector.checksumMatches] is reported rather than assumed.
 */
object PuffinReader {

    /** `PFA1` — "Puffin Fratercula arctica, version 1". Opens the file and closes the footer. */
    private val MAGIC = byteArrayOf(0x50, 0x46, 0x41, 0x31)

    /** The 4 bytes introducing a serialized delete vector, `D1 D3 39 64`. */
    private val VECTOR_MAGIC = byteArrayOf(0xD1.toByte(), 0xD3.toByte(), 0x39, 0x64)

    private const val SERIAL_COOKIE_NO_RUNCONTAINER = 12346
    private const val SERIAL_COOKIE = 12347
    private const val NO_OFFSET_THRESHOLD = 4

    /** The blob type a v3 deletion vector is stored under. */
    const val DELETION_VECTOR_V1 = "deletion-vector-v1"

    private val json = Json { ignoreUnknownKeys = true }

    /** Beyond this the list is for reading, not for scrolling. The count is still exact. */
    const val MAX_POSITIONS = 1_000

    /** A Puffin file this reader could not make sense of, with the reason in the message. */
    class PuffinFormatException(message: String) : Exception(message)

    /**
     * Reads the footer: what blobs the file holds and where each one sits.
     *
     * The footer is at the end by design, so this seeks rather than streams — a Puffin file next
     * to a large table is not something to read front to back for its table of contents.
     */
    fun readFooter(path: Path): PuffinFileMetadata {
        RandomAccessFile(path.toFile(), "r").use { file ->
            val size = file.length()
            if (size < 4L + 4 + 4 + 4 + 4) {
                throw PuffinFormatException("$size bytes is too short to be a Puffin file")
            }

            val head = ByteArray(4).also { file.readFully(it) }
            if (!head.contentEquals(MAGIC)) {
                throw PuffinFormatException("does not start with the Puffin magic PFA1")
            }

            // Footer: Magic | FooterPayload | FooterPayloadSize (4, LE) | Flags (4) | Magic
            file.seek(size - 12)
            val tail = ByteArray(12).also { file.readFully(it) }
            val buffer = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
            val payloadSize = buffer.getInt()
            val flags = ByteArray(4).also { buffer.get(it) }
            val endMagic = ByteArray(4).also { buffer.get(it) }
            if (!endMagic.contentEquals(MAGIC)) {
                throw PuffinFormatException("does not end with the Puffin magic PFA1")
            }
            if (payloadSize < 0 || payloadSize > size - 20) {
                throw PuffinFormatException("footer claims a $payloadSize-byte payload in a $size-byte file")
            }
            // Flags byte 0, bit 0: the payload is a single LZ4 frame. Iceberg does not write one
            // today, and a wrong guess here would be a JSON parse error blamed on the wrong thing.
            if (flags[0].toInt() and 1 == 1) {
                throw PuffinFormatException("the footer is LZ4-compressed, which this reader does not decompress")
            }

            file.seek(size - 12 - payloadSize)
            val payload = ByteArray(payloadSize).also { file.readFully(it) }
            return runCatching { json.decodeFromString<PuffinFileMetadata>(payload.decodeToString()) }
                .getOrElse { throw PuffinFormatException("the footer is not readable as Puffin JSON: ${it.message}") }
        }
    }

    /**
     * Reads one `deletion-vector-v1` blob and decodes the positions it marks.
     *
     * [offset] and [length] are the blob's own coordinates — the manifest records them on the
     * delete file as `content_offset` and `content_size_in_bytes`, which is why this can be read
     * without the footer at all. Passing the footer's `offset`/`length` gives the same bytes.
     *
     * [referencedDataFile] and [recordedCardinality] are what the *manifest* says about this blob,
     * passed in rather than read here: they are the figures a scan plans against without ever
     * opening the Puffin file, so a disagreement between them and the bytes is the finding.
     */
    fun readDeletionVector(
        path: Path,
        offset: Long,
        length: Long,
        referencedDataFile: String? = null,
        recordedCardinality: Long? = null,
    ): DeletionVector {
        if (length < 12) throw PuffinFormatException("a $length-byte blob is too short to hold a vector")
        val blob = ByteArray(length.toInt())
        RandomAccessFile(path.toFile(), "r").use { file ->
            if (offset < 0 || offset + length > file.length()) {
                throw PuffinFormatException("blob at $offset+$length lies outside a ${file.length()}-byte file")
            }
            file.seek(offset)
            file.readFully(blob)
        }
        return decodeDeletionVector(blob, referencedDataFile, recordedCardinality)
    }

    /**
     * The blob's bytes to positions.
     *
     * Layout, from the spec: a 4-byte big-endian length covering the magic and the vector, the
     * 4-byte magic, the vector, then a 4-byte big-endian CRC-32 of the magic and vector together.
     * Big-endian here and little-endian inside the bitmap is not a mistake in either the spec or
     * this code — the spec says the outer fields were made big-endian for compatibility with
     * Delta's deletion vectors.
     */
    internal fun decodeDeletionVector(
        blob: ByteArray,
        referencedDataFile: String? = null,
        recordedCardinality: Long? = null,
    ): DeletionVector {
        val outer = ByteBuffer.wrap(blob).order(ByteOrder.BIG_ENDIAN)
        val declared = outer.getInt()
        if (declared < 4 || 4 + declared + 4 > blob.size) {
            throw PuffinFormatException("the vector declares $declared bytes, which does not fit its ${blob.size}-byte blob")
        }
        val magic = blob.copyOfRange(4, 8)
        if (!magic.contentEquals(VECTOR_MAGIC)) {
            throw PuffinFormatException("the blob does not carry the deletion-vector magic D1 D3 39 64")
        }

        val checked = blob.copyOfRange(4, 4 + declared)
        val recordedCrc = ByteBuffer.wrap(blob, 4 + declared, 4).order(ByteOrder.BIG_ENDIAN).getInt()
        val computedCrc = CRC32().apply { update(checked) }.value.toInt()

        val positions = mutableListOf<Long>()
        var cardinality = 0L
        forEachPosition(blob.copyOfRange(8, 4 + declared)) { position ->
            cardinality++
            if (positions.size < MAX_POSITIONS) positions.add(position)
        }

        return DeletionVector(
            positions = positions,
            cardinality = cardinality,
            recordedCardinality = recordedCardinality,
            checksumMatches = recordedCrc == computedCrc,
            referencedDataFile = referencedDataFile,
        )
    }

    /**
     * Walks a Roaring bitmap in the 64-bit "portable" layout, emitting every position in order.
     *
     * A callback rather than a list because a deletion vector over a compacted file can hold
     * millions of positions, and the caller wants the count regardless of how many it keeps.
     */
    private fun forEachPosition(vector: ByteArray, emit: (Long) -> Unit) {
        val buffer = ByteBuffer.wrap(vector).order(ByteOrder.LITTLE_ENDIAN)
        val buckets = buffer.getLong()
        if (buckets < 0 || buckets > Int.MAX_VALUE) {
            throw PuffinFormatException("the vector claims $buckets bitmaps")
        }
        repeat(buckets.toInt()) {
            val key = buffer.getInt().toLong() and 0xFFFFFFFFL
            readRoaring32(buffer) { low -> emit((key shl 32) or low) }
        }
    }

    /**
     * One 32-bit Roaring bitmap, from the buffer's current position, leaving it just past the end.
     *
     * The three container kinds are decided the way the spec says and not by their contents: a run
     * container is named by the cookie's run bitset, and the rest are told apart by cardinality
     * alone — 4,096 or fewer is an array, more is a 8 KiB bitset. Guessing from the byte count
     * would be right for every container the fixture has and wrong at the boundary.
     */
    private fun readRoaring32(buffer: ByteBuffer, emit: (Long) -> Unit) {
        val start = buffer.position()
        val cookie = buffer.getInt()

        val containers: Int
        val runFlags: ByteArray?
        when {
            cookie == SERIAL_COOKIE_NO_RUNCONTAINER -> {
                containers = buffer.getInt()
                runFlags = null
            }
            cookie and 0xFFFF == SERIAL_COOKIE -> {
                containers = (cookie ushr 16) + 1
                runFlags = ByteArray((containers + 7) / 8).also { buffer.get(it) }
            }
            else -> throw PuffinFormatException("0x${cookie.toString(16)} is not a Roaring cookie")
        }
        if (containers < 0 || containers > 65_536) {
            throw PuffinFormatException("a Roaring bitmap cannot hold $containers containers")
        }

        val keys = IntArray(containers)
        val cardinalities = IntArray(containers)
        repeat(containers) { i ->
            keys[i] = buffer.getShort().toInt() and 0xFFFF
            cardinalities[i] = (buffer.getShort().toInt() and 0xFFFF) + 1
        }

        // The offset header is present under exactly the two conditions the spec names. It is
        // skipped rather than used: the containers follow in order, so sequential reading needs
        // no random access, and honouring it would mean trusting an index against bytes already
        // in hand.
        val hasOffsets = runFlags == null || containers >= NO_OFFSET_THRESHOLD
        if (hasOffsets) buffer.position(buffer.position() + 4 * containers)

        repeat(containers) { i ->
            val base = keys[i].toLong() shl 16
            val isRun = runFlags != null && (runFlags[i / 8].toInt() shr (i % 8)) and 1 == 1
            when {
                isRun -> {
                    val runs = buffer.getShort().toInt() and 0xFFFF
                    repeat(runs) {
                        val first = buffer.getShort().toInt() and 0xFFFF
                        val extra = buffer.getShort().toInt() and 0xFFFF
                        for (v in first..(first + extra)) emit(base or v.toLong())
                    }
                }
                cardinalities[i] <= 4_096 -> repeat(cardinalities[i]) {
                    emit(base or (buffer.getShort().toLong() and 0xFFFF))
                }
                else -> repeat(1_024) { word ->
                    val bits = buffer.getLong()
                    if (bits != 0L) {
                        for (bit in 0 until 64) {
                            if ((bits shr bit) and 1L == 1L) emit(base or (word * 64L + bit))
                        }
                    }
                }
            }
        }
        check(buffer.position() > start) { "a Roaring bitmap consumed no bytes" }
    }
}
