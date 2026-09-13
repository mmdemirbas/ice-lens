package model

import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The summaries a scan prunes on, against the entries under them. The corpus is the oracle in
 * one direction — every engine-written manifest agrees with its own entries, across every
 * transform `parted` and `respec` carry — and a summary edited by hand is the other: a lower
 * bound moved to the upper is a disagreement on that figure alone.
 */
class PartitionSummaryTallyTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun model(fixture: String) = UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/$fixture").absolutePath))

    private fun manifests(m: UnifiedTableModel) =
        m.metadatas.flatMap { it.snapshots }.filter { !it.expired }.flatMap { it.manifests }.distinctBy { it.metadata.manifestPath }

    @Test
    fun `every engine-written manifest's summaries agree with its entries`() {
        var compared = 0
        var fields = mutableSetOf<String>()
        for (fixture in File(repoRoot, "example/iceberg/default").listFiles()!!.filter { it.isDirectory }.map { it.name }.sorted()) {
            manifests(model(fixture)).forEach { m ->
                val tallies = partitionSummaryTallies(m.partitionSummaries, m.dataFiles.map { it.partition })
                tallies.forEach { t ->
                    if (t.agrees != null) { compared++; fields += "$fixture/${t.field}" }
                    assertTrue(t.agrees != false, "$fixture ${m.metadata.manifestPath}: ${t.field} ${t.figure} recorded ${t.recorded} counted ${t.counted}")
                }
                // A partitioned manifest whose entries all decoded compares every bound it records.
                if (m.partitionSummaries.isNotEmpty() && m.dataFiles.isNotEmpty() && m.dataFiles.all { it.partition != null }) {
                    assertTrue(tallies.any { it.figure == "Lower bound" && it.agrees == true } || m.partitionSummaries.all { it.lower == null }, "$fixture ${m.metadata.manifestPath}")
                }
            }
        }
        assertTrue(compared >= 100, "compared only $compared figures")
        assertTrue(fields.size >= 8, "saw only ${fields.size} distinct fields: $fields")
    }

    @Test
    fun `a bound moved by hand is a disagreement on that figure alone`() {
        val m = manifests(model("parted")).first { mf -> mf.partitionSummaries.any { it.lower != null && it.upper != null && it.lower != it.upper } }
        val i = m.partitionSummaries.indexOfFirst { it.lower != null && it.upper != null && it.lower != it.upper }
        val lied = m.partitionSummaries.mapIndexed { j, s -> if (j == i) s.copy(lower = s.upper) else s }
        val tallies = partitionSummaryTallies(lied, m.dataFiles.map { it.partition })
        val wrong = tallies.filter { it.agrees == false }
        assertEquals(1, wrong.size, wrong.toString())
        assertEquals(m.partitionSummaries[i].field.name, wrong.single().field)
        assertEquals("Lower bound", wrong.single().figure)
        assertEquals(m.partitionSummaries[i].humanUpper, wrong.single().recorded)
        assertEquals(m.partitionSummaries[i].humanLower, wrong.single().counted)
    }

    @Test
    fun `an entry that did not decode leaves the field uncounted rather than wrong`() {
        val m = manifests(model("parted")).first { it.partitionSummaries.isNotEmpty() && it.dataFiles.size >= 2 }
        val partitions = m.dataFiles.map { it.partition }.toMutableList<DecodedPartition?>().also { it[0] = null }
        partitionSummaryTallies(m.partitionSummaries, partitions).forEach { t ->
            assertNull(t.counted, t.toString())
            assertNull(t.agrees, t.toString())
        }
    }
}
