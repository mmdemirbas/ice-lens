package model

/**
 * What `sys.compact(table)` does to a primary-key bucket — `compact_strategy => 'full'`, the
 * default outside incremental clustering — read at release-1.3.1. `CompactProcedure.compactAwareBucketTable`
 * calls `write.compact(partition, bucket, fullCompaction = true)` for every bucket the
 * partition filter keeps, which is `MergeTreeCompactManager.triggerCompaction(true)`:
 *
 * - `CompactStrategy.pickFullCompaction`: no run is nothing; **one run at the top level** is
 *   nothing too, unless a file carries a deletion vector, holds expired records under
 *   `record-level.expire-time`, or `compaction.force-rewrite-all-files` is set — those files are
 *   rewritten one by one in place (`FileRewriteCompactTask`), the vector applied; otherwise every
 *   run goes into one unit whose output is the top level.
 * - `MergeTreeCompactTask` cuts the unit's files into sections of intersecting key ranges
 *   (`IntervalPartition`) and walks them in key order: a section of several files is rewritten;
 *   a lone file under `compaction.file-size` (7/10 of `target-file-size`, 128 MiB on a
 *   primary-key table) joins the pending rewrite; a lone file at or over it flushes the pending
 *   rewrite and is **upgraded** — renamed to the top level without a rewrite, a `DELETE` at its
 *   level and an `ADD` of the same name at the top — unless it holds `-D` rows (`_DELETE_ROW_COUNT`
 *   above zero, or unrecorded), is force-rewritten or holds expired records, when it is rewritten
 *   alone; a pending rewrite of one lone file is an upgrade too. A file already at the top level
 *   and alone in its key range is left as it is. `dropDelete` is true for a full compaction, so
 *   every rewrite drops the `-D` markers.
 * - Under `changelog-producer = lookup` an upgraded level-0 file is read to emit its changelog
 *   (`CHANGELOG_NO_REWRITE`); under `full-compaction` the whole rewrite emits one.
 *
 * `compact_strategy => 'minor'` is `triggerCompaction(false)`, the writer's own pick — see
 * [planCompaction]. `psl`'s one file was upgraded and `se`'s two overlapping files rewritten into
 * one; `docs/fixtures/paimon-compact.sql` records the runs.
 */
enum class PaimonFullCompactionAction(val label: String) {
    /** Merged with the rest of its group into new files at the top level; `-D` rows dropped. */
    REWRITE("REWRITE"),
    /** Rewritten alone at its own level, the top: its deletion vector applied, expired rows dropped. */
    REWRITE_IN_PLACE("REWRITE in place"),
    /** Renamed to the top level, not rewritten. */
    UPGRADE("UPGRADE"),
    /** Already at the top level and alone in its key range. */
    KEEP("kept"),
}

enum class PaimonFullCompactionRule(val label: String) {
    EMPTY("no file"),
    FULLY_COMPACTED("already fully compacted"),
    VECTORS_APPLIED("one run at the top; vectored files rewritten"),
    FULL("full compaction"),
}

data class PaimonFullCompactionFile(
    val file: PaimonDataFileMeta,
    val action: PaimonFullCompactionAction,
    /** The rewrite group the file merges into — one output run per group; null for an upgrade or a keep. */
    val group: Int?,
    val reason: String,
)

data class PaimonFullCompactionOptions(
    val numLevels: Int,
    /** `compaction.file-size`: `target-file-size` × 7 / 10, the size a lone file has to reach to be upgraded rather than joined to a rewrite. */
    val minFileSizeBytes: Long,
    val forceRewriteAllFiles: Boolean,
    /** `record-level.expire-time` set: files holding expired records are rewritten too, which this does not evaluate. */
    val recordLevelExpire: Boolean,
    val changelogProducer: String,
    val deletionVectors: Boolean,
) {
    companion object {
        fun from(tableOptions: Map<String, String>): PaimonFullCompactionOptions {
            val target = tableOptions["target-file-size"]?.let { parsePaimonMemoryBytes(it) } ?: (128L shl 20)
            return PaimonFullCompactionOptions(
                numLevels = PaimonCompactionOptions.from(tableOptions).numLevels,
                minFileSizeBytes = target / 10 * 7,
                forceRewriteAllFiles = tableOptions["compaction.force-rewrite-all-files"]?.toBoolean() == true,
                recordLevelExpire = !tableOptions["record-level.expire-time"].isNullOrBlank(),
                changelogProducer = (tableOptions["changelog-producer"] ?: "none").lowercase(),
                deletionVectors = tableOptions["deletion-vectors.enabled"]?.toBoolean() == true,
            )
        }
    }
}

data class PaimonFullCompactionVerdict(
    val lsm: PaimonBucketLsm,
    val options: PaimonFullCompactionOptions,
    val rule: PaimonFullCompactionRule,
    val outputLevel: Int,
    /** Every file of the bucket, in the order the task walks them. */
    val files: List<PaimonFullCompactionFile>,
) {
    val rewritten: List<PaimonFullCompactionFile> get() = files.filter { it.action == PaimonFullCompactionAction.REWRITE || it.action == PaimonFullCompactionAction.REWRITE_IN_PLACE }
    val upgraded: List<PaimonFullCompactionFile> get() = files.filter { it.action == PaimonFullCompactionAction.UPGRADE }
    val kept: List<PaimonFullCompactionFile> get() = files.filter { it.action == PaimonFullCompactionAction.KEEP }
    val groups: Int get() = files.mapNotNull { it.group }.distinct().size
    val compacts: Boolean get() = rewritten.isNotEmpty() || upgraded.isNotEmpty()
    /**
     * The `-D` markers the rewrites drop — the rewritten files' `_DELETE_ROW_COUNT`s. The rows
     * they remove, the rows a vector marks and the older versions the merge folds go with them,
     * and that count is a read ([service.PaimonMergedCount]), not a figure the manifests hold.
     */
    val deleteRowsDropped: Long get() = rewritten.sumOf { it.file.deleteRowCount ?: 0L }

    fun describe(): String = when {
        rule == PaimonFullCompactionRule.EMPTY -> rule.label
        !compacts -> "nothing: ${rule.label}"
        else -> listOfNotNull(
            rewritten.takeIf { it.isNotEmpty() }?.let { "rewrites ${it.size} ${if (it.size == 1) "file" else "files"} in ${groups} ${if (groups == 1) "group" else "groups"}" },
            upgraded.takeIf { it.isNotEmpty() }?.let { "upgrades ${it.size}" },
            kept.takeIf { it.isNotEmpty() }?.let { "keeps ${it.size}" },
        ).joinToString(", ") + " at level $outputLevel — ${rule.label}"
    }
}

