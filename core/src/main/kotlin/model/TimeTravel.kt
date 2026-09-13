package model

/**
 * Which snapshot a read *as of a time* lands on — Iceberg's `TIMESTAMP AS OF`, Paimon's
 * `scan.timestamp-millis` — decided the way each engine decides it, because "my time-travel
 * query returned the wrong data" is a question about this rule and nothing else.
 *
 * **Iceberg** (`SnapshotUtil.nullableSnapshotIdAsOfTime`, 1.8.1) walks `table.history()` — the
 * metadata's `snapshot-log` — and takes the **last entry at or before** the time. Two things
 * follow that a reader of a rolled-back table needs said. A log entry is a moment `main` was
 * pointed at a snapshot, so a time between an abandoned commit and the reset that undid it
 * resolves to the abandoned commit — a snapshot that is *not* an ancestor of the current one —
 * and a time after the reset resolves to the reset's target through the reset's own entry. And
 * an expiry drops the log entries of the snapshots it removes, so the earliest time a read can
 * resolve to moves forward; before it Iceberg raises `Cannot find a snapshot older than …`.
 *
 * **Paimon** (`SnapshotManager.earlierOrEqualTimeMills`, 1.3.1) binary-searches the snapshot ids
 * from the earliest to the latest by `timeMillis` for the latest one at or before the time, and
 * answers nothing when the earliest is already later. The search assumes commit times rise with
 * ids, which a single writer keeps; this reads the same answer as the latest id whose time is at
 * or before, and says nothing of tags, which a time never resolves to.
 */
data class TimeTravelResolution(
    val timestampMs: Long,
    /** Null when nothing is at or before the time. */
    val snapshotId: Long?,
    /** The moment the log entry or snapshot records — what the time was matched against. */
    val atMs: Long?,
    /** The earliest moment a read can resolve to; a time before it resolves to nothing. */
    val earliestMs: Long?,
    /** Iceberg: whether the snapshot is an ancestor of the current one — false on an abandoned commit. */
    val currentAncestor: Boolean? = null,
    /** Iceberg: the reset that later moved `main` past the snapshot, when the read lands on an abandoned commit. */
    val leftBehindAt: SnapshotLogEntry? = null,
    /** Iceberg: the entry that resolved it was a reset — `main` set back to an older snapshot at that moment. */
    val viaReset: Boolean = false,
)

/** `TIMESTAMP AS OF` on this metadata's log. */
fun TableMetadata.snapshotAsOf(timestampMs: Long): TimeTravelResolution {
    val history = snapshotHistory()
    var hit: SnapshotLogEvent? = null
    for (event in history) {
        if ((event.entry.timestampMs ?: Long.MAX_VALUE) <= timestampMs) hit = event
    }
    val leftBehind = leftBehindBy()
    return TimeTravelResolution(
        timestampMs = timestampMs,
        snapshotId = hit?.entry?.snapshotId,
        atMs = hit?.entry?.timestampMs,
        earliestMs = history.firstOrNull()?.entry?.timestampMs,
        currentAncestor = hit?.currentAncestor,
        leftBehindAt = hit?.entry?.snapshotId?.let { leftBehind[it] },
        viaReset = hit?.kind == SnapshotLogKind.RESET,
    )
}

/** `scan.timestamp-millis` over `snapshot/` — the latest id whose time is at or before. */
fun PaimonExpiryInput.snapshotAsOf(timestampMs: Long): TimeTravelResolution {
    val timed = snapshotTimes.entries.filter { it.value != null }.sortedBy { it.key }
    val hit = timed.lastOrNull { it.value!! <= timestampMs }
    return TimeTravelResolution(
        timestampMs = timestampMs,
        snapshotId = hit?.key,
        atMs = hit?.value,
        earliestMs = timed.firstOrNull()?.value,
    )
}
