package model

import service.PuffinReader
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import java.time.LocalTime
import java.util.BitSet

/**
 * A `bsi` index as `BitSliceIndexBitmapFileIndex` writes it at release-1.3.1: a version byte,
 * the row count, and two [Slices] behind a presence flag each — one over the non-negative
 * values and one over the magnitudes of the negative ones. Every value is a `long` first
 * (`getValueMapper`: an integer as itself, a decimal as its unscaled value, a date as its epoch
 * day, a time as its milliseconds of the day, a timestamp as [paimonTimestampLong]), which is
 * why the writer refuses a string, a float or a boolean and this decodes no such column.
 *
 * A bit-sliced index answers every comparison exactly, per row, where a bloom filter answers
 * `=` as a maybe and a bitmap dictionary answers `=` and `<>` alone — so it is the one index
 * that can rule a file out of `n BETWEEN 4 AND 6` when the file's bounds are -5..10. The
 * operators are read off `BitSliceIndexBitmapFileIndex.Reader`: a comparison against a
 * negative literal is asked of the negative slices with the sign turned round, and a `<`
 * against a non-negative literal takes every negative row with it; `IS NULL` is the rows in
 * neither existence bitmap; `<>` is the non-null rows less the value's — nulls are not among
 * them, where the bitmap index's `<>` counts them.
 */
class PaimonBsiIndex private constructor(
    val rowCount: Int,
    private val positive: Slices,
    private val negative: Slices,
) {
    /**
     * O'Neil's bit-sliced index over one sign (`BitSliceIndexRoaringBitmap`): the rows holding a
     * value, and one bitmap per bit of `value - min`, least significant first. [min] and [max]
     * settle a comparison outside the range before any slice is read; inside it the compare
     * walks the slices from the top bit down, splitting the candidate rows into greater, equal
     * and less at each bit.
     */
    class Slices(val min: Long, val max: Long, val rows: BitSet, val slices: List<BitSet>) {
        val isEmpty: Boolean get() = rows.isEmpty

        fun compare(op: Op, predicate: Long): BitSet {
            val all = { rows.clone() as BitSet }
            val none = { BitSet() }
            when (op) {
                Op.EQ -> if (min == max && min == predicate) return all() else if (predicate < min || predicate > max) return none()
                Op.NEQ -> if (min == max && min == predicate) return none() else if (predicate < min || predicate > max) return all()
                Op.GTE -> if (predicate <= min) return all() else if (predicate > max) return none()
                Op.GT -> if (predicate < min) return all() else if (predicate >= max) return none()
                Op.LTE -> if (predicate >= max) return all() else if (predicate < min) return none()
                Op.LT -> if (predicate > max) return all() else if (predicate <= min) return none()
            }
            val p = predicate - min
            var gt = BitSet()
            var lt = BitSet()
            var eq = all()
            for (i in slices.indices.reversed()) {
                if ((p shr i) and 1L == 1L) {
                    lt = lt or (eq andNot slices[i])
                    eq = eq and slices[i]
                } else {
                    gt = gt or (eq and slices[i])
                    eq = eq andNot slices[i]
                }
            }
            return when (op) {
                Op.EQ -> eq
                Op.NEQ -> all() andNot eq
                Op.GT -> gt
                Op.LT -> lt
                Op.LTE -> lt or eq
                Op.GTE -> gt or eq
            }
        }

        companion object {
            val EMPTY = Slices(0, 0, BitSet(), emptyList())
        }
    }

    enum class Op { EQ, NEQ, LTE, LT, GTE, GT }

    /** How many rows hold a value on either side. */
    val nonNullCount: Int get() = positive.rows.cardinality() + negative.rows.cardinality()

    fun isNotNull(): BitSet = positive.rows or negative.rows

    fun isNull(): BitSet = isNotNull().also { it.flip(0, rowCount) }

    fun eq(value: Long): BitSet = if (value < 0) negative.compare(Op.EQ, -value) else positive.compare(Op.EQ, value)

    fun notEq(value: Long): BitSet = isNotNull() andNot eq(value)

    fun lt(value: Long): BitSet = if (value < 0) negative.compare(Op.GT, -value) else positive.compare(Op.LT, value) or negative.rows

    fun lte(value: Long): BitSet = if (value < 0) negative.compare(Op.GTE, -value) else positive.compare(Op.LTE, value) or negative.rows

    fun gt(value: Long): BitSet = if (value < 0) positive.rows or negative.compare(Op.LT, -value) else positive.compare(Op.GT, value)

    fun gte(value: Long): BitSet = if (value < 0) positive.rows or negative.compare(Op.LTE, -value) else positive.compare(Op.GTE, value)

    /** The rows [op] keeps for [value], or null for an operator the index does not answer. */
    fun rowsMatching(op: PredicateOp, value: Long?): BitSet? = when (op) {
        PredicateOp.IS_NULL -> isNull()
        PredicateOp.IS_NOT_NULL -> isNotNull()
        PredicateOp.EQ -> value?.let(::eq)
        PredicateOp.NOT_EQ -> value?.let(::notEq)
        PredicateOp.LT -> value?.let(::lt)
        PredicateOp.LTE -> value?.let(::lte)
        PredicateOp.GT -> value?.let(::gt)
        PredicateOp.GTE -> value?.let(::gte)
        PredicateOp.LIKE, PredicateOp.NOT_LIKE -> null
    }

    companion object {
        const val VERSION_1 = 1

        /** The index, or null when the bytes are not a version-1 bit-sliced index. */
        fun decode(bytes: ByteArray): PaimonBsiIndex? {
            if (bytes.isEmpty() || bytes[0].toInt() != VERSION_1) return null
            val input = DataInputStream(ByteArrayInputStream(bytes, 1, bytes.size - 1))
            var pos = 1
            fun int(): Int { pos += 4; return input.readInt() }
            fun long(): Long { pos += 8; return input.readLong() }
            fun bool(): Boolean { pos += 1; return input.readBoolean() }
            // A Roaring bitmap is read off the bytes at [pos] in its own little-endian layout, and
            // the cursor moves by what the bitmap consumed; the DataInputStream is skipped past it.
            fun roaring(): BitSet {
                val buffer = ByteBuffer.wrap(bytes, pos, bytes.size - pos).slice().order(ByteOrder.LITTLE_ENDIAN)
                val set = BitSet()
                PuffinReader.readRoaring32(buffer) { set.set(it.toInt()) }
                input.skipBytes(buffer.position())
                pos += buffer.position()
                return set
            }
            fun slices(): Slices {
                if (!bool()) return Slices.EMPTY
                val version = input.readByte().toInt().also { pos += 1 }
                if (version != VERSION_1) throw IllegalArgumentException("a bit-sliced index of version $version is not one this reads")
                val min = long()
                val max = long()
                val rows = roaring()
                val slices = List(int()) { roaring() }
                return Slices(min, max, rows, slices)
            }
            val rowCount = int()
            val positive = slices()
            val negative = slices()
            return PaimonBsiIndex(rowCount, positive, negative)
        }
    }
}

