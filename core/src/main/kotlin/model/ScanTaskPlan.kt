package model

/**
 * How many tasks a read of a snapshot takes, planned the way `TableScanUtil.planTasks` plans them
 * at Iceberg 1.8.1 — the figure a Spark job's partition count comes from, and the one a reader
 * asks about when three files became forty tasks or four hundred files became one.
 *
 * Every live data file is first cut into splits (`BaseContentScanTask.split`): where the file
 * records `split_offsets` that are strictly ascending, **one split per offset** — a row group
 * each, whatever the target size — else `read.split.target-size` slices; a format that cannot be
 * split, or a file of no bytes, is left as it is. Each split then weighs the greater of its bytes
 * plus its delete files' (`ScanTaskUtil.contentSizeInBytes`: a vector at its blob's size, any
 * other delete at its file's) and `(1 + delete files) × read.split.open-file-cost` — so a small
 * file, and a small row group, costs a task 4 MiB whatever it holds. The splits are bin-packed
 * to the target size with `read.split.planning-lookback` bins open, an overflowing set closing
 * its heaviest bin ([binPack]); a task is a bin.
 *
 * Two things the source states that a reading of the docs does not. **A row group is a split
 * before it is a task**, so a Parquet file of a hundred small row groups weighs 400 MiB at the
 * default cost and takes four tasks whatever its size. And **the packing runs over the files in
 * the order the plan returns them**, which with several manifests is the order the worker pool
 * finished them in (`ManifestGroup.planFiles` over `ThreadPools.getWorkerPool()`), so on such a
 * table this is one packing of the splits and the count can move by a bin between runs.
 *
 * Spark reads the same plan through `planTaskGroups` — the same weights and packing, adjacent
 * splits of one file merged back into one task entry — and, under
 * `read.split.adaptive-size.enabled` (the default), first **shrinks the target** when the scan
 * has fewer target-size splits than the job's parallelism: to the greater of the scan's bytes
 * over the parallelism and 16 MiB, capped by the target ([adjustedSplitSize]). So a table small
 * enough to fit one task at 128 MiB gets a task per 16 MiB of weight — four small files each.
 */
data class ScanTaskOptions(
    /** `read.split.target-size`, 128 MiB. */
    val splitSize: Long = SPLIT_SIZE_DEFAULT,
    /** `read.split.planning-lookback`, 10. */
    val lookback: Int = SPLIT_LOOKBACK_DEFAULT,
    /** `read.split.open-file-cost`, 4 MiB. */
    val openFileCost: Long = SPLIT_OPEN_FILE_COST_DEFAULT,
) {
    companion object {
        const val SPLIT_SIZE_DEFAULT = 128L * 1024 * 1024
        const val SPLIT_LOOKBACK_DEFAULT = 10
        const val SPLIT_OPEN_FILE_COST_DEFAULT = 4L * 1024 * 1024
        /** `TableScanUtil.MIN_SPLIT_SIZE`: the floor the adaptive size shrinks to. */
        const val MIN_SPLIT_SIZE = 16L * 1024 * 1024

        /** The table's properties as `BaseScan.targetSplitSize` and siblings read them; a scan option overrides each, which this does not model. */
        fun from(properties: Map<String, String>): ScanTaskOptions = ScanTaskOptions(
            splitSize = properties["read.split.target-size"]?.toLongOrNull()?.takeIf { it > 0 } ?: SPLIT_SIZE_DEFAULT,
            lookback = properties["read.split.planning-lookback"]?.toIntOrNull()?.takeIf { it > 0 } ?: SPLIT_LOOKBACK_DEFAULT,
            openFileCost = properties["read.split.open-file-cost"]?.toLongOrNull()?.takeIf { it >= 0 } ?: SPLIT_OPEN_FILE_COST_DEFAULT,
        )
    }
}

/** How a data file was cut into splits. */
enum class SplitRule { BY_OFFSETS, BY_SIZE, UNSPLIT }

/**
 * One split of a data file with the delete files the scan pairs with the whole file — every
 * split of a file carries all of them, and pays the open cost for each.
 */
data class ScanSplit(
    val path: String,
    val start: Long,
    val length: Long,
    val deleteFiles: Int,
    val deleteBytes: Long,
    val rule: SplitRule,
) {
    /** `max(length + deleteBytes, (1 + deleteFiles) × openFileCost)`. */
    fun weight(openFileCost: Long): Long = maxOf(length + deleteBytes, (1 + deleteFiles) * openFileCost)
}

