package model

import kotlin.math.ceil

/**
 * What `rewrite_data_files` would rewrite at a snapshot, decided the way Iceberg's
 * `SizeBasedDataRewriter` (bin-pack and sort alike) decides it — read at Iceberg 1.8.1, the
 * runtime the fixtures were written with — and checked against the three rewrites those
 * fixtures ran (`mor`, `maint`, `sorted`).
 *
 * `RewriteDataFilesSparkAction` plans one `FileScanTask` per live data file, each carrying the
 * delete files the scan pairs with it, groups them **by partition** — a file written under a spec
 * other than the table's current one goes into the empty partition with every other such file —
 * and for each partition:
 *
 * 1. **Filters** the files, unless `rewrite-all`: a file is a candidate when it is outside
 *    `min-file-size-bytes`..`max-file-size-bytes` (75% and 180% of `target-file-size-bytes`, which
 *    is the table's `write.target-file-size-bytes`, 512 MB), or has at least `delete-file-threshold`
 *    delete files paired with it (unbounded by default), or its *file-scoped* deletes — vectors and
 *    positional deletes whose path bounds meet — mark at least `delete-ratio-threshold` (30%) of
 *    its rows.
 * 2. **Bin-packs** the candidates in scan order into groups of at most `max-file-group-size-bytes`
 *    (100 GB), one open bin at a time.
 * 3. **Keeps** a group when it holds at least `min-input-files` (5) files, or more than the target
 *    in bytes, or more than the maximum in bytes, or any file in it crossed a delete threshold.
 *
 * The first rule is why every small file is a candidate and the third is why a lone one is
 * still left alone: it takes five of them, or a delete file that marks a third of one.
 *
 * **`where => …` picks the files the way a scan does.** `planFileGroups` plans
 * `table.newScan().filter(filter).ignoreResiduals().planFiles()` (1.8.1, lines 199–206), so the
 * files a rewrite considers are the ones `ManifestEvaluator` and `InclusiveMetricsEvaluator`
 * leave — `evaluateScan`, held to Iceberg's own plans — and every file left is rewritten whole
 * (`ignoreResiduals`). A file the filter rules out is no candidate and fills no group, so a
 * `where` reaching one small file leaves it alone whatever the rest of the partition holds
 * ([RewritePlan.filteredOut]); `docs/fixtures/rewrite-where.sql` records the runs.
 */
data class RewriteOptions(
    /** `target-file-size-bytes`, else the table's `write.target-file-size-bytes`, else 512 MB. */
    val targetFileSizeBytes: Long = DEFAULT_TARGET_FILE_SIZE_BYTES,
    /** `min-file-size-bytes`, else 75% of the target. */
    val minFileSizeBytes: Long = (targetFileSizeBytes * 0.75).toLong(),
    /** `max-file-size-bytes`, else 180% of the target. */
    val maxFileSizeBytes: Long = (targetFileSizeBytes * 1.80).toLong(),
    /** `min-input-files` (5). */
    val minInputFiles: Int = 5,
    /** `rewrite-all` (false). */
    val rewriteAll: Boolean = false,
    /** `max-file-group-size-bytes` (100 GB). */
    val maxFileGroupSizeBytes: Long = 100L * 1024 * 1024 * 1024,
    /** `delete-file-threshold` — unbounded by default, so it never selects unless set. */
    val deleteFileThreshold: Int = Int.MAX_VALUE,
    /** `delete-ratio-threshold` (0.3). */
    val deleteRatioThreshold: Double = 0.3,
    /** The table's current spec; a file under any other spec is planned as unpartitioned. */
    val currentSpecId: Int? = null,
) {
    companion object {
        const val DEFAULT_TARGET_FILE_SIZE_BYTES = 512L * 1024 * 1024

        /** The options a bare call runs under, from the table's properties. */
        fun forTable(properties: Map<String, String>, currentSpecId: Int?): RewriteOptions {
            val target = properties["write.target-file-size-bytes"]?.toLongOrNull() ?: DEFAULT_TARGET_FILE_SIZE_BYTES
            return RewriteOptions(targetFileSizeBytes = target, currentSpecId = currentSpecId)
        }
    }
}

