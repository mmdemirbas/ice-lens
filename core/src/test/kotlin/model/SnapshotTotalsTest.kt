package model

import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every `total-*` figure every Iceberg fixture's writer recorded, against the live set folded
 * from the closure it names. Nothing here produced a summary, which is what makes it an oracle:
 * a status misread, a duplicate counted twice or a delete file charged at the wrong size shows up
 * as a disagreement on a table Spark wrote.
 */
class SnapshotTotalsTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val fixtures = FixtureCatalog.iceberg

    private fun table(name: String) =
        UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/$name").absolutePath))

    @Test
    fun `every recorded total agrees with the live set at that snapshot`() {
        var compared = 0
        val disagreements = mutableListOf<String>()
        fixtures.forEach { name ->
            table(name).metadatas.last().snapshots.filter { !it.expired }.forEach { snapshot ->
                snapshotTotals(snapshot.metadata.summary, liveFilesOf(snapshot)).forEach { tally ->
                    if (tally.recorded == null) return@forEach
                    compared++
                    if (tally.agrees != true) disagreements += "$name snapshot ${snapshot.metadata.snapshotId} ${tally.label}: recorded ${tally.recorded}, counted ${tally.counted}"
                }
            }
        }
        assertEquals(emptyList(), disagreements)
        // The corpus yields 336 comparisons; the bound is well under that so adding a fixture does
        // not move it, and far enough above zero that a sweep reading nothing cannot pass.
        assertTrue(compared >= 250, "the corpus should yield a few hundred comparisons, got $compared")
    }

    /** The fixture that settled the size rule: a vector is charged at its blob, not its container. */
    @Test
    fun `a deletion vector counts at its content size in the files total`() {
        val snapshot = table("v3").metadatas.last().snapshots.last { !it.expired }
        val live = liveFilesOf(snapshot)
        val vectors = live.filter { it.content == DataFileContent.POSITION_DELETES }
        assertTrue(vectors.isNotEmpty(), "the v3 fixture's current snapshot carries deletion vectors")
        vectors.forEach { vector ->
            assertTrue(vector.chargedSizeBytes < vector.sizeBytes, "blob ${vector.chargedSizeBytes} inside container ${vector.sizeBytes}")
        }
        val size = snapshotTotals(snapshot.metadata.summary, live).single { it.label == "Files size" }
        assertEquals(true, size.agrees, "recorded ${size.recorded}, counted ${size.counted}")
        assertEquals(live.sumOf { it.chargedSizeBytes }, size.counted)
    }
}
