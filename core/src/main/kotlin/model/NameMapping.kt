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
 * ids win. Top-level fields only, the same scope as [projectRow].
 */
data class NameMapping(val fields: List<MappedField>) {
    /** The field id a file column's name maps to, or null when the mapping names it nowhere. */
    fun fieldIdOf(name: String): Int? = fields.firstOrNull { name in it.names }?.fieldId

    companion object {
        const val PROPERTY = "schema.name-mapping.default"

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
