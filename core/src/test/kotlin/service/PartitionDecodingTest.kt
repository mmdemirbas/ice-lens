@file:OptIn(com.github.avrokotlin.avro4k.ExperimentalAvro4kApi::class)

package service

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.schema
import model.DataFileContent
import model.DecodedPartition
import model.ManifestEntry
import model.ManifestEntryStatus
import model.UnifiedDataFile
import model.UnifiedTableModel
import org.apache.avro.Schema
import org.apache.avro.file.DataFileWriter
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericDatumWriter
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Decodes `data_file.partition` from a real partitioned table and checks the result against the
 * paths Iceberg itself chose for those files.
 *
 * The oracle here is independent of this codebase. `example/iceberg/default/parted` was written
 * by Spark 3.5.5 / Iceberg 1.8.1, and Iceberg laid each data file under a directory named from
 * its own rendering of the partition tuple — `name=alpha/amount=-5.50/id_bucket=2/...`. Nothing
 * here derives that string from our decoder, so agreeing with it means agreeing with Iceberg.
 *
 * The table exercises the transforms whose result types differ in ways that fail silently:
 * `day` produces a date while `year`, `month` and `hour` produce int ordinals in the same four
 * bytes, and an identity partition on `decimal(9,2)` arrives as a big-endian Avro `fixed` while
 * every numeric bound is little-endian. It also carries pre-epoch values (a 1969 row, so the
 * ordinals go negative) and a negative decimal, because both decode to plausible wrong answers
 * under truncating division or an unsigned read.
 */
class PartitionDecodingTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun partedDataFiles(): List<UnifiedDataFile> {
        val tableDir = File(repoRoot, "example/iceberg/default/parted")
        assertTrue(tableDir.isDirectory, "partitioned fixture missing at $tableDir")
        val model = UnifiedTableModel(Paths.get(tableDir.absolutePath))
        assertEquals(emptyList(), model.readErrors, "table-level read errors on the partitioned fixture")
        return model.metadatas.flatMap { it.snapshots }.flatMap { it.manifests }.flatMap { it.dataFiles }
    }

    /**
     * The load-bearing assertion: our rendering of every partition tuple equals the directory
     * Iceberg put the file in.
     */
    @Test
    fun `decoded partition path matches the directory Iceberg wrote the file into`() {
        val dataFiles = partedDataFiles()
        assertTrue(dataFiles.isNotEmpty(), "fixture should contain data files")

        dataFiles.forEach { dataFile ->
            val partition = assertNotNull(dataFile.partition, "no partition decoded for ${dataFile.path}")
            val expected = partitionPathFromFileLocation(dataFile.path.toString())
            assertEquals(
                expected, partition.path,
                "decoded partition disagrees with the path Iceberg chose for ${dataFile.path.fileName}",
            )
        }
    }

    /** Pulls the `a=b/c=d` segments out of the data file's own location. */
    private fun partitionPathFromFileLocation(location: String): String =
        location.split('/').filter { it.contains('=') }.joinToString("/")

    @Test
    fun `every partition field of the spec is decoded, in spec order`() {
        val partition = assertNotNull(partedDataFiles().first().partition)
        assertEquals(
            listOf("name", "amount", "id_bucket", "name_trunc", "d_day", "ts_h_hour", "ts_m_month", "ts_y_year"),
            partition.values.map { it.field.name },
        )
    }

    /**
     * `day` yields a date and its three sibling time transforms yield int ordinals. All four are
     * four little-endian bytes, so a wrong result type here produces a plausible value, never an
     * error — which is why this is pinned separately from the path comparison.
     */
    @Test
    fun `time transforms decode to their own result types, not to each other's`() {
        val byName = valuesByFieldName(partitionFor(dayPartition = "2024-03-05"))

        assertEquals("date", byName.getValue("d_day").type.typeName)
        assertEquals("2024-03-05", byName.getValue("d_day").stored.display)

        listOf("ts_h_hour", "ts_m_month", "ts_y_year").forEach { field ->
            assertEquals("int", byName.getValue(field).type.typeName, "$field should be an int ordinal")
        }
    }

    /**
     * A `year` partition stores an offset from 1970, so 2024 is `54` on disk. Both readings have
     * to survive: the stored ordinal is what the file contains, the rendering is what it means.
     */
    @Test
    fun `ordinal time transforms keep the stored value and render Iceberg's own form`() {
        val byName = valuesByFieldName(partitionFor(dayPartition = "2024-03-05"))

        val year = byName.getValue("ts_y_year")
        assertEquals("54", year.stored.display, "2024 is stored as an offset from 1970")
        assertEquals("2024", year.human)
        assertTrue(year.isRenderedDifferently)

        assertEquals("2024-03", byName.getValue("ts_m_month").human)
        assertEquals("2024-03-05-07", byName.getValue("ts_h_hour").human)
    }

    /**
     * Pre-epoch values make every ordinal negative. Truncating division puts them in the wrong
     * year, and the wrong answer looks entirely reasonable.
     */
    @Test
    fun `pre-epoch partition values render with floor arithmetic, not truncation`() {
        val byName = valuesByFieldName(partitionFor(dayPartition = "1969-06-11"))

        assertEquals("1969", byName.getValue("ts_y_year").human)
        assertEquals("1969-06", byName.getValue("ts_m_month").human)
        assertEquals("1969-06-11-01", byName.getValue("ts_h_hour").human)
        assertEquals("1969-06-11", byName.getValue("d_day").stored.display)
    }

    /**
     * An identity partition on `decimal(9,2)` arrives as a big-endian Avro `fixed`, unlike every
     * numeric value elsewhere in a manifest. A negative one also has to survive the two's
     * complement read rather than coming back as a large positive.
     */
    @Test
    fun `decimal partition values decode with the schema's scale, including negatives`() {
        val decimals = partedDataFiles()
            .mapNotNull { it.partition }
            .map { p -> p.values.first { it.field.name == "amount" } }

        assertEquals("decimal(9, 2)", decimals.first().type.typeName)
        assertEquals(
            setOf("12.34", "0.01", "98765.43", "-5.50"),
            decimals.map { it.stored.display }.toSet(),
        )
    }

    @Test
    fun `bucket and truncate transforms decode to int and to the source type`() {
        val byName = valuesByFieldName(partitionFor(dayPartition = "2025-11-20"))

        assertEquals("int", byName.getValue("id_bucket").type.typeName)
        assertEquals("0", byName.getValue("id_bucket").stored.display)
        assertEquals("string", byName.getValue("name_trunc").type.typeName)
        assertEquals("bra", byName.getValue("name_trunc").stored.display)
        assertEquals("bravo", byName.getValue("name").stored.display)
    }

    /**
     * The unpartitioned fixture must come back as a spec with no fields, not as a null partition:
     * "this table is not partitioned" and "the spec could not be read" are different answers.
     */
    @Test
    fun `an unpartitioned table decodes to an empty partition, not to a missing one`() {
        val tableDir = File(repoRoot, "example/iceberg/default/test")
        val model = UnifiedTableModel(Paths.get(tableDir.absolutePath))
        val dataFiles = model.metadatas.flatMap { it.snapshots }
            .flatMap { it.manifests }.flatMap { it.dataFiles }

        assertTrue(dataFiles.isNotEmpty(), "unpartitioned fixture should contain data files")
        dataFiles.forEach { dataFile ->
            val partition = assertNotNull(dataFile.partition, "spec should have been read for ${dataFile.path}")
            assertTrue(partition.isUnpartitioned, "table has no partition fields")
            assertEquals("", partition.path)
        }
    }

    /**
     * A manifest whose records carry no `partition` field at all must still decode.
     *
     * Avro's `GenericRecord.get(String)` throws for an unknown field rather than returning null,
     * so reaching for `partition` where the writer never wrote one failed every record in the
     * file — the manifest read as empty, with the entries reported as decode errors.
     */
    @Test
    fun `a manifest with no partition field in its schema still decodes`() {
        val file = File.createTempFile("no-partition", ".avro").also { it.delete() }
        try {
            writeManifestWithoutPartitionField(file)

            val result = IcebergReader.readManifestFile(file.absolutePath)

            assertEquals(emptyList(), result.errors, "a manifest without a partition field failed to decode")
            assertEquals(1, result.entries.size)
            assertEquals(null, result.entries.single().partition, "no partition struct to extract")
        } finally {
            file.delete()
        }
    }

    /** Writes a manifest from `Avro.schema<ManifestEntry>()`, which has no `partition` field. */
    private fun writeManifestWithoutPartitionField(file: File) {
        val schema = Avro.schema<ManifestEntry>()
        DataFileWriter(GenericDatumWriter<GenericData.Record>(schema)).use { writer ->
            writer.create(schema, file)
            val record = GenericData.Record(schema)
            record.put("status", ManifestEntryStatus.ADDED)
            record.put("snapshot_id", 1L)
            val dataFileField = schema.getField("data_file").schema()
            val dataFileSchema =
                if (dataFileField.isUnion) dataFileField.types.first { it.type == Schema.Type.RECORD }
                else dataFileField
            val dataFile = GenericData.Record(dataFileSchema)
            dataFile.put("file_path", "data/file-1.parquet")
            dataFile.put("file_format", "PARQUET")
            dataFile.put("record_count", 100L)
            dataFile.put("file_size_in_bytes", 4096L)
            dataFile.put("content", DataFileContent.DATA)
            record.put("data_file", dataFile)
            writer.append(record)
        }
    }

    private fun partitionFor(dayPartition: String): DecodedPartition =
        partedDataFiles()
            .first { it.path.toString().contains("d_day=$dayPartition") }
            .partition!!

    private fun valuesByFieldName(partition: DecodedPartition) =
        partition.values.associateBy { it.field.name.orEmpty() }
}
