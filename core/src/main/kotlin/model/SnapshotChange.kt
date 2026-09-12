package model

/** Whether a commit put a file into the table or took it out. */
enum class FileChange { ADDED, REMOVED }

/**
 * One file a commit added or removed, as the manifests that commit wrote record it.
 *
 * [recordCount] is rows for a data file and delete records for a delete file — `record_count`
 * means both, depending on `content`, which is why the content is carried rather than folded away.
 *
 * [sizeBytes] is the **blob's** size for a v3 deletion vector and the file's size for everything
 * else, which is what Iceberg's own `added-files-size` counts. One Puffin file holds a blob per
 * data file it covers, so summing the container's size once per vector would count the same bytes
 * as many times as the file has vectors: the `v3` fixture's delete records `added-files-size = 42`
 * for a 434-byte `.puffin`, and the 42 is `content_size_in_bytes`.
 */
data class ChangedFile(
    val path: String,
    val change: FileChange,
    val content: Int,
    val recordCount: Long,
    val sizeBytes: Long,
    /** The manifest that recorded this change. The commit wrote it. */
    val manifestPath: String,
)

/**
 * One manifest a snapshot lists, and which commit wrote it.
 *
 * [writtenBy] is `manifest_file.added_snapshot_id`: the commit whose manifest this is, or null
 * when the list omits the field and nothing can say. The snapshot *wrote* the manifests whose
 * [writtenBy] is its own id and *carried* the rest forward — which is the split
 * `rewrite_manifests` records as `manifests-created` and `manifests-kept`, and the one figure that
 * checks the attribution rule itself rather than the entries read under it.
 */
data class CommitManifest(
    val path: String,
    val content: Int,
    val writtenBy: Long?,
)

/**
 * One figure a snapshot's summary records about its own commit, beside the same figure counted
 * from the manifests that commit wrote.
 *
 * Deliberately the same shape as [ManifestTally] and deliberately a separate type: the idea is
 * the same — a claim beside the count that checks it — but the rule producing each side is not.
 * A manifest tally compares `manifest_file` against the entries inside one manifest; this
 * compares a snapshot's `summary` map against every manifest the snapshot added.
 */
data class CommitTally(
    val label: String,
    /** From the snapshot summary. Null when the writer said nothing — every key is optional. */
    val recorded: Long?,
    /** Folded from the entries of the manifests this snapshot added. */
    val counted: Long,
) {
    /** Null when there is nothing recorded to agree with. A missing key is not a disagreement. */
    val agrees: Boolean? = recorded?.let { it == counted }
}

/**
 * What one commit did to the table.
 *
 * The question a reader arrives with at a snapshot is "what changed here", and Iceberg answers it
 * twice without ever comparing the two. The `summary` map on the snapshot is what the *writer*
 * said it did — `added-data-files`, `deleted-records`, `added-files-size` — a set of strings in
 * `metadata.json`, written by the engine, that nothing on the read path verifies. The manifests
 * that commit wrote are what it *actually* did: an entry with `status = ADDED` is a file this
 * commit put in, `status = DELETED` is one it took out.
 *
 * Both are here, and [tallies] puts them side by side.
 *
 * **Attribution is by `manifest_file.added_snapshot_id`, not by walking the closure.** An entry's
 * status is relative to the snapshot that created the *manifest* holding it, and a manifest is
 * carried forward unchanged into every later snapshot that still needs its files — so a manifest
 * written by commit 3 still says `ADDED` when commit 9 lists it. Counting statuses across a
 * snapshot's whole manifest closure would credit every commit with all of its ancestors' work.
 *
 * [files] is the explanation and the figures are folded from it, never stored beside it — the
 * same rule `StatsDerivation` follows. Drop a file here and the count changes with it.
 */