/** Why a file is a candidate. */
enum class RewriteFileReason(val label: String) {
    WRONGLY_SIZED("outside the size range"),
    TOO_MANY_DELETES("too many delete files"),
    HIGH_DELETE_RATIO("delete ratio"),
    REWRITE_ALL("rewrite-all"),
}

/** Why a group of candidates is rewritten. */
enum class RewriteGroupReason(val label: String) {
    ENOUGH_INPUT_FILES("enough input files"),
    ENOUGH_CONTENT("more than the target in bytes"),
    TOO_MUCH_CONTENT("more than the maximum in bytes"),
    TOO_MANY_DELETES("a file with too many delete files"),
    HIGH_DELETE_RATIO("a file past the delete ratio"),
    REWRITE_ALL("rewrite-all"),
}

data class RewriteCandidate(
    val path: String,
    val sizeBytes: Long,
    val recordCount: Long,
    /** The delete files the scan would pair with it — proved or unsettled, as [deleteReach] answers. */
    val deleteFileCount: Int,
    /** Rows its file-scoped deletes mark, capped at its row count — what the ratio is measured on. */
    val knownDeletedRecords: Long,
    val reasons: List<RewriteFileReason>,
) {
    val deleteRatio: Double get() = if (recordCount == 0L) 0.0 else knownDeletedRecords.toDouble() / recordCount
}

data class RewriteGroup(
    /** The partition the group is in — "" for unpartitioned, and for files under a spec that is not current. */
    val partition: String,
    val files: List<RewriteCandidate>,
    /** Empty when the group is left alone. */
    val reasons: List<RewriteGroupReason>,
    /** How many files the rewrite would write for it — `numOutputFiles`. */
    val outputFiles: Long,
) {
    val rewritten: Boolean get() = reasons.isNotEmpty()
    val inputBytes: Long get() = files.sumOf { it.sizeBytes }
}

data class RewritePlan(
    val options: RewriteOptions,
    /** Every live data file the rewrite considers, candidate or not, by partition in scan order. */
    val filesByPartition: Map<String, List<LiveFile>>,
    val groups: List<RewriteGroup>,
    /** The live data files a `where` ruled out — never candidates, never counted toward a group; empty on a bare call. */
    val filteredOut: List<LiveFile> = emptyList(),
) {
    val rewrittenGroups: List<RewriteGroup> get() = groups.filter { it.rewritten }
    val rewrittenPaths: Set<String> get() = rewrittenGroups.flatMap { g -> g.files.map { it.path } }.toSet()
    val candidateCount: Int get() = groups.sumOf { it.files.size }
}

/**
 * Plans the rewrite over [live] — a snapshot's live files, data files taken, delete files used only
 * through [reach], which pairs each with the data files it applies to.
 */
