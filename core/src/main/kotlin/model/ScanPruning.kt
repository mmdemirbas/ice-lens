package model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Answers "which manifests would my query skip, and why" against the bounds a manifest list
 * already carries.
 *
 * This is the other half of partition pruning. `manifest_file.partitions` gives the range of each
 * partition field across a whole manifest, and the inspector shows it; what a reader actually
 * wants to know is whether *their* predicate can rule that range out. Iceberg computes the same
 * thing during scan planning and then throws it away — a query reports how many files it read,
 * never which ones it skipped or which predicate did the skipping — so this is not available from
 * any engine surface.
 *
 * ## What a predicate can and cannot prune
 *
 * A predicate is written against the **source** column; a manifest records bounds on the
 * **transformed** value. Bridging the two is the whole of the work here, and the bridge only
 * exists for transforms that preserve order:
 *
 * - `identity`, `year`, `month`, `day`, `hour`, `truncate[W]` are monotonic, so `c < v` implies
 *   `T(c) <= T(v)` and a range on the source becomes a range on the partition value.
 * - `bucket[N]` is not. Equality is still prunable in principle — a scan computes `bucket(v)` and
 *   compares — but that means reproducing Iceberg's 32-bit murmur3 over its own serialisation of
 *   the value, and a hash re-implemented from a spec agrees with itself long before it agrees
 *   with the writer. Until there is an oracle for it, a bucket field reports that it did not
 *   evaluate rather than a verdict that might be wrong.
 * - `void` writes null for every row and can never eliminate anything.
 *
 * Every one of those cases is reported, never dropped: a field that could not be evaluated says
 * so with its reason, because a manifest listed as "would be read" for want of an evaluator must
 * not look like one that was checked and kept.
 *
 * ## What the verdict means
 *
 * Predicates are combined with AND, which is what makes them prunable: one term proving the
 * manifest cannot match is enough to skip it, and that is exactly how a scan uses them. A term
 * that cannot eliminate the manifest is not evidence that the manifest matches — it only means
 * this manifest survives to be opened. `SKIPPED` is a proof; `READ` is the absence of one.
 */
enum class PredicateOp(val symbol: String, val takesLiteral: Boolean = true) {
    EQ("="),
    NOT_EQ("<>"),
    LT("<"),
    LTE("<="),
    GT(">"),
    GTE(">="),
    IS_NULL("is null", takesLiteral = false),
    IS_NOT_NULL("is not null", takesLiteral = false),
    ;

    override fun toString(): String = symbol
}

/** One condition of a scan filter, written against a source column the way a query writes it. */
data class ScanPredicate(val column: String, val op: PredicateOp, val literal: String = "") {
    override fun toString(): String =
        if (op.takesLiteral) "$column ${op.symbol} $literal" else "$column ${op.symbol}"
}

/** What one predicate did to one manifest. */
enum class TermEffect {
    /** Proved the manifest cannot hold a matching row. */
    SKIPS,

    /** Was evaluated against recorded bounds and could not rule the manifest out. */
    KEEPS,

    /** Was not evaluated — no matching field, no bounds recorded, or a transform not bridged. */
    NOT_EVALUATED,
}

/**
 * One predicate against one partition field of one manifest.
 *
 * [reason] is always populated and always names the numbers it used. A verdict a reader cannot
 * check is the thing this feature exists to replace.
 */
data class PredicateOutcome(
    val predicate: ScanPredicate,
    /** The partition field this was matched to, or null when the predicate matched nothing. */
    val fieldName: String?,
    val transform: String?,
    val effect: TermEffect,
    val reason: String,
)

/** Every predicate's outcome against one manifest, and the verdict that follows from them. */
data class ManifestPruneResult(val outcomes: List<PredicateOutcome>) {
    /** The first outcome that proved the manifest cannot match, or null when none did. */
    val skippedBy: PredicateOutcome? get() = outcomes.firstOrNull { it.effect == TermEffect.SKIPS }

