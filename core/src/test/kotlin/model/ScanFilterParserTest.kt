package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reading a `WHERE` clause into the filter the evaluator folds.
 *
 * Most of these are about the two ways a parser fails *quietly*: getting precedence backwards, and
 * dropping something it did not understand. Both produce a filter that parses, evaluates, and
 * answers a question the reader did not ask — which is worse than an error message, because the
 * verdicts that come back look exactly like verdicts.
 */
class ScanFilterParserTest {

    private fun parsed(text: String): ScanFilter {
        val result = parseScanFilter(text)
        assertTrue(result is ScanFilterParse.Parsed, "'$text' should parse, got $result")
        return result.filter
    }

    private fun failed(text: String): ScanFilterParse.Failed {
        val result = parseScanFilter(text)
        assertTrue(result is ScanFilterParse.Failed, "'$text' should not parse, got $result")
        return result
    }

    private fun term(column: String, op: PredicateOp, literal: String = "") =
        ScanFilter.Term(ScanPredicate(column, op, literal))

    @Test
    fun `a single comparison`() {
        assertEquals(term("d", PredicateOp.EQ, "2024-03-05"), parsed("d = 2024-03-05"))
    }

    @Test
    fun `every comparison operator, including both spellings of not-equal`() {
        assertEquals(term("a", PredicateOp.LT, "1"), parsed("a < 1"))
        assertEquals(term("a", PredicateOp.LTE, "1"), parsed("a <= 1"))
        assertEquals(term("a", PredicateOp.GT, "1"), parsed("a > 1"))
        assertEquals(term("a", PredicateOp.GTE, "1"), parsed("a >= 1"))
        assertEquals(term("a", PredicateOp.NOT_EQ, "1"), parsed("a <> 1"))
        assertEquals(term("a", PredicateOp.NOT_EQ, "1"), parsed("a != 1"))
    }

    @Test
    fun `is null and is not null`() {
        assertEquals(term("a", PredicateOp.IS_NULL), parsed("a IS NULL"))
        assertEquals(term("a", PredicateOp.IS_NOT_NULL), parsed("a IS NOT NULL"))
        assertEquals(term("a", PredicateOp.IS_NULL), parsed("a is null"))
    }

    /**
     * `AND` binds tighter than `OR`, which is what every SQL reader already assumes.
     *
     * Backwards, this parses, evaluates, and prunes against a filter nobody wrote — and the
     * verdicts it returns are indistinguishable from correct ones.
     */
    @Test
    fun `AND binds tighter than OR`() {
        assertEquals(
            ScanFilter.Or(
                listOf(
                    term("a", PredicateOp.EQ, "1"),
                    ScanFilter.And(listOf(term("b", PredicateOp.EQ, "2"), term("c", PredicateOp.EQ, "3"))),
                )
            ),
            parsed("a = 1 OR b = 2 AND c = 3"),
        )
    }

    @Test
    fun `parentheses override precedence`() {
        assertEquals(
            ScanFilter.And(
                listOf(
                    ScanFilter.Or(listOf(term("a", PredicateOp.EQ, "1"), term("b", PredicateOp.EQ, "2"))),
                    term("c", PredicateOp.EQ, "3"),
                )
            ),
            parsed("(a = 1 OR b = 2) AND c = 3"),
        )
    }

    @Test
    fun `NOT binds tighter than AND`() {
        assertEquals(
            ScanFilter.And(
                listOf(ScanFilter.Not(term("a", PredicateOp.EQ, "1")), term("b", PredicateOp.EQ, "2"))
            ),
            parsed("NOT a = 1 AND b = 2"),
        )
    }

    @Test
    fun `keywords are case-insensitive`() {
        assertEquals(parsed("a = 1 and b = 2"), parsed("a = 1 AND b = 2"))
        assertEquals(parsed("a = 1 Or b = 2"), parsed("a = 1 OR b = 2"))
    }

    /** A quoted literal keeps its spaces, and a doubled quote is one quote. */
    @Test
    fun `quoted literals`() {
        assertEquals(term("ts", PredicateOp.GT, "2024-03-05 10:00:00"), parsed("ts > '2024-03-05 10:00:00'"))
        assertEquals(term("name", PredicateOp.EQ, "o'brien"), parsed("name = 'o''brien'"))
        assertEquals(term("name", PredicateOp.EQ, ""), parsed("name = ''"))
    }

    /** An unquoted date is one token, which is the whole reason a dash is a word character. */
    @Test
    fun `an unquoted date is a single literal`() {
        assertEquals(term("d", PredicateOp.GTE, "2024-03-05"), parsed("d >= 2024-03-05"))
    }

    @Test
    fun `an empty filter is an empty conjunction, not an error`() {
        assertTrue(parsed("").isEmpty())
        assertTrue(parsed("   ").isEmpty())
    }

    /**
     * Nothing is silently dropped. Each of these used to be a plausible way to lose half a filter.
     */
    @Test
    fun `what does not parse is reported, with the offset it happened at`() {
        // At the `&` itself, not at what follows it: the offset is what the reader is shown, and
        // pointing past the offending character makes them look at the part that was fine.
        assertEquals(6, failed("a = 1 & b = 2").at)
        assertTrue(failed("a =").message.contains("no value"))
        assertTrue(failed("a").message.contains("no condition"))
        assertTrue(failed("(a = 1").message.contains("never closed"))
        assertTrue(failed("a = 1 AND").message.contains("ends"))
        assertTrue(failed("a IS").message.contains("NULL"))
        assertTrue(failed("= 1").message.contains("column name"))
        assertTrue(failed("a = 'unterminated").message.contains("closing"))
        assertTrue(failed("a = AND").message.contains("expected a value"))
    }

