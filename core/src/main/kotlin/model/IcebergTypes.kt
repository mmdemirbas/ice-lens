package model

import kotlinx.serialization.json.*

/**
 * Iceberg's type model, as far as a reader needs it.
 *
 * This exists because the values stored in `lower_bounds`, `upper_bounds` and `partition`
 * carry **no self-description** — they are raw bytes whose meaning comes from a type resolved
 * elsewhere. Decoding them without a type produces hex, which is what every other tool in this
 * space shows.
 *
 * The type source differs per site, and getting it wrong is silent rather than loud:
 *
 * - `data_file.lower_bounds` / `upper_bounds`: the map key is a **schema field id**, resolved
 *   against the schema the *manifest* carries in its own Avro key-value metadata under
 *   `schema` — not the table's current schema. Using the current schema appears to work and
 *   yields wrong values for any file written before a type change.
 * - `manifest_file.partitions[i]`: the type is the **result type** of partition field *i* of
 *   the spec identified by `partition_spec_id`.
 * - `data_file.partition`: a struct whose field types are the partition spec's result types.
 *
 * @see decodeSingleValue for the byte-level decoding this type model feeds.
 */
sealed interface IcebergType {

    /** Display name matching the spec's own vocabulary, e.g. `decimal(9, 2)`. */
    val typeName: String

    // --- Primitives ---

    data object BooleanType : IcebergType { override val typeName = "boolean" }
    data object IntType : IcebergType { override val typeName = "int" }
    data object LongType : IcebergType { override val typeName = "long" }
    data object FloatType : IcebergType { override val typeName = "float" }
    data object DoubleType : IcebergType { override val typeName = "double" }
    data object DateType : IcebergType { override val typeName = "date" }
    data object StringType : IcebergType { override val typeName = "string" }
    data object UuidType : IcebergType { override val typeName = "uuid" }
    data object BinaryType : IcebergType { override val typeName = "binary" }

    /** v3. A value of unknown type; always null. */
    data object UnknownType : IcebergType { override val typeName = "unknown" }

    /** v3 semi-structured value. Bounds use a dedicated encoding, not this one. */
    data object VariantType : IcebergType { override val typeName = "variant" }

    /** Microsecond or nanosecond precision, with or without a zone. */
    data class TimeType(val unit: TimeUnit = TimeUnit.MICROS) : IcebergType {
        override val typeName = if (unit == TimeUnit.NANOS) "time_ns" else "time"
    }

    data class TimestampType(
        val withZone: Boolean,
        val unit: TimeUnit = TimeUnit.MICROS,
    ) : IcebergType {
        override val typeName = buildString {
            append("timestamp")
            if (withZone) append("tz")
            if (unit == TimeUnit.NANOS) append("_ns")
        }
    }

    data class FixedType(val length: Int) : IcebergType {
        override val typeName = "fixed[$length]"
    }

    data class DecimalType(val precision: Int, val scale: Int) : IcebergType {
        override val typeName = "decimal($precision, $scale)"
    }

    /** v3 geospatial types. Bounds are points, not the full geometry — see [decodeSingleValue]. */
    data class GeometryType(val crs: String? = null) : IcebergType {
        override val typeName = if (crs == null) "geometry" else "geometry($crs)"
    }

    data class GeographyType(val crs: String? = null, val algorithm: String? = null) : IcebergType {
        override val typeName = buildString {
            append("geography")
            if (crs != null) append("($crs${if (algorithm != null) ", $algorithm" else ""})")
        }
    }

    // --- Nested ---

    data class StructType(val fields: List<NestedField>) : IcebergType {
        override val typeName = "struct<${fields.joinToString(", ") { "${it.name}: ${it.type.typeName}" }}>"
        fun field(id: Int): NestedField? = fields.firstOrNull { it.id == id }
    }

    data class ListType(val elementId: Int, val element: IcebergType, val elementRequired: Boolean) : IcebergType {
        override val typeName = "list<${element.typeName}>"
    }

    data class MapType(
        val keyId: Int, val key: IcebergType,
        val valueId: Int, val value: IcebergType, val valueRequired: Boolean,
    ) : IcebergType {
        override val typeName = "map<${key.typeName}, ${value.typeName}>"
    }

    /** A type string the parser did not recognise. Kept rather than dropped so the UI can say so. */
    data class UnparsedType(val raw: String) : IcebergType {
        override val typeName = raw
    }

    enum class TimeUnit { MICROS, NANOS }
}

