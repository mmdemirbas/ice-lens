package ui

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatUtilsTest {

    @Test
    fun `formatAppTimestamp returns NA for null`() {
        assertEquals("N/A", formatAppTimestamp(null))
    }

    @Test
    fun `formatAppTimestamp formats epoch zero`() {
        val result = formatAppTimestamp(0L)
        // Just verify it doesn't crash and returns a non-empty string
        assert(result.isNotBlank()) { "Expected non-blank formatted timestamp" }
    }

    @Test
    fun `parseLongSet parses semicolon-separated values`() {
        assertEquals(setOf(1L, 2L, 3L), parseLongSet("1;2;3"))
    }

    @Test
    fun `parseLongSet handles whitespace`() {
        assertEquals(setOf(1L, 2L), parseLongSet(" 1 ; 2 "))
    }

    @Test
    fun `parseLongSet skips invalid entries`() {
        assertEquals(setOf(1L, 3L), parseLongSet("1;abc;3"))
    }

    @Test
    fun `parseLongSet returns empty for blank`() {
        assertEquals(emptySet(), parseLongSet(""))
    }

    @Test
    fun `encodeLongSet produces sorted semicolon-separated values`() {
        assertEquals("1;2;3", encodeLongSet(setOf(3L, 1L, 2L)))
    }

    @Test
    fun `encodeLongSet returns empty string for empty set`() {
        assertEquals("", encodeLongSet(emptySet()))
    }

    @Test
    fun `parseLongSet and encodeLongSet round-trip`() {
        val original = setOf(100L, 200L, 300L)
        assertEquals(original, parseLongSet(encodeLongSet(original)))
    }
}
