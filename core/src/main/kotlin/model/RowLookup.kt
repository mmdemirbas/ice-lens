package model

/**
 * What finding a row takes, read off the current snapshot: every live data file with the path
 * DuckDB opens it at, every delete file with what deciding a row's fate needs, and the pairing
 * [deleteReach] already made between them. The reading itself — DuckDB over the data files the
 * filter leaves, then the delete files over each hit — is `service/RowLookup.kt`; this is the
 * part that needs no file opened, and it is a `DeferredRead` on the table node because it
 * walks the current snapshot's closure.
 *
 * The question it exists for is "is this row live, and if not, what removed it": a scan prunes
 * to the files that *may* hold the row, but only the bytes say which does, and only the delete
 * files paired with that file say whether the row survives them. The lookup applies the three
 * delete kinds the way a scan does — a vector by the position's bit, a positional delete by
 * `(file_path, pos)`, an equality delete by the row's values in its `equality_ids` columns —
 * and the sequence rule is already in the pairing.
 */
data class LookupDataFile(
    /** The path the manifest records — what a positional delete's `file_path` names. */
    val recordedPath: String,
    /** Where it is opened from. */
    val localPath: String,
    val format: String?,
    val recordCount: Long?,
)

data class LookupDeleteFile(
    val recordedPath: String,
    val localPath: String,
    val kind: DeleteFileKind,
    /** A vector's blob, for [service.PuffinReader.readDeletionVector]. */
    val contentOffset: Long? = null,
    val contentSizeInBytes: Long? = null,
    val recordCount: Long? = null,
    /** An equality delete's field ids, resolved to the current schema's column names where it names them. */
    val equalityColumns: List<String> = emptyList(),
)

/**
 * What a looked-up row turned out to be. The first four are Iceberg's — a delete file of each
 * kind, or none — and the next two are Paimon's, where a primary-key file holds every write as
 * a row and a row is gone when a later write for its key exists or when it is itself the marker
 * of one. [UNKNOWN] is the honest answer where a rule could not be applied, never a guess.
 */
enum class RowFate(val label: String) {
    LIVE("live"),
    VECTOR_DELETED("deleted by a vector"),
    POSITION_DELETED("deleted by position"),
    EQUALITY_DELETED("deleted by equality"),
    /** A Paimon `-D` or `-U` record: the marker a delete or an update writes, not a row of the table. */
    RETRACTION("a retraction"),
    /** A Paimon record a later write for the same key shadows under the merge engine. */
    SUPERSEDED("superseded"),
    UNKNOWN("not decided"),
    ;

    val deleted: Boolean get() = this != LIVE && this != UNKNOWN
}

data class RowHit(
    /** The data file the row was read from — Iceberg's recorded path, Paimon's file name. */
    val filePath: String,
    /** `file_row_number`; null for a format DuckDB gives no position for. */
    val position: Long?,
    val cells: Map<String, Any?>,
    val fate: RowFate,
    /** The file that decided it, when one did — a delete file, an index file, a later data file. */
    val by: String? = null,
    val note: String? = null,
)

data class LookupFileOutcome(val filePath: String, val hits: Int, val error: String? = null)

data class RowLookupResult(
    val filesRead: List<LookupFileOutcome>,
    /** Live data files the filter ruled out before any was opened. */
    val filesRuledOut: Int,
    /** Live data files left unread by the cap. */
    val filesLeft: Int,
    val hits: List<RowHit>,
) {
    val live: Int get() = hits.count { it.fate == RowFate.LIVE }
    val deleted: Int get() = hits.count { it.fate.deleted }
    val undecided: Int get() = hits.count { it.fate == RowFate.UNKNOWN }
}

data class RowLookupInput(
    val snapshotId: Long?,
    val schema: IcebergSchemaModel?,
    val dataFiles: List<LookupDataFile>,
    val deleteFiles: List<LookupDeleteFile>,
    /** [deleteReach] over the snapshot — which delete files a scan pairs with which data files. */
    val reach: List<DeleteReach>,
) {
    /** The delete files paired with a data file, proved or unsettled, in the order the reach lists them. */
    fun deletesFor(recordedPath: String): List<LookupDeleteFile> {
        val key = normalizeFilePath(recordedPath)
        val byPath = deleteFiles.associateBy { normalizeFilePath(it.recordedPath) }
        return reach.filter { r -> (r.reaches + r.mayReach).any { normalizeFilePath(it) == key } }
            .mapNotNull { byPath[normalizeFilePath(it.deletePath)] }
    }
}

/** The lookup input for the current snapshot, or null when the newest metadata names none it can read. */
fun UnifiedTableModel.rowLookupInput(): RowLookupInput? {
    val newest = metadatas.lastOrNull()?.metadata ?: return null
    val currentId = newest.currentSnapshotId ?: return null
    val snapshot = metadatas.asReversed().firstNotNullOfOrNull { um -> um.snapshots.firstOrNull { it.metadata.snapshotId == currentId } }
        ?.takeIf { !it.expired } ?: return null
    return rowLookupInputOf(snapshot, liveFilesOf(snapshot), deleteReach(snapshot))
}

/**
 * The read input for one snapshot, from its live-file walk and its delete pairing already run —
 * the snapshot node holds both deferred, so a panel that compared and then counted walks once.
 * Column names for an equality delete's field ids come from the newest metadata's current schema.
 */
fun UnifiedTableModel.rowLookupInputOf(snapshot: UnifiedSnapshot, liveFiles: List<LiveFile>, reach: List<DeleteReach>): RowLookupInput? {
    val newest = metadatas.lastOrNull()?.metadata ?: return null
    val currentId = snapshot.metadata.snapshotId ?: return null
    val schema = newest.schemas.firstOrNull { it.schemaId == newest.currentSchemaId }?.let(::tableSchemaModel)
        ?: newest.schemas.lastOrNull()?.let(::tableSchemaModel)
    val live = liveFiles.map { normalizeFilePath(it.path) }.toSet()
    val data = mutableMapOf<String, LookupDataFile>()
    val deletes = mutableMapOf<String, LookupDeleteFile>()
    snapshot.manifests.forEach { m ->
        m.dataFiles.forEach { unified ->
            val file = unified.metadata.dataFile ?: return@forEach
            val recorded = file.filePath ?: return@forEach
            val key = normalizeFilePath(recorded)
            if (key !in live) return@forEach
            when (deleteKindOf(file)) {
                null -> data.putIfAbsent(key, LookupDataFile(recorded, unified.path.toString(), file.fileFormat, file.recordCount))
                else -> deletes.putIfAbsent(key, LookupDeleteFile(
                    recordedPath = recorded,
                    localPath = unified.path.toString(),
                    kind = deleteKindOf(file)!!,
                    contentOffset = file.contentOffset,
                    contentSizeInBytes = file.contentSizeInBytes,
                    recordCount = file.recordCount,
                    equalityColumns = file.equalityIds.orEmpty().mapNotNull { id -> schema?.nameOf(id) },
                ))
            }
        }
    }
    return RowLookupInput(currentId, schema, data.values.toList(), deletes.values.toList(), reach)
}
