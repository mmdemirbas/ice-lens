@file:OptIn(com.github.avrokotlin.avro4k.ExperimentalAvro4kApi::class)

package service

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.decodeFromGenericData
import com.github.avrokotlin.avro4k.schema
import kotlinx.serialization.json.Json
import model.ManifestEntry
import model.ManifestListEntry
import model.TableMetadata
import org.apache.avro.file.DataFileReader
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.generic.GenericRecord
import java.io.File
import java.net.URI

object IcebergReader {
    data class ReadError(
        val message: String,
        val stackTrace: String? = null,
    )

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
        return readAvro(localPath)
    }

    // 3. Read Manifest File (Avro)
    fun readManifestFile(localPath: String): ReadResult<ManifestEntry> {
        return readAvro(localPath)
    }

    private inline fun <reified T : Any> readAvro(localPath: String): ReadResult<T> {
        val file = when {
            localPath.startsWith("file:") -> File(URI(localPath))
            localPath.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) ->
                throw IllegalArgumentException("Unsupported URI scheme in path: $localPath")
            else                          -> File(localPath)
        }

        // Parse file blocks into schema-aware GenericRecords
        return DataFileReader(file, GenericDatumReader<GenericRecord>()).use { reader ->
            val entries = mutableListOf<T>()
            val errors = mutableListOf<ReadError>()
            val schema = Avro.schema<T>()
            var rowIndex = 0

            reader.forEach { record ->
                try {
                    @Suppress("DEPRECATION") entries.add(Avro.decodeFromGenericData(schema, record))
                } catch (e: Exception) {
                    val details = e.message ?: e::class.simpleName ?: "Unknown decode error"
                    errors.add(
                        ReadError(
                            message = "Record #$rowIndex decode failed: $details",
                            stackTrace = e.stackTraceToString(),
                        )
                    )
                }
                rowIndex++
            }

            ReadResult(entries = entries, errors = errors)
        }
    }
}
