package model

/**
 * What the next commit would do to a snapshot's manifest list, decided the way Iceberg's
 * `ManifestMergeManager` decides it — read at Iceberg 1.8.1, the runtime the fixtures were
 * written with — and checked against the three tables `docs/fixtures/merged.sql` wrote with
 * `commit.manifest.min-count-to-merge` lowered to two.
 *
 * Every batch write Spark makes commits through `MergingSnapshotProducer`, whose `apply` runs the
 * merge on both halves of the list, data manifests and delete manifests separately:
 *
 * 1. The manifests about to be listed are the ones this commit **wrote** — new files, or an
 *    existing manifest the filter rewrote — followed by the ones it kept, in list order; a kept
 *    manifest with neither added nor existing files is dropped first. The **first** of them is the
 *    commit's own new manifest when it has one, else the newest existing one.
 * 2. They are grouped by partition spec, and each group is bin-packed **from the oldest end** into
 *    `commit.manifest.target-size-bytes` (8 MB) bins with a lookback of one, so the under-filled
 *    bin is the newest one — the one holding the first manifest.
 * 3. A bin of one manifest is kept. A bin holding the first manifest is kept while it has fewer
 *    than `commit.manifest.min-count-to-merge` (100) manifests. **Any other bin of two or more is
 *    merged**, whatever the count — so a spec change merges the old spec's manifests at the next
 *    commit, and old bins that already fill the target size merge at once.
 * 4. `commit.manifest-merge.enabled = false` returns the list untouched.
 *
 * The merged manifest carries the bin's entries with earlier snapshots' `ADDED` rewritten as
 * `EXISTING` and their `DELETED` entries dropped; only this commit's own stay as they are.
 */
data class ManifestMergeOptions(
    /** `commit.manifest.target-size-bytes` (8 MB). */
    val targetSizeBytes: Long = DEFAULT_TARGET_SIZE_BYTES,
    /** `commit.manifest.min-count-to-merge` (100). */
    val minCountToMerge: Int = DEFAULT_MIN_COUNT_TO_MERGE,
    /** `commit.manifest-merge.enabled` (true). */
    val enabled: Boolean = true,
) {
    companion object {
        const val DEFAULT_TARGET_SIZE_BYTES = 8L * 1024 * 1024
        const val DEFAULT_MIN_COUNT_TO_MERGE = 100

        fun forTable(properties: Map<String, String>) = ManifestMergeOptions(
            targetSizeBytes = properties["commit.manifest.target-size-bytes"]?.toLongOrNull() ?: DEFAULT_TARGET_SIZE_BYTES,
            minCountToMerge = properties["commit.manifest.min-count-to-merge"]?.toIntOrNull() ?: DEFAULT_MIN_COUNT_TO_MERGE,
            enabled = properties["commit.manifest-merge.enabled"]?.toBooleanStrictOrNull() ?: true,
        )
    }
}

/** One manifest as the merge sees it: its spec, its length, and — for the one the next commit writes — no entry. */
data class ManifestMergeItem(
    /** The manifest list entry, or null for the manifest the next commit is assumed to write. */
    val entry: ManifestListEntry?,
    val specId: Int,
    val lengthBytes: Long,
) {
    val isNew: Boolean get() = entry == null
}

enum class ManifestMergeVerdict(val label: String) {
    ALONE("kept — one manifest in its bin"),
    UNDER_MIN_COUNT("kept — holds the new manifest, under min-count-to-merge"),
    MERGED("MERGED into one"),
}

data class ManifestMergeBin(
    val specId: Int,
    /** Newest first, as the manifest list would carry them. */
    val manifests: List<ManifestMergeItem>,
    val holdsFirst: Boolean,
    val verdict: ManifestMergeVerdict,
) {
    val merged: Boolean get() = verdict == ManifestMergeVerdict.MERGED
    val bytes: Long get() = manifests.sumOf { it.lengthBytes }
    /** How many manifests the bin becomes in the next list. */
    val outputCount: Int get() = if (merged) 1 else manifests.size
}

