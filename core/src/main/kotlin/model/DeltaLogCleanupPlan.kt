package model

import java.nio.file.Files
import java.nio.file.Path
import java.util.Calendar
import java.util.TimeZone

/**
 * What the log cleanup a checkpoint runs would delete from `_delta_log/`, planned the way
 * `MetadataCleanup.cleanUpExpiredLogs` does it at delta-spark 3.2.1.
 *
 * It runs after a checkpoint is written, under `delta.enableExpiredLogCleanup` (on by default),
 * and never on its own. **What is looked at** is every commit file (`<v>.json`) and checkpoint file
 * of any naming, in name order; `<v>.crc`, a log compaction and `_last_checkpoint` are not. **What
 * goes** is a file whose version is below the checkpoint `_last_checkpoint` names and whose
 * modification time is at or before the cutoff — `now - delta.logRetentionDuration` (30 days),
 * **truncated to the previous UTC midnight** — so a table keeps up to a day more than its
 * retention, and a file dated in the future is never old enough.
 *
 * **Modification times are made increasing first** (`BufferingLogDeletionIterator`): a file whose
 * time is not after the previous file's, at a higher version, is treated as one millisecond after
 * it, and a run of such files is decided together by its **last** file — all go or none do. So a
 * file old enough itself stays when the run it belongs to ends in one that is not, which is how a
 * clock that went backwards cannot take a commit whose successor is still needed.
 *
 * On a table with V2 checkpoints, a run that deleted a checkpoint then deletes each sidecar under
 * `_delta_log/_sidecars/` modified before the cutoff that no checkpoint still on disk names, and a
 * run that deletes anything first writes a classic single-file checkpoint of the snapshot for
 * readers that cannot read V2 ([DeltaLogCleanupPlan.writesCompatCheckpoint]).
 *
 * A file's modification time does not survive a copy or a checkout, so it is asked of [modifiedMs]
 * — the filesystem by default. The oracle is `dlcb` / `dlc`: the table before and after the
 * cleanup the checkpoint at 6 ran, with versions 0 to 4 dated 2020 by the script.
 */
enum class LogCleanupFate {
    DELETED,

    /** At or above the checkpoint: the cleanup never deletes what reading from that checkpoint needs. */
    KEPT_AT_CHECKPOINT,

    /** Modified after the cutoff. */
    KEPT_YOUNG,

    /** Old enough and below the checkpoint, but its run ends in a file that is not. */
    KEPT_WITH_ITS_RUN,

    /** The cleanup is switched off. */
    KEPT_DISABLED,
}

data class DeltaLogCleanupRow(
    val path: Path,
    val version: Long,
    val isCheckpoint: Boolean,
    val modifiedMs: Long?,
    /** The time the cleanup judged it by, where the order rule moved it. */
    val adjustedMs: Long?,
    val fate: LogCleanupFate,
    val reason: String,
)

data class DeltaLogCleanupPlan(
    val nowMs: Long,
    val enabled: Boolean,
    /** The checkpoint the cleanup runs after; nothing at or above it is deleted. */
    val checkpointVersion: Long,
    val retentionMs: Long,
    /** `now - retention`, truncated to UTC midnight. */
    val cutoffMs: Long,
    /** Every commit and checkpoint file, in name order. */
    val rows: List<DeltaLogCleanupRow>,
    /** Sidecars the run deletes after deleting a checkpoint. */
    val sidecarsDeleted: List<Path>,
    val writesCompatCheckpoint: Boolean,
) {
    val deleted: List<DeltaLogCleanupRow> get() = rows.filter { it.fate == LogCleanupFate.DELETED }

    /**
     * The earliest version a reader can still rebuild after the run: version 0 while its commit is
     * left, else the oldest checkpoint left — the one the run follows among them.
     */
    val earliestReadableAfter: Long
        get() {
            val left = rows.filter { it.fate != LogCleanupFate.DELETED }
            val fromZero = if (left.any { !it.isCheckpoint && it.version == 0L }) 0L else null
            return listOfNotNull(fromZero, left.filter { it.isCheckpoint }.minOfOrNull { it.version }, checkpointVersion).min()
        }
}

/** `DateUtils.truncate` to the day, in UTC — the cutoff's rounding. */
fun truncateToUtcDay(ms: Long): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun statModifiedMs(path: Path): Long? = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrNull()

/**
 * The cleanup run after a checkpoint at [checkpointVersion] — `_last_checkpoint`'s by default,
 * which is the run the latest checkpoint made, or would make now if it ran again. A version past
 * the latest plans the run the next checkpoint makes, with the commits before it written now.
 */