    val isSkipped: Boolean get() = skippedBy != null

    /** True when nothing could be evaluated, so "would be read" carries no information. */
    val isUnevaluated: Boolean
        get() = outcomes.isNotEmpty() && outcomes.all { it.effect == TermEffect.NOT_EVALUATED }
}

/**
 * Evaluates a conjunction of predicates against one manifest's partition summaries.
 *
 * A predicate matches a partition field when it names either the field or the source column the
 * field transforms. Naming the source column matches *every* field over it, which is the
 * behaviour worth seeing: a table partitioned by `year(ts)`, `month(ts)` and `hour(ts)` gets
 * three chances to eliminate a manifest from one `ts >` term, and they do not all fire.
 */
fun evaluatePruning(
    summaries: List<PartitionSummary>,
    predicates: List<ScanPredicate>,
): ManifestPruneResult {
    val outcomes = predicates.flatMap { predicate ->
        val matched = summaries.filter { it.matches(predicate.column) }
        if (matched.isEmpty()) {
            listOf(
                PredicateOutcome(
                    predicate, fieldName = null, transform = null,
                    effect = TermEffect.NOT_EVALUATED,
                    reason = "no partition field reads a column called '${predicate.column}', so a " +
                        "scan prunes nothing with this term and reads every file to apply it",
                )
            )
        } else {
            matched.map { evaluateTerm(it, predicate) }
        }
    }
    return ManifestPruneResult(outcomes)
}

private fun PartitionSummary.matches(column: String): Boolean {
    val wanted = column.trim()
    if (wanted.isEmpty()) return false
    return field.name.equals(wanted, ignoreCase = true) || sourceName.equals(wanted, ignoreCase = true)
}

