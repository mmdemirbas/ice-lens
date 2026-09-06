package ui

import model.PredicateOp
import model.PredicateOutcome
import model.ScanPredicate
import model.TermEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the "Because" column says, against the verdict sitting next to it in the same row.
 *
 * The two cells are the whole row, and they have to agree. Once a filter could hold an `OR`, a
 * term that rules the artifact out stopped meaning the artifact is ruled out — one proved branch
 * of `a = 1 OR b = 2` proves nothing — and the reason was still being read off the outcomes alone.
 * So a row could say "would be read" beside the reason a skip would have had, contradicting itself
 * in two adjacent cells with nothing failing.
 */
class ScanPruningSummaryTest {

    private fun outcome(column: String, effect: TermEffect, reason: String) = PredicateOutcome(
        predicate = ScanPredicate(column, PredicateOp.EQ, "1"),
        fieldName = column,
        transform = null,
        effect = effect,
        reason = reason,
    )

    private val proof = outcome("a", TermEffect.SKIPS, "outside the recorded range")
    private val kept = outcome("b", TermEffect.KEEPS, "the range overlaps")

    /**
     * The case the `OR` introduced: a proved term, and a verdict of "would be read".
     *
     * The reason must describe the verdict that was reached, not the strongest thing any single
     * term managed on its own.
     */
    @Test
    fun `a proved term is not the reason when the artifact was not ruled out`() {
        val reason = listOf(proof, kept).summarise(proved = false)
        assertTrue(
            reason.contains("the range overlaps"),
            "the term that did not rule it out has to be in the reason: $reason",
        )
        assertTrue(
            reason.contains("outside the recorded range"),
            "and so does the one that did, since both were evaluated: $reason",
        )
    }

    /** When it *was* ruled out, the proof is the reason and the rest is noise. */
    @Test
    fun `a skip is explained by what proved it`() {
        assertEquals("a — outside the recorded range", listOf(proof, kept).summarise(proved = true))
    }

    /**
     * Under a disjunction every branch had to be proved, so every proof is listed.
     *
     * Naming one would be true of a conjunction and false here — the reader would be told a single
     * term ruled the manifest out when it took all of them.
     */
    @Test
    fun `a skip proved by several terms lists all of them`() {
        val second = outcome("b", TermEffect.SKIPS, "also outside")
        val reason = listOf(proof, second).summarise(proved = true)
        assertEquals("a — outside the recorded range; b — also outside", reason)
    }

    /** A verdict of "skipped" with nothing marked as proving it still says what was evaluated. */
    @Test
    fun `a skip with no proving term falls back to what was evaluated`() {
        assertEquals("b — the range overlaps", listOf(kept).summarise(proved = true))
    }

    /** Nothing evaluated is an empty reason rather than a claim. */
    @Test
    fun `no outcomes is no reason`() {
        assertEquals("", emptyList<PredicateOutcome>().summarise(proved = false))
    }
}