/**
 * One combined task: the splits packed into one bin, in packing order, and [entries] — the same
 * with adjacent splits of one file joined back into one range, which is what `BaseCombinedScanTask`
 * and Spark's `planTaskGroups` do (`TableScanUtil.mergeTasks`) and what a task lists. The
 * [weight] is the bin's, summed before the join: a row group pays the open cost whether or not
 * its neighbour landed in the same bin.
 */
data class ScanTask(val splits: List<ScanSplit>, val weight: Long) {
    val files: Int get() = splits.map { it.path }.distinct().size
    val bytes: Long get() = splits.sumOf { it.length }
    val deleteFiles: Int get() = splits.map { it.path to it.deleteFiles }.distinct().sumOf { it.second }
    val entries: List<ScanSplit> get() {
        val out = mutableListOf<ScanSplit>()
        for (split in splits) {
            val last = out.lastOrNull()
            if (last != null && last.path == split.path && last.start + last.length == split.start) {
                out[out.lastIndex] = last.copy(length = last.length + split.length)
            } else {
                out += split
            }
        }
        return out
    }
}

data class ScanTaskPlan(
    val options: ScanTaskOptions,
    val splitSize: Long,
    val splits: List<ScanSplit>,
    val tasks: List<ScanTask>,
) {
    val dataFiles: Int get() = splits.map { it.path }.distinct().size
    val filesByOffsets: Int get() = splits.filter { it.rule == SplitRule.BY_OFFSETS }.map { it.path }.distinct().size
    val filesBySize: Int get() = splits.filter { it.rule == SplitRule.BY_SIZE }.map { it.path }.distinct().size
    /** The unsplit bytes of the scan, data and paired deletes — what `adjustSplitSize` measures (`ScanTask.sizeBytes` summed). */
    val scanBytes: Long get() = splits.sumOf { it.length } + splits.groupBy { it.path }.values.sumOf { it.first().deleteBytes }

    /**
     * One line — `1 task over 2 data files as 14 splits (2 a split per row group)` — which both
     * shells print, so the sentence cannot drift between them.
     */
    val describe: String get() {
        val unsplit = dataFiles - filesByOffsets - filesBySize
        val cuts = listOfNotNull(
            filesByOffsets.takeIf { it > 0 }?.let { "$it a split per row group" },
            filesBySize.takeIf { it > 0 }?.let { "$it cut into target-size slices" },
            unsplit.takeIf { it > 0 }?.let { "$it whole" },
        ).joinToString(", ")
        return counted(tasks.size, "task") + " over " + counted(dataFiles, "data file") +
            (if (dataFiles > 0) " as ${counted(splits.size, "split")} ($cuts)" else "")
    }

    private fun counted(n: Int, noun: String) = "$n $noun" + if (n == 1) "" else "s"
}

/**
 * Spark's answer in one line: the partitions at [parallelism] under the adaptive split size,
 * from [files] re-planned at [adjustedSplitSize] where it shrank the target — `Spark at a
 * parallelism of 200 reads 4 partitions, the target shrunk to 16,777,216 bytes`.
 */
fun ScanTaskPlan.describeSpark(files: List<ScanTaskFile>, parallelism: Int): String {
    val adjusted = adjustedSplitSize(scanBytes, parallelism, options.splitSize)
    val partitions = if (adjusted == options.splitSize) tasks.size else planScanTasks(files, options, adjusted).tasks.size
    return "Spark at a parallelism of $parallelism reads $partitions partition" + (if (partitions == 1) "" else "s") +
        (if (adjusted == options.splitSize) "" else ", the target shrunk to ${"%,d".format(adjusted)} bytes")
}

/** The data files of a snapshot with the delete files the scan pairs with each, from [liveFilesOf] and [deleteReach]. */
fun scanTaskFiles(live: List<LiveFile>, reach: List<DeleteReach>): List<ScanTaskFile> {
    val deleteBytes = live.filter { it.content != DataFileContent.DATA }.associate { it.key to it.chargedSizeBytes }
    val paired = mutableMapOf<String, MutableList<Long>>()
    reach.forEach { r ->
        val bytes = deleteBytes[r.deleteKey] ?: 0L
        (r.reaches + r.mayReach).forEach { paired.getOrPut(normalizeFilePath(it)) { mutableListOf() }.add(bytes) }
    }
    return live.filter { it.content == DataFileContent.DATA }.map { f ->
        val deletes = paired[normalizeFilePath(f.path)].orEmpty()
        ScanTaskFile(f.path, f.sizeBytes, f.format, f.splitOffsets, deletes.size, deletes.sum())
    }
}

