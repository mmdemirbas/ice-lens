package model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// --- Metadata JSON ---
@Serializable
data class TableMetadata(
    @SerialName("format-version") val formatVersion: Int? = null,
    @SerialName("table-uuid") val tableUuid: String? = null,
    val location: String? = null,
    @SerialName("last-sequence-number") val lastSequenceNumber: Int? = null,
    @SerialName("last-updated-ms") val lastUpdatedMs: Long? = null,
    @SerialName("last-column-id") val lastColumnId: Int? = null,
    @SerialName("current-schema-id") val currentSchemaId: Int? = null,
    @SerialName("current-snapshot-id") val currentSnapshotId: Long? = null,
    @SerialName("default-spec-id") val defaultSpecId: Int? = null,
    @SerialName("partition-specs") val partitionSpecs: List<PartitionSpec> = emptyList(),
    @SerialName("last-partition-id") val lastPartitionId: Int? = null,
    @SerialName("default-sort-order-id") val defaultSortOrderId: Int? = null,
    @SerialName("sort-orders") val sortOrders: List<SortOrder> = emptyList(),
    val schemas: List<TableSchema> = emptyList(),
    val refs: Map<String, SnapshotRef> = emptyMap(),
    val snapshots: List<Snapshot> = emptyList(),
    val statistics: List<JsonElement> = emptyList(),
    @SerialName("partition-statistics") val partitionStatistics: List<JsonElement> = emptyList(),
    @SerialName("snapshot-log") val snapshotLog: List<SnapshotLogEntry> = emptyList(),
    @SerialName("metadata-log") val metadataLog: List<MetadataLogEntry> = emptyList(),
    val properties: Map<String, String> = emptyMap(),
)

@Serializable
data class PartitionSpec(
    @SerialName("spec-id") val specId: Int? = null,
    val fields: List<PartitionField> = emptyList(),
)

@Serializable
data class PartitionField(
    @SerialName("source-id") val sourceId: Int? = null,
    @SerialName("field-id") val fieldId: Int? = null,
    val name: String? = null,
    val transform: JsonElement? = null,
)

@Serializable
data class SortOrder(
    @SerialName("order-id") val orderId: Int? = null,
    val fields: List<SortField> = emptyList(),
)

@Serializable
data class SortField(
    @SerialName("source-id") val sourceId: Int? = null,
    val transform: JsonElement? = null,
    val direction: String? = null,
    @SerialName("null-order") val nullOrder: String? = null,
)

@Serializable
data class SnapshotRef(
    @SerialName("snapshot-id") val snapshotId: Long? = null,
    val type: String? = null,
    @SerialName("max-ref-age-ms") val maxRefAgeMs: Long? = null,
    @SerialName("max-snapshot-age-ms") val maxSnapshotAgeMs: Long? = null,
    @SerialName("min-snapshots-to-keep") val minSnapshotsToKeep: Int? = null,
)

@Serializable
data class SnapshotLogEntry(
    @SerialName("timestamp-ms") val timestampMs: Long? = null,
    @SerialName("snapshot-id") val snapshotId: Long? = null,
)

@Serializable
data class MetadataLogEntry(
    @SerialName("timestamp-ms") val timestampMs: Long? = null,
    @SerialName("metadata-file") val metadataFile: String? = null,
)

@Serializable
data class TableSchema(
    val type: String? = null,
    @SerialName("schema-id") val schemaId: Int? = null,
    @SerialName("identifier-field-ids") val identifierFieldIds: List<Int> = emptyList(),
    val fields: List<TableSchemaField> = emptyList(),
)

@Serializable
data class TableSchemaField(
    val id: Int? = null,
    val name: String? = null,
    val required: Boolean? = null,
    val type: JsonElement? = null,
)

@Serializable
data class Snapshot(
    @SerialName("snapshot-id") val snapshotId: Long? = null,
    @SerialName("parent-snapshot-id") val parentSnapshotId: Long? = null,
    @SerialName("sequence-number") val sequenceNumber: Long? = null,
    @SerialName("schema-id") val schemaId: Int? = null,
    @SerialName("timestamp-ms") val timestampMs: Long? = null,
    @SerialName("manifest-list") val manifestList: String? = null, // Path to Avro file
    val summary: Map<String, String> = emptyMap(),
)

