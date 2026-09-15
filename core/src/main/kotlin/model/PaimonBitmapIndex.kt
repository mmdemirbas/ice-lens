package model

import service.PuffinReader
import java.io.DataInputStream
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import java.time.LocalTime
import java.util.BitSet

/**
 * A `bitmap` file index as `BitmapFileIndex` writes it at release-1.3.1: a dictionary of the
 * column's distinct values, each naming one 32-bit Roaring bitmap of the rows that hold it, plus
 * one for null. Where a bloom filter answers `=` with a possibility, this answers it exactly —
 * a value not in the dictionary is in no row — and it answers `<>`, `IS NULL` and `IS NOT NULL`
 * too, since a `<>` is the value's bitmap flipped over the row count and is empty only when every
 * row holds the value.
 *
 * The layout, one version byte first. Both versions open with the row count, the number of
 * non-null values, whether any row is null, and — only then — the null bitmap's offset (its
 * length as well in v2). **An offset below zero is a bitmap of one row**, `-1 - row`, written in
 * place of a bitmap that would be longer than the number. v1 lists every value with its offset
 * right there; v2 (the default) writes a directory of blocks — each block's first key and where
 * the block starts — and the blocks after it, each a count of `(key, offset, length)` entries
 * sorted by the type's comparator, so a reader seeks to one block rather than reading every
 * key. Offsets are from the body, which begins after the last block. A key is written by type:
 * a string as a 4-byte length and its UTF-8 bytes, a numeric as its Java width, a date and a
 * time as `int`, a timestamp of either kind as the `long` `FastHash` also hashes (milliseconds
 * at precision 3 and below, microseconds above); `BOOLEAN` as a byte. `BINARY`, `DECIMAL` and
 * the nested types are refused by the writer.
 */
