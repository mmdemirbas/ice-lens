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
 * - An `UPDATE`'s `numRemovedBytes` counts its **change files** too: `UpdateCommand` splits the
 *   actions its rewrite returned into `AddFile` and the rest, and sums the rest as removed bytes,
 *   so `dcdf`'s update recorded 1,663 bytes removed for a 660-byte file and a 1,003-byte cdc file.
 * - A `MERGE` records the same figures as `numTarget…`, counted by the same rules.
 * - A `RESTORE` counts its adds as `numRestoredFiles` / `restoredFilesSize`, its removes as
 *   `numRemovedFiles` / `removedFilesSize`, and the table it leaves as `numOfFilesAfterRestore` /
 *   `tableSizeAfterRestore` — which [stateAfter] answers, the replay at the commit's version.
 * - An `OPTIMIZE` records the smallest and largest file it wrote, and the vectors it purged with
 *   their rows and bytes.
 * A figure the actions cannot count — a row count where a remove carries no statistics — is left
 * out rather than compared.
 */
fun deltaCommitTallies(commit: DeltaCommit, stateAfter: () -> DeltaState? = { null }): List<CommitTally> {
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

    val changeBytes = commit.cdcs.sumOf { it.size ?: 0L }
    val vectorPathsAdded = adds.filter { it.deletionVector != null }.map { it.path }.toSet()
    val vectorsAdded = adds.count { it.deletionVector != null }.toLong()
    val vectorsRemoved = removes.count { it.deletionVector != null }.toLong()
    val vectorsUpdated = removes.count { it.deletionVector != null && it.path in vectorPathsAdded }.toLong()

    // Files written, not files carried: a path re-added under a new vector is neither (dcdfdv's
    // MERGE recorded 2 rows output where its adds, the re-added file included, hold 5).
    tally("numFiles", newAdds.size.toLong())
    tally("numOutputRows", rows(newAdds))
    tally("numOutputBytes", newAdds.sumOf { it.size ?: 0L })
    tally("numAddedFiles", newAdds.size.toLong())
    tally("numAddedBytes", newAdds.sumOf { it.size ?: 0L })
    tally("numRemovedFiles", if (operation == "UPDATE") removes.size.toLong() else goneRemoves.size.toLong())
    tally("numRemovedBytes", goneRemoves.sumOf { it.size ?: 0L } + if (operation == "UPDATE") changeBytes else 0L)
    tally("numAddedChangeFiles", commit.cdcs.size.toLong())
    tally("numDeletionVectorsAdded", vectorsAdded)
    tally("numDeletionVectorsRemoved", vectorsRemoved)
    tally("numDeletionVectorsUpdated", vectorsUpdated)
    if (operation == "MERGE") {
        tally("numTargetFilesAdded", newAdds.size.toLong())
        tally("numTargetFilesRemoved", goneRemoves.size.toLong())
        tally("numTargetBytesAdded", newAdds.sumOf { it.size ?: 0L })
        tally("numTargetBytesRemoved", goneRemoves.sumOf { it.size ?: 0L })
        tally("numTargetChangeFilesAdded", commit.cdcs.size.toLong())
        tally("numTargetDeletionVectorsAdded", vectorsAdded)
        tally("numTargetDeletionVectorsRemoved", vectorsRemoved)
        tally("numTargetDeletionVectorsUpdated", vectorsUpdated)
    }
    if (operation == "RESTORE") {
        tally("numRestoredFiles", adds.size.toLong())
        tally("restoredFilesSize", adds.sumOf { it.size ?: 0L })
        tally("removedFilesSize", removes.sumOf { it.size ?: 0L })
        if (metrics.keys.any { it == "numOfFilesAfterRestore" || it == "tableSizeAfterRestore" }) {
            stateAfter()?.let { after ->
                tally("numOfFilesAfterRestore", after.files.size.toLong())
                tally("tableSizeAfterRestore", after.sizeBytes)
            }
        }
    }
    if (operation == "OPTIMIZE") {
        tally("minFileSize", adds.minOfOrNull { it.size ?: 0L })
        tally("maxFileSize", adds.maxOfOrNull { it.size ?: 0L })
        tally("numDeletionVectorRowsRemoved", removes.sumOf { it.deletionVector?.cardinality ?: 0L })
        tally("numDeletionVectorBytesRemoved", removes.sumOf { (it.deletionVector?.sizeInBytes ?: 0).toLong() })
    }
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
    fun sizeOf(p: java.nio.file.Path) = runCatching { java.nio.file.Files.size(p) }.getOrDefault(0L)
    // A V2 checkpoint's size is its top-level file and its sidecars together: dv2 records 10,630
    // bytes for a 765-byte JSON file naming a 9,865-byte sidecar.
    last.sizeInBytes?.let { recorded ->
        tallies += CommitTally("sizeInBytes", recorded, cp.parts.sumOf(::sizeOf) + read.sidecars.sumOf { sizeOf(it.first) })
    }
    last.v2Checkpoint?.let { v2 ->
        val top = cp.parts.singleOrNull()
        tallies += CommitTally("v2Checkpoint names the checkpoint", 1, if (top?.fileName?.toString() == v2.path.substringAfterLast('/')) 1 else 0)
        if (top != null) v2.sizeInBytes?.let { tallies += CommitTally("v2Checkpoint sizeInBytes", it, sizeOf(top)) }
        v2.nonFileActions?.let { tallies += CommitTally("v2Checkpoint nonFileActions", it.size.toLong(), read.actions.count { a -> a.add == null && a.remove == null && a.sidecar == null }.toLong()) }
        v2.sidecarFiles?.let { recorded ->
            tallies += CommitTally("v2Checkpoint sidecars", recorded.size.toLong(), read.sidecars.size.toLong())
            val byName: Map<String, java.nio.file.Path> = read.sidecars.associate { it.first.fileName.toString() to it.first }
            recorded.forEach { sc ->
                val file = byName[sc.path.substringAfterLast('/')]
                val onDisk: Long = if (file == null) -1L else sizeOf(file)
                sc.sizeInBytes?.let { tallies += CommitTally("sidecar ${sc.path.substringAfterLast('/').take(24)}… sizeInBytes", it, onDisk) }
            }
        }
    }
    return tallies
}

