package model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The actions a Delta log holds, as `PROTOCOL.md` (delta-io/delta, branch-3.2) specifies them.
 *
 * One line of a commit file, one row of a checkpoint and one row of a sidecar are each one
 * [DeltaAction] with exactly one field set. Checkpoints are read through DuckDB with every action
 * column turned back into JSON (`to_json`), so this one decoder reads all three — a checkpoint's
 * extra columns (`partitionValues_parsed`, `stats_parsed`) are unknown keys and ignored, which is
 * also what the protocol requires of a reader meeting a field it does not know.
 */
@Serializable
data class DeltaAction(
    val commitInfo: DeltaCommitInfo? = null,
    val metaData: DeltaMetadata? = null,
    val protocol: DeltaProtocol? = null,
    val add: DeltaAddFile? = null,
    val remove: DeltaRemoveFile? = null,
    val cdc: DeltaCdcFile? = null,
    val txn: DeltaTxn? = null,
    val domainMetadata: DeltaDomainMetadata? = null,
    val checkpointMetadata: DeltaCheckpointMetadata? = null,
    val sidecar: DeltaSidecar? = null,
)

@Serializable
data class DeltaCommitInfo(
    val timestamp: Long? = null,
    /** `inCommitTimestamp` feature: the commit's authoritative time, where the file's mtime is not. */
    val inCommitTimestamp: Long? = null,
    val operation: String? = null,
    val operationParameters: JsonObject? = null,
    val operationMetrics: Map<String, String>? = null,
    val readVersion: Long? = null,
    val isolationLevel: String? = null,
    val isBlindAppend: Boolean? = null,
    val engineInfo: String? = null,
    val txnId: String? = null,
    val userMetadata: String? = null,
)

@Serializable
data class DeltaMetadata(
    val id: String? = null,
    val name: String? = null,
    val description: String? = null,
    val format: DeltaFormat? = null,
    val schemaString: String? = null,
    val partitionColumns: List<String> = emptyList(),
    val configuration: Map<String, String?> = emptyMap(),
    val createdTime: Long? = null,
) {
    /** The table's schema, parsed; null where `schemaString` is absent or not a struct. */
    val schema: DeltaStructType? by lazy { schemaString?.let { parseDeltaSchema(it) } }

    /** `delta.columnMapping.mode`: `none`, `name` or `id`; `none` where unset. */
    val columnMappingMode: String get() = configuration["delta.columnMapping.mode"] ?: "none"
    /** `delta.enableChangeDataFeed` — a commit that rewrites rows then writes its changes as cdc files. */
    val changeDataFeedEnabled: Boolean get() = configuration["delta.enableChangeDataFeed"].equals("true", ignoreCase = true)

    /**
     * `delta.deletedFileRetentionDuration`, default `interval 1 week`: how long a tombstone keeps
     * its file from `VACUUM`, and how long the state holds the tombstone at all. Null where the
     * value is one Delta refuses (a month or a year, or not an interval).
     */
    val tombstoneRetentionMs: Long? get() = deltaIntervalMs(configuration["delta.deletedFileRetentionDuration"] ?: "interval 1 week")

    /** `delta.logRetentionDuration`, default `interval 30 days`: how long a commit file outlives a checkpoint above it. */
    val logRetentionMs: Long? get() = deltaIntervalMs(configuration["delta.logRetentionDuration"] ?: "interval 30 days")
}

/**
 * A Delta interval property in milliseconds, the way `DeltaConfigs.parseCalendarInterval` and
 * `getMilliSeconds` read it at 3.2.1: an optional `interval` and any number of `<n> <unit>` pairs,
 * summed — `interval 1 week`, `2 days 12 hours`. A month or a year is refused
 * (`isValidIntervalConfigValue`, since neither has a fixed length), as is a negative total and
 * anything else: null.
 */
fun deltaIntervalMs(text: String): Long? {
    val words = text.trim().lowercase().removePrefix("interval").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty() || words.size % 2 != 0) return null
    var micros = 0L
    for ((n, unit) in words.chunked(2).map { it[0] to it[1].removeSuffix("s") }) {
        val value = n.toLongOrNull() ?: return null
        micros += value * when (unit) {
            "week" -> 7 * 86_400_000_000L
            "day" -> 86_400_000_000L
            "hour" -> 3_600_000_000L
            "minute" -> 60_000_000L
            "second" -> 1_000_000L
            "millisecond" -> 1_000L
            "microsecond" -> 1L
            else -> return null
        }
    }
    return if (micros < 0) null else micros / 1000
}

@Serializable
data class DeltaFormat(val provider: String? = null, val options: Map<String, String?> = emptyMap())

@Serializable
data class DeltaProtocol(
    val minReaderVersion: Int? = null,
    val minWriterVersion: Int? = null,
    val readerFeatures: List<String>? = null,
    val writerFeatures: List<String>? = null,
) {
    /** Reader and writer features together, each once — what the table requires of a client. */
    val features: List<String> get() = (readerFeatures.orEmpty() + writerFeatures.orEmpty()).distinct()
}

