package service

import kotlinx.serialization.json.Json
import model.ManifestEntry
import model.ManifestListEntry
import model.TableMetadata
import java.io.File

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
        val file = File(localPath)
        if (!file.exists()) throw IllegalArgumentException("File not found: $localPath")
        return json.decodeFromString(TableMetadata.serializer(), file.readText())
    }

    // 2. Read Manifest List (Avro)
    fun readManifestList(localPath: String): ReadResult<ManifestListEntry> {
        return toReadResult(AvroReader.readAvro(localPath))
    }

    // 3. Read Manifest File (Avro)
    fun readManifestFile(localPath: String): ReadResult<ManifestEntry> {
        return toReadResult(AvroReader.readAvro(localPath))
    }

    @Suppress("DEPRECATION")
    private fun <T> toReadResult(avroResult: AvroReader.ReadResult<T>): ReadResult<T> =
        ReadResult(
            entries = avroResult.entries,
            errors = avroResult.errors.map { ReadError(it.message, it.stackTrace) },
        )
}
