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
 * - `bucket[N]` is not monotonic, so no range crosses it — but **equality does**, and that is now
 *   evaluated: `c = v` implies `bucket(c) = bucket(v)`, and the summary records the range of
 *   bucket numbers the manifest holds. The reason it was declined before was that computing the
 *   bucket meant reproducing Iceberg's murmur3, and a hash written from a spec agrees with itself
 *   long before it agrees with the writer. [BucketTransform] does not reproduce it — it calls the
 *   same Guava function Iceberg's own `Bucket` transform calls — and `BucketTransformTest` checks
 *   the result against the bucket numbers Spark recorded, at two different bucket counts. Every
 *   other operator on a bucket field still reports that it did not evaluate, because a range of
 *   bucket numbers says nothing whatever about a range of values.
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

    /** `LIKE`, carrying the pattern rather than a value. See [likePattern] for what can be proved. */
    LIKE("like"),
    NOT_LIKE("not like"),
    ;

    /**
     * The operator that accepts exactly the rows this one rejects.
     *
     * Total on purpose: every operator here has an exact opposite, which is what lets
     * [ScanFilter.pushNegation] remove every `NOT` rather than leave one to disable the pruning
     * underneath it. Sound with nulls for the same reason SQL's rewrite is — a null column
     * satisfies neither `= 5` nor `<> 5`, and pruning asks only whether a row *could* match.
     */
    val negated: PredicateOp
        get() = when (this) {
            EQ -> NOT_EQ
            NOT_EQ -> EQ
            LT -> GTE
            LTE -> GT
            GT -> LTE
            GTE -> LT
            IS_NULL -> IS_NOT_NULL
            IS_NOT_NULL -> IS_NULL
            LIKE -> NOT_LIKE
            NOT_LIKE -> LIKE
        }

    override fun toString(): String = symbol
}

/**
 * What a `LIKE` pattern fixes at the front, and whether that is all it says.
 *
 * Bounds are a range of strings, so the only thing a pattern can be disproved against is the text
 * it pins at the *start*: the values beginning with `alp` are one contiguous interval in the order
 * the bounds record, and `%pha` or `_lpha` pin nothing there at all. [prefix] is therefore the text
 * before the first wildcard, and a pattern that begins with one has none.
 *
 * [exactlyPrefix] is the second, stricter question, and only `NOT LIKE` asks it. Proving a `LIKE`
 * empty needs "no value here starts with the prefix", which is implied by any pattern that fixes
 * one. Proving a `NOT LIKE` empty needs the opposite direction — *every* value matches the pattern
 * — and "every value starts with `abc`" does not mean every value matches `abc_`, since `abcde`
 * starts with it and matches nothing of the kind. So that proof is offered only for `abc%`, where
 * starting with the prefix and matching are the same set.
 *
 * `%` and `_` are the wildcards; there is no `ESCAPE` clause here, so a pattern meaning to match a
 * literal `%` is read as one that fixes the text before it. That is the safe direction — a wider
 * matching set can only produce fewer proofs, never a wrong one. A pattern holding no wildcard is
 * equality, so all of it is the prefix and none of it is [exactlyPrefix]: every value starting with
 * `alpha` is not every value *being* `alpha`.
 */
internal data class LikePattern(val prefix: String, val exactlyPrefix: Boolean)

internal fun likePattern(pattern: String): LikePattern? {
    val stop = pattern.indexOfFirst { it == '%' || it == '_' }
    if (stop == 0 || pattern.isEmpty()) return null
    // No wildcard at all is equality, and the whole pattern is the prefix. The clause parser reads
    // that as `=` before it gets here, which is stronger; the row form can still produce it, and
    // answering "pins no text at the start" about `alpha` would be nonsense on the reader's screen.
    val prefix = if (stop < 0) pattern else pattern.take(stop)
    return LikePattern(prefix, exactlyPrefix = pattern == "$prefix%")
}

