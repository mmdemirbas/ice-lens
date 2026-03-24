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
