package model

/**
 * A manifest's recorded partition summaries against the entries they summarise — the
 * `manifestTallies` rule applied to the one thing a scan reads *before* the counts: a manifest
 * is skipped or opened on `partitions[i].lower_bound` / `upper_bound` / `contains_null`, and
 * nothing on the read path checks them. `ManifestWriter.addEntry` folds every entry's partition
 * into the summary whatever its status (Iceberg 1.8.1: `stats.update` runs after the status
 * switch), so the counted side covers `DELETED` entries too, the way the entry count does.
 *
 * Each field's bounds are compared as values through [compareValues], not as text, and a field
 * is left uncounted when an entry's partition did not decode or two values have no order — an
 * uncounted field is not a disagreement. `contains_nan` is compared only where recorded, since
 * the writer may omit it; `lower_bound` is optional in the spec for a field with no non-null
 * value, so a recorded null is not compared either.
 */
data class PartitionSummaryTally(
    val field: String,
    /** `Lower bound`, `Upper bound`, `Contains null`, `Contains NaN`. */
    val figure: String,
    val recorded: String?,
    val counted: String?,
    /** Null when either side has nothing to say. */
    val agrees: Boolean?,
)

fun partitionSummaryTallies(summaries: List<PartitionSummary>, partitions: List<DecodedPartition?>): List<PartitionSummaryTally> =
    summaries.flatMapIndexed { i, summary ->
        val name = summary.field.name ?: "field ${summary.field.fieldId ?: i}"
        val decoded = partitions.map { it?.values?.getOrNull(i)?.stored?.takeIf { v -> !v.isError } }
        val allDecoded = decoded.none { it == null } && partitions.isNotEmpty()
        val values = decoded.filterNotNull().map { it.value }
        val nonNull = values.filterNotNull()
        fun yesNo(b: Boolean) = if (b) "yes" else "no"
        fun extreme(recorded: DecodedValue?, label: String, lower: Boolean): PartitionSummaryTally {
            val countedValue: Any? = if (!allDecoded || nonNull.isEmpty()) null else nonNull.reduceOrNull { a, b ->
                val c = compareValues(b, a) ?: return PartitionSummaryTally(name, label, recorded?.display, null, null)
                if ((c < 0) == lower) b else a
            }
            val countedText = countedValue?.let { v -> decoded.filterNotNull().firstOrNull { it.value == v }?.let { humanPartitionValue(summary.field.transformName, it) } ?: v.toString() }
            val recordedText = recorded?.let { if (lower) summary.humanLower else summary.humanUpper }
            val agrees = if (recorded == null || countedValue == null) null else compareValues(recorded.value, countedValue) == 0
            return PartitionSummaryTally(name, label, recordedText, countedText, agrees)
        }
        listOf(
            extreme(summary.lower, "Lower bound", lower = true),
            extreme(summary.upper, "Upper bound", lower = false),
            PartitionSummaryTally(name, "Contains null", yesNo(summary.containsNull), if (allDecoded) yesNo(values.any { it == null }) else null,
                if (allDecoded) summary.containsNull == values.any { it == null } else null),
            PartitionSummaryTally(name, "Contains NaN", summary.containsNan?.let(::yesNo), if (allDecoded) yesNo(nonNull.any { it.isNaN() }) else null,
                if (allDecoded && summary.containsNan != null) summary.containsNan == nonNull.any { it.isNaN() } else null),
        )
    }

private fun Any.isNaN(): Boolean = (this is Double && this.isNaN()) || (this is Float && this.isNaN())
