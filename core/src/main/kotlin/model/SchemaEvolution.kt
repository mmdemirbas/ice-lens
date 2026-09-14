package model

/**
 * What changed from one schema to the next, by field id — the reading of a table's `schemas`
 * list that a reader comparing two field tables by eye is doing in their head. An Iceberg field
 * is its id: a name that moves between two ids is a drop and an add, and an id that moves
 * between two names is a rename, which is what makes `evolved`'s `name → label` a rename and
 * `promoted`'s dropped `label` a drop. Each step names the snapshot first written under the
 * new schema, since a schema change writes no snapshot of its own and the reader's question is
 * which data was written under which shape.
 */
enum class SchemaChangeKind(val label: String) {
    ADDED("added"),
    DROPPED("dropped"),
    RENAMED("renamed"),
    /** Its place among its siblings changed — `ALTER COLUMN … FIRST / AFTER`; judged against the siblings both schemas have. */
    MOVED("moved"),
    /** A promotion where the format allows one (`int → long`), else a change a reader should not see. */
    TYPE_CHANGED("type changed"),
    REQUIRED_CHANGED("nullability changed"),
    DEFAULT_CHANGED("default changed"),
    IDENTIFIER_CHANGED("identifier fields changed"),
    /** Paimon's primary or partition keys, which its schema file carries. */
    KEYS_CHANGED("keys changed"),
    /** A Paimon schema option set, changed or removed — the file's `options` map. */
    OPTION_CHANGED("option changed"),
    COMMENT_CHANGED("comment changed"),
}

data class SchemaChange(
    val kind: SchemaChangeKind,
    /** The field's id; null for a change to the schema as a whole. */
    val fieldId: Int?,
    /** The field's path in the newer schema, or in the older one for a drop; empty for the schema as a whole. */
    val column: String,
    /** `int → long`, `name → label`, `after id`, `7` — what changed, in the direction of the step. */
    val detail: String,
)

/** One schema against the one before it, and the first commit that wrote under it. */
data class SchemaStep(
    /** Null for the first schema, whose "changes" are the columns it started with. */
    val fromId: Int?,
    val toId: Int,
    val changes: List<SchemaChange>,
    val firstSnapshotId: Long?,
    val firstSnapshotTimestampMs: Long?,
    /** Iceberg's `summary.operation`; Paimon's `commitKind`. */
    val firstSnapshotOperation: String?,
    /** When the schema was written, where the format records it — Paimon's `timeMillis`. */
    val timestampMs: Long? = null,
) {
    val label: String get() = if (fromId == null) "schema $toId" else "schema $fromId → $toId"
}

/** The changes from [from] to [to], by field id, then the schema-wide ones. */
fun schemaChanges(from: IcebergSchemaModel, to: IcebergSchemaModel): List<SchemaChange> {
    val changes = mutableListOf<SchemaChange>()
    val before = from.fieldsById
    val after = to.fieldsById
    for ((id, field) in after) {
        val path = to.pathOf(id) ?: field.name
        val old = before[id]
        if (old == null) {
            changes += SchemaChange(SchemaChangeKind.ADDED, id, path, field.type.typeName + (if (field.required) ", required" else ""))
            continue
        }
        val oldPath = from.pathOf(id) ?: old.name
        if (old.name != field.name) changes += SchemaChange(SchemaChangeKind.RENAMED, id, path, "$oldPath → $path")
        // Nested types are compared through their children, which have ids of their own.
        if (!old.type.isNested() || !field.type.isNested()) {
            if (old.type.typeName != field.type.typeName) changes += SchemaChange(SchemaChangeKind.TYPE_CHANGED, id, path, "${old.type.typeName} → ${field.type.typeName}")
        } else if (old.type::class != field.type::class) {
            changes += SchemaChange(SchemaChangeKind.TYPE_CHANGED, id, path, "${old.type.typeName} → ${field.type.typeName}")
        }
        if (old.required != field.required) changes += SchemaChange(SchemaChangeKind.REQUIRED_CHANGED, id, path, if (field.required) "optional → required" else "required → optional")
        if (old.initialDefault != field.initialDefault || old.writeDefault != field.writeDefault) {
            changes += SchemaChange(
                SchemaChangeKind.DEFAULT_CHANGED, id, path,
                "initial ${old.showDefault(old.initialDefault) ?: "none"} → ${field.showDefault(field.initialDefault) ?: "none"}, " +
                    "write ${old.showDefault(old.writeDefault) ?: "none"} → ${field.showDefault(field.writeDefault) ?: "none"}",
            )
        }
    }
    // Moves, judged per struct over the siblings both schemas keep, so an add or a drop beside a column is not a move of it.
    val common = before.keys intersect after.keys
    for (parent in after.keys.mapNotNull { to.parentOf(it) }.distinct()) {
        val fromOrder = from.childrenOf(parent).filter { it in common }
        val children = to.childrenOf(parent)
        for (id in movedIds(fromOrder, children.filter { it in common })) {
            val at = children.indexOf(id)
            changes += SchemaChange(SchemaChangeKind.MOVED, id, to.pathOf(id) ?: "$id", if (at > 0) "after ${to.pathOf(children[at - 1])}" else "first")
        }
    }
    for ((id, field) in before) {
        if (id !in after) changes += SchemaChange(SchemaChangeKind.DROPPED, id, from.pathOf(id) ?: field.name, field.type.typeName)
    }
    if (from.identifierFieldIds != to.identifierFieldIds) {
        changes += SchemaChange(
            SchemaChangeKind.IDENTIFIER_CHANGED, null, "",
            "${from.identifierFieldIds.map { from.pathOf(it) ?: it }.ifEmpty { listOf("none") }.joinToString(", ")} → " +
                to.identifierFieldIds.map { to.pathOf(it) ?: it }.ifEmpty { listOf("none") }.joinToString(", "),
        )
    }
    return changes
}

