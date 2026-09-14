package model

/**
 * A primary-key bucket as the LSM tree Paimon's writer sees it, and what that writer's next
 * flush would compact — decided the way `UniversalCompaction.pick()` decides it, read at Paimon
 * 1.3.1 (`paimon-core/.../mergetree/compact/UniversalCompaction.java`) and checked against the
 * `pc` fixture, where the fifth of seven one-row inserts is the one Paimon compacted.
 *
 * The shape is `Levels.levelSortedRuns()`: every level-0 file is a sorted run of its own, newest
 * first by `maxSequenceNumber`, and each higher level that holds anything is one run. The
 * writer asks `pick()` on every flush:
 *
 * 1. Under `num-sorted-run.compaction-trigger` (5) runs, nothing.
 * 2. **Size amplification**, at the trigger or above: when the runs newer than the oldest add up
 *    to more than `compaction.max-size-amplification-percent` (200%) of the oldest, every run goes
 *    into the top level (`num-levels`, default trigger + 1, so level 5).
 * 3. **Size ratio**, otherwise: starting from the newest run, each next run within
 *    `compaction.size-ratio` (1%) of the runs gathered so far joins them; two or more gathered is a
 *    compaction into the level below the first run left out.
 * 4. **Run count**, above the trigger only: the newest `runs - trigger + 1` are taken whatever
 *    their sizes, and the size-ratio walk continues from there.
 *
 * A `changelog-producer = lookup`, `deletion-vectors.enabled`, `force-lookup` or `first-row` table
 * wraps that in `ForceUpLevel0Compaction`: when `pick()` finds nothing, every level-0 file is
 * pushed up anyway, which is why `dv` and `lk` compact after every append. `write-only = true`
 * turns compaction off; `compaction.force-up-level-0` forces level 0 up as lookup does. Past
 * `num-sorted-run.stop-trigger` (trigger + 3) runs the writer waits for the compaction before it
 * goes on.
 *
 * An append table (no primary key) has no levels and compacts only when `sys.compact` or a
 * compaction job runs; that side is [PaimonAppendCompactionVerdict].
 */
data class PaimonSortedRun(
    val level: Int,
    val files: List<PaimonDataFileMeta>,
) {
    val sizeBytes: Long get() = files.sumOf { it.fileSize ?: 0L }
}

/** A file's `_MIN_KEY` and `_MAX_KEY` as values — what `IntervalPartition` orders a compaction's files by. Null where not decoded. */
data class PaimonKeyRange(val min: List<Any?>?, val max: List<Any?>?)

data class PaimonBucketLsm(
    /** The decoded partition, "" when unpartitioned. */
    val partition: String,
    val bucket: Int,
    /** Newest first, as `Levels.levelSortedRuns()` orders them. */
    val runs: List<PaimonSortedRun>,
    /** By file name — for the full compaction's sections; see [planFullCompaction]. */
    val keyRanges: Map<String, PaimonKeyRange> = emptyMap(),
) {
    val sortedRunCount: Int get() = runs.size
    val fileCount: Int get() = runs.sumOf { it.files.size }
    val level0FileCount: Int get() = runs.count { it.level == 0 }

    /** `L0×3, L5×1` — the tree in one glance. */
    fun describeLevels(): String = runs.groupBy { it.level }.toSortedMap().entries
        .joinToString(", ") { (level, runsAt) -> "L$level×${runsAt.sumOf { it.files.size }}" }
}

