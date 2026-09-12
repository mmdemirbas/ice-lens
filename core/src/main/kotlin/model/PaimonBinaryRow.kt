package model

import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * One partition key of a Paimon table, as a manifest entry's `_PARTITION` records it.
 *
 * [value] is the decoded value — a `LocalDate` for a `DATE`, a `String`, an `Int` — and
 * [pathText] is what Paimon writes after `name=` in the directory the partition's files live
 * under, which is not the same thing: under the default `partition.legacy-name = true` a date is
 * its epoch-day number (`dt=19787`), because the legacy naming is `toString()` of the internal
 * representation. Both are carried because the reader wants the date and the resolver wants the
 * directory.
 */
data class PaimonPartitionValue(
    val name: String,
    val type: String,
    val value: Any?,
    val pathText: String,
) {
    val display: String get() = value?.toString() ?: "null"
}

/** A manifest entry's partition, decoded against the partition keys of the schema it was written under. */
data class DecodedPaimonPartition(val values: List<PaimonPartitionValue>) {
    /** `dt=19787/region=eu` — the partition's directory, relative to the table root. Empty when unpartitioned. */
    val path: String get() = values.joinToString("/") { "${it.name}=${it.pathText}" }

    /** `dt=2024-03-05, region=eu` — the decoded values, for a reader. */
    val display: String get() = values.joinToString(", ") { "${it.name}=${it.display}" }
}

/** Paimon's `partition.default-name`: the directory a null partition value is written under. */
private const val DEFAULT_PARTITION_NAME_OPTION = "partition.default-name"
private const val DEFAULT_PARTITION_NAME = "__DEFAULT_PARTITION__"

/** Paimon's `partition.legacy-name`: `toString()` of the internal value (default) rather than a cast to string. */
private const val LEGACY_PARTITION_NAME_OPTION = "partition.legacy-name"

/**
 * Decodes a manifest entry's `_PARTITION` bytes: a 4-byte big-endian arity, then a Paimon
 * `BinaryRow` holding one field per partition key.
 *
 * The row is little-endian. It opens with a null-bit region of `((arity + 63 + 8) / 64) * 8`
 * bytes whose first eight bits are the row kind, so field *i*'s null bit is bit `i + 8`; then one
 * 8-byte slot per field; then a variable-length tail. A fixed-width value sits at the start of its
 * slot. A variable-width one is either **inline** — up to seven bytes in the slot with the length
 * in the high seven bits of the slot's last byte, whose top bit is set — or in the tail, the slot
 * then holding `(offset shl 32) or length` with the offset counted from the start of the row. The
 * `pt` fixture carries both string shapes (`eu` inline, `north-america` in the tail) and a date,
 * and its directory layout is what the decoding is checked against: the partition read out of the
 * entry has to be the directory the file is in.
 *
 * Returns null when the bytes cannot be read as a row over [keys] — the wrong arity, a key of a
 * type this does not decode, a slot past the end — rather than a partial answer, because a
 * partition path with one wrong segment resolves nowhere and reads as a missing file.
 */
fun decodePaimonPartition(
    bytes: ByteArray,
    keys: List<PaimonField>,
    options: Map<String, String> = emptyMap(),
): DecodedPaimonPartition? {
    if (keys.isEmpty()) return DecodedPaimonPartition(emptyList())
    if (bytes.size < 4) return null
    val arity = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.BIG_ENDIAN).int
    if (arity != keys.size) return null

    val row = ByteBuffer.wrap(bytes, 4, bytes.size - 4).slice().order(ByteOrder.LITTLE_ENDIAN)
    val nullBitsBytes = ((arity + 63 + 8) / 64) * 8
    if (row.limit() < nullBitsBytes + 8 * arity) return null

    val defaultName = options[DEFAULT_PARTITION_NAME_OPTION] ?: DEFAULT_PARTITION_NAME
    val legacyNames = options[LEGACY_PARTITION_NAME_OPTION]?.toBooleanStrictOrNull() ?: true

    val values = keys.mapIndexed { index, key ->
        val bitPosition = index + 8
        val isNull = (row.get(bitPosition ushr 3).toInt() shr (bitPosition and 7)) and 1 == 1
        val slot = nullBitsBytes + 8 * index
        val type = key.type.orEmpty()
        val value = if (isNull) null else decodeField(row, slot, type) ?: return null
        PaimonPartitionValue(
            name = key.name.orEmpty(),
            type = type,
            value = value,
            pathText = if (value == null) defaultName else escapePartitionValue(partitionPathText(value, legacyNames)),
        )
    }
    return DecodedPaimonPartition(values)
}

