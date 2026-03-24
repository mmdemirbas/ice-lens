package ui

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import model.*
import service.GraphLayoutService
import service.TableFormat
import service.TableFormatDetector
import java.io.File
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.prefs.Preferences
import kotlin.coroutines.coroutineContext

private val logger = LoggerFactory.getLogger(AppState::class.java)

data class TableSession(
    val table: UnifiedTableModel? = null,
    val paimonTable: PaimonUnifiedTableModel? = null,
    val graph: GraphModel,
    var selectedNodeIds: Set<String> = emptySet(),
    val fingerprint: String = "",
)

class AppState(
    private val prefs: Preferences,
    private val coroutineScope: CoroutineScope,
) {
    companion object {
        internal const val PREF_WORKSPACE_ITEMS = "workspace_items"
        internal const val PREF_WORKSPACE_EXPANDED_PATHS = "workspace_expanded_paths"
        internal const val PREF_SELECTED_TABLE_PATH = "selected_table_path"
        internal const val PREF_SELECTED_SNAPSHOT_IDS = "selected_snapshot_ids"
        internal const val PREF_SHOW_ROWS = "show_data_rows"
        internal const val PREF_LAST_BROWSE_DIRECTORY = "last_browse_directory"
    }

    // ═══════════════════════════════════════════════════════════════
    //  Workspace State
    // ═══════════════════════════════════════════════════════════════

    var workspaceItems by mutableStateOf<List<WorkspaceItem>>(emptyList())
        private set
    var workspaceExpandedPaths by mutableStateOf<Set<String>>(emptySet())
        private set
    var warehouseTableStatuses by mutableStateOf<Map<String, Map<String, WorkspaceTableStatus>>>(emptyMap())
        private set
    var singleTableStatuses by mutableStateOf<Map<String, WorkspaceTableStatus>>(emptyMap())
        private set
    var lastBrowseDirectory by mutableStateOf<String?>(null)
        private set
    var workspaceSearchQuery by mutableStateOf("")

    // ═══════════════════════════════════════════════════════════════
    //  Table / Graph State
    // ═══════════════════════════════════════════════════════════════

    var selectedTablePath by mutableStateOf<String?>(null)
        private set
    var graphModel by mutableStateOf<GraphModel?>(null, neverEqualPolicy())
        private set
    var graphRevision by mutableStateOf(0)
        private set
    var selectedNodeIds by mutableStateOf<Set<String>>(emptySet())
    var errorMsg by mutableStateOf<String?>(null)
    var isStaleData by mutableStateOf(false)
        private set
    var isLoadingTable by mutableStateOf(false)
        private set
    var showRows by mutableStateOf(true)
        private set

    // ═══════════════════════════════════════════════════════════════
    //  Snapshot Filter State
    // ═══════════════════════════════════════════════════════════════

    var selectedSnapshotFilterNodeIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var selectedSnapshotFilterSnapshotIds by mutableStateOf<Set<Long>>(emptySet())
        private set

    // ═══════════════════════════════════════════════════════════════
    //  Session Cache
    // ═══════════════════════════════════════════════════════════════

    val sessionCache = ConcurrentHashMap<String, TableSession>()
    private var loadRequestId = 0L

    // ═══════════════════════════════════════════════════════════════
    //  Derived State
    // ═══════════════════════════════════════════════════════════════

    val snapshotFilterOptions: List<SnapshotFilterOption> by derivedStateOf {
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

    val allSnapshotFilterNodeIds: Set<String> by derivedStateOf {
        snapshotFilterOptions.map { it.nodeId }.toSet()
    }

    val nodeIdToSnapshotId: Map<String, Long?> by derivedStateOf {
        snapshotFilterOptions.associate { it.nodeId to it.snapshotId }
    }

    val visibleNodeIds: Set<String> by derivedStateOf {
        graphModel?.let { computeVisibleNodeIdsForSnapshotFilter(it, selectedSnapshotFilterNodeIds) }.orEmpty()
    }

    val visibleGraphModel: GraphModel? by derivedStateOf {
        graphModel?.let { filteredGraphModel(it, visibleNodeIds) }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Initialization
    // ═══════════════════════════════════════════════════════════════

    init {
        loadPersistedState()
    }

    private fun loadPersistedState() {
        logger.info("Loading persisted workspace state")
        // Workspace items
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
        workspaceItems = deduplicated

        // Selected table
        selectedTablePath = prefs.get(PREF_SELECTED_TABLE_PATH, "").trim().ifBlank { null }

        // Expanded paths
        val savedExpanded = prefs.get(PREF_WORKSPACE_EXPANDED_PATHS, "")
        workspaceExpandedPaths = savedExpanded
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        // Statuses
        warehouseTableStatuses = initialWarehouseTableStatuses(workspaceItems)
        singleTableStatuses = workspaceItems
            .filterIsInstance<WorkspaceItem.SingleTable>()
            .associate { table ->
                val dir = File(table.path)
                val exists = dir.exists() && dir.isDirectory && isTableDirectory(dir)
                table.path to if (exists) WorkspaceTableStatus.EXISTING else WorkspaceTableStatus.DELETED
            }

        // Other persisted state
        showRows = prefs.getBoolean(PREF_SHOW_ROWS, true)
        selectedSnapshotFilterSnapshotIds = parseLongSet(prefs.get(PREF_SELECTED_SNAPSHOT_IDS, ""))
        lastBrowseDirectory = prefs.get(PREF_LAST_BROWSE_DIRECTORY, "").ifBlank { null }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Workspace Management
    // ═══════════════════════════════════════════════════════════════

    fun updateWorkspaceExpandedPaths(paths: Set<String>) {
        workspaceExpandedPaths = paths
        prefs.put(PREF_WORKSPACE_EXPANDED_PATHS, paths.joinToString(";"))
    }

    fun saveWorkspace(items: List<WorkspaceItem>) {
        val deduplicated = deduplicateWorkspaceItems(items)
        workspaceItems = deduplicated
        prefs.put(PREF_WORKSPACE_ITEMS, deduplicated.joinToString(";") { it.serialize() })
        val validPaths = deduplicated.map { it.path }.toSet()
        updateWorkspaceExpandedPaths(workspaceExpandedPaths.filter { it in validPaths }.toSet())

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

    fun addWorkspaceRoot(path: String) {
        val file = File(path)
        if (file.exists() && file.isDirectory) {
            val normalizedPath = canonicalWorkspacePath(path)
            if (workspaceItems.any { canonicalWorkspacePath(it.path) == normalizedPath }) {
                logger.debug("Workspace root already exists, skipping: {}", normalizedPath)
                return
            }

            val newItem = if (isTableDirectory(file)) {
                WorkspaceItem.SingleTable(normalizedPath, file.name)
            } else {
                WorkspaceItem.Warehouse(normalizedPath, file.name, scanForTables(file))
            }
            logger.info("Adding workspace root: {} ({})", normalizedPath, newItem::class.simpleName)
            if (newItem is WorkspaceItem.Warehouse) {
                logger.info("  Found {} tables in warehouse", newItem.tables.size)
            }
            saveWorkspace(workspaceItems + newItem)
            if (newItem is WorkspaceItem.Warehouse) {
                updateWorkspaceExpandedPaths(workspaceExpandedPaths + newItem.path)
            }
        } else {
            logger.warn("Cannot add workspace root — path does not exist or is not a directory: {}", path)
        }
    }

    fun removeWorkspaceRoot(item: WorkspaceItem) {
        saveWorkspace(workspaceItems.filter { it != item })
    }

    fun moveWorkspaceRoot(item: WorkspaceItem, delta: Int) {
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

    fun refreshWarehouseTables() {
        var hasWorkspaceUpdate = false
        var hasStatusUpdate = false

        val refreshedItems = workspaceItems.map { item ->
            if (item is WorkspaceItem.SingleTable) {
                val tableDir = File(item.path)
                val existsNow = tableDir.exists() && tableDir.isDirectory && isTableDirectory(tableDir)
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
                    val existsNow = tableDir.exists() && tableDir.isDirectory && isTableDirectory(tableDir)
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

    fun updateLastBrowseDirectory(dir: String) {
        lastBrowseDirectory = dir
        prefs.put(PREF_LAST_BROWSE_DIRECTORY, dir)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Table Loading
    // ═══════════════════════════════════════════════════════════════

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

    fun updateShowRows(value: Boolean) {
        showRows = value
        prefs.putBoolean(PREF_SHOW_ROWS, value)
    }

    fun setGraphModelAndBump(model: GraphModel?) {
        model?.syncPositionsToUI()
        graphModel = model
        graphRevision++
        syncSnapshotFilterFromSnapshotIds()
        constrainSnapshotFilter()
        constrainNodeSelection()
    }

    /** Save current selection into session cache before switching tables. */
    fun saveCurrentSessionSelection() {
        val tablePath = selectedTablePath ?: return
        val cacheKey = "$tablePath-rows_$showRows"
        sessionCache[cacheKey]?.selectedNodeIds = selectedNodeIds
    }

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

        val cachedSession = if (!forceRelayout && !forceReloadFromFs) sessionCache[cacheKey] else null
        if (cachedSession != null) {
            logger.debug("Cache hit for table: {}", normalizedTablePath)
            setGraphModelAndBump(cachedSession.graph)
            selectedNodeIds = cachedSession.selectedNodeIds
            errorMsg = null
            isLoadingTable = false
            return
        }

        logger.info("Loading table: {} (showRows={}, forceRelayout={}, forceReloadFromFs={}, preservePositions={})",
            normalizedTablePath, withRows, forceRelayout, forceReloadFromFs, preservePositions)
        isLoadingTable = true
        errorMsg = null
        val requestId = loadRequestId + 1
        loadRequestId = requestId

        coroutineScope.launch {
            try {
                val previousSession = sessionCache[cacheKey]
                val reloaded = withContext(Dispatchers.Default) {
                    coroutineContext.ensureActive()
                    val fingerprint = computeTableFingerprint(normalizedTablePath)
                    if (fingerprint == "missing" && previousSession != null) {
                        return@withContext previousSession.copy(fingerprint = "missing")
                    }
                    if (forceReloadFromFs && !forceRelayout && previousSession != null && previousSession.fingerprint == fingerprint) {
                        return@withContext previousSession
                    }
                    coroutineContext.ensureActive()
                    val tablePath = Paths.get(normalizedTablePath)
                    val format = TableFormatDetector.detect(tablePath.toFile())
                    coroutineContext.ensureActive()
                    val (icebergModel, paimonModel, newGraphRaw) = when (format) {
                        TableFormat.PAIMON -> {
                            val pm = PaimonUnifiedTableModel(tablePath)
                            Triple(null, pm, GraphLayoutService.layoutPaimonGraph(pm, withRows))
                        }
                        else -> {
                            val im = UnifiedTableModel(tablePath)
                            Triple(im, null, GraphLayoutService.layoutGraph(im, withRows))
                        }
                    }
                    var newGraph = newGraphRaw
                    if (preservePositions && previousSession != null) {
                        val oldInitial = previousSession.graph.initialPositions
                        val mergedPositions = newGraph.initialPositions.toMutableMap()
                        newGraph.nodes.forEach { n ->
                            oldInitial[n.id]?.let { mergedPositions[n.id] = it }
                        }
                        newGraph = newGraph.copy(initialPositions = mergedPositions)
                    }
                    TableSession(
                        table = icebergModel,
                        paimonTable = paimonModel,
                        graph = newGraph,
                        selectedNodeIds = previousSession?.selectedNodeIds.orEmpty(),
                        fingerprint = fingerprint
                    )
                }

                if (requestId != loadRequestId) return@launch
                sessionCache[cacheKey] = reloaded
                logger.info("Table loaded successfully: {} ({} nodes, {} edges)",
                    normalizedTablePath, reloaded.graph.nodes.size, reloaded.graph.edges.size)
                setGraphModelAndBump(reloaded.graph)
                selectedNodeIds = if (preservePositions && !forceRelayout) {
                    selectedNodeIds.filter { id -> reloaded.graph.nodes.any { it.id == id } }.toSet()
                } else {
                    emptySet()
                }
                errorMsg = null
                isStaleData = false
            } catch (e: NoSuchFileException) {
                if (requestId != loadRequestId) return@launch
                logger.warn("Table path no longer exists: {}", normalizedTablePath)
                val cached = sessionCache[cacheKey]
                if (cached != null) {
                    val fallback = cached.copy(fingerprint = "missing")
                    sessionCache[cacheKey] = fallback
                    setGraphModelAndBump(fallback.graph)
                    selectedNodeIds = selectedNodeIds.filter { id -> fallback.graph.nodes.any { it.id == id } }.toSet()
                    errorMsg = "Table was deleted from filesystem. Showing latest cached snapshot."
                    isStaleData = true
                } else {
                    errorMsg = "Table was deleted from filesystem and no cached snapshot is available."
                    if (!forceReloadFromFs) {
                        setGraphModelAndBump(null)
                    }
                }
            } catch (e: Exception) {
                if (requestId != loadRequestId) return@launch
                errorMsg = e.message
                logger.error("Failed to load table: {}", normalizedTablePath, e)
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
                    val existingSession = sessionCache[cacheKey]
                    val fingerprint = withContext(Dispatchers.Default) { computeTableFingerprint(tablePath) }
                    val fullyLaidOut = withContext(Dispatchers.Default) {
                        if (existingSession?.paimonTable != null) {
                            val pm = existingSession.paimonTable
                            GraphLayoutService.layoutPaimonGraph(pm, showRows)
                        } else {
                            val tableModel = existingSession?.table ?: UnifiedTableModel(Paths.get(tablePath))
                            GraphLayoutService.layoutGraph(tableModel, showRows)
                        }
                    }
                    if (requestId != loadRequestId) return@launch

                    val mergedPositions = currentGraph.initialPositions.toMutableMap()
                    mergedPositions.putAll(currentGraph.positions)
                    currentGraph.nodes.forEach { node ->
                        if (node.id in visibleNodeIds) {
                            fullyLaidOut.initialPositions[node.id]?.let { mergedPositions[node.id] = it }
                        }
                    }
                    val relaid = GraphModel(
                        nodes = currentGraph.nodes,
                        edges = currentGraph.edges,
                        width = currentGraph.nodes.maxOfOrNull { (mergedPositions[it.id]?.x?.toDouble() ?: 0.0) + it.width } ?: 1.0,
                        height = currentGraph.nodes.maxOfOrNull { (mergedPositions[it.id]?.y?.toDouble() ?: 0.0) + it.height } ?: 1.0,
                        initialPositions = mergedPositions
                    )
                    setGraphModelAndBump(relaid)
                    selectedNodeIds = selectedNodeIds.intersect(visibleNodeIds)
                    sessionCache[cacheKey] = TableSession(
                        table = existingSession?.table,
                        paimonTable = existingSession?.paimonTable,
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
                val existingSession = sessionCache[cacheKey]
                val fingerprint = withContext(Dispatchers.Default) { computeTableFingerprint(tablePath) }
                val format = withContext(Dispatchers.Default) { TableFormatDetector.detect(java.io.File(tablePath)) }
                val (icebergModel, paimonModel, newGraph) = withContext(Dispatchers.Default) {
                    when {
                        format == TableFormat.PAIMON || existingSession?.paimonTable != null -> {
                            val pm = existingSession?.paimonTable ?: PaimonUnifiedTableModel(Paths.get(tablePath))
                            Triple(null, pm, GraphLayoutService.layoutPaimonGraph(pm, showRows))
                        }
                        else -> {
                            val im = existingSession?.table ?: UnifiedTableModel(Paths.get(tablePath))
                            Triple(im, null, GraphLayoutService.layoutGraph(im, showRows))
                        }
                    }
                }

                if (requestId != loadRequestId) return@launch
                val session = TableSession(table = icebergModel, paimonTable = paimonModel, graph = newGraph, fingerprint = fingerprint)
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

    // ═══════════════════════════════════════════════════════════════
    //  Snapshot Filter
    // ═══════════════════════════════════════════════════════════════

    fun updateSnapshotFilterSelection(nodeIds: Set<String>) {
        val constrained = nodeIds.intersect(allSnapshotFilterNodeIds)
        selectedSnapshotFilterNodeIds = constrained
        val snapshotIds = constrained.mapNotNull { nodeIdToSnapshotId[it] }.toSet()
        selectedSnapshotFilterSnapshotIds = snapshotIds
        prefs.put(PREF_SELECTED_SNAPSHOT_IDS, encodeLongSet(snapshotIds))
        constrainNodeSelection()
    }

    /** Persist current snapshot filter state. Called from periodic sync. */
    fun persistSnapshotFilter() {
        val snapshotIds = selectedSnapshotFilterNodeIds
            .mapNotNull { nodeIdToSnapshotId[it] }
            .toSet()
        if (snapshotIds != selectedSnapshotFilterSnapshotIds) {
            selectedSnapshotFilterSnapshotIds = snapshotIds
            prefs.put(PREF_SELECTED_SNAPSHOT_IDS, encodeLongSet(snapshotIds))
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Internal Helpers
    // ═══════════════════════════════════════════════════════════════

    private fun syncSnapshotFilterFromSnapshotIds() {
        val desiredNodeIds = snapshotFilterOptions
            .filter { option -> option.snapshotId != null && option.snapshotId in selectedSnapshotFilterSnapshotIds }
            .map { it.nodeId }
            .toSet()
        if (desiredNodeIds != selectedSnapshotFilterNodeIds) {
            selectedSnapshotFilterNodeIds = desiredNodeIds
        }
    }

    private fun constrainSnapshotFilter() {
        val constrained = selectedSnapshotFilterNodeIds.intersect(allSnapshotFilterNodeIds)
        if (constrained != selectedSnapshotFilterNodeIds) {
            selectedSnapshotFilterNodeIds = constrained
        }
    }

    private fun constrainNodeSelection() {
        val visible = visibleNodeIds
        if (selectedNodeIds.isNotEmpty() && visible.isNotEmpty()) {
            val constrained = selectedNodeIds.intersect(visible)
            if (constrained != selectedNodeIds) {
                selectedNodeIds = constrained
            }
        }
    }
}