private fun IcebergType.isNested(): Boolean = this is IcebergType.StructType || this is IcebergType.ListType || this is IcebergType.MapType

/** The path of the struct holding [id] — empty at the root; null for an id the schema lacks. */
private fun IcebergSchemaModel.parentOf(id: Int): String? = pathOf(id)?.substringBeforeLast('.', "")

/** The ids directly under [parent], in schema order. */
private fun IcebergSchemaModel.childrenOf(parent: String): List<Int> =
    pathsById.filter { (_, path) -> path.substringBeforeLast('.', "") == parent }.keys.toList()

/**
 * The fewest ids whose move turns [fromOrder] into [toOrder]: everything outside a longest
 * common subsequence of the two. `a b c → c a b` is one move of `c`, not a move of every column
 * whose neighbour changed.
 */
internal fun <T> movedIds(fromOrder: List<T>, toOrder: List<T>): List<T> {
    val n = fromOrder.size
    val m = toOrder.size
    val lcs = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
        lcs[i][j] = if (fromOrder[i] == toOrder[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
    }
    val kept = mutableSetOf<T>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            fromOrder[i] == toOrder[j] -> { kept += fromOrder[i]; i++; j++ }
            lcs[i + 1][j] > lcs[i][j + 1] -> i++
            else -> j++
        }
    }
    return toOrder.filter { it !in kept }
}

/** The first schema's columns, listed as what it started with. */
private fun initialColumns(model: IcebergSchemaModel): List<SchemaChange> =
    model.fieldsById.map { (id, f) -> SchemaChange(SchemaChangeKind.ADDED, id, model.pathOf(id) ?: f.name, f.type.typeName + (if (f.required) ", required" else "")) }

/** Every schema in id order against the one before it, each with the first snapshot written under it. */
fun TableMetadata.schemaEvolution(): List<SchemaStep> {
    val ordered = schemas.filter { it.schemaId != null }.sortedBy { it.schemaId }.map(::tableSchemaModel)
    val firstUnder = snapshots.filter { it.schemaId != null }
        .groupBy { it.schemaId!! }
        .mapValues { (_, list) -> list.minWith(compareBy<Snapshot> { it.sequenceNumber ?: Long.MAX_VALUE }.thenBy { it.timestampMs ?: Long.MAX_VALUE }) }
    return ordered.mapIndexed { i, schema ->
        val id = requireNotNull(schema.schemaId)
        val first = firstUnder[id]
        SchemaStep(
            fromId = ordered.getOrNull(i - 1)?.schemaId,
            toId = id,
            changes = if (i == 0) initialColumns(schema) else schemaChanges(ordered[i - 1], schema),
            firstSnapshotId = first?.snapshotId,
            firstSnapshotTimestampMs = first?.timestampMs,
            firstSnapshotOperation = first?.summary?.get("operation"),
        )
    }
}

/**
 * The same on Paimon, whose schema file carries the keys, the options and a comment beside the
 * fields, and a type as text (`INT NOT NULL`) — so nullability is read off the text, and a
 * default is the write-time `defaultValue` (`pse`).
 */
