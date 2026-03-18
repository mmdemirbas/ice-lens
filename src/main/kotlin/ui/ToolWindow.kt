package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ToolWindowBar(
    anchor: model.ToolWindowAnchor,
    windows: List<Pair<String, ImageVector>>,
    activeWindowId: String?,
    onWindowClick: (String) -> Unit,
    onWindowDragStart: ((String, Offset) -> Unit)? = null,
    onWindowDragMove: ((Offset) -> Unit)? = null,
    onWindowDragEnd: (() -> Unit)? = null,
    onWindowDragCancel: (() -> Unit)? = null,
    isDropTarget: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    val isVertical = anchor == model.ToolWindowAnchor.LEFT_TOP ||
        anchor == model.ToolWindowAnchor.LEFT_BOTTOM ||
        anchor == model.ToolWindowAnchor.RIGHT_TOP ||
        anchor == model.ToolWindowAnchor.RIGHT_BOTTOM
    val barColor = when {
        isDropTarget -> colors.primaryContainer.copy(alpha = 0.72f)
        else -> colors.surfaceVariant
    }
    if (isVertical) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(32.dp)
                .background(barColor),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            windows.forEach { (id, icon) ->
                val isActive = id == activeWindowId
                var iconBounds by remember { mutableStateOf<Rect?>(null) }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(if (isActive) colors.surface else Color.Transparent)
                        .onGloballyPositioned { coords -> iconBounds = coords.boundsInWindow() }
                        .clickable { onWindowClick(id) },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .pointerInput(id, onWindowDragStart, onWindowDragMove, onWindowDragEnd, onWindowDragCancel) {
                                if (onWindowDragStart != null && onWindowDragMove != null && onWindowDragEnd != null) {
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            val bounds = iconBounds
                                            if (bounds != null) onWindowDragStart(id, Offset(bounds.left + offset.x, bounds.top + offset.y))
                                        },
                                        onDragEnd = onWindowDragEnd,
                                        onDragCancel = { onWindowDragCancel?.invoke() }
                                    ) { change, _ ->
                                        val bounds = iconBounds
                                        if (bounds != null) onWindowDragMove(Offset(bounds.left + change.position.x, bounds.top + change.position.y))
                                        change.consume()
                                    }
                                }
                            }
                    )
                    Icon(
                        imageVector = icon,
                        contentDescription = id,
                        modifier = Modifier.size(20.dp),
                        tint = if (isActive) colors.primary else colors.onSurfaceVariant
                    )
                }
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .background(barColor),
            verticalAlignment = Alignment.CenterVertically
        ) {
            windows.forEach { (id, icon) ->
                val isActive = id == activeWindowId
                var iconBounds by remember { mutableStateOf<Rect?>(null) }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(if (isActive) colors.surface else Color.Transparent)
                        .onGloballyPositioned { coords -> iconBounds = coords.boundsInWindow() }
                        .clickable { onWindowClick(id) },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .pointerInput(id, onWindowDragStart, onWindowDragMove, onWindowDragEnd, onWindowDragCancel) {
                                if (onWindowDragStart != null && onWindowDragMove != null && onWindowDragEnd != null) {
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            val bounds = iconBounds
                                            if (bounds != null) onWindowDragStart(id, Offset(bounds.left + offset.x, bounds.top + offset.y))
                                        },
                                        onDragEnd = onWindowDragEnd,
                                        onDragCancel = { onWindowDragCancel?.invoke() }
                                    ) { change, _ ->
                                        val bounds = iconBounds
                                        if (bounds != null) onWindowDragMove(Offset(bounds.left + change.position.x, bounds.top + change.position.y))
                                        change.consume()
                                    }
                                }
                            }
                    )
                    Icon(
                        imageVector = icon,
                        contentDescription = id,
                        modifier = Modifier.size(20.dp),
                        tint = if (isActive) colors.primary else colors.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun ToolWindowPane(
    title: String,
    isBeingDragged: Boolean = false,
    onClose: () -> Unit,
    onDragStart: ((Offset) -> Unit)? = null,
    onDragMove: ((Offset) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    onDragCancel: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize().background(colors.surface)) {
        var titleBounds by remember { mutableStateOf<Rect?>(null) }
        val isDraggable = onDragStart != null && onDragMove != null && onDragEnd != null
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(if (isBeingDragged) colors.primaryContainer.copy(alpha = 0.72f) else colors.surfaceVariant)
                .padding(horizontal = 8.dp)
                .onGloballyPositioned { coords -> titleBounds = coords.boundsInWindow() }
                .then(if (isDraggable) Modifier.pointerHoverIcon(PointerIcon(java.awt.Cursor(java.awt.Cursor.MOVE_CURSOR))) else Modifier)
                .pointerInput(onDragStart, onDragMove, onDragEnd, onDragCancel) {
                    if (onDragStart != null && onDragMove != null && onDragEnd != null) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val bounds = titleBounds
                                if (bounds != null) onDragStart(Offset(bounds.left + offset.x, bounds.top + offset.y))
                            },
                            onDragEnd = onDragEnd,
                            onDragCancel = { onDragCancel?.invoke() }
                        ) { change, _ ->
                            val bounds = titleBounds
                            if (bounds != null) onDragMove(Offset(bounds.left + change.position.x, bounds.top + change.position.y))
                            change.consume()
                        }
                    }
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isDraggable) {
                Icon(
                    Icons.Default.DragIndicator,
                    contentDescription = "Drag to reposition",
                    modifier = Modifier.size(14.dp),
                    tint = colors.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = title.uppercase(),
                fontSize = 10.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClose, modifier = Modifier.size(20.dp)) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = "Close",
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}
