package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * The tool-window dock: a bar and up to two stacked panes on each side, up to two panes side by
 * side along the bottom, and [centre] in what is left. Everything it draws is decided by [dock];
 * [window] draws the body of the window with a given id.
 *
 * A side with no window anchored on it draws nothing, not even its bar, so a reader who moved
 * everything to the left has the whole width for the canvas. The bottom is the one place a bar is
 * drawn without a pane — as a 32dp strip — because that bar is the only way back to a window
 * hidden there.
 *
 * The drop targets a drag lights up are drawn over the dock, not over the window: what a drop
 * position is judged against is [DockState.bounds], which this measures for itself, so the two
 * cannot drift apart.
 */
@Composable
fun DockLayout(
    dock: DockState,
    modifier: Modifier = Modifier,
    centre: @Composable BoxScope.() -> Unit,
    window: @Composable (id: String) -> Unit,
) {
    val density = LocalDensity.current
    val edgePx = with(density) { DOCK_DROP_EDGE.toPx() }
    // The window's width when it has not been measured yet, which is only the first frame.
    fun windowWidth() = with(density) { (dock.bounds?.width ?: 1600f).toDp() }

    val leftTop = dock.visibleAt(ToolWindowAnchor.LEFT_TOP)
    val leftBottom = dock.visibleAt(ToolWindowAnchor.LEFT_BOTTOM)
    val rightTop = dock.visibleAt(ToolWindowAnchor.RIGHT_TOP)
    val rightBottom = dock.visibleAt(ToolWindowAnchor.RIGHT_BOTTOM)
    val bottomLeft = dock.visibleAt(ToolWindowAnchor.BOTTOM_LEFT)
    val bottomRight = dock.visibleAt(ToolWindowAnchor.BOTTOM_RIGHT)
    val leftButtons = dock.buttonsAt(ToolWindowAnchor.LEFT_TOP, ToolWindowAnchor.LEFT_BOTTOM)
    val rightButtons = dock.buttonsAt(ToolWindowAnchor.RIGHT_TOP, ToolWindowAnchor.RIGHT_BOTTOM)
    val bottomLeftButtons = dock.buttonsAt(ToolWindowAnchor.BOTTOM_LEFT)
    val bottomRightButtons = dock.buttonsAt(ToolWindowAnchor.BOTTOM_RIGHT)

    Box(modifier.onGloballyPositioned { dock.bounds = it.boundsInWindow() }) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f)) {
                if (leftButtons.isNotEmpty()) {
                    DockBar(dock, ToolWindowAnchor.LEFT_TOP, leftButtons, edgePx,
                        isDropTarget = dock.isDropTarget(ToolWindowAnchor.LEFT_TOP, ToolWindowAnchor.LEFT_BOTTOM))
                }
                if (leftTop != null || leftBottom != null) {
                    Box(Modifier.width(dock.leftPaneWidth).fillMaxHeight()) {
                        StackedPanes(dock, leftTop, leftBottom, dock.leftSplit, dock::moveLeftSplit, edgePx, window)
                    }
                    DraggableVerticalDivider(onDrag = { delta ->
                        dock.resizeLeftPane(
                            delta = with(density) { delta.toDp() },
                            windowWidth = windowWidth(),
                            rightPaneShowing = rightTop != null || rightBottom != null,
                        )
                    })
                }

                Box(Modifier.weight(1f).fillMaxHeight().clipToBounds(), content = centre)

                if (rightTop != null || rightBottom != null) {
                    DraggableVerticalDivider(onDrag = { delta ->
                        dock.resizeRightPane(delta = with(density) { delta.toDp() }, windowWidth = windowWidth())
                    })
                    Box(Modifier.width(dock.rightPaneWidth).fillMaxHeight()) {
                        StackedPanes(dock, rightTop, rightBottom, dock.rightSplit, dock::moveRightSplit, edgePx, window)
                    }
                }
                if (rightButtons.isNotEmpty()) {
                    DockBar(dock, ToolWindowAnchor.RIGHT_TOP, rightButtons, edgePx,
                        isDropTarget = dock.isDropTarget(ToolWindowAnchor.RIGHT_TOP, ToolWindowAnchor.RIGHT_BOTTOM))
                }
            }

            if (bottomLeft != null || bottomRight != null) {
                DraggableHorizontalDivider(onDrag = { delta -> dock.resizeBottomPane(with(density) { delta.toDp() }) })
                Row(Modifier.height(dock.bottomPaneHeight).fillMaxWidth()) {
                    if (bottomLeftButtons.isNotEmpty()) {
                        DockBar(dock, ToolWindowAnchor.LEFT_TOP, bottomLeftButtons, edgePx,
                            isDropTarget = dock.isDropTarget(ToolWindowAnchor.BOTTOM_LEFT))
                    }
                    val both = bottomLeft != null && bottomRight != null
                    if (bottomLeft != null) {
                        Box(Modifier.weight(if (both) dock.bottomSplit else 1f).fillMaxHeight()) {
                            WindowSlot(dock, bottomLeft, edgePx, window)
                        }
                    }
                    if (both) DraggableVerticalDivider(onDrag = { delta -> dock.moveBottomSplit(delta / 800f) })
                    if (bottomRight != null) {
                        Box(Modifier.weight(if (both) 1f - dock.bottomSplit else 1f).fillMaxHeight()) {
                            WindowSlot(dock, bottomRight, edgePx, window)
                        }
                    }
                    if (bottomRightButtons.isNotEmpty()) {
                        DockBar(dock, ToolWindowAnchor.RIGHT_TOP, bottomRightButtons, edgePx,
                            isDropTarget = dock.isDropTarget(ToolWindowAnchor.BOTTOM_RIGHT))
                    }
                }
            } else if (bottomLeftButtons.isNotEmpty() || bottomRightButtons.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (bottomLeftButtons.isNotEmpty()) {
                        DockBar(dock, ToolWindowAnchor.LEFT_TOP, bottomLeftButtons, edgePx,
                            isDropTarget = dock.isDropTarget(ToolWindowAnchor.BOTTOM_LEFT))
                    }
                    Spacer(Modifier.weight(1f))
                    if (bottomRightButtons.isNotEmpty()) {
                        DockBar(dock, ToolWindowAnchor.RIGHT_TOP, bottomRightButtons, edgePx,
                            isDropTarget = dock.isDropTarget(ToolWindowAnchor.BOTTOM_RIGHT))
                    }
                }
            }
        }

        if (dock.draggingId != null) DockDropTargets(dock)
    }
}

