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

/** Paimon manifest entry (Avro), one record per data file ADD/DELETE operation. */
@Serializable
data class PaimonManifestEntry(
    @SerialName("_KIND") val kind: Int? = null,   // 0=ADD, 1=DELETE
    @SerialName("_BUCKET") val bucket: Int? = null,
    @SerialName("_TOTAL_BUCKETS") val totalBuckets: Int? = null,
    // _PARTITION is complex binary — skip for now
    @SerialName("_FILE") val file: PaimonDataFileMeta? = null,
)

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
