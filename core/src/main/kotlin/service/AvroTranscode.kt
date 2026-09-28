package service

import org.apache.avro.file.CodecFactory
import org.apache.avro.file.DataFileReader
import org.apache.avro.file.DataFileWriter
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.generic.GenericDatumWriter
import org.apache.avro.generic.GenericRecord
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * A copy of an Avro file under a codec DuckDB reads, written once per session and handed to
 * the SQL readers in place of the original — the way through a refused codec
 * ([AVRO_CODECS_REFUSED]) for the readers that scan the whole file, where reading the rows in
 * this process would be a second query engine ([AvroRows] serves the row cards, whose sample
 * is the first block). The copy is made block by block (`appendAllFrom` with `recompress`):
 * each block decompressed under the file's codec and compressed under `deflate`, no record
 * decoded, the header's own metadata carried over. It costs a read and a write of the file,
 * which for a reader that scans it anyway is about one extra scan, paid once.
 *
 * The copies are kept in a [LocalCopyCache] bounded by [maxBytes] — under the file's own name,
 * the least recently used going first — and a file larger than it on its own is refused with the
 * sentence the readers print, which names the codec.
 */
class AvroTranscodeCache(private val maxBytes: Long) {
    private val copies = LocalCopyCache(maxBytes, "ice-lens-avro")

    /** Copies made this session, for the tests. */
    val size: Int get() = copies.size

    /**
     * The path of a copy of [localPath] under `deflate`, written now if not yet this session.
     * Keyed by the path with the file's size and modification time where the filesystem gives
     * them, so a file regenerated in place is copied again.
     */
    fun readablePathOf(localPath: String): String {
        val source = StorageLocation.pathOf(localPath)
        val sourceBytes = runCatching { Files.size(source) }.getOrNull()
        val key = "$localPath|$sourceBytes|${runCatching { Files.getLastModifiedTime(source).toMillis() }.getOrNull()}"
        val name = localPath.substringAfterLast('/')
        // Nothing is written for a file that cannot fit: a copy decompresses to at least the source's size.
        return copies.copyOf(
            key = key,
            name = name,
            sourceBytes = sourceBytes,
            tooLarge = { bytes, written ->
                val codec = SampleRowReader.avroCodecUnreadable(runCatching { AvroReader.codecOf(localPath) }.getOrNull() ?: "?", name)
                if (written) "$codec, and a copy under deflate is $bytes bytes, larger than the $maxBytes bytes of copies this session keeps"
                else "$codec, and at $bytes bytes it is larger than the $maxBytes bytes of copies this session keeps"
            },
        ) { target ->
            transcode(source, target)
            logger.info("Copied {} under deflate for DuckDB: {} bytes at {}", name, Files.size(target), target)
        }.toString()
    }

    private fun transcode(source: Path, target: Path) {
        DataFileReader(AvroReader.ChannelInput(Files.newByteChannel(source)), GenericDatumReader<GenericRecord>()).use { reader ->
            val writer = DataFileWriter(GenericDatumWriter<GenericRecord>()).setCodec(CodecFactory.deflateCodec(DEFLATE_LEVEL))
            // An Iceberg data file's header names its schema and the writer that made it; carried over, since DuckDB reads only the bytes it needs and a reader of the copy may want them.
            reader.metaKeys.filter { !it.startsWith("avro.") }.forEach { key -> writer.setMeta(key, reader.getMeta(key)) }
            writer.create(reader.schema, target.toFile()).use { w -> w.appendAllFrom(reader, true) }
        }
    }


    companion object {
        private val logger = LoggerFactory.getLogger(AvroTranscodeCache::class.java)

        /** What the session's copies may hold at once. */
        const val MAX_CACHE_BYTES = 2L shl 30
        /** Fast over small: the copy is read once per query and thrown away with the session. */
        private const val DEFLATE_LEVEL = 1

        /** The one cache the readers share. */
        val session: AvroTranscodeCache by lazy { AvroTranscodeCache(MAX_CACHE_BYTES) }
    }
}
