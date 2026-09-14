package model

import service.GraphLayoutService
import service.RowLookup
import service.SampleRowReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `migdeep`: `migrated` with nested columns — a plain-Spark file holding a struct, a list of
 * structs and a map, registered by `add_files`, then `RENAME COLUMN addr.city TO town`,
 * `ADD COLUMN addr.country`, `RENAME COLUMN items.element.sku TO code`, and one Iceberg-written
 * row. The file records no field id at any level, so every nested field is placed through the
 * mapping's tree, which the three ALTERs kept current (`docs/fixtures/migdeep.sql`). The
 * script's own reads are the oracle: row 1 under `town`/`code` with `country` null, and
 * `addr.town = 'Ankara'` finding it.
 */
class MigratedNestedFixtureTest {

    private val model = FixtureCatalog.icebergModel("migdeep")
    private val mapping = assertNotNull(model.metadatas.last().metadata.nameMapping())
    private val plainPath = model.metadatas.last().snapshots.flatMap { it.manifests }.flatMap { it.dataFiles }
        .first { it.metadata.dataFile?.filePath.orEmpty().contains("deep-files") }.path.toString()

    /** The plain file's tree records no id anywhere; the mapping fills every one, a list's element and a map's entries by role. */
    @Test
    fun `the mapping's tree fills the ids a plain file records nowhere, down to a list's element and a map's key and value`() {
        val tree = SampleRowReader.fileColumnTreeOf(plainPath)
        fun ids(columns: List<FileColumn>): List<Pair<String, Int?>> = columns.flatMap { listOf(it.name to it.fieldId) + ids(it.children) }
        assertTrue(ids(tree).all { it.second == null }, "plain Spark writes no field ids: ${ids(tree)}")

        val placed = mapping.applyTo(tree)
        assertEquals(
            listOf("id" to 1, "addr" to 2, "city" to 5, "zip" to 6, "items" to 3, "element" to 7, "sku" to 8, "qty" to 9, "attrs" to 4, "key" to 10, "value" to 11),
            ids(placed),
        )
        assertNull(mapping.applyTo(listOf(FileColumn.leaf("nobody", null))).single().fieldId, "a column the mapping names nowhere keeps its null")
    }

    /**
     * The lookup reads the plain file's struct rebuilt through the mapping: `addr.town` is field
     * 5, which the file calls `city`, and `addr.country` is a field the file predates. Before the
     * nested mapping was consulted, every field of the struct read as null on that file — a
     * filter on the old value found nothing, and the row's `addr` printed as all-null.
     */
    @Test
    fun `a filter on a renamed nested field finds the plain file's row, and a field added since reads null there`() {
        val input = assertNotNull(model.rowLookupInput())
        val ankara = RowLookup.lookup(input, ScanFilter.Term(ScanPredicate("addr.town", PredicateOp.EQ, "Ankara")), emptySet())
        assertTrue(ankara.filesRead.all { it.error == null }, ankara.filesRead.toString())
        assertEquals(listOf("1"), ankara.hits.map { it.cells["id"].toString() })
        val addr = ankara.hits.single().cells["addr"].toString()
        assertTrue("town" in addr && "Ankara" in addr && "country" in addr && "city" !in addr, addr)
        val items = ankara.hits.single().cells["items"].toString()
        assertTrue("code" in items && "A1" in items && "sku" !in items, items)

        val noCountry = RowLookup.lookup(input, ScanFilter.Term(ScanPredicate("addr.country", PredicateOp.IS_NULL, "")), emptySet())
        assertEquals(listOf("1", "2"), noCountry.hits.map { it.cells["id"].toString() }.sorted())
        val tr = RowLookup.lookup(input, ScanFilter.Term(ScanPredicate("addr.country", PredicateOp.EQ, "TR")), emptySet())
        assertEquals(listOf("3"), tr.hits.map { it.cells["id"].toString() })
        val a2 = RowLookup.lookup(input, ScanFilter.Term(ScanPredicate("attrs", PredicateOp.IS_NOT_NULL, "")), emptySet())
        assertEquals(listOf("1", "2", "3"), a2.hits.map { it.cells["id"].toString() }.sorted())
    }

    /** The row panel's `Read As` rebuilds the plain file's struct and list the same way, and says both were placed by the mapping and rebuilt inside. */
    @Test
    fun `a sampled row's Read As rebuilds the plain file's nested values under the schema's names`() {
        val withRows = GraphLayoutService.layoutGraph(model, showRows = true)
        val rows = withRows.nodes.filterIsInstance<GraphNode.RowNode>()
        val old = rows.first { it.resolvedData["id"].toString() == "1" }
        val read = assertNotNull(old.readAs.value)
        val addr = read.cells.single { it.name == "addr" }
        assertEquals("{'town': Ankara, 'zip': 6000, 'country': NULL}", addr.value)
        assertTrue(addr.viaMapping && addr.rebuilt && addr.source == ProjectedCellSource.FILE, "$addr")
        val items = read.cells.single { it.name == "items" }
        assertEquals("[{'code': A1, 'qty': 2}, {'code': A2, 'qty': 1}]", items.value)
        assertTrue(items.viaMapping && items.rebuilt, "$items")
        assertTrue(read.differsFromFile && "rebuilt inside" in read.describe, read.describe)
        assertEquals(emptyList(), read.dropped + read.unmatched)

        val new = rows.first { it.resolvedData["id"].toString() == "3" }
        val newRead = assertNotNull(new.readAs.value)
        assertTrue(newRead.cells.none { it.rebuilt || it.viaMapping }, "the Iceberg-written file has ids and the schema's shape: $newRead")
    }
}
