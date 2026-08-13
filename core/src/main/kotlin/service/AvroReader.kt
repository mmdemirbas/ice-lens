@file:OptIn(com.github.avrokotlin.avro4k.ExperimentalAvro4kApi::class)

package service

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.decodeFromGenericData
import com.github.avrokotlin.avro4k.schema
import org.apache.avro.file.DataFileReader
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.generic.GenericRecord
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI

/** Shared Avro file reader for any `@Serializable` data class. */
object AvroReader {

    @PublishedApi internal val logger = LoggerFactory.getLogger(AvroReader::class.java)
    @PublishedApi internal val URI_SCHEME_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")

    /** Result of reading an Avro file: successfully decoded entries plus per-record errors. */
    data class ReadResult<T>(
        val entries: List<T>,
        val errors: List<ReadError> = emptyList(),
    )

    data class ReadError(
        val message: String,
        val stackTrace: String? = null,
    )

    /**
     * Reads an Avro file and decodes each record into [T] using avro4k.
     * Records that fail to decode are collected as errors rather than aborting.
     */
    inline fun <reified T : Any> readAvro(localPath: String): ReadResult<T> {
        logger.debug("Reading Avro file: {} (type={})", localPath, T::class.simpleName)
        val file = when {
            localPath.startsWith("file:") -> File(URI(localPath))
            localPath.matches(URI_SCHEME_PATTERN) -> {
                logger.error("Unsupported URI scheme in Avro path: {}", localPath)
                throw IllegalArgumentException("Unsupported URI scheme in path: $localPath")
            }
            else -> File(localPath)
        }

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
                    logger.warn("Avro decode error in {}, record #{}: {}", localPath, rowIndex, details)
                    errors.add(
                        ReadError(
                            message = "Record #$rowIndex decode failed: $details",
                            stackTrace = e.stackTraceToString(),
                        )
                    )
                }
                rowIndex++
            }

            logger.debug("Avro file read complete: {} ({} entries, {} errors)", localPath, entries.size, errors.size)
            ReadResult(entries = entries, errors = errors)
        }
    }
}
