package model

import java.util.Locale

/**
 * What `expire_snapshots` would remove from a Paimon table, decided the way
 * `ExpireSnapshotsImpl.expire()` decides it — read from that method (Paimon 1.3.1, unchanged at
 * the current main), because the option names describe bounds and the code is what says how the
 * bounds combine:
 *
 * 1. `min = max(latest - retainMax + 1, earliest)`: everything below it is beyond
 *    `snapshot.num-retained.max` and goes **whatever its age**.
 * 2. `maxExclusive = latest - retainMin + 1`: the newest `snapshot.num-retained.min` stay. It is
 *    then clipped to the smallest `nextSnapshot` any consumer records — a reader's bookmark and
 *    everything after it stay — and to `earliest + snapshot.expire.limit`, at most that many
 *    going in one run.
 * 3. Between the two, the run stops at the **first** snapshot younger than the cutoff — `now`
 *    minus `snapshot.time-retained`, or the procedure's `older_than` — and everything from it on
 *    stays. Age is consulted only there: a snapshot below `min` goes seconds old, and one above
 *    `maxExclusive` stays years old.
 * 4. `[earliest, end)` is removed. A removed snapshot a tag names keeps its files on disk, since
 *    expiry skips a data file a tagged snapshot still lists; only the snapshot itself goes.
 *
 * `retainMax < retainMin` is rejected by Paimon before any of this runs, and here too.
 */
data class PaimonExpiryOptions(
    /** The moment the ages are measured from. */
    val nowMs: Long,
    /** The procedure's `retain_max`, or null for the table's `snapshot.num-retained.max` (unbounded). */
    val retainMax: Int? = null,
    /** The procedure's `retain_min`, or null for the table's `snapshot.num-retained.min` (10). */
    val retainMin: Int? = null,
    /** The procedure's `older_than` as a moment, or null for [nowMs] minus the table's `snapshot.time-retained` (1h). */
    val olderThanMs: Long? = null,
    /** The procedure's `max_deletes`, or null for the table's `snapshot.expire.limit` (50). */
    val maxDeletes: Int? = null,
)

/** The rule under which a snapshot survives an expiry. */
enum class PaimonKeepRule(val label: String) {
    /** Among the newest `snapshot.num-retained.min`. */
    RETAIN_MIN("within num-retained.min"),
    /** At or after a consumer's `nextSnapshot`. */
    CONSUMER("not yet consumed by"),
    /** Past what `snapshot.expire.limit` lets one run remove. */
    EXPIRE_LIMIT("past expire.limit for one run"),
    /** Younger than the cutoff, and the first such — where the run stops. */
    YOUNGER_THAN_CUTOFF("younger than the cutoff, where the run stops"),
    /** After the snapshot the run stopped at, whatever its own age. */
    BEHIND_STOP("behind where the run stopped"),
}

/** One reason a snapshot survives: the rule, and for [PaimonKeepRule.CONSUMER] the consumers it applies through. */
data class PaimonKeep(val rule: PaimonKeepRule, val consumers: List<String> = emptyList())

data class PaimonSnapshotExpiryVerdict(
    val snapshotId: Long,
    val retained: Boolean,
    /** Every rule that keeps it, in [PaimonKeepRule] order; empty when removed. */
    val keptBy: List<PaimonKeep>,
    /** The tags naming it — on a removed snapshot, what keeps its files after it. */
    val tags: List<String>,
) {
    /** `within num-retained.min; not yet consumed by reader`. */
    fun describeKeptBy(): String = keptBy.joinToString("; ") { keep ->
        if (keep.consumers.isEmpty()) keep.rule.label else "${keep.rule.label} ${keep.consumers.joinToString(", ")}"
    }
}

data class PaimonExpiryPlan(
    val options: PaimonExpiryOptions,
    /** What the run would use, options resolved against the table's own. */
    val retainMax: Int,
    val retainMin: Int,
    val maxDeletes: Int,
    val cutoffMs: Long,
    /** `snapshot/`'s first and last, or null on a table with none. */
    val earliestId: Long?,
    val latestId: Long?,
    /** Why the walk stops where it does, as [PaimonKeepRule]s, for a reader who wants the bound rather than the rows. */
    val snapshots: List<PaimonSnapshotExpiryVerdict>,
) {
    val removed: List<PaimonSnapshotExpiryVerdict> get() = snapshots.filter { !it.retained }
}

