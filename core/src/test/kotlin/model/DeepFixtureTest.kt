package model

import service.GraphLayoutService
import service.RowLookup
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

    /**
     * The lookup reads a nested leaf as struct access — `"addr"."town"` — under the current
     * names, and the old file answers it too: its struct is rebuilt by field id
     * (`FileProjection`'s `struct_pack`), so `city` is read as `town` and the `country` the
     * file predates is null. The projected cell is what a read returns for the row.
     */
    @Test
    fun `the lookup reads a nested leaf by path through the old file's struct rebuilt by id`() {
        val input = assertNotNull(model.rowLookupInput())
        val zips = RowLookup.lookup(input, ScanFilter.Term(ScanPredicate("addr.zip", PredicateOp.LT, "7000")), emptySet())
        assertTrue(zips.filesRead.all { it.error == null }, zips.filesRead.toString())
        assertEquals(mapOf("1" to RowFate.LIVE, "3" to RowFate.LIVE), zips.hits.associate { it.cells["id"].toString() to it.fate })
        val izmir = RowLookup.lookup(input, ScanFilter.Term(ScanPredicate("addr.town", PredicateOp.EQ, "Izmir")), emptySet())
        assertTrue(izmir.filesRead.all { it.error == null }, izmir.filesRead.toString())
        assertEquals(listOf("4"), izmir.hits.map { it.cells["id"].toString() })
        // `Ankara` sits in the file written before the rename, under `city`.
        val ankara = RowLookup.lookup(input, ScanFilter.Term(ScanPredicate("addr.town", PredicateOp.EQ, "Ankara")), emptySet())
        assertTrue(ankara.filesRead.all { it.error == null }, ankara.filesRead.toString())
        assertEquals(listOf("1", "3"), ankara.hits.map { it.cells["id"].toString() }.sorted())
        val addr = ankara.hits.first { it.cells["id"].toString() == "1" }.cells["addr"].toString()
        assertTrue("town" in addr && "Ankara" in addr && "country" in addr && "city" !in addr, addr)
        // A struct field added after the file: null in the old file, `TR` in the new one.
        val noCountry = RowLookup.lookup(input, ScanFilter.Term(ScanPredicate("addr.country", PredicateOp.IS_NULL, "")), emptySet())
        assertEquals(listOf("1", "2", "3"), noCountry.hits.map { it.cells["id"].toString() }.sorted())
        val tr = RowLookup.lookup(input, ScanFilter.Term(ScanPredicate("addr.country", PredicateOp.EQ, "TR")), emptySet())
        assertEquals(listOf("4"), tr.hits.map { it.cells["id"].toString() })
    }

    @Test
    fun `a file's column tree has the schema's shape, the list and map wrappers folded away`() {
        val file = model.metadatas.last().snapshots.first().manifests.flatMap { it.dataFiles }.first()
        val tree = service.SampleRowReader.fileColumnTreeOf(file.path.toString())
        assertEquals(listOf("id" to 1, "name" to 2, "addr" to 3, "tags" to 4, "props" to 5), tree.map { it.name to it.fieldId })
        val addr = tree.first { it.name == "addr" }
        assertEquals(FileColumn.Kind.STRUCT, addr.kind)
        assertEquals(listOf("city" to 6, "zip" to 7), addr.children.map { it.name to it.fieldId })
        val tags = tree.first { it.name == "tags" }
        assertEquals(FileColumn.Kind.LIST, tags.kind)
        assertEquals(listOf("element" to 8), tags.children.map { it.name to it.fieldId })
        val props = tree.first { it.name == "props" }
        assertEquals(FileColumn.Kind.MAP, props.kind)
        assertEquals(listOf("key" to 9, "value" to 10), props.children.map { it.name to it.fieldId })
    }
}
