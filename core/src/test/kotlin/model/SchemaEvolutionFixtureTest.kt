package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scripts that wrote `evolved`, `deep`, `defaults`, `pse` and `pkr` say which DDL ran between
 * which writes, and the evolution has to name the same changes step by step — each by field id,
 * so a rename is a rename and not a drop and an add — with the first snapshot written under each
 * schema, which is the snapshot the script's next `INSERT` made.
 */
class SchemaEvolutionFixtureTest {

    private fun iceberg(fixture: String) = FixtureCatalog.icebergModel(fixture).metadatas.last().metadata.schemaEvolution()

    private fun List<SchemaStep>.at(toId: Int) = single { it.toId == toId }

    private fun SchemaStep.summary() = changes.map { "${it.kind.label} ${it.column}: ${it.detail}" }

    @Test
    fun `evolved names two promotions, a rename, an add and a drop, one per schema, and the inserts written under them`() {
        // Script: create (id int, name string, amount float); ALTER id TYPE BIGINT; amount TYPE DOUBLE;
        // RENAME name TO label; ADD COLUMN note; INSERT; DROP COLUMN label; INSERT.
        val steps = iceberg("evolved")
        assertEquals(listOf(0, 1, 2, 3, 4, 5), steps.map { it.toId })
        assertNull(steps.first().fromId)
        assertEquals(listOf("added id: int", "added name: string", "added amount: float"), steps.at(0).summary())
        assertEquals(listOf("type changed id: int → long"), steps.at(1).summary())
        assertEquals(listOf("type changed amount: float → double"), steps.at(2).summary())
        assertEquals(listOf("renamed label: name → label"), steps.at(3).summary())
        assertEquals(listOf("added note: string"), steps.at(4).summary())
        assertEquals(listOf("dropped label: string"), steps.at(5).summary())
        assertEquals(2, steps.at(5).changes.single().fieldId)
        // Schemas 1..3 were written between two inserts: nothing was written under them.
        assertEquals("append", steps.at(0).firstSnapshotOperation)
        assertTrue(steps.at(1).firstSnapshotId == null && steps.at(2).firstSnapshotId == null && steps.at(3).firstSnapshotId == null)
        assertNotNull(steps.at(4).firstSnapshotId)
        assertNotNull(steps.at(5).firstSnapshotId)
        assertEquals("schema 4 → 5", steps.at(5).label)
        assertEquals("schema 0", steps.at(0).label)
    }

    @Test
    fun `deep names a nested add and a nested rename by the leaf's path, and says nothing of the struct holding them`() {
        // Script: ADD COLUMN addr.country STRING; RENAME COLUMN addr.city TO town.
        val steps = iceberg("deep")
        assertEquals(listOf("added addr.country: string"), steps.at(1).summary())
        assertEquals(listOf("renamed addr.town: addr.city → addr.town"), steps.at(2).summary())
        // The first schema's columns are the leaves too — a struct's children by path, a list's element, a map's key and value.
        val initial = steps.at(0).changes.map { it.column }
        assertTrue("addr.city" in initial && "tags.element" in initial && "props.key" in initial && "props.value" in initial, "$initial")
    }

    @Test
    fun `defaults names a default set with the column, and a write default moved on its own`() {
        // Script: region added with initial and write default eu; score with 0; region's write default set to us.
        val steps = iceberg("defaults")
        assertEquals(listOf("added region: string"), steps.at(1).summary())
        assertEquals(listOf("added score: int"), steps.at(2).summary())
        assertEquals(listOf("default changed region: initial eu → eu, write eu → us"), steps.at(3).summary())
    }

