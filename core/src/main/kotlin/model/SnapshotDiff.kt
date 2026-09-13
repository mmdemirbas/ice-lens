package model

/**
 * One file as it stands at a snapshot — enough to say what it is and what it costs.
 *
 * Deliberately not [ChangedFile]. That type answers "what did this commit do" and carries the
 * manifest the commit wrote to say it; this one answers "what is in the table here", where no
 * commit is in the picture and the manifest that happens to list the file is an accident of how
 * many commits have carried it forward.
 */
data class LiveFile(
    val path: String,
    val content: Int,
    val recordCount: Long,
    val sizeBytes: Long,
    /** What a snapshot summary charges the file at — see [EntryContribution.chargedSizeBytes]. */
    val chargedSizeBytes: Long = sizeBytes,
    /**
     * A Paimon data-evolution patch file: it holds some columns of rows another live file
     * already holds (`_WRITE_COLS` set), so its [recordCount] adds no rows to the table.
     * Always false for Iceberg, which has no such file.
     */
    val partial: Boolean = false,
)

/**
 * Which side of a comparison a file is on.
 *
 * `CHANGED` is a file present on both sides under one path whose recorded figures differ. It
 * should not happen — an Iceberg data file is immutable and its path is unique — so it is reported
 * rather than folded into `IN_BOTH`, on the same principle as `unattributedManifests`: a state the
 * format says is impossible is exactly the state a reader needs told about rather than smoothed
 * over.
 */
enum class DiffSide { ONLY_IN_FROM, ONLY_IN_TO, IN_BOTH, CHANGED }

/** One file, and where it sits across the two snapshots. */
data class DiffFile(val file: LiveFile, val side: DiffSide, val otherSide: LiveFile? = null)

/**
 * What is different between the contents of two snapshots, in either direction and at any distance.
 *
 * [SnapshotChange] answers a different question and neither replaces the other. It reads the
 * manifests *one commit wrote*, which is what a reader arriving at a snapshot wants — and it is
 * only defined against that commit's parent. This is a set difference between two complete live
 * file sets, so the two snapshots need no relationship at all: a branch tip against `main`, a
 * snapshot against one ten commits back, or two snapshots on different forks.
 *
 * **Both sides are the live contents, not a replay of the commits between them.** Folding the
 * changes along a lineage path would be cheaper and would go wrong in two ways that matter here:
 * there may be no path (a fork needs the common ancestor found first), and a snapshot on the path
 * may be missing from the drawn graph because aggregation folded it into a group. A set difference
 * of two closures cannot be wrong about either.
 *
 * [added] and [removed] are named from [fromId] to [toId]. Comparing a snapshot against a later
 * one and against an earlier one are the same call with the arguments swapped, and the words then
 * mean what they say.
 */
data class SnapshotDiff(
    val fromId: Long?,
    val toId: Long?,
    val files: List<DiffFile>,
) {
    val removed: List<DiffFile> get() = files.filter { it.side == DiffSide.ONLY_IN_FROM }
    val added: List<DiffFile> get() = files.filter { it.side == DiffSide.ONLY_IN_TO }
    val unchanged: List<DiffFile> get() = files.filter { it.side == DiffSide.IN_BOTH }

    /** Files listed under one path on both sides with different figures — see [DiffSide.CHANGED]. */
    val contradictory: List<DiffFile> get() = files.filter { it.side == DiffSide.CHANGED }

    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty() && contradictory.isEmpty()

    /**
     * The figures [toId] has that [fromId] does not, and the other way round.
     *
     * Folded from [files] rather than accumulated beside it, the same rule as `StatsDerivation`: a
     * total computed separately from the rows explaining it is a second implementation of the
     * number, and two implementations drift.
     */
    val addedStats: ContentStats get() = added.fold(ContentStats()) { running, it -> running + it.file.asStats() }
    val removedStats: ContentStats get() = removed.fold(ContentStats()) { running, it -> running + it.file.asStats() }

    /** What the table gained on net. Negative components are normal — a compaction removes more than it adds. */
    val netRecordCount: Long get() = addedStats.recordCount - removedStats.recordCount
    val netSizeBytes: Long get() = addedStats.totalSizeBytes - removedStats.totalSizeBytes
    val netDataFileCount: Int get() = addedStats.dataFileCount - removedStats.dataFileCount
}

