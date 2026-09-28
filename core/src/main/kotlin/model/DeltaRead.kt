package model

import kotlinx.serialization.json.JsonPrimitive

/**
 * A Delta read, put in the shape the Iceberg read path takes — so the row lookup, the live row
 * count and the pruning rules run over a Delta version unchanged rather than being written a
 * second time.
 *
 * Three things make that sound. **A Delta deletion vector's stored bytes are Iceberg's v3 blob**
 * — size, magic, portable Roaring bitmap, CRC — so a vector is a `DELETION_VECTOR` delete file at
 * `offset` for `4 + sizeInBytes + 4` bytes, paired with the one data file whose `add` carries it,
 * which is the only pairing Delta has. **The schema becomes an [IcebergSchemaModel] with a
 * [NameMapping]**: a field's id is its `delta.columnMapping.id` where column mapping wrote one and
 * a number assigned here otherwise, and the mapping names each field by its physical name and its
 * display name — so a file written without field ids is placed by name, a column-mapped file by
 * its physical name or its Parquet field id, and a renamed column is still its column. **A
 * partition column is not in the file**: its value is in the `add`'s `partitionValues`, and
 * [LookupDataFile.constants] carries it to the projection, which reads a field the file lacks
 * from there.
 */
data class DeltaReadSchema(val schema: IcebergSchemaModel, val mapping: NameMapping)

/** The Delta [struct] as the Iceberg read path takes it — see [DeltaReadSchema]. A column whose type has no Iceberg counterpart is left out. */
fun deltaReadSchema(struct: DeltaStructType): DeltaReadSchema {
    val used = mutableSetOf<Int>()
    fun collect(type: DeltaType) {
        if (type is DeltaStructType) type.fields.forEach { f -> f.columnMappingId?.let(used::add); collect(f.type) }
        if (type is DeltaArrayType) collect(type.elementType)
        if (type is DeltaMapType) { collect(type.keyType); collect(type.valueType) }
    }
    collect(struct)
    var next = (used.maxOrNull() ?: 0) + 1
    fun fresh(): Int { while (next in used) next++; return next++ }

    fun convert(type: DeltaType): Pair<IcebergType, List<MappedField>>? = when (type) {
        is DeltaPrimitiveType -> deltaPrimitiveAsIceberg(type.name)?.let { it to emptyList() }
        is DeltaStructType -> {
            val converted = type.fields.mapNotNull { f ->
                val id = f.columnMappingId ?: fresh()
                convert(f.type)?.let { (t, nested) ->
                    NestedField(id, f.name, t, required = !f.nullable) to MappedField(id, listOf(f.physicalName, f.name).distinct(), nested)
                }
            }
            IcebergType.StructType(converted.map { it.first }) to converted.map { it.second }
        }
        is DeltaArrayType -> convert(type.elementType)?.let { (element, nested) ->
            val id = fresh()
            IcebergType.ListType(id, element, elementRequired = !type.containsNull) to listOf(MappedField(id, listOf("element"), nested))
        }
        is DeltaMapType -> convert(type.keyType)?.let { (k, kn) ->
            convert(type.valueType)?.let { (v, vn) ->
                val keyId = fresh()
                val valueId = fresh()
                IcebergType.MapType(keyId, k, valueId, v, valueRequired = !type.valueContainsNull) to
                    listOf(MappedField(keyId, listOf("key"), kn), MappedField(valueId, listOf("value"), vn))
            }
        }
    }

    val (root, mapped) = convert(struct) ?: (IcebergType.StructType(emptyList()) to emptyList())
    return DeltaReadSchema(IcebergSchemaModel(null, root as IcebergType.StructType), NameMapping(mapped))
}

/**
 * A Spark primitive as the Iceberg type its values compare as: `timestamp` is instant-based
 * (Iceberg's `timestamptz`), `timestamp_ntz` wall-clock; `byte` and `short` widen to `int`, as
 * their Parquet physical type already is. Null for a type Iceberg has no counterpart for.
 */