    @Test
    fun `a column moved is judged against the siblings both schemas keep, so a drop beside it is not a move`() {
        val a = IcebergSchemaModel(0, IcebergType.StructType(listOf(field(1, "a"), field(2, "b"), field(3, "c"))))
        val dropped = IcebergSchemaModel(1, IcebergType.StructType(listOf(field(1, "a"), field(3, "c"))))
        assertEquals(listOf(SchemaChangeKind.DROPPED), schemaChanges(a, dropped).map { it.kind })
        val moved = IcebergSchemaModel(1, IcebergType.StructType(listOf(field(3, "c"), field(1, "a"), field(2, "b"))))
        assertEquals(listOf(SchemaChange(SchemaChangeKind.MOVED, 3, "c", "first")), schemaChanges(a, moved))
        val movedAndAdded = IcebergSchemaModel(1, IcebergType.StructType(listOf(field(1, "a"), field(4, "d"), field(3, "c"), field(2, "b"))))
        assertEquals(
            listOf(SchemaChange(SchemaChangeKind.ADDED, 4, "d", "int"), SchemaChange(SchemaChangeKind.MOVED, 3, "c", "after d")),
            schemaChanges(a, movedAndAdded).sortedBy { it.kind },
        )
        assertEquals(listOf("c"), movedIds(listOf("a", "b", "c"), listOf("c", "a", "b")))
        assertEquals(emptyList(), movedIds(listOf("a", "b", "c"), listOf("a", "c")))
        val required = IcebergSchemaModel(1, IcebergType.StructType(listOf(field(1, "a", required = true), field(2, "b"), field(3, "c"))))
        assertEquals(listOf(SchemaChange(SchemaChangeKind.REQUIRED_CHANGED, 1, "a", "optional → required")), schemaChanges(a, required))
        val keyed = IcebergSchemaModel(1, a.struct, identifierFieldIds = setOf(1))
        assertEquals(listOf(SchemaChange(SchemaChangeKind.IDENTIFIER_CHANGED, null, "", "none → a")), schemaChanges(a, keyed))
    }

    private fun field(id: Int, name: String, required: Boolean = false) = NestedField(id, name, IcebergType.IntType, required)

    @Test
    fun `pse and pkr name Paimon's add, rename, default and a renamed primary key, with the snapshot each was first written under`() {
        // pse: ADD COLUMN w; RENAME v TO label; ALTER w SET DEFAULT 7; then two inserts under schema 3.
        val pse = FixtureCatalog.paimonModel("pse").schemaEvolution()
        assertEquals(listOf(0, 1, 2, 3), pse.map { it.toId })
        assertEquals(listOf("added k: INT", "added v: STRING"), pse.at(0).summary())
        assertEquals(listOf("added w: INT"), pse.at(1).summary())
        assertEquals(listOf("renamed label: v → label"), pse.at(2).summary())
        assertEquals(listOf("default changed w: none → 7"), pse.at(3).summary())
        assertEquals(1L, pse.at(0).firstSnapshotId)
        assertNull(pse.at(1).firstSnapshotId)
        assertEquals(2L, pse.at(3).firstSnapshotId)
        assertEquals("APPEND", pse.at(3).firstSnapshotOperation)
        assertNotNull(pse.at(3).timestampMs)
        // pkr: RENAME COLUMN k TO id on the primary key — the field and the key list both move.
        val pkr = FixtureCatalog.paimonModel("pkr").schemaEvolution()
        assertEquals(listOf("renamed id: k → id", "keys changed : primary key k → id"), pkr.at(1).summary())
        assertEquals(2L, pkr.at(1).firstSnapshotId)
    }

    @Test
    fun `every Iceberg fixture's first schema step lists its columns and every later step lists a change`() {
        for (fixture in FixtureCatalog.iceberg) {
            val steps = iceberg(fixture)
            assertTrue(steps.isNotEmpty(), fixture)
            assertTrue(steps.first().changes.all { it.kind == SchemaChangeKind.ADDED }, fixture)
            // A schema id is assigned only when the schema differs from every one before it.
            steps.drop(1).forEach { assertTrue(it.changes.isNotEmpty(), "$fixture ${it.label}") }
        }
    }
}