/**
 * Where a deletion vector is and how big it is. `uniqueId` is the protocol's derived field and
 * half of a logical file's key: `<storageType><pathOrInlineDv>`, with `@<offset>` where an offset
 * is recorded.
 */
@Serializable
data class DeltaDeletionVector(
    val storageType: String? = null,
    val pathOrInlineDv: String? = null,
    val offset: Int? = null,
    val sizeInBytes: Int? = null,
    val cardinality: Long? = null,
    val maxRowIndex: Long? = null,
) {
    val uniqueId: String get() = "$storageType$pathOrInlineDv" + (offset?.let { "@$it" } ?: "")
}

@Serializable
data class DeltaAddFile(
    val path: String,
    val partitionValues: Map<String, String?> = emptyMap(),
    val size: Long? = null,
    val modificationTime: Long? = null,
    val dataChange: Boolean? = null,
    val stats: String? = null,
    val tags: Map<String, String?>? = null,
    val deletionVector: DeltaDeletionVector? = null,
    val baseRowId: Long? = null,
    val defaultRowCommitVersion: Long? = null,
    val clusteringProvider: String? = null,
) {
    /** The file's statistics, parsed from the JSON string the action carries. */
    val parsedStats: DeltaStats? by lazy { stats?.let { parseDeltaStats(it) } }

    val key: DeltaFileKey get() = DeltaFileKey(path, deletionVector?.uniqueId)
}

@Serializable
data class DeltaRemoveFile(
    val path: String,
    val deletionTimestamp: Long? = null,
    val dataChange: Boolean? = null,
    val extendedFileMetadata: Boolean? = null,
    val partitionValues: Map<String, String?>? = null,
    val size: Long? = null,
    val stats: String? = null,
    val tags: Map<String, String?>? = null,
    val deletionVector: DeltaDeletionVector? = null,
    val baseRowId: Long? = null,
    val defaultRowCommitVersion: Long? = null,
) {
    val parsedStats: DeltaStats? by lazy { stats?.let { parseDeltaStats(it) } }

    val key: DeltaFileKey get() = DeltaFileKey(path, deletionVector?.uniqueId)
}

@Serializable
data class DeltaCdcFile(
    val path: String,
    val partitionValues: Map<String, String?> = emptyMap(),
    val size: Long? = null,
    val dataChange: Boolean? = null,
    val tags: Map<String, String?>? = null,
)

@Serializable
data class DeltaTxn(val appId: String, val version: Long, val lastUpdated: Long? = null)

@Serializable
data class DeltaDomainMetadata(val domain: String, val configuration: String? = null, val removed: Boolean = false)

@Serializable
data class DeltaCheckpointMetadata(val version: Long, val tags: Map<String, String?>? = null)

@Serializable
data class DeltaSidecar(
    val path: String,
    val sizeInBytes: Long? = null,
    val modificationTime: Long? = null,
    val tags: Map<String, String?>? = null,
)

/** `_delta_log/_last_checkpoint`: a pointer to near the end of the log, and figures about what it points at. */
@Serializable
data class DeltaLastCheckpoint(
    val version: Long,
    val size: Long? = null,
    val parts: Int? = null,
    val sizeInBytes: Long? = null,
    val numOfAddFiles: Long? = null,
    val checksum: String? = null,
    val tags: Map<String, String?>? = null,
    /** Written for a V2 checkpoint: the top-level file, its sidecars and its non-file actions. */
    val v2Checkpoint: DeltaLastCheckpointV2? = null,
)

/** `_last_checkpoint`'s `v2Checkpoint`: the top-level file it names, with its size, its non-file actions and its sidecars. */
@Serializable
data class DeltaLastCheckpointV2(
    val path: String,
    val sizeInBytes: Long? = null,
    val modificationTime: Long? = null,
    val nonFileActions: List<JsonObject>? = null,
    val sidecarFiles: List<DeltaSidecar>? = null,
)

/**
 * A logical file's identity: the data file's path and the deletion vector that goes with it. The
 * same path with two vectors is two logical files — the protocol's reconciliation key.
 */
data class DeltaFileKey(val path: String, val dvId: String?) {
    override fun toString(): String = if (dvId == null) path else "$path#$dvId"
}

/**
 * `add.stats` parsed. Per-column figures are keyed by the column's **physical** name (the display
 * name without column mapping) and nested as the schema is; [tightBounds] false means the bounds
 * are still bounds but not necessarily values a live row holds — sound for skipping either way.
 */
data class DeltaStats(
    val numRecords: Long?,
    val tightBounds: Boolean?,
    val minValues: JsonObject?,
    val maxValues: JsonObject?,
    val nullCount: JsonObject?,
)

/**
 * `coerceInputValues`: a checkpoint row turned into JSON spells every absent field as an explicit
 * `null`, and a list or map the class defaults to empty must read that as empty.
 */