private fun evaluateTerm(summary: PartitionSummary, predicate: ScanPredicate): PredicateOutcome {
    val fieldName = summary.field.name ?: "field ${summary.field.fieldId}"
    val transform = summary.field.transformName.ifEmpty { "identity" }

    fun outcome(effect: TermEffect, reason: String) =
        PredicateOutcome(predicate, fieldName, transform, effect, reason)

    // Null is null under every transform, so this one needs no bridge — and it is the only term
    // that reads contains_null, which is recorded even when the bounds are not.
    when (predicate.op) {
        PredicateOp.IS_NULL -> return if (summary.containsNull) {
            outcome(TermEffect.KEEPS, "$fieldName records at least one null")
        } else {
            outcome(TermEffect.SKIPS, "$fieldName records no null, so no row here is null")
        }

        PredicateOp.IS_NOT_NULL -> return outcome(
            TermEffect.NOT_EVALUATED,
            "a manifest that holds nulls may hold non-nulls beside them, and the summary does not " +
                "say it is all null — so this term never eliminates a manifest",
        )

        else -> Unit
    }

    val bridge = transformBridge(transform)
    if (bridge == null) {
        return outcome(
            TermEffect.NOT_EVALUATED,
            when {
                transform == "void" ->
                    "void writes null for every row, so its bounds describe nothing and it can never prune"
                bucketCount(transform) != null ->
                    "$transform is not order-preserving, so a range says nothing about it; equality " +
                        "would need this tool to reproduce Iceberg's bucket hash, which it does not"
                else -> "'$transform' is not a transform this evaluator can translate a literal through"
            },
        )
    }

    val lower = summary.lower?.takeIf { !it.isError }?.value
    val upper = summary.upper?.takeIf { !it.isError }?.value
    if (lower == null || upper == null) {
        return outcome(
            TermEffect.NOT_EVALUATED,
            "$fieldName has no usable ${if (lower == null) "lower" else "upper"} bound recorded in " +
                "this manifest, so there is no range to rule the predicate out against",
        )
    }

    val sourceType = summary.sourceType
        ?: return outcome(
            TermEffect.NOT_EVALUATED,
            "the manifest carried no schema, so the type of '${predicate.column}' is unknown and its " +
                "literal cannot be read",
        )

    val literal = parseLiteral(predicate.literal, sourceType)
        ?: return outcome(
            TermEffect.NOT_EVALUATED,
            "'${predicate.literal}' is not a ${sourceType.typeName}, which is what " +
                "'${predicate.column}' is",
        )

    val transformed = bridge(literal)
        ?: return outcome(
            TermEffect.NOT_EVALUATED,
            "$transform does not apply to a ${sourceType.typeName}",
        )

    val shown = humanPartitionValue(transform, DecodedValue(transformed.toString(), transformed, summary.type, ByteArray(0)))
    val range = "${summary.humanLower} … ${summary.humanUpper}"
    val typed = predicate.literal.trim().removeSurrounding("'").removeSurrounding("\"")
    val applied = if (shown == typed) shown else "$transform($typed) = $shown"
    // identity is the one transform here that is injective, so a bound that equals the literal
    // still proves a strict comparison empty: every row is exactly v, and none is above it.
    val injective = transform == "identity"

    val below = compareValues(transformed, lower) ?: return outcome(
        TermEffect.NOT_EVALUATED,
        "a ${sourceType.typeName} literal and this field's ${summary.type.typeName} bounds cannot be compared",
    )
    val above = compareValues(transformed, upper) ?: return outcome(
        TermEffect.NOT_EVALUATED,
        "a ${sourceType.typeName} literal and this field's ${summary.type.typeName} bounds cannot be compared",
    )

    return when (predicate.op) {
        // c = v implies T(c) = T(v) for every transform here, so a T(v) outside the range is proof.
        PredicateOp.EQ -> when {
            below < 0 -> outcome(TermEffect.SKIPS, "$applied is below $range")
            above > 0 -> outcome(TermEffect.SKIPS, "$applied is above $range")
            else -> outcome(TermEffect.KEEPS, "$applied is inside $range")
        }

        // c < v and c <= v both fail everywhere once the smallest transformed value already
        // exceeds T(v), because the transform never decreases.
        PredicateOp.LT, PredicateOp.LTE -> {
            val strictEnough = below < 0 || (injective && predicate.op == PredicateOp.LT && below == 0)
            if (strictEnough) {
                outcome(
                    TermEffect.SKIPS,
                    "the lowest here is ${summary.humanLower}, already past $applied",
                )
            } else {
                outcome(TermEffect.KEEPS, "$range reaches below $applied")
            }
        }

        PredicateOp.GT, PredicateOp.GTE -> {
            val strictEnough = above > 0 || (injective && predicate.op == PredicateOp.GT && above == 0)
            if (strictEnough) {
                outcome(
                    TermEffect.SKIPS,
                    "the highest here is ${summary.humanUpper}, already below $applied",
                )
            } else {
                outcome(TermEffect.KEEPS, "$range reaches above $applied")
            }
        }

        // Only an identity partition can prove a manifest holds nothing but v. Under day or
        // truncate a single partition value covers many source values, so bounds that meet say
        // nothing about whether some row differs from v.
        PredicateOp.NOT_EQ -> when {
            transform != "identity" -> outcome(
                TermEffect.NOT_EVALUATED,
                "$transform maps many values to one, so bounds that meet do not mean every row " +
                    "equals ${predicate.literal.trim()}",
            )
            below == 0 && above == 0 -> outcome(
                TermEffect.SKIPS,
                "every row here has $fieldName = $shown, which is the value excluded",
            )
            else -> outcome(TermEffect.KEEPS, "$range holds more than $applied")
        }

        PredicateOp.IS_NULL, PredicateOp.IS_NOT_NULL -> error("handled above")
    }
}

/**
 * The function that turns a source value into the value a manifest records for it, or null when
 * the transform has no order-preserving bridge — see the class comment for which and why.
 */
