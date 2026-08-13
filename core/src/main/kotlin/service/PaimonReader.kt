package service

import kotlinx.serialization.json.Json
import model.PaimonManifestEntry
import model.PaimonManifestFileMeta
import model.PaimonSchema
import model.PaimonSnapshot
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger(PaimonReader::class.java)

/**
 * Reads Paimon table metadata files (JSON snapshots, schemas, and Avro manifests).
 */
object PaimonReader {
    private val json = Json { ignoreUnknownKeys = true }

    /** Reads a Paimon snapshot JSON file. */
    fun readSnapshot(path: String): PaimonSnapshot {
        logger.debug("Reading Paimon snapshot: {}", path)
        val file = File(path)
        if (!file.exists()) {
            logger.error("Paimon snapshot file not found: {}", path)
            throw IllegalArgumentException("File not found: $path")
        }
        val snapshot = json.decodeFromString(PaimonSnapshot.serializer(), file.readText())
        logger.debug("Paimon snapshot read: id={}, schemaId={}, commitKind={}", snapshot.id, snapshot.schemaId, snapshot.commitKind)
        return snapshot
    }

    /** Reads a Paimon schema JSON file. */
    fun readSchema(path: String): PaimonSchema {
        logger.debug("Reading Paimon schema: {}", path)
        val file = File(path)
        if (!file.exists()) {
            logger.error("Paimon schema file not found: {}", path)
            throw IllegalArgumentException("File not found: $path")
        }
        val schema = json.decodeFromString(PaimonSchema.serializer(), file.readText())
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
}
