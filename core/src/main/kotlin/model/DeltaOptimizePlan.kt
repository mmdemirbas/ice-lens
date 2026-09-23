package model

/**
 * What `OPTIMIZE` — compaction, no `ZORDER BY` — would rewrite, planned the way
 * `OptimizeExecutor.optimize` does it at delta-spark 3.2.1.
 *
 * **A candidate** is a live file smaller than `spark.databricks.delta.optimize.minFileSize`
 * (1 GiB), or one whose deletion vector marks more than `optimize.maxDeletedRowsRatio` (5%) of its
 * rows — the cardinality over `numRecords`, which counts the marked rows too — or one with a vector
 * and no `numRecords`, which is always compacted so the output gets statistics. **Candidates are
 * grouped by partition values, sorted by size ascending, and packed greedily** into bins of at most
 * `optimize.maxFileSize` (1 GiB): a file that would take the bin past it closes the bin and opens
 * the next. **Only a bin of two or more files is rewritten**, so a lone candidate — a file
 * alone in its partition, or the one left after the last full bin — stays as it is, its vector
 * with it. Each rewritten bin becomes one file, removed and added with `dataChange = false`.
 *
 * A table clustered by `CLUSTER BY`, and `OPTIMIZE … ZORDER BY`, take every file instead; neither
 * is planned here, and [DeltaOptimizePlan.clustered] says when the first applies.
 *
 * The oracle is `dopt` / `dvac`: the table before, and after the `OPTIMIZE` that rewrote its four
 * one-row files into one.
 */
data class DeltaOptimizeOptions(
    val minFileSize: Long = 1L shl 30,
    val maxFileSize: Long = 1L shl 30,
    val maxDeletedRowsRatio: Double = 0.05,
)

data class DeltaOptimizeFile(
    val add: DeltaAddFile,
    /** Why it is a candidate; null for one that is not. */
    val candidateBecause: String?,
    /** The marked rows over the recorded ones, where both are known. */
    val deletedRatio: Double?,
)

data class DeltaOptimizeBin(
    val partitionValues: Map<String, String?>,
    val files: List<DeltaOptimizeFile>,
) {
    val bytes: Long get() = files.sumOf { it.add.size ?: 0L }

    /** Only a bin of two or more is rewritten. */
    val rewritten: Boolean get() = files.size > 1
}

data class DeltaOptimizePlan(
    val version: Long,
    val options: DeltaOptimizeOptions,
    /** Every live file, candidates first. */
    val files: List<DeltaOptimizeFile>,
    /** Every bin, per partition in the order packed, the ones left alone included. */
    val bins: List<DeltaOptimizeBin>,
    /** A `CLUSTER BY` table, whose OPTIMIZE takes every file and is not planned here. */
    val clustered: Boolean,
) {
    val rewrittenBins: List<DeltaOptimizeBin> get() = bins.filter { it.rewritten }
    val removed: List<DeltaOptimizeFile> get() = rewrittenBins.flatMap { it.files }
    val removedBytes: Long get() = removed.sumOf { it.add.size ?: 0L }

    /** One output file per rewritten bin. */
    val filesAdded: Int get() = rewrittenBins.size

    /** Candidates in a bin of one: small or marked, and left alone. */
    val leftAlone: List<DeltaOptimizeFile> get() = bins.filter { !it.rewritten }.flatMap { it.files }

    /** `OPTIMIZE` writes no commit when it rewrites nothing. */
    val commits: Boolean get() = filesAdded > 0
}

/** The plan over [files], the live adds of [version]. */
fun planDeltaOptimize(
    version: Long,
    files: Collection<DeltaAddFile>,
    options: DeltaOptimizeOptions = DeltaOptimizeOptions(),
    clustered: Boolean = false,
): DeltaOptimizePlan {
    val judged = files.map { add ->
        val records = add.parsedStats?.numRecords
        val marked = add.deletionVector?.cardinality ?: 0L
        val ratio = if (records != null && records > 0) marked.toDouble() / records else null
        val because = when {
            (add.size ?: 0L) < options.minFileSize -> "smaller than optimize.minFileSize (${formatBytes(options.minFileSize)})"
            add.deletionVector != null && records == null -> "a deletion vector and no numRecords — always compacted, so the output gets statistics"
            ratio != null && ratio > options.maxDeletedRowsRatio -> "its vector marks %.1f%% of its rows, over optimize.maxDeletedRowsRatio (%.0f%%)".format(ratio * 100, options.maxDeletedRowsRatio * 100)
            else -> null
        }
        DeltaOptimizeFile(add, because, ratio)
    }
    val bins = mutableListOf<DeltaOptimizeBin>()
    judged.filter { it.candidateBecause != null }.groupBy { it.add.partitionValues }.forEach { (partition, candidates) ->
        var bin = mutableListOf<DeltaOptimizeFile>()
        var binBytes = 0L
        for (file in candidates.sortedBy { it.add.size ?: 0L }) {
            val size = file.add.size ?: 0L
            if (size + binBytes > options.maxFileSize) {
                if (bin.isNotEmpty()) bins += DeltaOptimizeBin(partition, bin)
                bin = mutableListOf(file)
                binBytes = size
            } else {
                bin += file
                binBytes += size
            }
        }
        if (bin.isNotEmpty()) bins += DeltaOptimizeBin(partition, bin)
    }
    val ordered = judged.sortedWith(compareBy<DeltaOptimizeFile> { it.candidateBecause == null }.thenBy { it.add.path })
    return DeltaOptimizePlan(version, options, ordered, bins, clustered)
}

/** The plan at the latest version, under the default options unless given. */
fun DeltaUnifiedTableModel.planOptimize(options: DeltaOptimizeOptions = DeltaOptimizeOptions()): Result<DeltaOptimizePlan> = runCatching {
    val version = requireNotNull(latestVersion) { "the log holds no version" }
    val state = stateAt(version).getOrThrow()
    planDeltaOptimize(version, state.files.values, options, clustered = "delta.clustering" in state.domains)
}
