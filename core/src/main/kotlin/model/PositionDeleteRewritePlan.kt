package model

/**
 * What `rewrite_position_delete_files` would rewrite, planned the way
 * `RewritePositionDeleteFilesSparkAction` and `SizeBasedPositionDeletesRewriter` plan it at
 * Iceberg 1.8.1 — [planRewrite]'s twin for the delete side.
 *
 * The action scans the `position_deletes` metadata table — the current snapshot's live
 * positional delete files — groups them by partition (coerced to the table's unified partition
 * type, so files under two specs with one tuple share a group), and per group takes the files
 * outside `min-file-size-bytes`–`max-file-size-bytes` (75% and 180% of
 * `write.delete.target-file-size-bytes`, 64 MB by default) unless `rewrite-all`, packs them
 * into `max-file-group-size-bytes` bins in scan order, and rewrites a bin holding
 * `min-input-files` (5) or more, more than the target in bytes, or more than the maximum —
 * the same three rules as the data rewriter, without its two delete-based ones. **It refuses a
 * v3 table** (`Cannot rewrite position deletes for V3 table`), so a deletion vector is never
 * planned. What the rewrite writes is the other half, and it takes reading: a position is kept
 * when its `file_path` names a live data file of the group's partition
 * (`SparkBinPackPositionDeletesRewriter.doRewrite`, a left-semi join on `file_path` against
 * `data_files` filtered to the partition) and dropped otherwise — the dangling records, which
 * is the reason the procedure is run on a table after a compaction. `service.PositionDeleteRewriteDrops`
 * reads them; `maint`'s own rewrite (three positions removed, one written) is the oracle.
 */
data class PositionDeleteRewriteOptions(
    /** `target-file-size-bytes`, else the table's `write.delete.target-file-size-bytes`, else 64 MB. */
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
    /** The table's `format-version`; the action refuses 3 and above. */
    val formatVersion: Int? = null,
) {
    companion object {
        const val DEFAULT_TARGET_FILE_SIZE_BYTES = 64L * 1024 * 1024
        const val REFUSED_V3 = "Cannot rewrite position deletes for V3 table"

        /** The options a bare call runs under, from the table's properties. */
        fun forTable(properties: Map<String, String>, formatVersion: Int?): PositionDeleteRewriteOptions {
            val target = properties["write.delete.target-file-size-bytes"]?.toLongOrNull() ?: DEFAULT_TARGET_FILE_SIZE_BYTES
            return PositionDeleteRewriteOptions(targetFileSizeBytes = target, formatVersion = formatVersion)
        }
    }
}

data class PositionDeleteRewriteGroup(
    /** The partition the group is in — "" for unpartitioned. */
    val partition: String,
    /** The candidates, in scan order. */
    val files: List<LiveFile>,
    /** Empty when the group is left alone. */
    val reasons: List<RewriteGroupReason>,
    /** How many files the rewrite would write for it — `numOutputFiles`. */
    val outputFiles: Long,
) {
    val rewritten: Boolean get() = reasons.isNotEmpty()
    val inputBytes: Long get() = files.sumOf { it.sizeBytes }
    val recordCount: Long get() = files.sumOf { it.recordCount }
}

data class PositionDeleteRewritePlan(
    val options: PositionDeleteRewriteOptions,
    /** Every live positional delete file, candidate or not, by partition in scan order. */
    val filesByPartition: Map<String, List<LiveFile>>,
    val groups: List<PositionDeleteRewriteGroup>,
    /** The action's own refusal, where it would not run at all. */
    val refused: String? = null,
) {
    val rewrittenGroups: List<PositionDeleteRewriteGroup> get() = groups.filter { it.rewritten }
    val rewrittenFiles: List<LiveFile> get() = rewrittenGroups.flatMap { it.files }
    val candidateCount: Int get() = groups.sumOf { it.files.size }
    val deleteFileCount: Int get() = filesByPartition.values.sumOf { it.size }
}

/** Plans the rewrite over [live] — a snapshot's live files, the positional delete files taken. */
fun planPositionDeleteRewrite(live: List<LiveFile>, options: PositionDeleteRewriteOptions): PositionDeleteRewritePlan {
    val deletes = live.filter { it.content == DataFileContent.POSITION_DELETES }
    val byPartition = deletes.groupBy { it.partition ?: UNDECODED_PARTITION }
    if ((options.formatVersion ?: 0) >= 3) {
        return PositionDeleteRewritePlan(options, byPartition, emptyList(), refused = PositionDeleteRewriteOptions.REFUSED_V3)
    }
    val groups = byPartition.entries.sortedBy { it.key }.flatMap { (partition, files) ->
        val candidates = if (options.rewriteAll) files else files.filter { it.sizeBytes < options.minFileSizeBytes || it.sizeBytes > options.maxFileSizeBytes }
        packBins(candidates, options.maxFileGroupSizeBytes) { it.sizeBytes }.map { bin ->
            val input = bin.sumOf { it.sizeBytes }
            val reasons = buildList {
                if (options.rewriteAll) add(RewriteGroupReason.REWRITE_ALL) else {
                    if (bin.size > 1 && bin.size >= options.minInputFiles) add(RewriteGroupReason.ENOUGH_INPUT_FILES)
                    if (bin.size > 1 && input > options.targetFileSizeBytes) add(RewriteGroupReason.ENOUGH_CONTENT)
                    if (input > options.maxFileSizeBytes) add(RewriteGroupReason.TOO_MUCH_CONTENT)
                }
            }
            PositionDeleteRewriteGroup(partition, bin, reasons, numOutputFiles(input, options.targetFileSizeBytes, options.minFileSizeBytes, options.maxFileSizeBytes))
        }
    }
    return PositionDeleteRewritePlan(options, byPartition, groups)
}
