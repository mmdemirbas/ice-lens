package model

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneOffset

private val specJson = Json { ignoreUnknownKeys = true }

/**
 * Parses the `partition-spec` JSON a manifest carries in its own Avro file metadata.
 *
 * Iceberg 1.8.1 writes a bare array of fields — verified on `example/iceberg/default/parted` —
 * but the object form `{"spec-id": N, "fields": [...]}` used in metadata.json is accepted too,
 * so a manifest from a writer that chose it still resolves.
 */
fun parsePartitionSpec(json: String, specId: Int? = null): PartitionSpec? = runCatching {
    when (val root = specJson.parseToJsonElement(json)) {
        is JsonArray -> PartitionSpec(
            specId = specId,
            fields = specJson.decodeFromJsonElement(ListSerializer(PartitionField.serializer()), root),
        )
        is JsonObject -> specJson.decodeFromJsonElement(PartitionSpec.serializer(), root)
            .let { if (it.specId == null) it.copy(specId = specId) else it }
        else -> null
    }
}.getOrNull()

private val BUCKET_RE = Regex("""bucket\s*\[\s*(\d+)\s*]""")
private val TRUNCATE_RE = Regex("""truncate\s*\[\s*(\d+)\s*]""")

/**
 * The type a partition transform produces, given the type of its source column.
 *
 * The asymmetry among the time transforms is the trap, and it is silent: `day` produces a
 * **date** while `year`, `month` and `hour` produce a plain **int** ordinal. All four occupy the
 * same four little-endian bytes, so reading a `year` as a date — or a `day` as an int — yields a
 * plausible value rather than an error.
 *
 * Observed, not assumed: on a manifest written by Iceberg 1.8.1
 * (`example/iceberg/default/parted`) the partition struct's own Avro schema types `d_day` as
 * `int` with `logicalType: date`, and `ts_y_year` / `ts_m_month` / `ts_h_hour` as bare `int`.
 */
fun partitionResultType(transform: String, sourceType: IcebergType?): IcebergType {
    val name = transform.trim()
    return when (name) {
        // Passes the source value through unchanged, so it keeps the source type.
        "identity" -> sourceType ?: IcebergType.UnparsedType(name)
        // Always null, but typed as the source so the column still describes itself.
        "void" -> sourceType ?: IcebergType.UnparsedType(name)
        // Ordinals counted from the epoch: years from 1970, months from 1970-01, hours from
        // 1970-01-01T00:00. Not dates, despite naming a point in time.
        "year", "month", "hour" -> IcebergType.IntType
        "day" -> IcebergType.DateType
        else -> {
            BUCKET_RE.matchEntire(name)?.let { return IcebergType.IntType }
            // Truncation shortens a value without changing its type.
            TRUNCATE_RE.matchEntire(name)?.let { return sourceType ?: IcebergType.UnparsedType(name) }
            IcebergType.UnparsedType(name)
        }
    }
}

/**
 * One decoded partition value: which field it belongs to, the value as stored, and the way
 * Iceberg itself writes that value into a partition path.
 *
 * [stored] and [human] differ for the ordinal time transforms, and that difference is the point:
 * a `year` partition for 2024 stores `54`. Showing only the stored value is accurate and
 * unreadable; showing only the rendered one hides what the transform did.
 */
data class PartitionValue(
    val field: PartitionField,
    /** The type [stored] was decoded against — the transform's result type. */
    val type: IcebergType,
    val stored: DecodedValue,
    /** Iceberg's own rendering, matching the `name=value` segments of the data file's path. */
    val human: String,
) {
    /** True when the transform makes [human] say something [stored] does not. */
    val isRenderedDifferently: Boolean get() = human != stored.display
}

/**
 * A data file's partition tuple, decoded.
 *
 * An empty [values] alongside a spec that was read means the table really is unpartitioned; a
 * null `DecodedPartition` means the spec could not be read at all. Those are different answers
 * and the UI must not show the second as the first.
 */
data class DecodedPartition(val values: List<PartitionValue>) {
    val isUnpartitioned: Boolean get() = values.isEmpty()

    /**
     * `name=alpha/id_bucket=2/...` — the same form Iceberg writes into the data file's path,
     * which makes the file's own location an independent check on this decoding.
     */
    val path: String get() = values.joinToString("/") { "${it.field.name.orEmpty()}=${it.human}" }
}

/**
 * Decodes a `data_file.partition` tuple.
 *
 * [raw] holds the partition struct's values by field name, already normalised to plain Kotlin
 * types by the reader. Unlike `lower_bounds` / `upper_bounds`, this struct is a real Avro record
 * rather than Appendix-D bytes, so the values arrive typed — but they are re-encoded to the
 * Appendix-D form and run through [decodeSingleValue] anyway, so that there is exactly one place
 * in the codebase where a type meets its bytes.
 *
 * Returns null when [spec] is absent: "not partitioned" and "spec unreadable" are different.
 */
fun decodePartition(
    raw: Map<String, Any?>?,
    spec: PartitionSpec?,
    schema: IcebergSchemaModel?,
): DecodedPartition? {
    if (spec == null) return null
    return DecodedPartition(
        spec.fields.map { field ->
            val sourceType = field.sourceId?.let { schema?.typeOf(it) }
            val type = partitionResultType(field.transformName, sourceType)
            val stored = decodePartitionValue(raw?.get(field.name.orEmpty()), type)
            PartitionValue(
                field = field,
                type = type,
                stored = stored,
                human = humanPartitionValue(field.transformName, stored),
            )
        }
    )
}

