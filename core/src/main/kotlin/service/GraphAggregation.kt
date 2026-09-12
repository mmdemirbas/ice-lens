package service

import model.AggregationKind
import model.GraphEdge
import model.GraphNode
import model.aggregationKind

/**
 * How many siblings of one kind are drawn under one parent before the rest become one
 * expandable [GraphNode.GroupNode].
 *
 * One knob, not two. A separate "collapse above N, then show M" pair invites the case where
 * expanding a group shows fewer nodes than were already there, and there is no reading of the
 * graph in which that is what the user asked for.
 *
 * [pageSize] is also what one expansion reveals. 24 comes from the measured budget: composed
 * node cards cost 6.4 / 14.6 / 42.3 ms at 100 / 1,000 / 2,000 nodes, and 24 siblings across the
 * ~20 parents a screen holds lands under the 500-card target with room for the layers above.
 */
data class AggregationPolicy(val pageSize: Int = DEFAULT_PAGE_SIZE) {
    companion object {
        const val DEFAULT_PAGE_SIZE = 24
        val DEFAULT = AggregationPolicy()

        /** Draw every node. What the tests of layout and of the builders want. */
        val NONE = AggregationPolicy(pageSize = Int.MAX_VALUE)
    }
}

data class AggregationResult(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
)

/**
 * Replaces long runs of sibling nodes with a group node that can be expanded to reveal them.
 *
 * Pure, and deliberately a pass over a finished graph rather than a rule inside each builder:
 * the decision is about how many cards a reader and a layout engine can take, which has nothing
 * to do with Iceberg or Paimon. Both formats get it from one implementation, and a third gets
 * it for free.
 *
 * The shape it produces, for children of one parent of one kind:
 *
 * ```
 * members    [0 .. pageSize)   drawn
 *            [pageSize .. )    one group node, "and N more manifests"
 * expanded   [0 .. 2*pageSize) drawn
 *            [2*pageSize .. )  the next group node
 * ```
 *
 * so expansion is a set of group ids and never a count, and a group id survives a rebuild of
 * the graph because it is derived from the parent, the kind and how many pages came before.
 */
object GraphAggregation {

    /**
     * @param expandedGroupIds ids of [GraphNode.GroupNode]s the reader has opened. An id that no
     *   longer corresponds to anything is ignored, so state kept across a table reload is safe.
     */
    fun apply(
        nodes: List<GraphNode>,
        edges: List<GraphEdge>,
        expandedGroupIds: Set<String> = emptySet(),
        policy: AggregationPolicy = AggregationPolicy.DEFAULT,
    ): AggregationResult {
        if (policy.pageSize >= nodes.size) return AggregationResult(nodes, edges)

        val nodeById = nodes.associateBy { it.id }
        // Containment only. A sibling edge (Paimon snapshot to its schema) does not make the
        // schema part of the snapshot, and a lineage edge runs between peers — grouping on
        // either would pile up nodes that are not siblings at all.
        val containment = edges.filter { it.affectsLayout && !it.isSibling }

        val members = membersByParentAndKind(containment, nodeById, nodes)
        val hiddenByParent = mutableMapOf<String, MutableSet<String>>()
        val pending = mutableListOf<PendingGroup>()

        members.forEach { (key, siblings) ->
            val group = pageToCollapse(key, siblings, expandedGroupIds, policy) ?: return@forEach
            hiddenByParent.getOrPut(key.parentId) { mutableSetOf() } += group.memberIds
            pending += group
        }
        if (pending.isEmpty()) return AggregationResult(nodes, edges)

        val kept = reachableNodeIds(nodes, containment, hiddenByParent)
        // A group stands beside the siblings it pages, under their parent. A parent that is
        // itself not drawn — folded into a group of its own kind, or under one that was — leaves
        // nothing for its groups to stand beside, and a group emitted anyway reaches ELK with no
        // edge and lands in the first column, over the table root. Its members are still counted:
        // the sweep below reaches them through whichever group hid the parent.
        val drawn = pending.filter { it.parentId in kept }
        val attribution = attributeHiddenNodes(drawn, containment, kept, nodeById)

        val groupNodes = drawn.map { group ->
            val owned = attribution[group.id].orEmpty()
            GraphNode.GroupNode(
                id = group.id,
                parentId = group.parentId,
                kind = group.kind,
                memberIds = group.memberIds,
                hiddenNodeCount = owned.size,
                hiddenErrorCount = owned.count { nodeById[it] is GraphNode.ErrorNode },
                pageIndex = group.pageIndex,
            )
        }

        val visible = kept + groupNodes.map { it.id }
        val keptEdges = edges.filter { edge ->
            edge.fromId in visible &&
                edge.toId in visible &&
                hiddenByParent[edge.fromId]?.contains(edge.toId) != true
        }
        val groupEdges = groupNodes.map { group ->
            GraphEdge(id = "e_grp_${group.parentId}_to_${group.id}", fromId = group.parentId, toId = group.id)
        }

        return AggregationResult(
            nodes = nodes.filter { it.id in kept } + groupNodes,
            edges = keptEdges + groupEdges,
        )
    }

