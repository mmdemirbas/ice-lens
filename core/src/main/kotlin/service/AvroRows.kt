package service

import org.apache.avro.Conversions
import org.apache.avro.data.TimeConversions
import org.apache.avro.file.DataFileReader
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.generic.GenericEnumSymbol
import org.apache.avro.generic.GenericFixed
import org.apache.avro.generic.GenericRecord
import org.apache.avro.util.Utf8
import java.nio.ByteBuffer
import java.nio.file.Files

/**
 * An Avro data file's first rows, read in this process through the Avro library that reads
 * the manifests — the way in when DuckDB's Avro reader refuses the file's codec
 * ([AVRO_CODECS_REFUSED]), which on Paimon's `file.format = avro` is the default: every data
 * file of such a table is `zstandard`, and the row cards said so instead of showing a row.
 *
 * The rows only. A sample is the file's first block, which is cheap whatever the file's size;
 * the readers that run SQL over a file — the lookup, the merged count, the live count, the
 * statistics sweep — scan the whole of it through DuckDB and still refuse the codec, since a
 * `WHERE` over rows read here would be a second query engine. The values arrive the way the
 * file's logical types say ([conversions]): a `date` as a `LocalDate`, a `timestamp-millis` as
 * an `Instant`, a `decimal` as a `BigDecimal` — the shape DuckDB's JDBC driver gives a Parquet
 * row's, near enough for a card, and a nested record, list or map as the plain values inside it.
 */
object AvroRows {
    /** `GenericData` with every logical type the library converts, so a date is not printed as its epoch day. */
    private val conversions: GenericData = GenericData().apply {
        addLogicalTypeConversion(Conversions.DecimalConversion())
        addLogicalTypeConversion(Conversions.UUIDConversion())
        addLogicalTypeConversion(TimeConversions.DateConversion())
        addLogicalTypeConversion(TimeConversions.TimeMillisConversion())
        addLogicalTypeConversion(TimeConversions.TimeMicrosConversion())
        addLogicalTypeConversion(TimeConversions.TimestampMillisConversion())
        addLogicalTypeConversion(TimeConversions.TimestampMicrosConversion())
        addLogicalTypeConversion(TimeConversions.LocalTimestampMillisConversion())
        addLogicalTypeConversion(TimeConversions.LocalTimestampMicrosConversion())
    }

    /** The first [limit] rows of [localPath], one map per row in the schema's field order; a null cell is the string `"null"`, as DuckDB's arrive. */
    fun readSampleRows(localPath: String, limit: Int): List<Map<String, Any>> =
        DataFileReader(AvroReader.ChannelInput(Files.newByteChannel(StorageLocation.pathOf(localPath))), GenericDatumReader<GenericRecord>(null, null, conversions))
            .use { reader ->
                val rows = mutableListOf<Map<String, Any>>()
                while (rows.size < limit && reader.hasNext()) {
                    val record = reader.next()
                    rows += record.schema.fields.associate { f -> f.name() to cellOf(record.get(f.pos())) }
                }
                rows
            }

    /** An Avro value as a cell: text as `String`, a nested structure as plain Kotlin collections, bytes by their size. */
    private fun cellOf(value: Any?): Any = when (value) {
        null -> "null"
        is Utf8, is GenericEnumSymbol<*> -> value.toString()
        is GenericRecord -> value.schema.fields.associate { f -> f.name() to cellOf(value.get(f.pos())) }
        is GenericFixed -> "<${value.bytes().size} bytes>"
        is ByteBuffer -> "<${value.remaining()} bytes>"
        is Collection<*> -> value.map(::cellOf)
        is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to cellOf(v) }
        else -> value
    }
}
