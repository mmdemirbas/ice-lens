package model

/**
 * A scan filter as a boolean expression rather than a list of terms.
 *
 * The list form could only ever be a conjunction, which answers "why did this read so much" and
 * cannot reproduce a real query's plan — `WHERE a = 1 OR b = 2` is the shape every partitioned
 * table gets asked about, and ANDing its two halves is a different question with a different
 * answer.
 *
 * ### Why `Not` never survives evaluation
 *
 * Pruning is a one-sided proof: recorded bounds can show that a manifest or file **cannot** hold a
 * matching row, and can never show that it does. Negating that proof yields nothing — `NOT` of
 * "cannot match" is not "must match", it is "no longer proved". So a `Not` left in the tree would
 * turn every proof underneath it into a non-proof and quietly stop pruning.
 *
 * [pushNegation] removes it instead, by De Morgan through the connectives and by negating the
 * *operator* at each leaf, which is what Iceberg's own `Expressions.not` does. Every operator here
 * has an exact negation, so nothing is lost. The rewrite is sound with nulls for the same reason
 * SQL's is: a row whose column is null satisfies neither `a = 5` nor `a <> 5`, and pruning asks
 * only whether some row *could* match.
 */
sealed interface ScanFilter {

    /** One condition. Several partition fields may read its column, and any of them may prove it. */
    data class Term(val predicate: ScanPredicate) : ScanFilter

    /** Proved empty when **any** branch is. */
    data class And(val terms: List<ScanFilter>) : ScanFilter

    /** Proved empty only when **every** branch is — one branch that might match is enough to read. */
    data class Or(val terms: List<ScanFilter>) : ScanFilter

    /** Rewritten away by [pushNegation] before anything is evaluated. */
    data class Not(val term: ScanFilter) : ScanFilter

    companion object {
        /** The conjunction the old list form meant. An empty list is a filter that proves nothing. */
        fun of(predicates: List<ScanPredicate>): ScanFilter = And(predicates.map(::Term))
    }
}

/** Every predicate in the tree, in the order they were written. */
fun ScanFilter.predicates(): List<ScanPredicate> = when (this) {
    is ScanFilter.Term -> listOf(predicate)
    is ScanFilter.And -> terms.flatMap { it.predicates() }
    is ScanFilter.Or -> terms.flatMap { it.predicates() }
    is ScanFilter.Not -> term.predicates()
}

/** Whether the filter says anything at all. An empty `And` is what "no filter" parses to. */
fun ScanFilter.isEmpty(): Boolean = predicates().isEmpty()

/**
 * The same filter with every [ScanFilter.Not] pushed into the leaves and removed.
 *
 * De Morgan through the connectives, [PredicateOp.negated] at each leaf. Idempotent, and the
 * result contains no `Not` — which is what lets [ScanFilter.verdict] be a plain two-way fold.
 */
fun ScanFilter.pushNegation(negated: Boolean = false): ScanFilter = when (this) {
    is ScanFilter.Term ->
        if (negated) ScanFilter.Term(predicate.copy(op = predicate.op.negated)) else this

    is ScanFilter.And ->
        if (negated) ScanFilter.Or(terms.map { it.pushNegation(true) })
        else ScanFilter.And(terms.map { it.pushNegation(false) })

    is ScanFilter.Or ->
        if (negated) ScanFilter.And(terms.map { it.pushNegation(true) })
        else ScanFilter.Or(terms.map { it.pushNegation(false) })

    is ScanFilter.Not -> term.pushNegation(!negated)
}

/** What a filter proved about one artifact. Only [CANNOT_MATCH] is a proof. */
enum class ScanVerdict {
    /** The recorded figures rule out every row this filter could accept. */
    CANNOT_MATCH,

    /** Nothing was proved — either a term failed to rule it out, or nothing could be evaluated. */
    MIGHT_MATCH,
}

