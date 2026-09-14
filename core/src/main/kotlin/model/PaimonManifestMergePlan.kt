package model

/**
 * What the next Paimon commit does to the base manifest list — `ManifestFileMerger.merge` at
 * release-1.3.1, run by `FileStoreCommitImpl.tryCommitOnce` on **every** commit over the previous
 * snapshot's base and delta manifests, in that order.
 *
 * Two compactions, tried in this order:
 *
 * - **Full**, when the manifests that *must change* — any holding a `DELETE` entry, or smaller
 *   than `manifest.target-file-size` (8 MB) — sum to `manifest.full-compaction-threshold-size`
 *   (16 MB) or more: every `DELETE` entry is read, a manifest that need not change and whose
 *   partition range holds none of the deleted partitions is kept, and the rest are rewritten
 *   into fresh manifests with the `DELETE` entries and the `ADD` entries they cancel dropped.
 *   Nothing happens when that leaves one manifest or none to rewrite.
 * - **Minor**, otherwise: the manifests in list order fill a bin until the bin's bytes reach the
 *   target size, which closes it and merges it; the leftover bin is merged only when it holds
 *   `manifest.merge-min-count` (30) manifests or more. A bin of one is kept as it is.
 *
 * A merge folds the entries through `FileEntry.mergeEntries`: an `ADD` is put, a `DELETE` removes
 * the `ADD` of the same identifier when the bin holds it and is kept otherwise, since the `ADD` is
 * then in an earlier manifest — so a merged manifest can hold fewer entries than its inputs, and
 * none at all, in which case no file is written. The identifier is partition, bucket, level, file
 * name, extra files and external path, so a level upgrade's `DELETE` at level 0 and `ADD` at level
 * 5 of one name are two identifiers.
 *
 * On a small table neither size is ever reached, so the count is the rule a reader sees — and the
 * one `pmm` pins, at `manifest.merge-min-count = 5`. The full compaction's partition skip and the
 * bin that closes on size are read from the source and exercised by no fixture.
 */
data class PaimonManifestMergeOptions(
    val targetSizeBytes: Long = PAIMON_MANIFEST_TARGET_SIZE_BYTES,
    val mergeMinCount: Int = PAIMON_MANIFEST_MERGE_MIN_COUNT,
    val fullCompactionThresholdBytes: Long = PAIMON_MANIFEST_FULL_COMPACTION_BYTES,
) {
    companion object {
        fun forTable(options: Map<String, String>) = PaimonManifestMergeOptions(
            targetSizeBytes = options["manifest.target-file-size"]?.let(::parsePaimonMemorySize) ?: PAIMON_MANIFEST_TARGET_SIZE_BYTES,
            mergeMinCount = options["manifest.merge-min-count"]?.toIntOrNull() ?: PAIMON_MANIFEST_MERGE_MIN_COUNT,
            fullCompactionThresholdBytes = options["manifest.full-compaction-threshold-size"]?.let(::parsePaimonMemorySize) ?: PAIMON_MANIFEST_FULL_COMPACTION_BYTES,
        )
    }
}

const val PAIMON_MANIFEST_TARGET_SIZE_BYTES: Long = 8L * 1024 * 1024
const val PAIMON_MANIFEST_MERGE_MIN_COUNT: Int = 30
const val PAIMON_MANIFEST_FULL_COMPACTION_BYTES: Long = 16L * 1024 * 1024

/** Paimon's `MemorySize.parse`: a number with an optional unit — `b`, `k`/`kb`, `m`/`mb`, `g`/`gb`, `t`/`tb`. */
fun parsePaimonMemorySize(text: String): Long? {
    val t = text.trim().lowercase()
    val digits = t.takeWhile { it.isDigit() }
    val number = digits.toLongOrNull() ?: return null
    val unit = t.drop(digits.length).trim()
    val factor = when (unit) {
        "", "b", "bytes" -> 1L
        "k", "kb", "kibibytes" -> 1L shl 10
        "m", "mb", "mebibytes" -> 1L shl 20
        "g", "gb", "gibibytes" -> 1L shl 30
        "t", "tb", "tebibytes" -> 1L shl 40
        else -> return null
    }
    return number * factor
}

enum class PaimonManifestMergeKind { NONE, MINOR, FULL }

/** One manifest as the merger sees it. */
data class PaimonManifestMergeInput(
    val name: String,
    val sizeBytes: Long,
    val addedFiles: Long,
    val deletedFiles: Long,
    val entries: List<PaimonMergeEntry>,
) {
    /** `mustChange` in `tryFullCompaction`: holds a `DELETE`, or is under the target size. */
    fun mustChange(options: PaimonManifestMergeOptions): Boolean = deletedFiles > 0 || sizeBytes < options.targetSizeBytes
}

