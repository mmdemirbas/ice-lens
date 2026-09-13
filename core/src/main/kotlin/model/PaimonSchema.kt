package model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Paimon snapshot metadata, deserialized from `snapshot/snapshot-N` JSON files. */
@Serializable
data class PaimonSnapshot(
    val version: Int? = null,
    val id: Long? = null,
    val schemaId: Int? = null,
    val baseManifestList: String? = null,
    val deltaManifestList: String? = null,
    val changelogManifestList: String? = null,
    val indexManifest: String? = null,
    val commitUser: String? = null,
    val commitIdentifier: Long? = null,
    val commitKind: String? = null,           // APPEND, COMPACT, OVERWRITE, ANALYZE
    val timeMillis: Long? = null,
    val logOffsets: Map<String, Long> = emptyMap(),
    val totalRecordCount: Long? = null,
    val deltaRecordCount: Long? = null,
    val changelogRecordCount: Long? = null,
    val watermark: Long? = null,
    /** Paimon's row lineage counter, written from snapshot version 3. Absent on older tables. */
    val nextRowId: Long? = null,
    /**
     * The file under `statistics/` an `ANALYZE` commit names, and nothing else does. Parsed and
     * dropped until the `cl` fixture existed — the same shape of gap [indexManifest] was.
     */
    val statistics: String? = null,
    /** The byte size of each manifest list file, recorded so a planner can budget without a stat. */
    val baseManifestListSize: Long? = null,
    val deltaManifestListSize: Long? = null,
    val changelogManifestListSize: Long? = null,
)

/**
 * What an `ANALYZE TABLE` commit writes under `statistics/`, as Paimon's `Statistics` serialises it.
 *
 * [mergedRecordCount] is the figure a reader usually wants and the one nothing else in the format
 * records: the row count *after* the merge engine, where a snapshot's `totalRecordCount` sums the
 * rows of every file and so counts an updated key once per version it has and a `-D` row as a row.
 * On `dv`, `totalRecordCount` says 1500 for a table whose vector marks 3 of them deleted. [snapshotId] names the snapshot the figures were computed at,
 * which is the one *before* the `ANALYZE` commit that carries them.
 */
/**
 * `consumer/consumer-<id>`: a streaming reader's bookmark. [nextSnapshot] is the snapshot the
 * reader will consume next, and `expire_snapshots` will not expire it or anything after it — the
 * `cs` fixture's expiry with `retain_max = 1` left two snapshots, because a consumer stood at the
 * older one. The id is the file name's, not a field.
 */
@Serializable
data class PaimonConsumer(
    val nextSnapshot: Long? = null,
)

@Serializable
data class PaimonStatistics(
    val snapshotId: Long? = null,
    val schemaId: Long? = null,
    val mergedRecordCount: Long? = null,
    val mergedRecordSize: Long? = null,
    /** Keyed by column name; empty unless the statement said `FOR ALL COLUMNS` or named some. */
    val colStats: Map<String, PaimonColStats> = emptyMap(),
)

/**
 * One column's statistics. [min] and [max] are strings whatever the column's type — Paimon
 * serialises them through its own string form — and are absent for a string column, which the
 * `cl` fixture's `v` shows: a distinct count and lengths, and no bounds.
 */
@Serializable
data class PaimonColStats(
    val colId: Int? = null,
    val distinctCount: Long? = null,
    val min: String? = null,
    val max: String? = null,
    val nullCount: Long? = null,
    val avgLen: Long? = null,
    val maxLen: Long? = null,
)

/**
 * One entry of a Paimon **index manifest**, which the snapshot names and nothing here read until
 * now — `indexManifest` was parsed and dropped, the same shape of gap Iceberg's `statistics` had.
 *
 * An index file is per partition and per bucket, and its meaning is [indexType]:
 * `HASH` is the primary-key index a bucket looks a key up in, and `DELETION_VECTORS` is Paimon's
 * answer to the same problem Iceberg solves with a Puffin vector — rows marked deleted in a data
 * file without rewriting it. The second is why this is worth reading: a table with deletion vectors
 * enabled records here, and only here, which data files have deleted rows and how many.
 *
 * The field names are the Avro schema's own, taken from the fixture's file header rather than from
 * prose. [deletionVectorRanges] is null on a `HASH` entry.
 */
