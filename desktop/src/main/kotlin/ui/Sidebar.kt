package ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import model.WorkspaceItem
import model.WorkspaceTableStatus
import java.io.File


@Composable
fun CompactSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search..."
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .height(32.dp)
            .background(colors.surface, RoundedCornerShape(6.dp))
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, null, modifier = Modifier.size(14.dp), tint = colors.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(placeholder, fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = TypeScale.small, color = colors.onSurface),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (value.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.Clear,
                null,
                modifier = Modifier.size(14.dp).clickable { onValueChange("") },
                tint = colors.onSurfaceVariant
            )
        }
    }
}

/**
 * One visible line of the workspace list, in the order it is drawn.
 *
 * Two fields, because the keyboard rules need both and neither implies the other. [opensATable]
 * separates a warehouse, which expands, from a table, which loads. [depth] separates a root from a
 * table drawn under one — and a **single table added as a root is both**: it opens a table and it
 * has nothing above it. Deriving the parent from `opensATable` alone walked such a row out to the
 * previous root's warehouse, which is not its warehouse and does not contain it.
 */
internal data class WorkspaceRow(val path: String, val opensATable: Boolean, val depth: Int)

/**
 * The lines the workspace is currently drawing: every root, and the tables under an open one.
 *
 * Hoisted out of the `LazyColumn` so the keyboard and the drawing read the same list. Computing
 * it twice is how a cursor ends up on a line nobody can see.
 */
internal fun workspaceRows(
    items: List<WorkspaceItem>,
    expandedPaths: Set<String>,
    searchQuery: String,
    tablesOf: (WorkspaceItem.Warehouse) -> List<String>,
): List<WorkspaceRow> = items.flatMap { item ->
    val root = WorkspaceRow(item.path, opensATable = item is WorkspaceItem.SingleTable, depth = 0)
    // A search opens every warehouse it matched, so the rows follow suit — the reader can see the
    // tables, so the keyboard has to be able to reach them.
    val open = item.path in expandedPaths || searchQuery.isNotBlank()
    if (item !is WorkspaceItem.Warehouse || !open) {
        listOf(root)
    } else {
        listOf(root) + tablesOf(item).map {
            WorkspaceRow("${item.path}/$it", opensATable = true, depth = 1)
        }
    }
}

/** What a key asks the workspace list to do. */
internal sealed interface WorkspaceKeyAction {
    /** Move the cursor. Deliberately not "select": see [workspaceKeyAction]. */
    data class Focus(val path: String) : WorkspaceKeyAction
    data class Expand(val path: String) : WorkspaceKeyAction
    data class Collapse(val path: String) : WorkspaceKeyAction
    data class Open(val path: String) : WorkspaceKeyAction

    /** Ask to remove the root under the cursor. Ask — the dialog the `×` opens is the confirmation. */
    data class Remove(val path: String) : WorkspaceKeyAction

    /** Move the root under the cursor by one place, in the direction of [delta]. */
    data class Move(val path: String, val delta: Int) : WorkspaceKeyAction
}

/** A keystroke that edits the workspace list rather than moves through it. */
internal enum class WorkspaceEditKey { REMOVE, MOVE_UP, MOVE_DOWN }

/**
 * The editing key an event carries, or null when it is not one.
 *
 * Delete and Backspace bare. The move is a chord, `Alt + Up / Down`, because the bare arrows are
 * the cursor — and Alt is the one modifier a list may take: [navKey] leaves it alone so Alt+Arrow
 * still moves by word in a text field, and there is no text field inside the list.
 */
internal fun workspaceEditKey(event: KeyEvent): WorkspaceEditKey? {
    if (event.type != KeyEventType.KeyDown) return null
    val chorded = event.isMetaPressed || event.isCtrlPressed || event.isShiftPressed
    if (chorded) return null
    return when {
        !event.isAltPressed && (event.key == Key.Delete || event.key == Key.Backspace) -> WorkspaceEditKey.REMOVE
        event.isAltPressed && event.key == Key.DirectionUp -> WorkspaceEditKey.MOVE_UP
        event.isAltPressed && event.key == Key.DirectionDown -> WorkspaceEditKey.MOVE_DOWN
        else -> null
    }
}

/**
 * What an editing key does to the row under the cursor.
 *
 * Only a root is a workspace item. A table inside a warehouse is the warehouse's, and removing or
 * moving the warehouse because the cursor was on one of its tables would act on something the
 * reader did not point at — so a nested row answers nothing. A move under a search answers nothing
 * too: the neighbour the reader sees is not the neighbour the list has, and moving "one down" past
 * a root the filter hid would land somewhere the reader cannot see.
 */
