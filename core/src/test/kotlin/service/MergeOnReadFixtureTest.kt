package service

import model.DataFileContent
import model.GraphNode
import model.ManifestContent
import model.UnifiedTableModel
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reads `example/iceberg/default/mor` — a merge-on-read table written by Spark 3.5.5 / Iceberg
 * 1.8.1 — and checks the figures against Iceberg's own metadata tables.
 *
 * Until this fixture existed nothing in the suite had ever seen a delete file. `posDeleteFileCount`,
 * `deleteRecordCount`, `deleteSizeBytes` and the `ManifestContent.DELETES` branch were decided by
 * code that no real table exercised, and delete files are the largest Iceberg issue theme upstream.
 *
 * The expected numbers below are what Iceberg reports for this table, not what this codebase
 * produces:
 *
 * ```
 * SELECT count(*) FROM mor                                   -> 5
 * SELECT content, count(*), sum(record_count),
 *        sum(file_size_in_bytes) FROM mor.files GROUP BY 1   -> 0 | 1 | 6 | 1286
 *                                                               1 | 3 | 3 | 4139
 * SELECT content, count(*) FROM mor.manifests GROUP BY 1     -> 0 | 1
 *                                                               1 | 3
 * ```
 *
 * Regenerate the fixture with `docs/fixtures/mor.sql`.
 */
class MergeOnReadFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun morModel(): UnifiedTableModel {
        val tableDir = File(repoRoot, "example/iceberg/default/mor")
        assertTrue(tableDir.isDirectory, "merge-on-read fixture missing at $tableDir")
        return UnifiedTableModel(Paths.get(tableDir.absolutePath))
    }

    @Test
    fun `the merge-on-read table decodes with no read errors`() {
        val model = morModel()
        assertEquals(emptyList(), model.readErrors, "table-level read errors on the MOR fixture")
        model.metadatas.flatMap { it.snapshots }.forEach { snapshot ->
            assertEquals(emptyList(), snapshot.readErrors, "manifest list ${snapshot.path.fileName}")
            snapshot.manifests.forEach { manifest ->
                assertEquals(emptyList(), manifest.readErrors, "manifest ${manifest.path.fileName}")
            }
        }
    }

    @Test
    fun `current stats match what Iceberg reports for the current snapshot`() {
        val current = IcebergGraphBuilder.buildTableSummary(morModel()).current

        assertEquals(1, current.dataFileCount, "data files")
        assertEquals(6L, current.recordCount, "records in data files")
        assertEquals(1286L, current.dataSizeBytes, "data bytes")

        assertEquals(3, current.posDeleteFileCount, "positional delete files")
        assertEquals(0, current.eqDeleteFileCount, "Spark writes no equality deletes")
        assertEquals(3L, current.deleteRecordCount, "records in delete files")
        assertEquals(4139L, current.deleteSizeBytes, "delete bytes")

        assertEquals(1, current.dataManifestCount, "data manifests")
        assertEquals(3, current.deleteManifestCount, "delete manifests")
    }

    /**
     * `recordCount` counts rows in data files, before deletes are applied — 6 here, against 5 live
     * rows. Subtracting `deleteRecordCount` does not recover the live count and never will: after
     * the compaction two of this table's three delete files are dangling, so they delete rows that
     * no longer exist in any live data file. 6 - 3 = 3, and the table has 5 rows.
     *
     * This is pinned because the subtraction is the obvious thing to reach for, it produces a
     * plausible number, and nothing else in the suite could show that it is wrong.
     */
    @Test
    fun `records minus delete records is not the live row count`() {
        val current = IcebergGraphBuilder.buildTableSummary(morModel()).current
        assertEquals(
            3L, current.recordCount - current.deleteRecordCount,
            "the naive subtraction should be pinned so nobody ships it as the row count",
        )
        assertTrue(
            current.recordCount - current.deleteRecordCount != 5L,
            "if this ever equals the live row count the fixture stopped covering dangling deletes",
        )
    }

    @Test
    fun `delete manifests and delete files are classified, not counted as data`() {
        val model = morModel()
        val currentSnapshotId = model.metadatas.last().metadata.currentSnapshotId
        val currentSnapshot = model.metadatas.asReversed()
            .firstNotNullOfOrNull { meta -> meta.snapshots.firstOrNull { it.metadata.snapshotId == currentSnapshotId } }
        val manifests = requireNotNull(currentSnapshot).manifests

        assertEquals(
            3, manifests.count { it.metadata.content == ManifestContent.DELETES },
            "delete manifests in the current snapshot",
        )

        val contents = manifests.flatMap { it.dataFiles }.map { it.metadata.dataFile?.content }
        assertEquals(3, contents.count { it == DataFileContent.POSITION_DELETES }, "positional delete entries")
        assertTrue(
            contents.none { it == DataFileContent.EQUALITY_DELETES },
            "Spark does not write equality deletes; a hit here means the classification is wrong",
        )
    }

    /**
     * The compaction has to survive into the fixture: it is what makes two of the delete files
     * dangling, and a `replace` snapshot is the shape a maintenance job leaves behind.
     */
    @Test
    fun `the history contains a compaction and the deletes that preceded it`() {
        val model = morModel()
        val operations = model.metadatas.last().metadata.snapshots.map { it.summary?.get("operation") }

        assertEquals(6, operations.size, "six commits were written")
        assertTrue("replace" in operations, "no compaction snapshot: got $operations")
        assertEquals(2, operations.count { it == "delete" }, "two DELETE commits")
        assertTrue("overwrite" in operations, "the merge-on-read UPDATE should commit an overwrite")
    }

    /**
     * Delete files have to reach the graph as file nodes, or the inspector cannot show them
     * however well the model counts them.
     */
    @Test
    fun `positional delete files reach the graph as delete file nodes`() {
        val graph = GraphLayoutService.layoutGraph(morModel(), showRows = false)
        val deleteNodes = graph.nodes.filterIsInstance<GraphNode.FileNode>()
            .filter { it.data.content == DataFileContent.POSITION_DELETES }

        assertTrue(deleteNodes.isNotEmpty(), "no delete file nodes in the graph")
        deleteNodes.forEach { node ->
            assertTrue(
                node.data.filePath?.endsWith("-deletes.parquet") == true,
                "a node classified as a positional delete points at ${node.data.filePath}",
            )
        }
    }
}
