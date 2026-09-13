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

/**
 * A count and the noun it counts, with an ending that agrees: `1` → `"1 file"`, `4` → `"4 files"`.
 *
 * Worth a function rather than an `if` at each call site, because the call sites are prose and
 * "1 files never opened" is the kind of thing that reads as a machine wrote the screen.
 */
fun formatCounted(value: Int, singular: String, plural: String = singular + "s"): String =
    "${formatCount(value)} ${if (value == 1) singular else plural}"

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

/**
 * A retention age as the unit it was most likely written in — `30 days`, `12 hours`, `90 min` —
 * with the exact millisecond figure beside it, since `2592000000` is what the metadata holds and
 * what a reader may need to match against a `RETAIN` clause. Null is "not set", which for a ref
 * means the table's own defaults apply.
 */
fun formatRetentionMs(ms: Long?): String {
    if (ms == null) return "not set"
    val dayMs = 86_400_000L
    val hourMs = 3_600_000L
    val minuteMs = 60_000L
    val human = when {
        ms % dayMs == 0L -> "${ms / dayMs} ${if (ms / dayMs == 1L) "day" else "days"}"
        ms % hourMs == 0L -> "${ms / hourMs} ${if (ms / hourMs == 1L) "hour" else "hours"}"
        ms % minuteMs == 0L -> "${ms / minuteMs} min"
        else -> "${ms / 1000.0} s"
    }
    return "$human (${formatCount(ms)} ms)"
}

/** [formatAppTimestamp] with the milliseconds kept where there are any — a time that round-trips through [parseAppTimestamp]. */
fun formatAppTimestampExact(ms: Long): String =
    if (ms % 1000 == 0L) formatAppTimestamp(ms)
    else DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))

/**
 * The inverse of [formatAppTimestamp], for a time a reader types: `yyyy-MM-dd HH:mm:ss[.SSS]` or
 * `yyyy-MM-dd HH:mm` or `yyyy-MM-dd` in the local zone the panels print in, an ISO-8601 instant
 * (`2026-08-14T06:34:41Z`), or epoch milliseconds. Null when none of those reads it.
 */
fun parseAppTimestamp(text: String): Long? {
    val t = text.trim()
    if (t.isEmpty()) return null
    t.toLongOrNull()?.let { return it }
    runCatching { return Instant.parse(t).toEpochMilli() }
    val zone = ZoneId.systemDefault()
    for (pattern in listOf("yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm")) {
        runCatching {
            return java.time.LocalDateTime.parse(t, DateTimeFormatter.ofPattern(pattern)).atZone(zone).toInstant().toEpochMilli()
        }
    }
    runCatching { return java.time.LocalDate.parse(t).atStartOfDay(zone).toInstant().toEpochMilli() }
    return null
}
