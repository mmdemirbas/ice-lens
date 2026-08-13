package service

import kotlinx.serialization.json.Json
import model.ManifestEntry
import model.ManifestListEntry
import model.TableMetadata
import org.apache.avro.generic.GenericFixed
import org.apache.avro.generic.GenericRecord
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.ByteBuffer

private val logger = LoggerFactory.getLogger(IcebergReader::class.java)

object IcebergReader {
    private val json = Json { ignoreUnknownKeys = true }

    fun readTableMetadata(localPath: String): TableMetadata {
        logger.debug("Reading Iceberg metadata: {}", localPath)
        val file = File(localPath)
        if (!file.exists()) {
            logger.error("Iceberg metadata file not found: {}", localPath)
            throw IllegalArgumentException("File not found: $localPath")
        }
        val metadata = json.decodeFromString(TableMetadata.serializer(), file.readText())
        logger.debug("Iceberg metadata read: formatVersion={}, snapshots={}", metadata.formatVersion, metadata.snapshots?.size ?: 0)
        return metadata
    }

    fun readManifestList(localPath: String): AvroReader.ReadResult<ManifestListEntry> {
        logger.debug("Reading Iceberg manifest list: {}", localPath)
        return AvroReader.readAvro(localPath)
    }

    fun readManifestFile(localPath: String): AvroReader.ReadResult<ManifestEntryRecord> {
        logger.debug("Reading Iceberg manifest: {}", localPath)
        return AvroReader.readAvroWithRecord<ManifestEntry, ManifestEntryRecord>(localPath) { entry, record ->
            ManifestEntryRecord(entry, extractPartition(record))
        }
    }

    /**
     * Pulls `data_file.partition` out of a raw manifest record, as plain Kotlin values keyed by
     * partition field name.
     *
     * The struct's Avro schema comes from the table's partition spec, so it is table-specific and
     * cannot be modelled by a `@Serializable` class — this is the one field the typed decode has
     * to skip. Values are normalised here so nothing downstream needs to know about Avro's
     * `Utf8`, `ByteBuffer` or `GenericFixed`.
     *
     * Returns null when the record has no partition struct at all, which is distinct from an
     * unpartitioned table's empty struct.
     */
    private fun extractPartition(record: GenericRecord): Map<String, Any?>? {
        val dataFile = record.fieldOrNull("data_file") as? GenericRecord ?: return null
        val partition = dataFile.fieldOrNull("partition") as? GenericRecord ?: return null
        return partition.schema.fields.associate { field ->
            field.name() to normalizeAvroValue(partition.get(field.pos()))
        }
    }

    /**
     * Reads a field by name, or null when the record's schema has no such field.
     *
     * Avro's own `GenericRecord.get(String)` throws `AvroRuntimeException` for an unknown field
     * rather than returning null, so asking a v1 manifest — or any writer that omits `partition`
     * — for a field it does not have would fail the whole record.
     */
    private fun GenericRecord.fieldOrNull(name: String): Any? =
        schema.getField(name)?.let { field -> get(field.pos()) }

    private fun normalizeAvroValue(value: Any?): Any? = when (value) {
        is GenericFixed -> value.bytes()
        is ByteBuffer -> ByteArray(value.remaining()).also { value.duplicate().get(it) }
        is CharSequence -> value.toString()
        else -> value
    }
}

/**
 * A manifest entry together with its partition tuple.
 *
 * The two are read at once because the partition struct only exists on the raw Avro record, which
 * is gone by the time the typed [ManifestEntry] reaches a caller. Pairing them here keeps the
 * association in the type system rather than in two lists that have to stay aligned.
 */
data class ManifestEntryRecord(
    val entry: ManifestEntry,
    /** `data_file.partition` field values by name, or null when the record carried no struct. */
    val partition: Map<String, Any?>?,
)
