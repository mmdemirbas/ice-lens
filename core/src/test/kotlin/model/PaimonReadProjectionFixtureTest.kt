package model

import service.GraphLayoutService
import service.PaimonRowLookup
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Paimon row projected onto the snapshot's schema by field id — what a read returns — held
 * to what Paimon 1.3 printed for `pse` (`1 a null / 2 b null / 3 c 30 / 4 d 7`): the first
 * file's `v` read as `label`, its missing `w` as null, and the `SET DEFAULT 7` a later write
 * stored into a row that omitted `w`. A Paimon default is write-time, so the projection puts
 * nothing into the absent column and the schema carries it as a write default only.
 */
class PaimonReadProjectionFixtureTest {

    private val model = FixtureCatalog.paimonModel("pse")

    private fun rowsByFile(): Map<String, List<GraphNode.RowNode>> {
        val graph = GraphLayoutService.layoutGraph(model, showRows = true)
        val files = graph.nodes.filterIsInstance<GraphNode.PaimonDataFileNode>()
        return graph.nodes.filterIsInstance<GraphNode.RowNode>().groupBy { row ->
            files.first { row.id.startsWith("row_${it.id}_") }.id
        }
    }

    @Test
    fun `the schema carries the default a SET DEFAULT wrote, and the bridge carries it as a write default only`() {
        val latest = model.schemas.maxBy { it.id ?: -1 }
        assertEquals(3, latest.id)
        assertEquals(listOf(null, null, "7"), latest.fields.map { it.defaultValue })
        val bridged = paimonSchemaAsIceberg(latest)
        assertEquals(listOf(0 to "k", 1 to "label", 2 to "w"), bridged.struct.fields.map { it.id to it.name })
        val w = bridged.fieldsById.getValue(2)
        assertEquals(JsonPrimitive("7"), w.writeDefault)
        assertNull(w.initialDefault, "a Paimon default is applied on write, never on read")
        assertEquals(IcebergType.StringType, bridged.fieldsById.getValue(1).type)
    }

    @Test
    fun `a file's columns are placed by the schema its own schema id names, and a system column by its fixed id`() {
        val files = model.snapshots.last().let { replayPaimonSnapshot(it).liveEntries.values }.sortedBy { it.metadata.file?.schemaId }
        assertEquals(listOf(0L, 3L, 3L), files.map { it.metadata.file?.schemaId })
        assertEquals(mapOf("k" to 0, "v" to 1), files[0].fileColumns)
        assertEquals(mapOf("k" to 0, "label" to 1, "w" to 2), files[1].fileColumns)
        val keyed = FixtureCatalog.paimonModel("pav").snapshots.last().let { replayPaimonSnapshot(it).liveEntries.values.first() }
        assertEquals(
            mapOf("_KEY_k" to 1073741823, "_SEQUENCE_NUMBER" to 2147483646, "_VALUE_KIND" to 2147483645, "k" to 0, "v" to 1, "n" to 2),
            keyed.fileColumns, "an Avro file records no ids; the schema places it, and a key column at KEY_FIELD_ID_START + its field's",
        )
        // `pkr`: the key renamed between writes is one column under two names, at one id.
        val renamed = FixtureCatalog.paimonModel("pkr").snapshots.last().let { replayPaimonSnapshot(it).liveEntries.values }.sortedBy { it.metadata.file?.schemaId }
        assertEquals(listOf(0L, 1L, 1L), renamed.map { it.metadata.file?.schemaId })
        assertEquals(1073741823, renamed[0].fileColumns["_KEY_k"])
        assertEquals(1073741823, renamed[1].fileColumns["_KEY_id"])
        val read = paimonSchemaAsIceberg(FixtureCatalog.paimonModel("pkr").schemas.maxBy { it.id ?: -1 }, systemColumns = true)
        assertEquals(listOf(1073741823 to "_KEY_id", 2147483646 to "_SEQUENCE_NUMBER", 2147483645 to "_VALUE_KIND", 0 to "id", 1 to "v"), read.struct.fields.map { it.id to it.name })
        assertEquals(listOf(0 to "id", 1 to "v"), paimonSchemaAsIceberg(FixtureCatalog.paimonModel("pkr").schemas.maxBy { it.id ?: -1 }).struct.fields.map { it.id to it.name })
    }

    @Test
    fun `a file written under the first schema reads its renamed column under the new name and the added column as null`() {
        val byFile = rowsByFile()
        val old = byFile.values.first { rows -> rows.any { it.resolvedData.containsKey("v") } }.sortedBy { it.resolvedData["k"].toString() }
        assertEquals(listOf("1", "2"), old.map { it.resolvedData["k"].toString() })
        for ((row, label) in old.zip(listOf("a", "b"))) {
            val read = assertNotNull(row.readAs.value, row.id)
            assertEquals(listOf("k", "label", "w"), read.cells.map { it.name })
            assertEquals(listOf(row.resolvedData["k"].toString(), label, "null"), read.cells.map { it.value })
            assertEquals(listOf(ProjectedCellSource.FILE, ProjectedCellSource.FILE, ProjectedCellSource.ABSENT), read.cells.map { it.source })
            assertEquals("v", read.cells[1].fileColumn, "placed by field id 1, which the file's own schema calls v")
            assertTrue(read.differsFromFile)
            assertTrue(read.dropped.isEmpty())
        }
        val new = byFile.values.filter { rows -> rows.none { it.resolvedData.containsKey("v") } }.flatten().sortedBy { it.resolvedData["k"].toString() }
        assertEquals(listOf(listOf("3", "c", "30"), listOf("4", "d", "7")), new.map { assertNotNull(it.readAs.value).cells.map { c -> c.value } })
        assertTrue(new.all { row -> row.readAs.value!!.cells.all { it.source == ProjectedCellSource.FILE } })
        assertFalse(new.first().readAs.value!!.differsFromFile)
    }

    @Test
    fun `the lookup reads the old file under the schema's names, so the renamed and the added column both answer`() {
        val input = assertNotNull(model.paimonRowLookupInput())
        fun keys(column: String, op: PredicateOp, literal: String = ""): List<String> {
            val result = PaimonRowLookup.lookup(input, ScanFilter.Term(ScanPredicate(column, op, literal)), emptySet())
            assertTrue(result.filesRead.all { it.error == null }, result.filesRead.mapNotNull { it.error }.toString())
            return result.hits.map { it.cells["k"].toString() }.sorted()
        }
        assertEquals(listOf("1"), keys("label", PredicateOp.EQ, "a"))
        assertEquals(listOf("1", "2"), keys("w", PredicateOp.IS_NULL))
        assertEquals(listOf("4"), keys("w", PredicateOp.EQ, "7"))
        assertEquals(listOf("1", "2", "3", "4"), keys("k", PredicateOp.GTE, "1"))
        assertTrue(PaimonRowLookup.lookup(input, ScanFilter.Term(ScanPredicate("k", PredicateOp.GTE, "1")), emptySet()).hits.all { it.fate == RowFate.LIVE })
    }
}