/**
 * The small part of a table an expiry is decided from, carried on the table node so the panel
 * can plan without the model: `snapshot/`'s ids and times, the consumers' bookmarks, the tags,
 * and the options the latest schema records.
 */
data class PaimonExpiryInput(
    /** `snapshot/`'s contents — id to `timeMillis`; a snapshot with no time recorded counts as older than any cutoff. */
    val snapshotTimes: Map<Long, Long?>,
    /** Consumer name to the `nextSnapshot` it records; one with none recorded holds nothing back. */
    val consumerNext: Map<String, Long?>,
    val tagsBySnapshotId: Map<Long, List<String>>,
    /** The latest schema's `options`, where `snapshot.*` retention is set. */
    val tableOptions: Map<String, String>,
    /** `changelog/`'s contents — id to `timeMillis` — where the changelog lifecycle is decoupled; see [planChangelogExpiry]. */
    val changelogTimes: Map<Long, Long?> = emptyMap(),
)

fun PaimonUnifiedTableModel.expiryInput(): PaimonExpiryInput = PaimonExpiryInput(
    snapshotTimes = snapshots.mapNotNull { s -> s.metadata.id?.let { it to s.metadata.timeMillis } }.toMap(),
    consumerNext = consumers.associate { it.name to it.metadata.nextSnapshot },
    tagsBySnapshotId = tagNamesBySnapshotId,
    tableOptions = schemas.maxByOrNull { it.id ?: -1 }?.options.orEmpty(),
    changelogTimes = changelogs.mapNotNull { c -> c.metadata.id?.let { it to c.metadata.timeMillis } }.toMap(),
)

const val PAIMON_DEFAULT_RETAIN_MIN = 10
const val PAIMON_DEFAULT_EXPIRE_LIMIT = 50
const val PAIMON_DEFAULT_TIME_RETAINED_MS = 60L * 60 * 1000

fun PaimonExpiryInput.planExpiry(options: PaimonExpiryOptions): PaimonExpiryPlan {
    val retainMax = options.retainMax ?: tableOptions["snapshot.num-retained.max"]?.toIntOrNull() ?: Int.MAX_VALUE
    val retainMin = options.retainMin ?: tableOptions["snapshot.num-retained.min"]?.toIntOrNull() ?: PAIMON_DEFAULT_RETAIN_MIN
    val maxDeletes = options.maxDeletes ?: tableOptions["snapshot.expire.limit"]?.toIntOrNull() ?: PAIMON_DEFAULT_EXPIRE_LIMIT
    val timeRetainedMs = tableOptions["snapshot.time-retained"]?.let { parsePaimonDurationMs(it) } ?: PAIMON_DEFAULT_TIME_RETAINED_MS
    val cutoffMs = options.olderThanMs ?: (options.nowMs - timeRetainedMs)
    require(retainMax >= retainMin) { "retainMax ($retainMax) must not be less than retainMin ($retainMin)" }

    val ids = snapshotTimes.keys.sorted()
    val earliest = ids.firstOrNull()
    val latest = ids.lastOrNull()
    if (earliest == null || latest == null) {
        return PaimonExpiryPlan(options, retainMax, retainMin, maxDeletes, cutoffMs, null, null, emptyList())
    }

    // Long arithmetic on purpose: latest - Int.MAX_VALUE + 1 must go negative, not wrap.
    val min = maxOf(latest - retainMax + 1, earliest)
    val minRetainBound = latest - retainMin + 1
    val consumerBound = consumerNext.values.filterNotNull().minOrNull() ?: Long.MAX_VALUE
    val limitBound = earliest + maxDeletes
    val maxExclusive = minOf(minRetainBound, consumerBound, limitBound)
    // The walk is over ids, not over what exists, as Paimon's is; a gap in `snapshot/` counts as
    // a snapshot that cannot be young.
    val stopAt = (min until maxExclusive).firstOrNull { id ->
        snapshotTimes[id]?.let { it >= cutoffMs } == true
    }
    val end = stopAt ?: maxExclusive

    val verdicts = ids.map { id ->
        val retained = id >= end
        val keptBy = if (!retained) emptyList() else buildList {
            if (id >= minRetainBound) add(PaimonKeep(PaimonKeepRule.RETAIN_MIN))
            val holding = consumerNext.filterValues { it != null && id >= it }.keys.sorted()
            if (holding.isNotEmpty()) add(PaimonKeep(PaimonKeepRule.CONSUMER, holding))
            if (id >= limitBound) add(PaimonKeep(PaimonKeepRule.EXPIRE_LIMIT))
            if (stopAt != null) {
                if (id == stopAt) add(PaimonKeep(PaimonKeepRule.YOUNGER_THAN_CUTOFF))
                else if (id > stopAt && id < maxExclusive) add(PaimonKeep(PaimonKeepRule.BEHIND_STOP))
            }
        }
        PaimonSnapshotExpiryVerdict(id, retained, keptBy, tagsBySnapshotId[id].orEmpty())
    }
    return PaimonExpiryPlan(options, retainMax, retainMin, maxDeletes, cutoffMs, earliest, latest, verdicts)
}

