package model

/**
 * The six `total-*` figures a snapshot's summary records about the table *as it stands at that
 * commit*, each beside the same figure folded from the snapshot's live file set.
 *
 * `SnapshotChange` checks what a commit *did* against the manifests it wrote; this checks what
 * the commit *left* against the closure it names, which is the other half of the summary and the
 * half a planner reads first — `total-records` is the figure a `SELECT count(*)` estimate starts
 * from, and `total-data-files` is what decides whether a scan is worth planning at all. The
 * writer keeps the totals by arithmetic, previous total plus added minus removed, commit after
 * commit, so a total that has drifted from the closure is a total that has been wrong since some
 * earlier commit and has been carried forward since. Nothing on the read path checks it.
 *
 * The counted side is [liveFilesOf]: the same `manifestLedger` walk the table's `current` figures
 * and the two-snapshot comparison are folded from, so this is a third reading of one walk rather
 * than a second implementation of it. `total-files-size` is charged the way Iceberg charges it —
 * a deletion vector at its `content_size_in_bytes`, everything else at its file size — which is
 * the rule `added-files-size` settled on the `v3` fixture.
 */
fun snapshotTotals(summary: Map<String, String>, live: List<LiveFile>): List<CommitTally> {
    fun recorded(key: String): Long? = summary[key]?.toLongOrNull()
    val data = live.filter { it.content == DataFileContent.DATA }
    val deletes = live.filter { it.content != DataFileContent.DATA }
    return listOf(
        CommitTally("Data files", recorded("total-data-files"), data.size.toLong()),
        CommitTally("Delete files", recorded("total-delete-files"), deletes.size.toLong()),
        CommitTally("Records", recorded("total-records"), data.sumOf { it.recordCount }),
        CommitTally("Files size", recorded("total-files-size"), live.sumOf { it.chargedSizeBytes }),
        CommitTally("Position deletes", recorded("total-position-deletes"), live.filter { it.content == DataFileContent.POSITION_DELETES }.sumOf { it.recordCount }),
        CommitTally("Equality deletes", recorded("total-equality-deletes"), live.filter { it.content == DataFileContent.EQUALITY_DELETES }.sumOf { it.recordCount }),
    )
}
