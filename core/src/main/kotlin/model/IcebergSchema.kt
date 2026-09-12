package model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// --- Metadata JSON ---
@Serializable
data class TableMetadata(
    @SerialName("format-version") val formatVersion: Int? = null,
    @SerialName("table-uuid") val tableUuid: String? = null,
    val location: String? = null,
    // long in the spec, and every other sequence number here is a Long. Unreachable in practice
    // — it would take 2^31 commits — but a table that got there would fail to parse at all.
    @SerialName("last-sequence-number") val lastSequenceNumber: Long? = null,
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
    val statistics: List<StatisticsFile> = emptyList(),
    @SerialName("partition-statistics") val partitionStatistics: List<PartitionStatisticsFile> = emptyList(),
    @SerialName("snapshot-log") val snapshotLog: List<SnapshotLogEntry> = emptyList(),
    @SerialName("metadata-log") val metadataLog: List<MetadataLogEntry> = emptyList(),
    val properties: Map<String, String> = emptyMap(),
)

/**
 * A Puffin file of table statistics, and what `metadata.json` records about it.
 *
 * The container is the same format a v3 deletion vector lives in — see `service/PuffinReader.kt` —
 * so the file this points at holds a footer and one blob per entry in [blobMetadata]. What is here
 * is the *record* of that file, which a planner reads without opening it: that is the whole point
 * of copying the blob metadata up into the table's metadata, and it is why the two can disagree.
 */
@Serializable
data class StatisticsFile(
    @SerialName("snapshot-id") val snapshotId: Long? = null,
    @SerialName("statistics-path") val statisticsPath: String? = null,
    @SerialName("file-size-in-bytes") val fileSizeInBytes: Long? = null,
    @SerialName("file-footer-size-in-bytes") val fileFooterSizeInBytes: Long? = null,
    @SerialName("key-metadata") val keyMetadata: String? = null,
    @SerialName("blob-metadata") val blobMetadata: List<BlobMetadata> = emptyList(),
)

/**
 * One blob inside a statistics file: what it measures, for which columns, at which commit.
 *
 * [fields] are **field ids**, not names, and are resolved against the schema the blob's own
 * snapshot used — see [statisticsRows]. [properties] is where the answer lives: a
 * `apache-datasketches-theta-v1` blob carries its distinct count as the string property `ndv`,
 * which is the figure a cost-based optimiser plans against without opening the sketch.
 */
@Serializable
data class BlobMetadata(
    val type: String? = null,
    @SerialName("snapshot-id") val snapshotId: Long? = null,
    @SerialName("sequence-number") val sequenceNumber: Long? = null,
    val fields: List<Int> = emptyList(),
    val properties: Map<String, String> = emptyMap(),
)

/**
 * A Puffin file of partition statistics.
 *
 * Deliberately thin: no fixture in this repository has one, because the procedure that writes them
 * does not exist in the Iceberg the fixture image ships (see `docs/fixtures/stats.sql`). The three
 * fields the spec requires are modelled so the list stops being raw JSON; anything more would be
 * written against the spec with nothing to check it.
 */
@Serializable
data class PartitionStatisticsFile(
    @SerialName("snapshot-id") val snapshotId: Long? = null,
    @SerialName("statistics-path") val statisticsPath: String? = null,
    @SerialName("file-size-in-bytes") val fileSizeInBytes: Long? = null,
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
) {
    /**
     * The transform as the spec writes it — `identity`, `bucket[4]`, `truncate[3]`, `day`,
     * `hour`, `month`, `year`, `void`. Kept as a string rather than an enum so an unrecognised
     * transform is still shown by name instead of dropping the field.
     */
    val transformName: String get() = (transform as? JsonPrimitive)?.contentOrNull.orEmpty()
}

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
) {
    val transformName: String get() = (transform as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** `name ASC NULLS FIRST`, or `truncate[4](name) DESC NULLS LAST` — the way `WRITE ORDERED BY` is written. */
    fun describe(nameOf: (Int) -> String?): String {
        val column = sourceId?.let { nameOf(it) ?: "field $it" } ?: "field ?"
        val term = if (transformName.isEmpty() || transformName == "identity") column else "$transformName($column)"
        val dir = direction?.uppercase() ?: "ASC"
        val nulls = nullOrder?.uppercase()?.replace('-', ' ') ?: "NULLS FIRST"
        return "$term $dir $nulls"
    }
}

/**
 * The order as `WRITE ORDERED BY` would state it, with source ids resolved to names through
 * [nameOf] — a file's own schema, or the metadata's current one. Order 0 is the unsorted order
 * and reads as such rather than as an empty string.
 */
fun SortOrder.describe(nameOf: (Int) -> String?): String =
    if (fields.isEmpty()) "unsorted" else fields.joinToString(", ") { it.describe(nameOf) }

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

/** `manifest_file.content` values from the Iceberg table spec. */
object ManifestContent {
    const val DATA = 0
    const val DELETES = 1
}

/** `manifest_entry.status` values from the Iceberg table spec. */
object ManifestEntryStatus {
    const val EXISTING = 0
    const val ADDED = 1
    const val DELETED = 2
}

/** `data_file.content` values from the Iceberg table spec. */
object DataFileContent {
    const val DATA = 0
    const val POSITION_DELETES = 1
    const val EQUALITY_DELETES = 2
}

// --- Manifest List (Avro) ---
@Serializable
data class ManifestListEntry(
    @SerialName("manifest_path") val manifestPath: String? = null,
    @SerialName("manifest_length") val manifestLength: Long? = null,
    @SerialName("partition_spec_id") val partitionSpecId: Int? = null,
    @SerialName("content") val content: Int? = null, // 0=Data, 1=Deletes
    @SerialName("sequence_number") val sequenceNumber: Long? = null,
    @SerialName("min_sequence_number") val minSequenceNumber: Long? = null,
    @SerialName("added_snapshot_id") val addedSnapshotId: Long? = null,
    @SerialName("added_files_count") val addedFilesCount: Int? = null,
    @SerialName("existing_files_count") val existingFilesCount: Int? = null,
    @SerialName("deleted_files_count") val deletedFilesCount: Int? = null,
    @SerialName("added_rows_count") val addedRowsCount: Long? = null,
    @SerialName("existing_rows_count") val existingRowsCount: Long? = null,
    @SerialName("deleted_rows_count") val deletedRowsCount: Long? = null,
    /**
     * Per-partition-field bounds over every file in this manifest, **positional**: entry `i`
     * describes field `i` of the manifest's partition spec.
     *
     * This is what decides whether a scan opens the manifest at all. A planner intersects the
     * query's partition predicate with these bounds and skips the whole file if they cannot
     * overlap, so a manifest can be the reason a query is fast without a single one of its
     * entries being read.
     */
    val partitions: List<PartitionFieldSummary>? = null,
)

/**
 * `field_summary` from the manifest list: the range of one partition field across a manifest.
 *
 * Bounds are Appendix D bytes in the partition field's **result** type, not the source column's
 * — a `bucket[8]` summary is an int range even when the source is a string.
 */
@Serializable
data class PartitionFieldSummary(
    @SerialName("contains_null") val containsNull: Boolean = false,
    @SerialName("contains_nan") val containsNan: Boolean? = null,
    @SerialName("lower_bound") val lowerBound: ByteArray? = null,
    @SerialName("upper_bound") val upperBound: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PartitionFieldSummary) return false
        return containsNull == other.containsNull &&
            containsNan == other.containsNan &&
            lowerBound.contentEquals(other.lowerBound) &&
            upperBound.contentEquals(other.upperBound)
    }

    override fun hashCode(): Int {
        var result = containsNull.hashCode()
        result = 31 * result + containsNan.hashCode()
        result = 31 * result + (lowerBound?.contentHashCode() ?: 0)
        result = 31 * result + (upperBound?.contentHashCode() ?: 0)
        return result
    }
}

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

