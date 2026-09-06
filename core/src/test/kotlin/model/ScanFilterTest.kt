package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The boolean algebra a scan filter is folded through, and the rewrite that makes it sound.
 *
 * Pruning is a **one-sided proof**: recorded bounds can show a manifest cannot hold a matching
 * row and can never show that it does. Every rule below follows from that asymmetry, and each one
 * is the kind that looks obviously right and is easy to get backwards — an `Or` that skips when
 * one branch is proved would silently drop files a real query returns.
 */
class ScanFilterTest {

    private fun term(column: String, op: PredicateOp, literal: String = "") =
        ScanFilter.Term(ScanPredicate(column, op, literal))

    /** A leaf oracle: the named predicates are proved empty, everything else might match. */
    private fun proves(vararg proved: ScanFilter.Term): (ScanPredicate) -> ScanVerdict {
        val set = proved.map { it.predicate }.toSet()
        return { if (it in set) ScanVerdict.CANNOT_MATCH else ScanVerdict.MIGHT_MATCH }
    }

    private val a = term("a", PredicateOp.EQ, "1")
    private val b = term("b", PredicateOp.EQ, "2")

    @Test
    fun `an AND is proved by one branch`() {
        val filter = ScanFilter.And(listOf(a, b))
        assertEquals(ScanVerdict.CANNOT_MATCH, filter.verdict(proves(a)))
        assertEquals(ScanVerdict.CANNOT_MATCH, filter.verdict(proves(b)))
        assertEquals(ScanVerdict.MIGHT_MATCH, filter.verdict(proves()))
    }

    /**
     * The rule that a conjunction-only evaluator gets wrong by construction.
     *
     * `a = 1 OR b = 2` is empty only when *both* halves are, and a list of terms has no way to say
     * that — it would take the first proof and skip a manifest that holds every row matching the
     * other half.
     */
    @Test
    fun `an OR needs every branch proved`() {
        val filter = ScanFilter.Or(listOf(a, b))
        assertEquals(ScanVerdict.MIGHT_MATCH, filter.verdict(proves(a)))
        assertEquals(ScanVerdict.MIGHT_MATCH, filter.verdict(proves(b)))
        assertEquals(ScanVerdict.CANNOT_MATCH, filter.verdict(proves(a, b)))
    }

    @Test
    fun `an empty filter proves nothing`() {
        assertEquals(ScanVerdict.MIGHT_MATCH, ScanFilter.of(emptyList()).verdict(proves()))
        assertTrue(ScanFilter.of(emptyList()).isEmpty())
    }

    @Test
    fun `every operator has an exact negation, and negating twice is the identity`() {
        PredicateOp.entries.forEach { op ->
            assertEquals(op, op.negated.negated, "$op negated twice should be itself")
            assertTrue(op.negated != op, "$op must not be its own negation")
        }
    }

    /**
     * `NOT` is removed rather than evaluated, and this is why.
     *
     * A `Not` reaching the fold can only turn a proof into a non-proof, so a tree that kept it
     * would stop pruning wherever it appeared. The rewrite negates the operator at the leaf, which
     * keeps the proof — `NOT (ts >= x)` prunes exactly the manifests `ts < x` prunes.
     */
    @Test
    fun `NOT is pushed into the leaves and disappears`() {
        val filter = ScanFilter.Not(term("ts", PredicateOp.GTE, "2024-01-01"))
        val pushed = filter.pushNegation()
        assertEquals(ScanFilter.Term(ScanPredicate("ts", PredicateOp.LT, "2024-01-01")), pushed)
        assertTrue(pushed.predicates().none { it.op == PredicateOp.GTE })
    }

    @Test
    fun `NOT over an AND becomes an OR of negated leaves`() {
        val pushed = ScanFilter.Not(ScanFilter.And(listOf(a, b))).pushNegation()
        assertEquals(
            ScanFilter.Or(
                listOf(
                    ScanFilter.Term(ScanPredicate("a", PredicateOp.NOT_EQ, "1")),
                    ScanFilter.Term(ScanPredicate("b", PredicateOp.NOT_EQ, "2")),
                )
            ),
            pushed,
        )
    }

    @Test
    fun `NOT over an OR becomes an AND of negated leaves`() {
        val pushed = ScanFilter.Not(ScanFilter.Or(listOf(a, b))).pushNegation()
        assertEquals(
            ScanFilter.And(
                listOf(
                    ScanFilter.Term(ScanPredicate("a", PredicateOp.NOT_EQ, "1")),
                    ScanFilter.Term(ScanPredicate("b", PredicateOp.NOT_EQ, "2")),
                )
            ),
            pushed,
        )
    }

    @Test
    fun `a doubled NOT cancels`() {
        val filter = ScanFilter.Not(ScanFilter.Not(a))
        assertEquals(a, filter.pushNegation())
    }

    /**
     * An unrewritten `Not` is answered "might match", never guessed at.
     *
     * Nothing should reach the fold with one — every entry point calls [pushNegation] first — but
     * the safe answer for a one-sided proof is the absence of a proof, and the alternative is a
     * skip that drops rows.
     */
    @Test
    fun `a NOT that reached the fold prunes nothing`() {
        assertEquals(ScanVerdict.MIGHT_MATCH, ScanFilter.Not(a).verdict(proves(a)))
    }

    @Test
    fun `rendering parenthesises an OR inside an AND and nothing else`() {
        assertEquals("a = 1 AND b = 2", ScanFilter.And(listOf(a, b)).render())
        assertEquals("a = 1 OR b = 2", ScanFilter.Or(listOf(a, b)).render())
        assertEquals(
            "a = 1 AND (a = 1 OR b = 2)",
            ScanFilter.And(listOf(a, ScanFilter.Or(listOf(a, b)))).render(),
        )
    }
}
