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
    /**
     * The partition the file is in, decoded, as `name=value` pairs — `""` for an unpartitioned
     * table, null where the tuple could not be decoded.
     */
    val partition: String? = null,
    /** The partition spec the file's manifest was written under; null for Paimon and where unrecorded. */
    val specId: Int? = null,
    /**
     * What the ledger told the file by — the path, with the referenced data file on a deletion
     * vector, whose Puffin container holds a blob per data file (`UnifiedDataFile.ledgerFileKey`).
     * The path on Paimon, whose files are one per name.
     */
    val key: String = path,
)

/** One partition's share of a snapshot, folded from its live files — see [partitionBreakdown]. */
data class PartitionShare(
    val partition: String,
    val dataFileCount: Int,
    val dataRecordCount: Long,
    val dataSizeBytes: Long,
    val posDeleteFileCount: Int,
    val posDeleteRecordCount: Long,
    val eqDeleteFileCount: Int,
    val eqDeleteRecordCount: Long,
) {
    val deleteFileCount: Int get() = posDeleteFileCount + eqDeleteFileCount
    val deleteRecordCount: Long get() = posDeleteRecordCount + eqDeleteRecordCount
    /** `dataFileCount` including delete files: what a scan of this partition opens. */
    val fileCount: Int get() = dataFileCount + deleteFileCount
}

/**
 * The live files grouped by partition, largest data first — the question Iceberg's `.partitions`
 * metadata table answers, folded here from the same [LiveFile] set the comparison and the
 * table's `current` figures come from, so it cannot disagree with them. A Paimon patch file
 * (see [LiveFile.partial]) is a file in its partition and its rows are excluded, the same
 * reading as [partialRows]. Files whose partition could not be decoded are grouped under
 * [UNDECODED_PARTITION] rather than dropped, since a partition that silently loses its files
 * is the one kind of wrong this section must not be.
 */
fun List<LiveFile>.partitionBreakdown(): List<PartitionShare> =
    groupBy { it.partition ?: UNDECODED_PARTITION }
        .map { (partition, files) ->
            val data = files.filter { it.content == DataFileContent.DATA }
            val pos = files.filter { it.content == DataFileContent.POSITION_DELETES }
            val eq = files.filter { it.content == DataFileContent.EQUALITY_DELETES }
            PartitionShare(
                partition = partition,
                dataFileCount = data.size,
                dataRecordCount = data.filter { !it.partial }.sumOf { it.recordCount },
                dataSizeBytes = data.sumOf { it.sizeBytes },
                posDeleteFileCount = pos.size,
                posDeleteRecordCount = pos.sumOf { it.recordCount },
                eqDeleteFileCount = eq.size,
                eqDeleteRecordCount = eq.sumOf { it.recordCount },
            )
        }
        .sortedWith(compareByDescending<PartitionShare> { it.dataSizeBytes }.thenBy { it.partition })

/** The group [partitionBreakdown] puts a file under when its partition tuple did not decode. */
const val UNDECODED_PARTITION = "(partition not decoded)"

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
        // The ledger answers one contribution per entry, in order, so the entry's decoded
        // partition is read back beside it by position.
        manifestLedger(
            entries = manifest.dataFiles.map { unified ->
                LedgerEntry(
                    fileKey = unified.ledgerFileKey(),
                    status = unified.metadata.status,
                    dataFile = unified.metadata.dataFile,
                )
            },
            liveEntriesOnly = true,
            seenFileKeys = seen,
        ).zip(manifest.dataFiles)
            .filter { (entry, _) -> entry.fate == EntryFate.COUNTED }
            .map { (entry, unified) ->
                LiveFile(
                    path = entry.filePath,
                    key = entry.fileKey,
                    content = entry.content,
                    recordCount = entry.recordCount,
                    sizeBytes = entry.sizeBytes,
                    chargedSizeBytes = entry.chargedSizeBytes,
                    partition = unified.partition?.path,
                    specId = manifest.metadata.partitionSpecId,
                )
            }
    }
}

/**
 * The set difference between two live file sets, keyed the way the ledger keys them
 * ([LiveFile.key]): the normalised path, with the referenced data file on a deletion vector.
 *
 * Path is the key because it is what Iceberg guarantees unique within a table and what a reader
 * recognises — except for a vector, whose Puffin container holds a blob per data file, so two
 * vectors share one path and are told apart by the file each references. Two files with the
 * same key and different figures is the contradictory case [DiffSide.CHANGED] exists for — not
 * silently merged, and not silently counted twice.
 *
 * The order is stable and chosen for reading: what left, then what arrived, then what stayed,
 * each by key. A diff whose rows move between runs cannot be compared against a previous one.
 */
fun snapshotDiff(fromId: Long?, from: List<LiveFile>, toId: Long?, to: List<LiveFile>): SnapshotDiff {
    val fromByPath = from.associateBy { it.key }
    val toByPath = to.associateBy { it.key }

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

/**
 * The drawn snapshots a comparison can step through, in commit order — the order the panel puts
 * two selected snapshots in, so "the next one" means the same thing whichever side is stepped.
 * Expired snapshots are left out: they have no manifests to compare.
 */
fun GraphModel.comparableSnapshotsInOrder(): List<ComparableSnapshot> =
    nodes.filterIsInstance<ComparableSnapshot>()
        .filter { it.canDiff }
        .sortedWith(
            compareBy(
                { it.commitOrder ?: Long.MAX_VALUE },
                { it.commitTimeMs ?: Long.MAX_VALUE },
                { it.commitId ?: Long.MAX_VALUE },
            ),
        )

/**
 * The snapshot [steps] commits from [nodeId] in [comparableSnapshotsInOrder] — negative for
 * older — or null off either end. Stepping one side of a comparison while the other stays
 * pinned is how a branch is compared against successive points on `main`.
 */
fun GraphModel.stepComparableSnapshot(nodeId: String, steps: Int): ComparableSnapshot? {
    val ordered = comparableSnapshotsInOrder()
    val index = ordered.indexOfFirst { it.nodeId == nodeId }
    if (index < 0) return null
    return ordered.getOrNull(index + steps)
}
