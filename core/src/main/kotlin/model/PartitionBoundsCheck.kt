package model

/**
 * A data file's partition tuple against its own column bounds — two things the manifest entry
 * records about one file, and the one pair nothing on a read path compares.
 *
 * A scan prunes on the partition (`ManifestEvaluator` over the manifest's summaries, then the
 * entry's tuple) before it looks at a bound, so a file registered under the wrong partition is
 * skipped for the value its rows actually hold and read for one they do not — with nothing
 * failing, because the partition is the manifest's statement and the rows are never asked.
 * `add_files` and `migrate` take the partition from the directory a file sits in, a hand-built
 * `DataFile` takes whatever it was given, and Paimon's `_PARTITION` is written by the same
 * writer as the file. The bounds are the check: every row's source value transforms to the
 * partition value, so the transform of the lower bound and of the upper bound must both be it.
 *
 * Exact for a number, a date or a timestamp, whose bounds are the values. **Contained, not
 * equal, for a string**: Iceberg truncates a string bound to sixteen characters and increments
 * the upper one (`write.metadata.metrics.default`), so the transformed partition value has to
 * lie within the transformed bounds and a value sharing a sixteen-character prefix with the
 * right one would pass — the same one-sidedness the statistics check has. `bucket[N]` is
 * decidable only where the bounds are one value, since a range of values says nothing about a
 * bucket; a null partition value means every row's source is null, and a non-null one that
 * every row is not. `void` records nothing.
 */
enum class PartitionFieldVerdict { AGREES, DISAGREES, NOT_CHECKED }

data class PartitionFieldCheck(
    /** The partition field's name — `d_day`, `dt`. */
    val field: String,
    val transform: String,
    /** The source column the bounds are read from. */
    val source: String,
    /** The recorded partition value, as the path shows it. */
    val recorded: String,
    /** The transformed bounds — one value, or `a … b` where the two differ; null when not transformed. */
    val fromBounds: String?,
    val verdict: PartitionFieldVerdict,
    val reason: String,
) {
    val agrees: Boolean? get() = when (verdict) {
        PartitionFieldVerdict.AGREES -> true
        PartitionFieldVerdict.DISAGREES -> false
        PartitionFieldVerdict.NOT_CHECKED -> null
    }
}

/**
 * One partition field from the pieces both formats have: the recorded value (null for the null
 * partition; [recordedDecoded] false when it could not be read), the source column's bounds and
 * its null and value counts (Paimon records no value count, and every row holds one value, so
 * the caller passes the row count).
 */
