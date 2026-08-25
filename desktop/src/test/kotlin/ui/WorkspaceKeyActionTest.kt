package ui

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
}
