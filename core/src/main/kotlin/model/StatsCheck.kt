package model

import java.time.Instant
import java.time.LocalDateTime

/**
 * A data file's recorded column statistics against the same figures counted from its rows —
 * the one recorded figure the table-level check (`Integrity.kt`) leaves out, because it costs a
 * file read per entry, and the one a wrong query result most often traces back to: a scan
 * prunes on `lower_bounds` / `upper_bounds` / `null_value_count` (`_VALUE_STATS` on Paimon)
 * without opening the file, so a bound the writer got wrong loses rows with nothing failing.
 *
 * What holds is one-sided on the bounds and exact on the counts. A recorded lower bound may sit
 * *below* the smallest value and an upper bound *above* the largest — Iceberg truncates string
 * metrics to `write.metadata.metrics.default`'s width and increments the upper one, Paimon
 * likewise — so a bound disagrees only when a row lies outside it. A null count is the count,
 * and Iceberg's `value_count` is the file's row count for a top-level column.
 */
data class RecordedColumnStats(
    /** The field id where the format keys statistics by one (Iceberg); null on Paimon, whose statistics are by position. */
    val fieldId: Int?,
    val name: String,
    val lower: Any?,
    val upper: Any?,
    val lowerShown: String?,
    val upperShown: String?,
    val nullCount: Long?,
    val valueCount: Long?,
    val nanCount: Long?,
)

/** What the file's rows say about one column, as DuckDB counted them. NaNs are kept out of the bounds, the way both writers keep them. */
data class ActualColumnStats(val min: Any?, val max: Any?, val nullCount: Long, val nanCount: Long?)

enum class StatsVerdict { AGREES, DISAGREES, NOT_CHECKED }

data class ColumnStatsCheck(
    val column: String,
    val recordedLower: String?,
    val actualMin: String?,
    val recordedUpper: String?,
    val actualMax: String?,
    val recordedNulls: Long?,
    val actualNulls: Long?,
    val verdict: StatsVerdict,
    val reason: String,
    /** The disagreements, one per figure — empty unless [verdict] is [StatsVerdict.DISAGREES]. */
    val problems: List<StatsProblem> = emptyList(),
    /** Figures compared with both sides — nulls, values, NaNs, each bound — none when not checked. */
    val compared: Int = 0,
)

/** One recorded figure the file contradicts: what was recorded, and what counting the rows gave. */
data class StatsProblem(val figure: String, val recorded: String, val counted: String) {
    val sentence: String get() = "$figure: records $recorded, the file holds $counted"
}

data class StatsCheckResult(
    /** Rows counted in the file. */
    val rows: Long,
    /** The entry's `record_count` (`_ROW_COUNT`), compared with [rows]. */
    val recordedRows: Long?,
    val columns: List<ColumnStatsCheck>,
) {
    val rowsAgree: Boolean? get() = recordedRows?.let { it == rows }
    val disagreements: Int get() = columns.count { it.verdict == StatsVerdict.DISAGREES } + (if (rowsAgree == false) 1 else 0)
    val checked: Int get() = columns.count { it.verdict != StatsVerdict.NOT_CHECKED } + (if (rowsAgree != null) 1 else 0)
    /** Figures compared, over every column and the row count — what [problems] is a share of. */
    val figures: Int get() = columns.sumOf { it.compared } + (if (rowsAgree != null) 1 else 0)

    /** Every disagreement, the row count first, each column's figure named with its column. */
    val problems: List<StatsProblem>
        get() = buildList {
            if (rowsAgree == false) add(StatsProblem("row count", "$recordedRows", "$rows"))
            for (c in columns) for (p in c.problems) add(StatsProblem("${c.column} ${p.figure}", p.recorded, p.counted))
        }
}

