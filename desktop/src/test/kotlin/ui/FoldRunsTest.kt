package ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Run-folding for the deleted-row list.
 *
 * A compaction leaves deletion vectors covering a contiguous block, so the common case is one run
 * of thousands and the rendering decision is whether the panel says `0-3999` or four thousand
 * lines. The edges are what this pins: a single position must not become `5-5`, and two runs
 * separated by one gap must not silently merge.
 */
class FoldRunsTest {

    @Test
    fun `a single position stays a bare number`() {
        assertEquals("5", foldRuns(listOf(5L)))
    }

    @Test
    fun `consecutive positions fold and a gap breaks the run`() {
        assertEquals("0-2, 5, 9-10", foldRuns(listOf(0L, 1L, 2L, 5L, 9L, 10L)))
    }

    @Test
    fun `two positions one apart do not merge`() {
        assertEquals("1, 3", foldRuns(listOf(1L, 3L)))
        assertEquals("1-2", foldRuns(listOf(1L, 2L)), "and two adjacent ones do")
    }

    @Test
    fun `an empty vector says so rather than rendering an empty line`() {
        assertEquals("none", foldRuns(emptyList()))
    }

    @Test
    fun `a position past 32 bits is not truncated`() {
        val high = (3L shl 32) or 7L
        assertEquals("$high", foldRuns(listOf(high)))
    }
}