/**
 * A literal as `BitSliceIndexBitmapFileIndex.getValueMapper` maps the column's values, or null
 * for a type the writer refuses to index or a literal the column's scale cannot hold exactly —
 * a decimal is its unscaled value at the column's scale, and rounding a literal to fit would
 * compare the rows with a value the reader did not write.
 */
fun paimonBsiValue(paimonType: String, value: Any): Long? {
    val head = Regex("""^([A-Z_]+)(?:\((\d+)(?:,\s*(\d+))?\))?""").find(paimonType.trim().uppercase()) ?: return null
    return when (head.groupValues[1]) {
        "TINYINT", "SMALLINT", "INT", "BIGINT" -> (value as? Number)?.toLong()
        "DECIMAL" -> (value as? BigDecimal)?.let {
            val scale = head.groupValues[3].toIntOrNull() ?: 0
            runCatching { it.setScale(scale).unscaledValue().longValueExact() }.getOrNull()
        }
        "DATE" -> (value as? LocalDate)?.toEpochDay()
        "TIME" -> (value as? LocalTime)?.let { it.toNanoOfDay() / 1_000_000 }
        "TIMESTAMP", "TIMESTAMP_LTZ" -> paimonTimestampLong(head.groupValues[2].toIntOrNull() ?: 6, value)
        else -> null
    }
}

private infix fun BitSet.or(other: BitSet): BitSet = (clone() as BitSet).also { it.or(other) }
private infix fun BitSet.and(other: BitSet): BitSet = (clone() as BitSet).also { it.and(other) }
private infix fun BitSet.andNot(other: BitSet): BitSet = (clone() as BitSet).also { it.andNot(other) }
