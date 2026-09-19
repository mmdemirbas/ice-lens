package model

/**
 * The splits a batch read of a Paimon snapshot takes, planned the way
 * `SnapshotReaderImpl.generateSplits` plans them at release-1.3.1 — the Paimon twin of
 * [planScanTasks], and the figure a Spark job's partition count comes from.
 *
 * The read files ([PaimonReadInput.readFiles]: every live file, or those above level 0 where a
 * batch read skips it) are grouped by partition and bucket in the order the plan lists them, and
 * each bucket goes through the table's `SplitGenerator`:
 *
 * - A **primary-key** table's `MergeTreeSplitGenerator`. When every file is above level 0 with no
 *   `-D` row inside, and the table has deletion vectors, is `first-row`, or holds them all at one
 *   level, the files are packed whole in the order given, each weighing its bytes or
 *   `source.split.open-file-cost`, whichever is more, and every split reads raw. Otherwise the
 *   files are cut into sections of intersecting key ranges (`IntervalPartition`,
 *   [paimonIntervalSections]) — a file's records may be shadowed by another's in the same range,
 *   so the two must merge in one split — and the sections are packed, a section weighing its
 *   files' bytes or the open cost; a split reads raw only when it holds one file with no `-D`
 *   row. **Packed, not sectioned**: two sections that share no key still land in one split when
 *   both fit the target.
 * - An **append** table's `AppendOnlySplitGenerator` sorts the files by `_MIN_SEQUENCE_NUMBER`
 *   and packs them, every split raw.
 * - A **data-evolution** table's `DataEvolutionSplitGenerator` sorts by first row id then by
 *   `_MAX_SEQUENCE_NUMBER` descending, groups the files sharing a first row id (a file recording
 *   none alone), and packs the groups, a group weighing its bytes or the open cost; a split reads
 *   raw only when every group in it is one file.
 *
 * The packing is `BinPacking.packForOrdered` to `source.split.target-size` (128 MiB): an item
 * joins the open split unless it would carry it past the target and the split already holds
 * something, so an item heavier than the target is a split of its own and nothing is reordered.
 * A split is one `DataSplit`, and one task in Flink; Spark repacks the raw ones — see
 * [paimonSparkPartitions].
 */
data class PaimonSplitOptions(
    /** `source.split.target-size`, 128 MiB. */
    val targetSplitSize: Long = TARGET_SPLIT_SIZE_DEFAULT,
    /** `source.split.open-file-cost`, 4 MiB. */
    val openFileCost: Long = OPEN_FILE_COST_DEFAULT,
) {
    companion object {
        const val TARGET_SPLIT_SIZE_DEFAULT = 128L shl 20
        const val OPEN_FILE_COST_DEFAULT = 4L shl 20

        /** The table's options as `CoreOptions.splitTargetSize` and `.splitOpenFileCost` read them. */
        fun from(options: Map<String, String>): PaimonSplitOptions = PaimonSplitOptions(
            targetSplitSize = options["source.split.target-size"]?.let(::parsePaimonMemoryBytes)?.takeIf { it > 0 } ?: TARGET_SPLIT_SIZE_DEFAULT,
            openFileCost = options["source.split.open-file-cost"]?.let(::parsePaimonMemoryBytes)?.takeIf { it >= 0 } ?: OPEN_FILE_COST_DEFAULT,
        )
    }
}

/** Which generator, and which of its branches, cut a bucket. */
enum class PaimonSplitRule(val label: String) {
    /** `MergeTreeSplitGenerator`, every file above level 0 without a `-D` row under deletion vectors, `first-row`, or one level: packed whole, read raw. */
    MERGE_PACKED_WHOLE("packed whole — every file above level 0 with no -D row"),
    /** `MergeTreeSplitGenerator`'s sections of intersecting key ranges, packed. */
    MERGE_SECTIONS("sections of intersecting key ranges, packed"),
    /** `AppendOnlySplitGenerator`: by minimum sequence number, packed. */
    APPEND("by minimum sequence number, packed"),
    /** `DataEvolutionSplitGenerator`: groups sharing a first row id, packed. */
    DATA_EVOLUTION("groups sharing a first row id, packed"),
}

/** One `DataSplit`: a bucket's files packed into one bin, in packing order. */
data class PaimonSplit(
    val partition: String,
    val bucket: Int,
    val files: List<PaimonLookupFile>,
    /** `DataSplit.rawConvertible` — read without merging, and so through the reader that consults a file index. */
    val rawConvertible: Boolean,
    /** The bin's weight: each item's bytes or the open cost, whichever is more, summed. */
    val weight: Long,
    /** The items packed — sections, groups, or files by the rule. */
    val items: Int,
    val rule: PaimonSplitRule,
) {
    val bytes: Long get() = files.sumOf { it.fileSize ?: 0L }
}

