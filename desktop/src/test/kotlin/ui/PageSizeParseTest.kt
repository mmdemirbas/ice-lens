package ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** What the typed page size accepts, and what it refuses, against the bounds the app applies. */
class PageSizeParseTest {
    private val range = 2..2_000

    @Test
    fun `a whole number in range is the page size`() {
        assertEquals(37, parsePageSize("37", range))
        assertEquals(2, parsePageSize("2", range))
        assertEquals(2_000, parsePageSize("2000", range))
    }

    @Test
    fun `the separators the bounds line prints are accepted back`() {
        assertEquals(2_000, parsePageSize("2,000", range))
        assertEquals(1_500, parsePageSize(" 1 500 ", range))
        assertEquals(1_000, parsePageSize("1_000", range))
    }

    @Test
    fun `outside the range, empty, and not a number are refused`() {
        assertEquals(null, parsePageSize("1", range))
        assertEquals(null, parsePageSize("2001", range))
        assertEquals(null, parsePageSize("", range))
        assertEquals(null, parsePageSize("24.5", range))
        assertEquals(null, parsePageSize("many", range))
        assertEquals(null, parsePageSize("-24", range))
    }
}
