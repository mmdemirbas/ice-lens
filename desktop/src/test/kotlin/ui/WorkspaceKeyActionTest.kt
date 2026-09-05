package ui

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import model.WorkspaceItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The workspace list's keyboard rules, and the one place they diverge from the structure tree's.
 *
 * The divergence is the reason this has tests of its own: moving the cursor here must **not** open
 * a table, because opening one reads a whole metadata tree off disk. Holding Down through a
 * warehouse of forty tables would otherwise load forty of them.
 */
class WorkspaceKeyActionTest {

    private val warehouse = WorkspaceItem.Warehouse(path = "/wh", name = "wh", tables = listOf("sales", "orders"))
    private val single = WorkspaceItem.SingleTable(path = "/tables/audit", name = "audit")
    private val items = listOf(warehouse, single)

    private fun rows(expanded: Set<String>, search: String = "") =
        workspaceRows(items, expanded, search) { it.tables }

    private fun act(
        focused: String?,
        key: ListKey,
        expanded: Set<String> = setOf("/wh"),
        search: String = "",
    ) = workspaceKeyAction(rows(expanded, search), expanded, search, focused, key)

    @Test
    fun `a closed warehouse contributes one line and an open one contributes its tables`() {
        assertEquals(
            listOf("/wh", "/tables/audit"),
            rows(emptySet()).map { it.path },
        )
        assertEquals(
            listOf("/wh", "/wh/sales", "/wh/orders", "/tables/audit"),
            rows(setOf("/wh")).map { it.path },
            "the tables keep the order the warehouse lists them in",
        )
    }

    @Test
    fun `a search opens every warehouse, so its tables are reachable`() {
        assertEquals(
            listOf("/wh", "/wh/sales", "/wh/orders", "/tables/audit"),
            rows(expanded = emptySet(), search = "sal").map { it.path },
            "nothing is expanded, but the panel draws them, so the keyboard must reach them",
        )
    }

    @Test
    fun `moving the cursor never opens a table`() {
        val walked = mutableListOf<WorkspaceKeyAction>()
        var cursor: String? = null
        while (true) {
            val action = act(cursor, ListKey.DOWN) ?: break
            walked += action
            cursor = (action as? WorkspaceKeyAction.Focus)?.path ?: break
        }

        assertEquals(
            listOf("/wh", "/wh/sales", "/wh/orders", "/tables/audit"),
            walked.filterIsInstance<WorkspaceKeyAction.Focus>().map { it.path },
        )
        assertTrue(
            walked.none { it is WorkspaceKeyAction.Open },
            "walking the list loaded a table: $walked",
        )
    }

    @Test
    fun `enter opens the table under the cursor and toggles a warehouse`() {
        assertEquals(WorkspaceKeyAction.Open("/wh/sales"), act("/wh/sales", ListKey.ACTIVATE))
        assertEquals(WorkspaceKeyAction.Open("/tables/audit"), act("/tables/audit", ListKey.ACTIVATE))
        assertEquals(WorkspaceKeyAction.Collapse("/wh"), act("/wh", ListKey.ACTIVATE))
        assertEquals(
            WorkspaceKeyAction.Expand("/wh"),
            act("/wh", ListKey.ACTIVATE, expanded = emptySet()),
        )
    }

    @Test
    fun `right opens a closed warehouse and steps into an open one`() {
        assertEquals(
            WorkspaceKeyAction.Expand("/wh"),
            act("/wh", ListKey.RIGHT, expanded = emptySet()),
        )
        assertEquals(WorkspaceKeyAction.Focus("/wh/sales"), act("/wh", ListKey.RIGHT))
        assertNull(act("/tables/audit", ListKey.RIGHT), "a table has nothing to open")
    }

    @Test
    fun `left closes a warehouse and steps a table out to its own warehouse`() {
        assertEquals(WorkspaceKeyAction.Collapse("/wh"), act("/wh", ListKey.LEFT))
        assertEquals(WorkspaceKeyAction.Focus("/wh"), act("/wh/orders", ListKey.LEFT))
        assertNull(
            act("/tables/audit", ListKey.LEFT),
            "a root single table has no warehouse above it",
        )
    }

