package model

/**
 * Which files an `expire_snapshots` would delete along with the snapshots [planExpiry] removes —
 * decided the way `RemoveSnapshots.cleanExpiredSnapshots` decides it at Iceberg 1.8.1, and
 * checked against two expiries run on tables copied on disk beforehand (`sweep`/`swept`,
 * `sweepb`/`sweptb`, from `docs/fixtures/cleanup.sql`).
 *
 * The strategy is chosen by the **ref count after the expiry**: exactly one ref runs
 * `IncrementalFileCleanup`, more run `ReachableFileCleanup`, and the two free different things.
 *
 * **Incremental** (one ref, so the ancestry of its tip is the whole live history): every expired
 * snapshot's manifest list goes; a manifest goes when no retained snapshot lists it; a **data file
 * goes for one of two reasons** — a `DELETED` entry whose deleting snapshot expired, read from a
 * manifest written by an ancestor of the tip (the file left the table on the live line, and the
 * commit that could still read it is gone), or an `ADDED` entry in a manifest written by an
 * expiring snapshot that is *not* an ancestor (a commit rolled back or left on no ref, whose
 * additions never reached the table and are reverted). A snapshot cherry-picked into the live
 * line, or picked from one, is left alone — its files belong to the pick.
 *
 * **Reachable** (more refs): every expired snapshot's manifest list goes; a manifest goes when no
 * retained snapshot lists it; a data file goes when it is a **live** (`ADDED` or `EXISTING`) entry
 * of a manifest that goes and a live entry of no manifest a retained snapshot lists. A `DELETED`
 * entry frees nothing here, which is why `sweptb`'s expiry deleted a manifest and not the file it
 * recorded as removed — the branch still reads it through an older manifest.
 *
 * Statistics files go with their snapshot under either strategy. Data and delete manifests are
 * treated alike throughout — both strategies read the whole manifest list.
 */
enum class ExpiryCleanup(val label: String) {
    INCREMENTAL("incremental — one ref"),
    REACHABLE("reachable — more than one ref"),
    NONE("none — nothing expires"),
}

enum class ExpiryFileKind(val label: String) {
    MANIFEST_LIST("manifest list"),
    MANIFEST("manifest"),
    DATA_FILE("data file"),
    DELETE_FILE("delete file"),
    STATISTICS("statistics file"),
}

enum class ExpiryFileReason(val label: String) {
    EXPIRED_SNAPSHOT("its snapshot expires"),
    UNLISTED("no retained snapshot lists it"),
    DELETED_BY_EXPIRED("removed by an expired commit on the live line"),
    REVERTED("added by an expired commit off the live line"),
    UNREACHABLE("no retained manifest lists it live"),
}

data class ExpiryFile(
    val kind: ExpiryFileKind,
    val path: String,
    /** Null where the metadata records no size — a manifest list. */
    val sizeBytes: Long?,
    val reason: ExpiryFileReason,
    /** The snapshot the file belongs to, where one does — a list's, a manifest's writer, a statistics file's. */
    val snapshotId: Long?,
)

data class ExpiryFilePlan(
    val cleanup: ExpiryCleanup,
    val removedSnapshotIds: Set<Long>,
    val files: List<ExpiryFile>,
) {
    fun ofKind(kind: ExpiryFileKind): List<ExpiryFile> = files.filter { it.kind == kind }
    val paths: Set<String> get() = files.map { normalizeFilePath(it.path) }.toSet()
    /** Bytes the metadata can account for — manifest lists carry no size and are left out. */
    val knownBytes: Long get() = files.sumOf { it.sizeBytes ?: 0L }
    val describe: String get() = when {
        files.isEmpty() -> "nothing"
        else -> ExpiryFileKind.entries.mapNotNull { k -> ofKind(k).size.takeIf { it > 0 }?.let { "$it ${k.label}${if (it == 1) "" else "s"}" } }.joinToString(", ")
    }
}

/** What the plan reads: the table before the expiry, with every manifest still on disk. */
data class ExpiryFileInput(
    val metadata: TableMetadata,
    /** Each snapshot's manifest list, by snapshot id — absent where the list could not be read. */
    val manifestLists: Map<Long, List<ManifestListEntry>>,
    /** A manifest's entries by its recorded path — absent where the manifest could not be read. */
    val entriesOf: (String) -> List<ManifestEntry>?,
)

