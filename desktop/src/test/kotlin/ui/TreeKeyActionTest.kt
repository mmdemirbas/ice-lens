package ui

import model.GraphNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The tree's arrow keys, checked against the flattened list they are defined over.
 *
 * Hand-built rows rather than a fixture, because what is under test is the keymap and not the
 * traversal that produces it: the interesting cases are a closed line with children, an open one,
 * the last child of a subtree and the boundaries, and a real table gives you whichever of those it
 * happens to have.
 */
class TreeKeyActionTest {

    /** A node is only a carrier of its id here; the keymap reads nothing else off it. */
    private fun node(id: String): GraphNode =
        GraphNode.ErrorNode(id = id, title = id, stage = "test", path = "", message = "")

    private fun row(id: String, depth: Int, hasChildren: Boolean = false) =
        TreeRow(node(id), depth, hasChildren)

    /**
     * ```
     * table            depth 0, open
     *   meta-1         depth 1, closed, has children
     *   meta-2         depth 1, open
     *     snap-1       depth 2
     *   meta-3         depth 1
     * ```
     */
    private val rows = listOf(
        row("table", 0, hasChildren = true),
        row("meta-1", 1, hasChildren = true),
        row("meta-2", 1, hasChildren = true),
        row("snap-1", 2),
        row("meta-3", 1),
    )
    private val expanded = setOf("table", "meta-2")

    private fun act(selected: String?, key: ListKey) =
        treeKeyAction(rows, expanded, selected, key)

    @Test
    fun `down and up move one line whatever the depth`() {
        assertEquals(TreeKeyAction.Select("snap-1"), act("meta-2", ListKey.DOWN))
        assertEquals(
            TreeKeyAction.Select("meta-3"), act("snap-1", ListKey.DOWN),
            "down leaves a subtree by moving to the next line, not to the next sibling",
        )
        assertEquals(TreeKeyAction.Select("snap-1"), act("meta-3", ListKey.UP))
    }

    @Test
    fun `up from the first line and down from the last go nowhere`() {
        assertNull(act("table", ListKey.UP))
        assertNull(act("meta-3", ListKey.DOWN))
    }

    @Test
    fun `right opens a closed line and steps into an open one`() {
        assertEquals(TreeKeyAction.Expand("meta-1"), act("meta-1", ListKey.RIGHT))
        assertEquals(
            TreeKeyAction.Select("snap-1"), act("meta-2", ListKey.RIGHT),
            "an open line's first child is the line below it",
        )
        assertNull(act("meta-3", ListKey.RIGHT), "a leaf has nothing to open or step into")
    }

    @Test
    fun `left closes an open line and steps out of a closed one`() {
        assertEquals(TreeKeyAction.Collapse("meta-2"), act("meta-2", ListKey.LEFT))
        assertEquals(
            TreeKeyAction.Select("table"), act("meta-1", ListKey.LEFT),
            "the parent is the nearest line above with a smaller depth",
        )
        assertEquals(
            TreeKeyAction.Select("meta-2"), act("snap-1", ListKey.LEFT),
            "and it is the nearest one, not the outermost",
        )
        assertEquals(
            TreeKeyAction.Collapse("table"), act("table", ListKey.LEFT),
            "an open line closes whatever its depth — a root is not a special case",
        )
        assertNull(
            treeKeyAction(rows.take(1), emptySet(), "table", ListKey.LEFT),
            "but a closed root has nothing to step out to",
        )
    }

    @Test
    fun `a selection that is not on screen starts the walk at the top`() {
        // What the search box produces: the selected node is still selected, and filtered out of
        // the lines being drawn. Doing nothing would read as the key being broken.
        assertEquals(TreeKeyAction.Select("table"), act("filtered-away", ListKey.DOWN))
        assertEquals(TreeKeyAction.Select("table"), act(null, ListKey.UP))
    }

    @Test
    fun `an empty tree answers nothing`() {
        ListKey.entries.forEach { key ->
            assertNull(treeKeyAction(emptyList(), emptySet(), null, key))
        }
    }
}
