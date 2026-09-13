package ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.prefs.Preferences

private const val PREF_LEFT_PANE_WIDTH = "left_pane_width"
private const val PREF_RIGHT_PANE_WIDTH = "right_pane_width"
private const val PREF_BOTTOM_PANE_HEIGHT = "bottom_pane_height"
private const val PREF_LEFT_SPLIT = "left_split"
private const val PREF_RIGHT_SPLIT = "right_split"
private const val PREF_BOTTOM_SPLIT = "bottom_split"
private const val PREF_WINDOW_ANCHORS = "tool_window_anchors"

/** The narrowest a side pane can be dragged to. The inspector's panels are laid out against it. */
val MIN_SIDE_PANE_WIDTH = 150.dp

/** How far in from an edge a dragged window has to be released to land on that edge. */
val DOCK_DROP_EDGE = 120.dp

/**
 * Where the tool windows sit, which are hidden, how wide the panes are, and a drag in flight.
 *
 * Everything here is UI-only and Compose-observable; the anchors and the pane sizes persist to
 * [prefs] as they change, the hidden set and the drag do not. It is a class rather than a dozen
 * `remember`s in `App` so that the layout drawing it ([DockLayout]) can be rendered in a test
 * without the rest of the window, and so the rules — which windows a bar lists, where a drop
 * lands, how far a pane may grow — are functions that can be asserted on rather than lambdas
 * inside a composition.
 *
 * A window's [ToolWindowConfig.anchor] is its *default*; [anchorOf] is where it is now.
 */
class DockState(
    private val prefs: Preferences,
    val windows: List<ToolWindowConfig>,
) {
    private var anchors by mutableStateOf(
        windows.associate { it.id to it.anchor } + parseAnchors(prefs.get(PREF_WINDOW_ANCHORS, ""))
            .filterKeys { id -> windows.any { it.id == id } }
    )
    var hiddenIds by mutableStateOf(setOf<String>())
        private set

    var draggingId by mutableStateOf<String?>(null)
        private set
    var dragTarget by mutableStateOf<ToolWindowAnchor?>(null)
        private set

    /** The dock's own rectangle in window coordinates — what a drop position is judged against. */
    var bounds by mutableStateOf<Rect?>(null)

    var leftPaneWidth: Dp by mutableStateOf(prefs.getFloat(PREF_LEFT_PANE_WIDTH, 250f).dp)
        private set
    var rightPaneWidth: Dp by mutableStateOf(prefs.getFloat(PREF_RIGHT_PANE_WIDTH, 300f).dp)
        private set
    var bottomPaneHeight: Dp by mutableStateOf(prefs.getFloat(PREF_BOTTOM_PANE_HEIGHT, 220f).dp)
        private set
    var leftSplit by mutableStateOf(prefs.getFloat(PREF_LEFT_SPLIT, 0.55f))
        private set
    var rightSplit by mutableStateOf(prefs.getFloat(PREF_RIGHT_SPLIT, 0.6f))
        private set
    var bottomSplit by mutableStateOf(prefs.getFloat(PREF_BOTTOM_SPLIT, 0.5f))
        private set

    // ═══ Where things are ═══

    fun anchorOf(id: String): ToolWindowAnchor =
        anchors[id] ?: windows.first { it.id == id }.anchor

    fun titleOf(id: String): String = windows.firstOrNull { it.id == id }?.title ?: id

    /** The window drawn at [anchor], or null when none is there or the one there is hidden. */
    fun visibleAt(anchor: ToolWindowAnchor): String? =
        windows.firstOrNull { anchorOf(it.id) == anchor && it.id !in hiddenIds }?.id

    /** What a bar on this side lists: every window anchored at any of [anchorsOnSide], hidden or not. */
    fun buttonsAt(vararg anchorsOnSide: ToolWindowAnchor): List<Pair<String, ImageVector>> =
        windows.filter { anchorOf(it.id) in anchorsOnSide }.map { it.id to it.icon }

    fun isDropTarget(vararg anchorsOnSide: ToolWindowAnchor): Boolean = dragTarget in anchorsOnSide

    // ═══ Showing and hiding ═══

    fun toggle(id: String) {
        hiddenIds = if (id in hiddenIds) hiddenIds - id else hiddenIds + id
    }

    fun hide(id: String) {
        hiddenIds = hiddenIds + id
    }

    /** Hides every window if any is showing; otherwise shows them all. */
    fun toggleAll() {
        val ids = windows.map { it.id }
        hiddenIds = if (ids.any { it !in hiddenIds }) ids.toSet() else emptySet()
    }

    // ═══ Moving ═══

    fun move(id: String, to: ToolWindowAnchor) {
        if (anchorOf(id) == to) return
        anchors = anchors + (id to to)
        prefs.put(PREF_WINDOW_ANCHORS, anchors.entries.joinToString(";") { "${it.key}:${it.value}" })
    }

    fun beginDrag(id: String, positionInWindow: Offset, edgePx: Float) {
        draggingId = id
        updateDragTarget(positionInWindow, edgePx)
    }

    fun updateDragTarget(positionInWindow: Offset?, edgePx: Float) {
        val bounds = bounds
        dragTarget = if (positionInWindow == null || bounds == null) null else dropAnchorAt(positionInWindow, bounds, edgePx)
    }

    /** Lands the dragged window on the target under it, if any, and ends the drag either way. */
    fun endDrag() {
        val dragged = draggingId
        val target = dragTarget
        if (dragged != null && target != null) move(dragged, target)
        cancelDrag()
    }

    fun cancelDrag() {
        draggingId = null
        dragTarget = null
    }

    // ═══ Resizing ═══

    /**
     * The centre keeps at least 260dp: a left pane may grow into the window's width minus the
     * right pane if one is showing, and never below [MIN_SIDE_PANE_WIDTH].
     */
    fun resizeLeftPane(delta: Dp, windowWidth: Dp, rightPaneShowing: Boolean) {
        val reservedRight = if (rightPaneShowing) rightPaneWidth else 0.dp
        val max = (windowWidth - reservedRight - 260.dp).coerceAtLeast(MIN_SIDE_PANE_WIDTH)
        leftPaneWidth = (leftPaneWidth + delta).coerceIn(MIN_SIDE_PANE_WIDTH, max)
        prefs.putFloat(PREF_LEFT_PANE_WIDTH, leftPaneWidth.value)
    }

    /** The inspector opens at 300dp and drags down to 200dp — the width its panels are checked at. */
    fun resizeRightPane(delta: Dp, windowWidth: Dp) {
        val max = (windowWidth - 260.dp).coerceAtLeast(200.dp)
        rightPaneWidth = (rightPaneWidth - delta).coerceIn(200.dp, max)
        prefs.putFloat(PREF_RIGHT_PANE_WIDTH, rightPaneWidth.value)
    }

    fun resizeBottomPane(delta: Dp) {
        bottomPaneHeight = (bottomPaneHeight - delta).coerceIn(120.dp, 500.dp)
        prefs.putFloat(PREF_BOTTOM_PANE_HEIGHT, bottomPaneHeight.value)
    }

    /** [delta] is a share of the pane, already scaled by the caller; the split stays within 20–80%. */
    fun moveLeftSplit(delta: Float) {
        leftSplit = (leftSplit + delta).coerceIn(0.2f, 0.8f)
        prefs.putFloat(PREF_LEFT_SPLIT, leftSplit)
    }

    fun moveRightSplit(delta: Float) {
        rightSplit = (rightSplit + delta).coerceIn(0.2f, 0.8f)
        prefs.putFloat(PREF_RIGHT_SPLIT, rightSplit)
    }

    fun moveBottomSplit(delta: Float) {
        bottomSplit = (bottomSplit + delta).coerceIn(0.2f, 0.8f)
        prefs.putFloat(PREF_BOTTOM_SPLIT, bottomSplit)
    }
}

