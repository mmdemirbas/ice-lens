package service

import model.DeletionVector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.BitSet
import java.util.zip.CRC32

/**
 * A Paimon deletion vector, from the index file its index manifest names — the format's answer
 * to the problem Iceberg's Puffin vector solves, and the same shape one level down: one
 * container per bucket holding a blob per data file, each found by the `(offset, length)` the
 * manifest's `_DELETIONS_VECTORS_RANGES` records beside the file name.
 *
 * Read off `DeletionVectorsIndexFile` and `DeletionVector.read` at 1.3.1, and checked against
 * the bytes of the `dv` and `ad` fixtures. The file opens with one version byte (`1`); at each
 * offset sits a 4-byte big-endian `size` covering a magic and a bitmap, then the magic, then
 * the bitmap, then a 4-byte big-endian CRC-32 of the magic and bitmap together — and `size` is
 * what the manifest records as the range's length. Two magics decide the bitmap.
 * `BitmapDeletionVector` (`1581511376` big-endian) is a 32-bit Roaring bitmap in the portable
 * layout, which is a Puffin vector's inner bitmap without the bucket wrapper.
 * `Bitmap64DeletionVector` (`1681511377` little-endian — the bytes `D1 D3 39 64`) is Iceberg's
 * own blob layout copied over, so it is decoded by the same code, and the one difference from
 * Puffin is that the length the manifest records excludes the size and CRC fields where a
 * Puffin manifest's `content_size_in_bytes` includes them.
 */
object PaimonDeletionVectorReader {
    private const val VERSION_V1: Byte = 1
    private const val MAGIC_BITMAP32 = 1581511376
    private const val MAGIC_BITMAP64 = 1681511377

    /** An index file this reader could not make sense of, with the reason in the message. */
    class PaimonIndexFormatException(message: String) : Exception(message)

    /**
     * The vector at [offset] whose magic and bitmap span [length] bytes — the range's own
     * coordinates. [dataFileName] and [recordedCardinality] are what the index manifest says
     * about it, carried onto the result rather than read here so a disagreement is visible.
     */
    fun read(indexFile: Path, offset: Long, length: Long, dataFileName: String? = null, recordedCardinality: Long? = null): DeletionVector =
        decode(readRange(indexFile, offset, length), dataFileName, recordedCardinality)

    /**
     * Every position the vector marks, as a bit set — for a count that has to be exact over a
     * whole file, where [DeletionVector.positions] stops at [PuffinReader.MAX_POSITIONS]. A Paimon
     * position is a row number in one file, so it fits an `Int`; one past that is refused rather
     * than folded.
     */
    fun readPositions(indexFile: Path, offset: Long, length: Long): BitSet {
        val bits = BitSet()
        decodeInto(readRange(indexFile, offset, length)) { position ->
            if (position < 0 || position > Int.MAX_VALUE) throw PaimonIndexFormatException("position $position is past what a bit set holds")
            bits.set(position.toInt())
        }
        return bits
    }

    private fun readRange(indexFile: Path, offset: Long, length: Long): ByteArray {
        if (length < 8) throw PaimonIndexFormatException("a $length-byte range is too short to hold a vector")
        return Files.newByteChannel(indexFile).use { file ->
            val version = ByteBuffer.allocate(1).also { file.position(0); file.read(it) }.get(0)
            if (version != VERSION_V1) throw PaimonIndexFormatException("index file version $version is not the 1 this reads")
            // size + magic + bitmap + crc
            val whole = 4 + length + 4
            if (offset < 1 || offset + whole > file.size()) {
                throw PaimonIndexFormatException("vector at $offset+$whole lies outside a ${file.size()}-byte file")
            }
            file.position(offset)
            file.readFully(whole.toInt())
        }
    }

    internal fun decode(blob: ByteArray, dataFileName: String? = null, recordedCardinality: Long? = null): DeletionVector {
        val positions = mutableListOf<Long>()
        var cardinality = 0L
        val checksumMatches = decodeInto(blob) { position ->
            cardinality++
            if (positions.size < PuffinReader.MAX_POSITIONS) positions.add(position)
        }
        return DeletionVector(positions, cardinality, recordedCardinality, checksumMatches, dataFileName)
    }

    /** Emits every position in order and answers whether the CRC after the bitmap agrees with the bytes. */
    private fun decodeInto(blob: ByteArray, emit: (Long) -> Unit): Boolean {
        val outer = ByteBuffer.wrap(blob).order(ByteOrder.BIG_ENDIAN)
        val size = outer.getInt()
        if (size < 4 || 4 + size + 4 > blob.size) {
            throw PaimonIndexFormatException("the vector declares $size bytes, which does not fit its ${blob.size}-byte range")
        }
        val magicBigEndian = outer.getInt()
        val magicLittleEndian = ByteBuffer.wrap(blob, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
        val checked = blob.copyOfRange(4, 4 + size)
        val recordedCrc = ByteBuffer.wrap(blob, 4 + size, 4).order(ByteOrder.BIG_ENDIAN).getInt()
        val computedCrc = CRC32().apply { update(checked) }.value.toInt()
        when {
            magicBigEndian == MAGIC_BITMAP32 ->
                PuffinReader.readRoaring32(ByteBuffer.wrap(blob, 8, size - 4).slice().order(ByteOrder.LITTLE_ENDIAN), emit)
            // The whole range, size and CRC included, is exactly a Puffin `deletion-vector-v1` blob:
            // the same outer fields in the same places, and the 64-bit bitmap inside.
            magicLittleEndian == MAGIC_BITMAP64 -> PuffinReader.forEachPosition(blob.copyOfRange(8, 4 + size), emit)
            else -> throw PaimonIndexFormatException("0x${magicBigEndian.toUInt().toString(16)} is neither Paimon vector magic")
        }
        return recordedCrc == computedCrc
    }

    private fun SeekableByteChannel.readFully(count: Int): ByteArray {
        val buffer = ByteBuffer.allocate(count)
        while (buffer.hasRemaining()) {
            if (read(buffer) < 0) {
                throw PaimonIndexFormatException("the file ended ${buffer.remaining()} bytes before a $count-byte read finished")
            }
        }
        return buffer.array()
    }
}