/** An entry by the identifier `FileEntry.mergeEntries` folds on, its partition, and its kind. */
data class PaimonMergeEntry(val identifier: String, val partition: String, val delete: Boolean)

data class PaimonManifestMergeBin(
    val manifests: List<PaimonManifestMergeInput>,
    /** Whether the bin is merged; a bin of one is kept as it is. */
    val merged: Boolean,
    /** Why the bin closed — reached the target size, held the minimum count, or is the leftover kept as it is. */
    val reason: String,
    /** The entries a merge writes, after cancelling; null for a bin kept as it is. */
    val mergedEntries: Int?,
) {
    val bytes: Long get() = manifests.sumOf { it.sizeBytes }

    /** Manifests in the next base list from this bin: the inputs when kept, one when merged into something, none when merged into nothing. */
    val outputCount: Int get() = when {
        !merged -> manifests.size
        mergedEntries == 0 -> 0
        else -> 1
    }
}

data class PaimonManifestMergePlan(
    val options: PaimonManifestMergeOptions,
    val input: List<PaimonManifestMergeInput>,
    val kind: PaimonManifestMergeKind,
    val bins: List<PaimonManifestMergeBin>,
    /** The full compaction's figure against its threshold: the bytes of the manifests that must change. */
    val mustChangeBytes: Long,
) {
    val mergedBins: List<PaimonManifestMergeBin> get() = bins.filter { it.merged }
    val mergedManifests: Int get() = mergedBins.sumOf { it.manifests.size }

    /** The names that survive into the next base list unchanged. */
    val kept: List<String> get() = bins.filterNot { it.merged }.flatMap { b -> b.manifests.map { it.name } }

    /** How many manifests the next base list holds, counting one per merged bin that writes anything. */
    val outputCount: Int get() = bins.sumOf { it.outputCount }

    val describe: String
        get() = when (kind) {
            PaimonManifestMergeKind.NONE -> when {
                input.isEmpty() -> "no manifest to merge"
                else -> "${input.size} kept: the leftover bin holds fewer than merge-min-count (${options.mergeMinCount}) and no bin reaches the target size"
            }
            PaimonManifestMergeKind.MINOR -> "$mergedManifests of ${input.size} merged into ${mergedBins.sumOf { it.outputCount }}, ${kept.size} kept; the base list goes from ${input.size} to $outputCount"
            PaimonManifestMergeKind.FULL -> "a full compaction: ${mergedManifests} of ${input.size} rewritten, ${kept.size} kept whole"
        }
}

/**
 * Plans the next commit's base manifest list from [input] — the previous snapshot's base
 * manifests then its delta manifests, the order `readDataManifests` returns them in.
 */
fun planPaimonManifestMerge(input: List<PaimonManifestMergeInput>, options: PaimonManifestMergeOptions): PaimonManifestMergePlan {
    val mustChangeBytes = input.filter { it.mustChange(options) }.sumOf { it.sizeBytes }
    if (input.isNotEmpty() && mustChangeBytes >= options.fullCompactionThresholdBytes) {
        planFullCompaction(input, options, mustChangeBytes)?.let { return it }
    }
    // Minor: bins close on size in list order; the leftover merges only on count.
    val bins = mutableListOf<PaimonManifestMergeBin>()
    var candidates = mutableListOf<PaimonManifestMergeInput>()
    var total = 0L
    for (m in input) {
        total += m.sizeBytes
        candidates += m
        if (total >= options.targetSizeBytes) {
            bins += binOf(candidates, "the bin reached manifest.target-file-size (${options.targetSizeBytes} B)")
            candidates = mutableListOf()
            total = 0
        }
    }
    if (candidates.isNotEmpty()) {
        bins += if (candidates.size >= options.mergeMinCount) {
            binOf(candidates, "the leftover bin holds ${candidates.size}, at or past manifest.merge-min-count (${options.mergeMinCount})")
        } else {
            PaimonManifestMergeBin(candidates, merged = false, reason = "the leftover bin holds ${candidates.size}, under merge-min-count (${options.mergeMinCount})", mergedEntries = null)
        }
    }
    val kind = if (bins.any { it.merged }) PaimonManifestMergeKind.MINOR else PaimonManifestMergeKind.NONE
    return PaimonManifestMergePlan(options, input, kind, bins, mustChangeBytes)
}

