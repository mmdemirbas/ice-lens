package model

import service.GraphLayoutService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `deep`: a struct, a list and a map, with `addr.city` renamed to `addr.town` and
 * `addr.country` added after the first file. What Iceberg records for a nested column is per
 * leaf field id — a struct's children, a list's element, a map's key and value — and a column is
 * named by its path, which is the name a filter is written in and the only one that says which
 * struct a `zip` is in.
 */
class DeepFixtureTest {

    private val model = FixtureCatalog.icebergModel("deep")
    private val graph by lazy { GraphLayoutService.layoutGraph(model, showRows = false) }
    private val schema = assertNotNull(model.metadatas.last().metadata.currentSchemaModel())

    private fun fileNamed(prefix: String) = graph.nodes.filterIsInstance<GraphNode.FileNode>().single { it.data.filePath.orEmpty().substringAfterLast('/').startsWith(prefix) }

    @Test
    fun `every leaf of a struct, a list and a map has an id and a path`() {
        assertEquals(
            mapOf(1 to "id", 2 to "name", 3 to "addr", 6 to "addr.town", 7 to "addr.zip", 11 to "addr.country", 4 to "tags", 8 to "tags.element", 5 to "props", 9 to "props.key", 10 to "props.value"),
            schema.pathsById,
        )
        assertEquals(6, schema.idOfPath("addr.town"))
        assertEquals(6, schema.idOfPath("ADDR.Town"))
        assertEquals(null, schema.idOfPath("addr.city"), "the old name binds to nothing")
        assertEquals(IcebergType.StringType, schema.fieldsById.getValue(8).type)
        assertEquals("element", schema.nameOf(8))
    }

    @Test
    fun `a file's statistics are named by the path its manifest's schema gives the leaf, the list's element and the map's key and value included`() {
        val old = fileNamed("00000-0-").columnStats.associateBy { it.fieldId }
        assertEquals(listOf(1, 2, 6, 7, 8, 9, 10), old.keys.sorted())
        assertEquals("addr.city", old.getValue(6).columnName, "the manifest's own schema, written before the rename")
        assertEquals("Ankara", old.getValue(6).lowerBound?.display)
        assertEquals("Istanbul", old.getValue(6).upperBound?.display)
        assertEquals("addr.zip", old.getValue(7).columnName)
        assertEquals("tags.element", old.getValue(8).columnName)
        assertEquals("props.key", old.getValue(9).columnName)
        assertEquals("props.value", old.getValue(10).columnName)
        // ['a', 'b'], ['c'], [] — three elements, and the empty list counted as one null element;
        // the map's {x: 1}, {y: 2, z: 3}, {} likewise. No bounds for either.
        assertEquals(4L to 1L, old.getValue(8).valueCount to old.getValue(8).nullValueCount)
        assertEquals(4L to 1L, old.getValue(9).valueCount to old.getValue(9).nullValueCount)
        assertEquals(null, old.getValue(8).lowerBound)
        val new = fileNamed("00000-1-").columnStats.associateBy { it.fieldId }
        assertEquals("addr.town", new.getValue(6).columnName)
        assertEquals("addr.country", new.getValue(11).columnName)
        assertEquals("TR", new.getValue(11).lowerBound?.display)
        assertEquals(null, old[11], "the old file records nothing for a column that did not exist")
    }

    @Test
    fun `the prunable columns are the current schema's leaves by path, each once, whatever the manifests call them`() {
        val columns = prunableColumns(graph)
        assertEquals(listOf("id", "name", "addr.town", "addr.zip", "addr.country", "tags.element", "props.key", "props.value"), columns.map { it.name })
        assertTrue(columns.all { it.hasFileBounds }, columns.toString())
        assertEquals(IcebergType.IntType, columns.first { it.name == "addr.zip" }.type)
    }

    @Test
    fun `a filter on a renamed leaf binds by id, so the old file's bound under the old name still prunes it`() {
        val plan = evaluateScan(graph, ScanFilter.Term(ScanPredicate("addr.town", PredicateOp.EQ, "Izmir")))
        assertEquals(FileFate.SKIPPED, plan.files.getValue(fileNamed("00000-0-").id).fate)
        assertEquals(FileFate.WOULD_BE_READ, plan.files.getValue(fileNamed("00000-1-").id).fate)
        // The positional delete is applied to the file it names, never opened or skipped as a data file.
        assertEquals(setOf(fileNamed("00000-0-").id, fileNamed("00000-1-").id), plan.files.keys)
        assertEquals(1, graph.nodes.filterIsInstance<GraphNode.FileNode>().count { !it.isScanDataFile })
        val unknown = evaluateScan(graph, ScanFilter.Term(ScanPredicate("addr.city", PredicateOp.EQ, "Ankara")))
        assertEquals(FileFate.UNEVALUATED, unknown.files.getValue(fileNamed("00000-0-").id).fate, "the old name is not a column of the table")
        assertTrue(unknown.files.getValue(fileNamed("00000-0-").id).outcomes.single().reason.contains("no column called 'addr.city'"))
    }
}
