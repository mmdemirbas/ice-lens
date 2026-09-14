package service

import model.FixtureCatalog
import model.GraphNode
import model.RowFate
import model.asLookupDataFile
import model.asLookupDeleteFile
import model.currentSchemaModel
import model.nameMapping
import model.newestIcebergMetadata
import model.deleteCandidatesFor
import model.DeleteReachVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A sampled row's fate, decided from the drawn graph the way the row panel asks it: the row's
 * parent file, the delete files the pairing leaves for it, the row's own position and cells.
 * `mor`'s id 7 is position-deleted after the compaction and `eqdel`'s ids 2 and 6 are
 * equality-deleted — the same answers the filter lookup gives, reached without a filter.
 */
class RowFateFixtureTest {

    /** The data rows — a delete file's own rows carry an `id` too, and are not rows a read returns. */
    private fun rows(fixture: String): Pair<model.GraphModel, List<GraphNode.RowNode>> {
        val graph = GraphLayoutService.layoutGraph(FixtureCatalog.icebergModel(fixture), showRows = true)
        return graph to graph.nodes.filterIsInstance<GraphNode.RowNode>().filter { it.content == 0 }
    }

    /** The rows holding [id], by the file they are drawn under: a compacted-away file still draws its rows, and no delete reaches it. */
    private fun rowsWithId(rows: List<GraphNode.RowNode>, id: Int) = rows.filter { it.resolvedData["id"]?.toString()?.toIntOrNull() == id }

    private fun fate(graph: model.GraphModel, row: GraphNode.RowNode): model.RowHit {
        val parent = assertNotNull(graph.edges.firstOrNull { it.toId == row.id }?.let { graph.nodeById[it.fromId] as? GraphNode.FileNode })
        val candidates = deleteCandidatesFor(parent, graph.nodes.filterIsInstance<GraphNode.FileNode>())
            .filter { it.verdict == DeleteReachVerdict.REACHES || it.verdict == DeleteReachVerdict.MAY_REACH }
        val metadata = graph.newestIcebergMetadata()
        return RowLookup.fateOf(
            assertNotNull(parent.asLookupDataFile()), row.filePosition, row.cellsForRead, candidates.mapNotNull { it.delete.asLookupDeleteFile() },
            metadata?.currentSchemaModel(), metadata?.nameMapping(),
        )
    }

    /**
     * `eqren`: the equality delete holds `name`, renamed to `label` after it was written. The
     * delete's column resolves to `label` through the table's fields, the row's cells come
     * under the schema's names from the projection, and the delete file is read projected —
     * without that the file is asked for a `label` it does not have and the row is not decided.
     * Id 8's `label` is `bravo` too, written after the delete, which does not reach its file.
     */
    @Test
    fun `an equality delete written before a rename still decides the row by the renamed column`() {
        val (graph, rows) = rows("eqren")
        val delete = graph.nodes.filterIsInstance<GraphNode.FileNode>().mapNotNull { it.asLookupDeleteFile() }.single { it.kind == model.DeleteFileKind.EQUALITY }
        assertEquals(listOf("label"), delete.equalityColumns)
        for (id in listOf(2, 6)) {
            val hit = rowsWithId(rows, id).map { fate(graph, it) }.single()
            assertEquals(RowFate.EQUALITY_DELETED, hit.fate, "id $id: $hit")
            assertEquals(delete.recordedPath, hit.by)
        }
        assertEquals(listOf(RowFate.POSITION_DELETED), rowsWithId(rows, 3).map { fate(graph, it).fate })
        for (id in listOf(1, 4, 5, 7, 8)) assertEquals(RowFate.LIVE, rowsWithId(rows, id).map { fate(graph, it) }.single().fate, "id $id")
    }

    @Test
    fun `a row a positional delete names is deleted, and its neighbours are live`() {
        val (graph, rows) = rows("mor")
        // id 7 is drawn twice: under the file the compaction removed, which the delete written
        // after it does not reach, and under the compacted file, which it names.
        val sevens = rowsWithId(rows, 7).map { fate(graph, it) }
        assertEquals(setOf(RowFate.LIVE, RowFate.POSITION_DELETED), sevens.map { it.fate }.toSet(), sevens.toString())
        assertTrue(assertNotNull(sevens.first { it.fate == RowFate.POSITION_DELETED }.by).endsWith("-deletes.parquet"))
        assertTrue(rowsWithId(rows, 1).map { fate(graph, it).fate }.all { it == RowFate.LIVE })
    }

    @Test
    fun `a row an equality delete matches by value is deleted, with the delete's columns named through the table's fields`() {
        val (graph, rows) = rows("eqdel")
        assertEquals(listOf(RowFate.EQUALITY_DELETED), rowsWithId(rows, 2).map { fate(graph, it).fate })
        val delete = graph.nodes.filterIsInstance<GraphNode.FileNode>().mapNotNull { it.asLookupDeleteFile() }.single { it.kind == model.DeleteFileKind.EQUALITY }
        assertEquals(listOf("id"), delete.equalityColumns)
        assertEquals(listOf(RowFate.POSITION_DELETED), rowsWithId(rows, 3).map { fate(graph, it).fate })
        assertEquals(listOf(RowFate.LIVE), rowsWithId(rows, 4).map { fate(graph, it).fate })
    }
}