    private data class GroupKey(val parentId: String, val kind: AggregationKind)

    private data class PendingGroup(
        val id: String,
        val parentId: String,
        val kind: AggregationKind,
        val memberIds: List<String>,
        val pageIndex: Int,
    )

    /**
     * Children per parent per kind, in the order layout will draw them.
     *
     * Sorted with [SiblingOrder] rather than left in the order the builder emitted the edges,
     * because the two differ exactly where it matters. A manifest that several snapshots carry
     * forward is emitted once, under whichever snapshot first wrote it; every later snapshot
     * inherits that position. Reading the emitted order there would draw the first page of a
     * snapshot's manifests in another snapshot's order, and the reader has no way to tell.
     */
    private fun membersByParentAndKind(
        containment: List<GraphEdge>,
        nodeById: Map<String, GraphNode>,
        nodes: List<GraphNode>,
    ): Map<GroupKey, List<String>> {
        val members = LinkedHashMap<GroupKey, MutableList<String>>()
        containment.forEach { edge ->
            val kind = nodeById[edge.toId]?.aggregationKind() ?: return@forEach
            members.getOrPut(GroupKey(edge.fromId, kind)) { mutableListOf() } += edge.toId
        }
        // Only snapshots need it, and only when there is a run of them long enough to page.
        val lineageRank by lazy {
            GraphLayoutService.snapshotLineageOrder(nodes.filterIsInstance<GraphNode.SnapshotNode>())
        }
        return members.mapValues { (key, ids) ->
            if (ids.size < 2) return@mapValues ids
            val comparator = SiblingOrder.forKind(
                key.kind,
                if (key.kind == AggregationKind.SNAPSHOT) lineageRank else emptyMap(),
            )
            ids.sortedWith(compareBy(comparator) { nodeById.getValue(it) })
        }
    }

    /**
     * The page of [siblings] that stays collapsed, or null when they all fit.
     *
     * A tail of one is drawn rather than grouped: a card reading "and 1 more manifest" occupies
     * the space of the manifest it is standing in for, and costs a click on top.
     */
    private fun pageToCollapse(
        key: GroupKey,
        siblings: List<String>,
        expandedGroupIds: Set<String>,
        policy: AggregationPolicy,
    ): PendingGroup? {
        var shown = policy.pageSize
        var pageIndex = 1
        while (groupId(key, pageIndex) in expandedGroupIds && shown < siblings.size) {
            shown += policy.pageSize
            pageIndex++
        }
        val hidden = siblings.drop(shown)
        if (hidden.size <= 1) return null
        return PendingGroup(
            id = groupId(key, pageIndex),
            parentId = key.parentId,
            kind = key.kind,
            memberIds = hidden,
            pageIndex = pageIndex,
        )
    }

    private fun groupId(key: GroupKey, pageIndex: Int): String =
        groupId(key.parentId, key.kind, pageIndex)

    /**
     * The id a group gets. Derived from the parent, the kind and how many pages came before it,
     * so it survives the graph rebuild that expanding one causes.
     */
    fun groupId(parentId: String, kind: AggregationKind, pageIndex: Int): String =
        "grp_${parentId}_${kind.key}_$pageIndex"

