package model

/**
 * One file's life across a table's retained snapshots: which commit added it, which removed
 * it, and which snapshots still list it live — the question behind "my query says this file is
 * missing" and "why is this file still on disk".
 *
 * Format-agnostic in what it answers and format-specific in how, the same split as
 * [ComparableSnapshot]. **Iceberg** filters: a snapshot lists the file live when any manifest in
 * its list holds an entry for the path that is not `DELETED`, and the commit whose manifest
 * (`added_snapshot_id`) holds an `ADDED` or `DELETED` entry is the one that did it — the rule
 * [snapshotChangeOf] reads a commit by. **Paimon** replays: the file is live at a snapshot when
 * the last entry for it, across the base manifests then the delta manifests in list order, is
 * an `ADD` — the rule [replayPaimonSnapshot] walks by, applied to one file's entries — and the
 * delta's `ADD` and `DELETE` entries are its events. A compaction that moves a file to another
 * level writes `DELETE` then `ADD` of the same name in one delta, which is [FileEvent.REWRITTEN]
 * and leaves the file live.
 *
 * Both walk the snapshot's own manifests rather than [liveFilesOf] or the replay for every
 * snapshot, because those walk a whole closure to answer about one file; this reads each
 * distinct manifest once for the one path. The tests hold the two readings equal on every
 * retained snapshot of every fixture.
 */
enum class FileEvent(val label: String) {
    ADDED("added here"),
    REMOVED("removed here"),
    /** Removed and added again by one commit — Paimon's level upgrade, the same file name at a new level. */
    REWRITTEN("re-added here"),
}

data class FileHistoryEntry(
    val snapshotId: Long,
    val timestampMs: Long?,
    /** Iceberg's `summary.operation`; Paimon's `commitKind`. */
    val operation: String?,
    /** Whether this snapshot lists the file as part of the table. */
    val live: Boolean,
    /** What this commit did to the file, or null when it only carried it. */
    val event: FileEvent?,
    /** The current snapshot — the newest metadata's on Iceberg, the latest on the branch on Paimon. */
    val isCurrent: Boolean,
)

data class FileHistory(
    /** The key the file is known by — the normalised recorded path on Iceberg, the file name on Paimon. */
    val fileKey: String,
    /** Every retained snapshot that lists the file, live or as removed, in commit order. */
    val snapshots: List<FileHistoryEntry>,
    /** How many retained snapshots there are, listing the file or not. */
    val retainedSnapshotCount: Int,
    /** The Paimon branch the snapshots are numbered on; null for main and for Iceberg, whose ids are table-wide. */
    val branch: String? = null,
) {
    val addedBy: FileHistoryEntry? get() = snapshots.firstOrNull { it.event == FileEvent.ADDED || it.event == FileEvent.REWRITTEN }
    val removedBy: FileHistoryEntry? get() = snapshots.lastOrNull { it.event == FileEvent.REMOVED }
    val liveIn: List<FileHistoryEntry> get() = snapshots.filter { it.live }
    val liveNow: Boolean get() = snapshots.any { it.isCurrent && it.live }

    /** The one line a reader came for. */
    val describe: String get() {
        val added = addedBy
        val removed = removedBy
        val live = liveIn
        fun name(e: FileHistoryEntry) = "snapshot ${e.snapshotId}" + (e.operation?.let { " ($it)" } ?: "")
        return when {
            liveNow && added != null -> "live now — added by ${name(added)}"
            liveNow -> "live now — carried in from a snapshot no longer retained"
            removed != null && live.isNotEmpty() -> "removed by ${name(removed)} — still listed live by ${live.size} of $retainedSnapshotCount retained snapshots, so on disk until they expire"
            removed != null -> "removed by ${name(removed)} — no retained snapshot lists it live"
            live.isNotEmpty() -> "not in the current snapshot — listed live by ${live.size} of $retainedSnapshotCount retained snapshots, the last being ${name(live.last())}"
            else -> "no retained snapshot lists it"
        }
    }
}

private fun eventOf(added: Int, removed: Int): FileEvent? = when {
    added > 0 && removed > 0 -> FileEvent.REWRITTEN
    added > 0 -> FileEvent.ADDED
    removed > 0 -> FileEvent.REMOVED
    else -> null
}

/** The key [liveFilesOf] and the ledger know an Iceberg entry's file by. */
fun UnifiedDataFile.ledgerFileKey(): String =
    metadata.dataFile?.filePath?.takeIf { it.isNotBlank() }?.let(::normalizeFilePath) ?: "path:$path"

