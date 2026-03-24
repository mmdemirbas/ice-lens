package service

import kotlinx.serialization.json.Json
import model.ManifestEntry
import model.ManifestListEntry
import model.TableMetadata
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger(IcebergReader::class.java)

object IcebergReader {
    data class ReadError(
        val message: String,
        val stackTrace: String? = null,
    )

    /** @see AvroReader.ReadResult */
    data class ReadResult<T>(
        val entries: List<T>,
        val errors: List<ReadError> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    // 1. Read Metadata JSON
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

    // 2. Read Manifest List (Avro)
    fun readManifestList(localPath: String): ReadResult<ManifestListEntry> {
        logger.debug("Reading Iceberg manifest list: {}", localPath)
        return toReadResult(AvroReader.readAvro(localPath))
    }

    // 3. Read Manifest File (Avro)
    fun readManifestFile(localPath: String): ReadResult<ManifestEntry> {
        logger.debug("Reading Iceberg manifest: {}", localPath)
        return toReadResult(AvroReader.readAvro(localPath))
    }

    @Suppress("DEPRECATION")
    private fun <T> toReadResult(avroResult: AvroReader.ReadResult<T>): ReadResult<T> =
        ReadResult(
            entries = avroResult.entries,
            errors = avroResult.errors.map { ReadError(it.message, it.stackTrace) },
        )
}