/**
 * Which anchor a window released at [position] lands on: within [edgePx] of the left or right
 * edge it is the top or bottom half of that side, within [edgePx] of the bottom it is the left or
 * right half of the bottom, and anywhere else is nowhere. The sides are tested first, so a corner
 * is a side.
 */
fun dropAnchorAt(position: Offset, bounds: Rect, edgePx: Float): ToolWindowAnchor? = when {
    position.x <= bounds.left + edgePx && position.y <= bounds.center.y -> ToolWindowAnchor.LEFT_TOP
    position.x <= bounds.left + edgePx -> ToolWindowAnchor.LEFT_BOTTOM
    position.x >= bounds.right - edgePx && position.y <= bounds.center.y -> ToolWindowAnchor.RIGHT_TOP
    position.x >= bounds.right - edgePx -> ToolWindowAnchor.RIGHT_BOTTOM
    position.y >= bounds.bottom - edgePx && position.x <= bounds.center.x -> ToolWindowAnchor.BOTTOM_LEFT
    position.y >= bounds.bottom - edgePx -> ToolWindowAnchor.BOTTOM_RIGHT
    else -> null
}

/** `id:ANCHOR;id:ANCHOR`; a part that does not parse is skipped rather than failing the rest. */
private fun parseAnchors(saved: String): Map<String, ToolWindowAnchor> =
    saved.split(";").mapNotNull { part ->
        val (id, anchor) = part.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
        runCatching { ToolWindowAnchor.valueOf(anchor) }.getOrNull()?.let { id to it }
    }.toMap()
