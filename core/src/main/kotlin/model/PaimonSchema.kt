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

/** Paimon manifest list entry (Avro), one record per manifest file reference. */
@Serializable
data class PaimonManifestFileMeta(
    @SerialName("_FILE_NAME") val fileName: String? = null,
    @SerialName("_FILE_SIZE") val fileSize: Long? = null,
    @SerialName("_NUM_ADDED_FILES") val numAddedFiles: Long? = null,
    @SerialName("_NUM_DELETED_FILES") val numDeletedFiles: Long? = null,
    @SerialName("_SCHEMA_ID") val schemaId: Long? = null,
    // _PARTITION_STATS is complex binary — skip for now
)

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

/** Metadata for a single Paimon data file, nested inside [PaimonManifestEntry]. */
@Serializable
data class PaimonDataFileMeta(
    @SerialName("_FILE_NAME") val fileName: String? = null,
    @SerialName("_FILE_SIZE") val fileSize: Long? = null,
    @SerialName("_ROW_COUNT") val rowCount: Long? = null,
    @SerialName("_LEVEL") val level: Int? = null,
    @SerialName("_SCHEMA_ID") val schemaId: Long? = null,
    @SerialName("_MIN_SEQUENCE_NUMBER") val minSequenceNumber: Long? = null,
    @SerialName("_MAX_SEQUENCE_NUMBER") val maxSequenceNumber: Long? = null,
    @SerialName("_CREATION_TIME") val creationTime: Long? = null,
)
