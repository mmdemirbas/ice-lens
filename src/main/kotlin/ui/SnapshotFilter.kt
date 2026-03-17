package ui

import model.GraphModel
import model.GraphNode

data class SnapshotFilterOption(
    val nodeId: String,
    val snapshotId: Long?,
    val sequenceNumber: Long?,
    val timestampMs: Long?,
)

fun snapshotFilterLabel(option: SnapshotFilterOption): String {
    val seq = option.sequenceNumber?.toString() ?: "N/A"
    val sid = option.snapshotId?.toString() ?: "N/A"
    return "Seq $seq | Snapshot $sid"
}

fun computeVisibleNodeIdsForSnapshotFilter(
    graph: GraphModel,
    selectedSnapshotNodeIds: Set<String>
): Set<String> {
    if (selectedSnapshotNodeIds.isEmpty()) return graph.nodes.map { it.id }.toSet()

    val validSnapshotIds = graph.nodes
        .filterIsInstance<GraphNode.SnapshotNode>()
        .map { it.id }
        .toHashSet()
    val selected = selectedSnapshotNodeIds.filterTo(mutableSetOf()) { it in validSnapshotIds }
    if (selected.isEmpty()) return graph.nodes.map { it.id }.toSet()

    val childrenByParent = graph.edges
        .groupBy { it.fromId }
        .mapValues { (_, edges) -> edges.map { it.toId } }
    val parentsByChild = graph.edges
        .groupBy { it.toId }
        .mapValues { (_, edges) -> edges.map { it.fromId } }

    val visible = mutableSetOf<String>()

    fun walk(start: String, next: (String) -> List<String>) {
        val queue = ArrayDeque<String>()
        val seen = mutableSetOf<String>()
        queue.add(start)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!seen.add(current)) continue
            visible += current
            next(current).forEach(queue::addLast)
        }
    }

    selected.forEach { snapshotId ->
        walk(snapshotId) { id -> parentsByChild[id].orEmpty() }
        walk(snapshotId) { id -> childrenByParent[id].orEmpty() }
    }

    return visible
}

fun filteredGraphModel(graph: GraphModel, visibleNodeIds: Set<String>): GraphModel {
    if (visibleNodeIds.isEmpty()) return GraphModel(emptyList(), emptyList(), 1.0, 1.0)
    if (visibleNodeIds.size == graph.nodes.size) return graph

    val nodes = graph.nodes.filter { it.id in visibleNodeIds }
    val edges = graph.edges.filter { it.fromId in visibleNodeIds && it.toId in visibleNodeIds }
    val width = nodes.maxOfOrNull { graph.getPosition(it.id).x.toDouble() + it.width } ?: 1.0
    val height = nodes.maxOfOrNull { graph.getPosition(it.id).y.toDouble() + it.height } ?: 1.0
    val filtered = GraphModel(nodes = nodes, edges = edges, width = width, height = height)
    // Copy positions from the original graph
    nodes.forEach { node ->
        val pos = graph.getPosition(node.id)
        filtered.setPosition(node.id, pos.x.toDouble(), pos.y.toDouble())
    }
    return filtered
}
