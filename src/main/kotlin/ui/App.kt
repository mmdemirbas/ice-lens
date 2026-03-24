package ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import model.*
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.prefs.Preferences

private val prefs = Preferences.userRoot().node("com.github.mmdemirbas.icelens")

private const val PREF_LEFT_PANE_WIDTH = "left_pane_width"
private const val PREF_RIGHT_PANE_WIDTH = "right_pane_width"
private const val PREF_BOTTOM_PANE_HEIGHT = "bottom_pane_height"
private const val PREF_LEFT_SPLIT = "left_split"
private const val PREF_RIGHT_SPLIT = "right_split"
private const val PREF_BOTTOM_SPLIT = "bottom_split"
private const val PREF_WINDOW_ANCHORS = "tool_window_anchors"
private const val PREF_ZOOM = "zoom"
private const val PREF_IS_SELECT_MODE = "is_select_mode"
private const val PREF_IS_DARK_MODE = "is_dark_mode"

@Composable
fun App() {
    val coroutineScope = rememberCoroutineScope()
    val state = remember { AppState(prefs, coroutineScope) }

    // ═══ UI-Only State ═══

    var isSelectMode by remember { mutableStateOf(prefs.getBoolean(PREF_IS_SELECT_MODE, true)) }
    var isDarkMode by remember { mutableStateOf(prefs.getBoolean(PREF_IS_DARK_MODE, false)) }
    var zoom by remember { mutableStateOf(prefs.getFloat(PREF_ZOOM, 1f)) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var snapshotFilterMenuExpanded by remember { mutableStateOf(false) }
    var fitGraphRequest by remember { mutableIntStateOf(0) }

    var leftPaneWidth by remember { mutableStateOf(prefs.getFloat(PREF_LEFT_PANE_WIDTH, 250f).dp) }
    var rightPaneWidth by remember { mutableStateOf(prefs.getFloat(PREF_RIGHT_PANE_WIDTH, 300f).dp) }
    var bottomPaneHeight by remember { mutableStateOf(prefs.getFloat(PREF_BOTTOM_PANE_HEIGHT, 220f).dp) }
    var leftSplitRatio by remember { mutableStateOf(prefs.getFloat(PREF_LEFT_SPLIT, 0.55f)) }
    var rightSplitRatio by remember { mutableStateOf(prefs.getFloat(PREF_RIGHT_SPLIT, 0.6f)) }
    var bottomSplitRatio by remember { mutableStateOf(prefs.getFloat(PREF_BOTTOM_SPLIT, 0.5f)) }
    val density = LocalDensity.current
    var draggingToolWindowId by remember { mutableStateOf<String?>(null) }
    var dragTargetAnchor by remember { mutableStateOf<ToolWindowAnchor?>(null) }
    var appWindowBounds by remember { mutableStateOf<Rect?>(null) }
    var hiddenToolWindowIds by remember { mutableStateOf(setOf<String>()) }

    var windowAnchors by remember {
        val defaults = mapOf(
            "workspace" to ToolWindowAnchor.LEFT_TOP,
            "structure" to ToolWindowAnchor.LEFT_BOTTOM,
            "inspector" to ToolWindowAnchor.RIGHT_TOP
        )
        val saved = prefs.get(PREF_WINDOW_ANCHORS, "")
        val parsed = saved.split(";").mapNotNull { part ->
            val idAndAnchor = part.split(":")
            if (idAndAnchor.size != 2) return@mapNotNull null
            val id = idAndAnchor[0]
            val anchor = runCatching { ToolWindowAnchor.valueOf(idAndAnchor[1]) }.getOrNull() ?: return@mapNotNull null
            id to anchor
        }.toMap()
        val map = defaults.toMutableMap().apply { putAll(parsed) }
        mutableStateOf(map)
    }

    val toolWindows = listOf(
        ToolWindowConfig("workspace", "Workspace", Icons.Default.Storage, windowAnchors["workspace"] ?: ToolWindowAnchor.LEFT_TOP),
        ToolWindowConfig("structure", "Structure", Icons.Default.AccountTree, windowAnchors["structure"] ?: ToolWindowAnchor.LEFT_BOTTOM),
        ToolWindowConfig("inspector", "Inspector", Icons.Default.Info, windowAnchors["inspector"] ?: ToolWindowAnchor.RIGHT_TOP)
    )

    // ═══ UI Helper Functions ═══

    fun moveToolWindow(id: String, newAnchor: ToolWindowAnchor) {
        val currentAnchor = windowAnchors[id] ?: ToolWindowAnchor.LEFT_TOP
        if (currentAnchor == newAnchor) return
        val newAnchors = windowAnchors.toMutableMap()
        newAnchors[id] = newAnchor
        windowAnchors = newAnchors
        prefs.put(PREF_WINDOW_ANCHORS, newAnchors.entries.joinToString(";") { "${it.key}:${it.value}" })
    }

    fun updateDragTarget(positionInWindow: Offset?) {
        val bounds = appWindowBounds
        val edgeSizePx = with(density) { 120.dp.toPx() }
        dragTargetAnchor = if (positionInWindow == null || bounds == null) null else when {
            positionInWindow.x <= bounds.left + edgeSizePx && positionInWindow.y <= bounds.center.y -> ToolWindowAnchor.LEFT_TOP
            positionInWindow.x <= bounds.left + edgeSizePx -> ToolWindowAnchor.LEFT_BOTTOM
            positionInWindow.x >= bounds.right - edgeSizePx && positionInWindow.y <= bounds.center.y -> ToolWindowAnchor.RIGHT_TOP
            positionInWindow.x >= bounds.right - edgeSizePx -> ToolWindowAnchor.RIGHT_BOTTOM
            positionInWindow.y >= bounds.bottom - edgeSizePx && positionInWindow.x <= bounds.center.x -> ToolWindowAnchor.BOTTOM_LEFT
            positionInWindow.y >= bounds.bottom - edgeSizePx -> ToolWindowAnchor.BOTTOM_RIGHT
            else -> null
        }
    }

    fun toggleWindowVisibility(id: String) {
        hiddenToolWindowIds = if (id in hiddenToolWindowIds) hiddenToolWindowIds - id else hiddenToolWindowIds + id
    }
    fun toggleInspectorVisibility() {
        toggleWindowVisibility("inspector")
    }
    fun toggleAllPanelsVisibility() {
        val visibleIds = toolWindows.map { it.id }.filter { it !in hiddenToolWindowIds }
        hiddenToolWindowIds = if (visibleIds.isNotEmpty()) toolWindows.map { it.id }.toSet() else emptySet()
    }

    fun toolWindowTitle(id: String): String = toolWindows.firstOrNull { it.id == id }?.title ?: id

    // ═══ Render Tool Window Content ═══

    @Composable
    fun RenderToolWindowContent(toolWindowId: String) {
        when (toolWindowId) {
            "workspace" -> WorkspacePanel(
                workspaceItems = state.workspaceItems,
                warehouseTableStatuses = state.warehouseTableStatuses,
                singleTableStatuses = state.singleTableStatuses,
                selectedTablePath = state.selectedTablePath,
                expandedPaths = state.workspaceExpandedPaths,
                onExpandedPathsChange = { state.updateWorkspaceExpandedPaths(it) },
                searchQuery = state.workspaceSearchQuery,
                onSearchQueryChange = { state.workspaceSearchQuery = it },
                lastBrowseDirectory = state.lastBrowseDirectory,
                onLastBrowseDirectoryChange = { dir -> state.updateLastBrowseDirectory(dir) },
                onTableSelect = { tablePath ->
                    state.saveCurrentSessionSelection()
                    state.loadTable(tablePath)
                },
                onAddRoot = { path -> state.addWorkspaceRoot(path) },
                onRemoveRoot = { item -> state.removeWorkspaceRoot(item) },
                onMoveRoot = { item, delta -> state.moveWorkspaceRoot(item, delta) }
            )
            "structure" -> {
                val graph = state.visibleGraphModel
                if (graph != null) {
                    NavigationTree(
                        graph = graph,
                        selectedNodeIds = state.selectedNodeIds,
                        onNodeSelect = { state.selectedNodeIds = setOf(it.id) }
                    )
                } else {
                    Text(
                        "No graph loaded.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
            "inspector" -> NodeDetailsContent(state.visibleGraphModel, state.selectedNodeIds)
        }
    }

    // ═══ Tool Window Layout Helpers ═══

    val anchorToWindowId = toolWindows.associate { window ->
        (windowAnchors[window.id] ?: window.anchor) to window.id
    }
    val visibleAnchorToWindowId = toolWindows
        .filterNot { it.id in hiddenToolWindowIds }
        .associate { window -> (windowAnchors[window.id] ?: window.anchor) to window.id }
    val leftSideButtons = toolWindows
        .filter { (windowAnchors[it.id] ?: it.anchor) in setOf(ToolWindowAnchor.LEFT_TOP, ToolWindowAnchor.LEFT_BOTTOM) }
        .map { it.id to it.icon }
    val rightSideButtons = toolWindows
        .filter { (windowAnchors[it.id] ?: it.anchor) in setOf(ToolWindowAnchor.RIGHT_TOP, ToolWindowAnchor.RIGHT_BOTTOM) }
        .map { it.id to it.icon }
    val bottomLeftButtons = toolWindows
        .filter { (windowAnchors[it.id] ?: it.anchor) == ToolWindowAnchor.BOTTOM_LEFT }
        .map { it.id to it.icon }
    val bottomRightButtons = toolWindows
        .filter { (windowAnchors[it.id] ?: it.anchor) == ToolWindowAnchor.BOTTOM_RIGHT }
        .map { it.id to it.icon }

    // ═══ Main UI ═══

    MaterialTheme(
        colorScheme = if (isDarkMode) IceLensDarkColorScheme else IceLensLightColorScheme
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            val appFocusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { appFocusRequester.requestFocus() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .focusRequester(appFocusRequester)
                    .focusable()
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val ctrl = keyEvent.isMetaPressed || keyEvent.isCtrlPressed
                        when {
                            ctrl && !keyEvent.isShiftPressed && (keyEvent.key == Key.Equals || keyEvent.key == Key.NumPadAdd) -> {
                                zoom = (zoom * 1.2f).coerceAtMost(MAX_ZOOM)
                                prefs.putFloat(PREF_ZOOM, zoom)
                                true
                            }
                            ctrl && !keyEvent.isShiftPressed && (keyEvent.key == Key.Minus || keyEvent.key == Key.NumPadSubtract) -> {
                                zoom = (zoom / 1.2f).coerceAtLeast(MIN_ZOOM)
                                prefs.putFloat(PREF_ZOOM, zoom)
                                true
                            }
                            ctrl && !keyEvent.isShiftPressed && keyEvent.key == Key.Zero -> {
                                zoom = 1f
                                prefs.putFloat(PREF_ZOOM, zoom)
                                true
                            }
                            ctrl && keyEvent.isShiftPressed && keyEvent.key == Key.F -> {
                                if (state.visibleGraphModel != null) fitGraphRequest++
                                true
                            }
                            ctrl && !keyEvent.isShiftPressed && keyEvent.key == Key.L -> {
                                state.reapplyCurrentLayout()
                                true
                            }
                            else -> false
                        }
                    }
                    .onGloballyPositioned { coords -> appWindowBounds = coords.boundsInWindow() }
            ) {
            Column(Modifier.fillMaxSize()) {

            // ═══ Toolbar ═══
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolbarGroup {
                    ToolbarIconButton(
                        icon = Icons.Default.PanTool,
                        tooltip = "Pan Mode",
                        onClick = {
                            isSelectMode = false
                            prefs.putBoolean(PREF_IS_SELECT_MODE, isSelectMode)
                        },
                        isSelected = !isSelectMode,
                        modifier = Modifier.size(32.dp)
                    )
                    Box(Modifier.width(1.dp).height(16.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    ToolbarIconButton(
                        icon = Icons.Default.AdsClick,
                        tooltip = "Selection Mode",
                        onClick = {
                            isSelectMode = true
                            prefs.putBoolean(PREF_IS_SELECT_MODE, isSelectMode)
                        },
                        isSelected = isSelectMode,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(Modifier.width(16.dp))

                ToolbarGroup {
                    ToolbarIconButton(
                        icon = Icons.Default.ZoomOut,
                        tooltip = "Zoom Out",
                        onClick = {
                            zoom = (zoom / 1.2f).coerceAtLeast(MIN_ZOOM)
                            prefs.putFloat(PREF_ZOOM, zoom)
                        },
                        modifier = Modifier.size(32.dp)
                    )
                    Box(Modifier.width(1.dp).height(16.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    Text(
                        "${(zoom * 100).toInt()}%",
                        fontSize = 11.sp,
                        modifier = Modifier.width(45.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Box(Modifier.width(1.dp).height(16.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    ToolbarIconButton(
                        icon = Icons.Default.ZoomIn,
                        tooltip = "Zoom In",
                        onClick = {
                            zoom = (zoom * 1.2f).coerceAtMost(MAX_ZOOM)
                            prefs.putFloat(PREF_ZOOM, zoom)
                        },
                        modifier = Modifier.size(32.dp)
                    )
                    Box(Modifier.width(1.dp).height(16.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    ToolbarIconButton(
                        icon = Icons.Default.ZoomOutMap,
                        tooltip = "Original Size (100%)",
                        onClick = {
                            zoom = 1f
                            prefs.putFloat(PREF_ZOOM, zoom)
                        },
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(Modifier.width(12.dp))

                ToolbarGroup {
                    ToolbarIconButton(
                        icon = Icons.Default.FullscreenExit,
                        tooltip = "Fit Graph",
                        onClick = {
                            if (state.visibleGraphModel != null) fitGraphRequest++
                        },
                        modifier = Modifier.size(32.dp)
                    )
                    Box(Modifier.width(1.dp).height(16.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    ToolbarIconButton(
                        icon = Icons.Default.Schema,
                        tooltip = "Re-apply Layout",
                        onClick = { state.reapplyCurrentLayout() },
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(Modifier.width(10.dp))

                ToolbarGroup {
                    Box {
                        ToolbarIconButton(
                            icon = Icons.Default.FilterList,
                            tooltip = "Filter by snapshots",
                            onClick = { snapshotFilterMenuExpanded = !snapshotFilterMenuExpanded },
                            isSelected = state.selectedSnapshotFilterNodeIds.isNotEmpty(),
                            modifier = Modifier.size(32.dp)
                        )
                        DropdownMenu(
                            expanded = snapshotFilterMenuExpanded,
                            onDismissRequest = { snapshotFilterMenuExpanded = false },
                            modifier = Modifier.widthIn(min = 320.dp, max = 520.dp)
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            "Snapshot Filter",
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            "Show only nodes connected to selected snapshots",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {}
                            )
                            HorizontalDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { state.updateSnapshotFilterSelection(state.allSnapshotFilterNodeIds) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Icon(Icons.Default.DoneAll, contentDescription = "Select all", modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("All", fontSize = 11.sp)
                                }
                                TextButton(
                                    onClick = { state.updateSnapshotFilterSelection(emptySet()) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear selection", modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("None", fontSize = 11.sp)
                                }
                                TextButton(
                                    onClick = {
                                        state.updateSnapshotFilterSelection(state.allSnapshotFilterNodeIds - state.selectedSnapshotFilterNodeIds)
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Icon(Icons.Default.SwapHoriz, contentDescription = "Invert selection", modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Invert", fontSize = 11.sp)
                                }
                            }
                            HorizontalDivider()
                            if (state.snapshotFilterOptions.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No snapshots") },
                                    onClick = {}
                                )
                            } else {
                                state.snapshotFilterOptions.forEach { option ->
                                    val isSelected = option.nodeId in state.selectedSnapshotFilterNodeIds
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Checkbox(
                                                    checked = isSelected,
                                                    onCheckedChange = null
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Column {
                                                    Text(snapshotFilterLabel(option), fontSize = 12.sp)
                                                    Text(
                                                        formatAppTimestamp(option.timestampMs),
                                                        fontSize = 10.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            val updated = if (isSelected) {
                                                state.selectedSnapshotFilterNodeIds - option.nodeId
                                            } else {
                                                state.selectedSnapshotFilterNodeIds + option.nodeId
                                            }
                                            state.updateSnapshotFilterSelection(updated)
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Box(Modifier.width(1.dp).height(16.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    Text(
                        text = if (state.selectedSnapshotFilterNodeIds.isEmpty()) {
                            "All"
                        } else {
                            "${state.selectedSnapshotFilterNodeIds.size}/${state.snapshotFilterOptions.size}"
                        },
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }

                Spacer(Modifier.weight(1f))

                ToolbarGroup {
                    ToolbarIconButton(
                        icon = if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                        tooltip = if (isDarkMode) "Switch to light mode" else "Switch to dark mode",
                        onClick = {
                            isDarkMode = !isDarkMode
                            prefs.putBoolean(PREF_IS_DARK_MODE, isDarkMode)
                        },
                        modifier = Modifier.size(32.dp)
                    )
                    Box(Modifier.width(1.dp).height(16.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    fun openGithubLink() {
                        runCatching {
                            if (!Desktop.isDesktopSupported()) {
                                error("Desktop browsing is not supported on this platform.")
                            }
                            Desktop.getDesktop().browse(URI("https://github.com/mmdemirbas/ice-lens"))
                        }.onFailure { e ->
                            state.errorMsg = "Failed to open GitHub link: ${e.message}"
                        }
                    }
                    ToolbarIconButton(
                        icon = Icons.AutoMirrored.Filled.HelpOutline,
                        tooltip = "About",
                        onClick = { showAboutDialog = true },
                        modifier = Modifier.size(32.dp)
                    )
                    Box(Modifier.width(1.dp).height(16.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    ToolbarIconButton(
                        icon = Icons.AutoMirrored.Filled.OpenInNew,
                        tooltip = "Open GitHub",
                        onClick = { openGithubLink() },
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            HorizontalDivider()

            // ═══ Main Content Area ═══

            val leftTopId = visibleAnchorToWindowId[ToolWindowAnchor.LEFT_TOP]
            val leftBottomId = visibleAnchorToWindowId[ToolWindowAnchor.LEFT_BOTTOM]
            val rightTopId = visibleAnchorToWindowId[ToolWindowAnchor.RIGHT_TOP]
            val rightBottomId = visibleAnchorToWindowId[ToolWindowAnchor.RIGHT_BOTTOM]
            val bottomLeftId = visibleAnchorToWindowId[ToolWindowAnchor.BOTTOM_LEFT]
            val bottomRightId = visibleAnchorToWindowId[ToolWindowAnchor.BOTTOM_RIGHT]

            fun onPaneDragEnd() {
                val draggedId = draggingToolWindowId
                val target = dragTargetAnchor
                if (draggedId != null && target != null) moveToolWindow(draggedId, target)
                draggingToolWindowId = null
                updateDragTarget(null)
            }

            @Composable
            fun WindowSlot(paneId: String?, modifier: Modifier = Modifier) {
                if (paneId == null) {
                    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
                    return
                }
                ToolWindowPane(
                    title = toolWindowTitle(paneId),
                    isBeingDragged = draggingToolWindowId == paneId,
                    onClose = { hiddenToolWindowIds = hiddenToolWindowIds + paneId },
                    onDragStart = { position ->
                        draggingToolWindowId = paneId
                        updateDragTarget(position)
                    },
                    onDragMove = { position -> updateDragTarget(position) },
                    onDragEnd = { onPaneDragEnd() },
                    onDragCancel = {
                        draggingToolWindowId = null
                        updateDragTarget(null)
                    }
                ) {
                    RenderToolWindowContent(paneId)
                }
            }

            Row(Modifier.weight(1f)) {
                if (leftSideButtons.isNotEmpty()) {
                    ToolWindowBar(
                        anchor = ToolWindowAnchor.LEFT_TOP,
                        windows = leftSideButtons,
                        activeWindowId = leftSideButtons.firstOrNull { (id, _) -> id !in hiddenToolWindowIds }?.first,
                        onWindowClick = { id -> toggleWindowVisibility(id) },
                        onWindowDragStart = { id, position ->
                            draggingToolWindowId = id
                            updateDragTarget(position)
                        },
                        onWindowDragMove = { position -> updateDragTarget(position) },
                        onWindowDragEnd = { onPaneDragEnd() },
                        onWindowDragCancel = {
                            draggingToolWindowId = null
                            updateDragTarget(null)
                        },
                        isDropTarget = dragTargetAnchor == ToolWindowAnchor.LEFT_TOP || dragTargetAnchor == ToolWindowAnchor.LEFT_BOTTOM
                    )
                }
                if (leftTopId != null || leftBottomId != null) {
                    Box(Modifier.width(leftPaneWidth).fillMaxHeight()) {
                        Column(Modifier.fillMaxSize()) {
                            if (leftTopId != null && leftBottomId != null) {
                                Box(Modifier.weight(leftSplitRatio).fillMaxWidth()) { WindowSlot(leftTopId, Modifier.fillMaxSize()) }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
                                DraggableHorizontalDivider(onDrag = { delta ->
                                    val h = (delta / 600f)
                                    leftSplitRatio = (leftSplitRatio + h).coerceIn(0.2f, 0.8f)
                                    prefs.putFloat(PREF_LEFT_SPLIT, leftSplitRatio)
                                })
                                Box(Modifier.weight(1f - leftSplitRatio).fillMaxWidth()) { WindowSlot(leftBottomId, Modifier.fillMaxSize()) }
                            } else {
                                Box(Modifier.fillMaxSize()) { WindowSlot(leftTopId ?: leftBottomId, Modifier.fillMaxSize()) }
                            }
                        }
                    }
                    DraggableVerticalDivider(onDrag = { delta ->
                        val deltaDp = with(density) { delta.toDp() }
                        val windowWidthDp = with(density) { (appWindowBounds?.width ?: 1600f).toDp() }
                        val reservedRight = if (rightTopId != null || rightBottomId != null) rightPaneWidth else 0.dp
                        val maxLeftWidth = (windowWidthDp - reservedRight - 260.dp).coerceAtLeast(150.dp)
                        leftPaneWidth = (leftPaneWidth + deltaDp).coerceIn(150.dp, maxLeftWidth)
                        prefs.putFloat(PREF_LEFT_PANE_WIDTH, leftPaneWidth.value)
                    })
                }

                // ═══ Graph Canvas (Center) ═══

                Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                    val currentGraph = state.visibleGraphModel
                    if (currentGraph != null) {
                        key(state.graphRevision) {
                            GraphCanvas(
                                graph = currentGraph,
                                graphRevision = state.graphRevision,
                                fitGraphRequest = fitGraphRequest,
                                selectedNodeIds = state.selectedNodeIds,
                                isSelectMode = isSelectMode,
                                zoom = zoom,
                                onZoomChange = {
                                    zoom = it
                                    prefs.putFloat(PREF_ZOOM, it)
                                },
                                onSelectionChange = { state.selectedNodeIds = it },
                                onEmptyAreaDoubleClick = { toggleAllPanelsVisibility() },
                                onNodeDoubleClick = { toggleInspectorVisibility() }
                            )
                        }
                    } else if (!state.isLoadingTable && state.workspaceItems.isNotEmpty()) {
                        Text(
                            "Select a table from the sidebar to view its structure.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else if (!state.isLoadingTable && state.workspaceItems.isEmpty()) {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Storage,
                                contentDescription = "No tables",
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "No tables in workspace",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Add an Iceberg/Paimon table or warehouse directory to get started.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = {
                                val initialDir = state.lastBrowseDirectory?.let { File(it) }
                                val selected = chooseDirectory(initialDir)
                                if (selected != null) {
                                    state.updateLastBrowseDirectory(selected.parent ?: selected.absolutePath)
                                    state.addWorkspaceRoot(selected.absolutePath)
                                }
                            }) {
                                Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Add to Workspace", fontSize = 13.sp)
                            }
                        }
                    }

                    if (state.isLoadingTable) {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text("Loading table...", color = MaterialTheme.colorScheme.onSurface)
                        }
                    }

                    if (state.isStaleData && state.errorMsg == null) {
                        Surface(
                            modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f),
                            tonalElevation = 1.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = "Stale data warning",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Viewing cached data \u2014 table may have been deleted from filesystem",
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    if (state.errorMsg != null) {
                        Surface(
                            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            tonalElevation = 2.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    state.errorMsg!!,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                if (state.selectedTablePath != null) {
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(
                                        onClick = {
                                            state.errorMsg = null
                                            state.reloadCurrentTableFromFilesystem()
                                        },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Reload", modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Reload", fontSize = 12.sp)
                                    }
                                }
                                Spacer(Modifier.width(4.dp))
                                IconButton(
                                    onClick = { state.errorMsg = null },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Dismiss",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }

                // ═══ Right Pane ═══

                if (rightTopId != null || rightBottomId != null) {
                    DraggableVerticalDivider(onDrag = { delta ->
                        val deltaDp = with(density) { delta.toDp() }
                        val windowWidthDp = with(density) { (appWindowBounds?.width ?: 1600f).toDp() }
                        val maxRightWidth = (windowWidthDp - 260.dp).coerceAtLeast(200.dp)
                        rightPaneWidth = (rightPaneWidth - deltaDp).coerceIn(200.dp, maxRightWidth)
                        prefs.putFloat(PREF_RIGHT_PANE_WIDTH, rightPaneWidth.value)
                    })
                    Box(Modifier.width(rightPaneWidth).fillMaxHeight()) {
                        Column(Modifier.fillMaxSize()) {
                            if (rightTopId != null && rightBottomId != null) {
                                Box(Modifier.weight(rightSplitRatio).fillMaxWidth()) { WindowSlot(rightTopId, Modifier.fillMaxSize()) }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
                                DraggableHorizontalDivider(onDrag = { delta ->
                                    val h = (delta / 600f)
                                    rightSplitRatio = (rightSplitRatio + h).coerceIn(0.2f, 0.8f)
                                    prefs.putFloat(PREF_RIGHT_SPLIT, rightSplitRatio)
                                })
                                Box(Modifier.weight(1f - rightSplitRatio).fillMaxWidth()) { WindowSlot(rightBottomId, Modifier.fillMaxSize()) }
                            } else {
                                Box(Modifier.fillMaxSize()) { WindowSlot(rightTopId ?: rightBottomId, Modifier.fillMaxSize()) }
                            }
                        }
                    }
                }
                if (rightSideButtons.isNotEmpty()) {
                    ToolWindowBar(
                        anchor = ToolWindowAnchor.RIGHT_TOP,
                        windows = rightSideButtons,
                        activeWindowId = rightSideButtons.firstOrNull { (id, _) -> id !in hiddenToolWindowIds }?.first,
                        onWindowClick = { id -> toggleWindowVisibility(id) },
                        onWindowDragStart = { id, position ->
                            draggingToolWindowId = id
                            updateDragTarget(position)
                        },
                        onWindowDragMove = { position -> updateDragTarget(position) },
                        onWindowDragEnd = { onPaneDragEnd() },
                        onWindowDragCancel = {
                            draggingToolWindowId = null
                            updateDragTarget(null)
                        },
                        isDropTarget = dragTargetAnchor == ToolWindowAnchor.RIGHT_TOP || dragTargetAnchor == ToolWindowAnchor.RIGHT_BOTTOM
                    )
                }
            }

            // ═══ Bottom Pane ═══

            if (bottomLeftId != null || bottomRightId != null) {
                DraggableHorizontalDivider(onDrag = { delta ->
                    val deltaDp = with(density) { delta.toDp() }
                    bottomPaneHeight = (bottomPaneHeight - deltaDp).coerceIn(120.dp, 500.dp)
                    prefs.putFloat(PREF_BOTTOM_PANE_HEIGHT, bottomPaneHeight.value)
                })
                Box(Modifier.height(bottomPaneHeight).fillMaxWidth()) {
                    Row(Modifier.fillMaxSize()) {
                        if (bottomLeftButtons.isNotEmpty()) {
                            ToolWindowBar(
                                anchor = ToolWindowAnchor.LEFT_TOP,
                                windows = bottomLeftButtons,
                                activeWindowId = bottomLeftButtons.firstOrNull { (id, _) -> id !in hiddenToolWindowIds }?.first,
                                onWindowClick = { id -> toggleWindowVisibility(id) },
                                onWindowDragStart = { id, position ->
                                    draggingToolWindowId = id
                                    updateDragTarget(position)
                                },
                                onWindowDragMove = { position -> updateDragTarget(position) },
                                onWindowDragEnd = { onPaneDragEnd() },
                                onWindowDragCancel = {
                                    draggingToolWindowId = null
                                    updateDragTarget(null)
                                },
                                isDropTarget = dragTargetAnchor == ToolWindowAnchor.BOTTOM_LEFT
                            )
                        }
                        val leftWeight = if (bottomLeftId != null && bottomRightId != null) bottomSplitRatio else 1f
                        val rightWeight = if (bottomLeftId != null && bottomRightId != null) 1f - bottomSplitRatio else 1f
                        if (bottomLeftId != null) {
                            Box(Modifier.weight(leftWeight).fillMaxHeight()) { WindowSlot(bottomLeftId, Modifier.fillMaxSize()) }
                        }
                        if (bottomLeftId != null && bottomRightId != null) {
                            DraggableVerticalDivider(onDrag = { delta ->
                                val w = (delta / 800f)
                                bottomSplitRatio = (bottomSplitRatio + w).coerceIn(0.2f, 0.8f)
                                prefs.putFloat(PREF_BOTTOM_SPLIT, bottomSplitRatio)
                            })
                        }
                        if (bottomRightId != null) {
                            Box(Modifier.weight(rightWeight).fillMaxHeight()) { WindowSlot(bottomRightId, Modifier.fillMaxSize()) }
                        }
                        if (bottomRightButtons.isNotEmpty()) {
                            ToolWindowBar(
                                anchor = ToolWindowAnchor.RIGHT_TOP,
                                windows = bottomRightButtons,
                                activeWindowId = bottomRightButtons.firstOrNull { (id, _) -> id !in hiddenToolWindowIds }?.first,
                                onWindowClick = { id -> toggleWindowVisibility(id) },
                                onWindowDragStart = { id, position ->
                                    draggingToolWindowId = id
                                    updateDragTarget(position)
                                },
                                onWindowDragMove = { position -> updateDragTarget(position) },
                                onWindowDragEnd = { onPaneDragEnd() },
                                onWindowDragCancel = {
                                    draggingToolWindowId = null
                                    updateDragTarget(null)
                                },
                                isDropTarget = dragTargetAnchor == ToolWindowAnchor.BOTTOM_RIGHT
                            )
                        }
                    }
                }
            } else if (bottomLeftButtons.isNotEmpty() || bottomRightButtons.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .background(Color.Transparent),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (bottomLeftButtons.isNotEmpty()) {
                        ToolWindowBar(
                            anchor = ToolWindowAnchor.LEFT_TOP,
                            windows = bottomLeftButtons,
                            activeWindowId = bottomLeftButtons.firstOrNull { (id, _) -> id !in hiddenToolWindowIds }?.first,
                            onWindowClick = { id -> toggleWindowVisibility(id) },
                            onWindowDragStart = { id, position ->
                                draggingToolWindowId = id
                                updateDragTarget(position)
                            },
                            onWindowDragMove = { position -> updateDragTarget(position) },
                            onWindowDragEnd = { onPaneDragEnd() },
                            onWindowDragCancel = {
                                draggingToolWindowId = null
                                updateDragTarget(null)
                            },
                            isDropTarget = dragTargetAnchor == ToolWindowAnchor.BOTTOM_LEFT
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (bottomRightButtons.isNotEmpty()) {
                        ToolWindowBar(
                            anchor = ToolWindowAnchor.RIGHT_TOP,
                            windows = bottomRightButtons,
                            activeWindowId = bottomRightButtons.firstOrNull { (id, _) -> id !in hiddenToolWindowIds }?.first,
                            onWindowClick = { id -> toggleWindowVisibility(id) },
                            onWindowDragStart = { id, position ->
                                draggingToolWindowId = id
                                updateDragTarget(position)
                            },
                            onWindowDragMove = { position -> updateDragTarget(position) },
                            onWindowDragEnd = { onPaneDragEnd() },
                            onWindowDragCancel = {
                                draggingToolWindowId = null
                                updateDragTarget(null)
                            },
                            isDropTarget = dragTargetAnchor == ToolWindowAnchor.BOTTOM_RIGHT
                        )
                    }
                }
                }

            }

            // ═══ Dialogs & Overlays ═══

            if (showAboutDialog) {
                AboutDialog(
                    onDismiss = { showAboutDialog = false },
                    onError = { msg -> state.errorMsg = msg },
                )
            }

            if (draggingToolWindowId != null) {
                val sideDropWidth = 220.dp
                val bottomDropHeight = 160.dp
                val baseColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                val activeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)

                Column(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(bottom = bottomDropHeight)
                        .width(sideDropWidth)
                        .fillMaxHeight()
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(if (dragTargetAnchor == ToolWindowAnchor.LEFT_TOP) activeColor else baseColor)
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), thickness = 1.dp)
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(if (dragTargetAnchor == ToolWindowAnchor.LEFT_BOTTOM) activeColor else baseColor)
                    )
                }

                Column(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(bottom = bottomDropHeight)
                        .width(sideDropWidth)
                        .fillMaxHeight()
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(if (dragTargetAnchor == ToolWindowAnchor.RIGHT_TOP) activeColor else baseColor)
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), thickness = 1.dp)
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(if (dragTargetAnchor == ToolWindowAnchor.RIGHT_BOTTOM) activeColor else baseColor)
                    )
                }

                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(0.5f)
                        .height(bottomDropHeight)
                        .background(if (dragTargetAnchor == ToolWindowAnchor.BOTTOM_LEFT) activeColor else baseColor)
                )
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .fillMaxWidth(0.5f)
                        .height(bottomDropHeight)
                        .background(if (dragTargetAnchor == ToolWindowAnchor.BOTTOM_RIGHT) activeColor else baseColor)
                )
            }
        }
    }
    }

    // ═══ LaunchedEffects (thin wrappers calling AppState methods) ═══

    LaunchedEffect(state.errorMsg) {
        if (state.errorMsg != null) {
            delay(ERROR_AUTO_DISMISS_MS)
            state.errorMsg = null
        }
    }

    LaunchedEffect(state.snapshotFilterOptions, state.selectedSnapshotFilterNodeIds) {
        if (state.snapshotFilterOptions.isEmpty()) return@LaunchedEffect
        state.persistSnapshotFilter()
    }

    LaunchedEffect(state.selectedTablePath, state.showRows) {
        while (isActive) {
            val tablePath = state.selectedTablePath
            if (tablePath != null && !state.isLoadingTable) {
                val cacheKey = "$tablePath-rows_${state.showRows}"
                val session = state.sessionCache[cacheKey]
                if (session != null) {
                    val currentFingerprint = withContext(Dispatchers.Default) { state.computeTableFingerprint(tablePath) }
                    if (currentFingerprint != session.fingerprint) {
                        state.reloadCurrentTableFromFilesystem(preserveLayout = true)
                    }
                }
            }
            delay(FILESYSTEM_POLL_INTERVAL_MS)
        }
    }

    LaunchedEffect(Unit) {
        val restoredTablePath = state.selectedTablePath
        if (restoredTablePath != null && state.graphModel == null && !state.isLoadingTable) {
            state.loadTable(tablePath = restoredTablePath, withRows = state.showRows)
        }
        // Single periodic refresh for workspace table status.
        // Also covers changes from addWorkspaceRoot/removeWorkspaceRoot within one polling interval.
        while (isActive) {
            state.refreshWarehouseTables()
            delay(FILESYSTEM_POLL_INTERVAL_MS)
        }
    }
}