/**
 * Decodes one partition value that arrived as an already-typed Avro value.
 *
 * Every branch re-encodes to the Appendix-D byte form and defers to [decodeSingleValue], so the
 * endianness, scale and unit rules live in one decoder rather than two.
 */
fun decodePartitionValue(raw: Any?, type: IcebergType): DecodedValue = when (raw) {
    null -> DecodedValue("null", null, type, ByteArray(0))
    // Avro `fixed` and `bytes` already carry the Appendix-D encoding — decimal included, whose
    // unscaled value is big-endian two's complement in both forms.
    is ByteArray -> decodeSingleValue(raw, type)
    is Boolean -> decodeSingleValue(byteArrayOf(if (raw) 1 else 0), type)
    is Int -> decodeSingleValue(littleEndian(4) { putInt(raw) }, type)
    is Long -> decodeSingleValue(littleEndian(8) { putLong(raw) }, type)
    is Float -> decodeSingleValue(littleEndian(4) { putFloat(raw) }, type)
    is Double -> decodeSingleValue(littleEndian(8) { putDouble(raw) }, type)
    // Avro hands back Utf8, not String.
    is CharSequence -> decodeSingleValue(raw.toString().toByteArray(Charsets.UTF_8), type)
    else -> DecodedValue(
        raw.toString(), raw, type, ByteArray(0),
        "unexpected partition value of type ${raw::class.simpleName}",
    )
}

private inline fun littleEndian(size: Int, put: ByteBuffer.() -> Unit): ByteArray =
    ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(put).array()

/**
 * Renders a partition value the way Iceberg does when it builds a partition path.
 *
 * Only the ordinal time transforms need this; every other transform already reads correctly from
 * its own type. The arithmetic is floor-based rather than truncating because the ordinals go
 * negative before 1970, and truncating division puts those in the wrong year.
 */
fun humanPartitionValue(transform: String, decoded: DecodedValue): String {
    val ordinal = decoded.value as? Int ?: return decoded.display
    return when (transform.trim()) {
        "year" -> "%04d".format(1970 + ordinal)
        "month" -> "%04d-%02d".format(1970 + Math.floorDiv(ordinal, 12), 1 + Math.floorMod(ordinal, 12))
        "hour" -> Instant.EPOCH.plusSeconds(ordinal.toLong() * 3600)
            .atOffset(ZoneOffset.UTC)
            .let { "%04d-%02d-%02d-%02d".format(it.year, it.monthValue, it.dayOfMonth, it.hour) }
        else -> decoded.display
    }
}

/**
 * One partition field's range across a whole manifest, decoded.
 *
 * This is the manifest-list side of partitioning. [DecodedPartition] says which partition a
 * single file belongs to; this says which partitions a whole manifest could contain, which is
 * the question a scan asks before deciding to open it.
 */
data class PartitionSummary(
    val field: PartitionField,
    val type: IcebergType,
    val lower: DecodedValue?,
    val upper: DecodedValue?,
    val containsNull: Boolean,
    /** Null when the writer did not record it, which is not the same as false. */
    val containsNan: Boolean?,
) {
    // `this.field`, not `field`: inside a property accessor the bare name is Kotlin's backing
    // field, so it resolves to this property rather than to the constructor parameter. The name
    // matches PartitionValue.field deliberately, which is why it is qualified rather than renamed.
    val humanLower: String? get() = lower?.let { humanPartitionValue(this.field.transformName, it) }
    val humanUpper: String? get() = upper?.let { humanPartitionValue(this.field.transformName, it) }

    /** True when the manifest holds exactly one value of this field, so it names a partition. */
    val isSingleValue: Boolean get() = lower != null && upper != null && humanLower == humanUpper
}

/**
 * Pairs `manifest_file.partitions` with the spec it describes.
 *
 * The pairing is **positional** — the summaries carry no field ids — so a spec of the wrong
 * length silently mislabels every field rather than failing. When the two disagree, nothing is
 * decoded: a summary attributed to the wrong partition field is worse than an absent one,
 * because it reads as an answer.
 *
 * Bounds use each field's result type, the same resolution [decodePartition] uses, so a
 * `bucket[N]` range decodes as ints and a `day` range as dates.
 */
fun decodePartitionSummaries(
    summaries: List<PartitionFieldSummary>?,
    spec: PartitionSpec?,
    schema: IcebergSchemaModel?,
): List<PartitionSummary> {
    if (summaries == null || spec == null) return emptyList()
    if (summaries.size != spec.fields.size) return emptyList()
    return spec.fields.mapIndexed { index, field ->
        val summary = summaries[index]
        val type = partitionResultType(field.transformName, field.sourceId?.let { schema?.typeOf(it) })
        PartitionSummary(
            field = field,
            type = type,
            lower = summary.lowerBound?.takeIf { it.isNotEmpty() }?.let { decodeSingleValue(it, type) },
            upper = summary.upperBound?.takeIf { it.isNotEmpty() }?.let { decodeSingleValue(it, type) },
            containsNull = summary.containsNull,
            containsNan = summary.containsNan,
        )
    }
}