class PaimonBitmapIndex private constructor(
    val version: Int,
    val rowCount: Int,
    /** How many distinct non-null values the column has in this file. */
    val distinctValues: Int,
    private val nullEntry: Entry?,
    private val blocks: List<Block>,
    private val bodyStart: Int,
    private val bytes: ByteArray,
    private val compare: Comparator<Any>,
) {
    /** A value's bitmap: where it starts in the body and how long it is, or a single row when [offset] is negative. */
    class Entry(val key: Any?, val offset: Int, val length: Int) {
        val singleRow: Int? get() = if (offset < 0) -1 - offset else null
    }

    /** One block of sorted entries: v2 reads a block on first use, v1 is one block read with the meta. */
    private class Block(val firstKey: Any, entries: Lazy<List<Entry>>) {
        val entries: List<Entry> by entries
    }

    val hasNull: Boolean get() = nullEntry != null

    /** Whether any row holds [key] — exactly, since the dictionary lists every value the file has. */
    fun contains(key: Any): Boolean = find(key) != null

    /** The entry for [key], or null when no row holds it. */
    fun find(key: Any): Entry? {
        val block = blocks.lastOrNull { compare.compare(it.firstKey, key) <= 0 } ?: return null
        return block.entries.firstOrNull { compare.compare(it.key!!, key) == 0 }
    }

    /** How many rows hold [key] — the bitmap's cardinality, read for the answer to `<>`. */
    fun cardinalityOf(key: Any): Int = rowsOf(key).cardinality()

    /** How many rows are null. */
    val nullCount: Int get() = nullRows().cardinality()

    /** The rows holding [key], decoded from its bitmap — empty when no row does. */
    fun rowsOf(key: Any): BitSet = find(key)?.let(::rows) ?: BitSet()

    /** The rows that are null. */
    fun nullRows(): BitSet = nullEntry?.let(::rows) ?: BitSet()

    /**
     * The rows [op] keeps for [key], as `BitmapFileIndex.Reader` answers it: `=` the value's
     * rows, `<>` those flipped over the row count — nulls among them — `IS NULL` the null
     * bitmap, `IS NOT NULL` that flipped. Null for an operator the dictionary does not answer.
     */
    fun rowsMatching(op: PredicateOp, key: Any?): BitSet? = when (op) {
        PredicateOp.EQ -> key?.let(::rowsOf)
        PredicateOp.NOT_EQ -> key?.let(::rowsOf)?.also { it.flip(0, rowCount) }
        PredicateOp.IS_NULL -> nullRows()
        PredicateOp.IS_NOT_NULL -> nullRows().also { it.flip(0, rowCount) }
        else -> null
    }

    private fun rows(entry: Entry): BitSet {
        val set = BitSet()
        entry.singleRow?.let { set.set(it); return set }
        val start = bodyStart + entry.offset
        val end = if (entry.length >= 0) start + entry.length else bytes.size
        PuffinReader.readRoaring32(ByteBuffer.wrap(bytes, start, end - start).slice().order(ByteOrder.LITTLE_ENDIAN)) { set.set(it.toInt()) }
        return set
    }

    companion object {
        const val VERSION_1 = 1
        const val VERSION_2 = 2

        /** The index over a column of [paimonType], or null when the type is one the writer refuses or the bytes are not a bitmap index. */
        fun decode(bytes: ByteArray, paimonType: String): PaimonBitmapIndex? {
            val keyType = PaimonBitmapKeyType.of(paimonType) ?: return null
            if (bytes.isEmpty()) return null
            val version = bytes[0].toInt()
            if (version != VERSION_1 && version != VERSION_2) return null
            val input = DataInputStream(ByteArrayInputStream(bytes, 1, bytes.size - 1))
            var pos = 1
            fun int(): Int { pos += 4; return input.readInt() }
            fun bool(): Boolean { pos += 1; return input.readBoolean() }
            fun key(): Any = keyType.read(input).also { pos += keyType.size(it) }
            val rowCount = int()
            val distinct = int()
            val hasNull = bool()
            val nullEntry = if (hasNull) Entry(null, int(), if (version == VERSION_2) int() else -1) else null
            val compare = keyType.comparator()
            if (version == VERSION_1) {
                // Every value here, offsets from the end of the meta; a length is the gap to the next offset.
                val listed = List(distinct) { Entry(key(), int(), -1) }
                val entries = listed.mapIndexed { i, e ->
                    if (e.offset < 0) e
                    else Entry(e.key, e.offset, listed.drop(i + 1).firstOrNull { it.offset >= 0 }?.let { it.offset - e.offset } ?: -1)
                }.sortedWith { a, b -> compare.compare(a.key!!, b.key!!) }
                val blocks = listOfNotNull(entries.firstOrNull()?.let { Block(it.key!!, lazyOf(entries)) })
                return PaimonBitmapIndex(version, rowCount, distinct, nullEntry, blocks, pos, bytes, compare)
            }
            val directory = List(int()) { key() to int() }
            val bitmapBodyOffset = int()
            val indexBlockStart = pos
            val blocks = directory.map { (first, start) ->
                Block(first, lazy {
                    val block = DataInputStream(ByteArrayInputStream(bytes, indexBlockStart + start, bytes.size - indexBlockStart - start))
                    List(block.readInt()) { Entry(keyType.read(block), block.readInt(), block.readInt()) }
                })
            }
            return PaimonBitmapIndex(version, rowCount, distinct, nullEntry, blocks, indexBlockStart + bitmapBodyOffset, bytes, compare)
        }
    }
}

/**
 * The eight key encodings `BitmapTypeVisitor` maps every indexable Paimon type onto — and the
 * range bitmap's `KeyFactory` too, whose serializers write the same bytes and whose one addition
 * is a decimal as a long ([ofRangeBitmap]).
 */
internal enum class PaimonBitmapKeyType {
    STRING, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BOOLEAN;

    fun read(input: DataInputStream): Any = when (this) {
        STRING -> ByteArray(input.readInt()).also(input::readFully)
        BYTE -> input.readByte()
        SHORT -> input.readShort()
        INT -> input.readInt()
        LONG -> input.readLong()
        FLOAT -> input.readFloat()
        DOUBLE -> input.readDouble()
        BOOLEAN -> input.readBoolean()
    }

    /** The same off a big-endian buffer, from its position. */
    fun read(buffer: java.nio.ByteBuffer): Any = when (this) {
        STRING -> ByteArray(buffer.getInt()).also(buffer::get)
        BYTE -> buffer.get()
        SHORT -> buffer.getShort()
        INT -> buffer.getInt()
        LONG -> buffer.getLong()
        FLOAT -> buffer.getFloat()
        DOUBLE -> buffer.getDouble()
        BOOLEAN -> buffer.get() == 1.toByte()
    }

