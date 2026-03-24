package service

import kotlinx.serialization.json.Json
import model.PaimonManifestEntry
import model.PaimonManifestFileMeta
import model.PaimonSchema
import model.PaimonSnapshot
import java.io.File

/**
 * Reads Paimon table metadata files (JSON snapshots, schemas, and Avro manifests).
 */
object PaimonReader {
    private val json = Json { ignoreUnknownKeys = true }

    /** Reads a Paimon snapshot JSON file. */
    fun readSnapshot(path: String): PaimonSnapshot {
        val file = File(path)
        if (!file.exists()) throw IllegalArgumentException("File not found: $path")
        return json.decodeFromString(PaimonSnapshot.serializer(), file.readText())
    }

    /** Reads a Paimon schema JSON file. */
    fun readSchema(path: String): PaimonSchema {
        val file = File(path)
        if (!file.exists()) throw IllegalArgumentException("File not found: $path")
        return json.decodeFromString(PaimonSchema.serializer(), file.readText())
    }

    /** Reads a Paimon manifest list Avro file (contains [PaimonManifestFileMeta] entries). */
    fun readManifestList(path: String): AvroReader.ReadResult<PaimonManifestFileMeta> {
        return AvroReader.readAvro(path)
    }

    /** Reads a Paimon manifest Avro file (contains [PaimonManifestEntry] entries). */
    fun readManifest(path: String): AvroReader.ReadResult<PaimonManifestEntry> {
        return AvroReader.readAvro(path)
    }
}