data class PaimonSplitPlan(
    val options: PaimonSplitOptions,
    val splits: List<PaimonSplit>,
    /** `deletion-vectors.enabled` — whether Spark charges a vector's bytes to its file's partition. */
    val deletionVectors: Boolean,
    /** Each read file's vector length by file name, where the table has vectors — `DeletionFile.length`, the index manifest's range. */
    val vectorLengths: Map<String, Long>,
) {
    val files: Int get() = splits.sumOf { it.files.size }
    val buckets: Int get() = splits.map { it.partition to it.bucket }.distinct().size
    val rawSplits: Int get() = splits.count { it.rawConvertible }
    val rules: Set<PaimonSplitRule> get() = splits.map { it.rule }.toSet()
}

/** [planPaimonSplits] over the snapshot's read files under the table's own `source.split.*`. */
fun planPaimonSplits(input: PaimonReadInput, options: PaimonSplitOptions = PaimonSplitOptions.from(input.schema.options)): PaimonSplitPlan =
    planPaimonSplits(input, input.readFiles, options)

/**
 * The plan over [files] — the read files, or the ones a filter leaves — grouped by partition and
 * bucket in the order given, each bucket cut by the generator the table's schema picks.
 */
fun planPaimonSplits(input: PaimonReadInput, files: List<PaimonLookupFile>, options: PaimonSplitOptions = PaimonSplitOptions.from(input.schema.options)): PaimonSplitPlan {
    val deletionVectors = input.schema.options["deletion-vectors.enabled"]?.trim().equals("true", ignoreCase = true)
    val splits = files.groupBy { it.partition to it.bucket }.flatMap { (key, bucketFiles) ->
        val (partition, bucket) = key
        val groups = when {
            input.hasPrimaryKey -> mergeTreeSplits(bucketFiles, deletionVectors, input.mergeEngine, options)
            input.dataEvolution -> dataEvolutionSplits(bucketFiles, options)
            else -> appendSplits(bucketFiles, options)
        }
        groups.map { PaimonSplit(partition, bucket, it.files, it.rawConvertible, it.weight, it.items, it.rule) }
    }
    val lengths = if (deletionVectors) files.mapNotNull { f -> input.vectorFor(f.fileName)?.let { f.fileName to it.length } }.toMap() else emptyMap()
    return PaimonSplitPlan(options, splits, deletionVectors, lengths)
}

private class Group(val files: List<PaimonLookupFile>, val rawConvertible: Boolean, val weight: Long, val items: Int, val rule: PaimonSplitRule)

private fun PaimonLookupFile.withoutDeleteRow(): Boolean = (deleteRowCount ?: 0L) == 0L
private fun PaimonLookupFile.size(): Long = fileSize ?: 0L

/** `MergeTreeSplitGenerator.splitForBatch`. */
private fun mergeTreeSplits(files: List<PaimonLookupFile>, deletionVectors: Boolean, mergeEngine: String, options: PaimonSplitOptions): List<Group> {
    val rawConvertible = files.all { (it.level ?: 0) != 0 && it.withoutDeleteRow() }
    val oneLevel = files.map { it.level ?: 0 }.toSet().size == 1
    if (rawConvertible && (deletionVectors || mergeEngine == "first-row" || oneLevel)) {
        return packForOrdered(files, options.targetSplitSize) { maxOf(it.size(), options.openFileCost) }
            .map { bin -> Group(bin, true, bin.sumOf { maxOf(it.size(), options.openFileCost) }, bin.size, PaimonSplitRule.MERGE_PACKED_WHOLE) }
    }
    val sections = paimonIntervalSections(files, { it.keyRange?.min }, { it.keyRange?.max })
    fun weightOf(section: List<PaimonLookupFile>) = maxOf(section.sumOf { it.size() }, options.openFileCost)
    return packForOrdered(sections, options.targetSplitSize, ::weightOf).map { bin ->
        val flat = bin.flatten()
        Group(flat, flat.size == 1 && flat[0].withoutDeleteRow(), bin.sumOf(::weightOf), bin.size, PaimonSplitRule.MERGE_SECTIONS)
    }
}

/** `AppendOnlySplitGenerator.splitForBatch`: `fileComparator` orders by `minSequenceNumber`, a stable sort keeping the plan's order among equals. */
private fun appendSplits(files: List<PaimonLookupFile>, options: PaimonSplitOptions): List<Group> {
    val sorted = files.sortedBy { it.minSequenceNumber ?: Long.MIN_VALUE }
    return packForOrdered(sorted, options.targetSplitSize) { maxOf(it.size(), options.openFileCost) }
        .map { bin -> Group(bin, true, bin.sumOf { maxOf(it.size(), options.openFileCost) }, bin.size, PaimonSplitRule.APPEND) }
}

/** `DataEvolutionSplitGenerator.splitForBatch` over `split(files)` — the stitch's groups in packing order. */
private fun dataEvolutionSplits(files: List<PaimonLookupFile>, options: PaimonSplitOptions): List<Group> {
    val sorted = files.sortedWith(compareBy<PaimonLookupFile> { it.firstRowId ?: Long.MIN_VALUE }.thenByDescending { it.maxSequenceNumber ?: Long.MIN_VALUE })
    val groups = mutableListOf<List<PaimonLookupFile>>()
    var current = mutableListOf<PaimonLookupFile>()
    var lastRowId: Long? = null
    for (f in sorted) {
        val first = f.firstRowId
        if (first == null) {
            groups += listOf(f)
            continue
        }
        if (first != lastRowId) {
            if (current.isNotEmpty()) groups += current
            current = mutableListOf()
            lastRowId = first
        }
        current += f
    }
    if (current.isNotEmpty()) groups += current
    fun weightOf(group: List<PaimonLookupFile>) = maxOf(group.sumOf { it.size() }, options.openFileCost)
    return packForOrdered(groups, options.targetSplitSize, ::weightOf).map { bin ->
        Group(bin.flatten(), bin.all { it.size == 1 }, bin.sumOf(::weightOf), bin.size, PaimonSplitRule.DATA_EVOLUTION)
    }
}