/** One column's recorded figures against the counted ones, or why they could not be compared. */
fun checkColumnStats(recorded: RecordedColumnStats, actual: ActualColumnStats?, rows: Long): ColumnStatsCheck {
    fun result(verdict: StatsVerdict, reason: String, problems: List<StatsProblem> = emptyList(), compared: Int = 0) = ColumnStatsCheck(
        column = recorded.name,
        recordedLower = recorded.lowerShown, actualMin = actual?.min?.let(::showValue),
        recordedUpper = recorded.upperShown, actualMax = actual?.max?.let(::showValue),
        recordedNulls = recorded.nullCount, actualNulls = actual?.nullCount,
        verdict = verdict, reason = reason, problems = problems, compared = compared,
    )
    if (actual == null) return result(StatsVerdict.NOT_CHECKED, "the file has no column named ${recorded.name}")
    if (recorded.lower == null && recorded.upper == null && recorded.nullCount == null && recorded.valueCount == null) {
        return result(StatsVerdict.NOT_CHECKED, "no statistic recorded for ${recorded.name}")
    }
    val problems = mutableListOf<StatsProblem>()
    var compared = 0
    if (recorded.nullCount != null) {
        compared++
        if (recorded.nullCount != actual.nullCount) problems += StatsProblem("nulls", "${recorded.nullCount}", "${actual.nullCount}")
    }
    if (recorded.valueCount != null) {
        compared++
        if (recorded.valueCount != rows) problems += StatsProblem("values", "${recorded.valueCount}", "$rows")
    }
    if (recorded.nanCount != null && actual.nanCount != null) {
        compared++
        if (recorded.nanCount != actual.nanCount) problems += StatsProblem("NaNs", "${recorded.nanCount}", "${actual.nanCount}")
    }
    val nonNull = rows - actual.nullCount - (actual.nanCount ?: 0L)
    val min = actual.min
    val max = actual.max
    if (recorded.lower != null) {
        if (nonNull == 0L) problems += StatsProblem("lower bound", recorded.lowerShown ?: "a bound", "no value to bound")
        else when (val c = compareValues(recorded.lower, min)) {
            null -> return result(StatsVerdict.NOT_CHECKED, "a ${recorded.lower.javaClass.simpleName} bound and a ${min?.javaClass?.simpleName} value cannot be compared")
            else -> { compared++; if (c > 0) problems += StatsProblem("lower bound", "${recorded.lowerShown}", "a smaller ${showValue(min)}") }
        }
    }
    if (recorded.upper != null) {
        if (nonNull == 0L) { if (recorded.lower == null) problems += StatsProblem("upper bound", recorded.upperShown ?: "a bound", "no value to bound") }
        else when (val c = compareValues(recorded.upper, max)) {
            null -> return result(StatsVerdict.NOT_CHECKED, "a ${recorded.upper.javaClass.simpleName} bound and a ${actual.max?.javaClass?.simpleName} value cannot be compared")
            else -> { compared++; if (c < 0) problems += StatsProblem("upper bound", "${recorded.upperShown}", "a larger ${showValue(max)}") }
        }
    }
    return when {
        problems.isNotEmpty() -> result(StatsVerdict.DISAGREES, problems.joinToString("; ") { it.sentence }, problems, compared)
        compared == 0 -> result(StatsVerdict.NOT_CHECKED, "nothing comparable recorded for ${recorded.name}")
        else -> result(StatsVerdict.AGREES, "the rows lie within the bounds and the counts agree", compared = compared)
    }
}

/** Every recorded column against the counted ones. */
fun checkStats(recorded: List<RecordedColumnStats>, actual: Map<String, ActualColumnStats>, rows: Long, recordedRows: Long?): StatsCheckResult =
    StatsCheckResult(rows, recordedRows, recorded.map { r -> checkColumnStats(r, actual[r.name], rows) })

private fun showValue(v: Any?): String = when (v) {
    null -> "null"
    is ByteArray -> "0x" + v.joinToString("") { "%02x".format(it) }
    else -> v.toString()
}

