@file:OptIn(com.github.avrokotlin.avro4k.ExperimentalAvro4kApi::class)

package service

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.decodeFromGenericData
import com.github.avrokotlin.avro4k.schema
import model.FileColumn
import model.topLevel
import org.apache.avro.Schema
import org.apache.avro.file.DataFileReader
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.generic.GenericRecord
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files

/** Shared Avro file reader for any `@Serializable` data class. */
object AvroReader {

    @PublishedApi internal val logger = LoggerFactory.getLogger(AvroReader::class.java)

    /**
     * Avro's own random-access abstraction, over any NIO channel.
     *
     * `DataFileReader` needs to seek — an Avro file's schema is in its header and its sync markers
     * are scattered through it — and Avro ships adapters for exactly two sources, a
     * [java.io.File] and a `ByteArray`. Neither can name a file that is not on this machine's
     * disk. A [SeekableByteChannel] is what `Files.newByteChannel` returns for *any* filesystem,
     * so this adapter is what lets one reader serve both a local manifest and a remote one.
     */
    @PublishedApi
    internal class ChannelInput(private val channel: SeekableByteChannel) : org.apache.avro.file.SeekableInput {
        override fun seek(position: Long) { channel.position(position) }
        override fun tell(): Long = channel.position()
        override fun length(): Long = channel.size()
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            channel.read(ByteBuffer.wrap(bytes, offset, length))
        override fun close() { channel.close() }
    }

    /**
     * Result of reading an Avro file: decoded entries, per-record errors, and the file's own
     * key-value metadata.
     *
     * [fileMetadata] is not incidental. An Iceberg manifest records the schema its bounds were
     * written against under a `schema` key, and its partition spec under `partition-spec` — and
     * those are the only correct sources for decoding `lower_bounds`, `upper_bounds` and
     * `partition`. Reading them from the table's *current* metadata.json instead appears to work
     * and silently produces wrong values for any file written before a type change.
     */
    data class ReadResult<T>(
        val entries: List<T>,
        val errors: List<ReadError> = emptyList(),
        val fileMetadata: Map<String, String> = emptyMap(),
    )

    data class ReadError(
        val message: String,
        val stackTrace: String? = null,
    )

    /** Avro file-metadata keys Iceberg writes into every manifest. */
    object MetaKeys {
        /** The Iceberg schema, as JSON, that this manifest's values were written against. */
        const val SCHEMA = "schema"

        /** The partition spec's fields, as JSON, for this manifest. */
        const val PARTITION_SPEC = "partition-spec"

        const val PARTITION_SPEC_ID = "partition-spec-id"
        const val FORMAT_VERSION = "format-version"
        const val CONTENT = "content"
    }

    /**
     * Reads an Avro file and decodes each record into [T] using avro4k.
     * Records that fail to decode are collected as errors rather than aborting.
     */
    inline fun <reified T : Any> readAvro(localPath: String): ReadResult<T> {
        val schema = Avro.schema<T>()
        return read(localPath, T::class.simpleName) { record ->
            @Suppress("DEPRECATION") Avro.decodeFromGenericData<T>(schema, record)
        }
    }

    /**
     * Reads an Avro file like [readAvro], but also hands each raw [GenericRecord] to [combine]
     * alongside the decoded [T], so a caller can keep something avro4k cannot type.
     *
     * This exists for `data_file.partition`: its Avro schema is derived from the table's own
     * partition spec, so no static `@Serializable` class can model it. [combine] runs while the
     * record is still in hand and only its result is retained — no `GenericRecord` outlives the
     * read, which matters for a manifest holding thousands of entries.
     */
    inline fun <reified T : Any, R> readAvroWithRecord(
        localPath: String,
        crossinline combine: (T, GenericRecord) -> R,
    ): ReadResult<R> {
        val schema = Avro.schema<T>()
        return read(localPath, T::class.simpleName) { record ->
            @Suppress("DEPRECATION") combine(Avro.decodeFromGenericData<T>(schema, record), record)
        }
    }

