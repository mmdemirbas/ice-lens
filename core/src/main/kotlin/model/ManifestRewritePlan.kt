package model

/**
 * What `rewrite_manifests` would do to a snapshot's manifest list, planned the way
 * `RewriteManifestsSparkAction` plans it at Iceberg 1.8.1 — the third procedure the `maint`
 * fixture ran, and the one whose summary counts manifests rather than files.
 *
 * Per content kind, data then deletes: the current snapshot's manifests of that kind under the
 * output spec (`spec-id`, else the table's current spec) that pass the predicate are the
 * matching set; `targetNumManifests` is their total `manifest_length` divided by
 * `commit.manifest.target-size-bytes` (8 MB), rounded up; **a kind of one manifest that fits one
 * target is left alone**, and any other matching set is rewritten whole into that many
 * manifests — so two small manifests of a kind fold into one, and one manifest past the target
 * splits. A manifest under another spec is never matched and is kept. The commit records the
 * outcome as `manifests-created`, `manifests-kept` and `manifests-replaced`
 * (`BaseRewriteManifests.apply`: created is the manifests added, kept the current ones neither
 * rewritten nor deleted, replaced the rewritten ones), which is what `maint`'s rewrite — two
 * data and two delete manifests into one each: created 2, kept 0, replaced 4 — holds the plan
 * to. The action validates that every matching manifest carries its file counts
 * (`No file counts in manifest`), which a v1 manifest may not; such a kind is reported refused.
 */
data class ManifestRewriteOptions(
    /** The table's `commit.manifest.target-size-bytes` (8 MB). */
    val targetManifestSizeBytes: Long = DEFAULT_TARGET_MANIFEST_SIZE_BYTES,
    /** `spec-id`, else the table's current spec; null matches every spec, which is not what the action does and only what a graph with no metadata can offer. */
    val specId: Int? = null,
) {
    companion object {
        const val DEFAULT_TARGET_MANIFEST_SIZE_BYTES = 8L * 1024 * 1024

        fun forTable(properties: Map<String, String>, currentSpecId: Int?): ManifestRewriteOptions = ManifestRewriteOptions(
            targetManifestSizeBytes = properties["commit.manifest.target-size-bytes"]?.toLongOrNull() ?: DEFAULT_TARGET_MANIFEST_SIZE_BYTES,
            specId = currentSpecId,
        )
    }
}

data class ManifestRewriteKind(
    /** [ManifestContent.DATA] or [ManifestContent.DELETES]. */
    val content: Int,
    /** The manifests of this kind under the output spec, in list order. */
    val matching: List<ManifestListEntry>,
    /** `targetNumManifests`: what the matching set's bytes come to at the target size, rounded up. */
    val targetNumManifests: Int,
    /** Null when the kind is rewritten; else why it is left alone, or the action's refusal. */
    val leftAlone: String?,
) {
    val rewritten: Boolean get() = leftAlone == null && matching.isNotEmpty()
    val inputBytes: Long get() = matching.sumOf { it.manifestLength ?: 0L }
    val label: String get() = if (content == ManifestContent.DELETES) "delete" else "data"
}

data class ManifestRewritePlan(
    val options: ManifestRewriteOptions,
    val kinds: List<ManifestRewriteKind>,
    /** Manifests of the list that no kind matched — another spec's — and so are kept whatever happens. */
    val unmatched: List<ManifestListEntry>,
) {
    /** `manifests-created`: one manifest per target for each kind rewritten (a partitioned kind may write fewer, one per non-empty range). */
    val created: Int get() = kinds.filter { it.rewritten }.sumOf { it.targetNumManifests }
    /** `manifests-replaced`. */
    val replaced: Int get() = kinds.filter { it.rewritten }.sumOf { it.matching.size }
    /** `manifests-kept`: the list's manifests neither rewritten nor deleted. */
    val kept: Int get() = unmatched.size + kinds.filter { !it.rewritten }.sumOf { it.matching.size }
    val rewrites: Boolean get() = kinds.any { it.rewritten }
    val refused: List<ManifestRewriteKind> get() = kinds.filter { it.leftAlone?.startsWith(REFUSED_PREFIX) == true }

    companion object {
        const val REFUSED_PREFIX = "refused: "
    }
}

/** Plans the rewrite over [manifestList] — a snapshot's manifest list as recorded. */
fun planManifestRewrite(manifestList: List<ManifestListEntry>, options: ManifestRewriteOptions): ManifestRewritePlan {
    val (inSpec, unmatched) = manifestList.partition { options.specId == null || it.partitionSpecId == options.specId }
    val kinds = listOf(ManifestContent.DATA, ManifestContent.DELETES).map { content ->
        val matching = inSpec.filter { (it.content ?: ManifestContent.DATA) == content }
        val target = options.targetManifestSizeBytes
        val bytes = matching.sumOf { it.manifestLength ?: 0L }
        val targetNum = ((bytes + target - 1) / target).toInt()
        val noCounts = matching.firstOrNull { it.addedFilesCount == null || it.existingFilesCount == null || it.deletedFilesCount == null }
        val leftAlone = when {
            matching.isEmpty() -> "no manifest of this kind under the spec"
            noCounts != null -> "${ManifestRewritePlan.REFUSED_PREFIX}No file counts in manifest: ${noCounts.manifestPath?.substringAfterLast('/')}"
            targetNum == 1 && matching.size == 1 -> "one manifest, within the target"
            else -> null
        }
        ManifestRewriteKind(content, matching, targetNum, leftAlone)
    }
    return ManifestRewritePlan(options, kinds, unmatched)
}
