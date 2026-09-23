package ui

import model.formatCount
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val appDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

fun formatAppTimestamp(ms: Long?): String =
    ms?.let { appDateTimeFormatter.format(Instant.ofEpochMilli(it)) } ?: "N/A"

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
    // Twelve digits or more: epoch milliseconds since 2001. A bare year or a short number would
    // otherwise read as a moment in 1970 and resolve to nothing, with nothing looking wrong.
    if (t.length >= 12 && t.all { it.isDigit() }) t.toLongOrNull()?.let { return it }
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
