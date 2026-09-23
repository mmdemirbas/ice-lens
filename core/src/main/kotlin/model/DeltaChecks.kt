package model

import service.DeltaCheckpointFile

/**
 * What a commit's `operationMetrics` say against what its own actions count — the Delta twin of
 * [SnapshotChange.tallies]. A writer records the metrics while it runs; the actions are the
 * commit. Nothing on a read path compares the two.
 *
 * Each figure's counted side follows what delta-spark 3.2.1 was seen to record on the fixtures
 * (each fixture script's `.out` under `docs/fixtures/delta/`), not a document — the metrics are the engine's, and the protocol
 * does not define them:
 * - A file a commit removes and adds back under another vector is not a removed or an added
 *   file; `numAddedFiles` / `numAddedBytes` count the adds of paths the commit does not also
 *   remove, and `numRemovedBytes` the removes of paths it does not add back.
 * - `numRemovedFiles` is the same on a `DELETE`, and on an `UPDATE` every remove: `ddv`'s
 *   update put a vector on one file and recorded one file removed and zero bytes.
 * - `numDeletionVectorsAdded` / `Removed` are the adds and removes carrying a vector,
 *   `numDeletionVectorsUpdated` the paths with a vector on both sides, and under vectors a
 *   `DELETE`'s `numDeletedRows` is the cardinality the commit's vectors gained.
 * - A `WRITE`'s `numFiles`, `numOutputRows` and `numOutputBytes` are its adds.
 * A figure the actions cannot count — a row count where a remove carries no statistics — is left
 * out rather than compared.
 */
fun deltaCommitTallies(commit: DeltaCommit): List<CommitTally> {
    val metrics = commit.commitInfo?.operationMetrics ?: return emptyList()
    val operation = commit.commitInfo?.operation
    val adds = commit.adds
    val removes = commit.removes
    val addedPaths = adds.map { it.path }.toSet()
    val removedPaths = removes.map { it.path }.toSet()
    val newAdds = adds.filter { it.path !in removedPaths }
    val goneRemoves = removes.filter { it.path !in addedPaths }
    val tallies = mutableListOf<CommitTally>()
    fun tally(key: String, counted: Long?) {
        val recorded = metrics[key]?.toLongOrNull() ?: return
        counted ?: return
        tallies += CommitTally(key, recorded, counted)
    }
    fun rows(files: List<DeltaAddFile>): Long? = files.map { it.parsedStats?.numRecords }.let { r -> if (r.any { it == null }) null else r.sumOf { it!! } }

    tally("numFiles", adds.size.toLong())
    tally("numOutputRows", rows(adds))
    tally("numOutputBytes", adds.sumOf { it.size ?: 0L })
    tally("numAddedFiles", newAdds.size.toLong())
    tally("numAddedBytes", newAdds.sumOf { it.size ?: 0L })
    tally("numRemovedFiles", if (operation == "UPDATE") removes.size.toLong() else goneRemoves.size.toLong())
    tally("numRemovedBytes", goneRemoves.sumOf { it.size ?: 0L })
    tally("numAddedChangeFiles", commit.cdcs.size.toLong())
    tally("numDeletionVectorsAdded", adds.count { it.deletionVector != null }.toLong())
    tally("numDeletionVectorsRemoved", removes.count { it.deletionVector != null }.toLong())
    val vectorPathsAdded = adds.filter { it.deletionVector != null }.map { it.path }.toSet()
    tally("numDeletionVectorsUpdated", removes.count { it.deletionVector != null && it.path in vectorPathsAdded }.toLong())
    if (operation == "DELETE" && adds.any { it.deletionVector != null }) {
        val gained = adds.sumOf { it.deletionVector?.cardinality ?: 0L } - removes.filter { it.path in addedPaths }.sumOf { it.deletionVector?.cardinality ?: 0L }
        tally("numDeletedRows", gained)
    }
    return tallies
}

/**
 * A checkpoint against the replay of the commits up to its version — two readings of one state.
 * [fromCommits] is false where a commit at or below the version is gone, which is the normal
 * state of a log past its retention and not a disagreement: then nothing is compared.
 */
