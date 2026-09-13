package model

import service.GraphLayoutService
import java.io.File
import java.nio.file.Paths
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A Paimon manifest list's recorded figures against the manifest's own entries, on every
 * checked-in Paimon table — `manifestTallies` for the other format.
 *
 * What the manifest list records is what a scan plans with without opening the manifest: the
 * entry counts, the bucket and level ranges, and a per-column minimum and maximum over the
 * entries' partitions. Nothing on the read path checks any of it, and the comparison is also the
 * only place this code's reading of `_PARTITION` and `_PARTITION_STATS` — two `BinaryRow`
 * encodings — is put against figures Paimon itself derived from the same entries.
 */
class PaimonManifestTallyTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun manifestNodes(name: String): List<GraphNode.PaimonManifestNode> {
        val model = PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/$name").absolutePath))
        return GraphLayoutService.layoutGraph(model, showRows = false).nodes.filterIsInstance<GraphNode.PaimonManifestNode>()
    }

    /**
     * Every recorded figure on every manifest of every fixture agrees with its entries — counts
     * and ranges alike, and on the tables with removals (`dv`, `cl`, `tg`) that is what settles
     * that the ranges cover `_KIND = 1` entries as well as additions.
     */
    @Test
    fun `every recorded figure on every checked-in manifest agrees with its entries`() {
        val disagreements = mutableListOf<String>()
        var checked = 0
        FixtureCatalog.paimon.forEach { name ->
            val nodes = manifestNodes(name)
            assertTrue(nodes.isNotEmpty(), name)
            nodes.forEach { node ->
                val tallies = paimonManifestTallies(node.data, node.entries, node.partitionMin, node.partitionMax)
                tallies.forEach { tally ->
                    if (tally.agrees == null) return@forEach
                    checked++
                    if (tally.agrees == false) {
                        disagreements += "$name/${node.data.fileName}: ${tally.label} recorded ${tally.recorded}, entries ${tally.counted}"
                    }
                }
            }
        }
        assertEquals(emptyList(), disagreements)
        assertTrue(checked >= 60, "only $checked figures had anything to check: $checked")
    }

    /**
     * The minimum is per column, and this is the manifest that can tell.
     *
     * `pt`'s third commit wrote `(2024-03-07, eu)` and `(2024-03-05, north-america)` into one
     * manifest. Its recorded minimum is `(2024-03-05, eu)` — a partition no entry has — and its
     * maximum `(2024-03-07, north-america)`, likewise. A fold that took the lowest *entry* would
     * disagree with a manifest list Paimon wrote correctly, on exactly this manifest.
     */
    @Test
    fun `the partition minimum is per column, a partition no entry of the manifest has`() {
        val node = manifestNodes("pt").single { n ->
            n.entries.map { it.partition?.values?.get(0)?.value }.toSet() == setOf(LocalDate.of(2024, 3, 7), LocalDate.of(2024, 3, 5))
        }
        assertEquals(2, node.entries.size)
        val partitions = node.entries.map { it.partition!!.display }.toSet()
        assertEquals(setOf("dt=2024-03-07, region=eu", "dt=2024-03-05, region=north-america"), partitions)

        assertEquals("dt=2024-03-05, region=eu", node.partitionMin!!.display)
        assertEquals("dt=2024-03-07, region=north-america", node.partitionMax!!.display)
        assertTrue(node.partitionMin!!.display !in partitions, "the recorded minimum is not an entry")

        val byLabel = paimonManifestTallies(node.data, node.entries, node.partitionMin, node.partitionMax).associateBy { it.label }
        listOf("dt minimum", "dt maximum", "region minimum", "region maximum", "dt nulls", "region nulls").forEach { label ->
            assertEquals(true, byLabel.getValue(label).agrees, "$label: ${byLabel.getValue(label)}")
        }
        assertEquals("0", byLabel.getValue("dt nulls").recorded)
    }

    /** An unpartitioned table records a zero-field stats row and no partition figures come of it. */
    @Test
    fun `an unpartitioned manifest has counts and ranges but no partition figures`() {
        manifestNodes("dv").forEach { node ->
            val labels = paimonManifestTallies(node.data, node.entries, node.partitionMin, node.partitionMax).map { it.label }
            assertEquals(
                listOf("Added entries", "Deleted entries", "Lowest bucket", "Highest bucket", "Lowest level", "Highest level"),
                labels,
            )
            assertEquals(DecodedPaimonPartition(emptyList()), node.partitionMin)
        }
    }
}