private val deltaJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

fun parseDeltaStats(text: String): DeltaStats? = runCatching {
    val o = deltaJson.parseToJsonElement(text).jsonObject
    DeltaStats(
        numRecords = (o["numRecords"] as? JsonPrimitive)?.longOrNull,
        tightBounds = (o["tightBounds"] as? JsonPrimitive)?.booleanOrNull,
        minValues = o["minValues"] as? JsonObject,
        maxValues = o["maxValues"] as? JsonObject,
        nullCount = o["nullCount"] as? JsonObject,
    )
}.getOrNull()

/** One line of a commit file; null for a blank line. */
fun parseDeltaAction(line: String): DeltaAction? =
    if (line.isBlank()) null else deltaJson.decodeFromString(DeltaAction.serializer(), line)

fun parseDeltaLastCheckpoint(text: String): DeltaLastCheckpoint =
    deltaJson.decodeFromString(DeltaLastCheckpoint.serializer(), text)

// ---- the schema ----------------------------------------------------------------------------

/** A Spark SQL type as `schemaString` spells it: a primitive by name, or a struct, array or map. */
sealed interface DeltaType {
    /** The type in the SQL spelling Spark prints: `int`, `decimal(10,2)`, `struct<a: int>`. */
    val sql: String
}

data class DeltaPrimitiveType(val name: String) : DeltaType {
    override val sql: String get() = when (name) {
        "integer" -> "int"
        "long" -> "bigint"
        "short" -> "smallint"
        "byte" -> "tinyint"
        "timestamp_ntz" -> "timestamp_ntz"
        else -> name
    }
}

data class DeltaStructType(val fields: List<DeltaField>) : DeltaType {
    override val sql: String get() = fields.joinToString(", ", "struct<", ">") { "${it.name}: ${it.type.sql}" }
}

data class DeltaArrayType(val elementType: DeltaType, val containsNull: Boolean) : DeltaType {
    override val sql: String get() = "array<${elementType.sql}>"
}

data class DeltaMapType(val keyType: DeltaType, val valueType: DeltaType, val valueContainsNull: Boolean) : DeltaType {
    override val sql: String get() = "map<${keyType.sql}, ${valueType.sql}>"
}

/**
 * A field with its metadata. [physicalName] and [columnMappingId] are what column mapping places
 * a file's column by; without column mapping the physical name is the display name.
 */
data class DeltaField(
    val name: String,
    val type: DeltaType,
    val nullable: Boolean,
    val metadata: JsonObject,
) {
    val physicalName: String
        get() = (metadata["delta.columnMapping.physicalName"] as? JsonPrimitive)?.contentOrNull ?: name

    val columnMappingId: Int?
        get() = (metadata["delta.columnMapping.id"] as? JsonPrimitive)?.intOrNull

    val generationExpression: String?
        get() = (metadata["delta.generationExpression"] as? JsonPrimitive)?.contentOrNull
}

/** `schemaString` parsed; null where it is not a struct. */
fun parseDeltaSchema(schemaString: String): DeltaStructType? = runCatching {
    parseDeltaType(deltaJson.parseToJsonElement(schemaString)) as? DeltaStructType
}.getOrNull()

private fun parseDeltaType(element: JsonElement): DeltaType = when (element) {
    is JsonPrimitive -> DeltaPrimitiveType(element.content)
    is JsonObject -> when (val kind = element["type"]?.jsonPrimitive?.content) {
        "struct" -> DeltaStructType(
            (element["fields"] as? JsonArray).orEmpty().map { f ->
                val o = f.jsonObject
                DeltaField(
                    name = o["name"]!!.jsonPrimitive.content,
                    type = parseDeltaType(o["type"]!!),
                    nullable = (o["nullable"] as? JsonPrimitive)?.booleanOrNull ?: true,
                    metadata = o["metadata"] as? JsonObject ?: JsonObject(emptyMap()),
                )
            },
        )
        "array" -> DeltaArrayType(
            parseDeltaType(element["elementType"]!!),
            (element["containsNull"] as? JsonPrimitive)?.booleanOrNull ?: true,
        )
        "map" -> DeltaMapType(
            parseDeltaType(element["keyType"]!!),
            parseDeltaType(element["valueType"]!!),
            (element["valueContainsNull"] as? JsonPrimitive)?.booleanOrNull ?: true,
        )
        else -> DeltaPrimitiveType(kind ?: "unknown")
    }
    else -> DeltaPrimitiveType("unknown")
}

/** Every leaf of [schema] by dotted display path, with the field it is — nested structs walked. */
fun DeltaStructType.leaves(prefix: String = ""): List<Pair<String, DeltaField>> = fields.flatMap { f ->
    val path = if (prefix.isEmpty()) f.name else "$prefix.${f.name}"
    when (val t = f.type) {
        is DeltaStructType -> t.leaves(path)
        else -> listOf(path to f)
    }
}
