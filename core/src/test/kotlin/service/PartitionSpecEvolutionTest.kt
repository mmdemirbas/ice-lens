package service

import model.UnifiedDataFile
import model.UnifiedTableModel
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The partition twin of [SchemaEvolutionFixtureTest]: a table whose **spec** changed.
 *
 * `decodePartition` resolves each field's result type from the spec the manifest carries in its
 * own Avro file metadata. Every other partitioned fixture has one spec, so the manifest's spec
 * and the table's current spec are the same object and a decoder reading either passes.
 *
 * `example/iceberg/default/respec` has two, and they differ in ways that fail silently:
 *
 * | | spec 0 | spec 1 (current) |
 * |---|---|---|
 * | `name` | `identity` | dropped |
 * | `id` | `bucket[4]` as `id_bucket` | `bucket[8]` as `id_bucket_8` |
 * | `d` | `days` as `d_day`, a **date** | `months` as `d_month`, an **int ordinal** |
 *
 * The `days`/`months` change is the sharp one: both are four little-endian bytes, so a day
 * ordinal read as a month puts 2024-03-05 in the year 3499 and a month read as a day lands in
 * 1970. Neither errors, and both look like dates.
 *
 * The oracle is Iceberg's, as in [PartitionDecodingTest]: the directory it chose for each file.
 */
class PartitionSpecEvolutionTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun respecDataFiles(): List<UnifiedDataFile> {
        val tableDir = File(repoRoot, "example/iceberg/default/respec")
        assertTrue(tableDir.isDirectory, "changed-spec fixture missing at $tableDir")
        val model = UnifiedTableModel(Paths.get(tableDir.absolutePath))
        assertEquals(emptyList(), model.readErrors, "table-level read errors on the changed-spec fixture")
        return model.metadatas.flatMap { it.snapshots }.flatMap { it.manifests }.flatMap { it.dataFiles }
    }

    private fun partitionPathFromFileLocation(location: String): String =
        location.split('/').filter { it.contains('=') }.joinToString("/")

    /**
     * The load-bearing assertion, and the only one that needs no knowledge of which spec is
     * which: every file's decoded tuple equals the directory Iceberg put it in, and those
     * directories were written under two different specs.
     */
    @Test
    fun `every file decodes against the spec in force when it was written`() {
        val dataFiles = respecDataFiles()
        assertTrue(dataFiles.isNotEmpty(), "fixture should contain data files")

        dataFiles.forEach { dataFile ->
            val partition = assertNotNull(dataFile.partition, "no partition decoded for ${dataFile.path}")
            assertEquals(
                partitionPathFromFileLocation(dataFile.path.toString()),
                partition.path,
                "decoded partition disagrees with the path Iceberg chose for ${dataFile.path.fileName}",
            )
        }
    }

    @Test
    fun `the fixture really does carry two different specs`() {
        val fieldNamesPerFile = respecDataFiles()
            .mapNotNull { it.partition }
            .map { partition -> partition.values.map { it.field.name } }
            .distinct()

        assertEquals(
            setOf(
                listOf("name", "id_bucket", "d_day"),
                listOf("id_bucket_8", "d_month"),
            ),
            fieldNamesPerFile.toSet(),
            "expected files written under both specs, got $fieldNamesPerFile",
        )
    }

    /**
     * `days` yields a date and `months` an int ordinal counted from the epoch. Same four bytes,
     * same source column, different meaning — and the fixture has files of both kinds in one
     * table, which is the case a single current spec would decode wrongly for half of them.
     */
    @Test
    fun `the same source column decodes as a date under one spec and an ordinal under the other`() {
        val byField = respecDataFiles()
            .mapNotNull { it.partition }
            .flatMap { it.values }
            .groupBy { it.field.name }

        val day = byField.getValue("d_day").first { it.human == "2024-03-05" }
        assertEquals("date", day.type.typeName)
        assertEquals("2024-03-05", day.stored.display, "a day partition stores a date")

        val month = byField.getValue("d_month").first { it.human == "2025-11" }
        assertEquals("int", month.type.typeName, "a month partition stores an int ordinal")
        assertEquals(
            "${(2025 - 1970) * 12 + 10}", month.stored.display,
            "2025-11 is stored as months since the epoch",
        )
    }

    /** Pre-epoch again, on the month transform this time: the ordinal has to go negative. */
    @Test
    fun `a pre-epoch month partition renders with floor arithmetic`() {
        val month = respecDataFiles()
            .mapNotNull { it.partition }
            .flatMap { it.values }
            .first { it.field.name == "d_month" && it.human == "1969-06" }

        assertEquals("-7", month.stored.display, "June 1969 is seven months before the epoch")
    }
}
