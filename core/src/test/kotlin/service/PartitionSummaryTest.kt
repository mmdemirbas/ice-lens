package service

import model.DecodedValue
import model.UnifiedManifest
import model.UnifiedTableModel
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `manifest_file.partitions` — the per-partition-field bounds a scan reads to decide whether to
 * open a manifest at all.
 *
 * Without these the tool can show *that* a manifest exists but not *why* a planner would skip
 * it, which is the question a manifest is mostly asked. It was the first item on the
 * format-coverage list for that reason.
 *
 * The check here needs no hand-written expected values, and does not compare our output to our
 * output. The summaries live in the **manifest list**; each file's partition tuple lives in the
 * **manifest**. Iceberg wrote the two separately, and a summary must bracket exactly the files
 * its manifest holds — so agreement between them is agreement with Iceberg twice over.
 */
class PartitionSummaryTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun manifestsOf(fixture: String): List<UnifiedManifest> {
        val tableDir = File(repoRoot, "example/iceberg/default/$fixture")
        assertTrue(tableDir.isDirectory, "fixture missing at $tableDir")
        return UnifiedTableModel(Paths.get(tableDir.absolutePath))
            .metadatas.flatMap { it.snapshots }.flatMap { it.manifests }
            .distinctBy { it.path.fileName.toString() }
    }

    /** Compares decoded values by their real type where possible, not by their rendering. */
    private fun compareDecoded(a: DecodedValue, b: DecodedValue): Int {
        val left = a.value
        val right = b.value
        if (left is Comparable<*> && right != null && left::class == right::class) {
            @Suppress("UNCHECKED_CAST")
            return (left as Comparable<Any>).compareTo(right)
        }
        return a.display.compareTo(b.display)
    }

    @Test
    fun `a partitioned manifest carries one summary per spec field, in spec order`() {
        val manifests = manifestsOf("parted")
        assertTrue(manifests.isNotEmpty())

        manifests.forEach { manifest ->
            val specFields = manifest.partitionSpec?.fields.orEmpty().map { it.name }
            assertTrue(specFields.isNotEmpty(), "the partitioned fixture should have spec fields")
            assertEquals(
                specFields, manifest.partitionSummaries.map { it.field.name },
                "summaries are positional, so they must line up with the spec for ${manifest.path.fileName}",
            )
        }
    }

    /**
     * The cross-check. For every partition field of every manifest, the summary's bounds must be
     * exactly the minimum and maximum of the values its own files carry.
     */
    @Test
    fun `summary bounds are the range of the files the manifest actually holds`() {
        listOf("parted", "respec").forEach { fixture ->
            manifestsOf(fixture).forEach { manifest ->
                val summaries = manifest.partitionSummaries
                assertTrue(summaries.isNotEmpty(), "$fixture: no summaries on ${manifest.path.fileName}")

                summaries.forEachIndexed { index, summary ->
                    val fileValues = manifest.dataFiles
                        .mapNotNull { it.partition }
                        .map { it.values[index].stored }
                    assertTrue(fileValues.isNotEmpty(), "$fixture: manifest holds no files")

                    val min = fileValues.minWith(::compareDecoded)
                    val max = fileValues.maxWith(::compareDecoded)
                    val where = "$fixture ${manifest.path.fileName} field ${summary.field.name}"

                    assertEquals(min.display, summary.lower?.display, "lower bound disagrees with the files: $where")
                    assertEquals(max.display, summary.upper?.display, "upper bound disagrees with the files: $where")
                }
            }
        }
    }

    /**
     * A manifest whose bounds meet on a field holds one partition for it — the shape a
     * well-clustered write produces, and the one that makes pruning effective. The partitioned
     * fixture has both kinds, so the flag is not vacuously true or false.
     */
    @Test
    fun `single-partition and multi-partition fields are distinguished`() {
        val summaries = manifestsOf("parted").flatMap { it.partitionSummaries }
        assertTrue(summaries.any { it.isSingleValue }, "some field should be pinned to one value")
        assertTrue(summaries.any { !it.isSingleValue }, "some field should span a range")
    }

    /**
     * Ordinal transforms render in the summary the same way they do on a file. A `year` bound of
     * 54 is 2024, and showing the ordinal in one place and the year in the other would make the
     * two tables look like they disagree.
     */
    @Test
    fun `ordinal transforms render in summaries the way they render on files`() {
        val yearSummaries = manifestsOf("parted")
            .flatMap { it.partitionSummaries }
            .filter { it.field.name == "ts_y_year" }

        assertTrue(yearSummaries.isNotEmpty(), "the fixture partitions by year")
        yearSummaries.forEach { summary ->
            assertEquals("int", summary.type.typeName, "a year partition is an int ordinal")
            assertTrue(
                summary.humanLower?.matches(Regex("\\d{4}")) == true,
                "a year bound should render as a year, got ${summary.humanLower} from ${summary.lower?.display}",
            )
        }
    }

    /** An unpartitioned table has no partition fields, so it has no summaries — not empty rows. */
    @Test
    fun `an unpartitioned table produces no summaries`() {
        manifestsOf("test").forEach { manifest ->
            assertEquals(emptyList(), manifest.partitionSummaries)
        }
    }

    /**
     * Constructed rather than read from a fixture, because no engine writes this: the pairing is
     * positional and the summaries carry no field ids, so a spec of a different length would
     * mislabel every field it did line up with. Decoding nothing is the only safe answer — a
     * bound attributed to the wrong partition field reads as an answer and is worse than a gap.
     *
     * Without this the guard is unexercised: loosening it to `<` passes every fixture.
     */
    @Test
    fun `a spec that disagrees with the summaries in length decodes to nothing`() {
        val twoFields = model.PartitionSpec(
            specId = 0,
            fields = listOf(
                model.PartitionField(sourceId = 1, fieldId = 1000, name = "a"),
                model.PartitionField(sourceId = 2, fieldId = 1001, name = "b"),
            ),
        )
        val threeSummaries = List(3) { model.PartitionFieldSummary(containsNull = false) }
        val oneSummary = listOf(model.PartitionFieldSummary(containsNull = false))

        assertEquals(emptyList(), model.decodePartitionSummaries(threeSummaries, twoFields, null))
        assertEquals(emptyList(), model.decodePartitionSummaries(oneSummary, twoFields, null))
        assertEquals(
            2,
            model.decodePartitionSummaries(List(2) { model.PartitionFieldSummary() }, twoFields, null).size,
            "a matching length should still decode",
        )
    }
}
