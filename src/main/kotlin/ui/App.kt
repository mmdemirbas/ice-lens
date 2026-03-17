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
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import model.*
import service.GraphLayoutService
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.util.prefs.Preferences

private val prefs = Preferences.userRoot().node("com.github.mmdemirbas.icelens")
private const val PREF_WAREHOUSE_PATH = "last_warehouse_path"
private const val PREF_SHOW_ROWS = "show_data_rows"
private const val PREF_LEFT_PANE_WIDTH = "left_pane_width"
private const val PREF_RIGHT_PANE_WIDTH = "right_pane_width"
private const val PREF_BOTTOM_PANE_HEIGHT = "bottom_pane_height"
private const val PREF_LEFT_SPLIT = "left_split"
private const val PREF_RIGHT_SPLIT = "right_split"
private const val PREF_BOTTOM_SPLIT = "bottom_split"
private const val PREF_WINDOW_ANCHORS = "tool_window_anchors"
private const val PREF_ZOOM = "zoom"
private const val PREF_IS_SELECT_MODE = "is_select_mode"
private const val PREF_WORKSPACE_ITEMS = "workspace_items"
private const val PREF_WORKSPACE_EXPANDED_PATHS = "workspace_expanded_paths"
private const val PREF_SELECTED_TABLE_PATH = "selected_table_path"
private const val PREF_SELECTED_SNAPSHOT_IDS = "selected_snapshot_ids"
private const val PREF_IS_DARK_MODE = "is_dark_mode"

data class TableSession(
    val table: UnifiedTableModel,
    val graph: GraphModel,
    var selectedNodeIds: Set<String> = emptySet(),
    val fingerprint: String = "",
)