    /** The serialised size of a key already read, for keeping the position. */
    fun size(key: Any): Int = when (this) {
        STRING -> 4 + (key as ByteArray).size
        BYTE, BOOLEAN -> 1
        SHORT -> 2
        INT, FLOAT -> 4
        LONG, DOUBLE -> 8
    }

    /** The order the v2 blocks are sorted in — `BinaryString.compareTo` is unsigned bytes then length. */
    fun comparator(): Comparator<Any> = when (this) {
        STRING -> Comparator { a, b ->
            val x = a as ByteArray; val y = b as ByteArray
            for (i in 0 until minOf(x.size, y.size)) {
                val d = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
                if (d != 0) return@Comparator d
            }
            x.size - y.size
        }
        BYTE -> Comparator { a, b -> (a as Byte).compareTo(b as Byte) }
        SHORT -> Comparator { a, b -> (a as Short).compareTo(b as Short) }
        INT -> Comparator { a, b -> (a as Int).compareTo(b as Int) }
        LONG -> Comparator { a, b -> (a as Long).compareTo(b as Long) }
        FLOAT -> Comparator { a, b -> (a as Float).compareTo(b as Float) }
        DOUBLE -> Comparator { a, b -> (a as Double).compareTo(b as Double) }
        BOOLEAN -> Comparator { a, b -> (a as Boolean).compareTo(b as Boolean) }
    }

    companion object {
        fun of(paimonType: String): PaimonBitmapKeyType? {
            val head = Regex("""^([A-Z_]+)""").find(paimonType.trim().uppercase()) ?: return null
            return when (head.groupValues[1]) {
                "CHAR", "VARCHAR", "STRING" -> STRING
                "TINYINT" -> BYTE
                "SMALLINT" -> SHORT
                "INT", "DATE", "TIME" -> INT
                "BIGINT", "TIMESTAMP", "TIMESTAMP_LTZ" -> LONG
                "FLOAT" -> FLOAT
                "DOUBLE" -> DOUBLE
                "BOOLEAN" -> BOOLEAN
                else -> null
            }
        }

        /**
         * `KeyFactory.create`: the bitmap's encodings plus a decimal as its unscaled long, and
         * null for what the factory refuses — a decimal past precision 18, a timestamp past
         * precision 6, or a type it has no case for.
         */
        fun ofRangeBitmap(paimonType: String): PaimonBitmapKeyType? {
            val head = Regex("""^([A-Z_]+)(?:\((\d+)(?:,\s*(\d+))?\))?""").find(paimonType.trim().uppercase()) ?: return null
            return when (head.groupValues[1]) {
                "DECIMAL" -> LONG.takeIf { (head.groupValues[2].toIntOrNull() ?: 10) <= 18 }
                "TIMESTAMP", "TIMESTAMP_LTZ" -> LONG.takeIf { (head.groupValues[2].toIntOrNull() ?: 6) <= 6 }
                else -> of(paimonType)
            }
        }
    }
}

/**
 * A literal as the bitmap index keys it — the value [parseLiteral] produced for the column's
 * Iceberg reading of [paimonType], turned into what `BitmapFileIndex.getValueMapper` and the
 * meta's value writer put in the dictionary. Null for a type the index does not cover, or a
 * value of the wrong kind.
 */
fun paimonBitmapKey(paimonType: String, value: Any): Any? {
    val head = Regex("""^([A-Z_]+)(?:\((\d+)(?:,\s*(\d+))?\))?""").find(paimonType.trim().uppercase()) ?: return null
    return when (head.groupValues[1]) {
        "CHAR", "VARCHAR", "STRING" -> value.toString().toByteArray(Charsets.UTF_8)
        "TINYINT" -> (value as? Number)?.toByte()
        "SMALLINT" -> (value as? Number)?.toShort()
        "INT" -> (value as? Number)?.toInt()
        "BIGINT" -> (value as? Number)?.toLong()
        "FLOAT" -> (value as? Number)?.toFloat()
        "DOUBLE" -> (value as? Number)?.toDouble()
        "BOOLEAN" -> value as? Boolean
        "DATE" -> (value as? LocalDate)?.toEpochDay()?.toInt()
        "TIME" -> (value as? LocalTime)?.let { (it.toNanoOfDay() / 1_000_000).toInt() }
        "TIMESTAMP", "TIMESTAMP_LTZ" -> paimonTimestampLong(head.groupValues[2].toIntOrNull() ?: 6, value)
        else -> null
    }
}