fun deltaPrimitiveAsIceberg(name: String): IcebergType? {
    Regex("""decimal\((\d+),\s*(\d+)\)""").matchEntire(name)?.let { return IcebergType.DecimalType(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
    return when (name) {
        "boolean" -> IcebergType.BooleanType
        "byte", "short", "integer" -> IcebergType.IntType
        "long" -> IcebergType.LongType
        "float" -> IcebergType.FloatType
        "double" -> IcebergType.DoubleType
        "date" -> IcebergType.DateType
        "string" -> IcebergType.StringType
        "binary" -> IcebergType.BinaryType
        "timestamp" -> IcebergType.TimestampType(withZone = true)
        "timestamp_ntz" -> IcebergType.TimestampType(withZone = false)
        else -> null
    }
}

/** [schema] with each top-level field [values] names read as that constant — `NULL` for a null value — where a file lacks it. */
fun IcebergSchemaModel.withConstants(values: Map<String, String?>): IcebergSchemaModel {
    if (values.isEmpty()) return this
    return copy(struct = IcebergType.StructType(struct.fields.map { f ->
        // A null constant is no default at all, which the projection reads as NULL — JsonNull
        // would print as the text "null" and be cast, so the row would hold the word.
        if (f.name !in values) f else f.copy(initialDefault = values[f.name]?.let(::JsonPrimitive))
    }))
}

/**
 * What reading [version] takes, in the Iceberg read path's shape: every live file with its
 * partition values as constants, and each file's deletion vector as a delete file paired with it
 * alone. Null where the version cannot be rebuilt or records no schema. [readUnder] is the schema
 * the rows are placed onto — the version's own unless given; the history passes the newest, so a
 * column renamed between two versions is one column at every step (column mapping keys files and
 * partition values by physical name, which a rename does not change).
 *
 * An inline vector (`storageType = i`) has no file to open: it is listed with the log's text of
 * its bytes ([LookupDeleteFile.inlineVector]), which the readers frame and decode as they would a
 * stored one — `dinl`'s two, written through delta-spark's classes, are held to its reads. The
 * display names are the
 * partition columns' keys only without column mapping; under it `partitionValues` is keyed by the
 * physical name, which is mapped back here.
 */
fun DeltaUnifiedTableModel.readInputAt(version: Long, readUnder: DeltaStructType? = null): RowLookupInput? {
    val state = stateAt(version).getOrNull() ?: return null
    val metadata = state.metadata ?: return null
    val struct = readUnder ?: metadata.schema ?: return null
    val read = deltaReadSchema(struct)
    val physicalToName = struct.fields.associate { it.physicalName to it.name }
    val data = mutableListOf<LookupDataFile>()
    val deletes = mutableListOf<LookupDeleteFile>()
    val reach = mutableListOf<DeleteReach>()
    state.files.forEach { (key, add) ->
        val local = runCatching { resolve(add.path).toString() }.getOrElse { add.path }
        val constants = add.partitionValues.mapKeys { (k, _) -> physicalToName[k] ?: k }
        data += LookupDataFile(add.path, local, "parquet", add.parsedStats?.numRecords, constants)
        val dv = add.deletionVector ?: return@forEach
        val deleteKey = key.toString()
        val file = dv.filePath(path)
        val location = file?.toString()
        // Named by its file and offset where it has one — what a reader can find on disk — rather
        // than by the Z85 string the log records.
        val name = file?.let { runCatching { path.relativize(it).toString() }.getOrDefault(it.toString()) + "@" + (dv.offset ?: 1) }
            ?: "inline vector on ${add.path.substringAfterLast('/')}"
        deletes += LookupDeleteFile(
            recordedPath = name,
            localPath = location ?: "inline vector ${dv.uniqueId}",
            kind = DeleteFileKind.DELETION_VECTOR,
            key = deleteKey,
            contentOffset = (dv.offset ?: 1).toLong(),
            contentSizeInBytes = dv.sizeInBytes?.let { 4L + it + 4L },
            recordCount = dv.cardinality,
            inlineVector = dv.pathOrInlineDv.takeIf { dv.storageType == "i" },
        )
        reach += DeleteReach(
            deletePath = name,
            deleteKey = deleteKey,
            kind = DeleteFileKind.DELETION_VECTOR,
            sequenceNumber = version,
            recordCount = dv.cardinality,
            reaches = listOf(add.path),
            mayReach = emptyList(),
            targets = DeleteTargets(referenced = add.path),
        )
    }
    return RowLookupInput(version, read.schema, data, deletes, reach, read.mapping)
}

/**
 * A live `add`'s statistics in the shape the file stage of [evaluateScan] prunes on: one
 * [ColumnStats] per leaf column the schema has, by the id [deltaReadSchema] gave it, from
 * `minValues` / `maxValues` / `nullCount` walked by **physical** name — the names the stats are
 * keyed by — and the file's `numRecords` as every column's value count. A partition column is
 * not in the stats and is exact instead: its one value as both bounds, or every row null.
 *
 * Sound under `tightBounds = false`, which a deletion vector leaves behind: the bounds and null
 * counts then cover rows the vector marks, so they are wider than the live rows and can only
 * keep a file a tight bound would skip, never the reverse. A Delta string bound is a prefix — the
 * maximum with a high character appended — which is a bound all the same.
 */
fun deltaColumnStats(add: DeltaAddFile, struct: DeltaStructType, read: DeltaReadSchema, partitionColumns: List<String>): List<ColumnStats> {
    val stats = add.parsedStats
    val rows = stats?.numRecords
    val physicalByName = struct.fields.associate { it.name to it.physicalName }
    fun walk(obj: kotlinx.serialization.json.JsonObject?, physicalPath: List<String>): kotlinx.serialization.json.JsonElement? =
        physicalPath.fold(obj as kotlinx.serialization.json.JsonElement?) { at, seg -> (at as? kotlinx.serialization.json.JsonObject)?.get(seg) }
    fun physicalPath(struct: DeltaStructType, path: List<String>): List<String>? {
        val field = struct.fields.firstOrNull { it.name == path.first() } ?: return null
        if (path.size == 1) return listOf(field.physicalName)
        val nested = field.type as? DeltaStructType ?: return null
        return physicalPath(nested, path.drop(1))?.let { listOf(field.physicalName) + it }
    }
    fun value(text: String?, type: IcebergType): DecodedValue? =
        text?.let { parseLiteral(it, type) }?.let { DecodedValue(display = text, value = it, type = type, raw = ByteArray(0)) }

    return struct.leaves().mapNotNull { (path, _) ->
        val id = read.schema.idOfPath(path) ?: return@mapNotNull null
        val type = read.schema.typeOf(id) ?: return@mapNotNull null
        if (path in partitionColumns) {
            val raw = add.partitionValues[physicalByName[path] ?: path] ?: add.partitionValues[path]
            return@mapNotNull ColumnStats(
                fieldId = id, columnName = path, type = type,
                lowerBound = value(raw, type), upperBound = value(raw, type),
                valueCount = rows, nullValueCount = if (raw == null) rows else 0L, nanValueCount = null, columnSizeBytes = null,
            )
        }
        if (stats == null) return@mapNotNull null
        val physical = physicalPath(struct, path.split('.')) ?: return@mapNotNull null
        fun text(obj: kotlinx.serialization.json.JsonObject?) = (walk(obj, physical) as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.content
        ColumnStats(
            fieldId = id, columnName = path, type = type,
            lowerBound = value(text(stats.minValues), type),
            upperBound = value(text(stats.maxValues), type),
            valueCount = rows,
            nullValueCount = text(stats.nullCount)?.toLongOrNull(),
            nanValueCount = null,
            columnSizeBytes = null,
        )
    }
}

/**
 * The retained versions, newest first, each with what looking a row up in it takes — read under
 * the newest schema, the Iceberg rule ([readInputAt]). A version is retained when it can be
 * rebuilt: a log cleaned past a checkpoint keeps commits it cannot replay, and those are left
 * out of the count as well as the trace, since no read of them exists.
 */
fun DeltaUnifiedTableModel.rowHistoryInputs(): RowHistoryInputs? {
    val newest = current?.metadata?.schema ?: return null
    // Rebuildable is monotone: once a version can be read, every later one can.
    val earliest = versions.firstOrNull { stateAt(it).isSuccess } ?: return null
    val retained = versions.filter { it >= earliest }.sortedDescending()
    val traced = retained.asSequence().take(MAX_HISTORY_SNAPSHOTS).mapNotNull { v ->
        val input = readInputAt(v, newest) ?: return@mapNotNull null
        HistorySnapshot(v, commitByVersion[v]?.timestampMs, commitByVersion[v]?.commitInfo?.operation, input)
    }.toList()
    return RowHistoryInputs(traced, retained.size, unit = "version", line = "in the log")
}
