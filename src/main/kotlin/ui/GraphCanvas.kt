package ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import model.GraphModel
import model.GraphNode
import model.GraphEdge
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private fun tonedEdgeColor(base: Color, sourceId: String): Color {
    val hash = sourceId.hashCode()
    val toneStep = ((hash and 0x7fffffff) % 9) - 4 // -4..4
    val delta = toneStep * 0.05f
    fun ch(v: Float): Float = (v + delta).coerceIn(0f, 1f)
    return Color(ch(base.red), ch(base.green), ch(base.blue), base.alpha)
}

private fun liftEdgeColor(base: Color, target: Color, minimumBrightness: Float): Color {
    val baseBrightness = perceivedBrightness(base)
    if (baseBrightness >= minimumBrightness) return base

    val targetBrightness = perceivedBrightness(target)
    val denominator = (targetBrightness - baseBrightness).coerceAtLeast(0.001f)
    val mix = ((minimumBrightness - baseBrightness) / denominator).coerceIn(0f, 1f)
    return Color(
        red = base.red + (target.red - base.red) * mix,
        green = base.green + (target.green - base.green) * mix,
        blue = base.blue + (target.blue - base.blue) * mix,
        alpha = base.alpha
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GraphCanvas(
    graph: GraphModel,
    graphRevision: Int = 0,
    fitGraphRequest: Int = 0,
    selectedNodeIds: Set<String>,
    isSelectMode: Boolean,
    zoom: Float,
    onZoomChange: (Float) -> Unit,
    onSelectionChange: (Set<String>) -> Unit,
    onEmptyAreaDoubleClick: () -> Unit = {},
    onNodeDoubleClick: (GraphNode) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val isDarkSurface = isDarkSurface(colors.surface)
    val selectionColor = if (isDarkSurface) DarkSelectionAccent else colors.primary
    // Consumed to ensure recomposition/reset-sensitive logic sees relayout events.
    @Suppress("UNUSED_VARIABLE")
    val _graphRevision = graphRevision

    val offsetAnim = remember { Animatable(Offset(100f, 100f), Offset.VectorConverter) }
    val coroutineScope = rememberCoroutineScope()

    // Smooth internal state to eliminate lag during gestures
    var localZoom by remember { mutableStateOf(zoom) }

    // Undo stack: snapshots of node positions before each drag
    data class PositionSnapshot(val positions: Map<String, Pair<Double, Double>>)
    val undoStack = remember { mutableListOf<PositionSnapshot>() }
    val maxUndoDepth = MAX_UNDO_DEPTH

    var hoveredNodeId by remember { mutableStateOf<String?>(null) }
    var activeTooltipNodeId by remember { mutableStateOf<String?>(null) }
    var marqueeStart by remember { mutableStateOf<Offset?>(null) }
    var marqueeEnd by remember { mutableStateOf<Offset?>(null) }
    var marqueeToggleSelection by remember { mutableStateOf(false) }
    var shiftPressedNow by remember { mutableStateOf(false) }

    data class GraphExtents(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
        val width: Float get() = (maxX - minX).coerceAtLeast(1f)
        val height: Float get() = (maxY - minY).coerceAtLeast(1f)
    }

    val extents by remember {
        derivedStateOf {
            if (graph.nodes.isEmpty()) {
                GraphExtents(0f, 0f, max(1f, graph.width.toFloat()), max(1f, graph.height.toFloat()))
            } else {
                val minX = graph.nodes.minOf { graph.getPosition(it.id).x }
                val minY = graph.nodes.minOf { graph.getPosition(it.id).y }
                val maxX = graph.nodes.maxOf { graph.getPosition(it.id).x + it.width.toFloat() }
                val maxY = graph.nodes.maxOf { graph.getPosition(it.id).y + it.height.toFloat() }
                GraphExtents(minX, minY, maxX, maxY)
            }
        }
    }

    val nodeById = graph.nodeById

    // Helpers to read/write Compose-observable positions
    fun nodeX(node: GraphNode): Float = graph.getPosition(node.id).x
    fun nodeY(node: GraphNode): Float = graph.getPosition(node.id).y
    fun moveNode(nodeId: String, dx: Float, dy: Float) {
        val old = graph.getPosition(nodeId)
        graph.positions[nodeId] = Offset(old.x + dx, old.y + dy)
    }

    val latestSelectedNodeIds by rememberUpdatedState(selectedNodeIds)

    val snapshotManifestOutLaneByEdgeId = remember(graph.nodes, graph.edges) {
        val bySource = graph.edges.groupBy { it.fromId }
        buildMap<String, Int> {
            bySource.forEach { (sourceId, sourceEdges) ->
                val sourceNode = nodeById[sourceId] ?: return@forEach
                if (sourceNode !is GraphNode.SnapshotNode) return@forEach
                val ranked = sourceEdges
                    .mapNotNull { edge ->
                        val targetNode = nodeById[edge.toId] ?: return@mapNotNull null
                        if (targetNode !is GraphNode.ManifestNode) return@mapNotNull null
                        edge to nodeY(targetNode)
                    }
                    .sortedBy { it.second }
                ranked.forEachIndexed { index, (edge, _) ->
                    put(edge.id, index)
                }
            }
        }
    }

    val snapshotManifestInLaneByEdgeId = remember(graph.nodes, graph.edges) {
        val byTarget = graph.edges.groupBy { it.toId }
        buildMap<String, Int> {
            byTarget.forEach { (targetId, targetEdges) ->
                val targetNode = nodeById[targetId] ?: return@forEach
                if (targetNode !is GraphNode.ManifestNode) return@forEach
                val ranked = targetEdges
                    .mapNotNull { edge ->
                        val sourceNode = nodeById[edge.fromId] ?: return@mapNotNull null
                        if (sourceNode !is GraphNode.SnapshotNode) return@mapNotNull null
                        edge to nodeY(sourceNode)
                    }
                    .sortedBy { it.second }
                ranked.forEachIndexed { index, (edge, _) ->
                    put(edge.id, index)
                }
            }
        }
    }

    fun DrawScope.drawEdge(edge: GraphEdge, source: GraphNode, target: GraphNode, color: Color, strokeWidth: Float) {
        val isSnapshotToManifest = source is GraphNode.SnapshotNode && target is GraphNode.ManifestNode
        val outIndex = snapshotManifestOutLaneByEdgeId[edge.id] ?: 0
        val inIndex = snapshotManifestInLaneByEdgeId[edge.id] ?: 0
        val outCount = if (isSnapshotToManifest) {
            graph.edges.count { it.fromId == edge.fromId && nodeById[it.toId] is GraphNode.ManifestNode }
        } else 1
        val inCount = if (isSnapshotToManifest) {
            graph.edges.count { it.toId == edge.toId && nodeById[it.fromId] is GraphNode.SnapshotNode }
        } else 1
        val outCentered = outIndex - ((outCount - 1) / 2f)
        val inCentered = inIndex - ((inCount - 1) / 2f)
        val laneYSpacing = 8f
        val laneXSpacing = 14f

        // Always use right-center -> left-center anchors.
        val srcPos = graph.getPosition(source.id)
        val tgtPos = graph.getPosition(target.id)
        val startX = srcPos.x + source.width.toFloat()
        val startY = srcPos.y + source.height.toFloat() / 2f
        val endX = tgtPos.x
        val endY = tgtPos.y + target.height.toFloat() / 2f
        val horizontalGap = kotlin.math.abs(endX - startX).coerceAtLeast(1f)
        // Explicit visible side ports: always leave from the right side and enter from the left side.
        val fixedPortStub = 18f
        val startStubX = startX + fixedPortStub
        val endStubX = endX - fixedPortStub
        val baseC1x = startStubX + horizontalGap * 0.22f
        val baseC2x = endStubX - horizontalGap * 0.22f
        val laneShift = if (isSnapshotToManifest) (outCentered + inCentered) * 0.5f * laneXSpacing else 0f
        val c1x = (baseC1x + laneShift).coerceAtLeast(startStubX + 4f)
        val c2x = (baseC2x + laneShift).coerceAtMost(endStubX - 4f)
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(startStubX, startY)
            cubicTo(
                c1x,
                startY,
                c2x,
                endY,
                endStubX,
                endY
            )
            lineTo(endX, endY)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeWidth)
        )
    }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfaceVariant)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown &&
                    keyEvent.key == Key.Z &&
                    (keyEvent.isMetaPressed || keyEvent.isCtrlPressed) &&
                    !keyEvent.isShiftPressed
                ) {
                    val snapshot = undoStack.removeLastOrNull()
                    if (snapshot != null) {
                        snapshot.positions.forEach { (id, pos) ->
                            graph.setPosition(id, pos.first, pos.second)
                        }
                    }
                    true
                } else {
                    false
                }
            }
            .pointerInput(isSelectMode) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val changes = event.changes
                        shiftPressedNow = event.keyboardModifiers.isShiftPressed
                        
                        if (event.type == PointerEventType.Scroll) {
                            val delta = changes.first().scrollDelta
                            val isZoom = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
                            
                            if (isZoom) {
                                // Zoom at mouse position
                                val zoomFactor = Math.pow(1.1, -delta.y.toDouble()).toFloat()
                                val oldZoom = localZoom
                                val newZoom = (oldZoom * zoomFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                
                                if (newZoom != oldZoom) {
                                    val mousePos = changes.first().position
                                    val layoutOffset = offsetAnim.value + (mousePos - offsetAnim.value) * (1 - newZoom / oldZoom)
                                    localZoom = newZoom
                                    onZoomChange(newZoom)
                                    coroutineScope.launch {
                                        offsetAnim.snapTo(layoutOffset)
                                    }
                                }
                            } else {
                                // Pan
                                coroutineScope.launch {
                                    offsetAnim.snapTo(offsetAnim.value - Offset(delta.x * 20f, delta.y * 20f))
                                }
                            }
                            changes.forEach { it.consume() }
                        } else {
                            // Detect pinch from multi-touch if reported as separate pointers
                            val zoomFactor = event.calculateZoom()
                            if (zoomFactor != 1f) {
                                val oldZoom = localZoom
                                val newZoom = (oldZoom * zoomFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                if (newZoom != oldZoom) {
                                    val centroid = event.calculateCentroid()
                                    val layoutOffset = offsetAnim.value + (centroid - offsetAnim.value) * (1 - newZoom / oldZoom)
                                    localZoom = newZoom
                                    onZoomChange(newZoom)
                                    coroutineScope.launch {
                                        offsetAnim.snapTo(layoutOffset)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Keep the transform gestures for non-scroll interactions
            .pointerInput(isSelectMode) {
                if (!isSelectMode) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        val oldZoom = localZoom
                        val newZoom = (oldZoom * gestureZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)

                        if (newZoom != oldZoom) {
                            val layoutOffset = offsetAnim.value + (centroid - offsetAnim.value) * (1 - newZoom / oldZoom)
                            localZoom = newZoom
                            onZoomChange(newZoom)
                            coroutineScope.launch {
                                offsetAnim.snapTo(layoutOffset + pan)
                            }
                        } else {
                            coroutineScope.launch {
                                offsetAnim.snapTo(offsetAnim.value + pan)
                            }
                        }
                    }
                } else {
                    detectDragGestures(
                        onDragStart = { offset ->
                            marqueeStart = offset
                            marqueeEnd = offset
                            marqueeToggleSelection = shiftPressedNow
                        },
                        onDragEnd = {
                            if (marqueeStart != null && marqueeEnd != null) {
                                val left = Math.min(marqueeStart!!.x, marqueeEnd!!.x)
                                val top = Math.min(marqueeStart!!.y, marqueeEnd!!.y)
                                val right = Math.max(marqueeStart!!.x, marqueeEnd!!.x)
                                val bottom = Math.max(marqueeStart!!.y, marqueeEnd!!.y)

                                val logLeft = (left - offsetAnim.value.x) / localZoom
                                val logTop = (top - offsetAnim.value.y) / localZoom
                                val logRight = (right - offsetAnim.value.x) / localZoom
                                val logBottom = (bottom - offsetAnim.value.y) / localZoom

                                val selRect = androidx.compose.ui.geometry.Rect(logLeft, logTop, logRight, logBottom)

                                val selected = graph.nodes.filter { n ->
                                    val nx = nodeX(n); val ny = nodeY(n)
                                    selRect.overlaps(
                                        androidx.compose.ui.geometry.Rect(
                                            nx, ny,
                                            nx + n.width.toFloat(), ny + n.height.toFloat()
                                        )
                                    )
                                }.map { it.id }.toSet()

                                if (marqueeToggleSelection) {
                                    val next = selectedNodeIds.toMutableSet()
                                    selected.forEach { id ->
                                        if (!next.add(id)) next.remove(id)
                                    }
                                    onSelectionChange(next)
                                } else {
                                    onSelectionChange(selected)
                                }
                            }
                            marqueeStart = null
                            marqueeEnd = null
                            marqueeToggleSelection = false
                        }
                    ) { change, _ ->
                        change.consume()
                        marqueeEnd = change.position
                        marqueeToggleSelection = shiftPressedNow
                    }
                }
            }
            .pointerInput(hoveredNodeId) {
                detectTapGestures(
                    onTap = {
                        if (hoveredNodeId == null) {
                            onSelectionChange(emptySet())
                        }
                    },
                    onDoubleTap = {
                        if (hoveredNodeId == null) {
                            onEmptyAreaDoubleClick()
                        }
                    }
                )
            }) {
        val viewportWidth = constraints.maxWidth.toFloat()
        val viewportHeight = constraints.maxHeight.toFloat()
        val boundsPadding = 100f

        val logicalLeft = -offsetAnim.value.x / localZoom
        val logicalTop = -offsetAnim.value.y / localZoom
        val logicalRight = logicalLeft + viewportWidth / localZoom
        val logicalBottom = logicalTop + viewportHeight / localZoom
        val cullMargin = 400f

        val visibleNodes = graph.nodes.filter { node ->
            val nodeLeft = nodeX(node)
            val nodeTop = nodeY(node)
            val nodeRight = nodeLeft + node.width.toFloat()
            val nodeBottom = nodeTop + node.height.toFloat()
            nodeRight >= logicalLeft - cullMargin &&
                nodeLeft <= logicalRight + cullMargin &&
                nodeBottom >= logicalTop - cullMargin &&
                nodeTop <= logicalBottom + cullMargin
        }
        val visibleNodeIds = visibleNodes.asSequence().map { it.id }.toHashSet()
        val visibleEdges = graph.edges.filter { edge ->
            edge.fromId in visibleNodeIds || edge.toId in visibleNodeIds
        }

        fun clampOffset(rawOffset: Offset, zoomValue: Float): Offset {
            val minOffsetX = viewportWidth - (extents.maxX + boundsPadding) * zoomValue
            val maxOffsetX = -(extents.minX - boundsPadding) * zoomValue
            val minOffsetY = viewportHeight - (extents.maxY + boundsPadding) * zoomValue
            val maxOffsetY = -(extents.minY - boundsPadding) * zoomValue

            val clampedX = if (minOffsetX <= maxOffsetX) {
                rawOffset.x.coerceIn(minOffsetX, maxOffsetX)
            } else {
                (minOffsetX + maxOffsetX) / 2f
            }
            val clampedY = if (minOffsetY <= maxOffsetY) {
                rawOffset.y.coerceIn(minOffsetY, maxOffsetY)
            } else {
                (minOffsetY + maxOffsetY) / 2f
            }
            return Offset(clampedX, clampedY)
        }

        LaunchedEffect(fitGraphRequest) {
            if (fitGraphRequest <= 0) return@LaunchedEffect
            val fitPadding = 56f
            val availableWidth = (viewportWidth - fitPadding * 2f).coerceAtLeast(1f)
            val availableHeight = (viewportHeight - fitPadding * 2f).coerceAtLeast(1f)
            val fitZoomX = availableWidth / extents.width
            val fitZoomY = availableHeight / extents.height
            val fitZoom = min(fitZoomX, fitZoomY).coerceIn(MIN_ZOOM, MAX_ZOOM)

            val centerX = (extents.minX + extents.maxX) / 2f
            val centerY = (extents.minY + extents.maxY) / 2f
            val targetOffset = Offset(
                x = viewportWidth / 2f - centerX * fitZoom,
                y = viewportHeight / 2f - centerY * fitZoom
            )

            localZoom = fitZoom
            onZoomChange(fitZoom)
            offsetAnim.snapTo(clampOffset(targetOffset, fitZoom))
        }

        // After a relayout, immediately re-clamp viewport so redraw is coherent without extra interaction.
        LaunchedEffect(graphRevision) {
            offsetAnim.snapTo(clampOffset(offsetAnim.value, localZoom))
        }

        LaunchedEffect(hoveredNodeId) {
            activeTooltipNodeId = null
            val targetNodeId = hoveredNodeId ?: return@LaunchedEffect
            delay(TOOLTIP_DELAY_MS.toLong())
            if (hoveredNodeId == targetNodeId) {
                activeTooltipNodeId = targetNodeId
            }
        }

        // Sync local state when external zoom changes (e.g. from buttons)
        LaunchedEffect(zoom) {
            if (Math.abs(zoom - localZoom) > 0.001f) {
                val viewportCenter = Offset(viewportWidth / 2f, viewportHeight / 2f)

                val oldZoom = localZoom
                val newZoom = zoom
                val layoutOffset = offsetAnim.value + (viewportCenter - offsetAnim.value) * (1 - newZoom / oldZoom)

                localZoom = newZoom
                offsetAnim.snapTo(clampOffset(layoutOffset, newZoom))
            }
        }

        LaunchedEffect(selectedNodeIds) {
            if (selectedNodeIds.size == 1) {
                val selectedNode = nodeById[selectedNodeIds.first()]
                if (selectedNode != null) {
                    val currentZoom = localZoom
                    val currentX = offsetAnim.value.x
                    val currentY = offsetAnim.value.y

                    val snx = nodeX(selectedNode)
                    val sny = nodeY(selectedNode)
                    val nodeLeft = snx * currentZoom + currentX
                    val nodeRight = (snx + selectedNode.width.toFloat()) * currentZoom + currentX
                    val nodeTop = sny * currentZoom + currentY
                    val nodeBottom = (sny + selectedNode.height.toFloat()) * currentZoom + currentY

                    val margin = 20f

                    val isVisible =
                        nodeLeft >= margin && nodeRight <= (viewportWidth - margin) && nodeTop >= margin && nodeBottom <= (viewportHeight - margin)

                    if (!isVisible) {
                        val nodeCenterX = snx + (selectedNode.width.toFloat() / 2f)
                        val nodeCenterY = sny + (selectedNode.height.toFloat() / 2f)

                        val targetX = (viewportWidth / 2f - nodeCenterX) * currentZoom
                        val targetY = (viewportHeight / 2f - nodeCenterY) * currentZoom

                        offsetAnim.animateTo(clampOffset(Offset(targetX, targetY), currentZoom))
                    }
                }
            }
        }

        Box(
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = localZoom
                scaleY = localZoom
                translationX = offsetAnim.value.x
                translationY = offsetAnim.value.y
                transformOrigin = TransformOrigin(0f, 0f)
            }) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val activeNodeIds = if (hoveredNodeId != null) setOf(hoveredNodeId!!) else selectedNodeIds

                visibleEdges.forEach { edge ->
                    val source = nodeById[edge.fromId]
                    val target = nodeById[edge.toId]

                    if (source != null && target != null) {
                        val baseEdgeColor = tonedEdgeColor(getGraphNodeBorderColor(source, isDarkSurface), source.id)
                        val edgeColor = if (isDarkSurface) {
                            liftEdgeColor(baseEdgeColor, colors.onSurface, minimumBrightness = 0.54f)
                        } else {
                            baseEdgeColor
                        }
                        if (activeNodeIds.contains(edge.fromId) || activeNodeIds.contains(edge.toId)) {
                            drawEdge(edge, source, target, edgeColor, strokeWidth = 6f)
                        } else {
                            drawEdge(edge, source, target, edgeColor.copy(alpha = 0.7f), strokeWidth = 2f)
                        }
                    }
                }
            }

            visibleNodes.forEach { node ->
                Box(modifier = Modifier.offset {
                    val p = graph.getPosition(node.id)
                    IntOffset(p.x.toInt(), p.y.toInt())
                }) {
                    var pickSelectionArmed by remember(node.id) { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .pointerInput(node.id) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.type == PointerEventType.Enter) {
                                            hoveredNodeId = node.id
                                        } else if (event.type == PointerEventType.Exit) {
                                            if (hoveredNodeId == node.id) {
                                                hoveredNodeId = null
                                            }
                                        } else if (event.type == PointerEventType.Press) {
                                            pickSelectionArmed =
                                                event.keyboardModifiers.isMetaPressed || event.keyboardModifiers.isCtrlPressed
                                        }
                                    }
                                }
                            }
                            .pointerInput(node.id, localZoom) {
                                detectDragGestures(
                                    onDragStart = {
                                        // Save positions for undo before drag begins
                                        val snapshot = PositionSnapshot(
                                            graph.positions.toMap().mapValues { (_, v) -> v.x.toDouble() to v.y.toDouble() }
                                        )
                                        if (undoStack.size >= maxUndoDepth) undoStack.removeFirst()
                                        undoStack.add(snapshot)

                                        if (!pickSelectionArmed && !latestSelectedNodeIds.contains(node.id)) {
                                            onSelectionChange(setOf(node.id))
                                        }
                                    }
                                ) { change, dragAmount ->
                                    change.consume()
                                    val dx = dragAmount.x
                                    val dy = dragAmount.y

                                    val currentSelectedIds = latestSelectedNodeIds
                                    if (node.id in currentSelectedIds) {
                                        currentSelectedIds.forEach { id -> moveNode(id, dx, dy) }
                                    } else {
                                        moveNode(node.id, dx, dy)
                                        onSelectionChange(setOf(node.id))
                                    }
                                }
                            }
                            .pointerInput(node.id, selectedNodeIds) {
                                detectTapGestures(
                                    onTap = {
                                        if (pickSelectionArmed) {
                                            val nextSelection = if (selectedNodeIds.contains(node.id)) {
                                                selectedNodeIds - node.id
                                            } else {
                                                selectedNodeIds + node.id
                                            }
                                            onSelectionChange(nextSelection)
                                        } else {
                                            onSelectionChange(setOf(node.id))
                                        }
                                        pickSelectionArmed = false
                                    },
                                    onDoubleTap = {
                                        if (pickSelectionArmed) {
                                            val nextSelection = if (selectedNodeIds.contains(node.id)) {
                                                selectedNodeIds - node.id
                                            } else {
                                                selectedNodeIds + node.id
                                            }
                                            onSelectionChange(nextSelection)
                                        } else {
                                            onSelectionChange(setOf(node.id))
                                        }
                                        pickSelectionArmed = false
                                        onNodeDoubleClick(node)
                                    }
                                )
                            }
                            // Removed redundant .clickable to avoid double selection triggers
                    ) {
                        val isActive = hoveredNodeId == node.id || selectedNodeIds.contains(node.id)
                        when (node) {
                            is GraphNode.TableNode    -> TableCard(node, isSelected = isActive)
                            is GraphNode.MetadataNode -> MetadataCard(node, isSelected = isActive)
                            is GraphNode.SnapshotNode -> SnapshotCard(node, isSelected = isActive)
                            is GraphNode.ManifestNode -> ManifestCard(node, isSelected = isActive)
                            is GraphNode.FileNode     -> FileCard(node, isSelected = isActive)
                            is GraphNode.RowNode      -> RowCard(node, isSelected = isActive)
                            is GraphNode.ErrorNode    -> ErrorCard(node, isSelected = isActive)
                            is GraphNode.PaimonSnapshotNode,
                            is GraphNode.PaimonSchemaNode,
                            is GraphNode.PaimonManifestListNode,
                            is GraphNode.PaimonManifestNode,
                            is GraphNode.PaimonDataFileNode -> PaimonNodeCard(node, isSelected = isActive)
                        }
                    }
                }
            }
        }

        val tooltipNode = activeTooltipNodeId?.let(nodeById::get)
        if (tooltipNode != null) {
            val ttPos = graph.getPosition(tooltipNode.id)
            val tooltipX = ((ttPos.x + tooltipNode.width.toFloat()) * localZoom + offsetAnim.value.x + 12f).roundToInt()
            val tooltipY = (ttPos.y * localZoom + offsetAnim.value.y + 12f).roundToInt()
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(tooltipX, tooltipY) }
            ) {
                NodeTooltip(tooltipNode)
            }
        }

        if (marqueeStart != null && marqueeEnd != null) {
            Canvas(Modifier.fillMaxSize()) {
                val left = minOf(marqueeStart!!.x, marqueeEnd!!.x)
                val top = minOf(marqueeStart!!.y, marqueeEnd!!.y)
                val width = kotlin.math.abs(marqueeStart!!.x - marqueeEnd!!.x)
                val height = kotlin.math.abs(marqueeStart!!.y - marqueeEnd!!.y)

                drawRect(
                    color = selectionColor.copy(alpha = if (isDarkSurface) 0.4f else 0.2f),
                    topLeft = Offset(left, top),
                    size = Size(width, height)
                )
                drawRect(
                    color = selectionColor,
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    style = Stroke(1.dp.toPx())
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .size(240.dp, 160.dp)
                .background(colors.surface.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                .border(1.dp, colors.outlineVariant, RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val mapScale = min(240f / extents.width, 160f / extents.height)
                        val mapOffsetX = (240f - (extents.width * mapScale)) / 2f
                        val mapOffsetY = (160f - (extents.height * mapScale)) / 2f

                        val px = change.position.x.coerceIn(mapOffsetX, mapOffsetX + extents.width * mapScale)
                        val py = change.position.y.coerceIn(mapOffsetY, mapOffsetY + extents.height * mapScale)
                        val graphX = extents.minX + ((px - mapOffsetX) / mapScale)
                        val graphY = extents.minY + ((py - mapOffsetY) / mapScale)
                        val targetOffset = Offset(
                            viewportWidth / 2f - graphX * localZoom,
                            viewportHeight / 2f - graphY * localZoom
                        )

                        coroutineScope.launch { offsetAnim.snapTo(clampOffset(targetOffset, localZoom)) }
                    }
                }) {
            Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                val mapScale = min(size.width / extents.width, size.height / extents.height)
                val mapOffsetX = (size.width - (extents.width * mapScale)) / 2f
                val mapOffsetY = (size.height - (extents.height * mapScale)) / 2f

                translate(mapOffsetX, mapOffsetY) {
                    graph.nodes.forEach { n ->
                        val np = graph.getPosition(n.id)
                        drawRect(
                            color = getGraphNodeColor(n, isDarkSurface),
                            topLeft = Offset((np.x - extents.minX) * mapScale, (np.y - extents.minY) * mapScale),
                            size = Size(n.width.toFloat() * mapScale, n.height.toFloat() * mapScale)
                        )
                    }

                    val vpW = viewportWidth / localZoom
                    val vpH = viewportHeight / localZoom
                    val vpX = -offsetAnim.value.x / localZoom
                    val vpY = -offsetAnim.value.y / localZoom

                    drawRect(
                        color = colors.primary.copy(alpha = 0.55f),
                        topLeft = Offset((vpX - extents.minX) * mapScale, (vpY - extents.minY) * mapScale),
                        size = Size(vpW * mapScale, vpH * mapScale),
                        style = Stroke(width = 3f)
                    )
                }
            }
        }
    }
}

// getNodeTooltipText removed and replaced by NodeTooltip in NodeComponents.kt
