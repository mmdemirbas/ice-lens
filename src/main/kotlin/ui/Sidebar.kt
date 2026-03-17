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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import model.WorkspaceItem
import model.WorkspaceTableStatus
import java.io.File
import javax.swing.JFileChooser


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
                Text(placeholder, fontSize = 11.sp, color = colors.onSurfaceVariant)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, color = colors.onSurface),
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
                val chooser = JFileChooser()
                chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                chooser.dialogTitle = "Add Warehouse or Table"
                chooser.currentDirectory = File(System.getProperty("user.home"))

                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    onAddRoot(chooser.selectedFile.absolutePath)
                }
            }, modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add to Workspace", fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "WORKSPACE",
            fontSize = 10.sp,
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

        val listState = rememberLazyListState()
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                            status = if (item is WorkspaceItem.SingleTable) {
                                singleTableStatuses[item.path] ?: WorkspaceTableStatus.EXISTING
                            } else null,
                            onToggleExpand = {
                                onExpandedPathsChange(if (expandedPaths.contains(item.path)) {
                                    expandedPaths - item.path
                                } else {
                                    expandedPaths + item.path
                                })
                            },
                            onSelect = {
                                if (item is WorkspaceItem.SingleTable) {
                                    onTableSelect(item.path)
                                }
                            },
                            onRemove = { pendingRemoveItem = item },
                            modifier = Modifier
                                .graphicsLayer {
                                    if (isDragging) {
                                        translationY = dragOffset
                                        alpha = 0.8f
                                    }
                                }
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
                        val allTables = if (tableStatuses.isNotEmpty()) tableStatuses.keys.toList().sorted() else item.tables
                        val filteredTables = if (searchQuery.isBlank()) {
                            allTables
                        } else {
                            allTables.filter { it.contains(searchQuery, ignoreCase = true) || item.name.contains(searchQuery, ignoreCase = true) }
                        }

                        items(filteredTables) { tableName ->
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

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(bgColor)
                                    .clickable { onTableSelect(tablePath) }
                                    .padding(vertical = 4.dp, horizontal = 24.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.TableChart, null, modifier = Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
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
                    contentDescription = null,
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

        Text(
            text = "$prefix${item.name}$suffix",
            fontSize = 13.sp,
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