fun DeltaUnifiedTableModel.planLogCleanup(
    nowMs: Long,
    checkpointVersion: Long? = lastCheckpoint?.version,
    modifiedMs: (Path) -> Long? = ::statModifiedMs,
): Result<DeltaLogCleanupPlan> = runCatching {
    val version = requireNotNull(checkpointVersion) { "no _last_checkpoint: the cleanup runs only after a checkpoint" }
    val latest = requireNotNull(latestVersion) { "the log holds no version" }
    val metadata = requireNotNull(current?.metadata) { "the latest version has no metadata" }
    val retention = requireNotNull(metadata.logRetentionMs) {
        "delta.logRetentionDuration = ${metadata.configuration["delta.logRetentionDuration"]} is not an interval Delta accepts"
    }
    val enabled = !metadata.configuration["delta.enableExpiredLogCleanup"].equals("false", ignoreCase = true)
    val cutoff = truncateToUtcDay(nowMs - retention)
    val threshold = version - 1

    data class Entry(val path: Path, val version: Long, val checkpoint: Boolean, val modified: Long?, var judged: Long, val virtual: Boolean = false)
    val files = (listing.commits.map { (v, p) -> Triple(p, v, false) } + checkpoints.flatMap { cp -> cp.parts.map { Triple(it, cp.version, true) } })
        .sortedBy { it.first.fileName.toString() }
        .map { (p, v, cp) -> val m = modifiedMs(p); Entry(p, v, cp, m, m ?: Long.MAX_VALUE) }.toMutableList()
    // A run past the latest version: the commits up to it are written now, which ends any run of adjusted times.
    if (version > latest) files += Entry(listing.logDir.resolve("%020d.json".format(latest + 1)), latest + 1, false, nowMs, nowMs, virtual = true)

    val deleted = mutableSetOf<Path>()
    val runEnd = mutableMapOf<Path, Entry>()
    val buffer = mutableListOf<Entry>()
    fun deletable(e: Entry) = e.judged <= cutoff && e.version <= threshold
    fun flush() {
        val last = buffer.lastOrNull() ?: return
        if (deletable(last)) buffer.forEach { deleted.add(it.path) }
        buffer.forEach { runEnd[it.path] = last }
        buffer.clear()
    }
    var previous: Entry? = null
    for (e in files) {
        val prev = previous
        if (prev != null && prev.version < e.version && prev.judged >= e.judged) {
            e.judged = prev.judged + 1
        } else {
            flush()
        }
        buffer += e
        previous = e
    }
    flush()

    val rows = files.filter { !it.virtual }.map { e ->
        val adjusted = e.judged.takeIf { e.modified != null && it != e.modified }
        val (fate, reason) = when {
            !enabled -> LogCleanupFate.KEPT_DISABLED to "delta.enableExpiredLogCleanup is false"
            e.path in deleted -> LogCleanupFate.DELETED to "below the checkpoint at $version, modified at or before the cutoff" +
                (adjusted?.let { " (read as ${formatAppTimestampUtc(it)}, a millisecond after the file before it)" } ?: "")
            e.version > threshold -> LogCleanupFate.KEPT_AT_CHECKPOINT to "at or above the checkpoint at $version, which reading from it needs"
            e.modified == null -> LogCleanupFate.KEPT_YOUNG to "no modification time could be read"
            e.judged > cutoff -> LogCleanupFate.KEPT_YOUNG to "modified after the cutoff" +
                (adjusted?.let { " as read (${formatAppTimestampUtc(it)}, moved after the file before it)" } ?: "")
            else -> {
                val end = runEnd.getValue(e.path)
                LogCleanupFate.KEPT_WITH_ITS_RUN to "old enough, but its time is not after the file before it and the run ends at ${end.path.fileName}, which is not deleted"
            }
        }
        DeltaLogCleanupRow(e.path, e.version, e.checkpoint, e.modified, adjusted, fate, reason)
    }

    val v2 = "v2Checkpoint" in (current?.protocol?.features ?: emptyList())
    val checkpointDeleted = rows.any { it.isCheckpoint && it.fate == LogCleanupFate.DELETED }
    val sidecars = if (!v2 || !checkpointDeleted) emptyList() else {
        val dir = listing.logDir.resolve("_sidecars")
        val remaining = checkpoints.filter { cp -> cp.parts.none { it in deleted } }
        val active = remaining.flatMap { cp -> checkpointRead(cp)?.sidecars.orEmpty().map { it.first.fileName.toString() } }.toSet()
        val onDisk = runCatching { Files.list(dir).use { s -> s.filter { Files.isRegularFile(it) }.toList() } }.getOrDefault(emptyList())
        onDisk.filter { p -> p.fileName.toString() !in active && (modifiedMs(p) ?: Long.MAX_VALUE) < cutoff }.sortedBy { it.fileName.toString() }
    }
    DeltaLogCleanupPlan(nowMs, enabled, version, retention, cutoff, rows, sidecars, writesCompatCheckpoint = v2 && deleted.isNotEmpty() && enabled)
}

/** The next version a checkpoint is written at: the first after [latest] that `delta.checkpointInterval` (10) divides. */
fun DeltaUnifiedTableModel.nextCheckpointVersion(): Long? {
    val latest = latestVersion ?: return null
    val interval = current?.metadata?.configuration?.get("delta.checkpointInterval")?.toLongOrNull()?.takeIf { it > 0 } ?: 10L
    return (latest / interval + 1) * interval
}

private fun formatAppTimestampUtc(ms: Long): String = java.time.Instant.ofEpochMilli(ms).toString()