private fun transformBridge(transform: String): ((Any) -> Comparable<*>?)? {
    truncateWidth(transform)?.let { width -> return { value -> truncate(value, width) } }
    return when (transform.trim()) {
        "identity" -> { value -> value as? Comparable<*> }
        "year" -> { value -> yearsFromEpoch(value) }
        "month" -> { value -> monthsFromEpoch(value) }
        "day" -> { value -> dateOf(value) }
        "hour" -> { value -> hoursFromEpoch(value) }
        else -> null
    }
}

private fun dateOf(value: Any): LocalDate? = when (value) {
    is LocalDate -> value
    is LocalDateTime -> value.toLocalDate()
    is Instant -> LocalDate.ofInstant(value, ZoneOffset.UTC)
    else -> null
}

private fun dateTimeOf(value: Any): LocalDateTime? = when (value) {
    is LocalDate -> value.atStartOfDay()
    is LocalDateTime -> value
    is Instant -> LocalDateTime.ofInstant(value, ZoneOffset.UTC)
    else -> null
}

private fun yearsFromEpoch(value: Any): Int? = dateOf(value)?.let { it.year - 1970 }

private fun monthsFromEpoch(value: Any): Int? =
    dateOf(value)?.let { (it.year - 1970) * 12 + (it.monthValue - 1) }

private fun hoursFromEpoch(value: Any): Int? = dateTimeOf(value)?.let {
    Math.floorDiv(it.toEpochSecond(ZoneOffset.UTC), 3600L).toInt()
}

/** Iceberg's truncate: floor to a multiple of the width for numbers, a prefix for text. */
private fun truncate(value: Any, width: Int): Comparable<*>? {
    if (width <= 0) return null
    return when (value) {
        is Int -> value - Math.floorMod(value, width)
        is Long -> value - Math.floorMod(value, width.toLong())
        is String -> value.take(width)
        is BigDecimal -> {
            val unscaled = value.unscaledValue()
            val w = java.math.BigInteger.valueOf(width.toLong())
            BigDecimal(unscaled.subtract(unscaled.mod(w)), value.scale())
        }
        else -> null
    }
}

/**
 * Reads a typed value out of what the reader typed.
 *
 * Returns null when the text is not a value of that type, which the caller reports rather than
 * guessing at — a predicate silently read as something else would produce a confident wrong
 * verdict, which is worse here than no verdict.
 */
fun parseLiteral(text: String, type: IcebergType): Any? {
    val trimmed = text.trim().removeSurrounding("'").removeSurrounding("\"")
    if (trimmed.isEmpty() && type !is IcebergType.StringType) return null
    return runCatching {
        when (type) {
            is IcebergType.BooleanType -> trimmed.lowercase().let {
                when (it) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
            }
            is IcebergType.IntType -> trimmed.toInt()
            is IcebergType.LongType -> trimmed.toLong()
            is IcebergType.FloatType -> trimmed.toFloat()
            is IcebergType.DoubleType -> trimmed.toDouble()
            is IcebergType.DateType -> LocalDate.parse(trimmed)
            is IcebergType.TimeType -> LocalTime.parse(trimmed)
            is IcebergType.TimestampType ->
                if (trimmed.endsWith("Z") || trimmed.contains('+')) Instant.parse(trimmed)
                else LocalDateTime.parse(trimmed.replace(' ', 'T'))
            is IcebergType.StringType -> trimmed
            is IcebergType.UuidType -> UUID.fromString(trimmed)
            is IcebergType.DecimalType -> BigDecimal(trimmed)
            else -> null
        }
    }.getOrNull()
}

/**
 * Orders two decoded values, or null when they are of kinds that have no order between them.
 *
 * Numbers are widened rather than required to match: a bound decoded as `Int` and a literal read
 * as `Long` describe the same magnitude, and refusing to compare them would report "cannot
 * evaluate" for an ordinary `truncate` on a long column.
 */
