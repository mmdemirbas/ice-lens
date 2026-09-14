package model

/**
 * What `fast_forward` does on each format — and the two procedures share a name and not an
 * operation.
 *
 * **Iceberg** (`UpdateSnapshotReferencesOperation.replaceBranch` with `fastForward = true`, 1.8.1;
 * `CALL fast_forward(table, branch, to)`) moves one ref. `to` must be a ref, branch or tag; a
 * `branch` the table lacks is created at `to`'s snapshot; a tag cannot be moved; the same snapshot
 * on both is nothing to do; otherwise the branch's tip must be an ancestor of `to`'s snapshot
 * (`SnapshotUtil.isAncestorOf`), or the call fails with
 * `Cannot fast-forward: <branch> is not an ancestor of <to>`. Nothing is deleted and no snapshot
 * is written: the branch's old tip is an ancestor of its new one, so every commit stays reachable.
 * Two lines that have both moved on since they forked cannot be fast-forwarded either way.
 *
 * **Paimon** (`FileSystemBranchManager.fastForward`, release-1.3.1; `CALL sys.fast_forward(table,
 * branch)`) replaces main's history from the branch's earliest snapshot id on. It deletes main's
 * `snapshot/snapshot-<id>` at or above that id, `schema/schema-<id>` at or above the earliest
 * snapshot's schema id, every tag whose snapshot is at or above the id, and `LATEST`; then copies
 * the branch's `snapshot/`, `schema/` and `tag/` over main's, overwriting. Main's own commits from
 * that id on are not merged — they are gone, and the files they wrote are left on disk named by
 * nothing, the rollback's leftovers again. `main` cannot be fast-forwarded, nor the branch a
 * session is on, and a branch with no snapshot refuses (`Cannot fast forward branch <b>, because
 * it does not have snapshot`). `brf` is `br` after `fast_forward('dev')`: main's snapshots 2 and
 * 3 gone, dev's 1 and 2 in their place, the tag on 1 rewritten from the branch's copy, and eight
 * files named by nothing.
 */

enum class IcebergFastForwardVerdict(val label: String) {
    MOVES("MOVES"),
    CREATES("CREATES"),
    NOTHING_TO_DO("nothing to do"),
    NOT_AN_ANCESTOR("REFUSED"),
    NOT_A_BRANCH("REFUSED"),
    NO_SUCH_REF("REFUSED"),
}

data class IcebergFastForwardPlan(
    val branch: String,
    val to: String,
    val verdict: IcebergFastForwardVerdict,
    /** The branch's tip before; null when the call creates the branch. */
    val from: Long?,
    /** `to`'s snapshot. */
    val target: Long?,
    /** The commits the branch takes on, newest first — `to`'s ancestry down to and excluding the old tip. */
    val gained: List<Long>,
    val reason: String,
) {
    val moves: Boolean get() = verdict == IcebergFastForwardVerdict.MOVES || verdict == IcebergFastForwardVerdict.CREATES
}

/** `fast_forward(table, branch = [branch], to = [to])` on this metadata version. */
fun TableMetadata.planFastForward(branch: String, to: String): IcebergFastForwardPlan {
    val byId = snapshots.associateBy { it.snapshotId }
    fun ancestors(tip: Long?): Sequence<Snapshot> = generateSequence(byId[tip]) { byId[it.parentSnapshotId] }
    val toRef = refs[to] ?: return IcebergFastForwardPlan(branch, to, IcebergFastForwardVerdict.NO_SUCH_REF, refs[branch]?.snapshotId, null, emptyList(), "Ref does not exist: $to")
    val target = toRef.snapshotId
    val branchRef = refs[branch]
        ?: return IcebergFastForwardPlan(branch, to, IcebergFastForwardVerdict.CREATES, null, target, emptyList(), "no ref named $branch: the call creates the branch at $to's snapshot")
    val from = branchRef.snapshotId
    if (branchRef.type != "branch") {
        return IcebergFastForwardPlan(branch, to, IcebergFastForwardVerdict.NOT_A_BRANCH, from, target, emptyList(), "Ref $branch is a tag not a branch")
    }
    if (from == target) {
        return IcebergFastForwardPlan(branch, to, IcebergFastForwardVerdict.NOTHING_TO_DO, from, target, emptyList(), "$branch and $to are at the same snapshot")
    }
    val path = ancestors(target).takeWhile { it.snapshotId != from }.mapNotNull { it.snapshotId }.toList()
    val reaches = ancestors(target).any { it.snapshotId == from }
    return if (reaches) {
        IcebergFastForwardPlan(branch, to, IcebergFastForwardVerdict.MOVES, from, target, path, "$branch's tip is an ancestor of $to's snapshot: the branch moves to it and takes on ${path.size} commit${if (path.size == 1) "" else "s"}")
    } else {
        IcebergFastForwardPlan(branch, to, IcebergFastForwardVerdict.NOT_AN_ANCESTOR, from, target, emptyList(), "Cannot fast-forward: $branch is not an ancestor of $to — the two lines have both moved on since they forked")
    }
}