/** A data file as the planner sees it: its bytes, its format, its recorded offsets, and its paired deletes' count and bytes. */
data class ScanTaskFile(
    val path: String,
    val sizeBytes: Long,
    val format: String?,
    val splitOffsets: List<Long>?,
    val deleteFiles: Int,
    val deleteBytes: Long,
)

/** `BaseContentScanTask.split` over each file, then `TableScanUtil.planTasks` over the splits, in the order given. */
fun planScanTasks(files: List<ScanTaskFile>, options: ScanTaskOptions, splitSize: Long = options.splitSize): ScanTaskPlan {
    val splits = files.flatMap { it.split(splitSize) }
    val tasks = binPack(splits, splitSize, options.lookback, largestBinFirst = true) { it.weight(options.openFileCost) }
        .map { bin -> ScanTask(bin, bin.sumOf { it.weight(options.openFileCost) }) }
    return ScanTaskPlan(options, splitSize, splits, tasks)
}

/** The splits of one file: a row group each where the offsets are well defined, else [splitSize] slices, else the file whole. */
internal fun ScanTaskFile.split(splitSize: Long): List<ScanSplit> {
    fun at(start: Long, length: Long, rule: SplitRule) = ScanSplit(path, start, length, deleteFiles, deleteBytes, rule)
    if (format?.lowercase() !in SPLITTABLE_FORMATS) return listOf(at(0, sizeBytes, SplitRule.UNSPLIT))
    // `BaseFile.splitOffsets()` hands the list out only while it is well defined — its last
    // offset below the file's size (#8925) — and the split iterator asks for strictly ascending.
    val offsets = splitOffsets?.takeIf { it.isNotEmpty() && it.last() < sizeBytes && it.zipWithNext().all { (a, b) -> a < b } }
    if (offsets != null) {
        return offsets.mapIndexed { i, start -> at(start, (offsets.getOrNull(i + 1) ?: sizeBytes) - start, SplitRule.BY_OFFSETS) }
    }
    val out = mutableListOf<ScanSplit>()
    var offset = 0L
    var remaining = sizeBytes
    while (remaining > 0) {
        val length = minOf(splitSize, remaining)
        out += at(offset, length, SplitRule.BY_SIZE)
        offset += length
        remaining -= length
    }
    return out
}

private val SPLITTABLE_FORMATS = setOf("parquet", "orc", "avro")

/**
 * `BinPacking.PackingIterator`: each item goes into the first of the open bins it fits, else
 * opens a new one; past [lookback] open bins the heaviest is closed when [largestBinFirst], else
 * the oldest; what is still open at the end closes oldest first.
 */
internal fun <T> binPack(items: List<T>, target: Long, lookback: Int, largestBinFirst: Boolean, weightOf: (T) -> Long): List<List<T>> {
    require(lookback > 0) { "Bin look-back size must be greater than 0: $lookback" }
    class Bin(val items: MutableList<T> = mutableListOf(), var weight: Long = 0L)
    val open = ArrayDeque<Bin>()
    val closed = mutableListOf<List<T>>()
    for (item in items) {
        val weight = weightOf(item)
        val bin = open.firstOrNull { it.weight + weight <= target }
        if (bin != null) {
            bin.items += item
            bin.weight += weight
        } else {
            open.addLast(Bin(mutableListOf(item), weight))
            if (open.size > lookback) {
                val out = if (largestBinFirst) open.maxBy { it.weight }.also { open.remove(it) } else open.removeFirst()
                closed += out.items
            }
        }
    }
    while (open.isNotEmpty()) closed += open.removeFirst().items
    return closed
}

/**
 * `TableScanUtil.adjustSplitSize`, which `SparkScan.adjustSplitSize` applies under
 * `read.split.adaptive-size.enabled` when no split size was given as a read option: with
 * fewer target-size splits than [parallelism] (`max(spark.default.parallelism,
 * spark.sql.shuffle.partitions)`), the target becomes the greater of the scan's bytes over the
 * parallelism and 16 MiB — never above the target itself, since the floor is `min(16 MiB, target)`.
 */
fun adjustedSplitSize(scanBytes: Long, parallelism: Int, splitSize: Long): Long {
    if (parallelism <= 0) return splitSize
    val splitCount = if (splitSize <= 0) 0 else (scanBytes + splitSize - 1) / splitSize
    val adjusted = maxOf(scanBytes / parallelism, minOf(ScanTaskOptions.MIN_SPLIT_SIZE, splitSize))
    return if (splitCount < parallelism) adjusted else splitSize
}