fun compareValues(left: Any?, right: Any?): Int? {
    if (left == null || right == null) return null
    return when {
        left is BigDecimal || right is BigDecimal -> {
            val l = left.toBigDecimalOrNull() ?: return null
            val r = right.toBigDecimalOrNull() ?: return null
            l.compareTo(r)
        }
        left is Number && right is Number ->
            if (left.isIntegral() && right.isIntegral()) left.toLong().compareTo(right.toLong())
            else left.toDouble().compareTo(right.toDouble())
        left is String && right is String -> left.compareTo(right)
        left is Boolean && right is Boolean -> left.compareTo(right)
        left is LocalDate && right is LocalDate -> left.compareTo(right)
        left is LocalTime && right is LocalTime -> left.compareTo(right)
        left is LocalDateTime && right is LocalDateTime -> left.compareTo(right)
        left is Instant && right is Instant -> left.compareTo(right)
        // A timestamptz bound and a timestamp literal, or the reverse: compare at UTC, which is
        // the offset the bound was encoded against anyway.
        left is Instant && right is LocalDateTime -> LocalDateTime.ofInstant(left, ZoneOffset.UTC).compareTo(right)
        left is LocalDateTime && right is Instant -> left.compareTo(LocalDateTime.ofInstant(right, ZoneOffset.UTC))
        left is UUID && right is UUID -> left.compareTo(right)
        else -> null
    }
}

private fun Number.isIntegral(): Boolean = this is Int || this is Long || this is Short || this is Byte

private fun Any.toBigDecimalOrNull(): BigDecimal? = when (this) {
    is BigDecimal -> this
    is Int -> BigDecimal(this)
    is Long -> BigDecimal(this)
    is Float -> BigDecimal.valueOf(this.toDouble())
    is Double -> BigDecimal.valueOf(this)
    else -> null
}

/**
 * Evaluates a filter against every manifest the graph draws, keyed by node id.
 *
 * Each manifest is evaluated against **its own** partition spec, because that is what the
 * summaries were written under. A table that has been repartitioned describes its older files by
 * the spec in force when they were written, and using the current one mis-decodes silently — the
 * same rule the bounds themselves follow.
 */
fun evaluatePruning(graph: GraphModel, predicates: List<ScanPredicate>): Map<String, ManifestPruneResult> {
    if (predicates.isEmpty()) return emptyMap()
    return graph.nodes.asSequence()
        .filterIsInstance<GraphNode.ManifestNode>()
        .associate { it.id to evaluatePruning(it.partitionSummaries, predicates) }
}

/**
 * The columns a filter can be written against: every source column some partition field reads,
 * with the transforms that read it.
 *
 * Offered rather than free text because a column the table is not partitioned on prunes nothing,
 * and a reader who types one gets a list of manifests that all say "would be read" — which looks
 * like an answer and is not one.
 */
data class PrunableColumn(
    val name: String,
    val type: IcebergType,
    /** Every transform that reads this column, in the order the specs declare them. */
    val transforms: List<String>,
) {
    /** True when nothing here can rule a manifest out, whatever the predicate says. */
    val isPrunable: Boolean get() = transforms.any { it != "void" && bucketCount(it) == null }
}

fun prunableColumns(graph: GraphModel): List<PrunableColumn> {
    val byColumn = LinkedHashMap<String, MutableList<PartitionSummary>>()
    graph.nodes.asSequence()
        .filterIsInstance<GraphNode.ManifestNode>()
        .flatMap { it.partitionSummaries.asSequence() }
        .forEach { summary ->
            val name = summary.sourceName ?: return@forEach
            byColumn.getOrPut(name) { mutableListOf() }.add(summary)
        }
    return byColumn.map { (name, summaries) ->
        PrunableColumn(
            name = name,
            type = summaries.first().sourceType ?: IcebergType.UnknownType,
            transforms = summaries.map { it.field.transformName.ifEmpty { "identity" } }.distinct(),
        )
    }
}
