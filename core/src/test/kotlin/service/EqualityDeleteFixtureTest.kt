package service

import model.DataFileContent
import model.UnifiedTableModel
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Reads `example/iceberg/default/eqdel` — the only fixture carrying **both** delete kinds — and
 * checks it against Iceberg's own metadata tables.
 *
 * `eqDeleteFileCount` had never been anything but zero in this suite. Spark writes no equality
 * deletes, so the v2 and v3 merge-on-read fixtures cannot reach that branch however many delete
 * files they carry. This table's equality delete is written by Iceberg's own
 * `EqualityDeleteWriter`, driven from spark-shell because no SQL exists for it, and committed
 * through `newRowDelta()` — so the bytes on disk are Iceberg's, not this repo's.
 *
 * Having both kinds in one table is the point rather than a convenience: a classification that
 * merged the two counters, or swapped them, passes every test where only one kind is present.
 *
 * Expected values are Iceberg's:
 *
 * ```
 * SELECT count(*) FROM eqdel                                  -> 4   (ids 1, 4, 5, 7)
 * SELECT content, count(*), sum(record_count),
 *        sum(file_size_in_bytes) FROM eqdel.files GROUP BY 1  -> 0 | 2 | 7 | 1885
 *                                                                1 | 1 | 1 | 1388
 *                                                                2 | 1 | 2 |  467
 * SELECT content, count(*) FROM eqdel.manifests GROUP BY 1    -> 0 | 2
 *                                                                1 | 2
 * ```
 *
 * Regenerate with `docs/fixtures/eqdel.sql` and
 * `docs/fixtures/eqdel-equality-deletes.scala` — in that order.
 */
class EqualityDeleteFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun eqdelModel(): UnifiedTableModel {
        val tableDir = File(repoRoot, "example/iceberg/default/eqdel")
        assertTrue(tableDir.isDirectory, "equality-delete fixture missing at $tableDir")
        return UnifiedTableModel(Paths.get(tableDir.absolutePath))
    }

    @Test
    fun `the table with both delete kinds decodes with no read errors`() {
        val model = eqdelModel()
        assertEquals(emptyList(), model.readErrors, "table-level read errors")
        model.metadatas.flatMap { it.snapshots }.forEach { snapshot ->
            assertEquals(emptyList(), snapshot.readErrors, "manifest list ${snapshot.path.fileName}")
            snapshot.manifests.forEach { manifest ->
                assertEquals(emptyList(), manifest.readErrors, "manifest ${manifest.path.fileName}")
            }
        }
    }

    @Test
    fun `positional and equality deletes are counted separately`() {
        val current = IcebergGraphBuilder.buildTableSummary(eqdelModel()).current

        assertEquals(2, current.dataFileCount, "data files")
        assertEquals(7L, current.recordCount, "records in data files")
        assertEquals(1885L, current.dataSizeBytes, "data bytes")

        assertEquals(1, current.posDeleteFileCount, "positional delete files")
        assertEquals(1, current.eqDeleteFileCount, "equality delete files")
        assertEquals(2, current.deleteFileCount, "both kinds together")
        assertEquals(3L, current.deleteRecordCount, "1 positional row plus 2 equality rows")
        assertEquals(1855L, current.deleteSizeBytes, "1388 positional bytes plus 467 equality")

        assertEquals(2, current.dataManifestCount, "data manifests")
        assertEquals(2, current.deleteManifestCount, "delete manifests")
    }

    /**
     * An equality delete names the fields it matches on and nothing else. There is no reference
     * to a data file anywhere in the entry, because the delete applies by predicate across every
     * file in scope — this one removes a row from each of the two data files.
     *
     * That is why an inspector has to say so rather than draw an edge: the link a reader expects
     * to see does not exist in the format. This test pins the two halves of that statement —
     * `equalityIds` is populated, and it is the only linkage there is.
     */
    @Test
    fun `an equality delete carries its field ids and no reference to any data file`() {
        val model = eqdelModel()
        val currentSnapshotId = model.metadatas.last().metadata.currentSnapshotId
        val currentSnapshot = model.metadatas.asReversed()
            .firstNotNullOfOrNull { meta -> meta.snapshots.firstOrNull { it.metadata.snapshotId == currentSnapshotId } }

        val entries = requireNotNull(currentSnapshot).manifests.flatMap { it.dataFiles }
        val equality = entries.filter { it.metadata.dataFile?.content == DataFileContent.EQUALITY_DELETES }
        val positional = entries.filter { it.metadata.dataFile?.content == DataFileContent.POSITION_DELETES }

        assertEquals(1, equality.size, "equality delete entries")
        assertEquals(1, positional.size, "positional delete entries")

        val equalityFile = assertNotNull(equality.single().metadata.dataFile)
        assertEquals(
            listOf(1), equalityFile.equalityIds,
            "the equality delete should name field id 1 (id), which is its only linkage",
        )

        val positionalFile = assertNotNull(positional.single().metadata.dataFile)
        assertTrue(
            positionalFile.equalityIds.isNullOrEmpty(),
            "a positional delete has no equality field ids",
        )
    }
}