/**
 * `LIKE` and `NOT LIKE` against one pair of recorded string bounds.
 *
 * Shared by both stages, because the reasoning is about the bounds and not about what recorded
 * them — a file's column statistics and an identity partition summary are the same two strings for
 * this purpose. The comparison runs over **the shorter of the prefix and the bound**, which is what
 * keeps it sound against bounds that are themselves truncated: Iceberg truncates string metrics at
 * 16 characters by default, and a `truncate[W]` partition value is a prefix by construction. A
 * bound compared over only the characters it has still orders every value behind it.
 *
 * [boundsAreTheValues] is false where a transform folded many source values into one recorded
 * value. It costs only the `NOT LIKE` proof, which is the one that needs every value to match.
 */
private fun evaluatePrefixTerm(
    predicate: ScanPredicate,
    lower: String,
    upper: String,
    lowerShown: String,
    upperShown: String,
    boundsAreTheValues: Boolean,
    foldedBy: String?,
    outcome: (TermEffect, String) -> PredicateOutcome,
): PredicateOutcome {
    val typed = predicate.literal.trim()
    val pattern = likePattern(typed) ?: return outcome(
        TermEffect.NOT_EVALUATED,
        "'$typed' pins no text at the start, and a range of strings can only rule out a pattern " +
            "that does",
    )
    val prefix = pattern.prefix
    val range = "$lowerShown … $upperShown"

    fun heads(bound: String): Pair<String, String> {
        val shared = minOf(prefix.length, bound.length)
        return bound.take(shared) to prefix.take(shared)
    }

    if (predicate.op == PredicateOp.LIKE) {
        val (lowHead, lowWanted) = heads(lower)
        if (lowHead > lowWanted) {
            return outcome(TermEffect.SKIPS, "the lowest here is $lowerShown, already past '$prefix'")
        }
        val (highHead, highWanted) = heads(upper)
        if (highHead < highWanted) {
            return outcome(TermEffect.SKIPS, "the highest here is $upperShown, already below '$prefix'")
        }
        return outcome(TermEffect.KEEPS, "$range spans values starting '$prefix'")
    }

    if (!pattern.exactlyPrefix) {
        return outcome(
            TermEffect.NOT_EVALUATED,
            "'$typed' matches less than everything starting '$prefix', so bounds that all start " +
                "with it do not mean every row matches",
        )
    }
    if (!boundsAreTheValues) {
        return outcome(
            TermEffect.NOT_EVALUATED,
            "$foldedBy maps many values to one, so bounds that all start '$prefix' do not mean " +
                "every row does",
        )
    }
    val bothStart = lower.length >= prefix.length && upper.length >= prefix.length &&
        lower.startsWith(prefix) && upper.startsWith(prefix)
    return if (bothStart) {
        outcome(TermEffect.SKIPS, "every value here starts '$prefix', which is what is excluded")
    } else {
        outcome(TermEffect.KEEPS, "$range holds values that do not start '$prefix'")
    }
}

