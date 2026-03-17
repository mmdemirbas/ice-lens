package ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