internal fun workspaceEditAction(
    rows: List<WorkspaceRow>,
    searchQuery: String,
    focusedPath: String?,
    key: WorkspaceEditKey,
): WorkspaceKeyAction? {
    val row = rows.firstOrNull { it.path == focusedPath } ?: return null
    if (row.depth != 0) return null
    return when (key) {
        WorkspaceEditKey.REMOVE -> WorkspaceKeyAction.Remove(row.path)
        WorkspaceEditKey.MOVE_UP -> if (searchQuery.isBlank()) WorkspaceKeyAction.Move(row.path, -1) else null
        WorkspaceEditKey.MOVE_DOWN -> if (searchQuery.isBlank()) WorkspaceKeyAction.Move(row.path, +1) else null
    }
}

/**
 * The workspace's keyboard rules, over the lines it is currently drawing.
 *
 * The same file-browser keymap as the structure tree, with one difference that matters: **moving
 * the cursor does not open a table.** In the tree and on the canvas the selection is the cursor,
 * because selecting is free. Here it is not — opening a table reads its whole metadata tree off
 * disk — so holding Down through a warehouse of forty tables would load forty of them. The cursor
 * moves, and Enter opens what it is on.
 *
 * That is also why a warehouse row can hold the cursor at all: it is not selectable by clicking,
 * but a keyboard has to be able to stand on it to press Right.
 */
internal fun workspaceKeyAction(
    rows: List<WorkspaceRow>,
    expandedPaths: Set<String>,
    searchQuery: String,
    focusedPath: String?,
    key: ListKey,
): WorkspaceKeyAction? {
    if (rows.isEmpty()) return null
    val index = rows.indexOfFirst { it.path == focusedPath }
    if (index < 0) return WorkspaceKeyAction.Focus(rows.first().path)

    val row = rows[index]
    // A search forces every warehouse open, and a line the reader can see open cannot be reported
    // as closed — Right would claim to open something already showing its tables.
    val isOpen = row.path in expandedPaths || searchQuery.isNotBlank()
    val isWarehouseRow = !row.opensATable
    return when (key) {
        ListKey.UP -> rows.getOrNull(index - 1)?.let { WorkspaceKeyAction.Focus(it.path) }
        ListKey.DOWN -> rows.getOrNull(index + 1)?.let { WorkspaceKeyAction.Focus(it.path) }
        ListKey.RIGHT -> when {
            !isWarehouseRow -> null
            !isOpen -> WorkspaceKeyAction.Expand(row.path)
            else -> rows.getOrNull(index + 1)?.let { WorkspaceKeyAction.Focus(it.path) }
        }
        ListKey.LEFT -> when {
            isWarehouseRow && isOpen && row.path in expandedPaths -> WorkspaceKeyAction.Collapse(row.path)
            // Same rule as the tree: the parent is the nearest line above with a smaller depth.
            // A root has none, which is the answer for a single table added at the top level.
            else -> rows.take(index).lastOrNull { it.depth < row.depth }
                ?.let { WorkspaceKeyAction.Focus(it.path) }
        }
        ListKey.ACTIVATE -> when {
            row.opensATable -> WorkspaceKeyAction.Open(row.path)
            isOpen -> WorkspaceKeyAction.Collapse(row.path)
            else -> WorkspaceKeyAction.Expand(row.path)
        }
    }
}

