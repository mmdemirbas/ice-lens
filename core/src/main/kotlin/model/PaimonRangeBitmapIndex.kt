package model

import service.PuffinReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.BitSet

/**
 * A `range-bitmap` index as `RangeBitmapFileIndex` writes it at release-1.3.1 (the index arrived
 * in 1.3.0): a dictionary of the column's distinct non-null values in the type's order, each
 * given a code by its rank, and a bit-sliced index over the codes — so a comparison is an order
 * question put to the dictionary and answered per row by the slices, on any type the writer
 * takes. `KeyFactory` maps them: the integers as themselves, a decimal of precision 18 or less as
 * its unscaled value, a date as its epoch day and a time as its milliseconds of the day, a
 * timestamp of precision 6 or less as [paimonTimestampLong], a float, a double, a boolean, and a
 * string as its bytes in `BinaryString`'s unsigned order — where `bsi` refuses a string, a float
 * and a boolean, and `bitmap` answers no range at all.
 *
 * The layout is three records in a row, each led by a big-endian header length. `RangeBitmap`: a
 * version byte, the row count, the cardinality, the min and max keys (absent at cardinality 0)
 * and the dictionary's length. `ChunkedDictionary`: the chunk count, an offsets region and a
 * chunk-headers region; a chunk's header holds its first key, that key's code, its keys' offset
 * into the keys region that follows and how many more keys it holds — a fixed-width type's keys
 * back to back, a string's behind a run of offsets — so the dictionary is searched by first key
 * across the chunks and then within one, and a key with no entry comes back as the code it would
 * take, which a `>` or `>=` on a literal the column never held is answered from. Chunks are cut
 * at `chunk-size` (16 KB by default). `BitSliceIndexBitmap`: the slice count, the existence
 * bitmap and one Roaring bitmap per bit of the code, each in the library's portable layout.
 *
 * The operators are read off `RangeBitmap`: a comparison outside `[min, max]` is settled by the
 * two figures, one at a cardinality of one by the existence bitmap, and the rest by the slices;
 * `<>`, `<` and `<=` are the complement of `=`, `>=` and `>` over the non-null rows; `IS NULL` is
 * the non-null rows flipped over the row count. One thing the source does that a reading would
 * not predict: at cardinality 0 — every value null — `isNull` answers `bitmapOf(0, rid - 1)`, the
 * first and the last row rather than the range between them, so Paimon counts two of an all-null
 * file's three rows as null. This reads every row as null, which is what the file holds and what
 * the verdict comes to either way; the fixture test pins the difference.
 */
