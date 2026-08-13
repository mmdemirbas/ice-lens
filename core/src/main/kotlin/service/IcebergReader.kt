package service

import kotlinx.serialization.json.Json
import model.ManifestEntry
import model.ManifestListEntry
import model.TableMetadata
import org.slf4j.LoggerFactory
import java.io.File

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

    fun readManifestFile(localPath: String): AvroReader.ReadResult<ManifestEntry> {
        logger.debug("Reading Iceberg manifest: {}", localPath)
        return AvroReader.readAvro(localPath)
    }
}
