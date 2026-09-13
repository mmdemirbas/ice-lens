package model

/**
 * What a `snapshot-log` entry records. Every entry is a moment `main` was pointed at a snapshot;
 * the first time an id appears that is its commit, and a later appearance is `main` being set
 * back to it — `rollback_to_snapshot`, `rollback_to_timestamp`, `set_current_snapshot` — which
 * writes no snapshot of its own and is recorded nowhere else.
 */
enum class SnapshotLogKind { COMMIT, RESET }

/**
 * One `snapshot-log` entry, read against the entries before it and against the current lineage.
 *
 * [leftBehind] is what a [SnapshotLogKind.RESET] undid: the ids made current between the target's
 * earlier appearance and this entry, in log order. Those snapshots stay in `snapshots` until an
 * expiry removes them, reachable from no ref and not an ancestor of the current snapshot, which
 * is [currentAncestor] — the flag Iceberg's own `.history` metadata table prints as
 * `is_current_ancestor`, and the oracle `RolledBackFixtureTest` holds this to.
 */
data class SnapshotLogEvent(
    val entry: SnapshotLogEntry,
    val kind: SnapshotLogKind,
    val leftBehind: List<Long> = emptyList(),
    val currentAncestor: Boolean,
)

/** The ids on the path from `current-snapshot-id` back to the first commit, through the retained snapshots. */
fun TableMetadata.currentAncestorIds(): Set<Long> {
    val byId = snapshots.associateBy { it.snapshotId }
    val seen = LinkedHashSet<Long>()
    var id = currentSnapshotId
    while (id != null && id != -1L && seen.add(id)) {
        id = byId[id]?.parentSnapshotId
    }
    return seen
}

/** The log in time order, each entry classified — see [SnapshotLogEvent]. */
fun TableMetadata.snapshotHistory(): List<SnapshotLogEvent> {
    val ancestors = currentAncestorIds()
    val ordered = snapshotLog.withIndex().sortedWith(compareBy({ it.value.timestampMs ?: Long.MAX_VALUE }, { it.index })).map { it.value }
    val lastSeenAt = HashMap<Long, Int>()
    return ordered.mapIndexed { index, entry ->
        val id = entry.snapshotId
        val earlier = id?.let { lastSeenAt[it] }
        val event = if (earlier == null) {
            SnapshotLogEvent(entry, SnapshotLogKind.COMMIT, currentAncestor = id in ancestors)
        } else {
            val undone = ordered.subList(earlier + 1, index).mapNotNull { it.snapshotId }.filter { it != id }.distinct()
            SnapshotLogEvent(entry, SnapshotLogKind.RESET, leftBehind = undone, currentAncestor = id in ancestors)
        }
        if (id != null) lastSeenAt[id] = index
        event
    }
}

/**
 * Snapshot id → the reset that moved `main` back past it. A snapshot in this map is retained by
 * nothing but the metadata, and the next commit forked from the reset's target rather than from it.
 */
fun TableMetadata.leftBehindBy(): Map<Long, SnapshotLogEntry> =
    snapshotHistory().filter { it.kind == SnapshotLogKind.RESET }
        .flatMap { event -> event.leftBehind.map { it to event.entry } }
        .toMap()
