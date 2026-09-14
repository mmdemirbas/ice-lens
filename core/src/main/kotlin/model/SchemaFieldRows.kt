package model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * One row per field of a schema, nested fields included — what both schema panels draw. A
 * struct's fields follow it under their dotted path (`addr.town`), a list's element and a
 * map's key and value under `element`, `key` and `value`, each with the id the format evolves
 * it by: that id is what a rename inside the struct keeps and what a file's column is placed
 * by, so a table of top-level fields printing a nested type as one cell showed the one thing
 * about a nested column a reader comes for as an unreadable blob. The container's own row
 * says only what it is (`struct`, `list`, `map`; Paimon's `ROW`, `ARRAY`, `MAP`), since its
 * contents are the rows under it.
 */
data class SchemaFieldRow(
    val id: Int,
    /** Dotted from the root; a top-level field is its name. */
    val path: String,
    /** How deep under the root, for the panel's indent — 0 at the top. */
    val depth: Int,
    val type: String,
    val required: Boolean,
    /** v3's read-time default; Paimon has none. */
    val initialDefault: String? = null,
    /** v3's write-time default, or Paimon's `defaultValue`. */
    val writeDefault: String? = null,
)

/** The rows of this schema, depth-first in the schema's own order — [IcebergSchemaModel.fieldsById]'s walk. */
fun IcebergSchemaModel.fieldRows(): List<SchemaFieldRow> = fieldsById.values.map { field ->
    val path = pathsById.getValue(field.id)
    SchemaFieldRow(
        id = field.id, path = path, depth = path.count { it == '.' },
        type = field.type.shortTypeName, required = field.required,
        initialDefault = field.initialDefault?.let(::showDefault), writeDefault = field.writeDefault?.let(::showDefault),
    )
}

/** The rows of a Paimon schema, its types in Paimon's own spelling; the element, key and value ids are the ones its Parquet writer derives (`PaimonSystemColumns`). */
fun PaimonSchema.fieldRows(): List<SchemaFieldRow> = buildList { fields.forEach { addPaimonField(it, "", 0) } }

private fun MutableList<SchemaFieldRow>.addPaimonField(field: PaimonField, prefix: String, depth: Int) {
    val id = field.id ?: return
    val name = field.name ?: "$id"
    val type = field.dataType
    add(SchemaFieldRow(id, prefix + name, depth, type?.let(::paimonShortTypeName) ?: field.type ?: "?", required = type?.nullable == false, writeDefault = field.defaultValue))
    if (type != null) addPaimonNested(type, id, prefix + name + ".", depth + 1)
}

private fun MutableList<SchemaFieldRow>.addPaimonNested(type: PaimonType, parentId: Int, prefix: String, depth: Int) {
    when (type) {
        is PaimonType.Row -> type.fields.forEach { addPaimonField(it, prefix, depth) }
        is PaimonType.Array -> addPaimonField(PaimonField(PaimonSystemColumns.arrayElementFieldId(parentId, depth), "element", type.element.sql, dataType = type.element), prefix, depth)
        is PaimonType.Multiset -> addPaimonField(PaimonField(PaimonSystemColumns.arrayElementFieldId(parentId, depth), "element", type.element.sql, dataType = type.element), prefix, depth)
        is PaimonType.Map -> {
            addPaimonField(PaimonField(PaimonSystemColumns.mapKeyFieldId(parentId, depth), "key", type.key.sql, dataType = type.key), prefix, depth)
            addPaimonField(PaimonField(PaimonSystemColumns.mapValueFieldId(parentId, depth), "value", type.value.sql, dataType = type.value), prefix, depth)
        }
        is PaimonType.Primitive -> Unit
    }
}

/** A container named by its kind alone, its fields being the rows under it; a primitive by its type. */
val IcebergType.shortTypeName: String
    get() = when (this) {
        is IcebergType.StructType -> "struct"
        is IcebergType.ListType -> "list"
        is IcebergType.MapType -> "map"
        else -> typeName
    }

private fun paimonShortTypeName(type: PaimonType): String = when (type) {
    is PaimonType.Primitive -> type.sql
    is PaimonType.Row -> "ROW"
    is PaimonType.Array -> "ARRAY"
    is PaimonType.Multiset -> "MULTISET"
    is PaimonType.Map -> "MAP"
}.let { if (type is PaimonType.Primitive || type.nullable) it else "$it NOT NULL" }

/** A v3 default as the schema table prints it — the JSON scalar's text. */
private fun showDefault(default: JsonElement): String = (default as? JsonPrimitive)?.content ?: default.toString()