    /**
     * The expanded page ids that belong to one parent — the inverse of expanding it.
     *
     * Expanding a parent's last page leaves no group node behind, so once it is fully drawn there
     * is nothing on the canvas to double-click back: the only route was "collapse every group",
     * which closes the other parents too. This is what a per-parent collapse needs, and there is no
     * group node to ask, which is exactly the situation.
     *
     * **Generated and intersected rather than parsed.** A group id is
     * `grp_<parentId>_<kind>_<page>` and a parent id contains underscores of its own (`man_3`,
     * `table_root`), so taking a parent back out of the string means guessing where the kind
     * begins — and a kind key that appears inside a parent id makes the guess wrong silently.
     * Building the ids this parent *could* have and keeping the ones that are actually expanded
     * cannot be ambiguous. The candidate range is bounded by [expanded] because a page id can only
     * be in the set if something put it there, and nothing adds more ids than pages.
     */
    fun expandedGroupIdsUnder(parentId: String, expanded: Set<String>): Set<String> {
        if (expanded.isEmpty()) return emptySet()
        return AggregationKind.entries
            .flatMap { kind -> (1..expanded.size).map { page -> groupId(parentId, kind, page) } }
            .filterTo(mutableSetOf()) { it in expanded }
    }

    /**
     * The ids that, added to the expanded set, draw every sibling [group] stands for.
     *
     * One page at a time is the right default and the wrong only option — a parent with 5,000
     * manifests is 208 double-clicks away from being fully drawn. Expanding is expressed as a set
     * of page ids rather than a flag, so this is arithmetic over that set and needs no second
     * mechanism in the pass itself. Ids past the last real page are harmless: [pageToCollapse]
     * stops walking once every sibling is shown.
     */
    fun pageIdsToRevealAll(group: GraphNode.GroupNode, policy: AggregationPolicy): Set<String> {
        val pageSize = policy.pageSize.toLong().coerceAtLeast(1L)
        val pages = ((group.memberCount + pageSize - 1) / pageSize).toInt().coerceAtLeast(1)
        return (0 until pages).mapTo(mutableSetOf()) {
            groupId(group.parentId, group.kind, group.pageIndex + it)
        }
    }

    /**
     * The nodes still reachable once the collapsed edges are cut.
     *
     * Reachability rather than "delete the members and their subtrees", because the graph is not
     * a tree. One Iceberg manifest is a child of every snapshot that carries it forward, so
     * collapsing one snapshot's manifests must not remove a manifest another snapshot is still
     * drawing. A node leaves the graph only when every way in was cut.
     *
     * Roots are the nodes with no incoming containment edge in the **original** graph. Deriving
     * them from the surviving edges instead would promote every collapsed member to a root and
     * keep the whole subtree.
     */
    private fun reachableNodeIds(
        nodes: List<GraphNode>,
        containment: List<GraphEdge>,
        hiddenByParent: Map<String, Set<String>>,
    ): Set<String> {
        val hasParent = containment.mapTo(HashSet()) { it.toId }
        val childrenOf = containment
            .filter { hiddenByParent[it.fromId]?.contains(it.toId) != true }
            .groupBy({ it.fromId }, { it.toId })

        val reached = HashSet<String>()
        val stack = ArrayDeque(nodes.filter { it.id !in hasParent }.map { it.id })
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!reached.add(id)) continue
            childrenOf[id].orEmpty().forEach(stack::addLast)
        }
        return reached
    }

    /**
     * Which group each departed node is counted against, so the per-group totals add up to the
     * number of nodes that actually left.
     *
     * A breadth-first sweep from every group's members at once, first group to reach a node owns
     * it. A file under two collapsed manifests in two different snapshots is genuinely hidden by
     * both; counting it once under one of them keeps `sum(groups) == total hidden`, which is the
     * property that lets the canvas state a figure without computing it a second way.
     */
    private fun attributeHiddenNodes(
        pending: List<PendingGroup>,
        containment: List<GraphEdge>,
        kept: Set<String>,
        nodeById: Map<String, GraphNode>,
    ): Map<String, List<String>> {
        val childrenOf = containment.groupBy({ it.fromId }, { it.toId })
        val owner = HashMap<String, String>()
        val queue = ArrayDeque<Pair<String, String>>()

        pending.forEach { group ->
            group.memberIds.forEach { memberId ->
                if (memberId !in kept) queue += memberId to group.id
            }
        }
        while (queue.isNotEmpty()) {
            val (nodeId, groupId) = queue.removeFirst()
            if (nodeId in kept || nodeId in owner || nodeId !in nodeById) continue
            owner[nodeId] = groupId
            childrenOf[nodeId].orEmpty().forEach { child -> queue += child to groupId }
        }
        return owner.entries.groupBy({ it.value }, { it.key })
    }
}
