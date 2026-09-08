package model

/**
 * What a typed filter turned into, or where it stopped making sense.
 *
 * A failure carries the offset it happened at, not just a message: the reader is looking at their
 * own text and the useful thing is *where*, and a message alone makes them re-read the whole line
 * hunting for the character this already knows.
 */
sealed interface ScanFilterParse {
    data class Parsed(val filter: ScanFilter) : ScanFilterParse
    data class Failed(val message: String, val at: Int) : ScanFilterParse
}

/**
 * Reads a `WHERE`-clause filter: comparisons joined by `AND` / `OR` / `NOT`, grouped by parens.
 *
 * The form above it can only build a conjunction, so this is the way in for every filter shape the
 * evaluator gained with [ScanFilter] — and it is closer to what the reader has in hand anyway,
 * because the question is usually about a query that already exists.
 *
 * **Literals stay text.** A `ScanPredicate` carries its literal as a string and `parseLiteral`
 * turns it into a value against the *column's own type*, which this cannot know: `2024-03-05` is a
 * date to one column and a string to another, and the manifest being evaluated decides which. So
 * the only thing done here is stripping the quotes that told the tokenizer where the literal
 * ended. That keeps one place where a literal becomes a value, rather than a second that drifts.
 */
fun parseScanFilter(text: String): ScanFilterParse {
    if (text.isBlank()) return ScanFilterParse.Parsed(ScanFilter.of(emptyList()))
    val tokens = tokenize(text) ?: return ScanFilterParse.Failed(
        "an opening quote with no closing one", text.lastIndexOf('\''),
    )
    val parser = FilterParser(tokens, text)
    return parser.parseAll()
}

private enum class TokenKind { WORD, STRING, OP, LPAREN, RPAREN }

private data class Token(val kind: TokenKind, val text: String, val at: Int)

/** Characters an unquoted token may hold. Dashes and colons are in so `2024-03-05` is one token. */
private fun Char.isWordChar(): Boolean = isLetterOrDigit() || this in "_.-:+"

/**
 * A literal written back so the tokenizer reads it as one token again.
 *
 * The rule is the tokenizer's, and it lives beside the tokenizer for that reason: a renderer that
 * decides separately what needs quoting drifts from what the parser accepts, and the drift arrives
 * as the reader's own filter coming back an error. `ts > '2024-03-05 10:00:00'` rendered as
 * `ts > 2024-03-05 10:00:00`, which is two literals here and parses as neither. The empty string is
 * quoted too — nothing at all is not a token.
 */
fun quoteScanLiteral(literal: String): String =
    if (literal.isNotEmpty() && literal.all { it.isWordChar() }) literal
    else "'" + literal.replace("'", "''") + "'"

private fun tokenize(text: String): List<Token>? {
    val tokens = mutableListOf<Token>()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c.isWhitespace() -> i++
            c == '(' -> { tokens += Token(TokenKind.LPAREN, "(", i); i++ }
            c == ')' -> { tokens += Token(TokenKind.RPAREN, ")", i); i++ }
            c == '\'' -> {
                // Doubled quotes are an escaped quote, as in SQL.
                val start = i
                val sb = StringBuilder()
                i++
                while (true) {
                    if (i >= text.length) return null
                    if (text[i] == '\'') {
                        if (i + 1 < text.length && text[i + 1] == '\'') { sb.append('\''); i += 2 }
                        else { i++; break }
                    } else { sb.append(text[i]); i++ }
                }
                tokens += Token(TokenKind.STRING, sb.toString(), start)
            }
            c == '<' || c == '>' || c == '=' || c == '!' -> {
                val two = if (i + 1 < text.length) text.substring(i, i + 2) else ""
                val op = if (two in setOf("<=", ">=", "<>", "!=")) two else c.toString()
                tokens += Token(TokenKind.OP, op, i)
                i += op.length
            }
            c.isWordChar() -> {
                val start = i
                while (i < text.length && text[i].isWordChar()) i++
                tokens += Token(TokenKind.WORD, text.substring(start, i), start)
            }
            else -> {
                // An unknown character is reported where it is rather than skipped: skipping is how
                // `a = 1 & b = 2` silently becomes `a = 1`.
                tokens += Token(TokenKind.OP, c.toString(), i)
                i++
            }
        }
    }
    return tokens
}

private val KEYWORDS = setOf("and", "or", "not", "is", "null", "in", "between")