data class DeltaCheckpointCheck(
    val version: Long,
    val fromCommits: Boolean,
    val readError: String? = null,
    val checkpointFileCount: Int = 0,
    val checkpointTombstoneCount: Int = 0,
    /** Live in the checkpoint, not in the replay — a file a reader starting there reads and should not. */
    val onlyInCheckpoint: List<DeltaFileKey> = emptyList(),
    /** Live in the replay, missing from the checkpoint — a file a reader starting there loses. */
    val onlyInReplay: List<DeltaFileKey> = emptyList(),
    /** Tombstones the checkpoint holds that the replay does not. */
    val tombstonesOnlyInCheckpoint: List<DeltaFileKey> = emptyList(),
    /** Tombstones the replay holds and the checkpoint dropped — allowed once past `delta.deletedFileRetentionDuration`. */
    val tombstonesDropped: Int = 0,
    val protocolAgrees: Boolean = true,
    val metadataAgrees: Boolean = true,
) {
    val agrees: Boolean
        get() = readError == null && onlyInCheckpoint.isEmpty() && onlyInReplay.isEmpty() &&
            tombstonesOnlyInCheckpoint.isEmpty() && protocolAgrees && metadataAgrees
}

fun DeltaUnifiedTableModel.checkDeltaCheckpoint(checkpoint: DeltaCheckpointFile): DeltaCheckpointCheck {
    val read = runCatching { service.DeltaReader.readCheckpoint(checkpoint, listing.logDir) }
        .getOrElse { return DeltaCheckpointCheck(checkpoint.version, fromCommits = false, readError = it.message ?: it.javaClass.simpleName) }
    val fromCheckpoint = DeltaReplay(null).also { it.apply(read.allActions) }.state(checkpoint.version, checkpoint.version, emptyList())
    val replay = stateFromCommits(checkpoint.version)
        ?: return DeltaCheckpointCheck(checkpoint.version, fromCommits = false, checkpointFileCount = fromCheckpoint.files.size, checkpointTombstoneCount = fromCheckpoint.tombstones.size)
    return DeltaCheckpointCheck(
        version = checkpoint.version,
        fromCommits = true,
        checkpointFileCount = fromCheckpoint.files.size,
        checkpointTombstoneCount = fromCheckpoint.tombstones.size,
        onlyInCheckpoint = (fromCheckpoint.files.keys - replay.files.keys).toList(),
        onlyInReplay = (replay.files.keys - fromCheckpoint.files.keys).toList(),
        tombstonesOnlyInCheckpoint = (fromCheckpoint.tombstones.keys - replay.tombstones.keys).toList(),
        tombstonesDropped = (replay.tombstones.keys - fromCheckpoint.tombstones.keys).size,
        protocolAgrees = fromCheckpoint.protocol == replay.protocol,
        metadataAgrees = fromCheckpoint.metadata == replay.metadata,
    )
}

/**
 * `_delta_log/_last_checkpoint` against the checkpoint it names: `size` is the checkpoint's action
 * count, `numOfAddFiles` its adds, `sizeInBytes` its files' total length, `parts` its part count.
 * A reader starts its listing at that version, so a pointer past the log's newest checkpoint, or
 * at one that is not there, is where a reader's replay begins wrong. `checksum` is not compared:
 * the spec computes it over a canonical JSON form this does not rebuild.
 */
fun DeltaUnifiedTableModel.lastCheckpointTallies(): List<CommitTally> {
    val last = lastCheckpoint ?: return emptyList()
    val tallies = mutableListOf<CommitTally>()
    val candidates = listing.checkpoints[last.version].orEmpty()
    tallies += CommitTally("_last_checkpoint names a checkpoint at version", last.version, candidates.firstOrNull()?.version ?: -1)
    val newest = listing.checkpoints.keys.maxOrNull()
    if (newest != null) tallies += CommitTally("newest checkpoint version", last.version, newest)
    val cp = candidates.firstOrNull { it.complete } ?: return tallies
    val read = checkpointRead(cp) ?: return tallies
    last.size?.let { tallies += CommitTally("size", it, read.allActions.size.toLong()) }
    last.numOfAddFiles?.let { tallies += CommitTally("numOfAddFiles", it, read.allActions.count { a -> a.add != null }.toLong()) }
    last.parts?.let { tallies += CommitTally("parts", it.toLong(), cp.parts.size.toLong()) }
    last.sizeInBytes?.let { recorded ->
        val onDisk = cp.parts.sumOf { runCatching { java.nio.file.Files.size(it) }.getOrDefault(0L) }
        tallies += CommitTally("sizeInBytes", recorded, onDisk)
    }
    return tallies
}
