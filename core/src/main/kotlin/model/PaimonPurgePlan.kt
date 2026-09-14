package model

/**
 * What `sys.purge_files(table)` leaves and takes — `FileStoreTable.purgeFiles()` at
 * release-1.3.1, run in this order: every branch dropped, every tag deleted, every consumer
 * deleted; the table truncated by one `OVERWRITE` commit whose delta manifest holds a `DELETE`
 * entry for every live file of the latest snapshot (`deltaRecordCount` minus its rows, total 0)
 * and whose base list carries the latest snapshot's own manifests forward; `changelog/`
 * deleted whole; every snapshot but the truncate expired at once (`retain 1`, no age, no
 * limit), which frees the data files the truncate's delta removed; then a local orphan clean
 * at `now`, which takes what nothing names any more — a branch's data file and manifests, an
 * index file, an old index manifest. Nothing is dropped: the schema files stay, the table reads
 * as empty at the truncate snapshot and takes the next INSERT. `docs/fixtures/paimon-purge.sql`
 * records the runs, and `brp` is `br` purged.
 */
data class PaimonPurgePlan(
    val branches: List<String>,
    val tags: List<String>,
    val consumers: List<String>,
    /** Main's snapshot ids, every one expired by the purge. */
    val snapshotIds: List<Long>,
    /** The truncate commit's id — the latest plus one — and its `deltaRecordCount`, minus the latest's rows. */
    val truncateSnapshotId: Long?,
    val deltaRecordCount: Long,
    /** Every file under the table the purge takes, by name, with its kind — data files first. */
    val removed: List<PaimonExpiryFile>,
    /** What stays: the latest snapshot's manifests, carried into the truncate's base list, and the statistics it names. */
    val kept: List<PaimonExpiryFile>,
    /** The files the truncate writes that this cannot name: its snapshot file, two manifest lists, one delete manifest, and an index manifest where the latest has one. */
    val newFiles: Int,
) {
    val removesAnything: Boolean get() = removed.isNotEmpty() || branches.isNotEmpty() || tags.isNotEmpty() || consumers.isNotEmpty()
    fun ofKind(kind: PaimonExpiryFileKind): List<PaimonExpiryFile> = removed.filter { it.kind == kind }
    val removedBytes: Long get() = removed.sumOf { it.sizeBytes ?: 0L }
}

fun PaimonExpiryFileInput.planPurge(): PaimonPurgePlan {
    val ids = snapshots.keys.sorted()
    val latest = ids.lastOrNull()?.let { snapshots[it] }
    // The latest snapshot's own manifests survive in the truncate's base list; its statistics
    // are carried on the new snapshot. Everything else the metadata names goes.
    val keptNames = buildMap<String, PaimonExpiryFile> {
        latest?.let { s ->
            (s.base + s.delta).forEach { m -> put(m.name, PaimonExpiryFile(PaimonExpiryFileKind.MANIFEST, m.name, "manifest/${m.name}", m.sizeBytes, PaimonExpiryFileReason.PURGED, s.id)) }
            s.metadata.statistics?.let { put(it, PaimonExpiryFile(PaimonExpiryFileKind.STATISTICS, it, "statistics/$it", null, PaimonExpiryFileReason.PURGED, s.id)) }
        }
    }
    // A data file is on disk while some retained snapshot, tag or branch holds it live: a
    // `DELETE` entry's file went with the expiry that read that delta, and a tag-only snapshot's
    // changelog went with the expiry that left the tag — so each view is taken at what it holds.
    val named = linkedMapOf<String, PaimonExpiryFile>()
    fun take(view: PaimonExpirySnapshotView, reason: PaimonExpiryFileReason, withChangelog: Boolean) {
        view.everyName(reason, liveOnly = true, withChangelog = withChangelog).forEach { (name, file) -> if (name !in keptNames) named.putIfAbsent(name, file) }
    }
    ids.forEach { id ->
        snapshots[id]?.let { s -> take(s, PaimonExpiryFileReason.PURGED, withChangelog = true) }
        named.putIfAbsent("snapshot-$id", PaimonExpiryFile(PaimonExpiryFileKind.SNAPSHOT, "snapshot-$id", "snapshot/snapshot-$id", null, PaimonExpiryFileReason.PURGED, id))
    }
    tags.forEach { t ->
        take(t.snapshot, PaimonExpiryFileReason.PURGED_TAG, withChangelog = false)
        named.putIfAbsent("tag-${t.name}", PaimonExpiryFile(PaimonExpiryFileKind.TAG, "tag-${t.name}", "tag/tag-${t.name}", null, PaimonExpiryFileReason.PURGED_TAG, t.snapshot.id))
    }
    changelogs.forEach { c ->
        // A long-lived changelog's lists are what the expiry left of them, so every entry it still names is on disk.
        c.everyName(PaimonExpiryFileReason.PURGED).forEach { (name, file) -> if (name !in keptNames) named.putIfAbsent(name, file) }
        c.id?.let { named.putIfAbsent("changelog-$it", PaimonExpiryFile(PaimonExpiryFileKind.SNAPSHOT, "changelog-$it", "changelog/changelog-$it", null, PaimonExpiryFileReason.PURGED, it)) }
    }
    branches.forEach { b ->
        b.snapshots.values.forEach { s -> take(s, PaimonExpiryFileReason.PURGED_ORPHAN, withChangelog = true) }
        b.tags.forEach { t -> take(t.snapshot, PaimonExpiryFileReason.PURGED_ORPHAN, withChangelog = false) }
    }
    val order = listOf(PaimonExpiryFileKind.DATA_FILE, PaimonExpiryFileKind.CHANGELOG_FILE, PaimonExpiryFileKind.INDEX_FILE, PaimonExpiryFileKind.INDEX_MANIFEST, PaimonExpiryFileKind.MANIFEST, PaimonExpiryFileKind.MANIFEST_LIST, PaimonExpiryFileKind.STATISTICS, PaimonExpiryFileKind.SNAPSHOT, PaimonExpiryFileKind.TAG)
    return PaimonPurgePlan(
        branches = branches.map { it.name }.sorted(),
        tags = tags.map { it.name },
        consumers = consumers.sorted(),
        snapshotIds = ids,
        truncateSnapshotId = ids.lastOrNull()?.let { it + 1 },
        deltaRecordCount = -(latest?.metadata?.totalRecordCount ?: 0L),
        removed = named.values.sortedBy { order.indexOf(it.kind) },
        kept = keptNames.values.toList(),
        newFiles = 4 + (if (latest?.metadata?.indexManifest != null) 1 else 0),
    )
}
