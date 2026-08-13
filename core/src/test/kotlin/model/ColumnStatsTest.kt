package model

import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bound-decoding chain end to end, against the real Spark-written manifest in `example/`.
 *
 * `SingleValueDecoderTest` covers the encoding with hand-built bytes. This covers the part that
 * cannot be faked: that the schema really does travel in the manifest's own Avro file metadata,
 * that the reader keeps it, and that a field id in `lower_bounds` resolves through it to the
 * right type. Every link in that chain is a place where a plausible wrong answer is possible.
 */
class ColumnStatsTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun exampleTable(): UnifiedTableModel =
        UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/test").absolutePath))

    @Test
    fun `the manifest carries the schema its bounds were written against`() {
        val manifest = exampleTable().metadatas
            .flatMap { it.snapshots }
            .flatMap { it.manifests }
            .firstOrNull()
        assertNotNull(manifest, "example table should contain a manifest")

        val schema = manifest.schema
        assertNotNull(schema, "manifest did not carry a schema in its Avro file metadata")
        assertEquals(IcebergType.IntType, schema.typeOf(1))
        assertEquals(IcebergType.StringType, schema.typeOf(2))
        assertEquals("k", schema.nameOf(1))
        assertEquals("v", schema.nameOf(2))
    }

    @Test
    fun `bounds in the example table decode to their real values`() {
        val manifest = exampleTable().metadatas
            .flatMap { it.snapshots }
            .flatMap { it.manifests }
            .first()
        val entry = manifest.dataFiles.first().metadata
        val dataFile = assertNotNull(entry.dataFile, "entry had no data_file")

        val stats = columnStatsFor(dataFile, manifest.schema).associateBy { it.displayName }
        assertEquals(setOf("k", "v"), stats.keys)

        val k = stats.getValue("k")
        assertEquals("1", k.lowerBound?.display)
        assertEquals("2", k.upperBound?.display)
        assertEquals(2L, k.valueCount)
        assertEquals(0L, k.nullValueCount)

        val v = stats.getValue("v")
        assertEquals("hello", v.lowerBound?.display)
        assertEquals("world", v.upperBound?.display)
    }

    @Test
    fun `without a schema the counts still come through and the bounds stay undecoded`() {
        val manifest = exampleTable().metadatas
            .flatMap { it.snapshots }
            .flatMap { it.manifests }
            .first()
        val dataFile = assertNotNull(manifest.dataFiles.first().metadata.dataFile)

        // Refusing to guess a type is the honest behaviour: a wrong type yields a plausible
        // wrong number, which is worse than showing nothing.
        val stats = columnStatsFor(dataFile, schema = null).associateBy { it.fieldId }
        assertEquals(2, stats.getValue(1).valueCount)
        assertNull(stats.getValue(1).lowerBound)
        assertNull(stats.getValue(1).type)
        assertEquals("field 1", stats.getValue(1).displayName)
    }

    @Test
    fun `a column mentioned by only one statistics map still gets a row`() {
        // Bounds without counts is a real shape — a writer may record one and not the other, and
        // dropping the column would hide that asymmetry from the reader.
        val dataFile = DataFile(
            filePath = "data/part-0.parquet",
            lowerBounds = listOf(KeyValuePairBytes(7, byteArrayOf(5, 0, 0, 0))),
            valueCounts = listOf(KeyValuePairLong(9, 100L)),
        )
        val schema = requireNotNull(
            parseIcebergSchema(
                """{"type":"struct","schema-id":0,"fields":[
                   {"id":7,"name":"amount","required":false,"type":"int"},
                   {"id":9,"name":"label","required":false,"type":"string"}]}"""
            )
        )

        val stats = columnStatsFor(dataFile, schema).associateBy { it.displayName }
        assertEquals(setOf("amount", "label"), stats.keys)
        assertEquals("5", stats.getValue("amount").lowerBound?.display)
        assertNull(stats.getValue("amount").valueCount)
        assertNull(stats.getValue("label").lowerBound)
        assertEquals(100L, stats.getValue("label").valueCount)
    }

    @Test
    fun `an all-null column is flagged so an absent bound is explained`() {
        val dataFile = DataFile(
            filePath = "data/part-0.parquet",
            valueCounts = listOf(KeyValuePairLong(1, 500L)),
            nullValueCounts = listOf(KeyValuePairLong(1, 500L)),
        )
        val stats = columnStatsFor(dataFile, schema = null).first()
        assertTrue(stats.isAllNull, "500 of 500 null should read as all-null")
    }
}