    /**
     * `IN` is the disjunction it is defined to be, not a leaf of its own.
     *
     * Which is the whole reason it needed no evaluator change: a manifest is ruled out by `IN` only
     * when every value is ruled out, and that rule is already [ScanFilter.Or]'s.
     */
    @Test
    fun `IN is a disjunction of equalities`() {
        assertEquals(
            ScanFilter.Or(
                listOf(
                    term("region", PredicateOp.EQ, "eu"),
                    term("region", PredicateOp.EQ, "us"),
                    term("region", PredicateOp.EQ, "apac"),
                )
            ),
            parsed("region IN ('eu', 'us', 'apac')"),
        )
    }

    /** One value is one condition — an `Or` of one would print a disjunction that is not there. */
    @Test
    fun `a one-value IN is a plain equality`() {
        assertEquals(term("region", PredicateOp.EQ, "eu"), parsed("region IN ('eu')"))
    }

    /**
     * `BETWEEN` is the pair of bounds, and its `AND` is not a connective.
     *
     * Reading that `AND` as a connective is the quiet failure here: `a BETWEEN 1 AND 2` would parse
     * as two filters and the upper bound would be dropped, which prunes *less* and looks correct.
     */
    @Test
    fun `BETWEEN is a pair of bounds`() {
        assertEquals(
            ScanFilter.And(listOf(term("d", PredicateOp.GTE, "1"), term("d", PredicateOp.LTE, "9"))),
            parsed("d BETWEEN 1 AND 9"),
        )
    }

    @Test
    fun `a BETWEEN does not swallow the AND after it`() {
        assertEquals(
            ScanFilter.And(
                listOf(
                    term("d", PredicateOp.GTE, "1"),
                    term("d", PredicateOp.LTE, "9"),
                    term("b", PredicateOp.EQ, "2"),
                )
            ),
            parsed("d BETWEEN 1 AND 9 AND b = 2"),
        )
    }

    /**
     * The negated forms are wrapped rather than negated by hand, so De Morgan has one implementation.
     *
     * What is asserted is the result of that one implementation: `NOT IN` is every `<>`, and
     * `NOT BETWEEN` is either side of the range.
     */
    @Test
    fun `NOT IN and NOT BETWEEN are rewritten by pushNegation, not by the parser`() {
        assertEquals(
            ScanFilter.And(
                listOf(term("r", PredicateOp.NOT_EQ, "eu"), term("r", PredicateOp.NOT_EQ, "us"))
            ),
            parsed("r NOT IN ('eu', 'us')").pushNegation(),
        )
        assertEquals(
            ScanFilter.Or(listOf(term("d", PredicateOp.LT, "1"), term("d", PredicateOp.GT, "9"))),
            parsed("d NOT BETWEEN 1 AND 9").pushNegation(),
        )
    }

    /**
     * A branch of the same connective is spliced in, which is what keeps the row form reachable.
     *
     * `BETWEEN` desugars to an `And`, so without the splice a filter mixing it with a plain term
     * would be a nested `And` — [asConjunction] would return null and the panel would keep the
     * reader in the editor for a filter the rows can show perfectly well.
     */
    @Test
    fun `nested connectives of one kind are flattened`() {
        assertEquals(
            listOf(
                ScanPredicate("a", PredicateOp.EQ, "1"),
                ScanPredicate("d", PredicateOp.GTE, "2"),
                ScanPredicate("d", PredicateOp.LTE, "3"),
            ),
            parsed("a = 1 AND d BETWEEN 2 AND 3").asConjunction(),
        )
        assertEquals(parsed("a = 1 OR b = 2 OR c = 3"), parsed("(a = 1 OR b = 2) OR c = 3"))
    }

    /** Each of these is a way to lose part of a list without noticing. */
    @Test
    fun `a malformed IN or BETWEEN is reported`() {
        assertTrue(failed("a IN").message.contains("no list"))
        assertTrue(failed("a IN 1").message.contains("'('"))
        assertTrue(failed("a IN ()").message.contains("expected a value"))
        assertTrue(failed("a IN (1").message.contains("never closed"))
        assertTrue(failed("a IN (1 2)").message.contains("',' or ')'"))
        assertTrue(failed("a BETWEEN 1").message.contains("expected AND"))
        assertTrue(failed("a BETWEEN 1 OR 2").message.contains("expected AND"))
        assertTrue(failed("a BETWEEN").message.contains("no value"))
    }

    /** What is rendered can be read back, which is what lets the form and the clause be one state. */
    @Test
    fun `render and parse round-trip`() {
        listOf(
            "a = 1",
            "a = 1 AND b = 2",
            "a = 1 OR b = 2",
            "a = 1 AND (b = 2 OR c = 3)",
            "a is null",
            "a is not null",
            // Neither has a node of its own, so what comes back is the shape being evaluated.
            "a IN (1, 2)",
            "a BETWEEN 1 AND 9",
        ).forEach { text ->
            val once = parsed(text)
            assertEquals(once, parsed(once.render()), "'$text' rendered as '${once.render()}'")
        }
    }
}
