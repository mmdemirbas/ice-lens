package service

import model.DataFileContent
import model.UnifiedTableModel
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reads `example/iceberg/default/v3` — a format-version 3 table written by Spark 3.5.5 /
 * Iceberg 1.8.1 — and pins what the reader currently does with it.
 *
 * v3 is unmodelled (see TODO.md): deletion vectors, row lineage and the new types are parsed
 * only in the sense that unknown Avro fields are dropped and unknown JSON keys ignored. That is
 * a deliberate design choice, and a deliberate choice needs a test rather than a hope — without
 * one, "a v3 table opens without error" is an assumption about a code path nothing exercises.
 *
 * Under merge-on-read this table's deletes are **deletion vectors**: Puffin blobs named
 * `-deletes.puffin`, referenced from the manifest entry by `referenced_data_file`,
 * `content_offset` and `content_size_in_bytes`. None of those three fields is surfaced yet. The
 * v2 fixture (`example/iceberg/default/mor`) carries `-deletes.parquet` instead, which is how
 * the two representations were confirmed to differ — by running both, not by reading the spec.
 *
 * Expected values are Iceberg's, from its own metadata tables:
 *
 * ```
 * SELECT count(*) FROM v3                                    -> 4
 * SELECT content, count(*), sum(record_count),
 *        sum(file_size_in_bytes) FROM v3.files GROUP BY 1    -> 0 | 5 | 6 | 4706
 *                                                               1 | 2 | 2 | 868
 * SELECT content, count(*) FROM v3.manifests GROUP BY 1      -> 0 | 3
 *                                                               1 | 2
 * ```
 *
 * Regenerate with `docs/fixtures/v3.sql`.
 */
class FormatV3FixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun v3Model(): UnifiedTableModel {
        val tableDir = File(repoRoot, "example/iceberg/default/v3")
        assertTrue(tableDir.isDirectory, "v3 fixture missing at $tableDir")
        return UnifiedTableModel(Paths.get(tableDir.absolutePath))
    }

    @Test
    fun `a v3 table decodes with no read errors`() {
        val model = v3Model()
        assertEquals(emptyList(), model.readErrors, "table-level read errors on the v3 fixture")
        model.metadatas.flatMap { it.snapshots }.forEach { snapshot ->
            assertEquals(emptyList(), snapshot.readErrors, "manifest list ${snapshot.path.fileName}")
            snapshot.manifests.forEach { manifest ->
                assertEquals(emptyList(), manifest.readErrors, "manifest ${manifest.path.fileName}")
            }
        }
    }

    @Test
    fun `the format version is read as 3`() {
        assertEquals(3, v3Model().metadatas.last().metadata.formatVersion)
    }

    @Test
    fun `v3 stats match what Iceberg reports for the current snapshot`() {
        val current = IcebergGraphBuilder.buildTableSummary(v3Model()).current

        assertEquals(5, current.dataFileCount, "data files")
        assertEquals(6L, current.recordCount, "records in data files")
        assertEquals(4706L, current.dataSizeBytes, "data bytes")

        assertEquals(2, current.posDeleteFileCount, "deletion vectors count as positional deletes")
        assertEquals(0, current.eqDeleteFileCount, "Spark writes no equality deletes")
        assertEquals(2L, current.deleteRecordCount, "rows the vectors delete")
        assertEquals(868L, current.deleteSizeBytes, "vector bytes")

        assertEquals(3, current.dataManifestCount, "data manifests")
        assertEquals(2, current.deleteManifestCount, "delete manifests")
    }

    /**
     * The representation itself, pinned. A v3 deletion vector is a Puffin blob while the v2
     * equivalent is a parquet file; the entry still declares `content = 1`, so nothing but the
     * path says which one is on disk until `referenced_data_file` is modelled.
     *
     * When deletion vectors are implemented this test is the place that says what changed.
     */
    @Test
    fun `deletion vectors arrive as puffin paths declaring positional delete content`() {
        val model = v3Model()
        val currentSnapshotId = model.metadatas.last().metadata.currentSnapshotId
        val currentSnapshot = model.metadatas.asReversed()
            .firstNotNullOfOrNull { meta -> meta.snapshots.firstOrNull { it.metadata.snapshotId == currentSnapshotId } }

        val deleteEntries = requireNotNull(currentSnapshot).manifests
            .flatMap { it.dataFiles }
            .filter { it.metadata.dataFile?.content == DataFileContent.POSITION_DELETES }

        assertEquals(2, deleteEntries.size, "deletion vector entries")
        deleteEntries.forEach { entry ->
            assertTrue(
                entry.metadata.dataFile?.filePath?.endsWith("-deletes.puffin") == true,
                "a v3 deletion vector should be a Puffin blob, got ${entry.metadata.dataFile?.filePath}",
            )
        }
    }

    /**
     * The one place the format records a delete-to-data link.
     *
     * A v2 positional delete keeps its targets inside its own `file_path` column and an equality
     * delete has none at all, so this is the only delete file that can name what it applies to
     * from metadata alone. A Puffin blob can hold several vectors, which is why the byte range
     * travels with the path.
     */
    @Test
    fun `a deletion vector names the single data file it applies to`() {
        val model = v3Model()
        val currentSnapshotId = model.metadatas.last().metadata.currentSnapshotId
        val currentSnapshot = model.metadatas.asReversed()
            .firstNotNullOfOrNull { meta -> meta.snapshots.firstOrNull { it.metadata.snapshotId == currentSnapshotId } }

        val vectors = requireNotNull(currentSnapshot).manifests
            .flatMap { it.dataFiles }
            .mapNotNull { it.metadata.dataFile }
            .filter { it.content == DataFileContent.POSITION_DELETES }

        val dataFilePaths = requireNotNull(currentSnapshot).manifests
            .flatMap { it.dataFiles }
            .mapNotNull { it.metadata.dataFile }
            .filter { it.content == DataFileContent.DATA }
            .mapNotNull { it.filePath }
            .toSet()

        assertEquals(2, vectors.size)
        vectors.forEach { vector ->
            val referenced = assertNotNull(
                vector.referencedDataFile,
                "a v3 deletion vector must name its data file, got null on ${vector.filePath}",
            )
            assertTrue(
                referenced in dataFilePaths,
                "the referenced file should be one of this snapshot's data files: $referenced",
            )
            assertNotNull(vector.contentOffset, "a vector inside a Puffin blob needs its offset")
            assertTrue(
                (vector.contentSizeInBytes ?: 0L) > 0L,
                "a vector needs a non-empty byte range",
            )
        }
    }

    /** The v2 table must not grow these fields — they would be a decode artefact, not data. */
    @Test
    fun `a v2 positional delete carries no referenced data file`() {
        val morDir = File(repoRoot, "example/iceberg/default/mor")
        val model = UnifiedTableModel(Paths.get(morDir.absolutePath))
        val deletes = model.metadatas.flatMap { it.snapshots }.flatMap { it.manifests }
            .flatMap { it.dataFiles }
            .mapNotNull { it.metadata.dataFile }
            .filter { it.content == DataFileContent.POSITION_DELETES }

        assertTrue(deletes.isNotEmpty(), "the v2 fixture should have positional deletes")
        deletes.forEach { delete ->
            assertNull(delete.referencedDataFile, "v2 has no referenced_data_file field")
            assertNull(delete.contentOffset)
        }
    }
}