/** What the table's options say about compacting a primary-key bucket. */
data class PaimonCompactionOptions(
    val trigger: Int,
    val stopTrigger: Int,
    val numLevels: Int,
    val maxSizeAmplificationPercent: Int,
    val sizeRatioPercent: Int,
    val writeOnly: Boolean,
    /** Lookup, deletion vectors, `force-lookup`, `first-row` or `compaction.force-up-level-0`. */
    val forceUpLevel0: Boolean,
) {
    companion object {
        fun from(tableOptions: Map<String, String>): PaimonCompactionOptions {
            val trigger = tableOptions["num-sorted-run.compaction-trigger"]?.toIntOrNull() ?: 5
            val stop = tableOptions["num-sorted-run.stop-trigger"]?.toIntOrNull() ?: (trigger + 3)
            val needLookup = tableOptions["changelog-producer"]?.lowercase() == "lookup" ||
                tableOptions["deletion-vectors.enabled"]?.toBoolean() == true ||
                tableOptions["force-lookup"]?.toBoolean() == true ||
                tableOptions["merge-engine"]?.lowercase() == "first-row"
            return PaimonCompactionOptions(
                trigger = trigger,
                stopTrigger = maxOf(trigger, stop),
                numLevels = tableOptions["num-levels"]?.toIntOrNull() ?: (trigger + 1),
                maxSizeAmplificationPercent = tableOptions["compaction.max-size-amplification-percent"]?.toIntOrNull() ?: 200,
                sizeRatioPercent = tableOptions["compaction.size-ratio"]?.toIntOrNull() ?: 1,
                writeOnly = tableOptions["write-only"]?.toBoolean() == true,
                forceUpLevel0 = needLookup || tableOptions["compaction.force-up-level-0"]?.toBoolean() == true,
            )
        }
    }
}

/** Why the next flush compacts, or why it does not. */
enum class PaimonCompactionRule(val label: String) {
    WRITE_ONLY("never — write-only"),
    UNDER_TRIGGER("not yet"),
    SIZE_AMPLIFICATION("size amplification"),
    SIZE_RATIO("size ratio"),
    RUN_COUNT("run count"),
    FORCE_UP_LEVEL_0("level 0 forced up on every flush"),
}

data class PaimonCompactionVerdict(
    val lsm: PaimonBucketLsm,
    val options: PaimonCompactionOptions,
    val rule: PaimonCompactionRule,
    /** The newest runs the compaction takes; empty when none. */
    val runsPicked: List<PaimonSortedRun>,
    /** Where their merge lands, null when nothing is picked. */
    val outputLevel: Int?,
) {
    val compacts: Boolean get() = runsPicked.isNotEmpty()
    val filesPicked: Int get() = runsPicked.sumOf { it.files.size }

    /** True past the stop trigger: the writer blocks on the compaction rather than flushing again. */
    val stalls: Boolean get() = lsm.sortedRunCount > options.stopTrigger

    /** `compacts 5 files in 5 runs into level 5 — size amplification` / `not yet — 3 of 5 runs`. */
    fun describe(): String = when (rule) {
        PaimonCompactionRule.WRITE_ONLY -> rule.label
        PaimonCompactionRule.UNDER_TRIGGER -> "${rule.label} — ${lsm.sortedRunCount} of ${options.trigger} runs"
        else -> "compacts $filesPicked ${if (filesPicked == 1) "file" else "files"} in ${runsPicked.size} " +
            "${if (runsPicked.size == 1) "run" else "runs"} into level $outputLevel — ${rule.label}" +
            (if (stalls) "; past the stop trigger (${options.stopTrigger}), the writer waits for it" else "")
    }
}

/**
 * The buckets of a snapshot's live files, each as the tree its writer would restore. Level-0
 * files are ordered newest first by `maxSequenceNumber`, then the tie-breaks `Levels` uses —
 * `minSequenceNumber`, creation time, file name — so two flushes of one commit sort as Paimon
 * sorts them.
 */
fun paimonBucketLsms(liveEntries: Collection<PaimonUnifiedDataFile>): List<PaimonBucketLsm> =
    liveEntries
        .groupBy { (it.partition?.display ?: "") to (it.metadata.bucket ?: 0) }
        .entries
        .sortedWith(compareBy({ it.key.first }, { it.key.second }))
        .map { (key, entries) ->
            val files = entries.mapNotNull { it.metadata.file }
            val level0 = files.filter { (it.level ?: 0) == 0 }.sortedWith(
                compareByDescending<PaimonDataFileMeta> { it.maxSequenceNumber ?: Long.MIN_VALUE }
                    .thenBy { it.minSequenceNumber ?: Long.MIN_VALUE }
                    .thenBy { it.creationTime ?: Long.MIN_VALUE }
                    .thenBy { it.fileName.orEmpty() },
            )
            val higher = files.filter { (it.level ?: 0) > 0 }.groupBy { it.level!! }.toSortedMap()
            PaimonBucketLsm(
                partition = key.first,
                bucket = key.second,
                runs = level0.map { PaimonSortedRun(0, listOf(it)) } +
                    higher.map { (level, atLevel) -> PaimonSortedRun(level, atLevel) },
                keyRanges = entries.mapNotNull { e ->
                    val name = e.metadata.file?.fileName ?: return@mapNotNull null
                    fun values(row: List<PaimonRowValue>?) = row?.takeIf { it.isNotEmpty() && it.all { v -> v.decoded } }?.map { it.value }
                    name to PaimonKeyRange(values(e.keyMin), values(e.keyMax))
                }.toMap(),
            )
        }

