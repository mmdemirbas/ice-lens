package ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val appDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

fun formatAppTimestamp(ms: Long?): String =
    ms?.let { appDateTimeFormatter.format(Instant.ofEpochMilli(it)) } ?: "N/A"

/**
 * Thousands-separated count: `48102336` → `"48,102,336"`.
 *
 * [Locale.US] is pinned deliberately, as everywhere else in this app: the audience reads
 * these figures next to engine output and query plans that are themselves US-formatted.
 */
fun formatCount(value: Long): String = String.format(Locale.US, "%,d", value)

fun formatCount(value: Int): String = formatCount(value.toLong())

private val BYTE_UNITS = listOf("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")

/**
 * Human-readable byte size in binary units: `4089446400` → `"3.81 GiB"`.
 *
 * Binary rather than decimal units because every engine and object store in this space
 * reports binary, and a size that disagrees with `ls -lh` costs the reader a detour.
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "N/A"
    if (bytes < 1024) return "$bytes B"
    var value = bytes.toDouble() / 1024
    var unitIndex = 0
    while (value >= 1024 && unitIndex < BYTE_UNITS.lastIndex) {
        value /= 1024
        unitIndex++
    }
    val precision = if (value >= 100) 0 else if (value >= 10) 1 else 2
    return String.format(Locale.US, "%.${precision}f %s", value, BYTE_UNITS[unitIndex])
}

fun parseLongSet(raw: String): Set<Long> =
    raw.split(";")
        .mapNotNull { it.trim().toLongOrNull() }
        .toSet()

fun encodeLongSet(values: Set<Long>): String =
    values.sorted().joinToString(";")