private val TYPE_HEAD = Regex("""^([A-Z]+)(?:\((\d+)(?:,\s*(\d+))?\))?""")

/** One field out of its slot, or null for a type this does not decode. */
private fun decodeField(row: ByteBuffer, slot: Int, type: String): Any? {
    val head = TYPE_HEAD.find(type.trim().uppercase()) ?: return null
    val name = head.groupValues[1]
    val p1 = head.groupValues[2].toIntOrNull()
    val p2 = head.groupValues[3].toIntOrNull()
    return when (name) {
        "BOOLEAN" -> row.get(slot).toInt() != 0
        "TINYINT" -> row.get(slot).toInt()
        "SMALLINT" -> row.getShort(slot).toInt()
        "INT" -> row.getInt(slot)
        "BIGINT" -> row.getLong(slot)
        "FLOAT" -> row.getFloat(slot)
        "DOUBLE" -> row.getDouble(slot)
        "DATE" -> LocalDate.ofEpochDay(row.getInt(slot).toLong())
        "TIME" -> LocalTime.ofNanoOfDay(row.getInt(slot) * 1_000_000L)
        "DECIMAL" -> {
            val precision = p1 ?: 10
            val scale = p2 ?: 0
            // Compact below 19 digits: the unscaled long in the slot. Above, the unscaled
            // BigInteger's two's-complement bytes in the tail.
            if (precision <= 18) BigDecimal.valueOf(row.getLong(slot), scale)
            else BigDecimal(BigInteger(variableBytes(row, slot) ?: return null), scale)
        }
        "TIMESTAMP" -> {
            val precision = p1 ?: 6
            // Compact at millisecond precision or below: epoch millis in the slot. Above, twelve
            // bytes in the tail — epoch millis and the nanos within that millisecond.
            if (precision <= 3) {
                val millis = row.getLong(slot)
                LocalDateTime.ofEpochSecond(Math.floorDiv(millis, 1000L), Math.floorMod(millis, 1000L).toInt() * 1_000_000, ZoneOffset.UTC)
            } else {
                val bytes = variableBytes(row, slot) ?: return null
                if (bytes.size < 12) return null
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val millis = buf.getLong(0)
                val nanosOfMilli = buf.getInt(8)
                LocalDateTime.ofEpochSecond(Math.floorDiv(millis, 1000L), Math.floorMod(millis, 1000L).toInt() * 1_000_000 + nanosOfMilli, ZoneOffset.UTC)
            }
        }
        "CHAR", "VARCHAR", "STRING" -> String(variableBytes(row, slot) ?: return null, Charsets.UTF_8)
        "BINARY", "VARBINARY", "BYTES" -> (variableBytes(row, slot) ?: return null).joinToString("") { "%02x".format(it) }
        else -> null
    }
}

private const val HIGHEST_FIRST_BIT = 0x80L shl 56
private const val HIGHEST_SECOND_TO_EIGHTH_BIT = 0x7FL shl 56

/** A variable-width field's bytes — inline in the slot when short, from the tail otherwise. */
private fun variableBytes(row: ByteBuffer, slot: Int): ByteArray? {
    val offsetAndLength = row.getLong(slot)
    val (start, length) = if (offsetAndLength and HIGHEST_FIRST_BIT == 0L) {
        (offsetAndLength ushr 32).toInt() to offsetAndLength.toInt()
    } else {
        slot to ((offsetAndLength and HIGHEST_SECOND_TO_EIGHTH_BIT) ushr 56).toInt()
    }
    if (length < 0 || start < 0 || start + length > row.limit()) return null
    return ByteArray(length).also { row.duplicate().position(start).get(it) }
}

/**
 * What Paimon writes after `name=` for a value. Under the default legacy naming that is the
 * internal value's `toString()`, which for a date is its epoch day; with
 * `partition.legacy-name = false` a date is cast to `yyyy-MM-dd`. Only the date differs between
 * the two for the types decoded here — a string, an integer and a decimal print the same either
 * way — and only the legacy form has been checked against a directory Paimon wrote.
 */
private fun partitionPathText(value: Any, legacy: Boolean): String = when (value) {
    is LocalDate -> if (legacy) value.toEpochDay().toString() else value.toString()
    else -> value.toString()
}

/** The characters Paimon's `PartitionPathUtils.escapePathName` percent-encodes. */
private val ESCAPED = (0..31).map { it.toChar() }.toSet() +
    setOf('"', '#', '%', '\'', '*', '/', ':', '=', '?', '\\', '\u007F', '{', '[', ']', '^')

private fun escapePartitionValue(text: String): String = buildString {
    text.forEach { c ->
        if (c in ESCAPED) append("%%%02X".format(c.code)) else append(c)
    }
}
