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

private val KEYWORDS = setOf("and", "or", "not", "is", "null")

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
        val first = parseAnd() ?: return null
        val terms = mutableListOf(first)
        while (keywordAt() == "or") {
            pos++
            terms += parseAnd() ?: return null
        }
        return if (terms.size == 1) first else ScanFilter.Or(terms)
    }

    private fun parseAnd(): ScanFilter? {
        val first = parseUnary() ?: return null
        val terms = mutableListOf(first)
        while (keywordAt() == "and") {
            pos++
            terms += parseUnary() ?: return null
        }
        return if (terms.size == 1) first else ScanFilter.And(terms)
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

        val operator = peek()
            ?: return fail("'${column.text}' has no condition after it", endOffset())
        if (operator.kind != TokenKind.OP || operator.text !in OPERATORS) {
            return fail("'${operator.text}' is not a comparison this understands", operator.at)
        }
        pos++

        val literal = peek()
            ?: return fail("'${column.text} ${operator.text}' has no value after it", endOffset())
        if (literal.kind != TokenKind.WORD && literal.kind != TokenKind.STRING) {
            return fail("expected a value after '${operator.text}'", literal.at)
        }
        if (literal.kind == TokenKind.WORD && literal.text.lowercase() in KEYWORDS) {
            return fail("expected a value, not '${literal.text}'", literal.at)
        }
        pos++
        return ScanFilter.Term(
            ScanPredicate(column.text, OPERATORS.getValue(operator.text), literal.text),
        )
    }
}
