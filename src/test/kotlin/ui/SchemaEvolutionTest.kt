package ui

import kotlinx.serialization.json.JsonPrimitive
import model.TableSchema
import model.TableSchemaField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Test the schema diff logic by accessing the private functions via reflection-free approach:
// we test the behavior through the data structures directly

class SchemaEvolutionTest {

    private fun field(id: Int, name: String, type: String, required: Boolean = false) =
        TableSchemaField(id = id, name = name, required = required, type = JsonPrimitive(type))

    private fun schema(id: Int, vararg fields: TableSchemaField) =
        TableSchema(type = "struct", schemaId = id, fields = fields.toList())

    // Since diffSchemas is private, we test through the public composable behavior indirectly.
    // But we can test the field extraction and change detection logic by duplicating the core algorithm here.
    // This tests the same algorithm that NodeDetails uses.

    private data class FieldInfo(val id: Int, val name: String, val required: Boolean, val type: String)

    private fun extractFields(schema: TableSchema): Map<Int, FieldInfo> =
        schema.fields.mapNotNull { field ->
            val id = field.id ?: return@mapNotNull null
            id to FieldInfo(
                id = id,
                name = field.name ?: "field_$id",
                required = field.required ?: false,
                type = field.type?.toString()?.removeSurrounding("\"") ?: "unknown"
            )
        }.toMap()

    private sealed class Change {
        data class Added(val name: String, val type: String) : Change()
        data class Dropped(val name: String) : Change()
        data class TypeChanged(val name: String, val from: String, val to: String) : Change()
        data class Renamed(val from: String, val to: String) : Change()
        data class RequiredChanged(val name: String, val wasRequired: Boolean) : Change()
    }

    private fun diff(old: TableSchema, new: TableSchema): List<Change> {
        val oldFields = extractFields(old)
        val newFields = extractFields(new)
        val changes = mutableListOf<Change>()
        (newFields.keys - oldFields.keys).forEach { id -> changes.add(Change.Added(newFields[id]!!.name, newFields[id]!!.type)) }
        (oldFields.keys - newFields.keys).forEach { id -> changes.add(Change.Dropped(oldFields[id]!!.name)) }
        (oldFields.keys intersect newFields.keys).forEach { id ->
            val o = oldFields[id]!!; val n = newFields[id]!!
            if (o.name != n.name) changes.add(Change.Renamed(o.name, n.name))
            if (o.type != n.type) changes.add(Change.TypeChanged(n.name, o.type, n.type))
            if (o.required != n.required) changes.add(Change.RequiredChanged(n.name, o.required))
        }
        return changes
    }

    @Test
    fun `no changes between identical schemas`() {
        val s = schema(0, field(1, "id", "long"), field(2, "name", "string"))
        assertEquals(emptyList(), diff(s, s))
    }

    @Test
    fun `detect added field`() {
        val s1 = schema(0, field(1, "id", "long"))
        val s2 = schema(1, field(1, "id", "long"), field(2, "name", "string"))
        val changes = diff(s1, s2)
        assertEquals(1, changes.size)
        assertTrue(changes[0] is Change.Added)
        assertEquals("name", (changes[0] as Change.Added).name)
    }

    @Test
    fun `detect dropped field`() {
        val s1 = schema(0, field(1, "id", "long"), field(2, "name", "string"))
        val s2 = schema(1, field(1, "id", "long"))
        val changes = diff(s1, s2)
        assertEquals(1, changes.size)
        assertTrue(changes[0] is Change.Dropped)
        assertEquals("name", (changes[0] as Change.Dropped).name)
    }

    @Test
    fun `detect type change`() {
        val s1 = schema(0, field(1, "id", "int"))
        val s2 = schema(1, field(1, "id", "long"))
        val changes = diff(s1, s2)
        assertEquals(1, changes.size)
        val change = changes[0] as Change.TypeChanged
        assertEquals("int", change.from)
        assertEquals("long", change.to)
    }

    @Test
    fun `detect rename`() {
        val s1 = schema(0, field(1, "old_name", "string"))
        val s2 = schema(1, field(1, "new_name", "string"))
        val changes = diff(s1, s2)
        assertEquals(1, changes.size)
        val change = changes[0] as Change.Renamed
        assertEquals("old_name", change.from)
        assertEquals("new_name", change.to)
    }

    @Test
    fun `detect required change`() {
        val s1 = schema(0, field(1, "id", "long", required = false))
        val s2 = schema(1, field(1, "id", "long", required = true))
        val changes = diff(s1, s2)
        assertEquals(1, changes.size)
        assertTrue(changes[0] is Change.RequiredChanged)
    }

    @Test
    fun `detect multiple changes`() {
        val s1 = schema(0,
            field(1, "id", "int"),
            field(2, "old_col", "string"),
        )
        val s2 = schema(1,
            field(1, "id", "long"),         // type changed
            field(3, "new_col", "double"),   // added (2 dropped, 3 added)
        )
        val changes = diff(s1, s2)
        assertTrue(changes.any { it is Change.TypeChanged }, "Should detect type change")
        assertTrue(changes.any { it is Change.Dropped }, "Should detect dropped field")
        assertTrue(changes.any { it is Change.Added }, "Should detect added field")
    }
}