@Serializable
data class PaimonIndexManifestEntry(
    @SerialName("_VERSION") val version: Int? = null,
    @SerialName("_KIND") val kind: Int? = null,
    @SerialName("_BUCKET") val bucket: Int? = null,
    @SerialName("_INDEX_TYPE") val indexType: String? = null,
    @SerialName("_FILE_NAME") val fileName: String? = null,
    @SerialName("_FILE_SIZE") val fileSize: Long? = null,
    @SerialName("_ROW_COUNT") val rowCount: Long? = null,
    @SerialName("_DELETIONS_VECTORS_RANGES") val deletionVectorRanges: List<PaimonDeletionVectorRange?>? = null,
    @SerialName("_EXTERNAL_PATH") val externalPath: String? = null,
) {
    /** Whether this entry describes deleted rows rather than a key index. */
    val isDeletionVectorIndex: Boolean get() = indexType.equals("DELETION_VECTORS", ignoreCase = true)
}

/**
 * One data file's slice of a Paimon deletion-vector index file.
 *
 * The three positional fields are the Avro record's own names, and they are not descriptive: `f0`
 * is the **data file** the vector applies to, `f1` its byte offset inside the index file and `f2`
 * the length. That container-with-a-blob-per-file shape is the same one Iceberg's Puffin has, and
 * it is why a vector's size is not the index file's size.
 */
@Serializable
data class PaimonDeletionVectorRange(
    @SerialName("f0") val dataFileName: String? = null,
    @SerialName("f1") val offset: Int? = null,
    @SerialName("f2") val length: Int? = null,
    @SerialName("_CARDINALITY") val cardinality: Long? = null,
)

/** Paimon table schema, deserialized from `schema/schema-N` JSON files. */
@Serializable
data class PaimonSchema(
    val id: Int? = null,
    val fields: List<PaimonField> = emptyList(),
    val highestFieldId: Int? = null,
    val partitionKeys: List<String> = emptyList(),
    val primaryKeys: List<String> = emptyList(),
    val options: Map<String, String> = emptyMap(),
    val comment: String? = null,
    val timeMillis: Long? = null,
)

/** A single field in a Paimon table schema. */
@Serializable
data class PaimonField(
    val id: Int? = null,
    val name: String? = null,
    val type: String? = null,   // Paimon encodes types as strings like "INT NOT NULL"
)

/**
 * Paimon's column-wise statistics over a set of rows: a `BinaryRow` of per-field minimums, one
 * of per-field maximums, and a null count per field. On a manifest list entry the rows are the
 * partitions of the manifest's entries, and the minimum is **per column** — a partition no entry
 * has, when the lowest date and the lowest region sit in different entries. The `pt` fixture's
 * third commit is exactly that and `PaimonManifestTallyTest` pins it.
 */
@Serializable
data class PaimonSimpleStats(
    @SerialName("_MIN_VALUES") val minValues: ByteArray? = null,
    @SerialName("_MAX_VALUES") val maxValues: ByteArray? = null,
    @SerialName("_NULL_COUNTS") val nullCounts: List<Long?>? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PaimonSimpleStats) return false
        return minValues.contentEquals(other.minValues) && maxValues.contentEquals(other.maxValues) && nullCounts == other.nullCounts
    }

    override fun hashCode(): Int = 31 * (31 * minValues.contentHashCode() + maxValues.contentHashCode()) + nullCounts.hashCode()
}

/**
 * Paimon manifest list entry (Avro), one record per manifest file reference.
 *
 * Everything after the file's own name and size is what a scan plans with without opening the
 * manifest — the added and deleted entry counts, the bucket and level ranges, and the partition
 * statistics — and nothing on the read path checks any of it. `paimonManifestTallies` does.
 */
@Serializable
data class PaimonManifestFileMeta(
    @SerialName("_FILE_NAME") val fileName: String? = null,
    @SerialName("_FILE_SIZE") val fileSize: Long? = null,
    @SerialName("_NUM_ADDED_FILES") val numAddedFiles: Long? = null,
    @SerialName("_NUM_DELETED_FILES") val numDeletedFiles: Long? = null,
    @SerialName("_PARTITION_STATS") val partitionStats: PaimonSimpleStats? = null,
    @SerialName("_SCHEMA_ID") val schemaId: Long? = null,
    @SerialName("_MIN_BUCKET") val minBucket: Int? = null,
    @SerialName("_MAX_BUCKET") val maxBucket: Int? = null,
    @SerialName("_MIN_LEVEL") val minLevel: Int? = null,
    @SerialName("_MAX_LEVEL") val maxLevel: Int? = null,
)