data class PaimonChangelogExpiryPlan(
    val options: PaimonExpiryOptions,
    /** What the run would use: `changelog.num-retained.max` / `.min` and `changelog.time-retained`, each falling back to the snapshot setting; `snapshot.expire.limit`. */
    val retainMax: Int,
    val retainMin: Int,
    val maxDeletes: Int,
    val cutoffMs: Long,
    /** `changelog/`'s first and last, or null on a table with none. */
    val earliestId: Long?,
    val latestId: Long?,
    /** The floor `latestSnapshotId - retainMax + 1`: every changelog below it goes whatever its age, since the live snapshots count toward the maximum. */
    val floor: Long?,
    val changelogs: List<PaimonSnapshotExpiryVerdict>,
) {
    val removed: List<PaimonSnapshotExpiryVerdict> get() = changelogs.filter { !it.retained }
}

/**
 * What `expire_changelogs` would remove from `changelog/` — `ExpireChangelogImpl.expire()` at
 * release-1.3.1, which is the snapshot expiry's walk over the long-lived changelogs with one
 * difference that matters: **the counts are against the latest snapshot id**, so the live
 * snapshots count toward `changelog.num-retained.max` and `.min`. The floor is
 * `latestSnapshotId - retainMax + 1` (or the earliest changelog); the end is the least of
 * `latestSnapshotId - retainMin + 1`, a consumer's `nextSnapshot`, `earliest + snapshot.expire.limit`
 * and the latest changelog; from the floor up, the first changelog that exists and is younger than
 * `changelog.time-retained` stops the run there, else it runs to the end — and everything from
 * the earliest changelog below where it stops goes, the floor included, whatever its age. It runs
 * at commit time like the snapshot expiry (`TableCommitImpl.expire`), which is how `pcl` came to
 * hold changelogs 5 and 6 under a maximum of 4 with snapshots 7 and 8 live. Spark 3.5 has no
 * procedure for it; Flink's `expire_changelogs` action is the only call.
 */