fun PaimonBucketLsm.planCompaction(options: PaimonCompactionOptions): PaimonCompactionVerdict {
    fun verdict(rule: PaimonCompactionRule, picked: List<PaimonSortedRun>, outputLevel: Int?) =
        PaimonCompactionVerdict(this, options, rule, picked, outputLevel)
    if (options.writeOnly) return verdict(PaimonCompactionRule.WRITE_ONLY, emptyList(), null)

    // `Levels` restores at least as many levels as the files use, so a file above num-levels
    // widens the tree rather than failing it.
    val numLevels = maxOf(options.numLevels, (runs.maxOfOrNull { it.level } ?: -1) + 1)
    val maxLevel = numLevels - 1
    // MergeTreeCompactManager drops a unit that would rewrite one file into the level it is in.
    fun worthDoing(picked: List<PaimonSortedRun>, output: Int) =
        picked.sumOf { it.files.size } > 1 || picked.first().level != output
    val universal = universalPick(runs, options, maxLevel)
    if (universal != null && worthDoing(universal.second, universal.third)) {
        return verdict(universal.first, universal.second, universal.third)
    }
    if (universal == null && options.forceUpLevel0) {
        val level0 = runs.takeWhile { it.level == 0 }
        if (level0.isNotEmpty()) {
            val (picked, output) = sizeRatioPick(runs, options, maxLevel, level0.size, forcePick = true)!!
            if (worthDoing(picked, output)) return verdict(PaimonCompactionRule.FORCE_UP_LEVEL_0, picked, output)
        }
    }
    return verdict(PaimonCompactionRule.UNDER_TRIGGER, emptyList(), null)
}

/** `UniversalCompaction.pick()` without the full-compaction-by-interval and off-peak branches, which need a clock and a schedule. */
private fun universalPick(
    runs: List<PaimonSortedRun>,
    options: PaimonCompactionOptions,
    maxLevel: Int,
): Triple<PaimonCompactionRule, List<PaimonSortedRun>, Int>? {
    if (runs.size < options.trigger) return null
    // Size amplification: everything newer than the oldest run, against the oldest.
    val candidateSize = runs.dropLast(1).sumOf { it.sizeBytes }
    val earliestSize = runs.last().sizeBytes
    if (candidateSize * 100 > options.maxSizeAmplificationPercent.toLong() * earliestSize) {
        return Triple(PaimonCompactionRule.SIZE_AMPLIFICATION, runs, maxLevel)
    }
    sizeRatioPick(runs, options, maxLevel, candidateCount = 1, forcePick = false)?.let { (picked, output) ->
        return Triple(PaimonCompactionRule.SIZE_RATIO, picked, output)
    }
    if (runs.size > options.trigger) {
        val forced = runs.size - options.trigger + 1
        sizeRatioPick(runs, options, maxLevel, candidateCount = forced, forcePick = false)?.let { (picked, output) ->
            return Triple(PaimonCompactionRule.RUN_COUNT, picked, output)
        }
    }
    return null
}