@Composable
fun App() {
    var workspaceItems by remember {
        val saved = prefs.get(PREF_WORKSPACE_ITEMS, "")
        val items = saved.split(";").mapNotNull { WorkspaceItem.deserialize(it) }
        val refreshed = items.map { item ->
            if (item is WorkspaceItem.Warehouse) {
                item.copy(tables = scanForTables(File(item.path)))
            } else item
        }
        val deduplicated = deduplicateWorkspaceItems(refreshed)
        if (deduplicated.size != refreshed.size) {
            prefs.put(PREF_WORKSPACE_ITEMS, deduplicated.joinToString(";") { it.serialize() })
        }
        mutableStateOf(deduplicated)
    }

    var selectedTablePath by remember {
        mutableStateOf(
            prefs.get(PREF_SELECTED_TABLE_PATH, "")
                .trim()
                .ifBlank { null }
        )
    }
    var graphModel by remember { mutableStateOf<GraphModel?>(null, neverEqualPolicy()) }
    var graphRevision by remember { mutableIntStateOf(0) }
    var fitGraphRequest by remember { mutableIntStateOf(0) }
    var selectedNodeIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var workspaceExpandedPaths by remember {
        val saved = prefs.get(PREF_WORKSPACE_EXPANDED_PATHS, "")
        val expanded = saved
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        mutableStateOf(expanded)
    }
    var workspaceSearchQuery by remember { mutableStateOf("") }
    var warehouseTableStatuses by remember { mutableStateOf(initialWarehouseTableStatuses(workspaceItems)) }
    var singleTableStatuses by remember {
        mutableStateOf(
            workspaceItems
                .filterIsInstance<WorkspaceItem.SingleTable>()
                .associate { it.path to WorkspaceTableStatus.EXISTING }
        )
    }
    var isLoadingTable by remember { mutableStateOf(false) }
    var loadRequestId by remember { mutableStateOf(0L) }
    var showRows by remember { mutableStateOf(prefs.getBoolean(PREF_SHOW_ROWS, true)) }
    var isSelectMode by remember { mutableStateOf(prefs.getBoolean(PREF_IS_SELECT_MODE, true)) }
    var isDarkMode by remember { mutableStateOf(prefs.getBoolean(PREF_IS_DARK_MODE, false)) }
    var zoom by remember { mutableStateOf(prefs.getFloat(PREF_ZOOM, 1f)) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var selectedSnapshotFilterNodeIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedSnapshotFilterSnapshotIds by remember {
        mutableStateOf(parseLongSet(prefs.get(PREF_SELECTED_SNAPSHOT_IDS, "")))
    }
    var snapshotFilterMenuExpanded by remember { mutableStateOf(false) }

    val sessionCache = remember { mutableMapOf<String, TableSession>() }
    val coroutineScope = rememberCoroutineScope()

    val snapshotFilterOptions = remember(graphModel) {
        graphModel
            ?.nodes
            ?.filterIsInstance<GraphNode.SnapshotNode>()
            ?.sortedWith(
                compareBy<GraphNode.SnapshotNode> { it.data.sequenceNumber ?: Long.MAX_VALUE }
                    .thenBy { it.data.timestampMs ?: Long.MAX_VALUE }
                    .thenBy { it.data.snapshotId ?: Long.MAX_VALUE }
            )
            ?.map {
                SnapshotFilterOption(
                    nodeId = it.id,
                    snapshotId = it.data.snapshotId,
                    sequenceNumber = it.data.sequenceNumber,
                    timestampMs = it.data.timestampMs
                )
            }
            .orEmpty()
    }
    val allSnapshotFilterNodeIds = remember(snapshotFilterOptions) { snapshotFilterOptions.map { it.nodeId }.toSet() }
    val nodeIdToSnapshotId = remember(snapshotFilterOptions) {
        snapshotFilterOptions.associate { it.nodeId to it.snapshotId }
    }
    val visibleNodeIds = remember(graphModel, selectedSnapshotFilterNodeIds) {
        graphModel?.let { computeVisibleNodeIdsForSnapshotFilter(it, selectedSnapshotFilterNodeIds) }.orEmpty()
    }
    val visibleGraphModel = remember(graphModel, visibleNodeIds) {
        graphModel?.let { filteredGraphModel(it, visibleNodeIds) }
    }

    LaunchedEffect(allSnapshotFilterNodeIds) {
        val constrained = selectedSnapshotFilterNodeIds.intersect(allSnapshotFilterNodeIds)
        if (constrained != selectedSnapshotFilterNodeIds) {
            selectedSnapshotFilterNodeIds = constrained
        }
    }

    LaunchedEffect(snapshotFilterOptions, selectedSnapshotFilterSnapshotIds) {
        val desiredNodeIds = snapshotFilterOptions
            .filter { option -> option.snapshotId != null && option.snapshotId in selectedSnapshotFilterSnapshotIds }
            .map { it.nodeId }
            .toSet()
        if (desiredNodeIds != selectedSnapshotFilterNodeIds) {
            selectedSnapshotFilterNodeIds = desiredNodeIds
        }
    }

    LaunchedEffect(visibleNodeIds) {
        if (selectedNodeIds.isNotEmpty() && visibleNodeIds.isNotEmpty()) {
            val constrained = selectedNodeIds.intersect(visibleNodeIds)
            if (constrained != selectedNodeIds) {
                selectedNodeIds = constrained
            }
        }
    }

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

    fun setWorkspaceExpandedPaths(paths: Set<String>) {
        workspaceExpandedPaths = paths
        prefs.put(PREF_WORKSPACE_EXPANDED_PATHS, paths.joinToString(";"))
    }

    fun saveWorkspace(items: List<WorkspaceItem>) {
        val deduplicated = deduplicateWorkspaceItems(items)
        workspaceItems = deduplicated
        prefs.put(PREF_WORKSPACE_ITEMS, deduplicated.joinToString(";") { it.serialize() })
        val validPaths = deduplicated.map { it.path }.toSet()
        setWorkspaceExpandedPaths(workspaceExpandedPaths.filter { it in validPaths }.toSet())
        val warehousePaths = deduplicated.filterIsInstance<WorkspaceItem.Warehouse>().map { it.path }.toSet()
        warehouseTableStatuses = warehouseTableStatuses
            .filterKeys { it in warehousePaths }
            .toMutableMap()
            .apply {
                deduplicated.filterIsInstance<WorkspaceItem.Warehouse>().forEach { warehouse ->
                    if (this[warehouse.path] == null) {
                        this[warehouse.path] = warehouse.tables.associateWith { WorkspaceTableStatus.EXISTING }
                    }
                }
            }
        val singleTablePaths = deduplicated.filterIsInstance<WorkspaceItem.SingleTable>().map { it.path }.toSet()
        singleTableStatuses = singleTableStatuses
            .filterKeys { it in singleTablePaths }
            .toMutableMap()
            .apply {
                deduplicated.filterIsInstance<WorkspaceItem.SingleTable>().forEach { table ->
                    if (this[table.path] == null) this[table.path] = WorkspaceTableStatus.EXISTING
                }
            }
    }

    fun refreshWarehouseTables() {
        var hasWorkspaceUpdate = false
        var hasStatusUpdate = false

        val refreshedItems = workspaceItems.map { item ->
            if (item is WorkspaceItem.SingleTable) {
                val tableDir = File(item.path)
                val existsNow = tableDir.exists() && tableDir.isDirectory && isIcebergTable(tableDir)
                val previousStatus = singleTableStatuses[item.path] ?: WorkspaceTableStatus.EXISTING
                val newStatus = when {
                    !existsNow -> WorkspaceTableStatus.DELETED
                    previousStatus == WorkspaceTableStatus.DELETED -> WorkspaceTableStatus.NEW
                    else -> previousStatus
                }
                if (newStatus != previousStatus) hasStatusUpdate = true
                return@map item
            }
            if (item !is WorkspaceItem.Warehouse) return@map item

            val scannedTables = scanForTables(File(item.path))
            if (scannedTables != item.tables) {
                hasWorkspaceUpdate = true
            }

            val existingStatuses = warehouseTableStatuses[item.path].orEmpty()
            val knownTableNames = (existingStatuses.keys + scannedTables).toSortedSet()
            val scannedSet = scannedTables.toSet()
            val updatedStatuses = knownTableNames.associateWith { tableName ->
                when {
                    tableName in scannedSet && tableName !in existingStatuses -> WorkspaceTableStatus.NEW
                    tableName in scannedSet && existingStatuses[tableName] == WorkspaceTableStatus.DELETED -> WorkspaceTableStatus.NEW
                    tableName in scannedSet -> existingStatuses[tableName] ?: WorkspaceTableStatus.EXISTING
                    else -> WorkspaceTableStatus.DELETED
                }
            }
            if (updatedStatuses != existingStatuses) {
                hasStatusUpdate = true
            }

            item.copy(tables = scannedTables)
        }

        if (hasWorkspaceUpdate) {
            workspaceItems = deduplicateWorkspaceItems(refreshedItems)
            prefs.put(PREF_WORKSPACE_ITEMS, workspaceItems.joinToString(";") { it.serialize() })
        }

        if (hasStatusUpdate || hasWorkspaceUpdate) {
            singleTableStatuses = refreshedItems
                .filterIsInstance<WorkspaceItem.SingleTable>()
                .associate { table ->
                    val tableDir = File(table.path)
                    val existsNow = tableDir.exists() && tableDir.isDirectory && isIcebergTable(tableDir)
                    val previousStatus = singleTableStatuses[table.path] ?: WorkspaceTableStatus.EXISTING
                    val status = when {
                        !existsNow -> WorkspaceTableStatus.DELETED
                        previousStatus == WorkspaceTableStatus.DELETED -> WorkspaceTableStatus.NEW
                        else -> previousStatus
                    }
                    table.path to status
                }
            warehouseTableStatuses = refreshedItems
                .filterIsInstance<WorkspaceItem.Warehouse>()
                .associate { warehouse ->
                    val scannedSet = warehouse.tables.toSet()
                    val previousStatuses = warehouseTableStatuses[warehouse.path].orEmpty()
                    val knownTableNames = (previousStatuses.keys + warehouse.tables).toSortedSet()
                    val statuses = knownTableNames.associateWith { tableName ->
                        when {
                            tableName in scannedSet && tableName !in previousStatuses -> WorkspaceTableStatus.NEW
                            tableName in scannedSet && previousStatuses[tableName] == WorkspaceTableStatus.DELETED -> WorkspaceTableStatus.NEW
                            tableName in scannedSet -> previousStatuses[tableName] ?: WorkspaceTableStatus.EXISTING
                            else -> WorkspaceTableStatus.DELETED
                        }
                    }
                    warehouse.path to statuses
                }
        }
    }

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

    fun setGraphModelAndBump(model: GraphModel?) {
        graphModel = model
        graphRevision++
    }

    fun updateSnapshotFilterSelection(nodeIds: Set<String>) {
        val constrained = nodeIds.intersect(allSnapshotFilterNodeIds)
        selectedSnapshotFilterNodeIds = constrained
        val snapshotIds = constrained.mapNotNull { nodeIdToSnapshotId[it] }.toSet()
        selectedSnapshotFilterSnapshotIds = snapshotIds
        prefs.put(PREF_SELECTED_SNAPSHOT_IDS, encodeLongSet(snapshotIds))
    }

    fun computeTableFingerprint(tablePath: String): String {
        val metadataDir = File(tablePath, "metadata")
        if (!metadataDir.exists() || !metadataDir.isDirectory) return "missing"
        val trackedFiles = metadataDir.listFiles()?.filter { file ->
            file.isFile && (file.name.endsWith(".metadata.json") || file.name == "version-hint.text")
        }?.sortedBy { it.name }.orEmpty()
        if (trackedFiles.isEmpty()) return "empty"
        val signature = trackedFiles.joinToString("|") { file ->
            "${file.name}:${file.length()}:${file.lastModified()}"
        }
        return signature.hashCode().toString()
    }

    // Logic to load a specific table
    fun loadTable(
        tablePath: String,
        withRows: Boolean = showRows,
        forceRelayout: Boolean = false,
        forceReloadFromFs: Boolean = false,
        preservePositions: Boolean = false
    ) {
        val normalizedTablePath = canonicalWorkspacePath(tablePath)
        val cacheKey = "$normalizedTablePath-rows_$withRows"
        selectedTablePath = normalizedTablePath
        prefs.put(PREF_SELECTED_TABLE_PATH, normalizedTablePath)

        if (!forceRelayout && !forceReloadFromFs && sessionCache.containsKey(cacheKey)) {
            val session = sessionCache[cacheKey]!!
            setGraphModelAndBump(session.graph)
            selectedNodeIds = session.selectedNodeIds
            errorMsg = null
            isLoadingTable = false
            return
        }

        isLoadingTable = true
        errorMsg = null
        val requestId = loadRequestId + 1
        loadRequestId = requestId

        coroutineScope.launch {
            try {
                val previousSession = sessionCache[cacheKey]
                val reloaded = withContext(Dispatchers.Default) {
                    val fingerprint = computeTableFingerprint(normalizedTablePath)
                    if (fingerprint == "missing" && previousSession != null) {
                        return@withContext previousSession.copy(fingerprint = "missing")
                    }
                    if (forceReloadFromFs && !forceRelayout && previousSession != null && previousSession.fingerprint == fingerprint) {
                        return@withContext previousSession
                    }
                    val tableModel = UnifiedTableModel(Paths.get(normalizedTablePath))
                    val newGraph = GraphLayoutService.layoutGraph(tableModel, withRows)
                    if (preservePositions && previousSession != null) {
                        val oldById = previousSession.graph.nodeById
                        newGraph.nodes.forEach { n ->
                            val old = oldById[n.id]
                            if (old != null) {
                                n.x = old.x
                                n.y = old.y
                            }
                        }
                    }
                    TableSession(
                        table = tableModel,
                        graph = newGraph,
                        selectedNodeIds = previousSession?.selectedNodeIds.orEmpty(),
                        fingerprint = fingerprint
                    )
                }

                if (requestId != loadRequestId) return@launch
                sessionCache[cacheKey] = reloaded
                setGraphModelAndBump(reloaded.graph)
                selectedNodeIds = if (preservePositions && !forceRelayout) {
                    selectedNodeIds.filter { id -> reloaded.graph.nodes.any { it.id == id } }.toSet()
                } else {
                    emptySet()
                }
                errorMsg = null
            } catch (e: NoSuchFileException) {
                if (requestId != loadRequestId) return@launch
                val cached = sessionCache[cacheKey]
                if (cached != null) {
                    val fallback = cached.copy(fingerprint = "missing")
                    sessionCache[cacheKey] = fallback
                    setGraphModelAndBump(fallback.graph)
                    selectedNodeIds = selectedNodeIds.filter { id -> fallback.graph.nodes.any { it.id == id } }.toSet()
                    errorMsg = "Table was deleted from filesystem. Showing latest cached snapshot."
                } else {
                    errorMsg = "Table was deleted from filesystem and no cached snapshot is available."
                    if (!forceReloadFromFs) {
                        setGraphModelAndBump(null)
                    }
                }
            } catch (e: Exception) {
                if (requestId != loadRequestId) return@launch
                errorMsg = e.message
                e.printStackTrace()
                if (!forceReloadFromFs) {
                    setGraphModelAndBump(null)
                }
            } finally {
                if (requestId == loadRequestId) {
                    isLoadingTable = false
                }
            }
        }
    }

    fun reapplyCurrentLayout() {
        val currentGraph = graphModel
        if (selectedSnapshotFilterNodeIds.isNotEmpty() && currentGraph != null) {
            val tablePath = selectedTablePath ?: return
            val cacheKey = "$tablePath-rows_$showRows"
            isLoadingTable = true
            errorMsg = null
            val requestId = loadRequestId + 1
            loadRequestId = requestId

            coroutineScope.launch {
                try {
                    val existingTableModel = sessionCache[cacheKey]?.table
                    val fingerprint = withContext(Dispatchers.Default) { computeTableFingerprint(tablePath) }
                    val tableModel = existingTableModel ?: withContext(Dispatchers.Default) {
                        UnifiedTableModel(Paths.get(tablePath))
                    }
                    val fullyLaidOut = withContext(Dispatchers.Default) {
                        GraphLayoutService.layoutGraph(tableModel, showRows)
                    }
                    if (requestId != loadRequestId) return@launch

                    val laidOutById = fullyLaidOut.nodeById
                    currentGraph.nodes.forEach { node ->
                        if (node.id in visibleNodeIds) {
                            laidOutById[node.id]?.let { laidOut ->
                                node.x = laidOut.x
                                node.y = laidOut.y
                            }
                        }
                    }
                    val relaid = GraphModel(
                        nodes = currentGraph.nodes,
                        edges = currentGraph.edges,
                        width = currentGraph.nodes.maxOfOrNull { it.x + it.width } ?: 1.0,
                        height = currentGraph.nodes.maxOfOrNull { it.y + it.height } ?: 1.0
                    )
                    setGraphModelAndBump(relaid)
                    selectedNodeIds = selectedNodeIds.intersect(visibleNodeIds)
                    sessionCache[cacheKey] = TableSession(
                        table = tableModel,
                        graph = relaid,
                        selectedNodeIds = selectedNodeIds,
                        fingerprint = fingerprint
                    )
                    errorMsg = null
                } catch (e: Exception) {
                    if (requestId != loadRequestId) return@launch
                    errorMsg = e.message
                } finally {
                    if (requestId == loadRequestId) {
                        isLoadingTable = false
                    }
                }
            }
            return
        }

        val tablePath = selectedTablePath ?: return
        val cacheKey = "$tablePath-rows_$showRows"

        isLoadingTable = true
        errorMsg = null
        val requestId = loadRequestId + 1
        loadRequestId = requestId

        coroutineScope.launch {
            try {
                val existingTableModel = sessionCache[cacheKey]?.table
                val fingerprint = withContext(Dispatchers.Default) { computeTableFingerprint(tablePath) }
                val tableModel = existingTableModel ?: withContext(Dispatchers.Default) {
                    UnifiedTableModel(Paths.get(tablePath))
                }
                val newGraph = withContext(Dispatchers.Default) {
                    GraphLayoutService.layoutGraph(tableModel, showRows)
                }

                if (requestId != loadRequestId) return@launch
                val session = TableSession(tableModel, newGraph, fingerprint = fingerprint)
                sessionCache[cacheKey] = session
                setGraphModelAndBump(newGraph)
                selectedNodeIds = emptySet()
                errorMsg = null
            } catch (e: Exception) {
                if (requestId != loadRequestId) return@launch
                errorMsg = e.message
            } finally {
                if (requestId == loadRequestId) {
                    isLoadingTable = false
                }
            }
        }
    }

    fun reloadCurrentTableFromFilesystem(preserveLayout: Boolean = true) {
        val tablePath = selectedTablePath ?: return
        loadTable(
            tablePath = tablePath,
            withRows = showRows,
            forceReloadFromFs = true,
            preservePositions = preserveLayout
        )
    }

    @Composable
    fun RenderToolWindowContent(toolWindowId: String) {
        when (toolWindowId) {
            "workspace" -> WorkspacePanel(
                workspaceItems = workspaceItems,
                warehouseTableStatuses = warehouseTableStatuses,
                singleTableStatuses = singleTableStatuses,
                selectedTablePath = selectedTablePath,
                expandedPaths = workspaceExpandedPaths,
                onExpandedPathsChange = { setWorkspaceExpandedPaths(it) },
                searchQuery = workspaceSearchQuery,
                onSearchQueryChange = { workspaceSearchQuery = it },
                onTableSelect = { tablePath ->
                    if (selectedTablePath != null && graphModel != null) {
                        val oldKey = "$selectedTablePath-rows_$showRows"
                        sessionCache[oldKey]?.selectedNodeIds = selectedNodeIds
                    }
                    loadTable(tablePath)
                },
                onAddRoot = { path ->
                    val file = File(path)
                    if (file.exists() && file.isDirectory) {
                        val normalizedPath = canonicalWorkspacePath(path)
                        if (workspaceItems.any { canonicalWorkspacePath(it.path) == normalizedPath }) return@WorkspacePanel

                        val newItem = if (isIcebergTable(file)) {
                            WorkspaceItem.SingleTable(normalizedPath, file.name)
                        } else {
                            WorkspaceItem.Warehouse(normalizedPath, file.name, scanForTables(file))
                        }
                        saveWorkspace(workspaceItems + newItem)
                        if (newItem is WorkspaceItem.Warehouse) {
                            setWorkspaceExpandedPaths(workspaceExpandedPaths + newItem.path)
                        }
                    }
                },
                onRemoveRoot = { item ->
                    saveWorkspace(workspaceItems.filter { it != item })
                },
                onMoveRoot = { item, delta ->
                    val index = workspaceItems.indexOf(item)
                    if (index != -1) {
                        val newIndex = (index + delta).coerceIn(0, workspaceItems.size - 1)
                        if (newIndex != index) {
                            val newList = workspaceItems.toMutableList()
                            newList.removeAt(index)
                            newList.add(newIndex, item)
                            saveWorkspace(newList)
                        }
                    }
                }
            )
            "structure" -> {
                if (visibleGraphModel != null) {
                    NavigationTree(
                        graph = visibleGraphModel,
                        selectedNodeIds = selectedNodeIds,
                        onNodeSelect = { selectedNodeIds = setOf(it.id) }
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
            "inspector" -> NodeDetailsContent(visibleGraphModel, selectedNodeIds)
        }
    }

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

    MaterialTheme(
        colorScheme = if (isDarkMode) IceLensDarkColorScheme else IceLensLightColorScheme
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .onGloballyPositioned { coords -> appWindowBounds = coords.boundsInWindow() }
            ) {
            Column(Modifier.fillMaxSize()) {

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
                            zoom = (zoom / 1.2f).coerceAtLeast(0.1f)
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
                            zoom = (zoom * 1.2f).coerceAtMost(3f)
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
                            if (visibleGraphModel != null) fitGraphRequest++
                        },
                        modifier = Modifier.size(32.dp)
                    )
                    Box(Modifier.width(1.dp).height(16.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    ToolbarIconButton(
                        icon = Icons.Default.Schema,
                        tooltip = "Re-apply Layout",
                        onClick = { reapplyCurrentLayout() },
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
                            isSelected = selectedSnapshotFilterNodeIds.isNotEmpty(),
                            modifier = Modifier.size(32.dp)
                        )
                        DropdownMenu(
                            expanded = snapshotFilterMenuExpanded,
                            onDismissRequest = { snapshotFilterMenuExpanded = false },
                            modifier = Modifier.widthIn(min = 320.dp, max = 520.dp)
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Snapshot Filter",
                                        fontWeight = FontWeight.SemiBold
                                    )
                                },
                                onClick = {}
                            )
                            HorizontalDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { updateSnapshotFilterSelection(allSnapshotFilterNodeIds) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("All", fontSize = 11.sp)
                                }
                                TextButton(
                                    onClick = { updateSnapshotFilterSelection(emptySet()) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("None", fontSize = 11.sp)
                                }
                                TextButton(
                                    onClick = {
                                        updateSnapshotFilterSelection(allSnapshotFilterNodeIds - selectedSnapshotFilterNodeIds)
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Invert", fontSize = 11.sp)
                                }
                            }
                            HorizontalDivider()
                            if (snapshotFilterOptions.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No snapshots") },
                                    onClick = {}
                                )
                            } else {
                                snapshotFilterOptions.forEach { option ->
                                    val isSelected = option.nodeId in selectedSnapshotFilterNodeIds
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
                                                selectedSnapshotFilterNodeIds - option.nodeId
                                            } else {
                                                selectedSnapshotFilterNodeIds + option.nodeId
                                            }
                                            updateSnapshotFilterSelection(updated)
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Box(Modifier.width(1.dp).height(16.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    Text(
                        text = if (selectedSnapshotFilterNodeIds.isEmpty()) {
                            "All"
                        } else {
                            "${selectedSnapshotFilterNodeIds.size}/${snapshotFilterOptions.size}"
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
                            errorMsg = "Failed to open GitHub link: ${e.message}"
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

                Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                    val currentGraph = visibleGraphModel
                    if (currentGraph != null) {
                        key(graphRevision) {
                            GraphCanvas(
                                graph = currentGraph,
                                graphRevision = graphRevision,
                                fitGraphRequest = fitGraphRequest,
                                selectedNodeIds = selectedNodeIds,
                                isSelectMode = isSelectMode,
                                zoom = zoom,
                                onZoomChange = {
                                    zoom = it
                                    prefs.putFloat(PREF_ZOOM, it)
                                },
                                onSelectionChange = { selectedNodeIds = it },
                                onEmptyAreaDoubleClick = { toggleAllPanelsVisibility() },
                                onNodeDoubleClick = { toggleInspectorVisibility() }
                            )
                        }
                    } else if (!isLoadingTable && workspaceItems.isNotEmpty()) {
                        Text(
                            "Select a table from the sidebar to view its structure.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else if (!isLoadingTable && workspaceItems.isEmpty()) {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Storage,
                                contentDescription = null,
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
                                "Click \"Add to Workspace\" in the sidebar to get started.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }

                    if (isLoadingTable) {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text("Loading table...", color = MaterialTheme.colorScheme.onSurface)
                        }
                    }

                    if (errorMsg != null) {
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
                                    "Error: $errorMsg",
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                IconButton(
                                    onClick = { errorMsg = null },
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
                // Keep bottom-anchored toolwindow buttons reachable even when their panes are hidden.
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

            if (showAboutDialog) {
                val githubUrl = "https://github.com/mmdemirbas/ice-lens"
                AlertDialog(
                    onDismissRequest = { showAboutDialog = false },
                    confirmButton = {
                        TextButton(onClick = { showAboutDialog = false }) {
                            Text("Close")
                        }
                    },
                    title = { Text("About Ice Lens") },
                    text = {
                        Column {
                            Text("A visual inspector for Apache Iceberg tables. It visualizes metadata, snapshots, manifests, and row-level delete relationships.")
                            Spacer(Modifier.height(8.dp))
                            Text("Author: Muhammed Demirbaş")
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = githubUrl,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.clickable {
                                    runCatching {
                                        if (!Desktop.isDesktopSupported()) {
                                            error("Desktop browsing is not supported on this platform.")
                                        }
                                        Desktop.getDesktop().browse(URI(githubUrl))
                                    }.onFailure { e ->
                                        errorMsg = "Failed to open GitHub link: ${e.message}"
                                    }
                                }
                            )
                        }
                    }
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

    LaunchedEffect(errorMsg) {
        if (errorMsg != null) {
            delay(8000)
            errorMsg = null
        }
    }

    LaunchedEffect(showRows) {
        prefs.putBoolean(PREF_SHOW_ROWS, showRows)
    }

    LaunchedEffect(snapshotFilterOptions, selectedSnapshotFilterNodeIds) {
        if (snapshotFilterOptions.isEmpty()) return@LaunchedEffect
        val snapshotIds = selectedSnapshotFilterNodeIds
            .mapNotNull { nodeIdToSnapshotId[it] }
            .toSet()
        if (snapshotIds != selectedSnapshotFilterSnapshotIds) {
            selectedSnapshotFilterSnapshotIds = snapshotIds
            prefs.put(PREF_SELECTED_SNAPSHOT_IDS, encodeLongSet(snapshotIds))
        }
    }

    LaunchedEffect(selectedTablePath, showRows) {
        while (isActive) {
            val tablePath = selectedTablePath
            if (tablePath != null && !isLoadingTable) {
                val cacheKey = "$tablePath-rows_$showRows"
                val session = sessionCache[cacheKey]
                if (session != null) {
                    val currentFingerprint = withContext(Dispatchers.Default) { computeTableFingerprint(tablePath) }
                    if (currentFingerprint != session.fingerprint) {
                        reloadCurrentTableFromFilesystem(preserveLayout = true)
                    }
                }
            }
            delay(3000)
        }
    }

    LaunchedEffect(workspaceItems) {
        refreshWarehouseTables()
    }

    LaunchedEffect(Unit) {
        val restoredTablePath = selectedTablePath
        if (restoredTablePath != null && graphModel == null && !isLoadingTable) {
            loadTable(tablePath = restoredTablePath, withRows = showRows)
        }
        while (isActive) {
            refreshWarehouseTables()
            delay(3000)
        }
    }
}