/**
 * The sequence number that applies to an entry, which is usually not the one it records.
 *
 * Iceberg **inherits** it. An entry written by the same commit as its manifest stores null, and the
 * value is `manifest_file.sequence_number` — the number is not repeated per entry because every
 * entry a commit adds shares it. Only an entry *carried forward* from an earlier commit records one
 * of its own, because by then the manifest's number has moved on and the file's has not.
 *
 * So a null here means "the manifest's", never "unknown", and printing it as unknown is how three
 * of `mor`'s four files came to show `N/A` for a number the format defines exactly. It matters
 * beyond display: which delete files a scan applies to a data file is decided by comparing these
 * two numbers, so an entry read as having none is an entry no rule can be applied to.
 *
 * **A manifest with none is a v1 manifest, and its entries are at 0.** Sequence numbers arrived
 * with v2; a v1 manifest has no column for them and a v1 manifest list records none for the
 * manifest, and the spec says what to read then: "When reading v1 manifests with no sequence
 * number column, sequence numbers for all files must default to 0." Upgrading a table rewrites
 * nothing, so a v2 table keeps its v1-written manifests exactly so — the `v1` fixture is one —
 * and a delete file written after the upgrade reaches those files because 0 is below its number,
 * which is the answer a scan gives and the answer "unknown" cannot.
 */
fun effectiveSequenceNumber(entry: ManifestEntry, manifestSequenceNumber: Long?): Long =
    entry.sequenceNumber ?: manifestSequenceNumber ?: 0L

/**
 * `sequence-number`, or 0 for a snapshot a v1 table committed. The field is required from v2 and
 * absent before it — "default to 0 when reading v1 metadata" — and an upgrade leaves the old
 * snapshots as they were.
 */
val Snapshot.effectiveSequenceNumber: Long get() = sequenceNumber ?: 0L

/** `manifest_file.sequence_number` (a [ManifestListEntry] here), or 0 from a v1 manifest list: "use 0 when reading v1 manifest lists". */
val ManifestListEntry.effectiveSequenceNumber: Long get() = sequenceNumber ?: 0L

/** `manifest_file.min_sequence_number`, under the same v1 rule. */
val ManifestListEntry.effectiveMinSequenceNumber: Long get() = minSequenceNumber ?: 0L

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
    /**
     * v3 deletion vector fields. The vector lives inside a Puffin blob that may hold several,
     * so the entry carries the byte range as well as the path, and — uniquely among delete
     * files — names the single data file it applies to.
     *
     * This is the only place the format records a delete-to-data link *exactly*. A v2 positional
     * delete keeps its targets inside its own `file_path` column — but the manifest still records
     * that column's **bounds**, which is a one-sided link a planner can prune with and this app
     * reads in [deleteTargetsOf]. An equality delete has neither: it applies by predicate. Null on
     * every v2 table.
     */
    @SerialName("referenced_data_file") val referencedDataFile: String? = null,
    @SerialName("content_offset") val contentOffset: Long? = null,
    @SerialName("content_size_in_bytes") val contentSizeInBytes: Long? = null,
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
            sortOrderId == other.sortOrderId &&
            referencedDataFile == other.referencedDataFile &&
            contentOffset == other.contentOffset &&
            contentSizeInBytes == other.contentSizeInBytes
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
        result = 31 * result + referencedDataFile.hashCode()
        result = 31 * result + contentOffset.hashCode()
        result = 31 * result + contentSizeInBytes.hashCode()
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