// --- Manifest List (Avro) ---
@Serializable
data class ManifestListEntry(
    @SerialName("manifest_path") val manifestPath: String? = null,
    @SerialName("manifest_length") val manifestLength: Long? = null,
    @SerialName("partition_spec_id") val partitionSpecId: Int? = null,
    @SerialName("content") val content: Int? = null, // 0=Data, 1=Deletes
    @SerialName("sequence_number") val sequenceNumber: Int? = null,
    @SerialName("min_sequence_number") val minSequenceNumber: Int? = null,
    @SerialName("added_snapshot_id") val addedSnapshotId: Long? = null,
    @SerialName("added_files_count") val addedFilesCount: Int? = null,
    @SerialName("existing_files_count") val existingFilesCount: Int? = null,
    @SerialName("deleted_files_count") val deletedFilesCount: Int? = null,
    @SerialName("added_rows_count") val addedRowsCount: Long? = null,
    @SerialName("existing_rows_count") val existingRowsCount: Long? = null,
    @SerialName("deleted_rows_count") val deletedRowsCount: Long? = null,
)

// --- Manifest File (Avro) ---
// Wraps the 'data_file' struct found inside manifest entries
@Serializable
data class ManifestEntry(
    val status: Int, // 0=EXISTING, 1=ADDED, 2=DELETED
    @SerialName("snapshot_id") val snapshotId: Long? = null,
    @SerialName("sequence_number") val sequenceNumber: Long? = null,
    @SerialName("file_sequence_number") val fileSequenceNumber: Long? = null,
    @SerialName("data_file") val dataFile: DataFile? = null,
)

@Serializable
data class DataFile(
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("file_format") val fileFormat: String? = null,
    @SerialName("record_count") val recordCount: Long? = null,
    @SerialName("file_size_in_bytes") val fileSizeInBytes: Long? = null,
    // V2 Specific: 0=DATA, 1=POSITION_DELETES, 2=EQUALITY_DELETES
    val content: Int? = null,
    @SerialName("data_sequence_number") val dataSequenceNumber: Long? = null,
    @SerialName("column_sizes") val columnSizes: List<KeyValuePairLong>? = null,
    @SerialName("value_counts") val valueCounts: List<KeyValuePairLong>? = null,
    @SerialName("null_value_counts") val nullValueCounts: List<KeyValuePairLong>? = null,
    @SerialName("nan_value_counts") val nanValueCounts: List<KeyValuePairLong>? = null,
    @SerialName("lower_bounds") val lowerBounds: List<KeyValuePairBytes>? = null,
    @SerialName("upper_bounds") val upperBounds: List<KeyValuePairBytes>? = null,
    @SerialName("key_metadata") val keyMetadata: ByteArray? = null,
    @SerialName("split_offsets") val splitOffsets: List<Long>? = null,
    @SerialName("equality_ids") val equalityIds: List<Int>? = null,
    @SerialName("sort_order_id") val sortOrderId: Long? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DataFile) return false
        return filePath == other.filePath &&
            fileFormat == other.fileFormat &&
            recordCount == other.recordCount &&
            fileSizeInBytes == other.fileSizeInBytes &&
            content == other.content &&
            dataSequenceNumber == other.dataSequenceNumber &&
            columnSizes == other.columnSizes &&
            valueCounts == other.valueCounts &&
            nullValueCounts == other.nullValueCounts &&
            nanValueCounts == other.nanValueCounts &&
            lowerBounds == other.lowerBounds &&
            upperBounds == other.upperBounds &&
            keyMetadata.contentEquals(other.keyMetadata) &&
            splitOffsets == other.splitOffsets &&
            equalityIds == other.equalityIds &&
            sortOrderId == other.sortOrderId
    }

    override fun hashCode(): Int {
        var result = filePath.hashCode()
        result = 31 * result + fileFormat.hashCode()
        result = 31 * result + recordCount.hashCode()
        result = 31 * result + fileSizeInBytes.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + dataSequenceNumber.hashCode()
        result = 31 * result + columnSizes.hashCode()
        result = 31 * result + valueCounts.hashCode()
        result = 31 * result + nullValueCounts.hashCode()
        result = 31 * result + nanValueCounts.hashCode()
        result = 31 * result + lowerBounds.hashCode()
        result = 31 * result + upperBounds.hashCode()
        result = 31 * result + (keyMetadata?.contentHashCode() ?: 0)
        result = 31 * result + splitOffsets.hashCode()
        result = 31 * result + equalityIds.hashCode()
        result = 31 * result + sortOrderId.hashCode()
        return result
    }
}


@Serializable
data class KeyValuePairLong(val key: Int, val value: Long)

@Serializable
data class KeyValuePairBytes(val key: Int, val value: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KeyValuePairBytes) return false
        return key == other.key && value.contentEquals(other.value)
    }

    override fun hashCode(): Int = 31 * key + value.contentHashCode()
}
