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
 * Thousands-separated count: `48102336` → `"48,102,336"`, null → `"N/A"`.
 *
 * [Locale.US] is pinned deliberately, as everywhere else in this app: the audience reads
 * these figures next to engine output and query plans that are themselves US-formatted.
 */
fun formatCount(value: Long?): String = value?.let { String.format(Locale.US, "%,d", it) } ?: "N/A"

fun formatCount(value: Int?): String = formatCount(value?.toLong())

private val BYTE_UNITS = listOf("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")

/**
 * Compact byte size in binary units: `4089446400` → `"3.81 GiB"`. For cards and dense table
 * cells, where the exact figure would not fit.
 *
 * Binary rather than decimal units, and labelled as such, because every engine and object
 * store in this space reports binary — a size that disagrees with `ls -lh` costs the reader
 * a detour, and a "GB" that is really a GiB costs them a wrong conclusion.
 */
fun formatBytes(bytes: Long?): String {
    if (bytes == null || bytes < 0) return "N/A"
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

/**
 * Exact byte size with its compact form alongside: `4096` → `"4,096 B (4.00 KiB)"`. For the
 * inspector, where the reader is comparing against the literal `file_size_in_bytes` a
 * manifest recorded.
 */
fun formatBytesExact(bytes: Long?): String {
    if (bytes == null || bytes < 0) return "N/A"
    if (bytes < 1024) return "$bytes B"
    return "${formatCount(bytes)} B (${formatBytes(bytes)})"
}

fun parseLongSet(raw: String): Set<Long> =
    raw.split(";")
        .mapNotNull { it.trim().toLongOrNull() }
        .toSet()

fun encodeLongSet(values: Set<Long>): String =
    values.sorted().joinToString(";")
