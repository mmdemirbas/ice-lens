package model

import service.GraphLayoutService
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `example/paimon/db.db/de`: an append table in data-evolution mode, from `docs/fixtures/paimon-de.sql`.
 * A `MERGE INTO … UPDATE SET t.b = s.b` wrote the two updated `b` values to a file of their own —
 * `_WRITE_COLS = [b]`, the same `_FIRST_ROW_ID` 0 as the file holding `id`, `b`, `c` for those
 * rows — and the not-matched row to a whole file at first row id 2. The script's final SELECT is
 * the oracle for the stitched rows: (1, 11, 1), (2, 22, 2), (3, 33, 0), row ids 0, 1, 2.
 *
 * Two things the bytes settled. The patch file's `_VALUE_STATS` is a **one-field** row over `b`
 * with `_VALUE_STATS_COLS` null — null means "every column the file writes", not "every column of
 * the schema" — so decoding it against the three-field schema fails the arity check and it had no
 * bounds. And the snapshot's `totalRecordCount` is 5 for a table of 3 rows: Paimon sums file row
 * counts and a patch file's rows are rows it already had.
 */
class PaimonDataEvolutionFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val model = PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/de").absolutePath))
    private val merge = model.snapshots.single { it.metadata.id == 2L }
    private val mergeAdded = merge.deltaManifests.single().entries
    private val patch = mergeAdded.single { it.metadata.file?.writeCols != null }
    private val newRow = mergeAdded.single { it.metadata.file?.writeCols == null }

    @Test
    fun `the merge wrote a one-column file for the matched rows and a whole file for the new one`() {
        assertEquals(listOf("id", "b", "c"), model.schemas.single().fields.map { it.name })
        assertEquals("true", model.schemas.single().options["data-evolution.enabled"])
        assertEquals(listOf("b"), patch.metadata.file?.writeCols)
        assertEquals(2L, patch.metadata.file?.rowCount)
        assertEquals(0L, patch.metadata.file?.firstRowId, "the same first row id as the file it patches")
        assertEquals(PaimonFileSource.APPEND, patch.metadata.file?.fileSource)
        assertNull(patch.metadata.file?.valueStatsCols, "null means every column the file writes")
        assertEquals(1L, newRow.metadata.file?.rowCount)
        assertEquals(2L, newRow.metadata.file?.firstRowId)
        assertEquals(3L, merge.metadata.nextRowId, "the patch took no ids; the new row took one")
        assertEquals(5L, merge.metadata.totalRecordCount, "file rows summed — the two patched rows counted twice")
        val live = paimonLiveFilesOf(merge)
        assertEquals(3, live.size)
        assertEquals(2L, live.partialRows(), "the rows a scan does not return twice")
        assertEquals(3L, live.sumOf { it.recordCount } - live.partialRows(), "what the script's SELECT printed")
        // The table's own figure is folded from the same replay and says the same two things.
        val current = service.PaimonGraphBuilder.buildTableSummary(model).current
        assertEquals(5L, current.recordCount)
        assertEquals(2L, current.partialRecordCount)
        assertEquals(3L, current.readRecordCount)
        assertTrue(model.readErrors.isEmpty() && merge.deltaManifests.single().readErrors.isEmpty())
    }

    @Test
    fun `a patch file's bounds are decoded against the columns it writes, not the schema`() {
        val bounds = assertNotNull(patch.columnBounds, "a one-field stats row under a three-field schema")
        assertEquals(listOf("b"), bounds.map { it.name })
        assertEquals(listOf(11 to 22), bounds.map { it.min to it.max })
        assertEquals(listOf(0L), bounds.map { it.nullCount })
        val whole = assertNotNull(newRow.columnBounds)
        assertEquals(listOf("id" to (3 to 3), "b" to (33 to 33), "c" to (0 to 0)), whole.map { it.name to (it.min to it.max) })
    }

    @Test
    fun `the graph pairs the patch with the file it patches, and nothing else`() {
        val graph = GraphLayoutService.layoutGraph(model, showRows = true)
        val patchEdges = graph.edges.filter { it.id.startsWith("e_patch_") }
        assertEquals(1, patchEdges.size)
        val edge = patchEdges.single()
        assertTrue(!edge.affectsLayout, "an annotation between two files of one layer")
        val from = graph.nodeById.getValue(edge.fromId) as GraphNode.PaimonDataFileNode
        val to = graph.nodeById.getValue(edge.toId) as GraphNode.PaimonDataFileNode
        assertEquals(listOf("b"), from.entry.file?.writeCols)
        assertNull(to.entry.file?.writeCols)
        assertEquals(0L, to.entry.file?.firstRowId)
        assertEquals(2L, to.entry.file?.rowCount)
        // The patch file's rows are the two b values with ids 0 and 1 — what the stitched read
        // pairs with id 1 and 2's other columns.
        val patchRows = graph.nodes.filterIsInstance<GraphNode.RowNode>()
            .filter { it.id.startsWith("row_${from.id}_") && "b" in it.resolvedData }
            .sortedBy { it.filePosition }
        assertEquals(listOf(11, 22), patchRows.map { (it.resolvedData["b"] as Number).toInt() })
        assertEquals(listOf(0L, 1L), patchRows.map { (it.resolvedData["_ROW_ID"] as Number).toLong() })
        assertEquals(setOf("b", "_ROW_ID"), patchRows.first().resolvedData.keys.filter { it == "b" || it == "_ROW_ID" || it in listOf("id", "c") }.toSet(), "no id or c cell — the file holds none")
    }
}
