package model

/**
 * What finding a row in a Paimon table takes, read off the latest snapshot on `main`: every
 * live data file with where it is opened from and the bucket it belongs to, every deletion
 * vector the snapshot's index manifest names, the schema the filter's columns are typed by,
 * and the merge engine that decides which of a key's records is the row. The reading itself —
 * DuckDB over the files the filter leaves, the bucket's other files over each hit's key — is
 * `service/PaimonRowLookup.kt`; this is the part that needs no file opened, and it is a
 * `DeferredRead` on the table node because it replays the snapshot.
 *
 * The question is the same one the Iceberg lookup answers, "is this row live, and if not, what
 * removed it", and the format answers it differently. A primary-key file holds every write as
 * a record — `_KEY_<col>` per trimmed primary key, `_SEQUENCE_NUMBER`, `_VALUE_KIND`, then the
 * value columns — so a record is the row only while no later write for its key exists in the
 * bucket and it is not itself the `-D` a delete writes; and with `deletion-vectors.enabled`
 * a record can be marked without either, by the vector the index file holds for its file.
 */
data class PaimonLookupFile(
    /** `_FILE_NAME` — what a vector range names, and what the panel prints. */
    val fileName: String,
    /** Where it is opened from. */
    val localPath: String,
    /** The decoded partition, `""` for an unpartitioned table — with [bucket], the merge scope. */
    val partition: String,
    val bucket: Int,
    val level: Int?,
    val recordCount: Long?,
)

data class PaimonLookupVector(
    val dataFileName: String,
    /** The index file holding it, where it is opened from. */
    val indexLocalPath: String,
    val indexFileName: String,
    val offset: Long,
    val length: Long,
    /** `_CARDINALITY`, the manifest's own figure. */
    val cardinality: Long?,
)

data class PaimonRowLookupInput(
    val snapshotId: Long,
    val schema: PaimonSchema,
    /** The primary keys that are not partition keys, in primary-key order — the file's `_KEY_` columns. */
    val trimmedPrimaryKeys: List<String>,
    /** `merge-engine`, `deduplicate` where unset. */
    val mergeEngine: String,
    val files: List<PaimonLookupFile>,
    val vectors: List<PaimonLookupVector>,
) {
    val hasPrimaryKey: Boolean get() = schema.primaryKeys.isNotEmpty()

    fun vectorFor(fileName: String): PaimonLookupVector? = vectors.firstOrNull { it.dataFileName == fileName }

    /** The live files sharing a file's partition and bucket — every file the key's later write could be in. */
    fun bucketOf(file: PaimonLookupFile): List<PaimonLookupFile> =
        files.filter { it.partition == file.partition && it.bucket == file.bucket }
}

const val DEFAULT_PAIMON_MERGE_ENGINE = "deduplicate"

/** The lookup input for the latest snapshot on `main`, or null when the table has none. */
fun PaimonUnifiedTableModel.paimonRowLookupInput(): PaimonRowLookupInput? {
    val snapshot = snapshots.lastOrNull() ?: return null
    val id = snapshot.metadata.id ?: return null
    val schema = snapshot.schema ?: return null
    val replay = replayPaimonSnapshot(snapshot)
    val files = replay.liveEntries.values.mapNotNull { entry ->
        val meta = entry.metadata.file ?: return@mapNotNull null
        PaimonLookupFile(
            fileName = meta.fileName ?: return@mapNotNull null,
            localPath = entry.path.toString(),
            partition = entry.partition?.display.orEmpty(),
            bucket = entry.metadata.bucket ?: return@mapNotNull null,
            level = meta.level,
            recordCount = meta.rowCount,
        )
    }
    val indexDir = path.resolve("index")
    val vectors = snapshot.indexFiles.filter { it.isDeletionVectorIndex }.flatMap { index ->
        val indexName = index.fileName ?: return@flatMap emptyList()
        index.deletionVectorRanges.orEmpty().filterNotNull().mapNotNull { range ->
            PaimonLookupVector(
                dataFileName = range.dataFileName ?: return@mapNotNull null,
                indexLocalPath = indexDir.resolve(indexName).toString(),
                indexFileName = indexName,
                offset = range.offset?.toLong() ?: return@mapNotNull null,
                length = range.length?.toLong() ?: return@mapNotNull null,
                cardinality = range.cardinality,
            )
        }
    }
    return PaimonRowLookupInput(
        snapshotId = id,
        schema = schema,
        trimmedPrimaryKeys = schema.primaryKeys.filterNot { it in schema.partitionKeys },
        mergeEngine = schema.options["merge-engine"] ?: DEFAULT_PAIMON_MERGE_ENGINE,
        files = files,
        vectors = vectors,
    )
}
