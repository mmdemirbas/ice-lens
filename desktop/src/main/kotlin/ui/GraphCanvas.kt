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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
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
import model.GraphDirection
import model.GraphModel
import model.GraphNode
import model.GraphEdge
import model.Point
import model.firstNode
import model.stepFrom
import service.snapshotColumns
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The mini-map's box, where it sits, and the gap between the box and the drawing inside it.
 *
 * Named because more than one place reads them and they have to agree: the `Canvas` insets its
 * drawing by [MINI_MAP_INSET], the drag handler above it converts a pointer position back into a
 * graph coordinate using the same box, and the render test masks the corner out. When those were
 * a literal `4.dp` in one and a bare `240f` in the other, a drag on the map landed the viewport
 * somewhere else — by the padding on every display, and by the whole scale factor on a display
 * that is not at 100%.
 */
internal val MINI_MAP_WIDTH = 240.dp
internal val MINI_MAP_HEIGHT = 160.dp
internal val MINI_MAP_MARGIN = 16.dp
private val MINI_MAP_INSET = 4.dp
private val MINI_MAP_SHAPE = RoundedCornerShape(8.dp)

/**
 * The gap between a branch label and the topmost snapshot card of the column it names.
 *
 * In model dp, because that is the space the label is positioned in — a chip is about 15dp tall,
 * so this leaves a little under a card's own vertical rhythm between the two. Close enough to
 * read as belonging to the column rather than to the layer above it.
 */
