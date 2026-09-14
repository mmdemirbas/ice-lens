package model

import kotlinx.serialization.json.Json
import service.GraphLayoutService
import service.PaimonRowLookup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `pne`: `pse` one level down — an append table with a struct and a list of structs, a field
 * renamed inside the struct, one added to it, and a field renamed inside the list's element
 * between two writes (`docs/fixtures/paimon-pne.sql`). Two things it settles. A nested type is
 * written as an **object** in the schema JSON, which read as a parse error on every schema
 * before this — a Paimon table with any struct, list or map column opened as four read errors
 * and nothing else. And Paimon evolves nested fields by id (`SchemaEvolutionUtil` at 1.3.1
 * maps a row's fields by `DataField.id()`), so the old file reads `addr.city` as `addr.town`
 * with `country` null and `items[].sku` as `code` — the script's own reads are the oracle.
 */
class PaimonNestedEvolutionFixtureTest {

    private val model = FixtureCatalog.paimonModel("pne")
    private val input by lazy { assertNotNull(model.paimonRowLookupInput()) }

    @Test
    fun `a nested type is read from the schema's JSON object, and rendered the way Paimon spells it`() {
        assertEquals(emptyList(), model.readErrors)
        val latest = model.schemas.maxByOrNull { it.id ?: -1 }!!
        assertEquals(
            listOf("k" to "INT", "addr" to "ROW<town STRING, zip INT, country STRING>", "items" to "ARRAY<ROW<code STRING, qty INT>>"),
            latest.fields.map { it.name to it.type },
        )
        val addr = assertNotNull(latest.fields[1].dataType as? PaimonType.Row)
        assertEquals(listOf(2 to "town", 3 to "zip", 7 to "country"), addr.fields.map { it.id to it.name }, "the nested fields carry ids of their own")
        val items = assertNotNull(latest.fields[2].dataType as? PaimonType.Array)
        assertEquals(listOf(5 to "code", 6 to "qty"), (items.element as PaimonType.Row).fields.map { it.id to it.name })
    }

    /** The shapes `DataTypeJsonParser` writes: `NOT NULL` in the head of an object type, a map, a multiset, and a primitive left as its string. */
    @Test
    fun `every nested shape parses, nullability included`() {
        val json = Json { ignoreUnknownKeys = true }
        val field = json.decodeFromString(
            PaimonField.serializer(),
            """{"id": 9, "name": "m", "type": {"type": "MAP NOT NULL", "key": "STRING NOT NULL", "value": {"type": "MULTISET", "element": {"type": "ROW", "fields": [{"id": 10, "name": "a", "type": "INT", "description": "x"}]}}}}""",
        )
        assertEquals("MAP<STRING NOT NULL, MULTISET<ROW<a INT>>> NOT NULL", field.type)
        val map = assertNotNull(field.dataType as? PaimonType.Map)
        assertTrue(!map.nullable && !map.key.nullable && map.value.nullable, "$map")
        assertEquals(10, ((map.value as PaimonType.Multiset).element as PaimonType.Row).fields.single().id)
        assertEquals(PaimonType.Primitive("BIGINT NOT NULL"), json.decodeFromString(PaimonField.serializer(), """{"id": 0, "name": "c", "type": "BIGINT NOT NULL"}""").dataType)
        assertNull(json.decodeFromString(PaimonField.serializer(), """{"id": 0, "name": "c"}""").dataType)
    }

    /** The read schema carries the struct with its fields' ids, and the array's element at the id Paimon's Parquet writer records for it. */
    @Test
    fun `the read schema is nested by id, the element at Paimon's derived id`() {
        val schema = input.readSchema
        assertEquals(2, schema.idOfPath("addr.town"))
        assertEquals(7, schema.idOfPath("addr.country"))
        assertEquals(5, schema.idOfPath("items.element.code"))
        val items = assertNotNull(schema.typeOf(4) as? IcebergType.ListType)
        assertEquals(PaimonSystemColumns.arrayElementFieldId(4, 1), items.elementId)
        assertEquals(536875008, items.elementId, "the id the file records on the element, checked against the Parquet footer below")
        val old = input.files.single { it.fileSchema?.id == 0 }
        val tree = paimonFileColumnTree(service.SampleRowReader.fileColumnTreeOf(old.localPath), old.fileSchema)
        val element = tree.single { it.name == "items" }.children.single()
        assertEquals(536875008, element.fieldId)
        assertEquals(listOf("city" to 2, "zip" to 3), tree.single { it.name == "addr" }.children.map { it.name to it.fieldId })
    }

    /**
     * The lookup reads the old file's struct rebuilt by id: `addr.town` is field 2, which that
     * file calls `city`, and `addr.country` a field it predates. Paimon's own read of the table
     * prints `{"town":"Ankara","zip":6000,"country":null}` for row 1 and finds it by `addr.town`.
     */
    @Test
    fun `a filter on a renamed nested field finds the old file's row, and a field added since reads null there`() {
        val ankara = PaimonRowLookup.lookup(input, ScanFilter.Term(ScanPredicate("addr.town", PredicateOp.EQ, "Ankara")), emptySet())
        assertTrue(ankara.filesRead.all { it.error == null }, ankara.filesRead.toString())
        assertEquals(listOf("1"), ankara.hits.map { it.cells["k"].toString() })
        assertEquals(RowFate.LIVE, ankara.hits.single().fate)
        val addr = ankara.hits.single().cells["addr"].toString()
        assertTrue("town" in addr && "Ankara" in addr && "country" in addr && "city" !in addr, addr)
        val items = ankara.hits.single().cells["items"].toString()
        assertTrue("code" in items && "A1" in items && "sku" !in items, items)

        val noCountry = PaimonRowLookup.lookup(input, ScanFilter.Term(ScanPredicate("addr.country", PredicateOp.IS_NULL, "")), emptySet())
        assertEquals(listOf("1", "2"), noCountry.hits.map { it.cells["k"].toString() }.sorted())
        val tr = PaimonRowLookup.lookup(input, ScanFilter.Term(ScanPredicate("addr.country", PredicateOp.EQ, "TR")), emptySet())
        assertEquals(listOf("3"), tr.hits.map { it.cells["k"].toString() })
    }

    /** The row panel's `Read As` rebuilds the old file's struct and list under the current schema's names, and leaves the new file's as DuckDB prints them. */
    @Test
    fun `a sampled row's Read As rebuilds the old file's nested values by id`() {
        val rows = GraphLayoutService.layoutGraph(model, showRows = true).nodes.filterIsInstance<GraphNode.RowNode>()
        val old = rows.first { it.resolvedData["k"].toString() == "1" }
        val read = assertNotNull(old.readAs.value)
        assertEquals("{'town': Ankara, 'zip': 6000, 'country': NULL}", read.cells.single { it.name == "addr" }.value)
        assertEquals("[{'code': A1, 'qty': 2}, {'code': A2, 'qty': 1}]", read.cells.single { it.name == "items" }.value)
        assertTrue(read.cells.single { it.name == "addr" }.rebuilt && read.differsFromFile, read.describe)
        val new = rows.first { it.resolvedData["k"].toString() == "3" }
        assertTrue(assertNotNull(new.readAs.value).cells.none { it.rebuilt }, "the schema's shape: nothing rebuilt")
    }

    /** The schema panel's rows, in Paimon's spelling, the element at the id its Parquet writer records. */
    @Test
    fun `the schema's rows list the nested fields by path, the element at its derived id`() {
        val rows = model.schemas.maxByOrNull { it.id ?: -1 }!!.fieldRows()
        assertEquals(
            listOf(
                Triple(0, "k", "INT"), Triple(1, "addr", "ROW"), Triple(2, "addr.town", "STRING"), Triple(3, "addr.zip", "INT"), Triple(7, "addr.country", "STRING"),
                Triple(4, "items", "ARRAY"), Triple(536875008, "items.element", "ROW"), Triple(5, "items.element.code", "STRING"), Triple(6, "items.element.qty", "INT"),
            ),
            rows.map { Triple(it.id, it.path, it.type) },
        )
        assertEquals(listOf(0, 0, 1, 1, 1, 0, 1, 2, 2), rows.map { it.depth })
    }

    /** The schema steps name the change where it happened: inside `addr`, inside the list's element — never a type change on the parent. */
    @Test
    fun `the schema evolution names a rename inside a struct and inside a list's element, not a type change on the parent`() {
        val steps = model.schemaEvolution()
        fun summary(toId: Int) = steps.single { it.toId == toId }.changes.map { "${it.kind.label} ${it.column}: ${it.detail}" }
        assertEquals(listOf("added k: INT", "added addr: ROW<city STRING, zip INT>", "added items: ARRAY<ROW<sku STRING, qty INT>>"), summary(0))
        assertEquals(listOf("renamed addr.town: city → town"), summary(1))
        assertEquals(listOf("added addr.country: STRING"), summary(2))
        assertEquals(listOf("renamed items.element.code: sku → code"), summary(3))
        assertTrue(steps.flatMap { it.changes }.none { it.kind == SchemaChangeKind.TYPE_CHANGED })
    }
}