/** `mergeCandidates`: a bin of one is kept as it is; otherwise the entries fold and what is left is written. */
private fun binOf(candidates: List<PaimonManifestMergeInput>, reason: String): PaimonManifestMergeBin =
    if (candidates.size == 1) {
        PaimonManifestMergeBin(candidates, merged = false, reason = "a bin of one is kept as it is", mergedEntries = null)
    } else {
        PaimonManifestMergeBin(candidates, merged = true, reason = reason, mergedEntries = foldEntries(candidates.flatMap { it.entries }))
    }

/** `FileEntry.mergeEntries`: an ADD is put; a DELETE removes the ADD it meets, and stays otherwise. */
internal fun foldEntries(entries: List<PaimonMergeEntry>): Int {
    val map = linkedMapOf<String, Boolean>()
    for (e in entries) {
        if (!e.delete) {
            map[e.identifier] = false
        } else if (map.containsKey(e.identifier)) {
            map.remove(e.identifier)
        } else {
            map[e.identifier] = true
        }
    }
    return map.size
}

/**
 * `tryFullCompaction` past the threshold: every DELETE identifier is collected; a manifest that
 * need not change is set aside first when its partition range holds none of the deleted
 * partitions (read here off its entries' partitions, the range's contents), and the rest are
 * candidates — one or none of them is no compaction, and the minor rule runs instead. Of the
 * candidates, one is rewritten when it must change or holds a deleted identifier, with the
 * DELETEs and the ADDs they cancel dropped, and kept whole otherwise.
 */
private fun planFullCompaction(input: List<PaimonManifestMergeInput>, options: PaimonManifestMergeOptions, mustChangeBytes: Long): PaimonManifestMergePlan? {
    val deleted = input.flatMap { m -> m.entries.filter { it.delete }.map { it.identifier } }.toSet()
    val deletedPartitions = input.flatMap { m -> m.entries.filter { it.delete }.map { it.partition } }.toSet()
    val candidates = input.filter { m -> m.mustChange(options) || m.entries.any { it.partition in deletedPartitions } }
    if (candidates.size <= 1) return null
    val kept = mutableListOf<PaimonManifestMergeInput>()
    val toMerge = mutableListOf<PaimonManifestMergeInput>()
    for (m in input) {
        val rewritten = m in candidates && (m.mustChange(options) || m.entries.any { !it.delete && it.identifier in deleted })
        if (rewritten) toMerge += m else kept += m
    }
    val survivors = toMerge.flatMap { m -> m.entries.filter { !it.delete && it.identifier !in deleted } }
    val bins = kept.map { PaimonManifestMergeBin(listOf(it), merged = false, reason = "holds no DELETE, is at the target size, and none of its files is deleted", mergedEntries = null) } +
        PaimonManifestMergeBin(toMerge, merged = true, reason = "the manifests that must change sum to $mustChangeBytes B, at or past manifest.full-compaction-threshold-size (${options.fullCompactionThresholdBytes} B)", mergedEntries = survivors.size)
    return PaimonManifestMergePlan(options, input, PaimonManifestMergeKind.FULL, bins, mustChangeBytes)
}

/** The merger's input for a snapshot: its base manifests then its delta manifests, as `readDataManifests` lists them. */
fun PaimonUnifiedSnapshot.manifestMergeInput(): List<PaimonManifestMergeInput> =
    (baseManifests + deltaManifests).map { m ->
        PaimonManifestMergeInput(
            name = paimonManifestKey(m),
            sizeBytes = m.metadata.fileSize ?: m.sizeOnDisk ?: 0L,
            addedFiles = m.metadata.numAddedFiles ?: m.entries.count { it.metadata.kind != PaimonEntryKind.DELETE }.toLong(),
            deletedFiles = m.metadata.numDeletedFiles ?: m.entries.count { it.metadata.kind == PaimonEntryKind.DELETE }.toLong(),
            entries = m.entries.map { e ->
                val f = e.metadata.file
                val partition = e.metadata.partition?.joinToString("") { "%02x".format(it) } ?: ""
                PaimonMergeEntry(
                    partition = partition,
                    identifier = listOf(
                        partition,
                        e.metadata.bucket?.toString() ?: "",
                        f?.level?.toString() ?: "",
                        f?.fileName ?: e.path.fileName.toString(),
                        f?.extraFiles?.joinToString(",") ?: "",
                        f?.externalPath ?: "",
                    ).joinToString("|"),
                    delete = e.metadata.kind == PaimonEntryKind.DELETE,
                )
            },
        )
    }