/**
 * `_VALUE_KIND` in a row of a Paimon key-value file — the `RowKind` byte the merge engine reads,
 * which is the whole of what makes a `-D` row a deletion: the row is stored like any other, and
 * only this byte says it retracts the key's earlier value when the levels are merged. A changelog
 * file's rows carry the same byte with the update pair split into before and after.
 */
object PaimonRowKind {
    const val COLUMN = "_VALUE_KIND"
    const val INSERT = 0
    const val UPDATE_BEFORE = 1
    const val UPDATE_AFTER = 2
    const val DELETE = 3

    /** `-D (delete)` — the short form Paimon prints, with the word beside it. */
    fun describe(code: Int): String = when (code) {
        INSERT -> "+I (insert)"
        UPDATE_BEFORE -> "-U (update, the value before)"
        UPDATE_AFTER -> "+U (update, the value after)"
        DELETE -> "-D (delete)"
        else -> "$code (not a RowKind this reads)"
    }

    /** A retraction — a row that removes rather than states: `-U` and `-D`. */
    fun isRetraction(code: Int): Boolean = code == UPDATE_BEFORE || code == DELETE
}

/**
 * `_KIND` values in a Paimon manifest entry. [DELETE] records the removal of a file from the
 * table; it is not an Iceberg-style positional or equality delete file.
 */
object PaimonEntryKind {
    const val ADD = 0
    const val DELETE = 1
}

/**
 * Paimon manifest entry (Avro), one record per data file ADD/DELETE operation.
 *
 * [partition] is a serialised `BinaryRow` over the table's partition keys — see
 * [decodePaimonPartition] — and it is where a partitioned table's file path comes from: the entry
 * names the file by `_FILE_NAME` only, and the file lives under `<key>=<value>/…/bucket-N/`.
 */
@Serializable
data class PaimonManifestEntry(
    @SerialName("_KIND") val kind: Int? = null,   // 0=ADD, 1=DELETE
    @SerialName("_PARTITION") val partition: ByteArray? = null,
    @SerialName("_BUCKET") val bucket: Int? = null,
    @SerialName("_TOTAL_BUCKETS") val totalBuckets: Int? = null,
    @SerialName("_FILE") val file: PaimonDataFileMeta? = null,
) {
    // A ByteArray compares by identity inside a data class; this entry sits in nodes that are
    // compared across graph builds, so the bytes have to compare by content.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PaimonManifestEntry) return false
        return kind == other.kind && bucket == other.bucket && totalBuckets == other.totalBuckets &&
            file == other.file && partition.contentEquals(other.partition)
    }

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + partition.contentHashCode()
        result = 31 * result + bucket.hashCode()
        result = 31 * result + totalBuckets.hashCode()
        result = 31 * result + file.hashCode()
        return result
    }
}

/** `_FILE_SOURCE`: how the file came to be written. */
object PaimonFileSource {
    const val APPEND = 0
    const val COMPACT = 1
}

/**
 * Metadata for a single Paimon data file, nested inside [PaimonManifestEntry].
 *
 * [minKey] and [maxKey] are `BinaryRow`s over the *trimmed* primary key — the primary keys that
 * are not partition keys — and [keyStats] covers the same fields; [valueStats] covers every field
 * of the schema in schema order — or of [writeCols], for a partial-column file — or the columns
 * [valueStatsCols] names when it is set. All decode
 * with [decodePaimonRow]. [deleteRowCount] counts the rows of kind `-D`/`-U` *inside* the file,
 * not rows a deletion vector marks — on `dv` it is 3 on the level-0 file holding the three delete
 * rows and 0 on the two files the vector covers.
 */
