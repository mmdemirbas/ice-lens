package ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Storage
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The dock's rules, asserted without drawing anything: which window a bar lists, where a drop
 * lands, how far a pane may grow, and what survives a restart.
 */
class DockStateTest {

    private lateinit var prefs: Preferences

    @BeforeTest
    fun setUp() {
        prefs = Preferences.userRoot().node("icelens-dock-test-${System.nanoTime()}")
    }

    @AfterTest
    fun tearDown() {
        prefs.removeNode()
    }

    private val windows = listOf(
        ToolWindowConfig("workspace", "Workspace", Icons.Default.Storage, ToolWindowAnchor.LEFT_TOP),
        ToolWindowConfig("structure", "Structure", Icons.Default.AccountTree, ToolWindowAnchor.LEFT_BOTTOM),
        ToolWindowConfig("inspector", "Inspector", Icons.Default.Info, ToolWindowAnchor.RIGHT_TOP),
    )

    private fun dock() = DockState(prefs, windows)

    @Test
    fun `a bar lists every window on its side, hidden ones included, and a slot only the visible one`() {
        val dock = dock()
        dock.toggle("structure")

        assertEquals(listOf("workspace", "structure"), dock.buttonsAt(ToolWindowAnchor.LEFT_TOP, ToolWindowAnchor.LEFT_BOTTOM).map { it.first })
        assertEquals("workspace", dock.visibleAt(ToolWindowAnchor.LEFT_TOP))
        assertNull(dock.visibleAt(ToolWindowAnchor.LEFT_BOTTOM), "a hidden window leaves its slot empty")
        assertEquals("inspector", dock.visibleAt(ToolWindowAnchor.RIGHT_TOP))
        assertEquals(emptyList(), dock.buttonsAt(ToolWindowAnchor.BOTTOM_LEFT))
    }

    @Test
    fun `a number chord names the window in that position of the bars, and one past the end names none`() {
        val dock = dock()
        assertEquals(listOf("workspace", "structure", "inspector"), (1..3).map { dock.windowAt(it) })
        assertNull(dock.windowAt(4))
        assertNull(dock.windowAt(0))
        assertEquals(true, dock.toggleNumbered(2))
        assertEquals(setOf("structure"), dock.hiddenIds)
        assertEquals(true, dock.toggleNumbered(2))
        assertEquals(emptySet(), dock.hiddenIds)
        assertEquals(false, dock.toggleNumbered(9), "an unassigned number falls through and hides nothing")
        assertEquals(emptySet(), dock.hiddenIds)
    }

    @Test
    fun `toggle all hides everything while anything shows, and shows everything once nothing does`() {
        val dock = dock()
        dock.toggle("inspector")
        dock.toggleAll()
        assertEquals(setOf("workspace", "structure", "inspector"), dock.hiddenIds)
        dock.toggleAll()
        assertEquals(emptySet(), dock.hiddenIds)
    }

    @Test
    fun `a move persists and a fresh dock reads it back, ignoring what it cannot parse`() {
        dock().move("inspector", ToolWindowAnchor.BOTTOM_RIGHT)
        prefs.put("tool_window_anchors", prefs.get("tool_window_anchors", "") + ";ghost:LEFT_TOP;structure:NOWHERE")

        val reopened = dock()
        assertEquals(ToolWindowAnchor.BOTTOM_RIGHT, reopened.anchorOf("inspector"))
        assertEquals(ToolWindowAnchor.LEFT_BOTTOM, reopened.anchorOf("structure"), "an anchor that does not parse keeps the default")
        assertEquals(listOf("inspector"), reopened.buttonsAt(ToolWindowAnchor.BOTTOM_RIGHT).map { it.first })
        assertEquals(emptyList(), reopened.buttonsAt(ToolWindowAnchor.RIGHT_TOP, ToolWindowAnchor.RIGHT_BOTTOM))
    }

