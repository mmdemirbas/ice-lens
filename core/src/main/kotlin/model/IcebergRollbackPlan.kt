package model

/**
 * What setting `main` back to a snapshot does on Iceberg — `SetSnapshotOperation` at 1.8.1 —
 * and what it leaves for the expiry, which is where the two formats part.
 *
 * `rollback_to_snapshot(id)` requires the target to be an ancestor of the current snapshot
 * (`Cannot roll back to snapshot, not an ancestor of the current state`); `set_current_snapshot(id)`
 * takes any snapshot the table holds; `rollback_to_timestamp(t)` is the rollback to the newest
 * current ancestor whose timestamp is before `t` (`findLatestAncestorOlderThan`), refused when
 * there is none. None of the three writes a snapshot: the commit is a `metadata.json` whose
 * `main` ref and `current-snapshot-id` name the target and whose `snapshot-log` gains an entry
 * for it — the moment the log records twice and nothing else records (`snapshotHistory`). The
 * commits main moved past stay in `snapshots`, on no ref unless a tag or branch names them,
 * until an expiry removes them; and that expiry frees the files those commits added, since they
 * are off the live line ([ExpiryFileReason.REVERTED]) — where Paimon's rollback deletes the
 * snapshot files at once and leaves the data as orphans ([PaimonRollbackPlan]).
 *
 * `sweep` and `rolled` each carry one such reset in their metadata log, and the plan from the
 * version before is held to the version after; `docs/fixtures/rollback.sql` records the runs
 * that refuse.
 */

enum class IcebergRollbackVerdict(val label: String) {
    MOVES("MOVES"),
    NOTHING_TO_DO("nothing to do"),
    NOT_AN_ANCESTOR("REFUSED"),
    UNKNOWN_SNAPSHOT("REFUSED"),
}

/** A commit main moves past, and the refs that still name it — none means an expiry removes it. */
data class LeftBehindSnapshot(val snapshotId: Long, val heldBy: List<String>)

data class IcebergRollbackPlan(
    val targetId: Long,
    val verdict: IcebergRollbackVerdict,
    val reason: String,
    /** True where `rollback_to_snapshot` refuses and `set_current_snapshot` would move main all the same. */
    val setCurrentSnapshotWould: Boolean,
    /** Main's current ancestors the target does not reach, newest first — off the live line after the move. */
    val leftBehind: List<LeftBehindSnapshot>,
    /**
     * The times `rollback_to_timestamp` lands on the target for: after the target's own timestamp,
     * up to and including the next current ancestor's. Null where the target is not an ancestor.
     */
    val timestampWindow: LongRange?,
    /** The metadata the commit writes — for the expiry planners to read what comes next. Null when nothing moves. */
    val after: TableMetadata?,
) {
    val moves: Boolean get() = verdict == IcebergRollbackVerdict.MOVES
}

/** The ids on the path from [tip] back to the first retained commit. */
private fun TableMetadata.ancestorIdsOf(tip: Long?): List<Long> {
    val byId = snapshots.associateBy { it.snapshotId }
    val seen = LinkedHashSet<Long>()
    var id = tip
    while (id != null && id != -1L && byId.containsKey(id) && seen.add(id)) id = byId[id]?.parentSnapshotId
    return seen.toList()
}

/** `rollback_to_snapshot(table, [targetId])` on this metadata, at [nowMs] — the moment the log entry records. */
fun TableMetadata.planRollback(targetId: Long, nowMs: Long): IcebergRollbackPlan {
    val byId = snapshots.associateBy { it.snapshotId }
    if (targetId !in byId) {
        return IcebergRollbackPlan(targetId, IcebergRollbackVerdict.UNKNOWN_SNAPSHOT, "Cannot roll back to unknown snapshot id: $targetId", false, emptyList(), null, null)
    }
    if (targetId == currentSnapshotId) {
        return IcebergRollbackPlan(targetId, IcebergRollbackVerdict.NOTHING_TO_DO, "main is at $targetId already", false, emptyList(), timestampWindowOf(targetId), null)
    }
    val current = ancestorIdsOf(currentSnapshotId)
    val targetLine = ancestorIdsOf(targetId).toSet()
    val leftBehind = current.filter { it !in targetLine }.map { id ->
        LeftBehindSnapshot(id, refs.filter { (name, ref) -> name != "main" && ref.snapshotId == id }.keys.sorted())
    }
    val after = copy(
        currentSnapshotId = targetId,
        refs = refs + ("main" to (refs["main"] ?: SnapshotRef(type = "branch")).copy(snapshotId = targetId)),
        snapshotLog = snapshotLog + SnapshotLogEntry(timestampMs = nowMs, snapshotId = targetId),
        lastUpdatedMs = nowMs,
    )
    return if (targetId in current) {
        IcebergRollbackPlan(
            targetId, IcebergRollbackVerdict.MOVES,
            "$targetId is an ancestor of the current snapshot: main moves back to it, past ${leftBehind.size} commit${if (leftBehind.size == 1) "" else "s"}",
            setCurrentSnapshotWould = true, leftBehind, timestampWindowOf(targetId), after,
        )
    } else {
        IcebergRollbackPlan(
            targetId, IcebergRollbackVerdict.NOT_AN_ANCESTOR,
            "Cannot roll back to snapshot, not an ancestor of the current state: $targetId — set_current_snapshot would move main there, leaving ${leftBehind.size} commit${if (leftBehind.size == 1) "" else "s"} of the current line behind",
            setCurrentSnapshotWould = true, leftBehind, null, after,
        )
    }
}

/**
 * The target `rollback_to_timestamp(table, [timestampMs])` picks: the current ancestor with the
 * greatest timestamp below the time, as `findLatestAncestorOlderThan` walks them — null when none is.
 */
fun TableMetadata.rollbackTargetForTime(timestampMs: Long): Long? {
    val byId = snapshots.associateBy { it.snapshotId }
    return ancestorIdsOf(currentSnapshotId)
        .mapNotNull { id -> byId[id]?.let { s -> s.timestampMs?.let { ts -> id to ts } } }
        .filter { (_, ts) -> ts < timestampMs }
        .maxByOrNull { (_, ts) -> ts }?.first
}

/** The times that land `rollback_to_timestamp` on [targetId]: above its timestamp, up to the next ancestor's — see [rollbackTargetForTime]. */
private fun TableMetadata.timestampWindowOf(targetId: Long): LongRange? {
    val byId = snapshots.associateBy { it.snapshotId }
    val line = ancestorIdsOf(currentSnapshotId)
    val at = line.indexOf(targetId)
    if (at < 0) return null
    val own = byId[targetId]?.timestampMs ?: return null
    val next = line.getOrNull(at - 1)?.let { byId[it]?.timestampMs }
    return (own + 1)..(next ?: Long.MAX_VALUE)
}
