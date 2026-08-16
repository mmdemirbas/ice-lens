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
import service.AggregationPolicy
import service.GraphAggregation
import service.GraphLayoutService
import service.TableFormat
import service.TableFormatDetector
import java.io.File
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import java.util.prefs.Preferences
import kotlin.coroutines.coroutineContext

private val logger = LoggerFactory.getLogger(AppState::class.java)

data class TableSession(
    val tableModel: FormatTableModel? = null,
    val graph: GraphModel,
    var selectedNodeIds: Set<String> = emptySet(),
    val fingerprint: String = "",
    /**
     * The group nodes that were open when [graph] was built.
     *
     * Kept with the graph rather than beside it, because the two only mean anything together —
     * a cached graph restored under a different expansion set would draw one thing and report
     * another.
     */
    val expandedGroupIds: Set<String> = emptySet(),
)

class AppState(
    private val prefs: Preferences,
    private val coroutineScope: CoroutineScope,
    private val backgroundDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
) {
    companion object {
        internal const val PREF_WORKSPACE_ITEMS = "workspace_items"
        internal const val PREF_WORKSPACE_EXPANDED_PATHS = "workspace_expanded_paths"
        internal const val PREF_SELECTED_TABLE_PATH = "selected_table_path"
        internal const val PREF_SELECTED_SNAPSHOT_IDS = "selected_snapshot_ids"
        internal const val PREF_SHOW_ROWS = "show_data_rows"
        internal const val PREF_LAST_BROWSE_DIRECTORY = "last_browse_directory"
        internal const val PREF_GRAPH_PAGE_SIZE = "graph_page_size"

        /**
         * The page sizes the badge offers. A reader on a 27-inch monitor and one on a laptop
         * want different numbers, and the measured budget (500 composed cards on screen) is a
         * property of the machine, not of the table.
         */
        val GRAPH_PAGE_SIZE_CHOICES = listOf(8, 16, 24, 48, 96, 200)
        const val MIN_GRAPH_PAGE_SIZE = 2
        const val MAX_GRAPH_PAGE_SIZE = 2_000
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

    /**
     * Where the user has dragged nodes of [graphModel]. Shell state layered over the core
     * model's immutable layout positions — see [NodePositions]. Replaced whenever the graph is.
     */
    var nodePositions by mutableStateOf<NodePositions?>(null)
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

    /**
     * Group nodes the reader has opened, for the table currently shown.
     *
     * Not persisted. It is a view state about one session with one table, and the ids are
     * derived from the parent, the kind and the page — so an id kept across a table switch
     * would silently mean something else.
     */
    var expandedGroupIds by mutableStateOf<Set<String>>(emptySet())
        private set

    /**
     * How many siblings of one kind are drawn under one parent before the rest become a group.
     *
     * Persisted, unlike [expandedGroupIds]: it says something about this reader's screen rather
     * than about one table.
     */
    var graphPageSize by mutableStateOf(AggregationPolicy.DEFAULT_PAGE_SIZE)
        private set

    private val aggregationPolicy: AggregationPolicy get() = AggregationPolicy(graphPageSize)

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

    /**
     * LRU session cache. Each session can hold a 50–100 MB graph for large tables;
     * an unbounded cache OOMs after switching across enough tables in one run.
     */
    private val maxCachedSessions = 5
    val sessionCache: MutableMap<String, TableSession> = Collections.synchronizedMap(
        object : LinkedHashMap<String, TableSession>(maxCachedSessions + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, TableSession>): Boolean =
                size > maxCachedSessions
        }
    )
    private val loadRequestId = AtomicLong(0L)

    // ═══════════════════════════════════════════════════════════════
    //  Derived State
    // ═══════════════════════════════════════════════════════════════

    val snapshotFilterOptions: List<SnapshotFilterOption> by derivedStateOf {
        graphModel
            ?.nodes
            ?.mapNotNull { it.asSnapshotFilterOption() }
            ?.sortedWith(
                compareBy<SnapshotFilterOption> { it.sequenceNumber ?: Long.MAX_VALUE }
                    .thenBy { it.timestampMs ?: Long.MAX_VALUE }
                    .thenBy { it.snapshotId ?: Long.MAX_VALUE }
            )
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
        graphPageSize = prefs.getInt(PREF_GRAPH_PAGE_SIZE, AggregationPolicy.DEFAULT_PAGE_SIZE)
            .coerceIn(MIN_GRAPH_PAGE_SIZE, MAX_GRAPH_PAGE_SIZE)
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

    /** Creates the appropriate format-specific table model for a table path. */
    private fun loadTableModel(tablePath: String): FormatTableModel {
        val path = Paths.get(tablePath)
        return when (TableFormatDetector.detect(path.toFile())) {
            TableFormat.PAIMON -> PaimonUnifiedTableModel(path)
            else -> UnifiedTableModel(path)
        }
    }

    fun computeTableFingerprint(tablePath: String): String {
        val tableDir = File(tablePath)
        if (!tableDir.exists() || !tableDir.isDirectory) return "missing"
        // Iceberg first; fall back to Paimon. UNKNOWN treated as a present-but-empty directory.
        val trackedFiles = icebergTrackedFiles(tableDir)
            .ifEmpty { paimonTrackedFiles(tableDir) }
        if (trackedFiles.isEmpty()) return "empty"
        val signature = trackedFiles.joinToString("|") { file ->
            "${file.name}:${file.length()}:${file.lastModified()}"
        }
        return signature.hashCode().toString()
    }

    private fun icebergTrackedFiles(tableDir: File): List<File> {
        val metadataDir = File(tableDir, "metadata")
        if (!metadataDir.exists() || !metadataDir.isDirectory) return emptyList()
        return metadataDir.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".metadata.json") || it.name == "version-hint.text") }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    private fun paimonTrackedFiles(tableDir: File): List<File> {
        val snapshotDir = File(tableDir, "snapshot")
        val schemaDir = File(tableDir, "schema")
        if (!snapshotDir.isDirectory && !schemaDir.isDirectory) return emptyList()
        val snapshotFiles = snapshotDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("snapshot-") }
            ?.sortedBy { it.name }
            .orEmpty()
        val schemaFiles = schemaDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("schema-") }
            ?.sortedBy { it.name }
            .orEmpty()
        return snapshotFiles + schemaFiles
    }

    fun updateShowRows(value: Boolean) {
        showRows = value
        prefs.putBoolean(PREF_SHOW_ROWS, value)
    }

    fun setGraphModelAndBump(model: GraphModel?) {
        graphModel = model
        // Drag state belongs to the graph being shown. A replacement graph starts from whatever
        // layout decided, which is also what discards drags for nodes that no longer exist.
        nodePositions = model?.let { NodePositions(it) }
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
            // Bump request id so any in-flight load/reapply detects staleness and bails out.
            loadRequestId.incrementAndGet()
            expandedGroupIds = cachedSession.expandedGroupIds
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
        val requestId = loadRequestId.incrementAndGet()

        coroutineScope.launch {
            try {
                val previousSession = sessionCache[cacheKey]
                val reloaded = withContext(backgroundDispatcher) {
                    coroutineContext.ensureActive()
                    val fingerprint = computeTableFingerprint(normalizedTablePath)
                    if (fingerprint == "missing" && previousSession != null) {
                        return@withContext previousSession.copy(fingerprint = "missing")
                    }
                    if (forceReloadFromFs && !forceRelayout && previousSession != null && previousSession.fingerprint == fingerprint) {
                        return@withContext previousSession
                    }
                    coroutineContext.ensureActive()
                    val tableModel = loadTableModel(normalizedTablePath)
                    coroutineContext.ensureActive()
                    val expanded = previousSession?.expandedGroupIds.orEmpty()
                    var newGraph = GraphLayoutService.layoutGraph(tableModel, withRows, expanded, aggregationPolicy)
                    if (preservePositions && previousSession != null) {
                        val oldInitial = previousSession.graph.layoutPositions
                        val mergedPositions = newGraph.layoutPositions.toMutableMap()
                        newGraph.nodes.forEach { n ->
                            oldInitial[n.id]?.let { mergedPositions[n.id] = it }
                        }
                        newGraph = newGraph.copy(layoutPositions = mergedPositions)
                    }
                    TableSession(
                        tableModel = tableModel,
                        graph = newGraph,
                        selectedNodeIds = previousSession?.selectedNodeIds.orEmpty(),
                        fingerprint = fingerprint,
                        expandedGroupIds = expanded,
                    )
                }

                if (requestId != loadRequestId.get()) return@launch
                sessionCache[cacheKey] = reloaded
                expandedGroupIds = reloaded.expandedGroupIds
                logger.info("Table loaded successfully: {} ({} nodes drawn, {} hidden, {} edges)",
                    normalizedTablePath, reloaded.graph.nodes.size,
                    reloaded.graph.hiddenNodeCount, reloaded.graph.edges.size)
                setGraphModelAndBump(reloaded.graph)
                selectedNodeIds = if (preservePositions && !forceRelayout) {
                    selectedNodeIds.filter { id -> reloaded.graph.nodes.any { it.id == id } }.toSet()
                } else {
                    emptySet()
                }
                errorMsg = null
                isStaleData = false
            } catch (e: NoSuchFileException) {
                if (requestId != loadRequestId.get()) return@launch
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
                    setGraphModelAndBump(null)
                }
            } catch (e: Exception) {
                if (requestId != loadRequestId.get()) return@launch
                errorMsg = e.message
                logger.error("Failed to load table: {}", normalizedTablePath, e)
                if (sessionCache[cacheKey] == null) {
                    setGraphModelAndBump(null)
                }
            } finally {
                if (requestId == loadRequestId.get()) {
                    isLoadingTable = false
                }
            }
        }
    }

    fun reapplyCurrentLayout() {
        if (selectedSnapshotFilterNodeIds.isNotEmpty() && graphModel != null) {
            val tablePath = selectedTablePath ?: return
            val cacheKey = "$tablePath-rows_$showRows"
            isLoadingTable = true
            errorMsg = null
            val requestId = loadRequestId.incrementAndGet()

            coroutineScope.launch {
                try {
                    val existingSession = sessionCache[cacheKey]
                    val fingerprint = withContext(backgroundDispatcher) {
                        coroutineContext.ensureActive()
                        computeTableFingerprint(tablePath)
                    }
                    coroutineContext.ensureActive()
                    val model = existingSession?.tableModel ?: withContext(backgroundDispatcher) {
                        coroutineContext.ensureActive()
                        loadTableModel(tablePath)
                    }
                    coroutineContext.ensureActive()
                    val fullyLaidOut = withContext(backgroundDispatcher) {
                        coroutineContext.ensureActive()
                        GraphLayoutService.layoutGraph(model, showRows, expandedGroupIds, aggregationPolicy)
                    }
                    if (requestId != loadRequestId.get()) return@launch
                    // T-1: re-read graphModel on the main thread after the staleness check —
                    // it may have been replaced via a cache hit since this coroutine started.
                    val activeGraph = graphModel ?: return@launch

                    // Start from where the previous layout put things, let the user's drags win
                    // over that, then let a freshly laid-out visible node win over both. Reading
                    // the drag state is main-thread work, which is where this line runs.
                    val mergedPositions = activeGraph.layoutPositions.toMutableMap()
                    nodePositions?.draggedSnapshot()?.forEach { (id, xy) ->
                        mergedPositions[id] = Point(xy.first.toFloat(), xy.second.toFloat())
                    }
                    activeGraph.nodes.forEach { node ->
                        if (node.id in visibleNodeIds) {
                            fullyLaidOut.layoutPositions[node.id]?.let { mergedPositions[node.id] = it }
                        }
                    }
                    val relaid = GraphModel(
                        nodes = activeGraph.nodes,
                        edges = activeGraph.edges,
                        width = activeGraph.nodes.maxOfOrNull { (mergedPositions[it.id]?.x?.toDouble() ?: 0.0) + it.width } ?: 1.0,
                        height = activeGraph.nodes.maxOfOrNull { (mergedPositions[it.id]?.y?.toDouble() ?: 0.0) + it.height } ?: 1.0,
                        layoutPositions = mergedPositions
                    )
                    setGraphModelAndBump(relaid)
                    selectedNodeIds = selectedNodeIds.intersect(visibleNodeIds)
                    sessionCache[cacheKey] = TableSession(
                        tableModel = model,
                        graph = relaid,
                        selectedNodeIds = selectedNodeIds,
                        fingerprint = fingerprint
                    )
                    errorMsg = null
                } catch (e: Exception) {
                    if (requestId != loadRequestId.get()) return@launch
                    errorMsg = e.message
                } finally {
                    if (requestId == loadRequestId.get()) {
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
        val requestId = loadRequestId.incrementAndGet()

        coroutineScope.launch {
            try {
                val existingSession = sessionCache[cacheKey]
                val fingerprint = withContext(backgroundDispatcher) {
                    coroutineContext.ensureActive()
                    computeTableFingerprint(tablePath)
                }
                coroutineContext.ensureActive()
                val model = existingSession?.tableModel ?: withContext(backgroundDispatcher) {
                    coroutineContext.ensureActive()
                    loadTableModel(tablePath)
                }
                coroutineContext.ensureActive()
                val newGraph = withContext(backgroundDispatcher) {
                    coroutineContext.ensureActive()
                    GraphLayoutService.layoutGraph(model, showRows, expandedGroupIds, aggregationPolicy)
                }

                if (requestId != loadRequestId.get()) return@launch
                val session = TableSession(tableModel = model, graph = newGraph, fingerprint = fingerprint)
                sessionCache[cacheKey] = session
                setGraphModelAndBump(newGraph)
                selectedNodeIds = emptySet()
                errorMsg = null
            } catch (e: Exception) {
                if (requestId != loadRequestId.get()) return@launch
                errorMsg = e.message
            } finally {
                if (requestId == loadRequestId.get()) {
                    isLoadingTable = false
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Aggregation
    // ═══════════════════════════════════════════════════════════════

    /**
     * Opens one group node, revealing the next page of the siblings it stands for.
     *
     * Expansion changes which nodes exist, so it rebuilds the graph rather than toggling
     * visibility — see [rebuildDrawnGraph] for what that costs.
     */
    fun expandGroup(groupId: String) {
        if (groupId in expandedGroupIds) return
        expandedGroupIds = expandedGroupIds + groupId
        rebuildDrawnGraph()
    }

    /**
     * Opens every remaining page of one group at once.
     *
     * A parent with 5,000 manifests is 208 double-clicks from being drawn whole, which is not a
     * thing anyone does — so "slow but complete" has to be reachable in one action. The cost is
     * the reader's to accept: the button says how many nodes it is about to draw.
     */
    fun expandGroupFully(group: GraphNode.GroupNode) {
        val pages = GraphAggregation.pageIdsToRevealAll(group, aggregationPolicy)
        if (expandedGroupIds.containsAll(pages)) return
        expandedGroupIds = expandedGroupIds + pages
        rebuildDrawnGraph()
    }

    /** Closes every group, back to one page per parent. */
    fun collapseAllGroups() {
        if (expandedGroupIds.isEmpty()) return
        expandedGroupIds = emptySet()
        rebuildDrawnGraph()
    }

    /**
     * Changes how many siblings a page holds, and redraws under the new size.
     *
     * Two things have to go with it. A group id names *a page at a size*, so an expansion
     * recorded under the old one would name a different set of siblings under the new one —
     * expansion is cleared rather than reinterpreted. And every other cached graph was drawn
     * under the old size, so those sessions are dropped; restoring one would put a graph on
     * screen that disagrees with the number in the badge above it. The table on screen keeps its
     * session, because that one is rebuilt right here from the model already in memory.
     */
    fun updateGraphPageSize(pageSize: Int) {
        val clamped = pageSize.coerceIn(MIN_GRAPH_PAGE_SIZE, MAX_GRAPH_PAGE_SIZE)
        if (clamped == graphPageSize) return
        graphPageSize = clamped
        prefs.putInt(PREF_GRAPH_PAGE_SIZE, clamped)
        expandedGroupIds = emptySet()

        val currentKey = selectedTablePath?.let { "$it-rows_$showRows" }
        synchronized(sessionCache) {
            sessionCache.keys.filter { it != currentKey }.forEach { sessionCache.remove(it) }
        }
        rebuildDrawnGraph()
    }

    /**
     * Rebuilds the graph from the table model already in the session — never from disk.
     *
     * Every caller here changes how much of the table is drawn, not what the table says. Reading
     * a production table's metadata is the expensive half; deciding how much of it to draw is
     * not.
     */
    private fun rebuildDrawnGraph() {
        val tablePath = selectedTablePath ?: return
        val cacheKey = "$tablePath-rows_$showRows"
        val session = sessionCache[cacheKey] ?: return
        val model = session.tableModel ?: return

        isLoadingTable = true
        val requestId = loadRequestId.incrementAndGet()
        val expanded = expandedGroupIds

        coroutineScope.launch {
            try {
                val rebuilt = withContext(backgroundDispatcher) {
                    coroutineContext.ensureActive()
                    GraphLayoutService.layoutGraph(model, showRows, expanded, aggregationPolicy)
                }
                if (requestId != loadRequestId.get()) return@launch

                // A drag survives for a node that is still on screen; the nodes that just
                // appeared go where layout put them. Reading the drag state is main-thread work,
                // which is where this runs.
                val merged = rebuilt.layoutPositions.toMutableMap()
                nodePositions?.draggedSnapshot()?.forEach { (id, xy) ->
                    if (merged.containsKey(id)) merged[id] = Point(xy.first.toFloat(), xy.second.toFloat())
                }
                val graph = rebuilt.copy(layoutPositions = merged)
                val survivingSelection = selectedNodeIds.filterTo(mutableSetOf()) { graph.nodeById.containsKey(it) }

                sessionCache[cacheKey] = session.copy(
                    graph = graph,
                    selectedNodeIds = survivingSelection,
                    expandedGroupIds = expanded,
                )
                setGraphModelAndBump(graph)
                selectedNodeIds = survivingSelection
                errorMsg = null
            } catch (e: Exception) {
                if (requestId != loadRequestId.get()) return@launch
                logger.error("Failed to rebuild the graph after a change to what it draws", e)
                errorMsg = e.message
            } finally {
                if (requestId == loadRequestId.get()) {
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
