package model

import service.TableFormat
import java.util.concurrent.TimeUnit

/**
 * What a `remove_orphan_files` call would delete now, over what [findUnreferencedFiles] found.
 *
 * Both procedures decide a file by two things and nothing else: whether they list its directory
 * at all, and whether it was modified before `older_than`. Which directories are listed is the
 * format's rule and is decided by the walk ([UnreferencedFile.unlistedBecause]); the age is
 * decided here, against a cutoff that defaults to what each procedure defaults to — Iceberg's
 * `DeleteOrphanFilesSparkAction` takes `now - 3 days` at 1.8.1, Paimon's `OrphanFilesClean` `now -
 * 1 day` at release-1.3.1 — because the question a reader brings is "why did the call I ran
 * delete nothing", and the answer is almost always that the files are younger than the cutoff.
 * The other answer, on Iceberg, is the opposite: the walk reports nothing and the call deletes
 * files, because the procedure reaches from the current metadata only
 * ([UnreferencedFilesReport.unreachedFromCurrent]); those rows are planned too and say so.
 *
 * Neither guard on the argument is planned around, only stated: Iceberg's procedure refuses an
 * `older_than` inside the last 24 hours (`validateInterval`, skipped under `spark.testing`), and
 * Paimon's refuses one in the future ("The arg olderThan must be less than now").
 */
enum class OrphanFate {
    /** Listed, and modified before the cutoff. */
    REMOVED,

    /** Listed, but modified at or after the cutoff — kept as possibly still being written. */
    TOO_YOUNG,

    /** In a place the procedure never lists, whatever its age. */
    UNLISTED,
}

data class OrphanRemovalRow(
    val file: UnreferencedFile,
    val relativePath: String,
    val fate: OrphanFate,
    val reason: String,
    /** Iceberg: the walk counts this file referenced, and the procedure does not reach it. */
    val namedByOlderVersionOnly: Boolean,
)

data class OrphanRemovalPlan(
    val format: TableFormat,
    val nowMs: Long,
    val cutoffMs: Long,
    /** Whether [cutoffMs] is the procedure's own default rather than a typed `older_than`. */
    val defaultCutoff: Boolean,
    val rows: List<OrphanRemovalRow>,
) {
    val removed: List<OrphanRemovalRow> get() = rows.filter { it.fate == OrphanFate.REMOVED }
    val removedBytes: Long get() = removed.sumOf { it.file.sizeBytes }
    val tooYoung: Int get() = rows.count { it.fate == OrphanFate.TOO_YOUNG }
    val unlisted: Int get() = rows.count { it.fate == OrphanFate.UNLISTED }

    /** The default interval, as the procedure's documentation states it. */
    val defaultIntervalText: String
        get() = when (format) {
            TableFormat.ICEBERG -> "3 days"
            else -> "1 day"
        }
}

/** Iceberg's `remove_orphan_files` default: `System.currentTimeMillis() - TimeUnit.DAYS.toMillis(3)`. */
val ICEBERG_ORPHAN_INTERVAL_MS: Long = TimeUnit.DAYS.toMillis(3)

/** Paimon's `remove_orphan_files` default: `System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)`. */
val PAIMON_ORPHAN_INTERVAL_MS: Long = TimeUnit.DAYS.toMillis(1)

fun planOrphanRemoval(report: UnreferencedFilesReport, nowMs: Long, olderThanMs: Long? = null): OrphanRemovalPlan {
    val interval = if (report.format == TableFormat.ICEBERG) ICEBERG_ORPHAN_INTERVAL_MS else PAIMON_ORPHAN_INTERVAL_MS
    val cutoff = olderThanMs ?: (nowMs - interval)
    fun rowOf(file: UnreferencedFile, olderVersionOnly: Boolean): OrphanRemovalRow {
        val unlisted = file.unlistedBecause
        val (fate, reason) = when {
            unlisted != null -> OrphanFate.UNLISTED to unlisted
            file.modifiedMs >= cutoff -> OrphanFate.TOO_YOUNG to
                "modified ${formatOrphanAge(nowMs - file.modifiedMs)} ago, at or after the cutoff — kept as possibly still being written"
            else -> OrphanFate.REMOVED to "listed, and modified ${formatOrphanAge(nowMs - file.modifiedMs)} ago, before the cutoff"
        }
        val why = if (olderVersionOnly) {
            "named only by an older metadata version or a DELETED entry, which the procedure does not read; $reason"
        } else {
            reason
        }
        return OrphanRemovalRow(file, report.relativePathOf(file), fate, why, olderVersionOnly)
    }
    val rows = report.unreferenced.map { rowOf(it, false) } + report.unreachedFromCurrent.map { rowOf(it, true) }
    return OrphanRemovalPlan(report.format, nowMs, cutoff, olderThanMs == null, rows)
}

/** An age in the largest unit that keeps a whole number: `3 d`, `5 h`, `12 min`, `40 s`. */
fun formatOrphanAge(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return when {
        s >= 86_400 -> "${s / 86_400} d"
        s >= 3_600 -> "${s / 3_600} h"
        s >= 60 -> "${s / 60} min"
        else -> "$s s"
    }
}