/** The history of the file [fileKey] names — see [UnifiedDataFile.ledgerFileKey] — over the retained snapshots. */
fun UnifiedTableModel.fileHistoryOf(fileKey: String): FileHistory {
    val currentId = metadatas.lastOrNull()?.metadata?.currentSnapshotId
    // One copy per snapshot, the newest metadata's, in commit order — a timestamp is a clock
    // and two commits from a fast writer can share one, so the sequence number leads.
    val retained = metadatas.asReversed().flatMap { it.snapshots }
        .filter { !it.expired && it.metadata.snapshotId != null }
        .distinctBy { it.metadata.snapshotId }
        .sortedWith(compareBy({ it.metadata.effectiveSequenceNumber }, { it.metadata.timestampMs ?: Long.MAX_VALUE }, { it.metadata.snapshotId }))
    // Each distinct manifest's entries for the file: its status, read once however many lists carry it.
    val holdings = mutableMapOf<String, List<Int>>()
    retained.forEach { s ->
        s.manifests.forEach { m ->
            val manifestPath = m.metadata.manifestPath ?: return@forEach
            holdings.getOrPut(manifestPath) { m.dataFiles.filter { it.ledgerFileKey() == fileKey }.map { it.metadata.status } }
        }
    }
    val entries = retained.mapNotNull { s ->
        val id = s.metadata.snapshotId ?: return@mapNotNull null
        var live = false
        var added = 0
        var removed = 0
        s.manifests.forEach { m ->
            val statuses = holdings[m.metadata.manifestPath] ?: return@forEach
            val writtenHere = m.metadata.addedSnapshotId == id
            statuses.forEach { status ->
                if (status != ManifestEntryStatus.DELETED) live = true
                if (writtenHere && status == ManifestEntryStatus.ADDED) added++
                if (writtenHere && status == ManifestEntryStatus.DELETED) removed++
            }
        }
        val event = eventOf(added, removed)
        if (!live && event == null) return@mapNotNull null
        FileHistoryEntry(id, s.metadata.timestampMs, s.metadata.summary["operation"], live, event, isCurrent = id == currentId)
    }
    return FileHistory(fileKey, entries, retained.size, branch = null)
}

/**
 * The history of the file [fileKey] names — see [paimonDataFileKey] — over the snapshots of
 * [branch], main when null. Snapshot ids are per branch, and a tag-only snapshot is retained
 * too: its files are the table's for as long as the tag stands.
 */
fun PaimonUnifiedTableModel.fileHistoryOf(fileKey: String, branch: String? = null): FileHistory {
    val line = branch?.let { name -> branches.firstOrNull { it.name == name } }
    val snapshots = if (branch == null) this.snapshots else line?.snapshots.orEmpty()
    val tagOnly = if (branch == null) this.tagOnlySnapshots else line?.tagOnlySnapshots.orEmpty()
    val currentId = snapshots.lastOrNull()?.metadata?.id
    val retained = (snapshots + tagOnly).filter { it.metadata.id != null }.distinctBy { it.metadata.id }.sortedBy { it.metadata.id }
    // Each distinct manifest's entries for the file, in entry order — the order decides.
    val holdings = mutableMapOf<String, List<Int>>()
    retained.forEach { s ->
        (s.baseManifests + s.deltaManifests).forEach { m ->
            holdings.getOrPut(paimonManifestKey(m)) { m.entries.filter { paimonDataFileKey(it) == fileKey }.map { it.metadata.kind ?: PaimonEntryKind.ADD } }
        }
    }
    val entries = retained.mapNotNull { s ->
        val id = s.metadata.id ?: return@mapNotNull null
        var last: Int? = null
        val consumed = mutableSetOf<String>()
        // Base then delta, a manifest once — the replay's own order and its own deduplication.
        (s.baseManifests + s.deltaManifests).forEach { m ->
            val key = paimonManifestKey(m)
            if (!consumed.add(key)) return@forEach
            holdings[key]?.forEach { last = it }
        }
        var added = 0
        var removed = 0
        s.deltaManifests.forEach { m -> holdings[paimonManifestKey(m)]?.forEach { if (it == PaimonEntryKind.DELETE) removed++ else added++ } }
        val live = last != null && last != PaimonEntryKind.DELETE
        val event = eventOf(added, removed)
        if (!live && event == null) return@mapNotNull null
        FileHistoryEntry(id, s.metadata.timeMillis, s.metadata.commitKind, live, event, isCurrent = id == currentId)
    }
    return FileHistory(fileKey, entries, retained.size, branch)
}
