package model

/**
 * What Delta's change data feed publishes for each version, as `CDCReader.changesToDF` builds it
 * at 3.2.1 — the other side of `delta.enableChangeDataFeed`, and what `table_changes` returns.
 *
 * A version is read one of two ways. **A commit that wrote `cdc` actions is read from those files
 * alone**: a rewrite (an `UPDATE`, a `MERGE`, a `DELETE` that keeps some of a file's rows) writes
 * its changes under `_change_data/` with `_change_type` beside the cells, and its data files are
 * not read for the feed. **Any other commit is read from its file actions** with `dataChange`: a
 * file only added is its rows as `insert`, a file only removed its rows as `delete`, each less the
 * rows its own vector marks; a path removed and added back in the one commit is a vector changing,
 * and the rows the new vector marks and the old did not are `delete`, the reverse `insert` (a
 * `RESTORE`'s). A `MERGE` recording no row inserted, updated or deleted is skipped whatever its
 * actions (`shouldSkipFileActionsInCommit`), and a commit with `dataChange = false` throughout —
 * an `OPTIMIZE` — publishes nothing.
 */
data class DeltaChangeFeedInputs(
    /** The versions that publish something, oldest first — the newest [MAX_HISTORY_SNAPSHOTS] of them. */
    val versions: List<DeltaChangeVersion>,
    /** How many versions in the feed's range publish something, so a capped trace can say so. */
    val publishing: Int,
    /** The first version of the range: the feed is read only while every version in it has the feed on. */
    val fromVersion: Long,
    /** The newest schema, which every source is read under — the history's rule. */
    val struct: DeltaStructType,
    /** Where a relative deletion vector's file is resolved from. */
    val tableRoot: java.nio.file.Path,
) {
    val capped: Boolean get() = versions.size < publishing
    val read: DeltaReadSchema by lazy { deltaReadSchema(struct) }

    val rule: String
        get() = "delta.enableChangeDataFeed from version $fromVersion: a commit that rewrote rows is read from the cdc files it wrote under _change_data/, " +
            "with _change_type beside the cells; any other commit from its files — an added file's rows as insert, a removed file's as delete, " +
            "each less the rows its vector marks, and a vector replaced in place as delete for the rows it newly marks"
}

data class DeltaChangeVersion(val version: Long, val timestampMs: Long?, val operation: String?, val sources: List<DeltaChangeSource>)

enum class DeltaChangeSourceKind {
    /** A `cdc` file: its rows carry their own `_change_type`. */
    CHANGE_FILE,
    /** A file only added: its rows are inserts, less the rows [DeltaChangeSource.excluded] marks. */
    INSERTED,
    /** A file only removed: its rows are deletes, less the rows [DeltaChangeSource.excluded] marks. */
    DELETED,
    /** A path removed and added back under another vector: [DeltaChangeSource.newer] less [DeltaChangeSource.older] is deleted, the reverse inserted. */
    VECTOR_CHANGED,
}

data class DeltaChangeSource(
    val kind: DeltaChangeSourceKind,
    /** The path as the log records it. */
    val path: String,
    val localPath: String,
    /** The partition values by the newest schema's names; a Delta file does not hold them. */
    val constants: Map<String, String?>,
    val excluded: DeltaDeletionVector? = null,
    val older: DeltaDeletionVector? = null,
    val newer: DeltaDeletionVector? = null,
)

/** `_change_type` as the Paimon row kind it is the same thing as — the one vocabulary [Changelog] records carry. */
fun deltaChangeKind(changeType: String?): Int? = when (changeType) {
    "insert" -> PaimonRowKind.INSERT
    "update_preimage" -> PaimonRowKind.UPDATE_BEFORE
    "update_postimage" -> PaimonRowKind.UPDATE_AFTER
    "delete" -> PaimonRowKind.DELETE
    else -> null
}

/**
 * The feed's range and sources — see [DeltaChangeFeedInputs]. Null where the current metadata
 * does not enable the feed. The range runs back from the newest version while the metadata in
 * force enables the feed and the version can be rebuilt; `table_changes` refuses a range that
 * crosses a version with the feed off, so nothing older is read.
 */
fun DeltaUnifiedTableModel.deltaChangeFeedInputs(): DeltaChangeFeedInputs? {
    val metadata = current?.metadata ?: return null
    val struct = metadata.schema ?: return null
    if (!metadata.changeDataFeedEnabled) return null
    val physicalToName = struct.fields.associate { it.physicalName to it.name }
    fun constantsOf(values: Map<String, String?>) = values.mapKeys { (k, _) -> physicalToName[k] ?: k }
    fun local(path: String) = runCatching { resolve(path).toString() }.getOrElse { path }

    val range = mutableListOf<Long>()
    for (v in versions.sortedDescending()) {
        val state = stateAt(v).getOrNull() ?: break
        if (state.metadata?.changeDataFeedEnabled != true) break
        range += v
    }
    val publishing = range.sorted().mapNotNull { v ->
        val commit = commitByVersion[v] ?: return@mapNotNull null
        val sources = when {
            commit.cdcs.isNotEmpty() -> commit.cdcs.map { DeltaChangeSource(DeltaChangeSourceKind.CHANGE_FILE, it.path, local(it.path), constantsOf(it.partitionValues)) }
            commit.skipsFileActionsInFeed -> emptyList()
            else -> {
                val added = commit.adds.filter { it.dataChange != false }.associateBy { it.path }
                val removed = commit.removes.filter { it.dataChange != false }.associateBy { it.path }
                added.values.map { add ->
                    val remove = removed[add.path]
                    if (remove == null) DeltaChangeSource(DeltaChangeSourceKind.INSERTED, add.path, local(add.path), constantsOf(add.partitionValues), excluded = add.deletionVector)
                    else DeltaChangeSource(DeltaChangeSourceKind.VECTOR_CHANGED, add.path, local(add.path), constantsOf(add.partitionValues), older = remove.deletionVector, newer = add.deletionVector)
                } + removed.values.filter { it.path !in added }.map { remove ->
                    DeltaChangeSource(DeltaChangeSourceKind.DELETED, remove.path, local(remove.path), constantsOf(remove.partitionValues.orEmpty()), excluded = remove.deletionVector)
                }
            }
        }
        sources.takeIf { it.isNotEmpty() }?.let { DeltaChangeVersion(v, commit.timestampMs, commit.commitInfo?.operation, it) }
    }
    return DeltaChangeFeedInputs(publishing.takeLast(MAX_HISTORY_SNAPSHOTS), publishing.size, range.minOrNull() ?: return null, struct, path)
}

/** A `MERGE` recording that it inserted, updated and deleted nothing — the feed reads none of its file actions (`CDCReader.shouldSkipFileActionsInCommit`). */
private val DeltaCommit.skipsFileActionsInFeed: Boolean
    get() {
        val info = commitInfo ?: return false
        val metrics = info.operationMetrics ?: return false
        return info.operation == "MERGE" && listOf("numTargetRowsInserted", "numTargetRowsUpdated", "numTargetRowsDeleted").all { metrics[it] == "0" }
    }
