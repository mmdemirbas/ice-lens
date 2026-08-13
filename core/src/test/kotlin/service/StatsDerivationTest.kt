package service

import model.ContentStats
import model.PaimonUnifiedTableModel
import model.StatsDerivation
import model.UnifiedTableModel
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins the property that makes a derivation worth carrying: the total is folded from the
 * contribution list, so the two cannot disagree.
 *
 * An explanation computed beside a number is a second implementation of that number, and two
 * implementations drift. These tests fail if anyone reintroduces a stored total — dropping a
 * contribution has to move the figure, or the ledger is decoration.
 */
class StatsDerivationTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun partedSummary() =
        IcebergGraphBuilder.buildTableSummary(
            UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/parted").absolutePath))
        )

    /**
     * Each counted contribution is dropped in turn, not just the first. Dropping only one leaves
     * the assertion satisfiable by a total that happens to equal that contribution — which is
     * exactly what a total that stopped folding looks like.
     */
    @Test
    fun `dropping any contribution changes the total by exactly that contribution`() {
        val derivation = partedSummary().historyDerivation
        val counted = derivation.contributions.filter { !it.isRepeat }
        assertTrue(counted.isNotEmpty(), "the partitioned fixture should contribute manifests")

        counted.forEach { dropped ->
            val without = StatsDerivation(derivation.contributions - dropped)

            assertNotEquals(
                derivation.total, without.total,
                "the total ignored the removal of ${dropped.manifestPath}",
            )
            assertEquals(
                derivation.total, without.total + dropped.delta,
                "putting ${dropped.manifestPath} back did not restore the total",
            )
        }
    }

    @Test
    fun `an empty ledger folds to zero rather than to a remembered figure`() {
        assertEquals(ContentStats(), StatsDerivation().total)
    }

    /**
     * The witness half. Every snapshot re-lists the manifests it carries forward, so the second
     * commit sees the first commit's manifest again; counting it twice is the defect this
     * deduplication exists to prevent, and the repeat entry is the evidence that it did not.
     */
    @Test
    fun `a manifest carried forward is recorded as a repeat naming where it was first counted`() {
        val derivation = partedSummary().historyDerivation
        val repeats = derivation.repeats

        assertTrue(
            repeats.isNotEmpty(),
            "the two-commit fixture carries its first manifest forward, so a repeat is expected",
        )
        repeats.forEach { repeat ->
            assertEquals(ContentStats(), repeat.delta, "a repeat must contribute nothing")
            assertTrue(
                repeat.firstCountedIn?.startsWith("snapshot ") == true,
                "a repeat should name the snapshot that counted it first, got ${repeat.firstCountedIn}",
            )
            assertTrue(
                derivation.contributions.any { it.manifestPath == repeat.manifestPath && !it.isRepeat },
                "a repeat of a manifest that was never counted: ${repeat.manifestPath}",
            )
        }
    }

    @Test
    fun `each manifest is counted exactly once however many snapshots list it`() {
        val derivation = partedSummary().historyDerivation
        val countedPerManifest = derivation.contributions
            .filter { !it.isRepeat }
            .groupingBy { it.manifestPath }
            .eachCount()

        countedPerManifest.forEach { (path, times) ->
            assertEquals(1, times, "$path was counted $times times")
        }
        assertEquals(
            countedPerManifest.size,
            derivation.total.manifestCount,
            "manifest count disagrees with the number of counted contributions",
        )
    }

    /**
     * Paimon applies a snapshot's delta manifest list over its base, so a manifest recording a
     * removal takes files back out of the running total. That has to survive as a negative delta:
     * a fold cannot express removal any other way, and clamping it at zero would make the ledger
     * add up to a different number than the table has.
     */
    @Test
    fun `the Paimon current derivation folds to its own total, negative deltas included`() {
        val tableDir = File(repoRoot, "example/paimon/db.db/test")
        assertTrue(tableDir.isDirectory, "example Paimon table missing at $tableDir")
        val summary = PaimonGraphBuilder.buildTableSummary(
            PaimonUnifiedTableModel(Paths.get(tableDir.absolutePath))
        )

        val refolded = summary.currentDerivation.contributions
            .fold(ContentStats()) { running, contribution -> running + contribution.delta }
        assertEquals(summary.current, refolded)
        assertTrue(
            summary.currentDerivation.contributions.isNotEmpty(),
            "a Paimon table with a snapshot should produce contributions",
        )
    }
}