private const val BRANCH_LABEL_GAP = 22.0

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
    positions: NodePositions,
    graphRevision: Int = 0,
    fitGraphRequest: Int = 0,
    selectedNodeIds: Set<String>,
    isSelectMode: Boolean,
    zoom: Float,
    onZoomChange: (Float) -> Unit,
    onSelectionChange: (Set<String>) -> Unit,
    onEmptyAreaDoubleClick: () -> Unit = {},
    onNodeDoubleClick: (GraphNode) -> Unit = {},
    /**
     * Drawn over the bottom-left of the canvas, opposite the mini-map. A statement about what
     * this drawing contains belongs on the drawing, not in a panel beside it — see
     * [GraphStatusBadge], which is what the app puts here.
     */
    statusOverlay: @Composable () -> Unit = {},
    /**
     * Manifests the reader's scan filter would let a query skip. Drawn faded and labelled — the
     * answer belongs on the graph, where the manifests are, not only in a panel beside it.
     */
    /**
     * Every node a scan under the reader's filter does not read — manifests it rules out, and the
     * data files it never opens, whether because their own bounds excluded them or because the
     * manifest listing them was gone. One set rather than two, because what the drawing has to
     * show is the query's footprint, and the reason belongs in the panel that has room for it.
     */
    prunedNodeIds: Set<String> = emptySet(),
) {
    val colors = MaterialTheme.colorScheme
    val isDarkSurface = isDarkSurface(colors.surface)
    val selectionColor = if (isDarkSurface) DarkSelectionAccent else colors.primary

    // The graph model is in dp — ELK laid it out against card sizes the nodes declare in dp, and
    // the cards are drawn with Modifier.size(...dp). Everything else on this surface is device
    // pixels: the pointer, the constraints, graphicsLayer's translation, DrawScope. `zoom *
    // density` is the only conversion between the two spaces, and every coordinate that crosses
    // has to go through it. Positioning a node in pixels while sizing it in dp — which is what
    // Modifier.offset's lambda does if you hand it model coordinates — draws every card over its
    // neighbour on any display scaled past 100%.
    val density = LocalDensity.current.density
    // Consumed to ensure recomposition/reset-sensitive logic sees relayout events.
    @Suppress("UNUSED_VARIABLE")
    val _graphRevision = graphRevision

    // The pan is a pixel translation on the layer, so the opening margin is stated in dp and
    // converted, or the graph starts twice as close to the corner on a scaled display.
    val offsetAnim = remember { Animatable(Offset(100f * density, 100f * density), Offset.VectorConverter) }
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
                val minX = graph.nodes.minOf { positions.of(it.id).x }
                val minY = graph.nodes.minOf { positions.of(it.id).y }
                val maxX = graph.nodes.maxOf { positions.of(it.id).x + it.width.toFloat() }
                val maxY = graph.nodes.maxOf { positions.of(it.id).y + it.height.toFloat() }
                GraphExtents(minX, minY, maxX, maxY)
            }
        }
    }

    val nodeById = graph.nodeById

    // Helpers to read/write Compose-observable positions
    fun nodeX(node: GraphNode): Float = positions.of(node.id).x
    fun nodeY(node: GraphNode): Float = positions.of(node.id).y
    fun moveNode(nodeId: String, dx: Float, dy: Float) {
        val old = positions.of(nodeId)
        positions.set(nodeId, old.x + dx, old.y + dy)
    }

    val latestSelectedNodeIds by rememberUpdatedState(selectedNodeIds)

    // Per-edge data for snapshot→manifest fan-in/out lane indexing AND counts.
    // Computed once per (nodes, edges) change; replaces O(E) `count {…}` scans inside drawEdge.
    data class SnapshotManifestLanes(
        val outLane: Map<String, Int>,
        val inLane: Map<String, Int>,
        val outCountBySource: Map<String, Int>,
        val inCountByTarget: Map<String, Int>,
    )
    val snapshotManifestLanes = remember(graph.nodes, graph.edges) {
        val outLane = mutableMapOf<String, Int>()
        val inLane = mutableMapOf<String, Int>()
        val outCount = mutableMapOf<String, Int>()
        val inCount = mutableMapOf<String, Int>()
        graph.edges.groupBy { it.fromId }.forEach { (sourceId, sourceEdges) ->
            val sourceNode = nodeById[sourceId] ?: return@forEach
            if (sourceNode !is GraphNode.SnapshotNode) return@forEach
            val ranked = sourceEdges
                .mapNotNull { edge ->
                    val target = nodeById[edge.toId] ?: return@mapNotNull null
                    if (target !is GraphNode.ManifestNode) return@mapNotNull null
                    // Sort by initial position (stable across drags) so lane assignment doesn't drift.
                    edge to (positions.of(target.id).y)
                }
                .sortedBy { it.second }
            outCount[sourceId] = ranked.size
            ranked.forEachIndexed { index, (edge, _) -> outLane[edge.id] = index }
        }
        graph.edges.groupBy { it.toId }.forEach { (targetId, targetEdges) ->
            val targetNode = nodeById[targetId] ?: return@forEach
            if (targetNode !is GraphNode.ManifestNode) return@forEach
            val ranked = targetEdges
                .mapNotNull { edge ->
                    val source = nodeById[edge.fromId] ?: return@mapNotNull null
                    if (source !is GraphNode.SnapshotNode) return@mapNotNull null
                    edge to (positions.of(source.id).y)
                }
                .sortedBy { it.second }
            inCount[targetId] = ranked.size
            ranked.forEachIndexed { index, (edge, _) -> inLane[edge.id] = index }
        }
        SnapshotManifestLanes(outLane, inLane, outCount, inCount)
    }

    // Reusable Path for edge drawing — avoids per-edge per-frame allocation.
    val edgePath = remember { Path() }

    fun DrawScope.drawEdge(edge: GraphEdge, source: GraphNode, target: GraphNode, color: Color, strokeWidth: Float) {
        val isSnapshotToManifest = source is GraphNode.SnapshotNode && target is GraphNode.ManifestNode
        val outIndex = snapshotManifestLanes.outLane[edge.id] ?: 0
        val inIndex = snapshotManifestLanes.inLane[edge.id] ?: 0
        val outCount = if (isSnapshotToManifest) snapshotManifestLanes.outCountBySource[edge.fromId] ?: 1 else 1
        val inCount = if (isSnapshotToManifest) snapshotManifestLanes.inCountByTarget[edge.toId] ?: 1 else 1
        val outCentered = outIndex - ((outCount - 1) / 2f)
        val inCentered = inIndex - ((inCount - 1) / 2f)
        val laneXSpacing = 14f

        // Always use right-center -> left-center anchors.
        val srcPos = positions.of(source.id)
        val tgtPos = positions.of(target.id)
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
        edgePath.reset()
        edgePath.moveTo(startX, startY)
        edgePath.lineTo(startStubX, startY)
        edgePath.cubicTo(c1x, startY, c2x, endY, endStubX, endY)
        edgePath.lineTo(endX, endY)
        drawPath(
            path = edgePath,
            color = color,
            // An edge withheld from ELK runs between two nodes of the same layer — snapshot
            // lineage, a deletion vector and the file it names. It is an annotation over the
            // tree rather than part of it, and it is the only kind whose two ends can sit side
            // by side, so drawn solid it is indistinguishable from the parent-child edges
            // crossing the same gap. Dashing it is what lets a fork read as a fork now that its
            // branch has a column of its own.
            style = Stroke(
                width = strokeWidth,
                pathEffect = if (edge.affectsLayout) null else PathEffect.dashPathEffect(
                    floatArrayOf(strokeWidth * 3f, strokeWidth * 2f),
                ),
            ),
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
                            positions.set(id, pos.first.toFloat(), pos.second.toFloat())
                        }
                    }
                    true
                } else {
                    navKey(keyEvent)?.asGraphDirection()?.let { direction ->
                        // The graph is a picture, so the step is decided against where the nodes
                        // are drawn — `positions` and not `layoutPositions`, because a reader who
                        // has dragged a node is navigating the drawing they made. Selecting is
                        // all this does: the existing scroll-into-view effect brings the node on
                        // screen, the same one that runs when a node is clicked in the tree.
                        val current = latestSelectedNodeIds.singleOrNull()
                        val next = if (current == null) {
                            firstNode(graph) { node -> Point(nodeX(node), nodeY(node)) }
                        } else {
                            stepFrom(graph, current, direction) { node -> Point(nodeX(node), nodeY(node)) }
                        }
                        if (next != null) {
                            onSelectionChange(setOf(next))
                            true
                        } else {
                            // Consumed anyway. An arrow that falls through at the edge of the
                            // graph scrolls whatever is behind the canvas, which reads as the
                            // selection having jumped somewhere off screen.
                            true
                        }
                    } ?: false
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
                                // Pan. 20dp per scroll notch, so a wheel turn covers the same
                                // distance on the graph whatever the display scale is.
                                coroutineScope.launch {
                                    val step = 20f * density
                                    offsetAnim.snapTo(offsetAnim.value - Offset(delta.x * step, delta.y * step))
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

                                // The marquee is drawn from pointer pixels; the nodes it selects
                                // are in model dp.
                                val modelScale = localZoom * density
                                val logLeft = (left - offsetAnim.value.x) / modelScale
                                val logTop = (top - offsetAnim.value.y) / modelScale
                                val logRight = (right - offsetAnim.value.x) / modelScale
                                val logBottom = (bottom - offsetAnim.value.y) / modelScale

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

        // Pixels in, model dp out — the viewport rectangle expressed in the graph's own units.
        val modelScale = localZoom * density
        val logicalLeft = -offsetAnim.value.x / modelScale
        val logicalTop = -offsetAnim.value.y / modelScale
        val logicalRight = logicalLeft + viewportWidth / modelScale
        val logicalBottom = logicalTop + viewportHeight / modelScale
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
            // extents and boundsPadding are model dp; the offset being clamped is a pixel
            // translation.
            val scale = zoomValue * density
            val minOffsetX = viewportWidth - (extents.maxX + boundsPadding) * scale
            val maxOffsetX = -(extents.minX - boundsPadding) * scale
            val minOffsetY = viewportHeight - (extents.maxY + boundsPadding) * scale
            val maxOffsetY = -(extents.minY - boundsPadding) * scale

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
            // The available space is pixels and the extents are model dp, so the zoom that makes
            // one fit the other carries the density in its denominator.
            val fitZoomX = availableWidth / (extents.width * density)
            val fitZoomY = availableHeight / (extents.height * density)
            val fitZoom = min(fitZoomX, fitZoomY).coerceIn(MIN_ZOOM, MAX_ZOOM)

            val centerX = (extents.minX + extents.maxX) / 2f
            val centerY = (extents.minY + extents.maxY) / 2f
            val targetOffset = Offset(
                x = viewportWidth / 2f - centerX * fitZoom * density,
                y = viewportHeight / 2f - centerY * fitZoom * density
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
                    val scale = currentZoom * density
                    val currentX = offsetAnim.value.x
                    val currentY = offsetAnim.value.y

                    val snx = nodeX(selectedNode)
                    val sny = nodeY(selectedNode)
                    val nodeLeft = snx * scale + currentX
                    val nodeRight = (snx + selectedNode.width.toFloat()) * scale + currentX
                    val nodeTop = sny * scale + currentY
                    val nodeBottom = (sny + selectedNode.height.toFloat()) * scale + currentY

                    val margin = 20f

                    val isVisible =
                        nodeLeft >= margin && nodeRight <= (viewportWidth - margin) && nodeTop >= margin && nodeBottom <= (viewportHeight - margin)

                    if (!isVisible) {
                        val nodeCenterX = snx + (selectedNode.width.toFloat() / 2f)
                        val nodeCenterY = sny + (selectedNode.height.toFloat() / 2f)

                        // The offset that puts the node's centre at the viewport's centre solves
                        // `centre * scale + offset = viewport / 2`. The previous form scaled the
                        // difference instead, which only agrees with this at a zoom of exactly 1.
                        val targetX = viewportWidth / 2f - nodeCenterX * scale
                        val targetY = viewportHeight / 2f - nodeCenterY * scale

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

                // Edges are routed from node positions, which are model dp, while a DrawScope is
                // in pixels. Scaling the whole scope converts them in one place — and it puts the
                // stroke widths and the port stubs in dp too, so a line is the same thickness on
                // every display rather than a hairline on a scaled one.
                scale(density, density, pivot = Offset.Zero) {
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
            }

            // The branch each snapshot column belongs to, over the top of the column.
            //
            // Inside the transformed layer so it pans and zooms with the graph it labels — a
            // header pinned to the viewport would drift off its own column the moment anything
            // moved. Drawn before the cards so a card that has been dragged up over the header
            // sits above it rather than under it.
            //
            // Same shape and weight as the ref chips on a snapshot card, because it is the same
            // word meaning the same thing — but not the same colours. Those are fixed light-mode
            // values (`RefBranchChip` is 20% black) chosen against a card, and a card is always
            // light here. This chip sits on `colors.surfaceVariant`, the canvas field itself, so
            // it is derived from the theme: on a dark canvas a 20%-black pill under dark-grey
            // text is a smudge.
            // The list is hoisted, the positions are not: `positions.of` is the Compose-observable
            // read that has to happen in composition for a drag to move the label with its column.
            val snapshotNodes = remember(graph) { graph.nodes.filterIsInstance<GraphNode.SnapshotNode>() }
            snapshotColumns(snapshotNodes) { id -> positions.of(id).let { Point(it.x, it.y) } }
                .filter { it.labels.isNotEmpty() }
                .forEach { column ->
                    Box(modifier = Modifier.offset {
                        IntOffset(
                            column.x.dp.roundToPx(),
                            (column.topY - BRANCH_LABEL_GAP).dp.roundToPx(),
                        )
                    }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            column.labels.forEach { ref ->
                                Text(
                                    ref.name,
                                    fontSize = TypeScale.micro,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    color = colors.onSurfaceVariant,
                                    modifier = Modifier
                                        .background(
                                            colors.onSurfaceVariant.copy(alpha = 0.16f),
                                            RoundedCornerShape(3.dp),
                                        )
                                        .padding(horizontal = 5.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }

            visibleNodes.forEach { node ->
                Box(modifier = Modifier.offset {
                    val p = positions.of(node.id)
                    // This lambda returns pixels and the position is model dp — the card inside is
                    // sized in dp, so both have to be. roundToPx() converts and rounds, which also
                    // avoids the 1px sub-pixel drift truncation toward zero would leave.
                    IntOffset(p.x.dp.roundToPx(), p.y.dp.roundToPx())
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
                                            positions.draggedSnapshot()
                                        )
                                        if (undoStack.size >= maxUndoDepth) undoStack.removeFirst()
                                        undoStack.add(snapshot)

                                        if (!pickSelectionArmed && !latestSelectedNodeIds.contains(node.id)) {
                                            onSelectionChange(setOf(node.id))
                                        }
                                    }
                                ) { change, dragAmount ->
                                    change.consume()
                                    // The gesture reports pixels within the layer; positions are
                                    // model dp. The layer's zoom is already out of the delta —
                                    // the density is not.
                                    val dx = dragAmount.x / density
                                    val dy = dragAmount.y / density

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
                        GraphNodeCard(
                            node = node,
                            isSelected = hoveredNodeId == node.id || selectedNodeIds.contains(node.id),
                            isPruned = node.id in prunedNodeIds,
                        )
                    }
                }
            }
        }

        val tooltipNode = activeTooltipNodeId?.let(nodeById::get)
        if (tooltipNode != null) {
            val ttPos = positions.of(tooltipNode.id)
            // The tooltip is placed against the canvas, not inside the transformed layer, so the
            // node's model dp goes all the way to pixels here.
            val gap = 12f * density
            val tooltipX =
                ((ttPos.x + tooltipNode.width.toFloat()) * modelScale + offsetAnim.value.x + gap).roundToInt()
            val tooltipY = (ttPos.y * modelScale + offsetAnim.value.y + gap).roundToInt()
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

        Box(modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            statusOverlay()
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(MINI_MAP_MARGIN)
                .size(MINI_MAP_WIDTH, MINI_MAP_HEIGHT)
                .background(colors.surface.copy(alpha = 0.85f), MINI_MAP_SHAPE)
                .border(1.dp, colors.outlineVariant, MINI_MAP_SHAPE)
                // The viewport rectangle below is the viewport in graph units, which is larger
                // than the graph itself whenever the whole table fits on screen — and Compose
                // clips nothing, so it was drawn straight out of the map and across the canvas.
                // Clipping after the border keeps the frame and confines what is drawn in it.
                .clip(MINI_MAP_SHAPE)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        // The pointer arrives in pixels, so the map's own box is measured in
                        // pixels too — `size` here, inset by the padding the Canvas below adds,
                        // rather than the dp numbers the modifier was written with.
                        val inset = MINI_MAP_INSET.toPx()
                        val mapWidth = (size.width - inset * 2f).coerceAtLeast(1f)
                        val mapHeight = (size.height - inset * 2f).coerceAtLeast(1f)
                        val mapScale = min(mapWidth / extents.width, mapHeight / extents.height)
                        val mapOffsetX = inset + (mapWidth - (extents.width * mapScale)) / 2f
                        val mapOffsetY = inset + (mapHeight - (extents.height * mapScale)) / 2f

                        val px = change.position.x.coerceIn(mapOffsetX, mapOffsetX + extents.width * mapScale)
                        val py = change.position.y.coerceIn(mapOffsetY, mapOffsetY + extents.height * mapScale)
                        val graphX = extents.minX + ((px - mapOffsetX) / mapScale)
                        val graphY = extents.minY + ((py - mapOffsetY) / mapScale)
                        val targetOffset = Offset(
                            viewportWidth / 2f - graphX * localZoom * density,
                            viewportHeight / 2f - graphY * localZoom * density
                        )

                        coroutineScope.launch { offsetAnim.snapTo(clampOffset(targetOffset, localZoom)) }
                    }
                }) {
            Canvas(modifier = Modifier.fillMaxSize().padding(MINI_MAP_INSET)) {
                val mapScale = min(size.width / extents.width, size.height / extents.height)
                val mapOffsetX = (size.width - (extents.width * mapScale)) / 2f
                val mapOffsetY = (size.height - (extents.height * mapScale)) / 2f

                translate(mapOffsetX, mapOffsetY) {
                    graph.nodes.forEach { n ->
                        val np = positions.of(n.id)
                        drawRect(
                            color = getGraphNodeColor(n, isDarkSurface),
                            topLeft = Offset((np.x - extents.minX) * mapScale, (np.y - extents.minY) * mapScale),
                            size = Size(n.width.toFloat() * mapScale, n.height.toFloat() * mapScale)
                        )
                    }

                    // The map draws model dp, so the viewport rectangle is the pixel viewport
                    // converted back into them.
                    val vpW = viewportWidth / modelScale
                    val vpH = viewportHeight / modelScale
                    val vpX = -offsetAnim.value.x / modelScale
                    val vpY = -offsetAnim.value.y / modelScale

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
