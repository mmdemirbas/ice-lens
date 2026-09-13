package service

import model.DataFileContent
import model.GraphNode
import model.ManifestContent
import model.ManifestEntryStatus
import model.UnifiedTableModel
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `example/iceberg/default/lineage`: a format-version 3 table written by Iceberg 1.10, from
 * `docs/fixtures/lineage.sql` — the first fixture here from a runtime newer than the image's.
 *
 * Row lineage is read by inheritance at three levels, and every level is exercised: a snapshot's
 * `first-row-id` is `next-row-id` as it stood; a manifest's `first_row_id` is assigned in manifest
 * list order from that, advancing by the rows of every manifest assigned one — existing rows
 * included, which is how ids 7 and 8 were burned by the update's rewritten manifest; a data
 * file's is the manifest's plus the rows of the files before it that also recorded none; and a
 * row's `_row_id` is the file's plus its position unless the file wrote the column. The expected
 * values are the script's, and the rows are what the writer printed for itself at the end of it.
 */
class RowLineageFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val tableDir: Path = Paths.get(File(repoRoot, "example/iceberg/default/lineage").absolutePath)
    private val model = UnifiedTableModel(tableDir)
    private val snapshots = model.metadatas.last().snapshots.sortedBy { it.metadata.sequenceNumber }

    /** The manifests a snapshot's own commit wrote, in manifest-list order. */
    private fun own(index: Int) = snapshots[index].manifests.filter { it.metadata.addedSnapshotId == snapshots[index].metadata.snapshotId }

    @Test
    fun `next-row-id advances by every manifest assigned a first id, existing rows included`() {
        assertEquals(3, model.metadatas.last().metadata.formatVersion)
        assertEquals(listOf(0L, 3L, 6L, 9L, 9L, 9L), model.metadatas.map { it.metadata.nextRowId })
        assertEquals(listOf(0L, 3L, 6L, 9L), snapshots.map { it.metadata.firstRowId })
        assertEquals(listOf("append", "append", "overwrite", "delete"), snapshots.map { it.metadata.summary["operation"] })
        assertTrue(model.readErrors.isEmpty(), "${model.readErrors}")
    }

    @Test
    fun `a manifest's first id is assigned in list order, and a delete manifest gets none`() {
        assertEquals(listOf(0L), own(0).map { it.metadata.firstRowId })
        assertEquals(listOf(3L), own(1).map { it.metadata.firstRowId })
        // The update wrote two: the added file's manifest, then snapshot 1's manifest rewritten
        // to mark the old file DELETED. The second is assigned 7 = 6 + the first's one added row,
        // and its own two existing rows move next-row-id to 9 although no row takes 7 or 8.
        val update = own(2)
        assertEquals(listOf(6L, 7L), update.map { it.metadata.firstRowId })
        assertEquals(listOf(1L to 0L, 0L to 2L), update.map { it.metadata.addedRowsCount to it.metadata.existingRowsCount })
        val delete = own(3).single()
        assertEquals(ManifestContent.DELETES, delete.metadata.content)
        assertNull(delete.metadata.firstRowId)
        assertEquals("1", snapshots[3].metadata.summary["added-dvs"])
    }

    @Test
    fun `a data file inherits the manifest's first id plus the rows of the files before it`() {
        val first = own(0).single().dataFiles
        assertEquals(listOf(null, null), first.map { it.metadata.dataFile?.firstRowId }, "an added entry records none")
        assertEquals(listOf(2L, 1L), first.map { it.metadata.dataFile?.recordCount })
        assertEquals(listOf(0L, 2L), first.map { it.firstRowId }, "0, then 0 + the two rows before it")
        assertTrue(first.all { it.firstRowIdInherited })
        val second = own(1).single().dataFiles
        assertEquals(listOf(3L, 5L), second.map { it.firstRowId })
        assertEquals(listOf(2L, 1L), second.map { it.metadata.dataFile?.recordCount })
    }

    @Test
    fun `an entry carried into a rewritten manifest records the id it inherited`() {
        val rewritten = own(2)[1]
        val byStatus = rewritten.dataFiles.associateBy { it.metadata.status }
        assertEquals(setOf(ManifestEntryStatus.EXISTING, ManifestEntryStatus.DELETED), byStatus.keys)
        assertEquals(0L, byStatus[ManifestEntryStatus.EXISTING]?.metadata?.dataFile?.firstRowId, "written in, not null")
        assertEquals(2L, byStatus[ManifestEntryStatus.DELETED]?.metadata?.dataFile?.firstRowId)
        assertTrue(byStatus.values.none { it.firstRowIdInherited })
        assertEquals(listOf(0L, 2L), byStatus.values.map { it.firstRowId })
        // The added file inherits 6 from its manifest even though its one row carries its own id.
        val added = own(2)[0].dataFiles.single()
        assertEquals(6L, added.firstRowId)
        assertTrue(added.firstRowIdInherited)
        // A deletion vector is assigned nothing.
        val vector = own(3).single().dataFiles.single()
        assertEquals(DataFileContent.POSITION_DELETES, vector.metadata.dataFile?.content)
        assertNull(vector.firstRowId)
    }

    /**
     * The writer's own answer, printed by the script's last statement:
     * `1 alpha 0 1 / 2 BRAVO 2 3 / 3 charlie 1 1 / 5 echo 5 2 / 6 foxtrot 4 2` (id, name, row id,
     * last updated). Row 4 is under the deletion vector and not among them.
     */
    @Test
    fun `a sampled row's id and last-updated sequence number are the writer's`() {
        val graph = GraphLayoutService.layoutGraph(model, showRows = true)
        val fileNodes = graph.nodes.filterIsInstance<GraphNode.FileNode>()
        val current = snapshots.last()
        val live = current.manifests.flatMap { it.dataFiles }
            .filter { it.metadata.status != ManifestEntryStatus.DELETED && it.metadata.dataFile?.content == DataFileContent.DATA }
            .map { it.metadata.dataFile?.filePath }.toSet()
        val rowsByFile = fileNodes
            .filter { it.data.filePath in live && it.entry.status != ManifestEntryStatus.DELETED }
            .distinctBy { it.data.filePath }
            .flatMap { file ->
                graph.nodes.filterIsInstance<GraphNode.RowNode>().filter { it.id.startsWith("row_${file.id}_") && "id" in it.resolvedData }.map { row ->
                    val cells = row.resolvedData
                    Triple(
                        (cells["id"] as Number).toInt(),
                        (cells[IcebergGraphBuilder.ROW_ID_COLUMN] as Number).toLong(),
                        (cells[IcebergGraphBuilder.LAST_UPDATED_SEQUENCE_NUMBER_COLUMN] as Number).toLong(),
                    )
                }
            }
            .sortedBy { it.first }
        assertEquals(
            listOf(Triple(1, 0L, 1L), Triple(2, 2L, 3L), Triple(3, 1L, 1L), Triple(4, 3L, 2L), Triple(5, 5L, 2L), Triple(6, 4L, 2L)),
            rowsByFile,
            "row 4 is present with its id — the vector marks it, it does not remove the row from the file",
        )
        // The rewritten file wrote `_row_id` 2 and a null `_last_updated_sequence_number`, so the
        // id is the column's and the sequence number is the file's (3), not 6 + position.
        val rewritten = fileNodes.single { it.firstRowId == 6L && it.entry.status == ManifestEntryStatus.ADDED }
        val rewrittenRow = graph.nodes.filterIsInstance<GraphNode.RowNode>().single { it.id.startsWith("row_${rewritten.id}_") && "id" in it.resolvedData }
        assertEquals(2L, (rewrittenRow.resolvedData[IcebergGraphBuilder.ROW_ID_COLUMN] as Number).toLong())
        assertEquals(3L, (rewrittenRow.resolvedData[IcebergGraphBuilder.LAST_UPDATED_SEQUENCE_NUMBER_COLUMN] as Number).toLong())
        assertEquals(3L, rewritten.sequenceNumber)
        assertNotNull(rewrittenRow.filePosition)
    }
}