data class NestedField(
    val id: Int,
    val name: String,
    val type: IcebergType,
    val required: Boolean = false,
    val doc: String? = null,
    /**
     * v3 column defaults, in the JSON single-value form the spec fixes (Appendix D: a string as a
     * string, a number as a number, a decimal or a timestamp as a string). [initialDefault] is what
     * a read returns for the column on a row written before it existed; [writeDefault] is what a
     * writer stores when a row omits it. A field added before v3, or without one, has neither.
     */
    val initialDefault: JsonElement? = null,
    val writeDefault: JsonElement? = null,
) {
    /** A default as the panel prints it — the JSON scalar's text, or the element for a struct/list. */
    fun showDefault(default: JsonElement?): String? = default?.let { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
}

private val FIXED_RE = Regex("""fixed\s*\[\s*(\d+)\s*]""")
private val DECIMAL_RE = Regex("""decimal\s*\(\s*(\d+)\s*,\s*(\d+)\s*\)""")
private val GEOMETRY_RE = Regex("""geometry(?:\(([^)]*)\))?""")
private val GEOGRAPHY_RE = Regex("""geography(?:\(([^)]*)\))?""")

/**
 * Parses an Iceberg type from its JSON form — either a primitive type string like `"long"`,
 * `"decimal(9, 2)"` or `"fixed[16]"`, or a nested `{"type": "struct"|"list"|"map", ...}` object.
 *
 * Unrecognised types become [IcebergType.UnparsedType] rather than throwing: a reader whose job
 * is to show you what is in the file should show an unfamiliar type by name, not refuse to open
 * the table. New primitive types have been added in every format version so far.
 */
fun parseIcebergType(element: JsonElement): IcebergType = when (element) {
    is JsonPrimitive -> parsePrimitiveType(element.content)
    is JsonObject -> when (element["type"]?.jsonPrimitive?.contentOrNull) {
        "struct" -> IcebergType.StructType(
            element["fields"]?.jsonArray.orEmpty().mapNotNull { parseNestedField(it) }
        )
        "list" -> IcebergType.ListType(
            elementId = element["element-id"]?.jsonPrimitive?.intOrNull ?: -1,
            element = element["element"]?.let { parseIcebergType(it) } ?: IcebergType.UnparsedType("unknown"),
            elementRequired = element["element-required"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
        "map" -> IcebergType.MapType(
            keyId = element["key-id"]?.jsonPrimitive?.intOrNull ?: -1,
            key = element["key"]?.let { parseIcebergType(it) } ?: IcebergType.UnparsedType("unknown"),
            valueId = element["value-id"]?.jsonPrimitive?.intOrNull ?: -1,
            value = element["value"]?.let { parseIcebergType(it) } ?: IcebergType.UnparsedType("unknown"),
            valueRequired = element["value-required"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
        else -> IcebergType.UnparsedType(element.toString())
    }
    else -> IcebergType.UnparsedType(element.toString())
}

private fun parseNestedField(element: JsonElement): NestedField? {
    val obj = element as? JsonObject ?: return null
    val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return null
    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
    val type = obj["type"]?.let { parseIcebergType(it) } ?: return null
    return NestedField(
        id = id,
        name = name,
        type = type,
        required = obj["required"]?.jsonPrimitive?.booleanOrNull ?: false,
        doc = obj["doc"]?.jsonPrimitive?.contentOrNull,
        initialDefault = obj["initial-default"],
        writeDefault = obj["write-default"],
    )
}

private fun parsePrimitiveType(raw: String): IcebergType {
    val name = raw.trim()
    return when (name) {
        "boolean" -> IcebergType.BooleanType
        "int" -> IcebergType.IntType
        "long" -> IcebergType.LongType
        "float" -> IcebergType.FloatType
        "double" -> IcebergType.DoubleType
        "date" -> IcebergType.DateType
        "time" -> IcebergType.TimeType(IcebergType.TimeUnit.MICROS)
        "time_ns" -> IcebergType.TimeType(IcebergType.TimeUnit.NANOS)
        "timestamp" -> IcebergType.TimestampType(withZone = false)
        "timestamptz" -> IcebergType.TimestampType(withZone = true)
        "timestamp_ns" -> IcebergType.TimestampType(withZone = false, unit = IcebergType.TimeUnit.NANOS)
        "timestamptz_ns" -> IcebergType.TimestampType(withZone = true, unit = IcebergType.TimeUnit.NANOS)
        "string" -> IcebergType.StringType
        "uuid" -> IcebergType.UuidType
        "binary" -> IcebergType.BinaryType
        "unknown" -> IcebergType.UnknownType
        "variant" -> IcebergType.VariantType
        else -> {
            FIXED_RE.matchEntire(name)?.let { return IcebergType.FixedType(it.groupValues[1].toInt()) }
            DECIMAL_RE.matchEntire(name)?.let {
                return IcebergType.DecimalType(it.groupValues[1].toInt(), it.groupValues[2].toInt())
            }
            GEOMETRY_RE.matchEntire(name)?.let {
                return IcebergType.GeometryType(it.groupValues[1].takeIf { c -> c.isNotBlank() })
            }
            GEOGRAPHY_RE.matchEntire(name)?.let {
                val args = it.groupValues[1].split(',').map(String::trim).filter(String::isNotEmpty)
                return IcebergType.GeographyType(args.getOrNull(0), args.getOrNull(1))
            }
            IcebergType.UnparsedType(name)
        }
    }
}

/**
 * A table schema as recorded in a metadata.json `schemas` entry or in a manifest's `schema`
 * file-metadata key.
 */
data class IcebergSchemaModel(
    val schemaId: Int?,
    val struct: IcebergType.StructType,
    val identifierFieldIds: Set<Int> = emptySet(),
) {
    /**
     * Flat field-id → field map, descending through structs, lists and maps — a list's element
     * and a map's key and value included, as `element`, `key` and `value`, since a data file
     * records bounds and counts for them under their own ids (`deep`).
     */
    val fieldsById: Map<Int, NestedField> by lazy {
        buildMap { collectFields(struct, "", this, mutableMapOf()) }
    }

    /**
     * Field id → the field's path from the root, dotted — `addr.town`, `tags.element`,
     * `props.value` — which is how Iceberg names a nested column in its metadata tables and in
     * a filter, and the only name that says which struct a `zip` is in.
     */
    val pathsById: Map<Int, String> by lazy {
        buildMap { collectFields(struct, "", mutableMapOf(), this) }
    }

    private val idsByPath: Map<String, Int> by lazy { pathsById.entries.associate { (id, path) -> path.lowercase() to id } }

    fun typeOf(fieldId: Int): IcebergType? = fieldsById[fieldId]?.type
    fun nameOf(fieldId: Int): String? = fieldsById[fieldId]?.name
    fun pathOf(fieldId: Int): String? = pathsById[fieldId]
    /** The field a path names, case-insensitively — `addr.town` or a top-level `id`; null for none. */
    fun idOfPath(path: String): Int? = idsByPath[path.trim().lowercase()]
}

private fun collectFields(type: IcebergType, prefix: String, fields: MutableMap<Int, NestedField>, paths: MutableMap<Int, String>) {
    fun put(field: NestedField) {
        if (fields.putIfAbsent(field.id, field) == null) paths.putIfAbsent(field.id, prefix + field.name)
        collectFields(field.type, prefix + field.name + ".", fields, paths)
    }
    when (type) {
        is IcebergType.StructType -> type.fields.forEach(::put)
        is IcebergType.ListType -> put(NestedField(type.elementId, "element", type.element, type.elementRequired))
        is IcebergType.MapType -> {
            put(NestedField(type.keyId, "key", type.key, required = true))
            put(NestedField(type.valueId, "value", type.value, type.valueRequired))
        }
        else -> Unit
    }
}

/**
 * A metadata.json `schemas` entry as the same model a manifest's schema is parsed into, so the
 * two can answer the same questions. A field whose id, name or type is missing is left out
 * rather than failing the schema, the way [parseNestedField] does.
 */
/** The metadata's current schema as a model — `current-schema-id`, else the last listed — or null when it lists none. */
fun TableMetadata.currentSchemaModel(): IcebergSchemaModel? =
    (schemas.firstOrNull { it.schemaId == currentSchemaId } ?: schemas.lastOrNull())?.let(::tableSchemaModel)

/**
 * Every field any of the metadata's schemas defined, by id, the newest definition of each
 * winning and each named by its path — so a bound for a column dropped before its manifest
 * was rewritten still has a name and a type, and a nested one still says which struct it was
 * in. A type only ever widens, so the newest definition is the widest and safe to decode with.
 */
fun TableMetadata.fieldsEverDefined(): Map<Int, NestedField> = schemas
    .sortedBy { it.schemaId ?: -1 }
    .flatMap { tableSchema -> tableSchemaModel(tableSchema).let { m -> m.fieldsById.values.map { f -> f.copy(name = m.pathOf(f.id) ?: f.name) } } }
    .associateBy { it.id }

fun tableSchemaModel(schema: TableSchema): IcebergSchemaModel = IcebergSchemaModel(
    schemaId = schema.schemaId,
    struct = IcebergType.StructType(
        schema.fields.mapNotNull { field ->
            val id = field.id ?: return@mapNotNull null
            val name = field.name ?: return@mapNotNull null
            val type = field.type?.let { parseIcebergType(it) } ?: return@mapNotNull null
            NestedField(id = id, name = name, type = type, required = field.required ?: false, initialDefault = field.initialDefault, writeDefault = field.writeDefault)
        },
    ),
    identifierFieldIds = schema.identifierFieldIds.toSet(),
)

/** Parses the `schema` JSON a manifest carries in its Avro file metadata, or a metadata.json schema. */
fun parseIcebergSchema(json: String): IcebergSchemaModel? = runCatching {
    val obj = Json.parseToJsonElement(json).jsonObject
    val struct = parseIcebergType(obj) as? IcebergType.StructType ?: return@runCatching null
    IcebergSchemaModel(
        schemaId = obj["schema-id"]?.jsonPrimitive?.intOrNull,
        struct = struct,
        identifierFieldIds = obj["identifier-field-ids"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.intOrNull }?.toSet().orEmpty(),
    )
}.getOrNull()
