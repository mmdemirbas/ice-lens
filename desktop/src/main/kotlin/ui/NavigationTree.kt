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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import model.displayLabel
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
                Text(text = tooltip, color = colors.inverseOnSurface, fontSize = TypeScale.small)
            }
        },
        delayMillis = TOOLTIP_DELAY_MS,
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

    // The list is one focus target, not one per row. A tree with a thousand lines would otherwise
    // put a thousand stops in the Tab order, and the cursor here is the selection anyway.
    val listFocus = remember { FocusRequester() }
    var listFocused by remember { mutableStateOf(false) }

    fun handleArrow(event: KeyEvent): Boolean {
        val key = navKey(event) ?: return false
        val action = treeKeyAction(
            rows = flattenedTree,
            expandedIds = expandedNodeIds,
            selectedId = selectedNodeIds.singleOrNull(),
            key = key,
        )
        when (action) {
            is TreeKeyAction.Select -> graph.nodeById[action.nodeId]?.let(onNodeSelect)
            is TreeKeyAction.Expand -> expandedNodeIds = expandedNodeIds + action.nodeId
            is TreeKeyAction.Collapse -> expandedNodeIds = expandedNodeIds - action.nodeId
            null -> Unit
        }
        // Consumed either way: an arrow falling through scrolls the pane behind the tree, which
        // reads as the selection having moved somewhere off screen.
        return true
    }

    LaunchedEffect(selectedNodeIds) {
        if (selectedNodeIds.size == 1) {
            val selectedId = selectedNodeIds.first()
            val path = findPathToNode(graph, selectedId)
            expandedNodeIds = expandedNodeIds + path

            val index = flattenedTree.indexOfFirst { it.node.id == selectedId }
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

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .focusRequester(listFocus)
                .onFocusChanged { listFocused = it.isFocused }
                .focusable()
                // Bubbling, not preview: the search field above consumes its own arrows, and a
                // preview handler here would take them before the reader could move the caret.
                .onKeyEvent { handleArrow(it) }
                .border(
                    width = 1.dp,
                    color = if (listFocused) colors.primary else Color.Transparent,
                )
        ) {
            Box(modifier = Modifier.fillMaxSize().horizontalScroll(horizontalScrollState)) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .verticalScroll(verticalScrollState)
                        .width(IntrinsicSize.Max)
                        .defaultMinSize(minWidth = 300.dp)
                ) {
                    flattenedTree.forEach { (node, depth, hasChildren) ->
                        
                        val isSelected = selectedNodeIds.contains(node.id)
                        val isExpanded = expandedNodeIds.contains(node.id)
                        val bgColor = if (isSelected) selectedBgColor else Color.Transparent
                        val textColor = if (isSelected) selectionColor else colors.onSurfaceVariant

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                                .background(bgColor)
                                .clickable {
                                    // Clicking a row hands the list the keyboard too, so the
                                    // arrows carry on from where the reader just pointed.
                                    listFocus.requestFocus()
                                    onNodeSelect(node)
                                }
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
                                    getGraphNodeColor(node, isDarkSurface(colors.surface)), androidx.compose.foundation.shape.CircleShape
                                ).border(
                                    if (isSelected) 2.dp else 1.dp,
                                    if (isSelected) selectionColor else getGraphNodeBorderColor(node, isDarkSurface(colors.surface)),
                                    androidx.compose.foundation.shape.CircleShape
                                )
                            )
                            Spacer(Modifier.width(8.dp))

                            Text(
                                text = getNodeLabel(node),
                                fontSize = TypeScale.small,
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

/** What an arrow key asks the tree to do. */
internal sealed interface TreeKeyAction {
    data class Select(val nodeId: String) : TreeKeyAction
    data class Expand(val nodeId: String) : TreeKeyAction
    data class Collapse(val nodeId: String) : TreeKeyAction
}

/**
 * The arrow-key rules for a tree, decided entirely from the lines currently visible.
 *
 * This is the keymap every file browser and IDE tree uses, and readers arrive already knowing it:
 * up and down move a line, right opens a closed line and otherwise steps into it, left closes an
 * open line and otherwise steps out to its parent. Stating it against the **flattened** list is
 * what makes it short — the first child of an open line is the next line, and the parent is the
 * nearest line above with a smaller depth, so no second traversal of the graph is needed and the
 * keyboard can never disagree with what is drawn.
 *
 * It is deliberately not [model.stepFrom]. The canvas is a picture and its rules are geometric;
 * a tree is a list, and up on a list means the line above whatever its depth is. One shared
 * keymap across the two would be wrong in both.
 *
 * The selection is the cursor. There is no separate focused row to keep in step with it, which
 * also means the existing scroll-into-view effect already brings a keyboard move on screen.
 */
internal fun treeKeyAction(
    rows: List<TreeRow>,
    expandedIds: Set<String>,
    selectedId: String?,
    key: ListKey,
): TreeKeyAction? {
    if (rows.isEmpty()) return null
    val index = rows.indexOfFirst { it.node.id == selectedId }
    // Nothing selected, or a selection the search box has filtered out of view: any arrow starts
    // at the top rather than doing nothing, which is what a reader pressing a key expects.
    if (index < 0) return TreeKeyAction.Select(rows.first().node.id)

    val row = rows[index]
    val isOpen = row.node.id in expandedIds
    return when (key) {
        ListKey.UP -> rows.getOrNull(index - 1)?.let { TreeKeyAction.Select(it.node.id) }
        ListKey.DOWN -> rows.getOrNull(index + 1)?.let { TreeKeyAction.Select(it.node.id) }
        ListKey.RIGHT -> when {
            !row.hasChildren -> null
            !isOpen -> TreeKeyAction.Expand(row.node.id)
            // Open already, so the first child is the line below — by construction, since that is
            // the order `flattenGraph` emits.
            else -> rows.getOrNull(index + 1)?.let { TreeKeyAction.Select(it.node.id) }
        }
        ListKey.LEFT -> when {
            row.hasChildren && isOpen -> TreeKeyAction.Collapse(row.node.id)
            else -> rows.take(index).lastOrNull { it.depth < row.depth }
                ?.let { TreeKeyAction.Select(it.node.id) }
        }
        // The tree selects as it moves, so there is nothing left for Enter to do.
        ListKey.ACTIVATE -> null
    }
}

/**
 * One visible line of the tree: which node, how deep, and whether it can open.
 *
 * A named record rather than a `Triple` because the keyboard rules below are written against
 * `depth` and `hasChildren` — "the parent is the nearest line above me with a smaller depth" is a
 * sentence, and `triple.second < triple.second` is not.
 */
internal data class TreeRow(val node: GraphNode, val depth: Int, val hasChildren: Boolean)

private fun flattenGraph(
    graph: GraphModel,
    expandedIds: Set<String>,
    searchQuery: String = ""
): List<TreeRow> {
    val result = mutableListOf<TreeRow>()
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

        val node = graph.nodeById[nodeId] ?: return
        val children = edgesBySource[nodeId]?.map { it.toId } ?: emptyList()
        val filteredChildren = if (visibleIds == null) children else children.filter { visibleIds.contains(it) }

        result.add(TreeRow(node, depth, filteredChildren.isNotEmpty()))

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

private fun getNodeLabel(node: GraphNode): String = node.displayLabel()