    /**
     * A search draws a warehouse open without putting it in `expandedPaths`, so "close it" has
     * nothing to remove. Reporting `Collapse` would be a keystroke that visibly does nothing.
     */
    @Test
    fun `left on a warehouse the search forced open does nothing`() {
        assertNull(act("/wh", ListKey.LEFT, expanded = emptySet(), search = "sal"))
        assertEquals(
            WorkspaceKeyAction.Focus("/wh/sales"),
            act("/wh", ListKey.RIGHT, expanded = emptySet(), search = "sal"),
            "and right steps in rather than claiming to open what is already open",
        )
    }

    @Test
    fun `a cursor on a line that is no longer drawn restarts at the top`() {
        assertEquals(WorkspaceKeyAction.Focus("/wh"), act("/wh/gone", ListKey.UP))
        assertEquals(WorkspaceKeyAction.Focus("/wh"), act(null, ListKey.DOWN))
    }

    @Test
    fun `an empty workspace answers nothing`() {
        ListKey.entries.forEach { key ->
            assertNull(workspaceKeyAction(emptyList(), emptySet(), "", null, key))
        }
    }

    private fun edit(focused: String?, key: WorkspaceEditKey, search: String = "") =
        workspaceEditAction(rows(setOf("/wh"), search), search, focused, key)

    /**
     * Only a root is a workspace item. Delete on a table inside a warehouse must not remove the
     * warehouse: that is acting on something the reader did not point at.
     */
    @Test
    fun `delete asks to remove the root under the cursor, and nothing under one of its tables`() {
        assertEquals(WorkspaceKeyAction.Remove("/wh"), edit("/wh", WorkspaceEditKey.REMOVE))
        assertEquals(WorkspaceKeyAction.Remove("/tables/audit"), edit("/tables/audit", WorkspaceEditKey.REMOVE))
        assertNull(edit("/wh/sales", WorkspaceEditKey.REMOVE))
        assertNull(edit(null, WorkspaceEditKey.REMOVE))
    }

    @Test
    fun `alt-arrows move the root under the cursor by one place`() {
        assertEquals(WorkspaceKeyAction.Move("/tables/audit", -1), edit("/tables/audit", WorkspaceEditKey.MOVE_UP))
        assertEquals(WorkspaceKeyAction.Move("/wh", +1), edit("/wh", WorkspaceEditKey.MOVE_DOWN))
        assertNull(edit("/wh/orders", WorkspaceEditKey.MOVE_DOWN))
    }

    /** The neighbour the reader sees under a search is not the neighbour the list has. */
    @Test
    fun `a move under a search does nothing`() {
        assertNull(edit("/wh", WorkspaceEditKey.MOVE_DOWN, search = "w"))
        assertEquals(WorkspaceKeyAction.Remove("/wh"), edit("/wh", WorkspaceEditKey.REMOVE, search = "w"))
    }

    @OptIn(InternalComposeUiApi::class)
    private fun press(key: Key, alt: Boolean = false, meta: Boolean = false, shift: Boolean = false) =
        workspaceEditKey(KeyEvent(key, KeyEventType.KeyDown, isAltPressed = alt, isMetaPressed = meta, isShiftPressed = shift))

    /**
     * Which keystrokes edit the list. Alt is the one modifier a list may take — nothing above the
     * list means Alt+Arrow, since there is no text field inside it — and any other chord is
     * somebody else's shortcut.
     */
    @OptIn(InternalComposeUiApi::class)
    @Test
    fun `delete and backspace bare remove, alt-arrows move, other chords are not ours`() {
        assertEquals(WorkspaceEditKey.REMOVE, press(Key.Delete))
        assertEquals(WorkspaceEditKey.REMOVE, press(Key.Backspace))
        assertEquals(WorkspaceEditKey.MOVE_UP, press(Key.DirectionUp, alt = true))
        assertEquals(WorkspaceEditKey.MOVE_DOWN, press(Key.DirectionDown, alt = true))
        assertNull(press(Key.DirectionUp), "a bare arrow is the cursor's")
        assertNull(press(Key.Delete, meta = true))
        assertNull(press(Key.DirectionUp, alt = true, shift = true))
        assertNull(workspaceEditKey(KeyEvent(Key.Delete, KeyEventType.KeyUp)), "a release is not a press")
    }
}