data class SnapshotChange(
    val snapshotId: Long,
    val parentSnapshotId: Long?,
    /** `summary["operation"]` — append, overwrite, delete, replace. */
    val operation: String?,
    val summary: Map<String, String> = emptyMap(),
    val files: List<ChangedFile> = emptyList(),
    /** Every manifest the snapshot lists, with the commit that wrote it — see [CommitManifest]. */
    val manifests: List<CommitManifest> = emptyList(),
) {
    val added: List<ChangedFile> get() = files.filter { it.change == FileChange.ADDED }
    val removed: List<ChangedFile> get() = files.filter { it.change == FileChange.REMOVED }

    /** The manifests this commit wrote: `added_snapshot_id` is its own. */
    val manifestsWritten: Int get() = manifests.count { it.writtenBy == snapshotId }

    /** The manifests it carried forward from an earlier commit, unchanged. */
    val manifestsCarried: Int get() = manifests.count { it.writtenBy != null && it.writtenBy != snapshotId }

    /**
     * Manifests this snapshot lists whose `added_snapshot_id` is absent, so nothing can say which
     * commit wrote them.
     *
     * Reported rather than guessed at. A v1 manifest list may omit the field, and a commit whose
     * manifests cannot be attributed is a commit this cannot describe — which is a different
     * statement from "this commit changed nothing", and the inspector has to be able to tell the
     * reader which one it is looking at.
     */
    val unattributedManifests: Int get() = manifests.count { it.writtenBy == null }

    /** The first commit on a table, or a branch's root — nothing to have changed *from*. */
    val isRoot: Boolean get() = parentSnapshotId == null

    /**
     * Every figure the summary and the manifests both speak about.
     *
     * Delete files are one figure rather than three, and the key for it is `added-delete-files`.
     * The others are **breakdowns of that same total, not alternatives to it**: the `v3` fixture's
     * merge-on-read delete writes one deletion vector and records `added-delete-files = 1`,
     * `added-dvs = 1` and `added-position-deletes = 1` about it. Reading `added-dvs` as a v3
     * replacement and adding it to the total reports two delete files where one was written —
     * which is what this did until the fixture said otherwise.
     *
     * `added-position-deletes` and `added-equality-deletes` are **rows**, not files: a delete
     * file's `record_count` is how many positions or key tuples it holds, and the writer sums
     * them per kind. `rewrite_position_delete_files` is where that pair matters — it records
     * three positions removed and one added on `maint`, which is the two dangling deletes and the
     * live one going in, and only the live one coming out.
     *
     * `manifests-created` and `manifests-kept` are what `rewrite_manifests` says about the list
     * it wrote, and they check the attribution rule itself: written is every manifest whose
     * `added_snapshot_id` is this commit, kept is every other. Ordinary commits record neither.
     */
    val tallies: List<CommitTally> get() = fileTallies + manifestTallies

    private fun recorded(key: String): Long? = summary[key]?.toLongOrNull()

    /**
     * The two figures about the manifest list itself, kept apart from the file figures because
     * the panel treats them differently: the split is stated in prose for every commit, so the
     * rows are drawn only where a summary recorded something to check them against.
     */
    val manifestTallies: List<CommitTally>
        get() = listOf(
            CommitTally("Manifests written", recorded("manifests-created"), manifestsWritten.toLong()),
            CommitTally("Manifests kept", recorded("manifests-kept"), manifestsCarried.toLong()),
        )

    /** The figures about files — what was put in and taken out, by count, rows and bytes. */
    val fileTallies: List<CommitTally>
        get() {

            fun count(change: FileChange, predicate: (ChangedFile) -> Boolean): Long =
                files.count { it.change == change && predicate(it) }.toLong()

            fun rows(change: FileChange, predicate: (ChangedFile) -> Boolean): Long =
                files.filter { it.change == change && predicate(it) }.sumOf { it.recordCount }

            fun bytes(change: FileChange): Long =
                files.filter { it.change == change }.sumOf { it.sizeBytes }

            val isData = { f: ChangedFile -> f.content == DataFileContent.DATA }
            val isDelete = { f: ChangedFile -> f.content != DataFileContent.DATA }
            val isPositional = { f: ChangedFile -> f.content == DataFileContent.POSITION_DELETES }
            val isEquality = { f: ChangedFile -> f.content == DataFileContent.EQUALITY_DELETES }

            return listOf(
                CommitTally("Data files added", recorded("added-data-files"), count(FileChange.ADDED, isData)),
                CommitTally("Data files removed", recorded("deleted-data-files"), count(FileChange.REMOVED, isData)),
                CommitTally(
                    "Delete files added",
                    recorded("added-delete-files"),
                    count(FileChange.ADDED, isDelete),
                ),
                CommitTally(
                    "Delete files removed",
                    recorded("removed-delete-files"),
                    count(FileChange.REMOVED, isDelete),
                ),
                CommitTally("Records added", recorded("added-records"), rows(FileChange.ADDED, isData)),
                CommitTally("Records removed", recorded("deleted-records"), rows(FileChange.REMOVED, isData)),
                CommitTally("Position deletes added", recorded("added-position-deletes"), rows(FileChange.ADDED, isPositional)),
                CommitTally("Position deletes removed", recorded("removed-position-deletes"), rows(FileChange.REMOVED, isPositional)),
                CommitTally("Equality deletes added", recorded("added-equality-deletes"), rows(FileChange.ADDED, isEquality)),
                CommitTally("Equality deletes removed", recorded("removed-equality-deletes"), rows(FileChange.REMOVED, isEquality)),
                CommitTally("Bytes added", recorded("added-files-size"), bytes(FileChange.ADDED)),
                CommitTally("Bytes removed", recorded("removed-files-size"), bytes(FileChange.REMOVED)),
            )
        }

    /** Figures where the writer's claim and the manifests disagree. Empty is the healthy case. */
    val disagreements: List<CommitTally> get() = tallies.filter { it.agrees == false }
}