class PaimonRangeBitmapIndex private constructor(
    val rowCount: Int,
    /** Distinct non-null values — the dictionary's size, and the codes run `0 until cardinality`. */
    val cardinality: Int,
    val min: Any?,
    val max: Any?,
    private val dictionary: Dictionary,
    private val bsi: BitSlices,
    private val compare: Comparator<Any>,
) {
    /** How many chunks the dictionary is cut into. */
    val chunkCount: Int get() = dictionary.chunks.size

    /** How many rows hold a value. */
    val nonNullCount: Int get() = if (cardinality <= 0) 0 else bsi.ebm.cardinality()

    /**
     * The key's code, or `-(insertion code) - 1` for a key the column never held, where the
     * insertion code is that of the first key above it — `ChunkedDictionary.find`.
     */
    fun codeOf(key: Any): Int = dictionary.find(key, compare)

    /** The key at [code], for reading the dictionary back. */
    fun keyOf(code: Int): Any? = dictionary.keyOf(code)

    fun isNotNull(): BitSet = if (cardinality <= 0) BitSet() else bsi.ebm.clone() as BitSet

    fun isNull(): BitSet =
        if (cardinality <= 0) BitSet().also { it.set(0, rowCount) }
        else isNotNull().also { it.flip(0, rowCount) }

    fun eq(key: Any): BitSet {
        if (cardinality <= 0) return BitSet()
        val belowMin = compare.compare(key, min!!)
        val aboveMax = compare.compare(key, max!!)
        if (belowMin == 0 && aboveMax == 0) return isNotNull()
        if (belowMin < 0 || aboveMax > 0) return BitSet()
        val code = codeOf(key)
        return if (code < 0) BitSet() else bsi.eq(code)
    }

    fun notEq(key: Any): BitSet = if (cardinality <= 0) BitSet() else not(eq(key))

    fun lte(key: Any): BitSet {
        if (cardinality <= 0) return BitSet()
        if (compare.compare(key, max!!) >= 0) return isNotNull()
        if (compare.compare(key, min!!) < 0) return BitSet()
        return not(gt(key))
    }

    fun lt(key: Any): BitSet {
        if (cardinality <= 0) return BitSet()
        if (compare.compare(key, max!!) > 0) return isNotNull()
        if (compare.compare(key, min!!) <= 0) return BitSet()
        return not(gte(key))
    }

    fun gte(key: Any): BitSet {
        if (cardinality <= 0) return BitSet()
        if (compare.compare(key, min!!) <= 0) return isNotNull()
        if (compare.compare(key, max!!) > 0) return BitSet()
        val code = codeOf(key)
        return if (code < 0) bsi.gte(-code - 1) else bsi.gte(code)
    }

    fun gt(key: Any): BitSet {
        if (cardinality <= 0) return BitSet()
        if (compare.compare(key, min!!) < 0) return isNotNull()
        if (compare.compare(key, max!!) >= 0) return BitSet()
        val code = codeOf(key)
        return if (code < 0) bsi.gte(-code - 1) else bsi.gt(code)
    }

    /** The rows [op] keeps for [key], or null for an operator the index does not answer. */
    fun rowsMatching(op: PredicateOp, key: Any?): BitSet? = when (op) {
        PredicateOp.IS_NULL -> isNull()
        PredicateOp.IS_NOT_NULL -> isNotNull()
        PredicateOp.EQ -> key?.let(::eq)
        PredicateOp.NOT_EQ -> key?.let(::notEq)
        PredicateOp.LT -> key?.let(::lt)
        PredicateOp.LTE -> key?.let(::lte)
        PredicateOp.GT -> key?.let(::gt)
        PredicateOp.GTE -> key?.let(::gte)
        PredicateOp.LIKE, PredicateOp.NOT_LIKE -> null
    }

    /** `RangeBitmap.not`: the complement over the rows, less the nulls. */
    private fun not(rows: BitSet): BitSet = rows.also { it.flip(0, rowCount); it.and(isNotNull()) }

    /**
     * `ChunkedDictionary`: the chunks in key order, each holding its first key and its code with
     * the keys after it decoded on first use — a search touches a few chunks of a dictionary that
     * may hold every distinct value of a large file.
     */
    private class Dictionary(val chunks: List<Chunk>) {
        fun find(key: Any, compare: Comparator<Any>): Int {
            var low = 0
            var high = chunks.size - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                val result = compare.compare(chunks[mid].firstKey, key)
                when {
                    result > 0 -> high = mid - 1
                    result < 0 -> low = mid + 1
                    else -> return chunks[mid].code
                }
            }
            if (low == 0) return -(low + 1)
            return chunks[low - 1].find(key, compare)
        }

        fun keyOf(code: Int): Any? {
            if (code < 0) return null
            val chunk = chunks.lastOrNull { it.code <= code } ?: return null
            if (chunk.code == code) return chunk.firstKey
            return chunk.keys.value.getOrNull(code - chunk.code - 1)
        }
    }

    /** A chunk: its first key in the header at [code], and [keys] after it at `code + 1 + i` (`AbstractChunk`). */
    private class Chunk(val firstKey: Any, val code: Int, val keys: Lazy<List<Any>>) {
        fun find(key: Any, compare: Comparator<Any>): Int {
            if (compare.compare(firstKey, key) == 0) return code
            val more = keys.value
            var low = 0
            var high = more.size - 1
            val base = code + 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                val result = compare.compare(more[mid], key)
                when {
                    result > 0 -> high = mid - 1
                    result < 0 -> low = mid + 1
                    else -> return base + mid
                }
            }
            return -(base + low + 1)
        }
    }

    /**
     * `BitSliceIndexBitmap` over the codes: the rows holding a value ([ebm]) and one bitmap per
     * bit of the code, least significant first. `eq` narrows the existing rows bit by bit; `gt`
     * walks up from the code's lowest clear bit — a row exceeds the code once it has that bit,
     * or once a higher bit decides it — and `gte(code)` is `gt(code - 1)`.
     */
    private class BitSlices(val ebm: BitSet, val slices: List<BitSet>) {
        fun eq(code: Int): BitSet {
            val state = ebm.clone() as BitSet
            if (state.isEmpty) return state
            for (i in slices.indices) {
                if ((code shr i) and 1 == 1) state.and(slices[i]) else state.andNot(slices[i])
            }
            return state
        }

        fun gt(code: Int): BitSet {
            if (code < 0) return ebm.clone() as BitSet
            val found = ebm.clone() as BitSet
            if (found.isEmpty) return found
            var state: BitSet? = null
            val start = Integer.numberOfTrailingZeros(code.inv())
            for (i in start until slices.size) {
                val s = state
                if (s == null) {
                    state = slices[i].clone() as BitSet
                    continue
                }
                if ((code shr i) and 1 == 1) s.and(slices[i]) else s.or(slices[i])
            }
            return state?.also { it.and(found) } ?: BitSet()
        }

        fun gte(code: Int): BitSet = gt(code - 1)
    }

    companion object {
        const val VERSION_1 = 1

        /**
         * The index over a column of [paimonType], or null when the type is one the writer refuses
         * or the bytes are not a version-1 range bitmap.
         */
        fun decode(bytes: ByteArray, paimonType: String): PaimonRangeBitmapIndex? {
            val keyType = PaimonBitmapKeyType.ofRangeBitmap(paimonType) ?: return null
            if (bytes.size < 4 + 1 + 4 + 4 + 4) return null
            val header = record(bytes, 0)
            if (header.get().toInt() != VERSION_1) return null
            val rowCount = header.getInt()
            val cardinality = header.getInt()
            val min = if (cardinality > 0) keyType.read(header) else null
            val max = if (cardinality > 0) keyType.read(header) else null
            val dictionaryLength = header.getInt()
            val dictionaryOffset = header.limit()
            val dictionary = decodeDictionary(bytes, dictionaryOffset, keyType)
            val bsi = decodeSlices(bytes, dictionaryOffset + dictionaryLength)
            return PaimonRangeBitmapIndex(rowCount, cardinality, min, max, dictionary, bsi, keyType.comparator())
        }

        /** The record at [offset]: its header behind a 4-byte length, as a buffer whose limit is where the body starts. */
        private fun record(bytes: ByteArray, offset: Int): ByteBuffer {
            val length = ByteBuffer.wrap(bytes, offset, 4).getInt()
            return ByteBuffer.wrap(bytes, offset + 4, length)
        }

        private fun decodeDictionary(bytes: ByteArray, offset: Int, keyType: PaimonBitmapKeyType): Dictionary {
            val header = record(bytes, offset)
            val version = header.get().toInt()
            if (version != VERSION_1) throw IllegalArgumentException("a range-bitmap dictionary of version $version is not one this reads")
            val size = header.getInt()
            val offsetsLength = header.getInt()
            val chunksLength = header.getInt()
            val offsetsStart = header.limit()
            val chunksStart = offsetsStart + offsetsLength
            val keysBase = chunksStart + chunksLength
            val chunks = List(size) { i ->
                val chunkOffset = ByteBuffer.wrap(bytes, offsetsStart + 4 * i, 4).getInt()
                val chunk = ByteBuffer.wrap(bytes, chunksStart + chunkOffset, keysBase - chunksStart - chunkOffset)
                val chunkVersion = chunk.get().toInt()
                if (chunkVersion != VERSION_1) throw IllegalArgumentException("a range-bitmap chunk of version $chunkVersion is not one this reads")
                val firstKey = keyType.read(chunk)
                val code = chunk.getInt()
                val keysOffset = chunk.getInt()
                val more = chunk.getInt()
                val keys: Lazy<List<Any>> = if (keyType == PaimonBitmapKeyType.STRING) {
                    val chunkOffsetsLength = chunk.getInt()
                    val keysLength = chunk.getInt()
                    lazy {
                        val offsets = ByteBuffer.wrap(bytes, keysBase + keysOffset, chunkOffsetsLength)
                        val keysStart = keysBase + keysOffset + chunkOffsetsLength
                        List(more) { val at = offsets.getInt(); keyType.read(ByteBuffer.wrap(bytes, keysStart + at, keysLength - at)) }
                    }
                } else {
                    val keysLength = chunk.getInt()
                    chunk.getInt() // the fixed length, which the type already knows
                    lazy {
                        val keys = ByteBuffer.wrap(bytes, keysBase + keysOffset, keysLength)
                        List(more) { keyType.read(keys) }
                    }
                }
                Chunk(firstKey, code, keys)
            }
            return Dictionary(chunks)
        }

        private fun decodeSlices(bytes: ByteArray, offset: Int): BitSlices {
            val header = record(bytes, offset)
            val version = header.get().toInt()
            if (version != VERSION_1) throw IllegalArgumentException("a range-bitmap slice index of version $version is not one this reads")
            val sliceCount = header.get().toInt() and 0xFF
            val ebmLength = header.getInt()
            header.getInt() // the (offset, length) table's length: 8 bytes a slice
            val table = List(sliceCount) { header.getInt() to header.getInt() }
            val bodyOffset = header.limit()
            val ebm = roaring(bytes, bodyOffset, ebmLength)
            val slices = table.map { (at, length) -> roaring(bytes, bodyOffset + ebmLength + at, length) }
            return BitSlices(ebm, slices)
        }

        /** One portable 32-bit Roaring bitmap, little-endian, at [offset]. */
        private fun roaring(bytes: ByteArray, offset: Int, length: Int): BitSet {
            val buffer = ByteBuffer.wrap(bytes, offset, length).slice().order(ByteOrder.LITTLE_ENDIAN)
            val set = BitSet()
            PuffinReader.readRoaring32(buffer) { set.set(it.toInt()) }
            return set
        }
    }
}

/**
 * A literal as the range bitmap keys it — `KeyFactory.createConverter` over the value
 * [parseLiteral] produced for the column's Iceberg reading of [paimonType]: [paimonBitmapKey]'s
 * encoding, plus a decimal as its unscaled value at the column's scale ([paimonBsiValue]). Null
 * for a type the index does not cover — a decimal past precision 18, a timestamp past precision
 * 6, a nested type — or a value of the wrong kind.
 */
fun paimonRangeBitmapKey(paimonType: String, value: Any): Any? {
    if (PaimonBitmapKeyType.ofRangeBitmap(paimonType) == null) return null
    val head = Regex("""^([A-Z_]+)""").find(paimonType.trim().uppercase()) ?: return null
    return if (head.groupValues[1] == "DECIMAL") paimonBsiValue(paimonType, value) else paimonBitmapKey(paimonType, value)
}