/** One side's bar: a button per window anchored there, lit while a drag is over that side. */
@Composable
private fun DockBar(
    dock: DockState,
    anchor: ToolWindowAnchor,
    buttons: List<Pair<String, ImageVector>>,
    edgePx: Float,
    isDropTarget: Boolean,
) {
    ToolWindowBar(
        anchor = anchor,
        windows = buttons,
        activeWindowId = buttons.firstOrNull { (id, _) -> id !in dock.hiddenIds }?.first,
        onWindowClick = dock::toggle,
        onWindowDragStart = { id, position -> dock.beginDrag(id, position, edgePx) },
        onWindowDragMove = { position -> dock.updateDragTarget(position, edgePx) },
        onWindowDragEnd = dock::endDrag,
        onWindowDragCancel = dock::cancelDrag,
        isDropTarget = isDropTarget,
    )
}

/** A side's two slots: both stacked at [split] when both are showing, else the one that is. */
@Composable
private fun StackedPanes(
    dock: DockState,
    top: String?,
    bottom: String?,
    split: Float,
    onSplit: (Float) -> Unit,
    edgePx: Float,
    window: @Composable (id: String) -> Unit,
) {
    if (top != null && bottom != null) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(split).fillMaxWidth()) { WindowSlot(dock, top, edgePx, window) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
            DraggableHorizontalDivider(onDrag = { delta -> onSplit(delta / 600f) })
            Box(Modifier.weight(1f - split).fillMaxWidth()) { WindowSlot(dock, bottom, edgePx, window) }
        }
    } else {
        WindowSlot(dock, top ?: bottom ?: return, edgePx, window)
    }
}

@Composable
private fun WindowSlot(dock: DockState, id: String, edgePx: Float, window: @Composable (id: String) -> Unit) {
    ToolWindowPane(
        title = dock.titleOf(id),
        isBeingDragged = dock.draggingId == id,
        onClose = { dock.hide(id) },
        onDragStart = { position -> dock.beginDrag(id, position, edgePx) },
        onDragMove = { position -> dock.updateDragTarget(position, edgePx) },
        onDragEnd = dock::endDrag,
        onDragCancel = dock::cancelDrag,
    ) {
        window(id)
    }
}

/**
 * The six places a dragged window can land, tinted, with the one under the pointer stronger.
 * These are a picture of [dropAnchorAt]'s regions rather than the regions themselves — the drop is
 * decided by the pointer's distance from the edge, and these are sized to read, not to match.
 */
@Composable
private fun BoxScope.DockDropTargets(dock: DockState) {
    val sideDropWidth = 220.dp
    val bottomDropHeight = 160.dp
    val baseColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val activeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)
    fun tint(anchor: ToolWindowAnchor) = if (dock.dragTarget == anchor) activeColor else baseColor

    @Composable
    fun Side(alignment: Alignment, top: ToolWindowAnchor, bottom: ToolWindowAnchor) {
        Column(Modifier.align(alignment).padding(bottom = bottomDropHeight).width(sideDropWidth).fillMaxHeight()) {
            Box(Modifier.weight(1f).fillMaxWidth().background(tint(top)))
            HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), thickness = 1.dp)
            Box(Modifier.weight(1f).fillMaxWidth().background(tint(bottom)))
        }
    }
    Side(Alignment.TopStart, ToolWindowAnchor.LEFT_TOP, ToolWindowAnchor.LEFT_BOTTOM)
    Side(Alignment.TopEnd, ToolWindowAnchor.RIGHT_TOP, ToolWindowAnchor.RIGHT_BOTTOM)
    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(0.5f).height(bottomDropHeight).background(tint(ToolWindowAnchor.BOTTOM_LEFT)))
    Box(Modifier.align(Alignment.BottomEnd).fillMaxWidth(0.5f).height(bottomDropHeight).background(tint(ToolWindowAnchor.BOTTOM_RIGHT)))
}