    /**
     * An Avro file's top-level fields in schema order with the `field-id` each carries, or null
     * — what Iceberg's Avro writer records, and what a read places the column by.
     */
    fun fileColumnsOf(localPath: String): Map<String, Int?> = fileColumnTreeOf(localPath).topLevel()

    /**
     * The file's columns as a tree — see [model.FileColumn]: a record's fields by `field-id`, an
     * array's element by the array's `element-id`, a map's key and value by `key-id` and
     * `value-id`, which is where Iceberg's Avro writer puts them; a nullable union is its
     * non-null branch.
     */
    fun fileColumnTreeOf(localPath: String): List<FileColumn> =
        DataFileReader(ChannelInput(Files.newByteChannel(StorageLocation.pathOf(localPath))), GenericDatumReader<GenericRecord>())
            .use { reader -> reader.schema.fields.map { f -> avroColumn(f.name(), (f.getObjectProp("field-id") as? Number)?.toInt(), f.schema()) } }

    private fun avroColumn(name: String, fieldId: Int?, schema: Schema): FileColumn {
        val type = if (schema.type == Schema.Type.UNION) schema.types.firstOrNull { it.type != Schema.Type.NULL } ?: schema else schema
        // `getProp` answers string-valued props only; an id is a JSON number, so `getObjectProp`.
        fun idOf(s: Schema, prop: String) = (s.getObjectProp(prop) as? Number)?.toInt()
        return when (type.type) {
            Schema.Type.RECORD -> FileColumn(name, fieldId, FileColumn.Kind.STRUCT, type.fields.map { avroColumn(it.name(), (it.getObjectProp("field-id") as? Number)?.toInt(), it.schema()) })
            Schema.Type.ARRAY -> FileColumn(name, fieldId, FileColumn.Kind.LIST, listOf(avroColumn("element", idOf(type, "element-id"), type.elementType)))
            Schema.Type.MAP -> FileColumn(
                name, fieldId, FileColumn.Kind.MAP,
                listOf(FileColumn("key", idOf(type, "key-id"), FileColumn.Kind.PRIMITIVE), avroColumn("value", idOf(type, "value-id"), type.valueType)),
            )
            else -> FileColumn(name, fieldId, FileColumn.Kind.PRIMITIVE)
        }
    }

    /**
     * The `avro.codec` an Avro file's header names — `null` when the header names none, which
     * the format reads as uncompressed. Opens the file and reads its header only.
     */
    fun codecOf(localPath: String): String? =
        DataFileReader(ChannelInput(Files.newByteChannel(StorageLocation.pathOf(localPath))), GenericDatumReader<GenericRecord>())
            .use { it.getMetaString("avro.codec") }

    /**
     * The shared read loop. [decode] carries the reified type from the inlined caller, so this
     * stays a single implementation rather than one copy per entry point.
     */
    @PublishedApi
    internal fun <R> read(
        localPath: String,
        typeName: String?,
        decode: (GenericRecord) -> R,
    ): ReadResult<R> {
        logger.debug("Reading Avro file: {} (type={})", localPath, typeName)
        val path = StorageLocation.pathOf(localPath)

        return DataFileReader(
            ChannelInput(Files.newByteChannel(path)), GenericDatumReader<GenericRecord>(),
        ).use { reader ->
            val entries = mutableListOf<R>()
            val errors = mutableListOf<ReadError>()
            var rowIndex = 0

            reader.forEach { record ->
                try {
                    entries.add(decode(record))
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

            val fileMetadata = reader.metaKeys.associateWith { key ->
                // Avro metadata values are bytes; Iceberg writes UTF-8 text. A value that is not
                // text comes back as whatever the bytes decode to rather than failing the read.
                runCatching { reader.getMetaString(key) }.getOrNull().orEmpty()
            }

            logger.debug("Avro file read complete: {} ({} entries, {} errors)", localPath, entries.size, errors.size)
            ReadResult(entries = entries, errors = errors, fileMetadata = fileMetadata)
        }
    }
}