/**
 * One commit's `inCommitTimestamp` against the one before it. The protocol makes it the commit's
 * authoritative time from `delta.inCommitTimestampEnablementVersion` on and requires it to be
 * **greater** than the previous commit's, since a time travel by timestamp searches the versions
 * by it; a clock that went backwards on a writer is what this finds. [timestamp] null is a commit
 * the feature covers that recorded none. [previous] is null for the first covered commit, or one
 * whose predecessor the log no longer holds.
 */
data class InCommitTimestampCheck(val version: Long, val timestamp: Long?, val previous: Long?) {
    val agrees: Boolean get() = timestamp != null && (previous == null || timestamp > previous)
}

/**
 * [InCommitTimestampCheck] for every commit the feature covers: from the enablement version the
 * table records, or from version 0 where the feature was on from the start and records none.
 * delta-spark 3.2.1 spells the properties with a `-preview` suffix; both spellings are read.
 */
fun DeltaUnifiedTableModel.inCommitTimestampChecks(): List<InCommitTimestampCheck> {
    val config = current?.metadata?.configuration ?: return emptyList()
    fun conf(key: String) = config[key] ?: config["$key-preview"]
    val enabled = conf("delta.enableInCommitTimestamps").equals("true", ignoreCase = true)
    if (!enabled) return emptyList()
    val from = conf("delta.inCommitTimestampEnablementVersion")?.toLongOrNull() ?: 0L
    return commits.filter { it.version >= from }.sortedBy { it.version }.map { c ->
        val previous = commitByVersion[c.version - 1]?.takeIf { c.version - 1 >= from }?.commitInfo?.inCommitTimestamp
        InCommitTimestampCheck(c.version, c.commitInfo?.inCommitTimestamp, previous)
    }
}