/** Every branch against every other ref, branches first in name order, `main` leading — the whole matrix a reader picks a row from. */
fun TableMetadata.fastForwardPlans(): List<IcebergFastForwardPlan> {
    val ordered = refs.entries.sortedWith(compareBy({ it.key != "main" }, { it.value.type != "branch" }, { it.key }))
    return ordered.filter { it.value.type == "branch" }.flatMap { (branch, _) ->
        ordered.filter { it.key != branch }.map { (to, _) -> planFastForward(branch, to) }
    }
}

data class PaimonFastForwardPlan(
    val branch: String,
    /** Why the call would fail, or null. */
    val refused: String?,
    /** The branch's earliest snapshot id — main loses everything from it on. */
    val earliestId: Long?,
    val removedSnapshots: List<Long>,
    val removedSchemas: List<Int>,
    val removedTags: List<String>,
    /** The branch's snapshot ids, schema ids and tags, copied over main's. */
    val arrivingSnapshots: List<Long>,
    val arrivingSchemas: List<Int>,
    val arrivingTags: List<String>,
    /** Main's snapshot ids that both lines hold — overwritten by the branch's file of the same name. */
    val overwrittenSnapshots: List<Long>,
    /** Of those, the ids whose manifest lists are the branch's too — the same commit, copied at the branch's creation. */
    val identicalSnapshots: List<Long>,
    /** The files main's removed commits wrote that nothing left names, the rollback's leftovers. */
    val leftovers: List<PaimonExpiryFile>,
) {
    val removesAnything: Boolean get() = removedSnapshots.isNotEmpty() || removedSchemas.isNotEmpty() || removedTags.isNotEmpty()
    /** Main's commits the call forgets: removed, less the ones the branch carries verbatim. */
    val droppedCommits: Int get() = removedSnapshots.size - identicalSnapshots.size
    val leftoverBytes: Long get() = leftovers.sumOf { it.sizeBytes ?: 0L }
}

/** `sys.fast_forward(table, branch = [branch])` on main, as `FileSystemBranchManager.fastForward` does it; null for a branch the table lacks. */
fun PaimonExpiryFileInput.planFastForward(branch: String): PaimonFastForwardPlan? {
    if (branch == "main") return PaimonFastForwardPlan(branch, "Branch name 'main' do not use in fast-forward.", null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    val view = branches.firstOrNull { it.name == branch } ?: return null
    val earliest = view.snapshots.keys.minOrNull()
        ?: return PaimonFastForwardPlan(branch, "Cannot fast forward branch $branch, because it does not have snapshot.", null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), view.tags.map { it.name }.sorted(), emptyList(), emptyList(), emptyList())
    val earliestSchema = view.snapshots.getValue(earliest).metadata.schemaId?.toInt() ?: 0
    val removedSnapshots = snapshots.keys.filter { it >= earliest }.sortedDescending()
    val removedSchemas = schemaIds.filter { it >= earliestSchema }.sortedDescending()
    val (removedTags, keptTags) = tags.partition { (it.snapshot.id ?: Long.MIN_VALUE) >= earliest }
    val overwritten = removedSnapshots.filter { it in view.snapshots }.sorted()
    val kept = snapshots.filterKeys { it < earliest }.values + keptTags.map { it.snapshot } +
        view.snapshots.values + view.tags.map { it.snapshot }
    val keptNames = kept.flatMap { it.everyName(PaimonExpiryFileReason.FAST_FORWARDED).keys }.toSet()
    val leftovers = linkedMapOf<String, PaimonExpiryFile>()
    (removedSnapshots.mapNotNull { snapshots[it] } + removedTags.map { it.snapshot }).forEach { s ->
        s.everyName(PaimonExpiryFileReason.FAST_FORWARDED).forEach { (name, file) -> if (name !in keptNames) leftovers.putIfAbsent(name, file) }
    }
    return PaimonFastForwardPlan(
        branch = branch,
        refused = null,
        earliestId = earliest,
        removedSnapshots = removedSnapshots,
        removedSchemas = removedSchemas,
        removedTags = removedTags.map { it.name }.sorted(),
        arrivingSnapshots = view.snapshots.keys.sorted(),
        arrivingSchemas = view.schemaIds.sorted(),
        arrivingTags = view.tags.map { it.name }.sorted(),
        overwrittenSnapshots = overwritten,
        identicalSnapshots = overwritten.filter { id ->
            val ours = snapshots.getValue(id).metadata
            val theirs = view.snapshots.getValue(id).metadata
            ours.baseManifestList == theirs.baseManifestList && ours.deltaManifestList == theirs.deltaManifestList
        },
        leftovers = leftovers.values.toList(),
    )
}