/** Iceberg's reserved `pos` column of a positional delete file, a `long` the manifest's schema does not describe. */
const val DELETE_POS_FIELD_ID = 2147483545

/**
 * An Iceberg file's `ColumnStats` in the shape the check reads, one per top-level field with any
 * statistic. A positional delete's two reserved columns are not in any table schema, so their
 * bounds are decoded here by the types the spec fixes for them — `file_path` a string, `pos` a
 * long — and named as the file names them.
 */
fun GraphNode.FileNode.recordedColumnStats(): List<RecordedColumnStats> = recordedColumnStatsOf(columnStats, data)

/** The same off a file's decoded statistics and its entry — what the table-wide sweep has without a node. */
fun recordedColumnStatsOf(columnStats: List<ColumnStats>, data: DataFile): List<RecordedColumnStats> = columnStats.map { s ->
    val reserved = when (s.fieldId) {
        DELETE_FILE_PATH_FIELD_ID -> "file_path" to IcebergType.StringType
        DELETE_POS_FIELD_ID -> "pos" to IcebergType.LongType
        else -> null
    }
    // The builder decodes a bound only under a schema type, which the reserved ids never have.
    fun bound(b: DecodedValue?, raw: List<KeyValuePairBytes>?): DecodedValue? = when {
        reserved != null -> raw?.firstOrNull { it.key == s.fieldId }?.value?.let { decodeSingleValue(it, reserved.second) }
        else -> b
    }
    val lower = bound(s.lowerBound, data.lowerBounds)
    val upper = bound(s.upperBound, data.upperBounds)
    RecordedColumnStats(
        fieldId = s.fieldId,
        name = reserved?.first ?: s.displayName,
        lower = lower?.takeIf { !it.isError }?.value,
        upper = upper?.takeIf { !it.isError }?.value,
        lowerShown = lower?.display,
        upperShown = upper?.display,
        nullCount = s.nullValueCount,
        valueCount = s.valueCount,
        nanCount = s.nanValueCount,
    )
}

/**
 * A Paimon file's `_VALUE_STATS` and, on a primary-key table, its `_KEY_STATS` — the latter over
 * the `_KEY_<name>` columns the file physically holds beside the value columns. Paimon records no
 * value count per column and every row holds one value per column, so none is put beside the
 * row count; the row count is compared on its own.
 */
fun GraphNode.PaimonDataFileNode.recordedColumnStats(): List<RecordedColumnStats> = paimonRecordedColumnStats(keyBounds, columnBounds)

/** [GraphNode.PaimonDataFileNode.recordedColumnStats] over the pieces, for a file the graph does not draw. */
fun paimonRecordedColumnStats(keyBounds: List<PaimonColumnBounds>?, columnBounds: List<PaimonColumnBounds>?): List<RecordedColumnStats> {
    fun of(b: PaimonColumnBounds, name: String) = RecordedColumnStats(
        fieldId = null, name = name,
        lower = b.min.takeIf { b.decoded }, upper = b.max.takeIf { b.decoded },
        lowerShown = b.min?.toString(), upperShown = b.max?.toString(),
        nullCount = b.nullCount, valueCount = null, nanCount = null,
    )
    return keyBounds.orEmpty().map { of(it, "_KEY_${it.name}") } + columnBounds.orEmpty().map { of(it, it.name) }
}

/** A DuckDB value in the kinds [compareValues] orders: JDBC's temporal classes to `java.time`'s, a zoned timestamp to an instant. */
fun normalizeDuckValue(v: Any?): Any? = when (v) {
    null -> null
    is java.sql.Timestamp -> v.toLocalDateTime()
    is java.sql.Date -> v.toLocalDate()
    is java.sql.Time -> v.toLocalTime()
    is java.time.OffsetDateTime -> v.toInstant()
    is java.time.ZonedDateTime -> v.toInstant()
    is Instant, is LocalDateTime -> v
    else -> v
}