fun PaimonExpiryInput.planChangelogExpiry(options: PaimonExpiryOptions): PaimonChangelogExpiryPlan {
    val snapshotMax = tableOptions["snapshot.num-retained.max"]?.toIntOrNull() ?: Int.MAX_VALUE
    val snapshotMin = tableOptions["snapshot.num-retained.min"]?.toIntOrNull() ?: PAIMON_DEFAULT_RETAIN_MIN
    val snapshotTime = tableOptions["snapshot.time-retained"]?.let { parsePaimonDurationMs(it) } ?: PAIMON_DEFAULT_TIME_RETAINED_MS
    val retainMax = options.retainMax ?: tableOptions["changelog.num-retained.max"]?.toIntOrNull() ?: snapshotMax
    val retainMin = options.retainMin ?: tableOptions["changelog.num-retained.min"]?.toIntOrNull() ?: snapshotMin
    val maxDeletes = options.maxDeletes ?: tableOptions["snapshot.expire.limit"]?.toIntOrNull() ?: PAIMON_DEFAULT_EXPIRE_LIMIT
    val timeRetainedMs = tableOptions["changelog.time-retained"]?.let { parsePaimonDurationMs(it) } ?: snapshotTime
    val cutoffMs = options.olderThanMs ?: (options.nowMs - timeRetainedMs)
    require(retainMax >= retainMin) { "retainMax ($retainMax) must not be less than retainMin ($retainMin)" }

    val ids = changelogTimes.keys.sorted()
    val earliest = ids.firstOrNull()
    val latest = ids.lastOrNull()
    val latestSnapshot = snapshotTimes.keys.maxOrNull()
    if (earliest == null || latest == null || latestSnapshot == null) {
        return PaimonChangelogExpiryPlan(options, retainMax, retainMin, maxDeletes, cutoffMs, earliest, latest, null, ids.map { PaimonSnapshotExpiryVerdict(it, true, emptyList(), emptyList()) })
    }
    val floor = maxOf(latestSnapshot - retainMax + 1, earliest)
    val minRetainBound = latestSnapshot - retainMin + 1
    val consumerBound = consumerNext.values.filterNotNull().minOrNull() ?: Long.MAX_VALUE
    val limitBound = earliest + maxDeletes
    val maxExclusive = minOf(minRetainBound, consumerBound, limitBound, latest)
    // `for (id = min; id <= maxExclusive; id++)`: the walk is inclusive of the end, over ids that exist.
    val stopAt = (floor..maxExclusive).firstOrNull { id -> changelogTimes[id]?.let { it >= cutoffMs } == true }
    val end = stopAt ?: maxExclusive

    val verdicts = ids.map { id ->
        val retained = id >= end
        val keptBy = if (!retained) emptyList() else buildList {
            if (id >= minRetainBound) add(PaimonKeep(PaimonKeepRule.RETAIN_MIN))
            val holding = consumerNext.filterValues { it != null && id >= it }.keys.sorted()
            if (holding.isNotEmpty()) add(PaimonKeep(PaimonKeepRule.CONSUMER, holding))
            if (id >= limitBound) add(PaimonKeep(PaimonKeepRule.EXPIRE_LIMIT))
            if (stopAt != null) {
                if (id == stopAt) add(PaimonKeep(PaimonKeepRule.YOUNGER_THAN_CUTOFF))
                else if (id > stopAt && id <= maxExclusive) add(PaimonKeep(PaimonKeepRule.BEHIND_STOP))
            }
        }
        PaimonSnapshotExpiryVerdict(id, retained, keptBy, tagsBySnapshotId[id].orEmpty())
    }
    return PaimonChangelogExpiryPlan(options, retainMax, retainMin, maxDeletes, cutoffMs, earliest, latest, floor, verdicts)
}

/**
 * `CoreOptions.changelogLifecycleDecoupled` at release-1.3.1: the changelog outlives the snapshots
 * when `changelog.num-retained.max`, `changelog.num-retained.min` or `changelog.time-retained` —
 * each defaulting to the snapshot setting — is above it. Derived, so a table never states it.
 */
fun paimonChangelogLifecycleDecoupled(options: Map<String, String>): Boolean {
    val snapshotMin = options["snapshot.num-retained.min"]?.toIntOrNull() ?: PAIMON_DEFAULT_RETAIN_MIN
    val snapshotMax = options["snapshot.num-retained.max"]?.toIntOrNull() ?: Int.MAX_VALUE
    val snapshotTime = options["snapshot.time-retained"]?.let { parsePaimonDurationMs(it) } ?: PAIMON_DEFAULT_TIME_RETAINED_MS
    val changelogMin = options["changelog.num-retained.min"]?.toIntOrNull() ?: snapshotMin
    val changelogMax = options["changelog.num-retained.max"]?.toIntOrNull() ?: snapshotMax
    val changelogTime = options["changelog.time-retained"]?.let { parsePaimonDurationMs(it) } ?: snapshotTime
    return changelogMax > snapshotMax || changelogTime > snapshotTime || changelogMin > snapshotMin
}

/**
 * Paimon's `TimeUtils.parseDuration`: digits, then an optional unit label — `ms`, `s`, `min`/`m`,
 * `h`, `d` and their long forms, plural or not; no label is milliseconds. Null for anything it
 * would reject, so a mistyped option falls back to the default rather than to a wrong number.
 */
fun parsePaimonDurationMs(text: String): Long? {
    val trimmed = text.trim()
    val digits = trimmed.takeWhile { it.isDigit() }
    val value = digits.toLongOrNull() ?: return null
    val label = trimmed.substring(digits.length).trim().lowercase(Locale.US)
    val unitMs = when (label) {
        "", "ms", "milli", "millis", "millisecond", "milliseconds" -> 1L
        "s", "sec", "secs", "second", "seconds" -> 1_000L
        "m", "min", "minute", "minutes" -> 60_000L
        "h", "hour", "hours" -> 3_600_000L
        "d", "day", "days" -> 86_400_000L
        else -> return null
    }
    return value * unitMs
}
