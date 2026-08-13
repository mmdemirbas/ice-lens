package ui

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Offset
import model.GraphModel

/**
 * Node positions as the shell sees them: whatever layout decided, with the user's drags on top.
 *
 * The split matters. Layout output lives in the core [GraphModel] — immutable, cacheable, safe to
 * compute on a background thread and safe to hand to a shell that has no UI toolkit. Drags are
 * shell state: Compose-observable so a drag repaints, and owned by whichever front end is
 * displaying the graph.
 *
 * Compose-observable state must only be read and written on the main thread, which is why this
 * type exists in the desktop module and never crosses back into core.
 */
class NodePositions(private val graph: GraphModel) {

    /** Only the nodes the user has moved. Absent means "wherever layout put it". */
    private val dragged = mutableStateMapOf<String, Offset>()

    fun of(nodeId: String): Offset =
        dragged[nodeId] ?: graph.layoutPosition(nodeId).let { Offset(it.x, it.y) }

    fun set(nodeId: String, x: Float, y: Float) {
        dragged[nodeId] = Offset(x, y)
    }

    fun moveBy(nodeId: String, dx: Float, dy: Float) {
        val current = of(nodeId)
        dragged[nodeId] = Offset(current.x + dx, current.y + dy)
    }

    /** The drags alone, for undo. Layout positions are not captured — they cannot change. */
    fun draggedSnapshot(): Map<String, Pair<Double, Double>> =
        dragged.toMap().mapValues { (_, offset) -> offset.x.toDouble() to offset.y.toDouble() }

    fun restore(snapshot: Map<String, Pair<Double, Double>>) {
        snapshot.forEach { (id, xy) -> set(id, xy.first.toFloat(), xy.second.toFloat()) }
    }

    /**
     * Every node's effective position, drags included — what a re-layout must merge over so a
     * user's manual arrangement survives a reload.
     */
    fun effective(): Map<String, Offset> =
        graph.nodes.associate { node -> node.id to of(node.id) }
}