@Serializable
data class PaimonDataFileMeta(
    @SerialName("_FILE_NAME") val fileName: String? = null,
    @SerialName("_FILE_SIZE") val fileSize: Long? = null,
    @SerialName("_ROW_COUNT") val rowCount: Long? = null,
    @SerialName("_MIN_KEY") val minKey: ByteArray? = null,
    @SerialName("_MAX_KEY") val maxKey: ByteArray? = null,
    @SerialName("_KEY_STATS") val keyStats: PaimonSimpleStats? = null,
    @SerialName("_VALUE_STATS") val valueStats: PaimonSimpleStats? = null,
    @SerialName("_LEVEL") val level: Int? = null,
    @SerialName("_SCHEMA_ID") val schemaId: Long? = null,
    @SerialName("_MIN_SEQUENCE_NUMBER") val minSequenceNumber: Long? = null,
    @SerialName("_MAX_SEQUENCE_NUMBER") val maxSequenceNumber: Long? = null,
    @SerialName("_CREATION_TIME") val creationTime: Long? = null,
    @SerialName("_DELETE_ROW_COUNT") val deleteRowCount: Long? = null,
    @SerialName("_FILE_SOURCE") val fileSource: Int? = null,
    @SerialName("_VALUE_STATS_COLS") val valueStatsCols: List<String>? = null,
    @SerialName("_EXTERNAL_PATH") val externalPath: String? = null,
    @SerialName("_FIRST_ROW_ID") val firstRowId: Long? = null,
    /**
     * Data evolution: the columns this file holds, when it holds only some. A `MERGE INTO` on a
     * table with `row-tracking.enabled` and `data-evolution.enabled` writes the columns it sets to
     * a file of their own with the same [firstRowId] as the file holding the rest, and a read
     * stitches files sharing a first row id, the higher `_MAX_SEQUENCE_NUMBER` winning a column.
     * [valueStats] then covers these columns, not the schema's; the writer stores null here for a
     * file carrying every column, so null means "the schema's". **Non-null does not mean
     * partial**: a full compaction under `row-tracking.enabled` lists every column plus `_ROW_ID`
     * and `_SEQUENCE_NUMBER` here (`rt`), and read as a patch file that had the table's rows at
     * zero. [isPartialUnder] is the one reading.
     */
    @SerialName("_WRITE_COLS") val writeCols: List<String>? = null,
    /**
     * The file's index files — `<file name>.index` beside the data file, for a file index over
     * `file-index.in-manifest-threshold` — and nothing else in the fixtures seen. Named by file
     * name, in the data file's directory; the `fi` fixture's bloom filter over a million items is
     * 599 KB and lands here.
     */
    @SerialName("_EXTRA_FILES") val extraFiles: List<String>? = null,
    /**
     * A file index small enough to travel in the entry instead of beside the file — the same
     * bloom filter over a hundred items is 60-odd bytes and lands here. Null when there is none;
     * the bytes are Paimon's own index container and are not decoded.
     */
    @SerialName("_EMBEDDED_FILE_INDEX") val embeddedFileIndex: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PaimonDataFileMeta) return false
        return fileName == other.fileName && fileSize == other.fileSize && rowCount == other.rowCount &&
            minKey.contentEquals(other.minKey) && maxKey.contentEquals(other.maxKey) &&
            keyStats == other.keyStats && valueStats == other.valueStats &&
            level == other.level && schemaId == other.schemaId &&
            minSequenceNumber == other.minSequenceNumber && maxSequenceNumber == other.maxSequenceNumber &&
            creationTime == other.creationTime && deleteRowCount == other.deleteRowCount &&
            fileSource == other.fileSource && valueStatsCols == other.valueStatsCols &&
            externalPath == other.externalPath && firstRowId == other.firstRowId && extraFiles == other.extraFiles &&
            embeddedFileIndex.contentEquals(other.embeddedFileIndex)
    }

    override fun hashCode(): Int {
        var result = fileName.hashCode()
        result = 31 * result + fileSize.hashCode()
        result = 31 * result + rowCount.hashCode()
        result = 31 * result + minKey.contentHashCode()
        result = 31 * result + maxKey.contentHashCode()
        result = 31 * result + keyStats.hashCode()
        result = 31 * result + valueStats.hashCode()
        result = 31 * result + level.hashCode()
        result = 31 * result + schemaId.hashCode()
        result = 31 * result + minSequenceNumber.hashCode()
        result = 31 * result + maxSequenceNumber.hashCode()
        result = 31 * result + creationTime.hashCode()
        result = 31 * result + deleteRowCount.hashCode()
        result = 31 * result + fileSource.hashCode()
        result = 31 * result + valueStatsCols.hashCode()
        result = 31 * result + externalPath.hashCode()
        result = 31 * result + firstRowId.hashCode()
        result = 31 * result + extraFiles.hashCode()
        result = 31 * result + embeddedFileIndex.contentHashCode()
        return result
    }
}

/**
 * Whether `_WRITE_COLS` leaves out a column the file's schema has — a data-evolution patch file,
 * whose rows are columns of rows another file holds. A file listing every schema column, with or
 * without system columns beside them, is whole; so is one recording nothing.
 */
fun PaimonDataFileMeta.isPartialUnder(schema: PaimonSchema?): Boolean {
    val cols = writeCols ?: return false
    val fields = schema?.fields?.mapNotNull { it.name }.orEmpty()
    return fields.any { it !in cols }
}
