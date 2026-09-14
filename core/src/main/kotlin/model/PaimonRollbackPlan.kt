package model

/**
 * What `rollback_to` a snapshot removes — `RollbackHelper.cleanLargerThan` at release-1.3.1 —
 * and what it leaves on disk, which is the part an Iceberg reader does not expect.
 *
 * A rollback deletes the snapshot files above the target and moves `LATEST` to it, deletes the
 * long-lived changelog files above it, and deletes every tag whose snapshot is above it — and
 * nothing else: the data files, manifests and manifest lists those commits wrote stay where they
 * are, named by nothing, until `remove_orphan_files`. (Iceberg's `rollback_to_snapshot` keeps the
 * abandoned commit in `snapshots` for an expiry to remove; Paimon's forgets it at once.) Rolling
 * back to a tag whose snapshot has expired writes the snapshot file back from the tag. `prb`/`prba`
 * is the oracle: the same table before and after `rollback(version => '2')`.
 */
data class PaimonRollbackPlan(
    val targetId: Long,
    /** `snapshot/` above the target, highest first — the order the helper deletes in. */
    val removedSnapshots: List<Long>,
    val removedChangelogs: List<Long>,
    val removedTags: List<String>,
    val keptTags: List<String>,
) {
    val removesAnything: Boolean get() = removedSnapshots.isNotEmpty() || removedChangelogs.isNotEmpty() || removedTags.isNotEmpty()
}

/** The plan for rolling `snapshot/` back to [targetId]; null when the target is not a snapshot the table holds. */
fun PaimonExpiryInput.planRollback(targetId: Long): PaimonRollbackPlan? {
    if (targetId !in snapshotTimes) return null
    val removedSnapshots = snapshotTimes.keys.filter { it > targetId }.sortedDescending()
    val removedChangelogs = changelogTimes.keys.filter { it > targetId }.sortedDescending()
    val (removedTags, keptTags) = tags.partition { (it.snapshotId ?: Long.MIN_VALUE) > targetId }
    return PaimonRollbackPlan(targetId, removedSnapshots, removedChangelogs, removedTags.map { it.name }.sorted(), keptTags.map { it.name }.sorted())
}

/**
 * The files the rolled-back commits wrote that nothing kept names — every file the snapshots
 * above [targetId] name, less every file the snapshots at or below it and the surviving tags
 * name — which is what an orphan check reports after the rollback, and what the rollback itself
 * leaves alone.
 */
fun PaimonExpiryFileInput.rollbackLeftovers(targetId: Long): List<PaimonExpiryFile> {
    fun PaimonExpirySnapshotView.everyName(): Map<String, PaimonExpiryFile> {
        val id = this.id
        val named = linkedMapOf<String, PaimonExpiryFile>()
        fun put(kind: PaimonExpiryFileKind, name: String, path: String?, size: Long?) { named.putIfAbsent(name, PaimonExpiryFile(kind, name, path, size, PaimonExpiryFileReason.ROLLED_BACK, id)) }
        (base + delta).forEach { m ->
            put(PaimonExpiryFileKind.MANIFEST, m.name, "manifest/${m.name}", m.sizeBytes)
            m.entries.forEach { e ->
                put(PaimonExpiryFileKind.DATA_FILE, paimonEntryFileName(e), e.path.toString(), e.metadata.file?.fileSize)
                e.metadata.file?.extraFiles.orEmpty().forEach { extra -> put(PaimonExpiryFileKind.DATA_FILE, extra, e.path.resolveSibling(extra).toString(), null) }
            }
        }
        changelog.forEach { m ->
            put(PaimonExpiryFileKind.MANIFEST, m.name, "manifest/${m.name}", m.sizeBytes)
            m.entries.forEach { e -> put(PaimonExpiryFileKind.CHANGELOG_FILE, paimonEntryFileName(e), e.path.toString(), e.metadata.file?.fileSize) }
        }
        listOfNotNull(metadata.baseManifestList, metadata.deltaManifestList, metadata.changelogManifestList).forEach { put(PaimonExpiryFileKind.MANIFEST_LIST, it, "manifest/$it", null) }
        metadata.indexManifest?.let { im ->
            put(PaimonExpiryFileKind.INDEX_MANIFEST, im, "index/$im", null)
            indexFiles.forEach { f -> f.fileName?.let { put(PaimonExpiryFileKind.INDEX_FILE, it, "index/$it", f.fileSize) } }
        }
        metadata.statistics?.let { put(PaimonExpiryFileKind.STATISTICS, it, "statistics/$it", null) }
        return named
    }
    val removed = snapshots.filterKeys { it > targetId }.values.sortedBy { it.id ?: Long.MAX_VALUE }
    val kept = snapshots.filterKeys { it <= targetId }.values + tags.filter { (it.snapshot.id ?: Long.MIN_VALUE) <= targetId }.map { it.snapshot }
    val keptNames = kept.flatMap { it.everyName().keys }.toSet()
    val leftovers = linkedMapOf<String, PaimonExpiryFile>()
    removed.forEach { s -> s.everyName().forEach { (name, file) -> if (name !in keptNames) leftovers.putIfAbsent(name, file) } }
    return leftovers.values.toList()
}