private val OPERATORS = mapOf(
    "=" to PredicateOp.EQ,
    "<>" to PredicateOp.NOT_EQ,
    "!=" to PredicateOp.NOT_EQ,
    "<" to PredicateOp.LT,
    "<=" to PredicateOp.LTE,
    ">" to PredicateOp.GT,
    ">=" to PredicateOp.GTE,
)

/**
 * Recursive descent over the usual precedence: `OR` binds loosest, then `AND`, then `NOT`.
 *
 * That order is not a preference — it is what every SQL reader already has in their head, so
 * `a = 1 OR b = 2 AND c = 3` has to mean `a = 1 OR (b = 2 AND c = 3)` here too. Getting it
 * backwards would produce a filter that parses, evaluates, and answers a question nobody asked.
 */
private class FilterParser(private val tokens: List<Token>, private val text: String) {
    private var pos = 0

    private fun peek(): Token? = tokens.getOrNull(pos)
    private fun keywordAt(offset: Int = 0): String? =
        tokens.getOrNull(pos + offset)?.takeIf { it.kind == TokenKind.WORD }
            ?.text?.lowercase()?.takeIf { it in KEYWORDS }

    private fun endOffset(): Int = tokens.lastOrNull()?.let { it.at + it.text.length } ?: text.length

    fun parseAll(): ScanFilterParse {
        val filter = parseOr() ?: return failure
        val leftover = peek()
        if (leftover != null) {
            return ScanFilterParse.Failed("expected AND, OR or the end of the filter", leftover.at)
        }
        return ScanFilterParse.Parsed(filter)
    }

    private var failure: ScanFilterParse.Failed =
        ScanFilterParse.Failed("could not read this filter", 0)

    private fun fail(message: String, at: Int): ScanFilter? {
        failure = ScanFilterParse.Failed(message, at)
        return null
    }

    private fun parseOr(): ScanFilter? {
        val terms = mutableListOf<ScanFilter>()
        terms.addOred(parseAnd() ?: return null)
        while (keywordAt() == "or") {
            pos++
            terms.addOred(parseAnd() ?: return null)
        }
        return terms.singleOrNull() ?: ScanFilter.Or(terms)
    }

    private fun parseAnd(): ScanFilter? {
        val terms = mutableListOf<ScanFilter>()
        terms.addAnded(parseUnary() ?: return null)
        while (keywordAt() == "and") {
            pos++
            terms.addAnded(parseUnary() ?: return null)
        }
        return terms.singleOrNull() ?: ScanFilter.And(terms)
    }

    /**
     * Splices a branch of the same connective into this one — `And(a, And(b, c))` is `And(a, b, c)`.
     *
     * Associativity, so the verdict is the same either way. What it is here for is
     * [asConjunction], which only recognises a flat list of terms: `BETWEEN` desugars to an `And`,
     * so without this `a = 1 AND b BETWEEN 2 AND 3` would be a nested `And` and the panel would
     * decide that a filter the row form can perfectly well show has to stay in the editor.
     */
    private fun MutableList<ScanFilter>.addAnded(term: ScanFilter) {
        if (term is ScanFilter.And) this += term.terms else this += term
    }

    private fun MutableList<ScanFilter>.addOred(term: ScanFilter) {
        if (term is ScanFilter.Or) this += term.terms else this += term
    }

    private fun parseUnary(): ScanFilter? {
        if (keywordAt() == "not") {
            pos++
            val inner = parseUnary() ?: return null
            return ScanFilter.Not(inner)
        }
        return parsePrimary()
    }

    private fun parsePrimary(): ScanFilter? {
        val token = peek() ?: return fail("the filter ends where a condition should be", endOffset())
        if (token.kind == TokenKind.LPAREN) {
            pos++
            val inner = parseOr() ?: return null
            val close = peek()
            if (close == null || close.kind != TokenKind.RPAREN) {
                return fail("this group is never closed", token.at)
            }
            pos++
            return inner
        }
        return parsePredicate()
    }

