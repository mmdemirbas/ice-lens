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
 * One drawn column of snapshots, and the branch whose tip sits at the bottom of it.
 *
 * [x] and [topY] are where the column is, so a caller can put the name above it. [labels] is
 * empty for a column no branch points into — which is a real state, not a gap: a column can be a
 * chain of commits kept alive only by a tag, or by an ancestor's ref.
 */
data class SnapshotColumn(
    val x: Double,
    val topY: Double,
    val labels: List<model.SnapshotRefLabel>,
)

/**
 * Names each column of snapshots after the branch whose tip is drawn at the bottom of it.
 *
 * A fork already reads as a fork — [snapshotTracks] gives each branch its own column and the
 * lineage edges are dashed — but nothing said *which* column was `main`. The ref chips are on
 * whichever commit a ref happens to point at, so reading the column meant following the dashes
 * back to a chip, which is the work the columns were supposed to remove.
 *
 * **The bottom-most commit names the column, and only if a branch points at it.** Commits are
 * drawn oldest-first downwards, so the bottom of a column is the tip of that line, and a branch
 * ref on the tip is what a line of commits *is*.
 *
 * Both halves of that rule reject a tag, for the same reason. A tag partway up marks a point in
 * history rather than the line — `release` sitting three commits back does not make the column
 * `release`. And a tag *on* the tip names the column only by today's coincidence: `prod` pointing
 * at the same commit `main` does says nothing about the column, and drawing both over it prints a
 * second name for a line that has one. So the header carries branches only, and the tags stay on
 * the ref chips of the commit they actually address.
 *
 * Positions come in through [positionOf] rather than being read from the layout, the same way
 * [model.stepFrom] takes them: a reader who has dragged a snapshot has made the drawing, and the
 * label belongs over the column they are looking at.
 *
 * Returns nothing for a single column. A linear history is one line, and a name floating over the
 * only column there is says nothing the graph did not already say.
 */
fun snapshotColumns(
    snapshots: List<GraphNode.SnapshotNode>,
    positionOf: (String) -> model.Point,
): List<SnapshotColumn> {
    if (snapshots.size < 2) return emptyList()
    // Rounded, because two nodes of one column are placed at the same x by layout but a drag can
    // leave a fraction of a dp between them.
    val byColumn = snapshots.groupBy { Math.round(positionOf(it.id).x.toDouble()) }
    if (byColumn.size < 2) return emptyList()

    return byColumn.map { (x, column) ->
        val tip = column.maxBy { positionOf(it.id).y }
        SnapshotColumn(
            x = x.toDouble(),
            topY = column.minOf { positionOf(it.id).y.toDouble() },
            labels = tip.refs.filter { it.isBranch },
        )
    }.sortedBy { it.x }
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
    val trunk = trunkCommits(snapshots, byCommit)
    val siblingOrder = compareBy<GraphNode.SnapshotNode>(
        // The trunk first, ahead of time. Everything below orders siblings by when they were
        // written, and on that alone a branch that commits before the trunk's next commit is the
        // "first child" — so it takes the column its parent was drawn in and the trunk is pushed
        // into a new one. The result is a drawing where the root commit sits under a feature
        // branch's name and the main line changes column halfway down, which is not a rendering
        // detail: it is the graph saying the wrong thing about which line is which. Measured on
        // `branched3`, where `main` landed in column 3 and column 0 was labelled `staging`.
        { if (it.data.snapshotId in trunk) 0 else 1 },
        { it.data.timestampMs ?: Long.MAX_VALUE },
        { it.data.sequenceNumber ?: Long.MAX_VALUE },
        { it.data.snapshotId ?: Long.MAX_VALUE },
    )
    return snapshots
        .filter { it.data.parentSnapshotId != null && byCommit.containsKey(it.data.parentSnapshotId) }
        .groupBy { it.data.parentSnapshotId }
        .mapValues { (_, children) -> children.sortedWith(siblingOrder) }
}

/**
 * The commits on the table's main line: the `main` tip and everything it descends from.
 *
 * `main` is not a convention borrowed from git here — Iceberg's spec requires the branch and ties
 * `current-snapshot-id` to it, so it is the table's own statement about which line an unqualified
 * read resolves to. That is the only thing that distinguishes two children of one commit when both
 * are the tip of their own line, which is exactly the case `branched3`'s last fork produces.
 *
 * Empty when no branch called `main` is drawn — a graph filtered to one branch, or a format that
 * does not have the concept — and then every sibling order is what it always was.
 */
private fun trunkCommits(
    snapshots: List<GraphNode.SnapshotNode>,
    byCommit: Map<Long, GraphNode.SnapshotNode>,
): Set<Long> {
    val tip = snapshots.firstOrNull { node -> node.refs.any { it.isBranch && it.name == "main" } }
        ?: return emptySet()
    return buildSet {
        var commit = tip.data.snapshotId
        // `add` returning false ends the walk, so a parent chain that somehow loops terminates.
        while (commit != null && add(commit)) commit = byCommit[commit]?.data?.parentSnapshotId
    }
}