fun UnifiedTableModel.expiryFileInput(): ExpiryFileInput {
    val latest = metadatas.last()
    val byPath = mutableMapOf<String, List<ManifestEntry>>()
    val lists = mutableMapOf<Long, List<ManifestListEntry>>()
    metadatas.flatMap { it.snapshots }.forEach { s ->
        val id = s.metadata.snapshotId ?: return@forEach
        if (s.expired) return@forEach
        lists.putIfAbsent(id, s.manifests.map { it.metadata })
        s.manifests.forEach { m -> m.metadata.manifestPath?.let { p -> byPath.putIfAbsent(normalizeFilePath(p), m.dataFiles.map { it.metadata }) } }
    }
    return ExpiryFileInput(latest.metadata, lists) { byPath[normalizeFilePath(it)] }
}

/**
 * Plans the files for the snapshots in [removed] — the ids [planExpiry] would drop, or any set
 * — from [input], the table as it stands.
 */
fun ExpiryFileInput.planExpiryFiles(removed: Set<Long>): ExpiryFilePlan {
    val snapshots = metadata.snapshots.filter { it.snapshotId != null }
    val expired = snapshots.filter { it.snapshotId in removed }
    val retained = snapshots.filter { it.snapshotId !in removed }
    if (expired.isEmpty()) return ExpiryFilePlan(ExpiryCleanup.NONE, removed, emptyList())
    val retainedIds = retained.mapNotNull { it.snapshotId }.toSet()

    // The refs the expiry leaves: one on a removed snapshot goes with it.
    val refsAfter = metadata.refs.filterValues { it.snapshotId != null && it.snapshotId !in removed }
    val cleanup = if (refsAfter.size == 1) ExpiryCleanup.INCREMENTAL else ExpiryCleanup.REACHABLE

    val files = mutableListOf<ExpiryFile>()
    val seen = mutableSetOf<String>()
    fun add(kind: ExpiryFileKind, path: String, size: Long?, reason: ExpiryFileReason, snapshotId: Long?) {
        if (seen.add(normalizeFilePath(path))) files.add(ExpiryFile(kind, path, size, reason, snapshotId))
    }
    fun listOf(s: Snapshot): List<ManifestListEntry> = manifestLists[s.snapshotId].orEmpty()
    fun kindOf(entry: ManifestEntry): ExpiryFileKind =
        if ((entry.dataFile?.content ?: DataFileContent.DATA) == DataFileContent.DATA) ExpiryFileKind.DATA_FILE else ExpiryFileKind.DELETE_FILE
    fun hasAdded(m: ManifestListEntry) = m.addedFilesCount == null || m.addedFilesCount > 0
    fun hasExisting(m: ManifestListEntry) = m.existingFilesCount == null || m.existingFilesCount > 0
    fun hasDeleted(m: ManifestListEntry) = m.deletedFilesCount == null || m.deletedFilesCount > 0

    when (cleanup) {
        ExpiryCleanup.INCREMENTAL -> {
            // `IncrementalFileCleanup.cleanFiles`: the ancestry is the tip of the first ref before
            // the expiry, walked while the parent is in the metadata.
            val byId = snapshots.associateBy { it.snapshotId }
            val tip = metadata.refs.values.firstOrNull()?.snapshotId?.let { byId[it] }
            if (tip != null) {
                val ancestorIds = generateSequence(tip) { it.parentSnapshotId?.let { p -> byId[p] } }.mapNotNull { it.snapshotId }.toSet()
                val picked = ancestorIds.mapNotNull { byId[it]?.sourceSnapshotId }.toSet()
                val validManifests = mutableSetOf<String>()
                val toScan = linkedMapOf<String, ManifestListEntry>()
                val toRevert = linkedMapOf<String, ManifestListEntry>()
                retained.forEach { s ->
                    listOf(s).forEach { m ->
                        val p = m.manifestPath ?: return@forEach
                        validManifests.add(normalizeFilePath(p))
                        val writer = m.addedSnapshotId
                        if (writer !in retainedIds && (writer in ancestorIds || writer in picked) && hasDeleted(m)) toScan.putIfAbsent(normalizeFilePath(p), m)
                    }
                }
                expired.forEach { s ->
                    val id = s.snapshotId ?: return@forEach
                    // A commit picked into the live line, or picked from it, keeps its files.
                    if (id in picked) return@forEach
                    val source = s.sourceSnapshotId
                    if (source != null && (source in ancestorIds || source in picked)) return@forEach
                    listOf(s).forEach { m ->
                        val p = m.manifestPath ?: return@forEach
                        if (normalizeFilePath(p) in validManifests) return@forEach
                        add(ExpiryFileKind.MANIFEST, p, m.manifestLength, ExpiryFileReason.UNLISTED, m.addedSnapshotId)
                        val writer = m.addedSnapshotId
                        if (writer in ancestorIds && hasDeleted(m)) toScan.putIfAbsent(normalizeFilePath(p), m)
                        if (writer !in ancestorIds && writer in removed && hasAdded(m)) toRevert.putIfAbsent(normalizeFilePath(p), m)
                    }
                    s.manifestList?.let { add(ExpiryFileKind.MANIFEST_LIST, it, null, ExpiryFileReason.EXPIRED_SNAPSHOT, id) }
                }
                toScan.forEach { (p, m) ->
                    entriesOf(p)?.forEach { e ->
                        if (e.status == ManifestEntryStatus.DELETED && e.snapshotId !in retainedIds) {
                            e.dataFile?.filePath?.let { add(kindOf(e), it, e.dataFile.fileSizeInBytes, ExpiryFileReason.DELETED_BY_EXPIRED, m.addedSnapshotId) }
                        }
                    }
                }
                toRevert.forEach { (p, m) ->
                    entriesOf(p)?.forEach { e ->
                        if (e.status == ManifestEntryStatus.ADDED) {
                            e.dataFile?.filePath?.let { add(kindOf(e), it, e.dataFile.fileSizeInBytes, ExpiryFileReason.REVERTED, m.addedSnapshotId) }
                        }
                    }
                }
            }
        }
        ExpiryCleanup.REACHABLE -> {
            // `ReachableFileCleanup.cleanFiles`: candidates are the expired snapshots' manifests
            // minus the retained snapshots'; a file goes when live in a candidate and live in no
            // retained manifest.
            val candidates = linkedMapOf<String, ManifestListEntry>()
            expired.forEach { s ->
                listOf(s).forEach { m -> m.manifestPath?.let { candidates.putIfAbsent(normalizeFilePath(it), m) } }
                s.manifestList?.let { add(ExpiryFileKind.MANIFEST_LIST, it, null, ExpiryFileReason.EXPIRED_SNAPSHOT, s.snapshotId) }
            }
            val current = linkedMapOf<String, ManifestListEntry>()
            retained.forEach { s -> listOf(s).forEach { m -> m.manifestPath?.let { p -> candidates.remove(normalizeFilePath(p)); current.putIfAbsent(normalizeFilePath(p), m) } } }
            fun livePaths(p: String): List<ManifestEntry> = entriesOf(p).orEmpty().filter { it.status != ManifestEntryStatus.DELETED }
            val stillLive = current.keys.flatMap { p -> livePaths(p).mapNotNull { it.dataFile?.filePath?.let(::normalizeFilePath) } }.toSet()
            candidates.forEach { (p, m) ->
                livePaths(p).forEach { e ->
                    val path = e.dataFile?.filePath ?: return@forEach
                    if (normalizeFilePath(path) !in stillLive) add(kindOf(e), path, e.dataFile.fileSizeInBytes, ExpiryFileReason.UNREACHABLE, m.addedSnapshotId)
                }
                add(ExpiryFileKind.MANIFEST, m.manifestPath!!, m.manifestLength, ExpiryFileReason.UNLISTED, m.addedSnapshotId)
            }
        }
        ExpiryCleanup.NONE -> Unit
    }

    // Statistics files: the ones the metadata after the expiry no longer names.
    val keptStats = (metadata.statistics.filter { it.snapshotId !in removed }.mapNotNull { it.statisticsPath } +
        metadata.partitionStatistics.filter { it.snapshotId !in removed }.mapNotNull { it.statisticsPath }).map(::normalizeFilePath).toSet()
    metadata.statistics.filter { it.snapshotId in removed }.forEach { f ->
        f.statisticsPath?.takeIf { normalizeFilePath(it) !in keptStats }?.let { add(ExpiryFileKind.STATISTICS, it, f.fileSizeInBytes, ExpiryFileReason.EXPIRED_SNAPSHOT, f.snapshotId) }
    }
    metadata.partitionStatistics.filter { it.snapshotId in removed }.forEach { f ->
        f.statisticsPath?.takeIf { normalizeFilePath(it) !in keptStats }?.let { add(ExpiryFileKind.STATISTICS, it, f.fileSizeInBytes, ExpiryFileReason.EXPIRED_SNAPSHOT, f.snapshotId) }
    }
    return ExpiryFilePlan(cleanup, removed, files)
}