fun checkPartitionField(
    field: String,
    transform: String,
    source: String,
    recorded: Any?,
    recordedShown: String,
    recordedDecoded: Boolean = true,
    lower: Any?,
    upper: Any?,
    nullCount: Long?,
    valueCount: Long?,
): PartitionFieldCheck {
    fun result(verdict: PartitionFieldVerdict, reason: String, fromBounds: String? = null) =
        PartitionFieldCheck(field, transform, source, recordedShown, fromBounds, verdict, reason)
    val name = transform.trim()
    if (!recordedDecoded) return result(PartitionFieldVerdict.NOT_CHECKED, "the partition value could not be decoded")
    if (name == "void") return result(PartitionFieldVerdict.NOT_CHECKED, "void records nothing about the rows")
    val allNull = nullCount != null && valueCount != null && valueCount > 0 && nullCount == valueCount
    val someNull = nullCount != null && nullCount > 0

    if (recorded == null) {
        // The null partition: every row's source value is null, whatever the transform.
        return when {
            allNull -> result(PartitionFieldVerdict.AGREES, "every row's $source is null, which is what the null partition holds")
            lower != null || upper != null -> result(
                PartitionFieldVerdict.DISAGREES,
                "$source holds values (${showPartitionValue(lower)} … ${showPartitionValue(upper)}), and the null partition holds only nulls",
                "${showPartitionValue(lower)} … ${showPartitionValue(upper)}",
            )
            nullCount != null && valueCount != null && valueCount > 0 -> result(PartitionFieldVerdict.DISAGREES, "${valueCount - nullCount} of $valueCount rows hold a $source, and the null partition holds only nulls")
            else -> result(PartitionFieldVerdict.NOT_CHECKED, "no bounds or counts recorded for $source")
        }
    }
    if (allNull) return result(PartitionFieldVerdict.DISAGREES, "every row's $source is null, so the partition value should be null and not $recordedShown")
    if (lower == null || upper == null) return result(PartitionFieldVerdict.NOT_CHECKED, "no bounds recorded for $source")
    if (someNull) {
        return result(
            PartitionFieldVerdict.DISAGREES,
            "$nullCount of the rows have a null $source, and a null belongs in the null partition, not under $recordedShown",
            if (compareValues(lower, upper) == 0) showPartitionValue(lower) else "${showPartitionValue(lower)} … ${showPartitionValue(upper)}",
        )
    }

    bucketCount(name)?.let { buckets ->
        if (compareValues(lower, upper) != 0) {
            return result(PartitionFieldVerdict.NOT_CHECKED, "$source spans ${showPartitionValue(lower)} … ${showPartitionValue(upper)}, and a range of values says nothing about a bucket")
        }
        val bucket = BucketTransform.bucketOf(lower, buckets)
            ?: return result(PartitionFieldVerdict.NOT_CHECKED, "a ${lower.javaClass.simpleName} has no bucket transform")
        val agrees = compareValues(bucket, recorded) == 0
        return result(
            if (agrees) PartitionFieldVerdict.AGREES else PartitionFieldVerdict.DISAGREES,
            if (agrees) "the one value ${showPartitionValue(lower)} falls in bucket $bucket" else "the one value ${showPartitionValue(lower)} falls in bucket $bucket, not $recordedShown",
            "$bucket",
        )
    }

    val bridge = transformBridge(name) ?: return result(PartitionFieldVerdict.NOT_CHECKED, "$name is not a transform this applies to a bound")
    val low = bridge(lower) ?: return result(PartitionFieldVerdict.NOT_CHECKED, "a ${lower.javaClass.simpleName} bound cannot be transformed by $name")
    val high = bridge(upper) ?: return result(PartitionFieldVerdict.NOT_CHECKED, "a ${upper.javaClass.simpleName} bound cannot be transformed by $name")
    val fromBounds = if (compareValues(low, high) == 0) showPartitionValue(low) else "${showPartitionValue(low)} … ${showPartitionValue(high)}"
    val belowLow = compareValues(recorded, low) ?: return result(PartitionFieldVerdict.NOT_CHECKED, "a ${low.javaClass.simpleName} and a ${recorded.javaClass.simpleName} cannot be compared", fromBounds)
    val aboveHigh = compareValues(recorded, high) ?: return result(PartitionFieldVerdict.NOT_CHECKED, "a ${high.javaClass.simpleName} and a ${recorded.javaClass.simpleName} cannot be compared", fromBounds)
    // A string bound is a truncated prefix and an incremented one, so the value can only be held to lie between them.
    val stringy = low is String || high is String
    val agrees = if (stringy) belowLow >= 0 && aboveHigh <= 0 else belowLow == 0 && aboveHigh == 0
    return when {
        agrees && stringy && compareValues(low, high) != 0 -> result(PartitionFieldVerdict.AGREES, "$recordedShown lies within the bounds, which a string bound can only be held to contain", fromBounds)
        agrees -> result(PartitionFieldVerdict.AGREES, "both bounds of $source transform to $recordedShown", fromBounds)
        else -> result(PartitionFieldVerdict.DISAGREES, "the bounds of $source transform to $fromBounds, not $recordedShown", fromBounds)
    }
}

/** Every field of an Iceberg entry's decoded partition against the entry's column statistics, matched by the field's source id. */
fun partitionBoundsChecks(partition: DecodedPartition, stats: List<ColumnStats>): List<PartitionFieldCheck> =
    partition.values.map { value ->
        val field = value.field
        val stat = field.sourceId?.let { id -> stats.firstOrNull { it.fieldId == id } }
        val source = stat?.displayName ?: field.sourceId?.let { "field $it" } ?: "?"
        checkPartitionField(
            field = field.name ?: "?",
            transform = field.transformName,
            source = source,
            recorded = value.stored.value,
            recordedShown = value.human,
            recordedDecoded = value.stored.error == null,
            lower = stat?.lowerBound?.takeIf { it.error == null }?.value,
            upper = stat?.upperBound?.takeIf { it.error == null }?.value,
            nullCount = stat?.nullValueCount,
            valueCount = stat?.valueCount,
        )
    }

/** Every key of a Paimon entry's `_PARTITION` — identity, by name — against the file's `_VALUE_STATS`, with the row count standing as the value count. */
fun paimonPartitionBoundsChecks(partition: DecodedPaimonPartition, bounds: List<PaimonColumnBounds>?, rowCount: Long?): List<PartitionFieldCheck> =
    partition.values.map { value ->
        val bound = bounds?.firstOrNull { it.name == value.name }?.takeIf { it.decoded }
        checkPartitionField(
            field = value.name,
            transform = "identity",
            source = value.name,
            recorded = value.value,
            recordedShown = value.display,
            lower = bound?.min,
            upper = bound?.max,
            nullCount = bound?.nullCount,
            valueCount = rowCount,
        )
    }

private fun showPartitionValue(v: Any?): String = v?.toString() ?: "null"