/**
 * Folds a leaf verdict up the tree.
 *
 * [leaf] answers one term against one artifact's recorded figures; everything above it is boolean
 * algebra over the one-sided proof. An `And` needs one branch proved, an `Or` needs all of them —
 * and an empty `And` is [ScanVerdict.MIGHT_MATCH], because a filter with no terms proves nothing.
 * An empty `Or` cannot arise from the parser and would mean "matches nothing", so it is reported
 * as a proof rather than silently inverted.
 *
 * **Call [pushNegation] first.** A `Not` reaching here is a filter nobody rewrote, and it is
 * reported as [ScanVerdict.MIGHT_MATCH] rather than guessed at: negating a one-sided proof gives
 * no proof, so that is the honest answer and never a wrong skip.
 */
fun ScanFilter.verdict(leaf: (ScanPredicate) -> ScanVerdict): ScanVerdict = when (this) {
    is ScanFilter.Term -> leaf(predicate)
    is ScanFilter.And ->
        if (terms.any { it.verdict(leaf) == ScanVerdict.CANNOT_MATCH }) ScanVerdict.CANNOT_MATCH
        else ScanVerdict.MIGHT_MATCH
    is ScanFilter.Or ->
        if (terms.all { it.verdict(leaf) == ScanVerdict.CANNOT_MATCH }) ScanVerdict.CANNOT_MATCH
        else ScanVerdict.MIGHT_MATCH
    is ScanFilter.Not -> ScanVerdict.MIGHT_MATCH
}

/**
 * The filter as a flat list of conditions, or null when it is not one.
 *
 * The row-per-condition form can only build and show a conjunction of plain terms; an `Or`, a `Not`
 * or a nested group has no row to be. Returning null rather than something lossy is what lets the
 * panel *keep the reader in the clause editor* instead of silently dropping half their filter the
 * moment they switch back.
 */
fun ScanFilter.asConjunction(): List<ScanPredicate>? = when (this) {
    is ScanFilter.Term -> listOf(predicate)
    is ScanFilter.And -> terms.map { (it as? ScanFilter.Term ?: return null).predicate }
    is ScanFilter.Or, is ScanFilter.Not -> null
}

/** The filter written back out, with the parentheses it needs and no others. */
fun ScanFilter.render(): String = when (this) {
    is ScanFilter.Term -> predicate.toString()
    is ScanFilter.And -> terms.joinToString(" AND ") { it.wrapped(this) }
    is ScanFilter.Or -> terms.joinToString(" OR ") { it.wrapped(this) }
    is ScanFilter.Not -> "NOT ${term.wrapped(this)}"
}

private fun ScanFilter.wrapped(parent: ScanFilter): String {
    // An OR inside an AND needs its parentheses back; everything else reads without them.
    val needs = this is ScanFilter.Or && parent !is ScanFilter.Or
    return if (needs) "(${render()})" else render()
}

/**
 * The filter as Spark SQL — what `rewrite_data_files(where => …)` takes: a literal that is not a
 * number, a boolean or NULL is quoted, since the parser keeps every literal as text and the
 * clause syntax lets a bare word stand for a string where Spark reads it as a column.
 */
fun ScanFilter.renderSparkSql(): String = when (this) {
    is ScanFilter.Term -> predicate.let { p ->
        if (p.op.takesLiteral) "${p.column} ${p.op.symbol} ${sparkSqlLiteral(p.literal)}" else "${p.column} ${p.op.symbol}"
    }
    is ScanFilter.And -> terms.joinToString(" AND ") { it.wrappedSparkSql(this) }
    is ScanFilter.Or -> terms.joinToString(" OR ") { it.wrappedSparkSql(this) }
    is ScanFilter.Not -> "NOT ${term.wrappedSparkSql(this)}"
}

private fun ScanFilter.wrappedSparkSql(parent: ScanFilter): String {
    val needs = this is ScanFilter.Or && parent !is ScanFilter.Or
    return if (needs) "(${renderSparkSql()})" else renderSparkSql()
}

private fun sparkSqlLiteral(literal: String): String {
    val bare = literal.toBigDecimalOrNull() != null || literal.equals("true", ignoreCase = true) || literal.equals("false", ignoreCase = true) || literal.equals("null", ignoreCase = true)
    return if (bare) literal else "'" + literal.replace("'", "\\'") + "'"
}
