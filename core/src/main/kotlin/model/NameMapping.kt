package model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * The table's `schema.name-mapping.default` — how a reader places the columns of a file that
 * records no field ids, which is every file a table took in through `add_files` or `migrate`:
 * a JSON list of `{field-id, names}` per field, the names being every name the column has had
 * (a rename appends the new one, so `migrated`'s field 2 maps `["name", "label"]`). A file
 * column is placed by its own field id first and through this only when it has none; a file
 * with ids and a table with a mapping is the ordinary case after one migrated write, and the
 * ids win. The mapping is a tree — `fields` under a field — and [applyTo] walks it down a
 * file's column tree the way Iceberg's `ApplyNameMapping` does, by the path of names.
 */
data class NameMapping(val fields: List<MappedField>) {
    /** The field id a file column's name maps to, or null when the mapping names it nowhere. */
    fun fieldIdOf(name: String): Int? = fields.firstOrNull { name in it.names }?.fieldId

    /**
     * [columns] with every id a column does not record filled from this mapping, at every
     * level: a struct's field by its name under the struct's mapped field, a list's element and
     * a map's key and value by their role — `ApplyNameMapping` normalises those to `element`,
     * `key` and `value` whatever the file calls them (`array_element`, a Hive-written list's
     * `array`), and `MappingUtil.create` writes them under those names. A column the mapping
     * names nowhere keeps its null, and nothing under it is looked for.
     */
    fun applyTo(columns: List<FileColumn>): List<FileColumn> = columns.map { apply(it, fields.firstOrNull { f -> it.name in f.names }) }

    private fun apply(column: FileColumn, mapped: MappedField?): FileColumn {
        val under = mapped?.fields.orEmpty()
        val children = when (column.kind) {
            FileColumn.Kind.STRUCT -> column.children.map { child -> apply(child, under.firstOrNull { child.name in it.names }) }
            FileColumn.Kind.LIST -> column.children.map { child -> apply(child, under.firstOrNull { ELEMENT in it.names }) }
            FileColumn.Kind.MAP -> column.children.mapIndexed { i, child -> apply(child, under.firstOrNull { (if (i == 0) KEY else VALUE) in it.names }) }
            FileColumn.Kind.PRIMITIVE -> column.children
        }
        return column.copy(fieldId = column.fieldId ?: mapped?.fieldId, children = children)
    }

    companion object {
        const val PROPERTY = "schema.name-mapping.default"
        private const val ELEMENT = "element"
        private const val KEY = "key"
        private const val VALUE = "value"

        /** The property's JSON, or null when it does not parse as a list of fields. */
        fun parse(json: String): NameMapping? = runCatching {
            val array = Json.parseToJsonElement(json) as? JsonArray ?: return null
            NameMapping(array.mapNotNull { mappedField(it as? JsonObject ?: return@mapNotNull null) })
        }.getOrNull()

        private fun mappedField(obj: JsonObject): MappedField = MappedField(
            fieldId = obj["field-id"]?.jsonPrimitive?.intOrNull,
            names = obj["names"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            fields = obj["fields"]?.jsonArray?.mapNotNull { (it as? JsonObject)?.let(::mappedField) }.orEmpty(),
        )
    }
}

data class MappedField(val fieldId: Int?, val names: List<String>, val fields: List<MappedField> = emptyList())

/** The mapping the metadata's properties carry, or null without one. */
fun TableMetadata.nameMapping(): NameMapping? = properties[NameMapping.PROPERTY]?.let(NameMapping::parse)

/**
 * Which file column holds each field: a column's own field id first, the [mapping] for a column
 * recording none — the two rules an Iceberg reader applies, in that order. [fileColumns] is the
 * file's top-level columns with the id each records, or null. A column placed by neither is
 * left out, and a field two columns claim keeps the first.
 */
fun placeFileColumns(fileColumns: Map<String, Int?>, mapping: NameMapping?): Map<Int, String> {
    val placed = linkedMapOf<Int, String>()
    fileColumns.forEach { (name, id) ->
        val fieldId = id ?: mapping?.fieldIdOf(name) ?: return@forEach
        placed.putIfAbsent(fieldId, name)
    }
    return placed
}
