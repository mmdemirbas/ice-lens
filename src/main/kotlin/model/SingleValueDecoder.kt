package model

import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * A value read out of a manifest, together with how it should be shown and where it came from.
 *
 * [display] is what the UI prints. [value] is the typed Kotlin value for anything that wants to
 * compare or sort. [raw] is kept so the inspector can always fall back to the bytes — a decoder
 * that hides its input is impossible to check when it is wrong.
 */
data class DecodedValue(
    val display: String,
    val value: Any?,
    val type: IcebergType,
    val raw: ByteArray,
    /** Set when the bytes could not be decoded as [type]; [display] then holds the hex. */
    val error: String? = null,
) {
    val isError: Boolean get() = error != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecodedValue) return false
        return display == other.display && value == other.value && type == other.type &&
            raw.contentEquals(other.raw) && error == other.error
    }

    override fun hashCode(): Int {
        var result = display.hashCode()
        result = 31 * result + (value?.hashCode() ?: 0)
        result = 31 * result + type.hashCode()
        result = 31 * result + raw.contentHashCode()
        result = 31 * result + (error?.hashCode() ?: 0)
        return result
    }
}

/** Hex rendering, used for undecodable values and for the raw view. */
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/**
 * Decodes one value using Iceberg's single-value binary serialization (spec Appendix D).
 *
 * This is the encoding used by `lower_bounds`, `upper_bounds` and `partition` values. It is
 * deliberately total: a value that cannot be decoded comes back as a [DecodedValue] carrying the
 * hex and an error string, never an exception. A manifest holding one surprising value should
 * still open.
 *
 * Encoding, per the spec:
 *
 * | Type | Bytes |
 * |---|---|
 * | boolean | 1, `0x00` false / `0x01` true |
 * | int, date | 4, little-endian |
 * | long, time, timestamp, timestamptz, timestamp_ns | 8, little-endian |
 * | float | 4, little-endian IEEE 754 |
 * | double | 8, little-endian IEEE 754 |
 * | string | UTF-8, **no length prefix** |
 * | uuid | 16, **big-endian** |
 * | fixed(L), binary | the bytes |
 * | decimal(P,S) | unscaled value, two's-complement **big-endian**, minimum bytes |
 *
 * The traps, all of which produce plausible wrong answers rather than errors:
 *
 * - **Endianness is not uniform.** Numbers are little-endian; uuid and decimal are big-endian.
 * - **Decimal length is variable.** The byte count depends on the magnitude of the individual
 *   value, not on the precision, so length cannot be used to infer the type. The scale comes
 *   from the schema, never from the bytes.
 * - **`time` is microseconds from midnight**, not milliseconds, and `timestamp` is microseconds
 *   from the epoch unless the type says `_ns`.
 * - **Geometry and geography bounds are not geometries.** The spec stores a single point as
 *   concatenated 8-byte little-endian doubles, so a bound decoded as WKB would be wrong.
 */
