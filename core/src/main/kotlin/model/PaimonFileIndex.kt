package model

import java.time.LocalDate
import java.time.ZoneOffset
import java.time.LocalTime
import java.time.LocalDateTime
import java.time.Instant

/**
 * A Paimon file index, decoded from its container — see [service.PaimonFileIndexReader].
 *
 * One entry per indexed column, in the order the head lists them; a column may carry several
 * index types. What each index's bytes mean is decided by [PaimonColumnIndex.type]: a
 * `bloom-filter` is [PaimonBloomFilter], a `bitmap` is [PaimonBitmapIndex], and the other types
 * (`bsi`, `dynamic-bitmap`) are named and left undecoded, which the pruning reports rather than
 * guesses at.
 */
data class PaimonFileIndex(
    val columns: Map<String, List<PaimonColumnIndex>>,
    /** The container's size — the `.index` file's, or the embedded bytes'. */
    val containerBytes: Int,
) {
    /** `bloom-filter on k, v` — what the panel prints beside where the index lives. */
    fun describe(): String = columns.entries
        .flatMap { (column, indexes) -> indexes.map { it.type to column } }
        .groupBy({ it.first }, { it.second })
        .entries.joinToString("; ") { (type, cols) -> "$type on ${cols.joinToString(", ")}" }

    /** The bloom filter over [column], decoded, or null when the column has none or its bytes are empty. */
    fun bloomFilter(column: String): PaimonBloomFilter? = columns.entries
        .firstOrNull { it.key.equals(column.trim(), ignoreCase = true) }?.value
        ?.firstOrNull { it.type == BLOOM_FILTER }?.bytes
        ?.let { PaimonBloomFilter.decode(it) }

    /** The bitmap index over [column], decoded against its [paimonType], or null when the column has none or its bytes are empty. */
    fun bitmapIndex(column: String, paimonType: String): PaimonBitmapIndex? = columns.entries
        .firstOrNull { it.key.equals(column.trim(), ignoreCase = true) }?.value
        ?.firstOrNull { it.type == BITMAP }?.bytes
        ?.let { PaimonBitmapIndex.decode(it, paimonType) }

    companion object {
        const val BLOOM_FILTER = "bloom-filter"
        const val BITMAP = "bitmap"
    }
}

/** One index over one column: its type name and its bytes — null where the writer recorded an empty index. */
class PaimonColumnIndex(val type: String, val bytes: ByteArray?) {
    override fun equals(other: Any?): Boolean =
        other is PaimonColumnIndex && type == other.type && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = 31 * type.hashCode() + bytes.contentHashCode()
    override fun toString(): String = "PaimonColumnIndex($type, ${bytes?.size ?: "no"} bytes)"
}

/**
 * A `bloom-filter` index as `BloomFilterFileIndex` writes it at release-1.3.1: a 4-byte
 * big-endian hash-function count, then `BloomFilter64`'s bit set — bit `i` is bit `i and 7` of
 * byte `i shr 3`, least significant first.
 *
 * A probe is Kirsch–Mitzenmacher over one 64-bit hash: the low and high halves are `h1` and
 * `h2`, and position `i` is `h1 + i * h2` for `i` in `1..k`, in `Int` arithmetic, bit-flipped
 * when negative, modulo the bit count. [mightContain] answers the way a bloom filter does — a
 * `false` is a proof the value was never added, a `true` is not a proof it was.
 */
class PaimonBloomFilter(val numHashFunctions: Int, private val bits: ByteArray) {
    val numBits: Int get() = bits.size * 8

    fun mightContain(hash64: Long): Boolean {
        val hash1 = hash64.toInt()
        val hash2 = (hash64 ushr 32).toInt()
        for (i in 1..numHashFunctions) {
            var combined = hash1 + i * hash2
            if (combined < 0) combined = combined.inv()
            val pos = combined % numBits
            if (bits[pos ushr 3].toInt() and (1 shl (pos and 7)) == 0) return false
        }
        return true
    }

    companion object {
        fun decode(bytes: ByteArray): PaimonBloomFilter? {
            if (bytes.size < 5) return null
            val k = ((bytes[0].toInt() and 0xFF) shl 24) or ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
            if (k <= 0) return null
            return PaimonBloomFilter(k, bytes.copyOfRange(4, bytes.size))
        }
    }
}

/**
 * The hash `FastHash` computes for a value of a Paimon type, or null for a type it does not
 * hash — `BOOLEAN`, `DECIMAL` and the nested types, which the writer refuses to index.
 *
 * Two functions, chosen by type: a string or binary is xxHash64 (seed 0) over its bytes, and
 * everything numeric is Thomas Wang's 64-bit integer hash over the value widened to a `long` —
 * a `FLOAT` over its `floatToIntBits`, a `DOUBLE` over its `doubleToLongBits`, a `DATE` over its
 * epoch day, a `TIME` over its milliseconds of the day, and a `TIMESTAMP(p)` of either kind over
 * its milliseconds since the epoch when `p <= 3` and its microseconds otherwise — the precision
 * defaulting to 6, which is what the type prints without one. The value is what [parseLiteral]
 * produced for the column's Iceberg reading of the type; a zone-less literal against a
 * `WITH LOCAL TIME ZONE` column is read at UTC, the convention [compareValues] already applies
 * to the bounds, and the `ft` fixture is written at UTC so the two agree on it.
 */