fun paimonSchemaChanges(from: PaimonSchema, to: PaimonSchema): List<SchemaChange> {
    val changes = mutableListOf<SchemaChange>()
    val before = from.fields.filter { it.id != null }.associateBy { it.id!! }
    val after = to.fields.filter { it.id != null }.associateBy { it.id!! }
    val common = before.keys intersect after.keys
    for ((id, field) in after) {
        val name = field.name ?: "$id"
        val old = before[id]
        if (old == null) {
            changes += SchemaChange(SchemaChangeKind.ADDED, id, name, field.type ?: "?")
            continue
        }
        if (old.name != field.name) changes += SchemaChange(SchemaChangeKind.RENAMED, id, name, "${old.name} → $name")
        val oldType = old.type?.removeSuffix(" NOT NULL")
        val newType = field.type?.removeSuffix(" NOT NULL")
        if (oldType != newType) changes += SchemaChange(SchemaChangeKind.TYPE_CHANGED, id, name, "${old.type} → ${field.type}")
        val wasRequired = old.type?.endsWith(" NOT NULL") == true
        val isRequired = field.type?.endsWith(" NOT NULL") == true
        if (wasRequired != isRequired) changes += SchemaChange(SchemaChangeKind.REQUIRED_CHANGED, id, name, if (isRequired) "optional → required" else "required → optional")
        if (old.defaultValue != field.defaultValue) changes += SchemaChange(SchemaChangeKind.DEFAULT_CHANGED, id, name, "${old.defaultValue ?: "none"} → ${field.defaultValue ?: "none"}")
    }
    val children = to.fields.mapNotNull { it.id }
    for (id in movedIds(from.fields.mapNotNull { it.id }.filter { it in common }, children.filter { it in common })) {
        val at = children.indexOf(id)
        changes += SchemaChange(SchemaChangeKind.MOVED, id, after[id]?.name ?: "$id", if (at > 0) "after ${after[children[at - 1]]?.name}" else "first")
    }
    for ((id, field) in before) {
        if (id !in after) changes += SchemaChange(SchemaChangeKind.DROPPED, id, field.name ?: "$id", field.type ?: "?")
    }
    if (from.primaryKeys != to.primaryKeys) changes += SchemaChange(SchemaChangeKind.KEYS_CHANGED, null, "", "primary key ${from.primaryKeys.ifEmpty { listOf("none") }.joinToString(", ")} → ${to.primaryKeys.ifEmpty { listOf("none") }.joinToString(", ")}")
    if (from.partitionKeys != to.partitionKeys) changes += SchemaChange(SchemaChangeKind.KEYS_CHANGED, null, "", "partition keys ${from.partitionKeys.ifEmpty { listOf("none") }.joinToString(", ")} → ${to.partitionKeys.ifEmpty { listOf("none") }.joinToString(", ")}")
    for (key in (from.options.keys + to.options.keys).distinct().sorted()) {
        val old = from.options[key]
        val new = to.options[key]
        if (old != new) changes += SchemaChange(SchemaChangeKind.OPTION_CHANGED, null, key, "${old ?: "unset"} → ${new ?: "unset"}")
    }
    if (from.comment != to.comment) changes += SchemaChange(SchemaChangeKind.COMMENT_CHANGED, null, "", "${from.comment ?: "none"} → ${to.comment ?: "none"}")
    return changes
}

/** Every schema under `schema/` in id order against the one before it, each with the first snapshot on `main` written under it. */
fun PaimonUnifiedTableModel.schemaEvolution(): List<SchemaStep> {
    val ordered = schemas.filter { it.id != null }.sortedBy { it.id }
    val firstUnder = snapshots.filter { it.metadata.schemaId != null }.groupBy { it.metadata.schemaId!! }
        .mapValues { (_, list) -> list.minByOrNull { it.metadata.id ?: Long.MAX_VALUE } }
    return ordered.mapIndexed { i, schema ->
        val id = requireNotNull(schema.id)
        val first = firstUnder[id]?.metadata
        SchemaStep(
            fromId = ordered.getOrNull(i - 1)?.id,
            toId = id,
            changes = if (i == 0) schema.fields.filter { it.id != null }.map { f -> SchemaChange(SchemaChangeKind.ADDED, f.id, f.name ?: "${f.id}", f.type ?: "?") } else paimonSchemaChanges(ordered[i - 1], schema),
            firstSnapshotId = first?.id,
            firstSnapshotTimestampMs = first?.timeMillis,
            firstSnapshotOperation = first?.commitKind,
            timestampMs = schema.timeMillis,
        )
    }
}