fun planRewrite(live: List<LiveFile>, reach: List<DeleteReach>, options: RewriteOptions, ruledOut: Set<String> = emptySet()): RewritePlan {
    val (filteredOut, data) = live.filter { it.content == DataFileContent.DATA }.partition { normalizeFilePath(it.path) in ruledOut }
    // Per data file: how many delete files the scan pairs with it, and the rows its file-scoped
    // ones mark. `mayReach` counts — the scan pairs an equality delete by sequence alone.
    val deleteCount = mutableMapOf<String, Int>()
    val knownDeleted = mutableMapOf<String, Long>()
    reach.forEach { r ->
        (r.reaches + r.mayReach).forEach { path ->
            val key = normalizeFilePath(path)
            deleteCount[key] = (deleteCount[key] ?: 0) + 1
            if (r.targets.namesOneFile) knownDeleted[key] = (knownDeleted[key] ?: 0L) + (r.recordCount ?: 0L)
        }
    }
    fun partitionOf(f: LiveFile): String =
        if (options.currentSpecId != null && f.specId != null && f.specId != options.currentSpecId) "" else f.partition ?: UNDECODED_PARTITION

    val byPartition = data.groupBy(::partitionOf)
    val groups = byPartition.entries.sortedBy { it.key }.flatMap { (partition, files) ->
        val candidates = files.mapNotNull { f ->
            val key = normalizeFilePath(f.path)
            val deletes = deleteCount[key] ?: 0
            val known = minOf(knownDeleted[key] ?: 0L, f.recordCount)
            val reasons = buildList {
                if (options.rewriteAll) add(RewriteFileReason.REWRITE_ALL) else {
                    if (f.sizeBytes < options.minFileSizeBytes || f.sizeBytes > options.maxFileSizeBytes) add(RewriteFileReason.WRONGLY_SIZED)
                    if (deletes >= options.deleteFileThreshold) add(RewriteFileReason.TOO_MANY_DELETES)
                    if (deletes > 0 && f.recordCount > 0 && known.toDouble() / f.recordCount >= options.deleteRatioThreshold) add(RewriteFileReason.HIGH_DELETE_RATIO)
                }
            }
            if (reasons.isEmpty()) null else RewriteCandidate(f.path, f.sizeBytes, f.recordCount, deletes, known, reasons)
        }
        packBins(candidates, options.maxFileGroupSizeBytes) { it.sizeBytes }.map { bin ->
            val input = bin.sumOf { it.sizeBytes }
            val reasons = buildList {
                if (options.rewriteAll) add(RewriteGroupReason.REWRITE_ALL) else {
                    if (bin.size > 1 && bin.size >= options.minInputFiles) add(RewriteGroupReason.ENOUGH_INPUT_FILES)
                    if (bin.size > 1 && input > options.targetFileSizeBytes) add(RewriteGroupReason.ENOUGH_CONTENT)
                    if (input > options.maxFileSizeBytes) add(RewriteGroupReason.TOO_MUCH_CONTENT)
                    if (bin.any { RewriteFileReason.TOO_MANY_DELETES in it.reasons }) add(RewriteGroupReason.TOO_MANY_DELETES)
                    if (bin.any { RewriteFileReason.HIGH_DELETE_RATIO in it.reasons }) add(RewriteGroupReason.HIGH_DELETE_RATIO)
                }
            }
            RewriteGroup(partition, bin, reasons, numOutputFiles(input, options.targetFileSizeBytes, options.minFileSizeBytes, options.maxFileSizeBytes))
        }
    }
    return RewritePlan(options, byPartition, groups, filteredOut)
}

/** `BinPacking.ListPacker(target, lookback = 1, largestBinFirst = false)`: one open bin, closed by the first item that does not fit. Shared with [planPositionDeleteRewrite]. */
internal fun <T> packBins(items: List<T>, target: Long, weightOf: (T) -> Long): List<List<T>> {
    val bins = mutableListOf<MutableList<T>>()
    var open: MutableList<T>? = null
    var weight = 0L
    for (item in items) {
        val itemWeight = weightOf(item)
        if (open != null && weight + itemWeight <= target) {
            open.add(item)
            weight += itemWeight
        } else {
            open = mutableListOf(item).also { bins.add(it) }
            weight = itemWeight
        }
    }
    return bins
}

/** `SizeBasedFileRewriter.numOutputFiles`: how many files a group's bytes become. Shared with [planPositionDeleteRewrite]. */
internal fun numOutputFiles(inputSize: Long, target: Long, minFileSize: Long, maxFileSize: Long): Long {
    if (inputSize < target) return 1
    val withRemainder = ceil(inputSize.toDouble() / target).toLong()
    val withoutRemainder = inputSize / target
    val avgWithoutRemainder = inputSize / withoutRemainder
    val writeMax = (target + (maxFileSize - target) * 0.5).toLong()
    return when {
        inputSize % target > minFileSize -> withRemainder
        avgWithoutRemainder < minOf(1.1 * target, writeMax.toDouble()) -> withoutRemainder
        else -> withRemainder
    }
}