fun decodeSingleValue(bytes: ByteArray, type: IcebergType): DecodedValue {
    fun ok(display: String, value: Any?) = DecodedValue(display, value, type, bytes)
    fun bad(reason: String) = DecodedValue(bytes.toHex(), null, type, bytes, reason)

    fun requireSize(expected: Int): String? =
        if (bytes.size == expected) null else "expected $expected bytes for ${type.typeName}, got ${bytes.size}"

    fun le(): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    return runCatching {
        when (type) {
            is IcebergType.BooleanType -> requireSize(1)?.let(::bad)
                ?: ok(if (bytes[0].toInt() != 0) "true" else "false", bytes[0].toInt() != 0)

            is IcebergType.IntType -> requireSize(4)?.let(::bad) ?: le().int.let { ok(it.toString(), it) }

            is IcebergType.LongType -> requireSize(8)?.let(::bad) ?: le().long.let { ok(it.toString(), it) }

            is IcebergType.FloatType -> requireSize(4)?.let(::bad) ?: le().float.let { ok(formatFloat(it.toDouble()), it) }

            is IcebergType.DoubleType -> requireSize(8)?.let(::bad) ?: le().double.let { ok(formatFloat(it), it) }

            is IcebergType.DateType -> requireSize(4)?.let(::bad) ?: le().int.let { days ->
                val date = LocalDate.ofEpochDay(days.toLong())
                ok("$date", date)
            }

            is IcebergType.TimeType -> requireSize(8)?.let(::bad) ?: le().long.let { v ->
                val nanos = if (type.unit == IcebergType.TimeUnit.NANOS) v else v * 1_000
                val time = LocalTime.ofNanoOfDay(nanos)
                ok("$time", time)
            }

            is IcebergType.TimestampType -> requireSize(8)?.let(::bad) ?: le().long.let { v ->
                val instant = if (type.unit == IcebergType.TimeUnit.NANOS) {
                    Instant.ofEpochSecond(Math.floorDiv(v, 1_000_000_000L), Math.floorMod(v, 1_000_000_000L).toLong())
                } else {
                    Instant.ofEpochSecond(Math.floorDiv(v, 1_000_000L), Math.floorMod(v, 1_000_000L).toLong() * 1_000)
                }
                if (type.withZone) {
                    ok(instant.toString(), instant)
                } else {
                    val local = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)
                    ok(local.toString(), local)
                }
            }

            is IcebergType.StringType -> bytes.toString(Charsets.UTF_8).let { ok(it, it) }

            is IcebergType.UuidType -> requireSize(16)?.let(::bad) ?: run {
                // Big-endian, unlike every numeric type above.
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                val uuid = UUID(bb.long, bb.long)
                ok(uuid.toString(), uuid)
            }

            is IcebergType.FixedType ->
                if (bytes.size != type.length) bad("expected ${type.length} bytes for ${type.typeName}, got ${bytes.size}")
                else ok("0x${bytes.toHex()}", bytes)

            is IcebergType.BinaryType -> ok("0x${bytes.toHex()} (${bytes.size} bytes)", bytes)

            is IcebergType.DecimalType ->
                if (bytes.isEmpty()) bad("empty value for ${type.typeName}")
                else {
                    // Unscaled two's-complement big-endian in the minimum number of bytes, so the
                    // length varies per value. BigInteger(ByteArray) reads exactly this form.
                    val unscaled = BigInteger(bytes)
                    val dec = BigDecimal(unscaled, type.scale)
                    ok(dec.toPlainString(), dec)
                }

            is IcebergType.UnknownType -> ok("null", null)

            is IcebergType.GeometryType, is IcebergType.GeographyType -> decodePointBound(bytes, type)

            is IcebergType.VariantType ->
                // Variant bounds are Variant metadata concatenated with a Variant object keyed by
                // normalized JSON paths. Not decoded yet; show the bytes rather than a wrong value.
                DecodedValue("0x${bytes.toHex()} (${bytes.size} bytes)", null, type, bytes,
                    "variant bound decoding not implemented")

            is IcebergType.StructType, is IcebergType.ListType, is IcebergType.MapType ->
                bad("${type.typeName} has no single-value encoding")

            is IcebergType.UnparsedType -> bad("unrecognised type '${type.raw}'")
        }
    }.getOrElse { e -> bad(e.message ?: e::class.simpleName ?: "decode failed") }
}

/**
 * Geometry and geography bounds hold a single point, stored as concatenated 8-byte little-endian
 * doubles in x, y, z, m order. z and m are optional and, per the spec, are omitted from the end
 * — a NaN in either position means "not present" for that coordinate while a later one is.
 */
private fun decodePointBound(bytes: ByteArray, type: IcebergType): DecodedValue {
    if (bytes.size % 8 != 0 || bytes.size < 16 || bytes.size > 32) {
        return DecodedValue(bytes.toHex(), null, type, bytes,
            "expected 16, 24 or 32 bytes for a ${type.typeName} bound, got ${bytes.size}")
    }
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val coords = List(bytes.size / 8) { bb.double }
    val names = listOf("x", "y", "z", "m")
    val shown = coords.indices
        .filter { !coords[it].isNaN() }
        .joinToString(", ") { "${names[it]}=${formatFloat(coords[it])}" }
    return DecodedValue(if (shown.isEmpty()) "(empty point)" else "($shown)", coords, type, bytes)
}

/** Renders a double without scientific notation for ordinary magnitudes, and names the specials. */
private fun formatFloat(value: Double): String = when {
    value.isNaN() -> "NaN"
    value == Double.POSITIVE_INFINITY -> "Infinity"
    value == Double.NEGATIVE_INFINITY -> "-Infinity"
    // -0.0 sorts before +0.0 in Iceberg's ordering, so the sign is load-bearing here.
    value == 0.0 && 1.0 / value < 0 -> "-0.0"
    // valueOf, not the BigDecimal(double) constructor: the constructor expands to the exact
    // binary value, so 0.1 prints as 0.1000000000000000055511151231257827021181583404541015625.
    else -> BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}