data class ManifestMergePlan(
    val options: ManifestMergeOptions,
    /** [ManifestContent.DATA] or [ManifestContent.DELETES] — the two halves merge separately. */
    val content: Int,
    /** What went in: the next commit's manifest, if assumed, then the snapshot's, in list order. */
    val input: List<ManifestMergeItem>,
    /** Empty when merging is disabled — the list is returned as it is, without grouping. */
    val bins: List<ManifestMergeBin>,
) {
    val mergedBins: List<ManifestMergeBin> get() = bins.filter { it.merged }
    /** How many manifests the next commit's list would carry for this content. */
    val outputCount: Int get() = if (options.enabled) bins.sumOf { it.outputCount } else input.size
    /** One line for a title: `off`, `nothing merges`, or `8 of 11 merge into 1`. */
    val describe: String get() = when {
        !options.enabled -> "off"
        mergedBins.isEmpty() -> "nothing merges"
        else -> "${mergedBins.sumOf { it.manifests.size }} of ${input.size} merge into ${mergedBins.size}"
    }
}

/**
 * Plans the merge over one content half of a snapshot's manifest list.
 *
 * @param listed the snapshot's manifests of that content, in list order (newest first, as
 *   `MergingSnapshotProducer` lists them), before the next commit
 * @param newManifestBytes the length assumed for the manifest the next commit writes, or null for
 *   a commit that writes none of this content — a row-level delete writes no data manifest
 */
fun planManifestMerge(
    listed: List<ManifestListEntry>,
    content: Int,
    newManifestBytes: Long?,
    newManifestSpecId: Int?,
    options: ManifestMergeOptions,
): ManifestMergePlan {
    // `shouldKeep`: a manifest with no live files is dropped unless this commit wrote it; a null
    // count reads as "has some", which is `ManifestFile.hasAddedFiles`' own rule.
    val kept = listed
        .filter { (it.content ?: ManifestContent.DATA) == content }
        .filter { (it.addedFilesCount ?: 1) > 0 || (it.existingFilesCount ?: 1) > 0 }
        .map { ManifestMergeItem(it, it.partitionSpecId ?: 0, it.manifestLength ?: 0L) }
    val fresh = newManifestBytes?.let { ManifestMergeItem(null, newManifestSpecId ?: kept.firstOrNull()?.specId ?: 0, it) }
    val input = listOfNotNull(fresh) + kept
    if (!options.enabled || input.isEmpty()) return ManifestMergePlan(options, content, input, emptyList())

    val first = input.first()
    val bins = input.groupBy { it.specId }.entries.sortedByDescending { it.key }.flatMap { (specId, group) ->
        packFromEnd(group, options.targetSizeBytes).map { bin ->
            val holdsFirst = first in bin
            val verdict = when {
                bin.size == 1 -> ManifestMergeVerdict.ALONE
                holdsFirst && bin.size < options.minCountToMerge -> ManifestMergeVerdict.UNDER_MIN_COUNT
                else -> ManifestMergeVerdict.MERGED
            }
            ManifestMergeBin(specId, bin, holdsFirst, verdict)
        }
    }
    return ManifestMergePlan(options, content, input, bins)
}

/**
 * The length to assume for the manifest the next commit writes: the newest listed one of the same
 * content, which is what the last commit wrote, else nothing. It only decides whether the new
 * manifest fits the open bin, and on a list of kilobyte manifests it decides nothing.
 */
fun assumedManifestBytes(listed: List<ManifestListEntry>, content: Int): Long =
    listed.firstOrNull { (it.content ?: ManifestContent.DATA) == content }?.manifestLength ?: 0L

/**
 * `ListPacker(target, lookback = 1, largestBinFirst = false).packEnd(items, length)`: the list
 * is packed reversed — oldest first — so the bin left under-filled is the one at the newest end,
 * and the bins come back in list order with their contents in list order.
 */
private fun packFromEnd(items: List<ManifestMergeItem>, target: Long): List<List<ManifestMergeItem>> {
    val bins = mutableListOf<MutableList<ManifestMergeItem>>()
    var open: MutableList<ManifestMergeItem>? = null
    var weight = 0L
    for (item in items.asReversed()) {
        if (open != null && weight + item.lengthBytes <= target) {
            open.add(item)
            weight += item.lengthBytes
        } else {
            open = mutableListOf(item).also { bins.add(it) }
            weight = item.lengthBytes
        }
    }
    return bins.asReversed().map { it.asReversed() }
}
