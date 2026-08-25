package model

/** Which way an arrow key moves through the drawing. */
enum class GraphDirection { LEFT, RIGHT, UP, DOWN }

/**
 * Where an arrow key moves the selection, or null when there is nothing that way.
 *
 * Navigation is defined against **where the nodes are drawn**, not against the order a comparator
 * would put them in. The reader is looking at a picture; "the one below" has to mean the one below
 * on screen, or the selection jumps somewhere they cannot see the reason for. That is also why
 * [positionOf] is a parameter rather than a read of [GraphModel.layoutPositions]: a shell that
 * lets the user drag nodes has to navigate the drawing the user made, not the one layout proposed.
 *
 * Left and right follow the edges, up and down follow the column:
 *
 * - **Left / right** move to a parent or a child, choosing the one whose vertical centre is
 *   nearest — with a fan of twelve files under one manifest, the one across from you is the one
 *   you meant.
 * - **Up / down** move to the nearest node whose horizontal span overlaps yours. That is the
 *   column as the eye reads it, and it is what makes a branch behave: `spreadSnapshotBranches`
 *   gives a fork its own column with a gutter, so walking down the main line does not step
 *   sideways into a branch that merely happens to sit at a similar height.
 *
 * **Only edges that shaped the layout are followed.** A lineage edge between two snapshots, and a
 * deletion vector's edge to the file it names, are annotations over the tree — drawn dashed for
 * exactly that reason — and both run between nodes of one layer. Following them from `left` would
 * mean two different things by "parent" in the same keystroke.
 */
fun stepFrom(
    graph: GraphModel,
    fromId: String,
    direction: GraphDirection,
    positionOf: (GraphNode) -> Point,
): String? {
    val from = graph.nodeById[fromId] ?: return null
    val here = positionOf(from)
    return when (direction) {
        GraphDirection.LEFT -> nearestAcross(relatives(graph, fromId, incoming = true), here, from, positionOf)
        GraphDirection.RIGHT -> nearestAcross(relatives(graph, fromId, incoming = false), here, from, positionOf)
        GraphDirection.UP -> nearestInColumn(graph, from, here, positionOf, downward = false)
        GraphDirection.DOWN -> nearestInColumn(graph, from, here, positionOf, downward = true)
    }
}

/**
 * Where the selection starts when an arrow is pressed with nothing selected.
 *
 * The node with no parent, leftmost then topmost — the table root in every graph this draws, but
 * stated as a property of the drawing rather than as a node id, so it still answers on a graph
 * whose root is filtered out.
 */
fun firstNode(graph: GraphModel, positionOf: (GraphNode) -> Point): String? {
    val hasParent = graph.edges.filter { it.isStructural }.map { it.toId }.toSet()
    val roots = graph.nodes.filter { it.id !in hasParent }.ifEmpty { graph.nodes }
    return roots.minWithOrNull(
        compareBy({ positionOf(it).x }, { positionOf(it).y }, { it.id }),
    )?.id
}

/**
 * A parent-child edge, as opposed to the two kinds that are not one.
 *
 * `affectsLayout = false` marks an annotation — lineage, a deletion vector — and `isSibling`
 * marks an edge drawn between two nodes at the same depth. Neither answers "what contains this".
 */
private val GraphEdge.isStructural: Boolean get() = affectsLayout && !isSibling

private fun relatives(graph: GraphModel, nodeId: String, incoming: Boolean): List<GraphNode> =
    graph.edges
        .filter { it.isStructural && (if (incoming) it.toId else it.fromId) == nodeId }
        .mapNotNull { graph.nodeById[if (incoming) it.fromId else it.toId] }
        .distinctBy { it.id }

private fun nearestAcross(
    candidates: List<GraphNode>,
    here: Point,
    from: GraphNode,
    positionOf: (GraphNode) -> Point,
): String? {
    val centre = here.y + from.height.toFloat() / 2f
    return candidates.minWithOrNull(
        compareBy(
            { kotlin.math.abs(positionOf(it).y + it.height.toFloat() / 2f - centre) },
            { it.id },
        ),
    )?.id
}

private fun nearestInColumn(
    graph: GraphModel,
    from: GraphNode,
    here: Point,
    positionOf: (GraphNode) -> Point,
    downward: Boolean,
): String? {
    val left = here.x
    val right = here.x + from.width.toFloat()
    val candidates = graph.nodes.asSequence()
        .filter { it.id != from.id }
        .mapNotNull { node ->
            val p = positionOf(node)
            val overlaps = p.x < right && left < p.x + node.width.toFloat()
            val delta = p.y - here.y
            val ahead = if (downward) delta > 0.5f else delta < -0.5f
            if (overlaps && ahead) node to kotlin.math.abs(delta) else null
        }
    return candidates.minWithOrNull(compareBy({ it.second }, { it.first.id }))?.first?.id
}