    @Test
    fun `a drop lands by the pointer's distance from an edge, sides before bottom, and nowhere in the middle`() {
        val bounds = Rect(0f, 0f, 1000f, 600f)
        fun at(x: Float, y: Float) = dropAnchorAt(Offset(x, y), bounds, edgePx = 100f)

        assertEquals(ToolWindowAnchor.LEFT_TOP, at(50f, 100f))
        assertEquals(ToolWindowAnchor.LEFT_BOTTOM, at(50f, 400f))
        assertEquals(ToolWindowAnchor.RIGHT_TOP, at(950f, 100f))
        assertEquals(ToolWindowAnchor.RIGHT_BOTTOM, at(950f, 400f))
        assertEquals(ToolWindowAnchor.BOTTOM_LEFT, at(300f, 550f))
        assertEquals(ToolWindowAnchor.BOTTOM_RIGHT, at(700f, 550f))
        assertEquals(ToolWindowAnchor.LEFT_BOTTOM, at(50f, 550f), "a corner is a side")
        assertNull(at(500f, 300f))
    }

    @Test
    fun `a drag released over a target moves the window there, and one released elsewhere moves nothing`() {
        val dock = dock()
        dock.bounds = Rect(0f, 0f, 1000f, 600f)

        dock.beginDrag("inspector", Offset(500f, 300f), edgePx = 100f)
        assertEquals("inspector", dock.draggingId)
        assertNull(dock.dragTarget)
        dock.updateDragTarget(Offset(20f, 500f), edgePx = 100f)
        assertEquals(ToolWindowAnchor.LEFT_BOTTOM, dock.dragTarget)
        dock.endDrag()
        assertEquals(ToolWindowAnchor.LEFT_BOTTOM, dock.anchorOf("inspector"))
        assertNull(dock.draggingId)

        dock.beginDrag("workspace", Offset(500f, 300f), edgePx = 100f)
        dock.endDrag()
        assertEquals(ToolWindowAnchor.LEFT_TOP, dock.anchorOf("workspace"))

        dock.beginDrag("workspace", Offset(950f, 100f), edgePx = 100f)
        assertEquals(ToolWindowAnchor.RIGHT_TOP, dock.dragTarget)
        dock.cancelDrag()
        assertEquals(ToolWindowAnchor.LEFT_TOP, dock.anchorOf("workspace"), "a cancelled drag moves nothing")
        assertNull(dock.dragTarget)
    }

    @Test
    fun `a side pane keeps the centre at least 260dp and stops at its minimum`() {
        val dock = dock()
        dock.resizeLeftPane(delta = 2000.dp, windowWidth = 1000.dp, rightPaneShowing = true)
        assertEquals(1000.dp - 300.dp - 260.dp, dock.leftPaneWidth, "the right pane's 300dp is reserved while it shows")
        dock.resizeLeftPane(delta = 2000.dp, windowWidth = 1000.dp, rightPaneShowing = false)
        assertEquals(740.dp, dock.leftPaneWidth)
        dock.resizeLeftPane(delta = (-2000).dp, windowWidth = 1000.dp, rightPaneShowing = false)
        assertEquals(MIN_SIDE_PANE_WIDTH, dock.leftPaneWidth)

        // Dragging the right pane's divider leftwards is a negative delta that widens it.
        dock.resizeRightPane(delta = (-2000).dp, windowWidth = 1000.dp)
        assertEquals(740.dp, dock.rightPaneWidth)
        dock.resizeRightPane(delta = 2000.dp, windowWidth = 1000.dp)
        assertEquals(200.dp, dock.rightPaneWidth)

        val reopened = dock()
        assertEquals(MIN_SIDE_PANE_WIDTH, reopened.leftPaneWidth)
        assertEquals(200.dp, reopened.rightPaneWidth)
    }

    @Test
    fun `a split stays between a fifth and four fifths`() {
        val dock = dock()
        dock.moveLeftSplit(1f)
        assertEquals(0.8f, dock.leftSplit)
        dock.moveLeftSplit(-1f)
        assertEquals(0.2f, dock.leftSplit)
        dock.moveBottomSplit(-1f)
        assertEquals(0.2f, dock().bottomSplit, "the split persists")
    }
}
