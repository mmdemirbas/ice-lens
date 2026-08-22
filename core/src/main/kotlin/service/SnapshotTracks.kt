package service

import model.GraphNode

/**
 * Which column each snapshot draws in, so a fork reads as a divergence rather than as a list.
 *
 * The graph lays snapshots out in one layer, stacked vertically in lineage order
 * ([GraphLayoutService.snapshotLineageOrder]), which already keeps a branch's commits together.
 * What one column cannot show is that two commits are *concurrent*: with every snapshot at the
 * same x, a fork is a lineage edge reaching back over some rows, and with three branches those
 * edges cross each other. A track is the second dimension — the same one `git log --graph` uses,
 * rotated, because this graph runs left to right and its commits stack downwards.
 *
 * ## The assignment
 *
 * Walked in the order the snapshots are drawn in, top to bottom. A commit takes the track its
 * parent reserved for it; a commit nobody reserved for takes the leftmost free track. Then it
 * reserves for its own children: the **first** child continues in its track, and every later
 * child opens a new one and holds it until the walk reaches it. Holding is the point — a track
 * reserved for a second branch stays empty for the whole of the first branch's run, which is
 * what makes the two read as parallel rather than sequential.
 *
 * A track is reused once nothing is reserved in it, so an unrelated root chain that starts below
 * where another one ended draws in the same column. Reuse is safe precisely because the vertical
 * order is depth-first: rows of one subtree never interleave with rows of another.
 *
 * On a linear history every snapshot has one child, so every commit inherits track 0 and the
 * result is empty of information — which is why the caller does nothing at all when the highest
 * track is 0, and no table without branches moves by a pixel.
 */
internal fun snapshotTracks(snapshots: List<GraphNode.SnapshotNode>): Map<String, Int> {
    if (snapshots.size < 2) return emptyMap()
    val order = GraphLayoutService.snapshotLineageOrder(snapshots)
    val children = lineageChildren(snapshots)
    val drawn = snapshots.sortedBy { order[it.id] ?: Int.MAX_VALUE }

    // One slot per track. A slot holds the snapshot id that track is being kept for, or null when
    // the track is free. Reserving by id rather than by position is what survives the whole first
    // subtree being walked in between.
    val reservedFor = mutableListOf<Long?>()
    fun claimFreeTrack(): Int {
        val free = reservedFor.indexOfFirst { it == null }
        if (free >= 0) return free
        reservedFor.add(null)
        return reservedFor.size - 1
    }

    val tracks = mutableMapOf<String, Int>()
    drawn.forEach { node ->
        val commit = node.data.snapshotId
        val mine = reservedFor.indexOfFirst { it != null && it == commit }.takeIf { it >= 0 }
            ?: claimFreeTrack()
        reservedFor[mine] = null
        tracks[node.id] = mine

        children[commit].orEmpty().forEachIndexed { index, child ->
            val childCommit = child.data.snapshotId ?: return@forEachIndexed
            reservedFor[if (index == 0) mine else claimFreeTrack()] = childCommit
        }
    }
    return tracks
}

/**
 * Each snapshot's children, in the order they are drawn.
 *
 * Shared with [GraphLayoutService.snapshotLineageOrder] rather than rebuilt: the two have to
 * agree on which child is *first*, because the ordering walks that child next and the track
 * assignment gives it the parent's column. Two copies of that rule would drift into a graph
 * whose lineage edges do not follow its lanes.
 */
internal fun lineageChildren(
    snapshots: List<GraphNode.SnapshotNode>,
): Map<Long?, List<GraphNode.SnapshotNode>> {
    val byCommit = snapshots.mapNotNull { node -> node.data.snapshotId?.let { it to node } }.toMap()
    val siblingOrder = compareBy<GraphNode.SnapshotNode>(
        { it.data.timestampMs ?: Long.MAX_VALUE },
        { it.data.sequenceNumber ?: Long.MAX_VALUE },
        { it.data.snapshotId ?: Long.MAX_VALUE },
    )
    return snapshots
        .filter { it.data.parentSnapshotId != null && byCommit.containsKey(it.data.parentSnapshotId) }
        .groupBy { it.data.parentSnapshotId }
        .mapValues { (_, children) -> children.sortedWith(siblingOrder) }
}