    private fun parsePredicate(): ScanFilter? {
        val column = peek() ?: return fail("the filter ends where a column name should be", endOffset())
        if (column.kind != TokenKind.WORD || column.text.lowercase() in KEYWORDS) {
            return fail("expected a column name", column.at)
        }
        pos++

        if (keywordAt() == "is") {
            val isAt = peek()!!.at
            pos++
            val negated = keywordAt() == "not"
            if (negated) pos++
            if (keywordAt() != "null") return fail("expected NULL after IS", peek()?.at ?: isAt)
            pos++
            val op = if (negated) PredicateOp.IS_NOT_NULL else PredicateOp.IS_NULL
            return ScanFilter.Term(ScanPredicate(column.text, op))
        }

        // `NOT` is infix here — `x NOT IN (…)`, `x NOT BETWEEN … AND …` — unlike the prefix `NOT`
        // [parseUnary] reads. Both are wrapped in a `Not` rather than negated by hand, so
        // `pushNegation` stays the one place a negation is worked out: `NOT IN` is a conjunction of
        // `<>` and `NOT BETWEEN` a disjunction of `<` and `>`, and writing either out here would be
        // a second implementation of De Morgan.
        val negated = keywordAt() == "not" && keywordAt(1) in setOf("in", "between")
        if (negated) pos++
        fun negate(filter: ScanFilter?) = filter?.let { if (negated) ScanFilter.Not(it) else it }
        when (keywordAt()) {
            "in" -> return negate(parseIn(column.text))
            "between" -> return negate(parseBetween(column.text))
        }

        val operator = peek()
            ?: return fail("'${column.text}' has no condition after it", endOffset())
        if (operator.kind != TokenKind.OP || operator.text !in OPERATORS) {
            return fail("'${operator.text}' is not a comparison this understands", operator.at)
        }
        pos++

        val literal = literal("${column.text} ${operator.text}") ?: return null
        return ScanFilter.Term(ScanPredicate(column.text, OPERATORS.getValue(operator.text), literal))
    }

    /**
     * `c IN (a, b)` as the disjunction it is defined to be, so the evaluator needs no leaf for it.
     *
     * A leaf would be a second implementation of the same one-sided proof — `IN` is exactly
     * `= a OR = b`, and a manifest is ruled out by it only when every value is ruled out, which is
     * what [ScanFilter.Or] already says. The cost is that [ScanFilter.render] writes it back as the
     * `OR`, which is what is actually being evaluated.
     */
    private fun parseIn(column: String): ScanFilter? {
        pos++
        val open = peek() ?: return fail("'$column IN' has no list after it", endOffset())
        if (open.kind != TokenKind.LPAREN) return fail("expected '(' after IN", open.at)
        pos++
        val values = mutableListOf<String>()
        while (true) {
            values += literal("$column IN (") ?: return null
            val next = peek() ?: return fail("this IN list is never closed", open.at)
            when {
                next.kind == TokenKind.RPAREN -> { pos++; break }
                next.kind == TokenKind.OP && next.text == "," -> pos++
                else -> return fail("expected ',' or ')' in the IN list", next.at)
            }
        }
        val terms = values.map { ScanFilter.Term(ScanPredicate(column, PredicateOp.EQ, it)) }
        return terms.singleOrNull() ?: ScanFilter.Or(terms)
    }

    /**
     * `c BETWEEN lo AND hi` as `c >= lo AND c <= hi`, which is SQL's own definition of it.
     *
     * The `AND` belongs to the `BETWEEN` and is consumed here rather than by [parseAnd] — reading
     * it as a connective would make `a BETWEEN 1 AND 2` two filters and lose the upper bound.
     */
    private fun parseBetween(column: String): ScanFilter? {
        pos++
        val low = literal("$column BETWEEN") ?: return null
        if (keywordAt() != "and") {
            return fail("expected AND after the first BETWEEN value", peek()?.at ?: endOffset())
        }
        pos++
        val high = literal("$column BETWEEN $low AND") ?: return null
        return ScanFilter.And(
            listOf(
                ScanFilter.Term(ScanPredicate(column, PredicateOp.GTE, low)),
                ScanFilter.Term(ScanPredicate(column, PredicateOp.LTE, high)),
            )
        )
    }

    /**
     * The next token as a literal, consumed. Null records the failure and stops the parse.
     *
     * [after] is what the reader has already written, so the message points at their own text
     * rather than at a token kind. A keyword is rejected rather than taken as a value: `a = AND`
     * is a half-typed filter, and reading `AND` as the literal is how one silently evaluates.
     */
    private fun literal(after: String): String? {
        val token = peek() ?: run {
            fail("'$after' has no value after it", endOffset())
            return null
        }
        if (token.kind != TokenKind.WORD && token.kind != TokenKind.STRING) {
            fail("expected a value after '$after'", token.at)
            return null
        }
        if (token.kind == TokenKind.WORD && token.text.lowercase() in KEYWORDS) {
            fail("expected a value, not '${token.text}'", token.at)
            return null
        }
        pos++
        return token.text
    }
}