@Composable
fun WorkspacePanel(
    workspaceItems: List<WorkspaceItem>,
    warehouseTableStatuses: Map<String, Map<String, WorkspaceTableStatus>>,
    singleTableStatuses: Map<String, WorkspaceTableStatus>,
    selectedTablePath: String?,
    expandedPaths: Set<String>,
    onExpandedPathsChange: (Set<String>) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    lastBrowseDirectory: String?,
    onLastBrowseDirectoryChange: (String) -> Unit,
    onTableSelect: (String) -> Unit,
    onAddRoot: (String) -> Unit,
    onRemoveRoot: (WorkspaceItem) -> Unit,
    onMoveRoot: (WorkspaceItem, Int) -> Unit,
) {
    var draggingItemPath by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    var dragAccumulatedOffset by remember { mutableStateOf(0f) }
    var pendingRemoveItem by remember { mutableStateOf<WorkspaceItem?>(null) }

    val currentWorkspaceItems by rememberUpdatedState(workspaceItems)
    val currentOnMoveRoot by rememberUpdatedState(onMoveRoot)

    val colors = MaterialTheme.colorScheme
    val selectionColor = selectionHighlightColor()
    val selectedBgColor = selectionColor.copy(alpha = if (isDarkSurface(colors.surface)) 0.4f else 0.2f)
    Column(modifier = Modifier.fillMaxSize().background(colors.surfaceVariant).padding(8.dp)) {
        Button(
            onClick = {
                val initialDir = lastBrowseDirectory?.let { File(it) }
                val selected = chooseDirectory(initialDir)
                if (selected != null) {
                    onLastBrowseDirectoryChange(selected.parent ?: selected.absolutePath)
                    onAddRoot(selected.absolutePath)
                }
            }, modifier = Modifier.fillMaxWidth()
        ) {
            // No description: the button's own text is right beside it, and a described icon
            // makes a screen reader say "Add, Add to Workspace".
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add to Workspace", fontSize = TypeScale.small)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "WORKSPACE",
            fontSize = TypeScale.micro,
            fontWeight = FontWeight.Bold,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactSearchField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            TreeIconButton(
                icon = Icons.Default.UnfoldMore,
                tooltip = "Expand All",
                onClick = {
                    val allWarehousePaths = workspaceItems
                        .filterIsInstance<WorkspaceItem.Warehouse>()
                        .map { it.path }
                        .toSet()
                    onExpandedPathsChange(allWarehousePaths)
                }
            )
            TreeIconButton(
                icon = Icons.Default.UnfoldLess,
                tooltip = "Collapse All",
                onClick = { onExpandedPathsChange(emptySet()) }
            )
        }

        val filteredWorkspaceItems = remember(workspaceItems, searchQuery) {
            if (searchQuery.isBlank()) workspaceItems
            else {
                workspaceItems.filter { item ->
                    when (item) {
                        is WorkspaceItem.SingleTable -> item.name.contains(searchQuery, ignoreCase = true)
                        is WorkspaceItem.Warehouse -> {
                            item.name.contains(searchQuery, ignoreCase = true) ||
                                    item.tables.any { it.contains(searchQuery, ignoreCase = true) }
                        }
                    }
                }
            }
        }

        // One definition, read by the drawing below and by the keyboard rows beside it. Two would
        // eventually disagree, and a cursor on a line nobody can see is the result.
        fun tablesOf(warehouse: WorkspaceItem.Warehouse): List<String> {
            val statuses = warehouseTableStatuses[warehouse.path].orEmpty()
            val all = if (statuses.isNotEmpty()) statuses.keys.toList().sorted() else warehouse.tables
            return if (searchQuery.isBlank()) {
                all
            } else {
                all.filter {
                    it.contains(searchQuery, ignoreCase = true) ||
                        warehouse.name.contains(searchQuery, ignoreCase = true)
                }
            }
        }

        val rows = workspaceRows(filteredWorkspaceItems, expandedPaths, searchQuery, ::tablesOf)
        var focusedPath by remember { mutableStateOf<String?>(null) }
        val listFocus = remember { FocusRequester() }
        var listFocused by remember { mutableStateOf(false) }

        val listState = rememberLazyListState()

        // The cursor and the LazyColumn share an index: the rows are emitted root-then-its-tables
        // in exactly the order `workspaceRows` lists them.
        LaunchedEffect(focusedPath, rows) {
            val index = rows.indexOfFirst { it.path == focusedPath }
            if (index >= 0) listState.animateScrollToItem(index)
        }

        fun handleKey(event: KeyEvent): Boolean {
            workspaceEditKey(event)?.let { edit ->
                val root = { path: String -> currentWorkspaceItems.firstOrNull { it.path == path } }
                when (val action = workspaceEditAction(rows, searchQuery, focusedPath, edit)) {
                    is WorkspaceKeyAction.Remove -> pendingRemoveItem = root(action.path)
                    is WorkspaceKeyAction.Move -> root(action.path)?.let { currentOnMoveRoot(it, action.delta) }
                    else -> Unit
                }
                return true
            }
            val key = navKey(event) ?: return false
            when (val action = workspaceKeyAction(rows, expandedPaths, searchQuery, focusedPath, key)) {
                is WorkspaceKeyAction.Focus -> focusedPath = action.path
                is WorkspaceKeyAction.Expand -> onExpandedPathsChange(expandedPaths + action.path)
                is WorkspaceKeyAction.Collapse -> onExpandedPathsChange(expandedPaths - action.path)
                is WorkspaceKeyAction.Open -> {
                    focusedPath = action.path
                    onTableSelect(action.path)
                }
                // The editing actions come from the other parser above; the cursor's keys never
                // produce them.
                is WorkspaceKeyAction.Remove, is WorkspaceKeyAction.Move, null -> Unit
            }
            return true
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .focusRequester(listFocus)
                .onFocusChanged { listFocused = it.isFocused }
                .focusable()
                // Bubbling: the search field above owns its own arrows.
                .onKeyEvent { handleKey(it) }
                .border(width = 1.dp, color = if (listFocused) colors.primary else Color.Transparent)
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                filteredWorkspaceItems.forEachIndexed { index, item ->
                    item(key = item.path) {
                        val isSelected = item is WorkspaceItem.SingleTable && item.path == selectedTablePath
                        val isDragging = draggingItemPath == item.path

                        val effectivelyExpanded = expandedPaths.contains(item.path) || searchQuery.isNotBlank()

                        WorkspaceRootItem(
                            item = item,
                            isSelected = isSelected,
                            isExpanded = effectivelyExpanded,
                            status = when {
                                item is WorkspaceItem.SingleTable ->
                                    singleTableStatuses[item.path] ?: WorkspaceTableStatus.EXISTING
                                item is WorkspaceItem.Warehouse && !File(item.path).exists() ->
                                    WorkspaceTableStatus.DELETED
                                else -> null
                            },
                            onToggleExpand = {
                                onExpandedPathsChange(if (expandedPaths.contains(item.path)) {
                                    expandedPaths - item.path
                                } else {
                                    expandedPaths + item.path
                                })
                            },
                            onSelect = {
                                // Pointing at a row hands the list the keyboard too, so the
                                // arrows carry on from where the reader just clicked.
                                listFocus.requestFocus()
                                focusedPath = item.path
                                if (item is WorkspaceItem.SingleTable) {
                                    onTableSelect(item.path)
                                }
                            },
                            onRemove = { pendingRemoveItem = item },
                            modifier = Modifier
                                // The cursor is drawn only while the list holds the keyboard.
                                // A ring left behind on a list nobody is typing into reads as a
                                // second selection.
                                .border(
                                    width = 1.dp,
                                    color = if (listFocused && item.path == focusedPath) {
                                        colors.primary
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .graphicsLayer {
                                    if (isDragging) {
                                        translationY = dragOffset
                                        alpha = 0.8f
                                    }
                                }
                                .pointerHoverIcon(PointerIcon(java.awt.Cursor(java.awt.Cursor.MOVE_CURSOR)))
                                .pointerInput(item) {
                                    detectDragGestures(
                                        onDragStart = {
                                            draggingItemPath = item.path
                                            dragOffset = 0f
                                            dragAccumulatedOffset = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffset += dragAmount.y
                                            dragAccumulatedOffset += dragAmount.y
                                            val threshold = 32.dp.toPx()
                                            val currentIdx = currentWorkspaceItems.indexOf(item)
                                            if (dragAccumulatedOffset > threshold && currentIdx < currentWorkspaceItems.size - 1) {
                                                currentOnMoveRoot(item, 1)
                                                dragAccumulatedOffset -= threshold
                                                dragOffset -= threshold
                                            } else if (dragAccumulatedOffset < -threshold && currentIdx > 0) {
                                                currentOnMoveRoot(item, -1)
                                                dragAccumulatedOffset += threshold
                                                dragOffset += threshold
                                            }
                                        },
                                        onDragEnd = {
                                            draggingItemPath = null
                                            dragOffset = 0f
                                        },
                                        onDragCancel = {
                                            draggingItemPath = null
                                            dragOffset = 0f
                                        }
                                    )
                                }
                        )
                    }

                    if (item is WorkspaceItem.Warehouse && (expandedPaths.contains(item.path) || searchQuery.isNotBlank())) {
                        val tableStatuses = warehouseTableStatuses[item.path].orEmpty()
                        items(tablesOf(item)) { tableName ->
                            val tableStatus = tableStatuses[tableName] ?: WorkspaceTableStatus.EXISTING
                            val tablePath = "${item.path}/$tableName"
                            val isSelected = tablePath == selectedTablePath
                            val statusBgColor = when (tableStatus) {
                                WorkspaceTableStatus.NEW -> colors.secondaryContainer.copy(alpha = 0.42f)
                                WorkspaceTableStatus.DELETED -> colors.errorContainer.copy(alpha = 0.42f)
                                WorkspaceTableStatus.EXISTING -> Color.Transparent
                            }
                            val bgColor = if (isSelected) selectedBgColor else statusBgColor
                            val textColor = when {
                                isSelected -> selectionColor
                                tableStatus == WorkspaceTableStatus.NEW -> colors.secondary
                                tableStatus == WorkspaceTableStatus.DELETED -> colors.error
                                else -> colors.onSurface
                            }
                            val label = when (tableStatus) {
                                WorkspaceTableStatus.NEW -> "$tableName (new)"
                                WorkspaceTableStatus.DELETED -> "$tableName (deleted)"
                                WorkspaceTableStatus.EXISTING -> tableName
                            }

                            val formatBadge = remember(tablePath) { formatBadgeLabel(File(tablePath)) }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(bgColor)
                                    .border(
                                        width = 1.dp,
                                        color = if (listFocused && tablePath == focusedPath) {
                                            colors.primary
                                        } else {
                                            Color.Transparent
                                        },
                                    )
                                    .clickable {
                                        listFocus.requestFocus()
                                        focusedPath = tablePath
                                        onTableSelect(tablePath)
                                    }
                                    .padding(vertical = 4.dp, horizontal = 24.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.TableChart, null, modifier = Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                if (formatBadge != null) {
                                    Text(
                                        text = formatBadge,
                                        fontSize = TypeScale.micro,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.onTertiaryContainer,
                                        modifier = Modifier
                                            .background(colors.tertiaryContainer, RoundedCornerShape(3.dp))
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(
                                    text = label,
                                    fontSize = TypeScale.small,
                                    color = textColor,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
            VerticalScrollbar(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                adapter = rememberScrollbarAdapter(scrollState = listState)
            )
        }
    }

    val itemToRemove = pendingRemoveItem
    if (itemToRemove != null) {
        AlertDialog(
            onDismissRequest = { pendingRemoveItem = null },
            title = { Text("Remove from workspace?") },
            text = { Text("Are you sure you want to remove '${itemToRemove.name}' from the workspace?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveRoot(itemToRemove)
                        pendingRemoveItem = null
                    }
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoveItem = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun WorkspaceRootItem(
    item: WorkspaceItem,
    isSelected: Boolean,
    isExpanded: Boolean,
    status: WorkspaceTableStatus? = null,
    onToggleExpand: () -> Unit,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val selectionColor = selectionHighlightColor()
    val selectedBgColor = selectionColor.copy(alpha = if (isDarkSurface(colors.surface)) 0.4f else 0.2f)
    val formatBadge = remember(item.path) {
        if (item is WorkspaceItem.SingleTable) formatBadgeLabel(File(item.path)) else null
    }
    val prefix = when (item) {
        is WorkspaceItem.Warehouse   -> "warehouse: "
        is WorkspaceItem.SingleTable -> "table: "
    }
    val statusBgColor = when (status) {
        WorkspaceTableStatus.NEW -> colors.secondaryContainer.copy(alpha = 0.42f)
        WorkspaceTableStatus.DELETED -> colors.errorContainer.copy(alpha = 0.42f)
        else -> Color.Transparent
    }
    val bgColor = if (isSelected) selectedBgColor else statusBgColor
    val textColor = when {
        isSelected -> selectionColor
        status == WorkspaceTableStatus.NEW -> colors.secondary
        status == WorkspaceTableStatus.DELETED -> colors.error
        else -> colors.onSurface
    }
    val suffix = when (status) {
        WorkspaceTableStatus.NEW -> " (new)"
        WorkspaceTableStatus.DELETED -> " (deleted)"
        else -> ""
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable {
                if (item is WorkspaceItem.Warehouse) onToggleExpand() else onSelect()
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (item is WorkspaceItem.Warehouse) {
            IconButton(onClick = onToggleExpand, modifier = Modifier.size(24.dp)) {
                Icon(
                    if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier.size(16.dp)
                )
            }
        } else {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.TableChart,
                    null,
                    modifier = Modifier.size(14.dp),
                    tint = if (isSelected) selectionColor else colors.onSurfaceVariant
                )
            }
        }

        if (formatBadge != null) {
            Text(
                text = formatBadge,
                fontSize = TypeScale.micro,
                fontWeight = FontWeight.Bold,
                color = colors.onTertiaryContainer,
                modifier = Modifier
                    .background(colors.tertiaryContainer, RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = "$prefix${item.name}$suffix",
            fontSize = TypeScale.body,
            color = textColor,
            fontWeight = if (isSelected || item is WorkspaceItem.Warehouse) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )

        IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
            Icon(
                Icons.Default.Close,
                null,
                modifier = Modifier.size(14.dp),
                tint = colors.error.copy(alpha = 0.8f)
            )
        }
    }
}
