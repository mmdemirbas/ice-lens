package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A deferred read is not part of the node's identity, asserted rather than commented.
 *
 * It was a comment for the whole life of the deletion-vector feature, and it was wrong. A
 * `private val` lambda in a data class's primary constructor **is** a component of the generated
 * `equals`, so two `FileNode`s for the same manifest entry — one from each of two graph builds —
 * carried two distinct lambda objects and compared unequal. Nothing failed: the comment said the
 * opposite, no test asked, and the only symptom was Compose re-composing a card that had not
 * changed.
 *
 * These are the checks that would have caught it, and the reason [DeferredRead] exists.
 */
class DeferredReadTest {

    private fun vectorEntry() = ManifestEntry(
        status = ManifestEntryStatus.ADDED,
        dataFile = DataFile(
            filePath = "/wh/default/v3/data/deletes.puffin",
            content = DataFileContent.POSITION_DELETES,
            contentOffset = 4,
            contentSizeInBytes = 42,
        ),
    )

    @Test
    fun `two nodes for one entry are equal whether or not they carry a deferred read`() {
        val entry = vectorEntry()
        val withLoader = { GraphNode.FileNode("f1", entry, simpleId = 1, deletionVectorLoader = DeferredRead.of { null }) }
        val withoutLoader = GraphNode.FileNode("f1", entry, simpleId = 1)

        assertEquals(withLoader(), withLoader(), "two builds of the same node must be the same node")
        assertEquals(
            withLoader().hashCode(),
            withLoader().hashCode(),
            "unequal hash codes break every map and set the graph is put in",
        )
        assertEquals(
            withLoader(),
            withoutLoader,
            "whether the means to read was attached is not something the node IS",
        )
    }

    /** Identity is still the node's own fields — this must not have made everything equal. */
    @Test
    fun `nodes for different entries are still unequal`() {
        val a = GraphNode.FileNode("f1", vectorEntry(), simpleId = 1, deletionVectorLoader = DeferredRead.of { null })
        val b = GraphNode.FileNode("f2", vectorEntry(), simpleId = 1, deletionVectorLoader = DeferredRead.of { null })
        val c = GraphNode.FileNode("f1", vectorEntry(), simpleId = 2, deletionVectorLoader = DeferredRead.of { null })
        assertTrue(a != b, "a different id is a different node")
        assertTrue(a != c, "a different simpleId is a different node")
    }

    /** The read happens once, on first ask, and not before. */
    @Test
    fun `nothing is read until the value is asked for, and then only once`() {
        var reads = 0
        val deferred = DeferredRead.of { reads++; "read $reads" }
        assertEquals(0, reads, "constructing it must not read")
        assertTrue(deferred.isPresent, "and asking whether there is anything to read must not either")
        assertEquals(0, reads)

        assertEquals("read 1", deferred.value)
        assertEquals("read 1", deferred.value)
        assertEquals(1, reads, "a second ask must not open the file again")
    }

    @Test
    fun `nothing to read is a state, distinct from a read that gave nothing`() {
        val nothing = DeferredRead.none<String>()
        assertFalse(nothing.isPresent, "there is no file behind this node")
        assertNull(nothing.value)

        val emptyRead = DeferredRead.of<String> { null }
        assertTrue(emptyRead.isPresent, "there is a file; opening it did not produce a value")
        assertNull(emptyRead.value)
    }

    /** The lazy value is memoised on the instance, not recomputed per access. */
    @Test
    fun `the value is the same object every time`() {
        val deferred = DeferredRead.of { StringBuilder("x") }
        assertSame(deferred.value, deferred.value)
    }
}