/**
 * `BinPacking.packForOrdered`: an item joins the open bin unless it would carry the bin past
 * [target] and the bin already holds something — so nothing is reordered, and an item past the
 * target on its own is a bin of one.
 */
internal fun <T> packForOrdered(items: List<T>, target: Long, weightOf: (T) -> Long): List<List<T>> {
    val packed = mutableListOf<List<T>>()
    var bin = mutableListOf<T>()
    var weight = 0L
    for (item in items) {
        val w = weightOf(item)
        if (weight + w > target && bin.isNotEmpty()) {
            packed += bin
            bin = mutableListOf()
            weight = 0L
        }
        weight += w
        bin += item
    }
    if (bin.isNotEmpty()) packed += bin
    return packed
}

/** One Spark input partition: the files it reads, and whether Spark repacked them or took a merge split as it was. */
data class PaimonSparkPartition(
    val files: List<PaimonLookupFile>,
    /** The bytes Spark charged: each file's size plus the open cost, plus its vector's length under deletion vectors. */
    val bytes: Long,
    /** True where the partition was packed from raw splits' files; false for a split that is not raw, taken whole. */
    val reshuffled: Boolean,
) {
    val buckets: Int get() = files.map { it.partition to it.bucket }.distinct().size
}

data class PaimonSparkPlan(
    val parallelism: Int,
    /** The bound a repacked partition fills to — `min(target, max(open cost, raw bytes / parallelism))`. */
    val maxSplitBytes: Long,
    /** `Σ(fileSize + openCost)` over the raw splits' files — what the bound is derived from; a vector's bytes are not in it. */
    val rawBytes: Long,
    val partitions: List<PaimonSparkPartition>,
)

/**
 * The input partitions Spark plans from the splits — `ScanHelper.getInputPartitions` at
 * release-1.3.1, the same in `paimon-spark-3.5` and `-4.0`. A split that is not raw-convertible
 * is a partition of its own. The raw ones are repacked from their files: the bound is
 * `min(source.split.target-size, max(source.split.open-file-cost, Σ(fileSize + openCost) over
 * the raw splits' files / minPartitionNum))`, `minPartitionNum` being `spark.sql.files.minPartitionNum`
 * or the leaf-node default parallelism (`spark.sql.leafNodeDefaultParallelism`, else
 * `sparkContext.defaultParallelism`) — so a large parallelism brings the bound down to the open
 * cost and every file is a partition, and a parallelism of one lets the bound hold everything.
 * Then the raw splits' files are walked in order, each charged `fileSize + openCost` **plus its
 * vector's length** under deletion vectors, a partition closing when the next file would carry
 * it past the bound. A vector's bytes are charged to the partition and not counted in the bound,
 * which is why a table with vectors takes one partition more than its bytes say at a parallelism
 * of one (`dv`, `ad`, `pid` at 1: two partitions of two files). `spark.sql.files.maxPartitionBytes`
 * and `.openCostInBytes` would replace the table's two options and are not read here.
 */
fun paimonSparkPartitions(plan: PaimonSplitPlan, parallelism: Int): PaimonSparkPlan {
    val openCost = plan.options.openFileCost
    val (raw, reserved) = plan.splits.partition { it.rawConvertible }
    val rawBytes = raw.sumOf { s -> s.files.sumOf { it.size() + openCost } }
    val minPartitionNum = maxOf(parallelism, 1)
    val maxSplitBytes = minOf(plan.options.targetSplitSize, maxOf(openCost, rawBytes / minPartitionNum))
    val partitions = mutableListOf<PaimonSparkPartition>()
    reserved.forEach { s -> partitions += PaimonSparkPartition(s.files, s.files.sumOf { it.size() + openCost + (plan.vectorLengths[it.fileName] ?: 0L) }, reshuffled = false) }
    var current = mutableListOf<PaimonLookupFile>()
    var size = 0L
    for (split in raw) {
        for (f in split.files) {
            val bytes = f.size() + openCost + (plan.vectorLengths[f.fileName] ?: 0L)
            if (size + bytes > maxSplitBytes && current.isNotEmpty()) {
                partitions += PaimonSparkPartition(current, size, reshuffled = true)
                current = mutableListOf()
                size = 0L
            }
            current += f
            size += bytes
        }
    }
    if (current.isNotEmpty()) partitions += PaimonSparkPartition(current, size, reshuffled = true)
    return PaimonSparkPlan(parallelism, maxSplitBytes, rawBytes, partitions)
}