/**
 * Reads what one commit did out of the manifests it wrote.
 *
 * Takes the snapshot rather than the table because the answer needs nothing else: a commit's own
 * manifests carry its additions and its removals, and its summary carries the claim to check them
 * against.
 */
fun snapshotChangeOf(snapshot: UnifiedSnapshot): SnapshotChange {
    val snapshotId = snapshot.metadata.snapshotId ?: return SnapshotChange(
        snapshotId = 0L,
        parentSnapshotId = snapshot.metadata.parentSnapshotId,
        operation = snapshot.metadata.summary["operation"],
        summary = snapshot.metadata.summary,
    )

    val manifests = snapshot.manifests.map { manifest ->
        CommitManifest(
            path = manifest.metadata.manifestPath.orEmpty(),
            content = manifest.metadata.content ?: ManifestContent.DATA,
            writtenBy = manifest.metadata.addedSnapshotId,
        )
    }
    val files = buildList {
        snapshot.manifests.forEach { manifest ->
            if (manifest.metadata.addedSnapshotId != snapshotId) return@forEach

            manifest.dataFiles.forEach { file ->
                val entry = file.metadata
                val change = when (entry.status) {
                    ManifestEntryStatus.ADDED -> FileChange.ADDED
                    ManifestEntryStatus.DELETED -> FileChange.REMOVED
                    // EXISTING: carried into a rewritten manifest, so this commit did not touch it.
                    else -> return@forEach
                }
                val dataFile = entry.dataFile
                add(
                    ChangedFile(
                        path = dataFile?.filePath.orEmpty(),
                        change = change,
                        content = dataFile?.content ?: DataFileContent.DATA,
                        recordCount = dataFile?.recordCount ?: 0L,
                        // The blob, not the container — see [ChangedFile.sizeBytes].
                        sizeBytes = dataFile?.contentSizeInBytes ?: dataFile?.fileSizeInBytes ?: 0L,
                        manifestPath = manifest.metadata.manifestPath.orEmpty(),
                    ),
                )
            }
        }
    }

    return SnapshotChange(
        snapshotId = snapshotId,
        parentSnapshotId = snapshot.metadata.parentSnapshotId,
        operation = snapshot.metadata.summary["operation"],
        summary = snapshot.metadata.summary,
        files = files,
        manifests = manifests,
    )
}
