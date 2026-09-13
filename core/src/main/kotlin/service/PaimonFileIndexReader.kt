package service

import model.PaimonColumnIndex
import model.PaimonFileIndex
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads a Paimon file index container — the `.index` file beside a data file, or the same bytes
 * carried in the manifest entry as `_EMBEDDED_FILE_INDEX` — the way `FileIndexFormat.Reader`
 * reads it at release-1.3.1.
 *
 * The layout is one head and one body. Head: an 8-byte magic, a 4-byte version (1), the head's
 * own length, then per indexed column its name (`writeUTF`: a 2-byte length and modified UTF-8),
 * the number of indexes over it, and per index its type name, the body offset of its bytes
 * **measured from the start of the container** (the writer adds the head length in), and their
 * length; a start of -1 is an index the writer left empty. The body is the indexes' bytes back to
 * back. Nothing in the head says what an index's bytes mean — that is the type name's job, and
 * only `bloom-filter` is decoded here.
 */
object PaimonFileIndexReader {
    private const val MAGIC = 1493475289347502L
    private const val VERSION = 1
    private const val EMPTY_INDEX = -1

    class PaimonFileIndexFormatException(message: String) : Exception(message)

    /** The whole file, decoded. A file index is a few hundred bytes to a few hundred kilobytes, so it is read at once. */
    fun read(indexFile: Path): PaimonFileIndex = decode(Files.readAllBytes(indexFile))

    fun decode(bytes: ByteArray): PaimonFileIndex {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val magic = input.readLong()
        if (magic != MAGIC) throw PaimonFileIndexFormatException("not a Paimon file index: magic $magic, expected $MAGIC")
        val version = input.readInt()
        if (version != VERSION) throw PaimonFileIndexFormatException("file index version $version; only $VERSION is read")
        val headLength = input.readInt()
        val head = ByteArray(headLength - 8 - 4 - 4).also(input::readFully)
        val columns = linkedMapOf<String, List<PaimonColumnIndex>>()
        DataInputStream(ByteArrayInputStream(head)).use { h ->
            repeat(h.readInt()) {
                val column = h.readUTF()
                val indexes = List(h.readInt()) {
                    val type = h.readUTF()
                    val start = h.readInt()
                    val length = h.readInt()
                    if (start == EMPTY_INDEX) PaimonColumnIndex(type, bytes = null)
                    else {
                        if (start < headLength || start + length > bytes.size) {
                            throw PaimonFileIndexFormatException("index '$type' on '$column' at $start+$length lies outside the ${bytes.size}-byte container")
                        }
                        PaimonColumnIndex(type, bytes.copyOfRange(start, start + length))
                    }
                }
                columns[column] = indexes
            }
        }
        return PaimonFileIndex(columns, containerBytes = bytes.size)
    }
}