fun paimonFastHash(paimonType: String, value: Any): Long? {
    val head = Regex("""^([A-Z_]+)(?:\((\d+)(?:,\s*(\d+))?\))?""").find(paimonType.trim().uppercase()) ?: return null
    return when (head.groupValues[1]) {
        "CHAR", "VARCHAR", "STRING" -> xxHash64(value.toString().toByteArray(Charsets.UTF_8))
        "BINARY", "VARBINARY" -> (value as? ByteArray)?.let { xxHash64(it) }
        "TINYINT", "SMALLINT", "INT", "BIGINT" -> (value as? Number)?.let { wangHash(it.toLong()) }
        "FLOAT" -> (value as? Number)?.let { wangHash(java.lang.Float.floatToIntBits(it.toFloat()).toLong()) }
        "DOUBLE" -> (value as? Number)?.let { wangHash(java.lang.Double.doubleToLongBits(it.toDouble())) }
        "DATE" -> (value as? LocalDate)?.let { wangHash(it.toEpochDay()) }
        "TIME" -> (value as? LocalTime)?.let { wangHash(it.toNanoOfDay() / 1_000_000) }
        "TIMESTAMP", "TIMESTAMP_LTZ" -> {
            val precision = head.groupValues[2].toIntOrNull() ?: 6
            val utc = when (value) {
                is LocalDateTime -> value
                is Instant -> LocalDateTime.ofInstant(value, ZoneOffset.UTC)
                else -> return null
            }
            // Paimon's Timestamp keeps milliseconds and the nanoseconds within the millisecond,
            // and `toMicros` floors the latter to microseconds.
            val millis = utc.toLocalDate().toEpochDay() * 86_400_000L + utc.toLocalTime().toNanoOfDay() / 1_000_000
            val nanoOfMilli = utc.toLocalTime().toNanoOfDay() % 1_000_000
            wangHash(if (precision <= 3) millis else millis * 1_000 + nanoOfMilli / 1_000)
        }
        else -> null
    }
}

/** Thomas Wang's 64-bit integer hash, as `FastHash.getLongHash` computes it. */
internal fun wangHash(key0: Long): Long {
    var key = key0
    key = key.inv() + (key shl 21)
    key = key xor (key shr 24)
    key = (key + (key shl 3)) + (key shl 8)
    key = key xor (key shr 14)
    key = (key + (key shl 2)) + (key shl 4)
    key = key xor (key shr 28)
    key = key + (key shl 31)
    return key
}

private const val XX_P1 = -7046029288634856825L
private const val XX_P2 = -4417276706812531889L
private const val XX_P3 = 1609587929392839161L
private const val XX_P4 = -8796714831421723037L
private const val XX_P5 = 2870177450012600261L

/**
 * XXH64 over [data] with seed 0 — what `LongHashFunction.xx().hashBytes` computes, and the hash
 * `FastHash` gives a string. Written from the published algorithm: four accumulators over
 * 32-byte stripes, then the remaining 8-, 4- and 1-byte pieces, then the avalanche.
 */
fun xxHash64(data: ByteArray, seed: Long = 0L): Long {
    fun rotl(v: Long, n: Int) = (v shl n) or (v ushr (64 - n))
    fun round(acc: Long, input: Long) = rotl(acc + input * XX_P2, 31) * XX_P1
    fun mergeRound(acc: Long, v: Long) = (acc xor round(0L, v)) * XX_P1 + XX_P4
    fun readLong(i: Int): Long {
        var v = 0L
        for (b in 7 downTo 0) v = (v shl 8) or (data[i + b].toLong() and 0xFF)
        return v
    }
    fun readInt(i: Int): Long {
        var v = 0L
        for (b in 3 downTo 0) v = (v shl 8) or (data[i + b].toLong() and 0xFF)
        return v
    }

    val len = data.size
    var i = 0
    var h: Long
    if (len >= 32) {
        var v1 = seed + XX_P1 + XX_P2
        var v2 = seed + XX_P2
        var v3 = seed
        var v4 = seed - XX_P1
        while (i <= len - 32) {
            v1 = round(v1, readLong(i))
            v2 = round(v2, readLong(i + 8))
            v3 = round(v3, readLong(i + 16))
            v4 = round(v4, readLong(i + 24))
            i += 32
        }
        h = rotl(v1, 1) + rotl(v2, 7) + rotl(v3, 12) + rotl(v4, 18)
        h = mergeRound(h, v1)
        h = mergeRound(h, v2)
        h = mergeRound(h, v3)
        h = mergeRound(h, v4)
    } else {
        h = seed + XX_P5
    }
    h += len.toLong()
    while (i + 8 <= len) {
        h = h xor round(0L, readLong(i))
        h = rotl(h, 27) * XX_P1 + XX_P4
        i += 8
    }
    if (i + 4 <= len) {
        h = h xor (readInt(i) * XX_P1)
        h = rotl(h, 23) * XX_P2 + XX_P3
        i += 4
    }
    while (i < len) {
        h = h xor ((data[i].toLong() and 0xFF) * XX_P5)
        h = rotl(h, 11) * XX_P1
        i++
    }
    h = h xor (h ushr 33)
    h *= XX_P2
    h = h xor (h ushr 29)
    h *= XX_P3
    h = h xor (h ushr 32)
    return h
}
