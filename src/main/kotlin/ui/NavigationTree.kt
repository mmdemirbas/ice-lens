package ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import model.GraphModel
import model.GraphNode

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TreeIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tooltip: String,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    TooltipArea(
        tooltip = {
            Box(
                modifier = Modifier
                    .background(colors.inverseSurface.copy(alpha = 0.92f), RoundedCornerShape(4.dp))
                    .border(1.dp, colors.outlineVariant, RoundedCornerShape(4.dp))
                    .padding(8.dp)
            ) {
                Text(text = tooltip, color = colors.inverseOnSurface, fontSize = 12.sp)
            }
        },
        delayMillis = 500,
        tooltipPlacement = TooltipPlacement.CursorPoint(
            alignment = Alignment.BottomEnd,
            offset = DpOffset(0.dp, 16.dp)
        )
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
            Icon(icon, contentDescription = tooltip, modifier = Modifier.size(18.dp), tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
fun NavigationTree(
    graph: GraphModel,
    selectedNodeIds: Set<String>,
    onNodeSelect: (GraphNode) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val selectionColor = selectionHighlightColor()
    val selectedBgColor = selectionColor.copy(alpha = if (isDarkSurface(colors.surface)) 0.4f else 0.2f)
    val expandedNodeIdsByState = remember { mutableStateOf(setOf<String>()) }
    var expandedNodeIds by expandedNodeIdsByState
    var searchQuery by remember { mutableStateOf("") }

    val flattenedTree = remember(graph, expandedNodeIds, searchQuery) {
        flattenGraph(graph, expandedNodeIds, searchQuery)
    }

    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    val density = androidx.compose.ui.platform.LocalDensity.current

    LaunchedEffect(selectedNodeIds) {
        if (selectedNodeIds.size == 1) {
            val selectedId = selectedNodeIds.first()
            val path = findPathToNode(graph, selectedId)
            expandedNodeIds = expandedNodeIds + path

            val index = flattenedTree.indexOfFirst { it.first.id == selectedId }
            if (index >= 0) {
                val itemHeightPx = with(density) { 32.dp.toPx() }
                val targetScroll = (index * itemHeightPx).toInt()
                
                val viewportHeightPx = verticalScrollState.viewportSize
                if (targetScroll < verticalScrollState.value || targetScroll > (verticalScrollState.value + viewportHeightPx - itemHeightPx)) {
                    verticalScrollState.animateScrollTo(targetScroll)
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactSearchField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            TreeIconButton(
                icon = Icons.Default.UnfoldMore,
                tooltip = "Expand All",
                onClick = { expandedNodeIds = graph.nodes.map { it.id }.toSet() }
            )
            TreeIconButton(
                icon = Icons.Default.UnfoldLess,
                tooltip = "Collapse All",
                onClick = { expandedNodeIds = emptySet() }
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(modifier = Modifier.fillMaxSize().horizontalScroll(horizontalScrollState)) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .verticalScroll(verticalScrollState)
                        .width(IntrinsicSize.Max)
                        .defaultMinSize(minWidth = 300.dp)
                ) {
                    flattenedTree.forEach { triple ->
                        val node = triple.first
                        val depth = triple.second
                        val hasChildren = triple.third
                        
                        val isSelected = selectedNodeIds.contains(node.id)
                        val isExpanded = expandedNodeIds.contains(node.id)
                        val bgColor = if (isSelected) selectedBgColor else Color.Transparent
                        val textColor = if (isSelected) selectionColor else colors.onSurfaceVariant

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                                .background(bgColor)
                                .clickable { onNodeSelect(node) }
                                .padding(horizontal = 8.dp)
                                .padding(start = (depth * 16).dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(16.dp).clickable {
                                    if (hasChildren) {
                                        expandedNodeIds = if (isExpanded) {
                                            expandedNodeIds - node.id
                                        } else {
                                            expandedNodeIds + node.id
                                        }
                                    }
                                }) {
                                if (hasChildren) {
                                    Icon(
                                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = "Toggle Expand",
                                        tint = colors.onSurfaceVariant,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }

                            Spacer(Modifier.width(4.dp))

                            Box(
                                modifier = Modifier.size(10.dp).background(
                                    getGraphNodeColor(node), androidx.compose.foundation.shape.CircleShape
                                ).border(
                                    if (isSelected) 2.dp else 1.dp,
                                    if (isSelected) selectionColor else getGraphNodeBorderColor(node),
                                    androidx.compose.foundation.shape.CircleShape
                                )
                            )
                            Spacer(Modifier.width(8.dp))

                            Text(
                                text = getNodeLabel(node),
                                fontSize = 11.sp,
                                color = textColor,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                softWrap = false
                            )
                        }
                    }
                }
            }
            VerticalScrollbar(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                adapter = rememberScrollbarAdapter(verticalScrollState)
            )
            HorizontalScrollbar(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                adapter = rememberScrollbarAdapter(horizontalScrollState)
            )
        }
    }
}

private fun flattenGraph(
    graph: GraphModel,
    expandedIds: Set<String>,
    searchQuery: String = ""
): List<Triple<GraphNode, Int, Boolean>> {
    val result = mutableListOf<Triple<GraphNode, Int, Boolean>>()
    val edgesBySource = graph.edges.groupBy { it.fromId }
    val visited = mutableSetOf<String>()

    val matchingNodeIds = if (searchQuery.isBlank()) emptySet()
    else graph.nodes.filter { getNodeLabel(it).contains(searchQuery, ignoreCase = true) }.map { it.id }.toSet()

    val visibleIds = if (searchQuery.isBlank()) null
    else {
        val visible = matchingNodeIds.toMutableSet()
        val edgesByTarget = graph.edges.groupBy { it.toId }
        fun markAncestors(nodeId: String) {
            edgesByTarget[nodeId]?.forEach { edge ->
                if (visible.add(edge.fromId)) {
                    markAncestors(edge.fromId)
                }
            }
        }
        matchingNodeIds.forEach { markAncestors(it) }
        visible
    }

    fun traverse(nodeId: String, depth: Int) {
        if (visited.contains(nodeId)) return
        if (visibleIds != null && !visibleIds.contains(nodeId)) return

        visited.add(nodeId)

        val node = graph.nodes.find { it.id == nodeId } ?: return
        val children = edgesBySource[nodeId]?.map { it.toId } ?: emptyList()
        val filteredChildren = if (visibleIds == null) children else children.filter { visibleIds.contains(it) }

        result.add(Triple(node, depth, filteredChildren.isNotEmpty()))

        val shouldExpand = expandedIds.contains(nodeId) || (searchQuery.isNotBlank() && visibleIds?.contains(nodeId) == true)
        if (shouldExpand) {
            filteredChildren.forEach { traverse(it, depth + 1) }
        }

        visited.remove(nodeId)
    }

    val childIds = graph.edges.map { it.toId }.toSet()
    val roots = graph.nodes.filter { it.id !in childIds }

    roots.forEach { traverse(it.id, 0) }
    return result
}

private fun findPathToNode(graph: GraphModel, targetId: String): List<String> {
    val edgesByTarget = graph.edges.groupBy { it.toId }
    val path = mutableListOf<String>()
    var currentId = targetId

    while (true) {
        val parentEdge = edgesByTarget[currentId]?.firstOrNull() ?: break
        currentId = parentEdge.fromId
        path.add(currentId)
    }
    return path
}

private fun getNodeLabel(node: GraphNode): String {
    return when (node) {
        is GraphNode.TableNode -> "Table: ${node.summary.tableName}"
        is GraphNode.MetadataNode -> "Meta ${node.simpleId}: ${node.fileName}"
        is GraphNode.SnapshotNode -> "Snap: ${node.data.snapshotId}"
        is GraphNode.ManifestNode -> "Manifest (${node.data.addedFilesCount} adds)"
        is GraphNode.FileNode     -> "File ${node.simpleId}: ${
            node.data.filePath?.substringAfterLast("/")
        }"

        is GraphNode.RowNode      -> "Row: ${node.data.values.firstOrNull() ?: "..."}"
        is GraphNode.ErrorNode    -> "Error: ${node.title}"
    }
}