/** One file's contribution, using the same content rules the table's own figures use. */
private fun LiveFile.asStats(): ContentStats = when (content) {
    DataFileContent.POSITION_DELETES ->
        ContentStats(posDeleteFileCount = 1, deleteRecordCount = recordCount, deleteSizeBytes = sizeBytes)
    DataFileContent.EQUALITY_DELETES ->
        ContentStats(eqDeleteFileCount = 1, deleteRecordCount = recordCount, deleteSizeBytes = sizeBytes)
    else -> ContentStats(dataFileCount = 1, recordCount = recordCount, dataSizeBytes = sizeBytes)
}

/**
 * Every file the table holds at this snapshot, deduplicated, live entries only.
 *
 * Folded through [manifestLedger] with one shared `seenFileKeys` across the whole closure — the
 * same call the table's `current` figures are folded from, so "what is in the table here" and
 * "how many files does the table have" cannot disagree. A manifest is carried forward by every
 * snapshot that still needs its files, so one file is listed by exactly one manifest inside a
 * closure and the deduplication is what makes that true rather than assumed.
 *
 * A `DELETED` entry contributes nothing, which is what "live" means: the entry records that a
 * commit took the file out.
 */
fun liveFilesOf(snapshot: UnifiedSnapshot): List<LiveFile> {
    val seen = mutableSetOf<String>()
    return snapshot.manifests.flatMap { manifest ->
        manifestLedger(
            entries = manifest.dataFiles.map { unified ->
                val recorded = unified.metadata.dataFile?.filePath?.takeIf { it.isNotBlank() }
                LedgerEntry(
                    fileKey = recorded?.let(::normalizeFilePath) ?: "path:${unified.path}",
                    status = unified.metadata.status,
                    dataFile = unified.metadata.dataFile,
                )
            },
            liveEntriesOnly = true,
            seenFileKeys = seen,
        ).filter { it.fate == EntryFate.COUNTED }
            .map { entry ->
                LiveFile(
                    path = entry.filePath,
                    content = entry.content,
                    recordCount = entry.recordCount,
                    sizeBytes = entry.sizeBytes,
                    chargedSizeBytes = entry.chargedSizeBytes,
                )
            }
    }
}

/**
 * The set difference between two live file sets, keyed by normalised path.
 *
 * Path is the key because it is what Iceberg guarantees unique within a table and what a reader
 * recognises. Two files with the same path and different figures is the contradictory case
 * [DiffSide.CHANGED] exists for — not silently merged, and not silently counted twice.
 *
 * The order is stable and chosen for reading: what left, then what arrived, then what stayed,
 * each by path. A diff whose rows move between runs cannot be compared against a previous one.
 */
fun snapshotDiff(fromId: Long?, from: List<LiveFile>, toId: Long?, to: List<LiveFile>): SnapshotDiff {
    val fromByPath = from.associateBy { normalizeFilePath(it.path) }
    val toByPath = to.associateBy { normalizeFilePath(it.path) }

    val files = (fromByPath.keys + toByPath.keys).sorted().map { key ->
        val before = fromByPath[key]
        val after = toByPath[key]
        when {
            before == null && after != null -> DiffFile(after, DiffSide.ONLY_IN_TO)
            before != null && after == null -> DiffFile(before, DiffSide.ONLY_IN_FROM)
            before != null && after != null && before == after -> DiffFile(after, DiffSide.IN_BOTH)
            // Both present and different. Reported from the `to` side with the `from` side beside
            // it, because the panel reads left to right from `from` to `to`.
            else -> DiffFile(
                requireNotNull(after) { "a key came from one of the two maps" },
                DiffSide.CHANGED,
                otherSide = before,
            )
        }
    }

    return SnapshotDiff(
        fromId = fromId,
        toId = toId,
        files = files.sortedBy { file ->
            when (file.side) {
                DiffSide.ONLY_IN_FROM -> 0
                DiffSide.ONLY_IN_TO -> 1
                DiffSide.CHANGED -> 2
                DiffSide.IN_BOTH -> 3
            }
        },
    )
}
