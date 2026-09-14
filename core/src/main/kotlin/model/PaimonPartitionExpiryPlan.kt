package model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.time.format.SignStyle
import java.time.temporal.ChronoField
import java.util.Locale

/**
 * Which partitions a Paimon partition expiry drops — `PartitionExpire.doExpire` at
 * release-1.3.1, planned from the latest snapshot's partitions and the table's options.
 *
 * A table has a `PartitionExpire` only when `partition.expiration-time` is set and it is
 * partitioned (`AbstractFileStore.newPartitionExpire`), and a `write-only` table never runs
 * one. A write checks it every `partition.expiration-check-interval` (1 h; the
 * `expire_partitions` procedure sets it to 0 so its call always runs), and the run reads the
 * partitions off the latest snapshot the way `readPartitionEntries` does: every entry of the
 * base and delta manifests folded per partition, a `DELETE` entry subtracting its counts, a
 * partition kept only while its file count is above zero. Then the strategy
 * `partition.expiration-strategy` names decides each:
 *
 * - `values-time` (the default) reads a time off the partition's **values** —
 *   `partition.timestamp-pattern` with each `$field` replaced by the field's value, else the
 *   first partition field's value alone — through `partition.timestamp-formatter`, else the
 *   default `yyyy-MM-dd[ HH:mm:ss[.n]]` and `yyyy-MM-dd` (lenient), and the partition is expired
 *   when `now - partition.expiration-time` is after that time. A value the formatter cannot
 *   parse, or a null value, is warned about and kept — a `DATE` partition column is such a
 *   value, since the row's getter spells it as its epoch day.
 * - `update-time` expires a partition whose newest file — the greatest `_CREATION_TIME` over
 *   the entries folded, a `DELETE` entry's included, since `PartitionEntry.merge` takes the
 *   maximum whatever the kind — is older than the cutoff.
 *
 * The expired partitions are sorted by their values as text and cut to
 * `partition.expiration-max-num` (100); the drop is one `OVERWRITE` commit removing their
 * files from the manifests, which stay on disk until a snapshot expiry reaches that commit's
 * `DELETE` entries. `ppx`/`ppxa` is the oracle: the same table before and after a bare
 * `expire_partitions` call.
 */
data class PaimonPartitionEntry(
    val partition: DecodedPaimonPartition,
    /** `ADD` entries less `DELETE` entries; a partition at zero is not listed, see [paimonPartitionEntriesOf]. */
    val fileCount: Long,
    val recordCount: Long,
    val fileSizeBytes: Long,
    /** The greatest `_CREATION_TIME` over every entry, removals included. Null where no entry records one. */
    val lastFileCreationTimeMs: Long?,
)

enum class PaimonPartitionExpireStrategy(val spelled: String) {
    VALUES_TIME("values-time"),
    UPDATE_TIME("update-time"),

    /** A factory the table names; what it does is its own. */
    CUSTOM("custom"),
    ;

    companion object {
        fun parse(text: String?): PaimonPartitionExpireStrategy? = entries.firstOrNull { it.spelled.equals(text?.trim(), ignoreCase = true) }
    }
}

data class PaimonPartitionExpiryVerdict(
    val entry: PaimonPartitionEntry,
    /** The time the strategy read for the partition — off its values, or its newest file — as epoch milliseconds; null where none could be read. */
    val timeMs: Long?,
    val expired: Boolean,
    val reason: String,
)

data class PaimonPartitionExpiryPlan(
    /** `partition.expiration-time`; null where the table sets none, and then nothing here expires and a bare call is refused. */
    val expirationMs: Long?,
    val strategy: PaimonPartitionExpireStrategy?,
    val strategySpelled: String,
    val formatter: String?,
    val pattern: String?,
    val checkIntervalMs: Long,
    val maxNum: Int,
    /** `now - partition.expiration-time`; null without an expiration time. */
    val cutoffMs: Long?,
    val partitions: List<PaimonPartitionExpiryVerdict>,
) {
    /** The partitions the run drops: the expired ones by their values as text, at most [maxNum]. */
    val dropped: List<PaimonPartitionExpiryVerdict> get() = expiredInOrder.take(maxNum)

    /** Expired but past the cap — the next run's. */
    val heldBack: Int get() = (expiredInOrder.size - maxNum).coerceAtLeast(0)

    private val expiredInOrder: List<PaimonPartitionExpiryVerdict>
        get() = partitions.filter { it.expired }.sortedBy { v -> v.entry.partition.values.joinToString(",") { paimonPartitionValueText(it) ?: "" } }
}