/**
 * What `rewrite_data_files(options => map('remove-dangling-deletes', 'true'))` removes after the
 * rewrite — `RemoveDanglingDeletesSparkAction` at 1.8.1, run by `RewriteDataFilesSparkAction.execute`
 * only once at least one group was planned. Per spec and partition it takes the lowest data
 * sequence number over the live data files **as the rewrite leaves them** — a rewritten group's
 * output carries the starting snapshot's sequence number (`use-starting-sequence-number`, true by
 * default), so a partition whose old files all went has its floor moved up to it — and removes a
 * positional delete or vector **below** that floor, an equality delete **at or below** it, and
 * every delete in a partition left with no data file. By sequence alone, never by target.
 *
 * Two things the runs settled (`docs/fixtures/rewrite-where.sql`). **On an unpartitioned table
 * with one spec the action returns nothing** — `execute` says the commit's `ManifestFilterManager`
 * already drops such deletes, but `dropDeleteFilesOlderThan` is applied only inside a delete
 * manifest the commit opens for a delete file it removes by path, and a data-file rewrite removes
 * none: `mor` rewritten whole under the option kept all three delete files, two of them below the
 * new file's sequence number. **On a partitioned table it removes by the rule**: `fupp` rewritten
 * whole lost all five, in a second `replace` of its own.
 */
data class DanglingDeleteFile(
    val path: String,
    /** [DataFileContent.POSITION_DELETES] or [DataFileContent.EQUALITY_DELETES]. */
    val content: Int,
    val sequenceNumber: Long,
    val partition: String,
    /** The partition's lowest data sequence number after the rewrite; null where no data file is left in it. */
    val floor: Long?,
    val removed: Boolean,
    val reason: String,
)

data class DanglingDeletePlan(
    /** Why the action would not run, or null where it would. */
    val skipped: String?,
    val files: List<DanglingDeleteFile>,
) {
    val removed: List<DanglingDeleteFile> get() = if (skipped == null) files.filter { it.removed } else emptyList()
}

/**
 * [live] and [rewrite] at one snapshot; [newFileSequenceNumber] is what the rewritten groups'
 * output is committed at — the starting snapshot's under `use-starting-sequence-number`.
 */
fun planDanglingDeletes(
    live: List<LiveFile>,
    rewrite: RewritePlan,
    newFileSequenceNumber: Long,
    unpartitionedSingleSpec: Boolean,
): DanglingDeletePlan {
    data class Scope(val specId: Int?, val partition: String?)
    val rewritten = rewrite.rewrittenPaths.map(::normalizeFilePath).toSet()
    val data = live.filter { it.content == DataFileContent.DATA }
    // The floor per scope after the rewrite: the kept files' numbers, and the new file's where any went.
    val floors = mutableMapOf<Scope, Long>()
    data.forEach { f ->
        val scope = Scope(f.specId, f.partition)
        val seq = if (normalizeFilePath(f.path) in rewritten) newFileSequenceNumber else f.sequenceNumber ?: return@forEach
        floors[scope] = minOf(floors[scope] ?: Long.MAX_VALUE, seq)
    }
    val files = live.filter { it.content != DataFileContent.DATA }.map { d ->
        val floor = floors[Scope(d.specId, d.partition)]
        val seq = d.sequenceNumber ?: 0L
        val equality = d.content == DataFileContent.EQUALITY_DELETES
        val (removed, reason) = when {
            floor == null -> true to "no data file left in its partition"
            equality && seq <= floor -> true to "an equality delete at $seq, at or below the partition's floor $floor"
            !equality && seq < floor -> true to "a positional delete at $seq, below the partition's floor $floor"
            equality -> false to "an equality delete at $seq, above the partition's floor $floor"
            else -> false to "a positional delete at $seq, at or above the partition's floor $floor"
        }
        DanglingDeleteFile(d.path, d.content, seq, d.partition ?: UNDECODED_PARTITION, floor, removed, reason)
    }
    val skipped = when {
        unpartitionedSingleSpec -> "the action returns nothing on an unpartitioned table with one spec — it defers to the commit, which drops a delete below the floor only inside a delete manifest it opens for a delete file it removes, and a data-file rewrite removes none"
        rewrite.rewrittenGroups.isEmpty() -> "the action runs only after a rewrite that planned a group; nothing is rewritten here"
        else -> null
    }
    return DanglingDeletePlan(skipped, files)
}