/**
 * The full compaction of this bucket. [vectored] names the files the snapshot's index manifest
 * holds a deletion vector for — the one input the manifests do not carry.
 */
fun PaimonBucketLsm.planFullCompaction(options: PaimonFullCompactionOptions, vectored: Set<String> = emptySet()): PaimonFullCompactionVerdict {
    val numLevels = maxOf(options.numLevels, (runs.maxOfOrNull { it.level } ?: -1) + 1)
    val maxLevel = numLevels - 1
    fun verdict(rule: PaimonFullCompactionRule, files: List<PaimonFullCompactionFile>) = PaimonFullCompactionVerdict(this, options, rule, maxLevel, files)
    if (runs.isEmpty()) return verdict(PaimonFullCompactionRule.EMPTY, emptyList())

    if (runs.size == 1 && runs[0].level == maxLevel) {
        // Only top-level files: nothing, but the files a vector marks, the force option, or
        // expired records — each rewritten on its own at the level it is at.
        val files = runs[0].files.map { f ->
            val why = when {
                options.forceRewriteAllFiles -> "compaction.force-rewrite-all-files"
                f.fileName in vectored -> "its deletion vector is applied"
                else -> null
            }
            if (why != null) PaimonFullCompactionFile(f, PaimonFullCompactionAction.REWRITE_IN_PLACE, null, why)
            else PaimonFullCompactionFile(f, PaimonFullCompactionAction.KEEP, null, "at the top level, no vector" + (if (options.recordLevelExpire) "; expired records not evaluated" else ""))
        }
        val rule = if (files.any { it.action != PaimonFullCompactionAction.KEEP }) PaimonFullCompactionRule.VECTORS_APPLIED else PaimonFullCompactionRule.FULLY_COMPACTED
        return verdict(rule, files)
    }

    // Every run into one unit at the top level, cut into sections of intersecting key ranges.
    val all = runs.flatMap { it.files }
    val sections = paimonIntervalSections(all, { keyRanges[it.fileName]?.min }, { keyRanges[it.fileName]?.max })
    val out = mutableListOf<PaimonFullCompactionFile>()
    var group = 0
    val pending = mutableListOf<List<PaimonDataFileMeta>>()
    fun containsDeleteRecords(f: PaimonDataFileMeta) = (f.deleteRowCount ?: 1L) > 0L
    fun upgrade(f: PaimonDataFileMeta) {
        val rewriteWhy = when {
            containsDeleteRecords(f) -> if (f.deleteRowCount == null) "no _DELETE_ROW_COUNT recorded, so read as holding -D rows" else "holds ${f.deleteRowCount} -D ${if (f.deleteRowCount == 1L) "row" else "rows"} the top level drops"
            options.forceRewriteAllFiles -> "compaction.force-rewrite-all-files"
            else -> null
        }
        when {
            rewriteWhy != null -> out += PaimonFullCompactionFile(f, PaimonFullCompactionAction.REWRITE, ++group, "alone in its key range; rewritten because it $rewriteWhy")
            (f.level ?: 0) != maxLevel -> out += PaimonFullCompactionFile(
                f, PaimonFullCompactionAction.UPGRADE, null,
                "alone in its key range, no -D row: renamed from level ${f.level ?: 0}" +
                    (if (options.changelogProducer == "lookup" && (f.level ?: 0) == 0) ", read once to emit its changelog" else "") +
                    (if (f.fileName in vectored) "; its deletion vector stays with the name" else ""),
            )
            else -> out += PaimonFullCompactionFile(f, PaimonFullCompactionAction.KEEP, null, "alone in its key range and at the top level already")
        }
    }
    fun flush() {
        if (pending.isEmpty()) return
        if (pending.size == 1 && pending[0].size == 1) {
            upgrade(pending[0][0])
        } else {
            val g = ++group
            val overlapping = pending.count { it.size > 1 }
            val small = pending.count { it.size == 1 }
            val why = listOfNotNull(
                overlapping.takeIf { it > 0 }?.let { "$it ${if (it == 1) "section" else "sections"} of intersecting key ranges" },
                small.takeIf { it > 0 }?.let { "$it lone ${if (it == 1) "file" else "files"} under compaction.file-size" },
            ).joinToString(" and ")
            pending.flatten().forEach { f -> out += PaimonFullCompactionFile(f, PaimonFullCompactionAction.REWRITE, g, "merged with the rest of group $g: $why") }
        }
        pending.clear()
    }
    for (section in sections) {
        if (section.size > 1) {
            pending += section
        } else {
            val f = section[0]
            if ((f.fileSize ?: 0L) < options.minFileSizeBytes) {
                pending += section
            } else {
                flush()
                upgrade(f)
            }
        }
    }
    flush()
    return verdict(PaimonFullCompactionRule.FULL, out)
}
