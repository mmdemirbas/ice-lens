package model

/**
 * What `cherrypick_snapshot(table, snapshot_id)` does with a snapshot — `CherryPickOperation` at
 * 1.8.1, the publish half of write-audit-publish — decided from the metadata alone.
 *
 * In the order the operation decides it: an unknown id is refused; an `append` whose staged
 * `wap.id` a current ancestor already staged or published is refused as a duplicate
 * (`WapUtil.validateWapPublish`); then, at apply, a snapshot whose parent **is** the current
 * snapshot is **fast-forwarded** — main is pointed at it and no new snapshot is written, whatever
 * its operation; otherwise an `append` is **published**: a new commit on main carrying the picked
 * snapshot's added data files, with `source-snapshot-id` naming it and `published-wap-id` its
 * staged id, unless the snapshot is already an ancestor or some ancestor's `source-snapshot-id`
 * already names it (`CherrypickAncestorCommitException`); an `overwrite` written by
 * `replace-partitions` is published the same way with its deletes, provided its parent is a
 * current ancestor and no commit since touched the partitions it replaced (the last is checked
 * against the manifests at commit and not here); anything else — a delete, a plain overwrite, a
 * replace — cannot be picked and is refused unless it fast-forwards. `docs/fixtures/cherrypick.sql`
 * records the runs on `wap`, `rolled`, `branched` and `mor`.
 */
enum class CherryPickVerdict(val label: String, val moves: Boolean) {
    FAST_FORWARD("FAST-FORWARD", true),
    PUBLISH("PUBLISH", true),
    DUPLICATE_WAP("REFUSED", false),
    ALREADY_ANCESTOR("REFUSED", false),
    ALREADY_PICKED("REFUSED", false),
    OVERWRITE_OFF_LINE("REFUSED", false),
    NOT_PICKABLE("REFUSED", false),
    UNKNOWN_SNAPSHOT("REFUSED", false),
}

data class CherryPickPlan(
    val snapshotId: Long,
    val verdict: CherryPickVerdict,
    val reason: String,
    /** The staged `wap.id` a publish carries over as `published-wap-id`. */
    val wapId: String?,
    /** The picked snapshot's `added-data-files` and `deleted-data-files`, which a publish copies. */
    val addedDataFiles: Int?,
    val deletedDataFiles: Int?,
    /** A publish writes a snapshot; a fast-forward moves main and writes none. */
    val writesSnapshot: Boolean,
    /** Whether the publish copies deletes too — a dynamic overwrite — and so is checked for changed partitions at commit. */
    val replacesPartitions: Boolean,
)

/** `cherrypick_snapshot(table, [snapshotId])` on this metadata. */
fun TableMetadata.planCherryPick(snapshotId: Long): CherryPickPlan {
    val byId = snapshots.associateBy { it.snapshotId }
    val picked = byId[snapshotId]
        ?: return CherryPickPlan(snapshotId, CherryPickVerdict.UNKNOWN_SNAPSHOT, "Cannot cherry-pick unknown snapshot ID: $snapshotId", null, null, null, false, false)
    val ancestors = currentAncestorIds()
    val wapId = picked.wapId?.takeIf { it.isNotEmpty() }
    val added = picked.summary["added-data-files"]?.toIntOrNull()
    val deleted = picked.summary["deleted-data-files"]?.toIntOrNull()
    val operation = picked.summary["operation"]
    val replacesPartitions = operation == "overwrite" && picked.summary["replace-partitions"]?.toBoolean() == true
    val fastForward = if (currentSnapshotId != null && currentSnapshotId != -1L) picked.parentSnapshotId == currentSnapshotId else picked.parentSnapshotId == null
    fun plan(v: CherryPickVerdict, reason: String, writes: Boolean) = CherryPickPlan(snapshotId, v, reason, wapId, added, deleted, writes, replacesPartitions)

    val pickable = operation == "append" || replacesPartitions
    if (pickable && wapId != null) {
        val published = ancestors.any { id -> byId[id]?.let { it.wapId == wapId || it.publishedWapId == wapId } == true }
        if (published) return plan(CherryPickVerdict.DUPLICATE_WAP, "Duplicate request to cherry pick wap id that was published already: $wapId", false)
    }
    if (replacesPartitions && picked.parentSnapshotId != null && picked.parentSnapshotId !in ancestors) {
        return plan(CherryPickVerdict.OVERWRITE_OFF_LINE, "Cannot cherry-pick overwrite not based on an ancestor of the current state: $snapshotId", false)
    }
    if (!pickable && !fastForward) {
        return plan(CherryPickVerdict.NOT_PICKABLE, "Cannot cherry-pick snapshot $snapshotId: not append, dynamic overwrite, or fast-forward — its operation is ${operation ?: "unknown"}", false)
    }
    if (fastForward) {
        return plan(CherryPickVerdict.FAST_FORWARD, "its parent is the current snapshot: main is pointed at it and no new snapshot is written", false)
    }
    if (snapshotId in ancestors) return plan(CherryPickVerdict.ALREADY_ANCESTOR, "Cannot cherrypick snapshot $snapshotId: already an ancestor", false)
    val pickedBefore = ancestors.firstOrNull { id -> byId[id]?.sourceSnapshotId == snapshotId }
    if (pickedBefore != null) return plan(CherryPickVerdict.ALREADY_PICKED, "Cannot cherrypick snapshot $snapshotId: already picked to create ancestor $pickedBefore", false)
    val what = if (replacesPartitions) "a new commit on main replacing the partitions it wrote — its ${added ?: "?"} added and ${deleted ?: "?"} deleted data files copied, refused at commit if a later commit touched those partitions"
    else "a new commit on main carrying its ${added?.let { "$it added data file${if (it == 1) "" else "s"}" } ?: "added data files"}, with source-snapshot-id naming it" + (wapId?.let { " and published-wap-id $it" } ?: "")
    return plan(CherryPickVerdict.PUBLISH, what, true)
}