/** One condition of a scan filter, written against a source column the way a query writes it. */
data class ScanPredicate(val column: String, val op: PredicateOp, val literal: String = "") {
    // Quoted through the tokenizer's own rule, because this is what the clause editor is seeded
    // from: a literal holding a space came back as two and the reader's own filter read as an error.
    override fun toString(): String =
        if (op.takesLiteral) "$column ${op.symbol} ${quoteScanLiteral(literal)}" else "$column ${op.symbol}"
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

/**
 * Every predicate's outcome against one manifest, and the verdict that follows from them.
 *
 * [outcomes] is flat — one entry per predicate per partition field it matched — because that is
 * the explanation, and a reader checking a verdict wants every figure that was looked at. The
 * *verdict* is not a fold over that list: under an `OR` a single `SKIPS` proves nothing on its
 * own, so it comes from folding the filter's own tree ([ScanFilter.verdict]) and is passed in.
 */
data class ManifestPruneResult(
    val outcomes: List<PredicateOutcome>,
    val verdict: ScanVerdict = ScanVerdict.MIGHT_MATCH,
) {
    /**
     * One outcome that ruled *its own term* out, or null when none did.
     *
     * Not a claim about the manifest: under a disjunction one proved branch proves nothing, so
     * this can be non-null while [isSkipped] is false. Read [verdict] for the verdict. The panel
     * lists every proving outcome when the verdict is a skip rather than using this, and asserting
     * on it is what the tests do.
     */
    val skippedBy: PredicateOutcome? get() = outcomes.firstOrNull { it.effect == TermEffect.SKIPS }

    val isSkipped: Boolean get() = verdict == ScanVerdict.CANNOT_MATCH

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
): ManifestPruneResult = evaluatePruning(summaries, ScanFilter.of(predicates))

/**
 * The same, over a filter that may hold `OR`, `NOT` and grouping.
 *
 * Two passes on purpose. Every predicate is evaluated once, whatever the tree looks like, so the
 * explanation is complete and each figure is read once; the *verdict* is then folded over the tree
 * against those outcomes. Evaluating during the fold would skip the branches an `And` short-circuits
 * and leave the panel unable to say why the other half was never looked at.
 */
fun evaluatePruning(
    summaries: List<PartitionSummary>,
    filter: ScanFilter,
): ManifestPruneResult {
    val normalized = filter.pushNegation()
    val outcomes = normalized.predicates().flatMap { predicate ->
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
    // A term is proved when *any* partition field over its column proves it: a table partitioned
    // by year, month and hour of one timestamp gets three chances from one term.
    val bySkip = outcomes.groupBy({ it.predicate }, { it.effect == TermEffect.SKIPS })
    val verdict = normalized.verdict { predicate ->
        if (bySkip[predicate]?.any { it } == true) ScanVerdict.CANNOT_MATCH else ScanVerdict.MIGHT_MATCH
    }
    return ManifestPruneResult(outcomes, verdict)
}

private fun PartitionSummary.matches(column: String): Boolean {
    val wanted = column.trim()
    if (wanted.isEmpty()) return false
    return field.name.equals(wanted, ignoreCase = true) || sourceName.equals(wanted, ignoreCase = true)
}

/**
 * What one predicate does to one manifest, when the partition field is a `bucket[N]`.
 *
 * Split out because a bucket is decidable for exactly one operator and undecidable for the rest,
 * which is a different shape from every other transform. `c = v` implies `bucket(c) = bucket(v)`,
 * so a literal whose bucket falls outside the range of bucket numbers this manifest records is a
 * proof the manifest cannot hold a matching row — the same reasoning `EQ` uses on an
 * order-preserving field, minus the ordering.
 *
 * **Nothing else crosses.** `id > 1000` says nothing at all about bucket numbers: bucketing is a
 * hash, so the rows above 1000 are spread over every bucket and a manifest holding buckets 2–3
 * may hold any of them. `<>` is worse than useless — a manifest whose whole bucket range is
 * `bucket(v)` still holds other values that hash there. Both report that they did not evaluate,
 * which is a different statement from "would be read".
 */
private fun evaluateBucketTerm(
    summary: PartitionSummary,
    predicate: ScanPredicate,
    buckets: Int,
    fieldName: String,
    transform: String,
): PredicateOutcome {
    fun outcome(effect: TermEffect, reason: String) =
        PredicateOutcome(predicate, fieldName, transform, effect, reason)

    if (predicate.op != PredicateOp.EQ) {
        return outcome(
            TermEffect.NOT_EVALUATED,
            "$transform is a hash, so a range of bucket numbers says nothing about a range of " +
                "values — only equality crosses it",
        )
    }

    val sourceType = summary.sourceType ?: return outcome(
        TermEffect.NOT_EVALUATED,
        "the manifest carried no schema, so the type of '${predicate.column}' is unknown and its " +
            "literal cannot be read",
    )
    val literal = parseLiteral(predicate.literal, sourceType) ?: return outcome(
        TermEffect.NOT_EVALUATED,
        "'${predicate.literal}' is not a ${sourceType.typeName}, which is what '${predicate.column}' is",
    )
    val bucket = BucketTransform.bucketOf(literal, buckets) ?: return outcome(
        TermEffect.NOT_EVALUATED,
        "a ${sourceType.typeName} has no bucket transform — the spec excludes boolean and floating point",
    )

    val lower = summary.lower?.takeIf { !it.isError }?.value
    val upper = summary.upper?.takeIf { !it.isError }?.value
    if (lower == null || upper == null) {
        return outcome(
            TermEffect.NOT_EVALUATED,
            "$fieldName has no usable ${if (lower == null) "lower" else "upper"} bound recorded in " +
                "this manifest, so there is no range of buckets to rule the literal out against",
        )
    }

    val typed = predicate.literal.trim().removeSurrounding("'").removeSurrounding("\"")
    val applied = "$transform($typed) = $bucket"
    val range = "${summary.humanLower} … ${summary.humanUpper}"

    val below = compareValues(bucket, lower) ?: return outcome(
        TermEffect.NOT_EVALUATED,
        "this field's bounds are ${summary.type.typeName}, which cannot be compared with a bucket number",
    )
    val above = compareValues(bucket, upper) ?: return outcome(
        TermEffect.NOT_EVALUATED,
        "this field's bounds are ${summary.type.typeName}, which cannot be compared with a bucket number",
    )

    return when {
        below < 0 -> outcome(TermEffect.SKIPS, "$applied is below $range")
        above > 0 -> outcome(TermEffect.SKIPS, "$applied is above $range")
        // Inside the range is not a match: many values hash to one bucket, and the range is a
        // range of bucket numbers rather than a set of them.
        else -> outcome(TermEffect.KEEPS, "$applied is inside $range")
    }
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

    bucketCount(transform)?.let { buckets ->
        return evaluateBucketTerm(summary, predicate, buckets, fieldName, transform)
    }

    // A pattern is not a value, so it never reaches the bridge below: `truncate(alp%, 3)` would
    // truncate the pattern. It is answered against the bounds directly, for the two transforms that
    // keep a value's leading text — identity, and truncate, which *is* a prefix.
    if (predicate.op == PredicateOp.LIKE || predicate.op == PredicateOp.NOT_LIKE) {
        if (transform != "identity" && truncateWidth(transform) == null) {
            return outcome(
                TermEffect.NOT_EVALUATED,
                "'$transform' does not keep a value's leading text, so a pattern says nothing " +
                    "about the range it records",
            )
        }
        if (summary.type !is IcebergType.StringType) {
            return outcome(
                TermEffect.NOT_EVALUATED,
                "$fieldName records ${summary.type.typeName} bounds, and a pattern compares text",
            )
        }
        val low = summary.lower?.takeIf { !it.isError }?.value as? String
        val high = summary.upper?.takeIf { !it.isError }?.value as? String
        if (low == null || high == null) {
            return outcome(
                TermEffect.NOT_EVALUATED,
                "$fieldName has no usable text bounds in this manifest, so there is no range to " +
                    "rule the pattern out against",
            )
        }
        return evaluatePrefixTerm(
            // Rendered by the transform where it has a rendering; the bound itself otherwise.
            predicate, low, high, summary.humanLower ?: low, summary.humanUpper ?: high,
            boundsAreTheValues = transform == "identity", foldedBy = transform,
        ) { effect, reason -> outcome(effect, reason) }
    }

    val bridge = transformBridge(transform)
    if (bridge == null) {
        return outcome(
            TermEffect.NOT_EVALUATED,
            when {
                transform == "void" ->
                    "void writes null for every row, so its bounds describe nothing and it can never prune"
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

        PredicateOp.IS_NULL, PredicateOp.IS_NOT_NULL, PredicateOp.LIKE, PredicateOp.NOT_LIKE ->
            error("handled above")
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
 * What a scan does with one data file, once its manifest's verdict is taken into account.
 *
 * The four are not four verdicts on the file's contents — [NOT_REACHED] is a statement about the
 * manifest above it, and it is the interesting one. A file whose own bounds would have matched is
 * still never opened if the manifest listing it was ruled out, and a reader counting "how many
 * files does my query read" who ignores that gets a number the engine does not agree with.
 */
enum class FileFate {
    /** Its manifest was ruled out, so a scan never reads the entry, let alone the file. */
    NOT_REACHED,

    /** Reached, and its own recorded bounds prove it holds no matching row. */
    SKIPPED,

    /** Reached, and nothing proved it empty. */
    WOULD_BE_READ,

    /** Reached, and nothing could be evaluated — so "would be read" carries no information. */
    UNEVALUATED,
}

/** Every predicate's outcome against one data file, and what a scan does with it. */
data class FilePruneResult(val outcomes: List<PredicateOutcome>, val fate: FileFate) {
    /** The first outcome that proved the file cannot match, or null when none did. */
    val skippedBy: PredicateOutcome? get() = outcomes.firstOrNull { it.effect == TermEffect.SKIPS }
}

/**
 * Evaluates a conjunction of predicates against one data file's recorded column statistics.
 *
 * Simpler than the manifest case in one way and stronger in another. **Simpler:** a column
 * statistic's bounds are the source values themselves, so there is no transform to bridge a
 * literal through and no bucket to decline. **Stronger:** a file records `value_count` beside
 * `null_value_count`, so `IS NOT NULL` can be proved empty here — an all-null column holds no
 * non-null row — where a manifest summary records only "contains a null somewhere" and can
 * never rule that term out.
 *
 * The returned fate is never [FileFate.NOT_REACHED]: that is a fact about the manifest above the
 * file, which this function is not given. [evaluateScan] composes the two.
 */
fun evaluateFilePruning(stats: List<ColumnStats>, predicates: List<ScanPredicate>): FilePruneResult =
    evaluateFilePruning(stats, ScanFilter.of(predicates))

/** The same, over a filter that may hold `OR`, `NOT` and grouping. See the manifest twin. */
fun evaluateFilePruning(stats: List<ColumnStats>, filter: ScanFilter): FilePruneResult {
    val normalized = filter.pushNegation()
    val outcomes = normalized.predicates().map { predicate ->
        val matched = stats.firstOrNull { it.matches(predicate.column) }
        if (matched == null) {
            PredicateOutcome(
                predicate, fieldName = null, transform = null,
                effect = TermEffect.NOT_EVALUATED,
                reason = "this file records no statistics for a column called " +
                    "'${predicate.column}', so there is nothing here to rule the predicate out against",
            )
        } else {
            evaluateColumnTerm(matched, predicate)
        }
    }
    val skipped = outcomes.filter { it.effect == TermEffect.SKIPS }.map { it.predicate }.toSet()
    val proved = normalized.verdict { predicate ->
        if (predicate in skipped) ScanVerdict.CANNOT_MATCH else ScanVerdict.MIGHT_MATCH
    }
    val fate = when {
        proved == ScanVerdict.CANNOT_MATCH -> FileFate.SKIPPED
        outcomes.isNotEmpty() && outcomes.all { it.effect == TermEffect.NOT_EVALUATED } -> FileFate.UNEVALUATED
        else -> FileFate.WOULD_BE_READ
    }
    return FilePruneResult(outcomes, fate)
}

private fun ColumnStats.matches(column: String): Boolean {
    val wanted = column.trim()
    if (wanted.isEmpty()) return false
    return columnName?.equals(wanted, ignoreCase = true) == true
}

private fun evaluateColumnTerm(stats: ColumnStats, predicate: ScanPredicate): PredicateOutcome {
    val name = stats.displayName

    // transform is null rather than "identity": a column statistic is not a partition value, and
    // labelling it with a transform would invite the reader to look for a partition field.
    fun outcome(effect: TermEffect, reason: String) =
        PredicateOutcome(predicate, name, transform = null, effect = effect, reason = reason)

    when (predicate.op) {
        PredicateOp.IS_NULL -> return when (stats.nullValueCount) {
            null -> outcome(TermEffect.NOT_EVALUATED, "$name records no null count in this file")
            0L -> outcome(TermEffect.SKIPS, "$name records no null in this file")
            else -> outcome(TermEffect.KEEPS, "$name records ${stats.nullValueCount} null values here")
        }

        // The term a manifest can never rule out. A file says how many values it holds and how
        // many of them are null, so all-null is a fact rather than a possibility.
        PredicateOp.IS_NOT_NULL -> return when {
            stats.isAllNull -> outcome(
                TermEffect.SKIPS,
                "all ${stats.valueCount} values of $name here are null",
            )
            stats.nullValueCount == null || stats.valueCount == null -> outcome(
                TermEffect.NOT_EVALUATED,
                "$name records no ${if (stats.valueCount == null) "value" else "null"} count in " +
                    "this file, so it cannot be shown to be entirely null",
            )
            else -> outcome(
                TermEffect.KEEPS,
                "$name holds ${stats.valueCount - stats.nullValueCount} non-null values here",
            )
        }

        else -> Unit
    }

    val lower = stats.lowerBound?.takeIf { !it.isError }?.value
    val upper = stats.upperBound?.takeIf { !it.isError }?.value
    if (lower == null || upper == null) {
        return outcome(
            TermEffect.NOT_EVALUATED,
            if (stats.isAllNull) {
                "every value of $name here is null, so the file records no bounds for it"
            } else {
                "$name has no usable ${if (lower == null) "lower" else "upper"} bound recorded in " +
                    "this file, so there is no range to rule the predicate out against"
            },
        )
    }

    val type = stats.type
        ?: return outcome(
            TermEffect.NOT_EVALUATED,
            "the manifest carried no schema, so the type of '$name' is unknown and its literal " +
                "cannot be read",
        )

    // Same reason as the manifest stage: the literal here is a pattern, not a value of the column.
    if (predicate.op == PredicateOp.LIKE || predicate.op == PredicateOp.NOT_LIKE) {
        if (type !is IcebergType.StringType) {
            return outcome(
                TermEffect.NOT_EVALUATED,
                "'$name' is a ${type.typeName}, and a pattern compares text",
            )
        }
        val low = lower as? String
        val high = upper as? String
        if (low == null || high == null) {
            return outcome(TermEffect.NOT_EVALUATED, "$name's bounds here did not decode as text")
        }
        return evaluatePrefixTerm(
            predicate, low, high, stats.lowerBound.display, stats.upperBound.display,
            // A file's bounds are the values themselves, truncated at worst — which the shared
            // comparison already allows for — so `NOT LIKE` is answerable here and not under a
            // transform that folds.
            boundsAreTheValues = true, foldedBy = null,
        ) { effect, reason -> outcome(effect, reason) }
    }

    val literal = parseLiteral(predicate.literal, type)
        ?: return outcome(
            TermEffect.NOT_EVALUATED,
            "'${predicate.literal}' is not a ${type.typeName}, which is what '$name' is",
        )

    val shown = predicate.literal.trim().removeSurrounding("'").removeSurrounding("\"")
    val range = "${stats.lowerBound.display} … ${stats.upperBound.display}"

    val below = compareValues(literal, lower) ?: return outcome(
        TermEffect.NOT_EVALUATED,
        "a ${type.typeName} literal and this column's bounds cannot be compared",
    )
    val above = compareValues(literal, upper) ?: return outcome(
        TermEffect.NOT_EVALUATED,
        "a ${type.typeName} literal and this column's bounds cannot be compared",
    )

    // No transform stands between the literal and the bounds here, so every comparison is exact
    // and the injectivity caveat the manifest evaluator carries does not arise.
    return when (predicate.op) {
        PredicateOp.EQ -> when {
            below < 0 -> outcome(TermEffect.SKIPS, "$shown is below $range")
            above > 0 -> outcome(TermEffect.SKIPS, "$shown is above $range")
            else -> outcome(TermEffect.KEEPS, "$shown is inside $range")
        }

        PredicateOp.LT -> if (below <= 0) {
            outcome(TermEffect.SKIPS, "the lowest here is ${stats.lowerBound.display}, already at or past $shown")
        } else {
            outcome(TermEffect.KEEPS, "$range reaches below $shown")
        }

        PredicateOp.LTE -> if (below < 0) {
            outcome(TermEffect.SKIPS, "the lowest here is ${stats.lowerBound.display}, already past $shown")
        } else {
            outcome(TermEffect.KEEPS, "$range reaches below $shown")
        }

        PredicateOp.GT -> if (above >= 0) {
            outcome(TermEffect.SKIPS, "the highest here is ${stats.upperBound.display}, already at or below $shown")
        } else {
            outcome(TermEffect.KEEPS, "$range reaches above $shown")
        }

        PredicateOp.GTE -> if (above > 0) {
            outcome(TermEffect.SKIPS, "the highest here is ${stats.upperBound.display}, already below $shown")
        } else {
            outcome(TermEffect.KEEPS, "$range reaches above $shown")
        }

        // Bounds that meet prove every value equals the literal — no transform is folding many
        // values into one here, which is what stops the manifest evaluator answering this.
        PredicateOp.NOT_EQ -> when {
            below == 0 && above == 0 -> outcome(
                TermEffect.SKIPS,
                "every row here has $name = $shown, which is the value excluded",
            )
            else -> outcome(TermEffect.KEEPS, "$range holds more than $shown")
        }

        PredicateOp.IS_NULL, PredicateOp.IS_NOT_NULL, PredicateOp.LIKE, PredicateOp.NOT_LIKE ->
            error("handled above")
    }
}

/**
 * The whole filter, against every manifest and every data file the graph draws.
 *
 * One call rather than two, because a file's fate is not decided by its own bounds alone: a scan
 * that ruled a manifest out never opens the entries inside it, so a file whose statistics would
 * have matched is still not read. Composing that here is what keeps the two answers from
 * disagreeing in the panel that shows them side by side.
 */
data class ScanPlan(
    val manifests: Map<String, ManifestPruneResult>,
    val files: Map<String, FilePruneResult>,
    /**
     * Why no file's bounds were consulted, where the table's read does not consult them — a
     * Paimon table under data evolution, see [paimonFileBoundsWithheld]; null everywhere else.
     */
    val fileBoundsWithheld: String? = null,
) {
    val skippedManifests: Int get() = manifests.values.count { it.isSkipped }
    val readFiles: Int get() = files.values.count { it.fate == FileFate.WOULD_BE_READ }
    val skippedFiles: Int get() = files.values.count { it.fate == FileFate.SKIPPED }
    val unreachedFiles: Int get() = files.values.count { it.fate == FileFate.NOT_REACHED }
    /** Reached and not ruled out, with nothing to evaluate — a scan opens these as it opens [readFiles]. */
    val unevaluatedFiles: Int get() = files.values.count { it.fate == FileFate.UNEVALUATED }
}

/**
 * Each manifest is evaluated against **its own** partition spec, and each file against the schema
 * its own manifest carries. A table that has been repartitioned or had a column widened describes
 * its older artifacts by what was in force when they were written, and using the current one
 * mis-decodes silently — the same rule the bounds themselves follow.
 */
fun evaluateScan(graph: GraphModel, predicates: List<ScanPredicate>): ScanPlan =
    evaluateScan(graph, ScanFilter.of(predicates))

/** The same, over a filter that may hold `OR`, `NOT` and grouping. */
fun evaluateScan(graph: GraphModel, filter: ScanFilter): ScanPlan {
    if (filter.isEmpty()) return ScanPlan(emptyMap(), emptyMap())

    // Both formats, through one rule: a manifest is ruled out by the partition range its list
    // records, a file by the bounds it records about its own columns. Paimon's are read through
    // the bridge in PaimonPruningBridge.kt, which puts them in the vocabulary the rules use.
    val manifests = graph.nodes.asSequence()
        .mapNotNull { node ->
            when (node) {
                is GraphNode.ManifestNode -> node.id to evaluatePruning(node.partitionSummaries, filter)
                is GraphNode.PaimonManifestNode -> node.id to evaluatePruning(paimonPartitionSummaries(node), filter)
                else -> null
            }
        }
        .toMap()

    // Which manifest holds which file, from the edges rather than from the id: a file id encodes
    // its manifest today and that is a naming convention, not a contract.
    val manifestOf = graph.edges.asSequence()
        .filter { it.isStructural }
        .filter { graph.nodeById[it.fromId].let { n -> n is GraphNode.ManifestNode || n is GraphNode.PaimonManifestNode } }
        .associate { it.toId to it.fromId }

    // A Paimon table under data evolution reads a file stitched with the ones sharing its first
    // row id, so a column's bounds here may describe values a patch replaced; the scan consults
    // none of them, and neither does this.
    val withheld = paimonFileBoundsWithheld(graph)
    val files = graph.nodes.asSequence()
        .mapNotNull { node ->
            val stats = when (node) {
                is GraphNode.FileNode -> node.columnStats
                is GraphNode.PaimonDataFileNode -> if (withheld == null) paimonColumnStats(node) else null
                else -> return@mapNotNull null
            }
            val manifestSkipped = manifestOf[node.id]?.let { manifests[it]?.isSkipped } == true
            val own = if (stats != null) evaluateFilePruning(stats, filter) else unevaluatedFile(filter, withheld.orEmpty())
            node.id to if (manifestSkipped) own.copy(fate = FileFate.NOT_REACHED) else own
        }
        .toMap()

    return ScanPlan(manifests, files, withheld)
}

/** Every predicate not evaluated against the file, for one [reason] that is about the table rather than the file. */
private fun unevaluatedFile(filter: ScanFilter, reason: String): FilePruneResult {
    val outcomes = filter.pushNegation().predicates().map { predicate ->
        PredicateOutcome(predicate, fieldName = null, transform = null, effect = TermEffect.NOT_EVALUATED, reason = reason)
    }
    return FilePruneResult(outcomes, if (outcomes.isEmpty()) FileFate.WOULD_BE_READ else FileFate.UNEVALUATED)
}

private val GraphEdge.isStructural: Boolean get() = affectsLayout && !isSibling

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
    /** Every transform that reads this column, in the order the specs declare them. Empty when
     *  no partition field reads it, which is most columns of most tables. */
    val transforms: List<String>,
    /** True when some data file records bounds for this column. */
    val hasFileBounds: Boolean,
) {
    /**
     * True when some partition field over this column can rule a manifest out.
     *
     * A bucket counts: it prunes on equality only, but it prunes — which is why this is no longer
     * "a range can be intersected with". `void` is the only transform that can never eliminate
     * anything, because it writes null for every row.
     */
    val prunesManifests: Boolean get() = transforms.any { it != "void" }

    /** True when the column can eliminate a file even though no manifest turns on it. */
    val prunesFiles: Boolean get() = hasFileBounds

    /** True when a predicate on this column can rule *something* out. */
    val isPrunable: Boolean get() = prunesManifests || prunesFiles
}

/**
 * The columns a filter can usefully be written against, and what each of them can rule out.
 *
 * Two sources, because the two stages prune on different things. A **manifest** is ruled out by a
 * partition field's summary, so only a partitioned column reaches it. A **file** is ruled out by
 * its own recorded `lower_bounds` / `upper_bounds`, which every column of every file carries — so
 * `id > 1000` prunes no manifest at all and can still eliminate most of the files, which is where
 * "why did my query read four hundred files" usually ends.
 *
 * Offering the union rather than the partition columns alone is the point of the file stage. What
 * the list still will not offer is a column nothing records anything about: a predicate on one
 * produces a page of "would be read" that looks like an answer and is not one.
 */
fun prunableColumns(graph: GraphModel): List<PrunableColumn> {
    val transformsByColumn = LinkedHashMap<String, MutableList<PartitionSummary>>()
    graph.nodes.asSequence()
        .flatMap { node ->
            when (node) {
                is GraphNode.ManifestNode -> node.partitionSummaries.asSequence()
                is GraphNode.PaimonManifestNode -> paimonPartitionSummaries(node).asSequence()
                else -> emptySequence()
            }
        }
        .forEach { summary ->
            val name = summary.sourceName ?: return@forEach
            transformsByColumn.getOrPut(name) { mutableListOf() }.add(summary)
        }

    val boundedByColumn = LinkedHashMap<String, IcebergType>()
    graph.nodes.asSequence()
        .flatMap { node ->
            when (node) {
                is GraphNode.FileNode -> node.columnStats.asSequence()
                is GraphNode.PaimonDataFileNode -> paimonColumnStats(node).asSequence()
                else -> emptySequence()
            }
        }
        .forEach { stats ->
            val name = stats.columnName ?: return@forEach
            val type = stats.type ?: return@forEach
            if (stats.lowerBound != null || stats.upperBound != null || stats.valueCount != null) {
                boundedByColumn.putIfAbsent(name, type)
            }
        }

    // Partition columns first: they are the ones that prune before a manifest is even opened, and
    // a reader scanning the menu should meet the cheapest stage first.
    val names = LinkedHashSet(transformsByColumn.keys) + boundedByColumn.keys
    return names.map { name ->
        val summaries = transformsByColumn[name].orEmpty()
        PrunableColumn(
            name = name,
            type = summaries.firstOrNull()?.sourceType ?: boundedByColumn[name] ?: IcebergType.UnknownType,
            transforms = summaries.map { it.field.transformName.ifEmpty { "identity" } }.distinct(),
            hasFileBounds = name in boundedByColumn,
        )
    }
}
