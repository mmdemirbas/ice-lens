package service

import kotlinx.serialization.json.Json
import model.PaimonIndexManifestEntry
import model.PaimonStatistics
import model.PaimonManifestEntry
import model.PaimonManifestFileMeta
import model.PaimonSchema
import model.PaimonSnapshot
import org.slf4j.LoggerFactory
import java.nio.file.Files

private val logger = LoggerFactory.getLogger(PaimonReader::class.java)

/**
 * Reads Paimon table metadata files (JSON snapshots, schemas, and Avro manifests).
 */
object PaimonReader {
    private val json = Json { ignoreUnknownKeys = true }

    /** Reads a Paimon snapshot JSON file. */
    fun readSnapshot(path: String): PaimonSnapshot {
        logger.debug("Reading Paimon snapshot: {}", path)
        val location = StorageLocation.pathOf(path)
        if (!Files.exists(location)) {
            logger.error("Paimon snapshot file not found: {}", path)
            throw IllegalArgumentException("File not found: $path")
        }
        val snapshot = json.decodeFromString(PaimonSnapshot.serializer(), Files.readString(location))
        logger.debug("Paimon snapshot read: id={}, schemaId={}, commitKind={}", snapshot.id, snapshot.schemaId, snapshot.commitKind)
        return snapshot
    }

    /** Reads the JSON file an `ANALYZE` commit names under `statistics/`. */
    fun readStatistics(path: String): PaimonStatistics {
        logger.debug("Reading Paimon statistics: {}", path)
        val location = StorageLocation.pathOf(path)
        if (!Files.exists(location)) {
            throw IllegalArgumentException("File not found: $path")
        }
        return json.decodeFromString(PaimonStatistics.serializer(), Files.readString(location))
    }

    /** Reads a Paimon schema JSON file. */
    fun readSchema(path: String): PaimonSchema {
        logger.debug("Reading Paimon schema: {}", path)
        val location = StorageLocation.pathOf(path)
        if (!Files.exists(location)) {
            logger.error("Paimon schema file not found: {}", path)
            throw IllegalArgumentException("File not found: $path")
        }
        val schema = json.decodeFromString(PaimonSchema.serializer(), Files.readString(location))
        logger.debug("Paimon schema read: id={}, fields={}", schema.id, schema.fields.size)
        return schema
    }

    /** Reads a Paimon manifest list Avro file (contains [PaimonManifestFileMeta] entries). */
    fun readManifestList(path: String): AvroReader.ReadResult<PaimonManifestFileMeta> {
        logger.debug("Reading Paimon manifest list: {}", path)
        return AvroReader.readAvro(path)
    }

    /** Reads a Paimon manifest Avro file (contains [PaimonManifestEntry] entries). */
    fun readManifest(path: String): AvroReader.ReadResult<PaimonManifestEntry> {
        logger.debug("Reading Paimon manifest: {}", path)
        return AvroReader.readAvro(path)
    }

    /** Reads a Paimon index manifest Avro file (contains [PaimonIndexManifestEntry] entries). */
    fun readIndexManifest(path: String): AvroReader.ReadResult<PaimonIndexManifestEntry> {
        logger.debug("Reading Paimon index manifest: {}", path)
        return AvroReader.readAvro(path)
    }
}