const val PAIMON_PARTITION_EXPIRATION_TIME_OPTION = "partition.expiration-time"
const val PAIMON_PARTITION_EXPIRATION_STRATEGY_OPTION = "partition.expiration-strategy"
const val PAIMON_PARTITION_EXPIRATION_CHECK_INTERVAL_OPTION = "partition.expiration-check-interval"
const val PAIMON_PARTITION_EXPIRATION_MAX_NUM_OPTION = "partition.expiration-max-num"
const val PAIMON_PARTITION_TIMESTAMP_FORMATTER_OPTION = "partition.timestamp-formatter"
const val PAIMON_PARTITION_TIMESTAMP_PATTERN_OPTION = "partition.timestamp-pattern"
const val PAIMON_DEFAULT_PARTITION_EXPIRATION_CHECK_INTERVAL_MS = 60L * 60 * 1000
const val PAIMON_DEFAULT_PARTITION_EXPIRATION_MAX_NUM = 100

/**
 * The plan under the table's own options at [nowMs]. [zone] is what `LocalDateTime.now()` and
 * the cutoff's `atZone(systemDefault)` are taken in — the writer's own zone when it runs.
 */
fun planPaimonPartitionExpiry(
    partitions: List<PaimonPartitionEntry>,
    tableOptions: Map<String, String>,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): PaimonPartitionExpiryPlan {
    val expirationMs = tableOptions[PAIMON_PARTITION_EXPIRATION_TIME_OPTION]?.let { parsePaimonDurationMs(it) }
    val strategySpelled = tableOptions[PAIMON_PARTITION_EXPIRATION_STRATEGY_OPTION] ?: PaimonPartitionExpireStrategy.VALUES_TIME.spelled
    val strategy = PaimonPartitionExpireStrategy.parse(strategySpelled)
    val formatter = tableOptions[PAIMON_PARTITION_TIMESTAMP_FORMATTER_OPTION]
    val pattern = tableOptions[PAIMON_PARTITION_TIMESTAMP_PATTERN_OPTION]
    val checkIntervalMs = tableOptions[PAIMON_PARTITION_EXPIRATION_CHECK_INTERVAL_OPTION]?.let { parsePaimonDurationMs(it) } ?: PAIMON_DEFAULT_PARTITION_EXPIRATION_CHECK_INTERVAL_MS
    val maxNum = tableOptions[PAIMON_PARTITION_EXPIRATION_MAX_NUM_OPTION]?.toIntOrNull() ?: PAIMON_DEFAULT_PARTITION_EXPIRATION_MAX_NUM
    val cutoffMs = expirationMs?.let { nowMs - it }
    val verdicts = partitions.map { entry ->
        when {
            cutoffMs == null -> PaimonPartitionExpiryVerdict(entry, null, false, "no $PAIMON_PARTITION_EXPIRATION_TIME_OPTION is set, so nothing expires")
            strategy == PaimonPartitionExpireStrategy.VALUES_TIME -> valuesTimeVerdict(entry, cutoffMs, pattern, formatter, zone)
            strategy == PaimonPartitionExpireStrategy.UPDATE_TIME -> {
                val newest = entry.lastFileCreationTimeMs
                when {
                    newest == null -> PaimonPartitionExpiryVerdict(entry, null, false, "no entry records a creation time")
                    cutoffMs > newest -> PaimonPartitionExpiryVerdict(entry, newest, true, "its newest file is older than the cutoff")
                    else -> PaimonPartitionExpiryVerdict(entry, newest, false, "its newest file is at or after the cutoff")
                }
            }
            else -> PaimonPartitionExpiryVerdict(entry, null, false, "strategy $strategySpelled is a factory of the table's own, not read here")
        }
    }
    return PaimonPartitionExpiryPlan(expirationMs, strategy, strategySpelled, formatter, pattern, checkIntervalMs, maxNum, cutoffMs, verdicts)
}

private fun valuesTimeVerdict(entry: PaimonPartitionEntry, cutoffMs: Long, pattern: String?, formatter: String?, zone: ZoneId): PaimonPartitionExpiryVerdict {
    val values = entry.partition.values
    if (values.isEmpty()) return PaimonPartitionExpiryVerdict(entry, null, false, "unpartitioned")
    // A null value is a NullPointerException in the extractor, caught and kept with a warning.
    val texts = values.map { paimonPartitionValueText(it) }
    val text = if (pattern == null) {
        texts.first() ?: return PaimonPartitionExpiryVerdict(entry, null, false, "its ${values.first().name} is null, which the extractor cannot read a time from")
    } else {
        var s: String = pattern
        values.zip(texts).forEach { (v, t) ->
            if (s.contains("\$${v.name}") && t == null) return PaimonPartitionExpiryVerdict(entry, null, false, "its ${v.name} is null, which the extractor cannot read a time from")
            s = s.replace(Regex("\\$" + Regex.escape(v.name)), Regex.escapeReplacement(t ?: ""))
        }
        s
    }
    val time = paimonPartitionTime(text, formatter)
        ?: return PaimonPartitionExpiryVerdict(entry, null, false, "'$text' does not parse as a time under ${formatter?.let { "'$it'" } ?: "the default formatter"}, so it is kept with a warning")
    val timeMs = time.atZone(zone).toInstant().toEpochMilli()
    return if (cutoffMs > timeMs) PaimonPartitionExpiryVerdict(entry, timeMs, true, "'$text' is before the cutoff")
    else PaimonPartitionExpiryVerdict(entry, timeMs, false, "'$text' is at or after the cutoff")
}