/** `UniversalCompaction.pickForSizeRatio` followed by `createUnit`: the runs taken and the level they land in. */
private fun sizeRatioPick(
    runs: List<PaimonSortedRun>,
    options: PaimonCompactionOptions,
    maxLevel: Int,
    candidateCount: Int,
    forcePick: Boolean,
): Pair<List<PaimonSortedRun>, Int>? {
    var count = candidateCount
    var candidateSize = runs.take(count).sumOf { it.sizeBytes }
    while (count < runs.size) {
        val next = runs[count]
        if (candidateSize * (100.0 + options.sizeRatioPercent) / 100.0 < next.sizeBytes) break
        candidateSize += next.sizeBytes
        count++
    }
    if (!forcePick && count <= 1) return null
    // createUnit: the level below the first run left out; never level 0, so a level-0 leftover
    // is swept in until a higher run is met; taking every run lands in the top level.
    var outputLevel = if (count == runs.size) maxLevel else maxOf(0, runs[count].level - 1)
    if (outputLevel == 0) {
        while (count < runs.size) {
            val next = runs[count]
            count++
            if (next.level != 0) {
                outputLevel = next.level
                break
            }
        }
    }
    if (count == runs.size) outputLevel = maxLevel
    return runs.take(count) to outputLevel
}

/**
 * An append table's side: `AppendCompactCoordinator` packs the files under
 * `compactionFileSize` (7/10 of `target-file-size`, 256 MB by default) per partition, and a pack
 * is a task once it holds `compaction.min.file-num` (5) files or twice the target in bytes.
 * Nothing runs it on a Spark write; `sys.compact` or a compaction job does.
 */
data class PaimonAppendCompactionVerdict(
    val partition: String,
    val fileCount: Int,
    val smallFileCount: Int,
    val smallFileBytes: Long,
    /** `target-file-size`, 256 MB by default; a file under 7/10 of it is small. */
    val targetBytes: Long,
    val compactionFileSizeBytes: Long,
    val minFileNum: Int,
) {
    /** A pack is a task at `compaction.min.file-num` files, or sooner once it holds twice the target in bytes. */
    val wouldPack: Boolean get() = smallFileCount >= minFileNum || (smallFileCount > 1 && smallFileBytes >= targetBytes * 2)

    fun describe(): String = when {
        smallFileCount == 0 -> "nothing under the threshold"
        wouldPack -> "sys.compact would merge $smallFileCount small files"
        else -> "$smallFileCount of $minFileNum small files — sys.compact would leave them"
    }
}

fun paimonAppendCompaction(liveEntries: Collection<PaimonUnifiedDataFile>, tableOptions: Map<String, String>): List<PaimonAppendCompactionVerdict> =
    liveEntries.groupBy { it.partition?.display ?: "" }.entries.sortedBy { it.key }.map { (partition, entries) ->
        paimonAppendVerdict(partition, entries.mapNotNull { it.metadata.file }, tableOptions)
    }

/** One partition's verdict from its files — what [paimonAppendCompaction] folds per partition. */
fun paimonAppendVerdict(partition: String, files: List<PaimonDataFileMeta>, tableOptions: Map<String, String>): PaimonAppendCompactionVerdict {
    val target = tableOptions["target-file-size"]?.let { parsePaimonMemoryBytes(it) } ?: (256L shl 20)
    val threshold = target / 10 * 7
    val small = files.mapNotNull { it.fileSize }.filter { it < threshold }
    return PaimonAppendCompactionVerdict(
        partition = partition,
        fileCount = files.size,
        smallFileCount = small.size,
        smallFileBytes = small.sum(),
        targetBytes = target,
        compactionFileSizeBytes = threshold,
        minFileNum = tableOptions["compaction.min.file-num"]?.toIntOrNull() ?: 5,
    )
}

/** Paimon's `MemorySize.parse`: digits and an optional unit — `b`, `kb`/`k`, `mb`/`m`, `gb`/`g`, `tb`/`t`, binary. Null when it would reject. */
fun parsePaimonMemoryBytes(text: String): Long? {
    val trimmed = text.trim().lowercase()
    val digits = trimmed.takeWhile { it.isDigit() }
    val value = digits.toLongOrNull() ?: return null
    val unit = when (trimmed.substring(digits.length).trim()) {
        "", "b", "bytes" -> 1L
        "k", "kb", "kibibytes" -> 1L shl 10
        "m", "mb", "mebibytes" -> 1L shl 20
        "g", "gb", "gibibytes" -> 1L shl 30
        "t", "tb", "tebibytes" -> 1L shl 40
        else -> return null
    }
    return value * unit
}
