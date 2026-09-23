package model

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * What `VACUUM` would delete now, planned the way `VacuumCommand.gc` decides it at delta-spark
 * 3.2.1.
 *
 * **The cutoff** is `now - RETAIN` where one is given, else `now - delta.deletedFileRetentionDuration`
 * (a week by default). A `RETAIN` below the table's retention is refused unless
 * `spark.databricks.delta.retentionDurationCheck.enabled` is off ([DeltaVacuumPlan.refusal]); the
 * plan is still drawn as the call would run with the check off.
 *
 * **What is kept** is what the latest snapshot's state names: every live `add`, and every tombstone
 * — a `remove` the state still holds, which is one newer than the table's retention — whose
 * `deletionTimestamp` is not before the cutoff; each with the directories above it and, for a
 * vector stored relative to the table (`u`), the vector's file. A `cdc` action is never in the
 * state, so a change data file is kept by nothing and goes once it is old enough.
 *
 * **What is looked at** is the table directory listed recursively, skipping every file and
 * directory whose name `DeltaTableUtils.isHiddenDirectory` calls hidden: `metadata` (UniForm's), and
 * a name starting `_` or `.` other than `_delta_index`, `_change_data` or `<partition column>=`. So
 * `_delta_log/` is never touched — log cleanup is a checkpoint's work — and neither are the `.crc`
 * files beside the data files, **except** that on a local or HDFS table Hadoop's checksum
 * filesystem deletes a file's `.<name>.crc` together with it, which is why `dvaca` lost both
 * ([DeltaVacuumRow.companions]).
 *
 * **What goes** is every listed file modified before the cutoff that nothing keeps, and every
 * listed directory nothing keeps that no such file lies under — a directory counts once for itself
 * and once for each old file below it, and only a count of one is deleted, so a partition directory
 * emptied by this run goes on the next. A directory is deleted non-recursively, which fails on one
 * still holding something; it counts in `numFilesToDelete` all the same
 * ([VacuumFate.DIRECTORY_NOT_EMPTY]).
 *
 * The oracle is `dvac` / `dvaca`: the table before and after `VACUUM … RETAIN 0 HOURS`, whose
 * `VACUUM START` recorded `numFilesToDelete 6` and `sizeOfDataToDelete 3661`.
 */
enum class VacuumFate {
    /** Listed, older than the cutoff, and kept by nothing: deleted. */
    DELETED,

    /** A directory the call tries to delete and cannot, because something is still under it. */
    DIRECTORY_NOT_EMPTY,

    /** Kept by nothing, but modified at or after the cutoff. */
    TOO_YOUNG,

    /** Named by a live `add`, a tombstone within the cutoff, or as one's vector or directory. */
    KEPT,
}

data class DeltaVacuumRow(
    val path: Path,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val fate: VacuumFate,
    val reason: String,
    /** Hidden checksum files the filesystem deletes with this one — `.<name>.crc` beside it. */
    val companions: List<Path> = emptyList(),
)

data class DeltaVacuumPlan(
    /** The snapshot the call reads — the latest version. */
    val version: Long,
    val nowMs: Long,
    /** `delta.deletedFileRetentionDuration`. */
    val tableRetentionMs: Long,
    /** `RETAIN n HOURS`; null for the table's retention. */
    val specifiedRetentionMs: Long?,
    /** Files modified before this, and tombstones deleted before it, are past the retention. */
    val deleteBeforeMs: Long,
    /** Why the call is refused while the retention check is on; null where it runs. */
    val refusal: String?,
    /** Every listed file and directory, what is deleted first. */
    val rows: List<DeltaVacuumRow>,
    /** Where the listing could not go, in the filesystem's own words. */
    val problems: List<String> = emptyList(),
) {
    /** What the call deletes or tries to, which is what `numFilesToDelete` counts. */
    val toDelete: List<DeltaVacuumRow> get() = rows.filter { it.fate == VacuumFate.DELETED || it.fate == VacuumFate.DIRECTORY_NOT_EMPTY }
    val numFilesToDelete: Int get() = toDelete.size
    val sizeOfDataToDelete: Long get() = toDelete.sumOf { it.sizeBytes }

    /** What actually goes — a directory still holding something does not. */
    val deleted: List<DeltaVacuumRow> get() = rows.filter { it.fate == VacuumFate.DELETED }
    val tooYoung: Int get() = rows.count { it.fate == VacuumFate.TOO_YOUNG }
    val kept: Int get() = rows.count { it.fate == VacuumFate.KEPT }

    /** `numVacuumedDirectories`: every listed directory and the table's own. */
    val directoriesListed: Int get() = rows.count { it.isDirectory } + 1

    /** The call as it would be typed. */
    val call: String
        get() = "VACUUM" + (specifiedRetentionMs?.let { " RETAIN ${formatRetainHours(it)} HOURS" } ?: "")
}

/** `spark.databricks.delta.retentionDurationCheck.enabled`'s message, from `checkRetentionPeriodSafety`. */
private fun retentionRefusal(specifiedMs: Long, tableMs: Long): String? {
    if (specifiedMs >= tableMs) return null
    val hours = (tableMs + 3_600_000 - 1) / 3_600_000
    return "refused while spark.databricks.delta.retentionDurationCheck.enabled is true, its default: " +
        "\"Are you sure you would like to vacuum files with such a low retention period?\" — " +
        "a RETAIN below the table's ${formatRetention(tableMs)} needs \"a value not less than $hours hours\" or the check off"
}

private fun formatRetainHours(ms: Long): String =
    if (ms % 3_600_000 == 0L) (ms / 3_600_000).toString() else "%.3f".format(ms / 3_600_000.0).trimEnd('0').trimEnd('.')

/** A retention as the largest whole unit: `7 days`, `36 hours`, `0 hours`. */
fun formatRetention(ms: Long): String = when {
    ms > 0 && ms % 86_400_000 == 0L -> formatCounted((ms / 86_400_000).toInt(), "day")
    ms % 3_600_000 == 0L -> formatCounted((ms / 3_600_000).toInt(), "hour")
    else -> formatCounted((ms / 60_000).toInt(), "minute")
}

/**
 * The plan at [nowMs] with `RETAIN` [retainMs] (null for the table's retention). Fails where the
 * latest version cannot be rebuilt or the table's retention is not a value Delta accepts.
 */
fun DeltaUnifiedTableModel.planVacuum(nowMs: Long, retainMs: Long? = null): Result<DeltaVacuumPlan> = runCatching {
    val version = requireNotNull(latestVersion) { "the log holds no version" }
    val state = stateAt(version).getOrThrow()
    val metadata = requireNotNull(state.metadata) { "version $version has no metadata" }
    val tableRetention = requireNotNull(metadata.tombstoneRetentionMs) {
        "delta.deletedFileRetentionDuration = ${metadata.configuration["delta.deletedFileRetentionDuration"]} is not an interval Delta accepts"
    }
    require(retainMs == null || retainMs >= 0) { "Retention for Vacuum can't be less than 0." }
    val deleteBefore = nowMs - (retainMs ?: tableRetention)
    val stateCutoff = nowMs - tableRetention
    val root = path.toAbsolutePath().normalize()

    // What keeps a path, and for the files nothing keeps, what named them last.
    val keptBy = linkedMapOf<Path, String>()
    val tombstoneOf = mutableMapOf<Path, DeltaRemoveFile>()
    fun keep(recorded: String, vector: DeltaDeletionVector?, why: String) {
        val file = runCatching { resolve(recorded).toAbsolutePath().normalize() }.getOrNull() ?: return
        if (file.startsWith(root)) {
            keptBy.putIfAbsent(file, why)
            var dir = file.parent
            while (dir != null && dir != root && dir.startsWith(root)) {
                keptBy.putIfAbsent(dir, "a directory above ${root.relativize(file)}, which is kept")
                dir = dir.parent
            }
        }
        if (vector?.storageType == "u") {
            vector.filePath(path)?.toAbsolutePath()?.normalize()?.let { keptBy.putIfAbsent(it, "the deletion vector of ${recorded.substringAfterLast('/')}, which is kept") }
        }
    }
    state.files.values.forEach { keep(it.path, it.deletionVector, "live at version $version") }
    for (remove in state.tombstones.values) {
        val deletedAt = remove.deletionTimestamp ?: 0L
        runCatching { resolve(remove.path).toAbsolutePath().normalize() }.getOrNull()?.let { tombstoneOf[it] = remove }
        if (deletedAt > stateCutoff && deletedAt >= deleteBefore) {
            keep(remove.path, remove.deletionVector, "removed ${formatOrphanAge(nowMs - deletedAt)} ago, a tombstone within the retention")
        }
    }
    val changeFiles = commits.flatMap { c -> c.cdcs.mapNotNull { runCatching { resolve(it.path).toAbsolutePath().normalize() }.getOrNull() } }.toSet()

    val problems = mutableListOf<String>()
    val listed = listForVacuum(root, metadata.partitionColumns, problems)
    val oldFiles = listed.filter { !it.isDirectory && it.modifiedMs < deleteBefore }
    val oldFileCounts = mutableMapOf<Path, Int>()
    oldFiles.forEach { f ->
        var dir = f.path.parent
        while (dir != null && dir != root && dir.startsWith(root)) {
            oldFileCounts.merge(dir, 1, Int::plus)
            dir = dir.parent
        }
    }
    fun unkeptReason(file: Path): String {
        val tombstone = tombstoneOf[file]
        return when {
            tombstone != null -> {
                val at = tombstone.deletionTimestamp
                if (at == null) "named only by a tombstone recording no deletionTimestamp, read as 0"
                else "named only by a tombstone removed ${formatOrphanAge(nowMs - at)} ago, before the cutoff"
            }
            file in changeFiles -> "a change data file — a cdc action is never in the state, so nothing keeps one"
            else -> "named by nothing the state holds"
        }
    }
    val rows = listed.map { entry ->
        val rel = root.relativize(entry.path).toString()
        val kept = keptBy[entry.path]
        val (fate, reason) = when {
            kept != null -> VacuumFate.KEPT to kept
            entry.isDirectory -> {
                val old = oldFileCounts[entry.path] ?: 0
                when {
                    old > 0 -> VacuumFate.KEPT to "holds ${formatCounted(old, "file")} this call deletes — an emptied directory goes on the next call"
                    entry.emptyOnDisk -> VacuumFate.DELETED to "kept by nothing and empty"
                    else -> VacuumFate.DIRECTORY_NOT_EMPTY to "kept by nothing, but not empty — a non-recursive delete leaves it"
                }
            }
            entry.modifiedMs >= deleteBefore -> VacuumFate.TOO_YOUNG to "${unkeptReason(entry.path)}; modified ${formatOrphanAge(nowMs - entry.modifiedMs)} ago, at or after the cutoff"
            else -> VacuumFate.DELETED to "${unkeptReason(entry.path)}; modified ${formatOrphanAge(nowMs - entry.modifiedMs)} ago"
        }
        val companions = if (fate == VacuumFate.DELETED && !entry.isDirectory) {
            listOf(entry.path.resolveSibling(".${entry.path.fileName}.crc")).filter { Files.exists(it) }
        } else emptyList()
        DeltaVacuumRow(entry.path, rel, entry.isDirectory, entry.sizeBytes, entry.modifiedMs, fate, reason, companions)
    }.sortedWith(compareBy<DeltaVacuumRow> { it.fate.ordinal }.thenBy { it.relativePath })

    DeltaVacuumPlan(
        version = version,
        nowMs = nowMs,
        tableRetentionMs = tableRetention,
        specifiedRetentionMs = retainMs,
        deleteBeforeMs = deleteBefore,
        refusal = retainMs?.let { retentionRefusal(it, tableRetention) },
        rows = rows,
        problems = problems,
    )
}

private data class VacuumListing(val path: Path, val isDirectory: Boolean, val sizeBytes: Long, val modifiedMs: Long, val emptyOnDisk: Boolean)

/** `DeltaTableUtils.isHiddenDirectory`, asked of every file and directory name. */
internal fun isDeltaHiddenName(name: String, partitionColumns: List<String>): Boolean =
    name == "metadata" ||
        (name.startsWith(".") || name.startsWith("_")) &&
        !name.startsWith("_delta_index") && !name.startsWith("_change_data") &&
        partitionColumns.none { name.startsWith("$it=") }

/** The table directory as `DeltaFileOperations.recursiveListDirs` lists it for VACUUM: hidden names skipped, directories included. */
private fun listForVacuum(root: Path, partitionColumns: List<String>, problems: MutableList<String>): List<VacuumListing> {
    val out = mutableListOf<VacuumListing>()
    if (!Files.isDirectory(root)) return out
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (dir == root) return FileVisitResult.CONTINUE
            if (isDeltaHiddenName(dir.fileName.toString(), partitionColumns)) return FileVisitResult.SKIP_SUBTREE
            val empty = runCatching { Files.list(dir).use { !it.findFirst().isPresent } }.getOrDefault(false)
            out += VacuumListing(dir.toAbsolutePath().normalize(), true, 0L, attrs.lastModifiedTime().toMillis(), empty)
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (attrs.isRegularFile && !isDeltaHiddenName(file.fileName.toString(), partitionColumns)) {
                out += VacuumListing(file.toAbsolutePath().normalize(), false, attrs.size(), attrs.lastModifiedTime().toMillis(), false)
            }
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
            problems += "${root.relativize(file)}: ${exc.message ?: exc::class.simpleName}"
            return FileVisitResult.CONTINUE
        }
    })
    return out
}