/**
 * A partition value spelled the way `RowDataToObjectArrayConverter` hands it to the extractor —
 * the field getter's object and its `toString`: a `DATE` as its epoch day, a timestamp in ISO
 * form, a string as itself. Null for a null value.
 */
fun paimonPartitionValueText(value: PaimonPartitionValue): String? = when (val v = value.value) {
    null -> null
    is LocalDate -> v.toEpochDay().toString()
    else -> v.toString()
}

private val PAIMON_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatterBuilder()
    .appendValue(ChronoField.YEAR, 1, 10, SignStyle.NORMAL).appendLiteral('-')
    .appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, SignStyle.NORMAL).appendLiteral('-')
    .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NORMAL)
    .optionalStart().appendLiteral(' ')
    .appendValue(ChronoField.HOUR_OF_DAY, 1, 2, SignStyle.NORMAL).appendLiteral(':')
    .appendValue(ChronoField.MINUTE_OF_HOUR, 1, 2, SignStyle.NORMAL).appendLiteral(':')
    .appendValue(ChronoField.SECOND_OF_MINUTE, 1, 2, SignStyle.NORMAL)
    .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
    .optionalEnd()
    .toFormatter().withResolverStyle(ResolverStyle.LENIENT)

private val PAIMON_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatterBuilder()
    .appendValue(ChronoField.YEAR, 1, 10, SignStyle.NORMAL).appendLiteral('-')
    .appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, SignStyle.NORMAL).appendLiteral('-')
    .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NORMAL)
    .toFormatter().withResolverStyle(ResolverStyle.LENIENT)

/** `PartitionTimeExtractor.toLocalDateTime`: the formatter as a timestamp, then as a date at midnight; null where neither reads it. */
fun paimonPartitionTime(text: String, formatter: String?): LocalDateTime? = runCatching {
    if (formatter == null) {
        try { LocalDateTime.parse(text, PAIMON_TIMESTAMP_FORMATTER) } catch (e: DateTimeParseException) { LocalDateTime.of(LocalDate.parse(text, PAIMON_DATE_FORMATTER), LocalTime.MIDNIGHT) }
    } else {
        val f = DateTimeFormatter.ofPattern(formatter, Locale.ROOT)
        try { LocalDateTime.parse(text, f) } catch (e: DateTimeParseException) { LocalDateTime.of(LocalDate.parse(text, f), LocalTime.MIDNIGHT) }
    }
}.getOrNull()

/** The latest snapshot's partitions folded the way `readPartitionEntries` folds them — every entry of the base and delta manifests. */
fun paimonPartitionEntriesOf(snapshot: PaimonUnifiedSnapshot): List<PaimonPartitionEntry> {
    class Fold(var files: Long = 0, var records: Long = 0, var bytes: Long = 0, var newest: Long? = null)
    val folds = LinkedHashMap<String, Pair<DecodedPaimonPartition, Fold>>()
    for (manifest in snapshot.baseManifests + snapshot.deltaManifests) {
        for (entry in manifest.entries) {
            val partition = entry.partition ?: continue
            val fold = folds.getOrPut(partition.path) { partition to Fold() }.second
            val sign = if (entry.metadata.kind == PaimonEntryKind.DELETE) -1 else 1
            val file = entry.metadata.file
            fold.files += sign
            fold.records += sign * (file?.rowCount ?: 0L)
            fold.bytes += sign * (file?.fileSize ?: 0L)
            file?.creationTime?.let { fold.newest = maxOf(fold.newest ?: Long.MIN_VALUE, it) }
        }
    }
    // A partition every file of which was removed folds to zero and is not listed, as the scan does not list it.
    return folds.values.map { (partition, fold) -> PaimonPartitionEntry(partition, fold.files, fold.records, fold.bytes, fold.newest) }.filter { it.fileCount > 0 }
}
