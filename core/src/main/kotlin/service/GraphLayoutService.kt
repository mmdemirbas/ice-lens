package service

import model.*
import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.data.LayoutMetaDataService
import org.eclipse.elk.core.math.KVector
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.Direction
import org.eclipse.elk.core.options.EdgeRouting
import org.eclipse.elk.core.options.SizeConstraint
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.eclipse.elk.graph.ElkNode
import org.eclipse.elk.graph.util.ElkGraphUtil
import org.slf4j.LoggerFactory
import java.util.*

private val logger = LoggerFactory.getLogger(GraphLayoutService::class.java)

/**
 * Layout engine that positions pre-built graph nodes using ELK and applies
 * post-processing (chronological ordering, parent alignment, overlap prevention).
 *
 * Graph construction is delegated to format-specific builders (e.g., [IcebergGraphBuilder]).
 */
object GraphLayoutService {

    init {
        // Every algorithm the reader can pick has to be registered, and ELK resolves them by id at
        // layout time — an unregistered one is a runtime failure with an ELK-worded message, not a
        // compile error. `GraphLayoutAlgorithmTest` lays a real table out under every entry so a
        // missing provider fails here rather than in front of somebody.
        LayoutMetaDataService
            .getInstance()
            .registerLayoutMetaDataProviders(
                LayeredMetaDataProvider(),
                org.eclipse.elk.alg.mrtree.options.MrTreeMetaDataProvider(),
                org.eclipse.elk.alg.force.options.ForceMetaDataProvider(),
            )
    }

    /** Max sample rows read from Parquet via DuckDB. */
    const val MAX_PARQUET_SAMPLE_ROWS = 50

    /**
     * The gap between two branch columns in the snapshot layer.
     *
     * Narrower than the 120 that separates unrelated siblings: two commits side by side are two
     * halves of one fork, and the space between them should say so rather than read as the space
     * between two things that have nothing to do with each other.
     */
    private const val SNAPSHOT_TRACK_GUTTER = 60.0

    /**
     * Builds and lays out a graph for any table format model.
     *
     * Three stages, in this order and not another. The builder emits every node the metadata
     * describes; [GraphAggregation] decides which of them the graph draws, folding the rest into
     * expandable group nodes; and only then are sample rows read, for the data files that
     * survived. Aggregating first is what keeps a production table's file count from costing a
     * filesystem stat and five nodes per file before anything is on screen. The rows are then
     * put through the same pass, so they are bounded by the page size like every other kind
     * rather than by the sample-row cap happening to be smaller.
     *
     * @param expandedGroupIds group nodes the reader has opened. Ids are derived from the parent,
     *   the kind and the page, so a set kept across a reload still means the same thing.
     */
    fun layoutGraph(
        tableModel: FormatTableModel,
        showRows: Boolean,
        expandedGroupIds: Set<String> = emptySet(),
        policy: AggregationPolicy = AggregationPolicy.DEFAULT,
        algorithm: GraphLayoutAlgorithm = GraphLayoutAlgorithm.DEFAULT,
    ): GraphModel {
        logger.debug("Building {} graph for: {}", tableModel.format, tableModel.name)
        val buildResult = when (tableModel) {
            is UnifiedTableModel -> IcebergGraphBuilder.buildGraph(tableModel)
            is PaimonUnifiedTableModel -> PaimonGraphBuilder.buildGraph(tableModel)
        }
        val aggregated = GraphAggregation.apply(buildResult.nodes, buildResult.edges, expandedGroupIds, policy)
        val withRows = if (showRows) {
            val attached = attachSampleRows(aggregated, buildResult.sampleRows)
            // Rows arrive after the pass that bounds every other kind, so they go through it
            // again rather than being the one kind exempt from the page size. The second pass can
            // only touch rows: every other kind is already at or below the page size, and a group
            // node has no aggregation kind, so it never becomes a member of anything.
            GraphAggregation.apply(attached.nodes, attached.edges, expandedGroupIds, policy)
        } else {
            aggregated
        }
        logger.debug(
            "{} graph built: {} nodes drawn of {}, {} edges",
            tableModel.format, withRows.nodes.size, buildResult.nodes.size, withRows.edges.size,
        )
        return layoutNodes(withRows.nodes, withRows.edges, algorithm)
    }

    /** Reads sample rows for the data-file nodes the graph is actually drawing. */
    private fun attachSampleRows(
        graph: AggregationResult,
        sampleRows: Map<String, () -> List<GraphNode.RowNode>>,
    ): AggregationResult {
        if (sampleRows.isEmpty()) return graph
        val rowNodes = mutableListOf<GraphNode>()
        val rowEdges = mutableListOf<GraphEdge>()
        graph.nodes.forEach { node ->
            val factory = sampleRows[node.id] ?: return@forEach
            factory().forEach { row ->
                rowNodes += row
                rowEdges += GraphEdge("e_row_${row.id}", node.id, row.id)
            }
        }
        return AggregationResult(graph.nodes + rowNodes, graph.edges + rowEdges)
    }

    /**
     * Positions pre-built nodes and edges using ELK layered layout, then applies
     * chronological ordering, parent alignment, and overlap prevention.
     */
    fun layoutNodes(
        nodes: List<GraphNode>,
        edges: List<GraphEdge>,
        algorithm: GraphLayoutAlgorithm = GraphLayoutAlgorithm.DEFAULT,
    ): GraphModel {
        val root = ElkGraphUtil.createGraph()
        root.setProperty(CoreOptions.ALGORITHM, algorithm.elkId)
        algorithm.direction?.let { root.setProperty(CoreOptions.DIRECTION, it) }
        root.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.SPLINES)
        root.setProperty(CoreOptions.SPACING_NODE_NODE, 120.0)
        if (algorithm.elkId == "org.eclipse.elk.layered") {
            root.setProperty(
                org.eclipse.elk.alg.layered.options.LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS,
                300.0
            )
        }

        // Create ELK nodes from logical nodes
        val elkNodes = mutableMapOf<String, ElkNode>()
        for (node in nodes) {
            elkNodes[node.id] = createElkNode(root, node.id, node.width, node.height)
        }

        // Create ELK edges. Edges that only record a relationship are withheld — see
        // GraphEdge.affectsLayout; snapshot lineage runs between nodes that belong in the same
        // layer, and letting it constrain layering stretches the graph by the commit history.
        for (edge in edges) {
            if (!edge.affectsLayout) continue
            val from = elkNodes[edge.fromId]
            val to = elkNodes[edge.toId]
            if (from != null && to != null) {
                ElkGraphUtil.createSimpleEdge(from, to)
            }
        }

        // Run ELK layout
        val engine = RecursiveGraphLayoutEngine()
        engine.layout(root, BasicProgressMonitor())

        // Sync ELK positions back to node models
        val finalNodes = nodes.map { node ->
            val elkNode = elkNodes[node.id]
            if (elkNode != null) {
                node.x = elkNode.x
                node.y = elkNode.y
            }
            node
        }

        val nodesById = finalNodes.associateBy { it.id }
        // Ordering runs twice, on purpose, and the second pass is the one that decides.
        //
        // alignParentsWithChildren moves a parent to the centre of its children, so a snapshot
        // ends up wherever its manifests are — which silently overrode the order the first pass
        // had just established. The vertical order of snapshots was therefore decided by ELK's
        // placement of their manifests, not by the comparator, and putting lineage into that
        // comparator changed nothing on screen until this second pass was added.
        //
        // The two passes are not equals: ordering is a constraint and alignment is a preference,
        // so the constraint is applied last. Alignment still does its work — the second pass only
        // permutes nodes within the y slots alignment left them in.
        // Only under the layered left-to-right layout. Every pass below is defined against that
        // shape — ordering runs down a column, alignment centres a parent vertically, the branch
        // spread claims a column — so under a downward layout each is about the wrong axis, and
        // under a tree or a force layout there are no layers for them to be about. See
        // `GraphLayoutAlgorithm.refinesLayers`: the other layouts are ELK's own output, which is
        // an honest drawing, where half-transposed passes would be a worse one.
        val branchSpread = if (!algorithm.refinesLayers) 0.0 else {
            enforceChronologicalVerticalOrder(nodesById, edges)
            alignParentsWithChildren(nodesById, edges)
            enforceChronologicalVerticalOrder(nodesById, edges)
            preventOverlaps(nodesById)
            // Last, and it moves x only. Everything above decides the vertical order and then
            // holds it; giving a branch its own column is a statement about the horizontal axis
            // alone, so it cannot disturb any of it. It also runs after overlap prevention rather
            // than before, because that pass compares y and ignores x — two commits in different
            // columns would otherwise be pushed apart vertically for an overlap that is not there.
            spreadSnapshotBranches(nodesById)
        }

        val posMap = finalNodes.associate { it.id to Point(it.x.toFloat(), it.y.toFloat()) }
        return GraphModel(finalNodes, edges, root.width + branchSpread, root.height, layoutPositions = posMap)
    }

    /**
     * Depth-first pre-order over the lineage forest: a snapshot, then its children oldest first.
     *
     * The point is that a branch stays contiguous. Ordering snapshots by timestamp alone
     * interleaves two branches by wall-clock time, so a fork reads as an arbitrary sequence and
     * the only thing saying otherwise is an edge crossing back over several rows.
     *
     * On a linear history this is identical to chronological order — each snapshot has exactly
     * one child and the walk follows the chain — which is what makes the change safe for every
     * table that has no branches. Iterative rather than recursive: a real table's history is
     * long enough to overflow a stack.
     *
     * A snapshot whose parent is not present (expired, or on a branch this metadata does not
     * carry) is a root, so nothing is dropped from the ordering.
     */
    internal fun snapshotLineageOrder(snapshots: List<GraphNode.SnapshotNode>): Map<String, Int> {
        if (snapshots.isEmpty()) return emptyMap()
        val byCommit = snapshots.mapNotNull { node -> node.data.snapshotId?.let { it to node } }.toMap()
        val siblingOrder = compareBy<GraphNode.SnapshotNode>(
            { it.data.timestampMs ?: Long.MAX_VALUE },
            { it.data.sequenceNumber ?: Long.MAX_VALUE },
            { it.data.snapshotId ?: Long.MAX_VALUE },
        )
        // Shared with snapshotTracks: which child is first decides both what the walk visits next
        // and which branch keeps the parent's column, and those two have to be the same child.
        val childrenOf = lineageChildren(snapshots)

        val roots = snapshots.filter { node ->
            node.data.parentSnapshotId == null || !byCommit.containsKey(node.data.parentSnapshotId)
        }.sortedWith(siblingOrder)

        val rank = mutableMapOf<String, Int>()
        // Reversed pushes keep siblings in ascending order as they come back off the stack.
        val stack = ArrayDeque(roots.asReversed())
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (rank.containsKey(node.id)) continue
            rank[node.id] = rank.size
            childrenOf[node.data.snapshotId].orEmpty().asReversed().forEach(stack::addLast)
        }
        return rank
    }

    private fun enforceChronologicalVerticalOrder(
        nodesById: Map<String, GraphNode>,
        edges: List<GraphEdge>
    ) {
        fun reorder(nodes: List<GraphNode>, comparator: Comparator<GraphNode>, minGap: Double = 24.0) {
            if (nodes.size < 2) return
            val ySlots = nodes.map { it.y }.sorted()
            val desired = nodes.sortedWith(comparator)
            var currentY = ySlots.first()
            desired.forEachIndexed { index, node ->
                val slotY = ySlots[index]
                currentY = if (index == 0) slotY else maxOf(slotY, currentY + minGap)
                node.y = currentY
            }
        }

        val nonSiblingEdges = edges.filter { !it.isSibling && it.affectsLayout }
        val childrenByParent = nonSiblingEdges.groupBy { it.fromId }
            .mapValues { (_, v) -> v.map { it.toId } }

        // Sibling order is shared with GraphAggregation — see SiblingOrder for why one definition.
        val metadataComparator = SiblingOrder.METADATA
        val snapshotComparator = SiblingOrder.snapshot(
            snapshotLineageOrder(nodesById.values.filterIsInstance<GraphNode.SnapshotNode>())
        )
        val manifestComparator = SiblingOrder.MANIFEST
        val fileComparator = SiblingOrder.FILE
        val rowComparator = SiblingOrder.ROW

        fun parsePosition(data: Map<String, Any>): Int? {
            val raw = data["pos"] ?: data["position"] ?: return null
            val asLong = when (raw) {
                is Number -> raw.toLong()
                else -> raw.toString().toLongOrNull() ?: return null
            }
            // Reject out-of-range values rather than silently truncating Long → Int.
            if (asLong < 0 || asLong > Int.MAX_VALUE.toLong()) return null
            return asLong.toInt()
        }

        val metadataNodes = nodesById.values.filterIsInstance<GraphNode.MetadataNode>()
        reorder(metadataNodes, metadataComparator, minGap = 52.0)

        metadataNodes.forEach { parent ->
            val snapshotChildren = childrenByParent[parent.id].orEmpty()
                .mapNotNull { nodesById[it] as? GraphNode.SnapshotNode }
            reorder(snapshotChildren, snapshotComparator, minGap = 40.0)
        }

        nodesById.values.filterIsInstance<GraphNode.SnapshotNode>().forEach { parent ->
            val manifestChildren = childrenByParent[parent.id].orEmpty()
                .mapNotNull { nodesById[it] as? GraphNode.ManifestNode }
            reorder(manifestChildren, manifestComparator, minGap = 34.0)
        }

        // Paimon: order snapshot nodes by ID
        val paimonSnapshots = nodesById.values.filterIsInstance<GraphNode.PaimonSnapshotNode>()
        reorder(paimonSnapshots, SiblingOrder.PAIMON_SNAPSHOT, minGap = 40.0)

        // Paimon: order manifest list nodes (base before delta before changelog)
        paimonSnapshots.forEach { parent ->
            val mlChildren = childrenByParent[parent.id].orEmpty()
                .mapNotNull { nodesById[it] as? GraphNode.PaimonManifestListNode }
            reorder(mlChildren, SiblingOrder.PAIMON_MANIFEST_LIST, minGap = 34.0)
        }

        // Paimon: order manifest nodes by simpleId
        nodesById.values.filterIsInstance<GraphNode.PaimonManifestListNode>().forEach { parent ->
            val manChildren = childrenByParent[parent.id].orEmpty()
                .mapNotNull { nodesById[it] as? GraphNode.PaimonManifestNode }
            reorder(manChildren, SiblingOrder.PAIMON_MANIFEST, minGap = 34.0)
        }

        // Paimon: order data file nodes by simpleId
        nodesById.values.filterIsInstance<GraphNode.PaimonManifestNode>().forEach { parent ->
            val fileChildren = childrenByParent[parent.id].orEmpty()
                .mapNotNull { nodesById[it] as? GraphNode.PaimonDataFileNode }
            reorder(fileChildren, SiblingOrder.PAIMON_FILE, minGap = 30.0)
        }

        val orderedManifests = nodesById.values
            .filterIsInstance<GraphNode.ManifestNode>()
            .sortedBy { it.y }

        val desiredFileOrder = orderedManifests.flatMap { manifestNode ->
            childrenByParent[manifestNode.id].orEmpty()
                .mapNotNull { nodesById[it] as? GraphNode.FileNode }
                .sortedWith(fileComparator)
        }
        if (desiredFileOrder.size > 1) {
            val fileYSlots = desiredFileOrder.map { it.y }.sorted()
            var currentY = fileYSlots.first()
            desiredFileOrder.forEachIndexed { index, fileNode ->
                val slotY = fileYSlots[index]
                currentY = if (index == 0) slotY else maxOf(slotY, currentY + 30.0)
                fileNode.y = currentY
            }
        }

        val orderedFiles = nodesById.values
            .filterIsInstance<GraphNode.FileNode>()
            .sortedBy { it.y }

        val rowParentFileByRowId = orderedFiles.flatMap { fileNode ->
            childrenByParent[fileNode.id].orEmpty().map { rowId -> rowId to fileNode }
        }.toMap()

        val dataRowsByTarget = mutableMapOf<Pair<String, Long>, List<GraphNode.RowNode>>()
        orderedFiles.forEach { fileNode ->
            val contentType = fileNode.data.content ?: 0
            val snapshotId = fileNode.entry.snapshotId
            val filePath = fileNode.data.filePath
            if (contentType != 0 || snapshotId == null || filePath.isNullOrBlank()) return@forEach
            val key = normalizeFilePath(filePath) to snapshotId
            if (dataRowsByTarget.containsKey(key)) return@forEach
            val rows = childrenByParent[fileNode.id].orEmpty()
                .mapNotNull { nodesById[it] as? GraphNode.RowNode }
                .sortedWith(rowComparator)
            dataRowsByTarget[key] = rows
        }

        val filesBySnapshot = orderedFiles.groupBy { it.entry.snapshotId }
        val snapshotsInOrder = orderedFiles.map { it.entry.snapshotId }.distinct()

        val adjustedRowOrder = mutableListOf<GraphNode.RowNode>()

        snapshotsInOrder.forEach { snapshotId ->
            val snapshotFiles = filesBySnapshot[snapshotId] ?: return@forEach

            val eqDeleteRows = mutableListOf<GraphNode.RowNode>()
            val dataRows = mutableListOf<GraphNode.RowNode>()
            val posDeleteRows = mutableListOf<GraphNode.RowNode>()

            snapshotFiles.forEach { fileNode ->
                val contentType = fileNode.data.content ?: 0
                val rows = childrenByParent[fileNode.id].orEmpty()
                    .mapNotNull { nodesById[it] as? GraphNode.RowNode }
                    .sortedWith(rowComparator)

                when (contentType) {
                    2 -> eqDeleteRows.addAll(rows)
                    0 -> dataRows.addAll(rows)
                    1 -> posDeleteRows.addAll(rows)
                }
            }

            adjustedRowOrder.addAll(eqDeleteRows)
            adjustedRowOrder.addAll(dataRows)

            data class PosDeleteMove(val row: GraphNode.RowNode, val anchor: GraphNode.RowNode)
            val moveCandidates = mutableListOf<PosDeleteMove>()

            posDeleteRows.forEach { rowNode ->
                val deleteFile = rowParentFileByRowId[rowNode.id] ?: return@forEach
                val targetPath = rowNode.resolvedData["file_path"]?.toString() ?: return@forEach
                val targetRows = dataRowsByTarget[normalizeFilePath(targetPath) to snapshotId] ?: return@forEach
                val position = parsePosition(rowNode.resolvedData) ?: return@forEach
                if (position < 0 || position >= targetRows.size) return@forEach
                moveCandidates.add(PosDeleteMove(rowNode, targetRows[position]))
            }

            val anchorIndexById = adjustedRowOrder.withIndex().associate { (i, r) -> r.id to i }
            val insertedAfterAnchor = mutableMapOf<String, Int>()
            moveCandidates.forEach { move ->
                val anchorIndex = anchorIndexById[move.anchor.id] ?: -1
                if (anchorIndex < 0) {
                    adjustedRowOrder.add(move.row)
                } else {
                    val offset = insertedAfterAnchor[move.anchor.id] ?: 0
                    val insertAt = (anchorIndex + 1 + offset).coerceAtMost(adjustedRowOrder.size)
                    adjustedRowOrder.add(insertAt, move.row)
                    insertedAfterAnchor[move.anchor.id] = offset + 1
                }
            }
        }

        if (adjustedRowOrder.size > 1) {
            val rowYSlots = adjustedRowOrder.map { it.y }.sorted()
            var currentY = rowYSlots.first()
            adjustedRowOrder.forEachIndexed { index, rowNode ->
                val slotY = rowYSlots[index]
                currentY = if (index == 0) slotY else maxOf(slotY, currentY + 24.0)
                rowNode.y = currentY
            }
        }

        // Paimon: order row nodes by ID within each data file parent
        nodesById.values.filterIsInstance<GraphNode.PaimonDataFileNode>().forEach { parent ->
            val rows = childrenByParent[parent.id].orEmpty()
                .mapNotNull { nodesById[it] as? GraphNode.RowNode }
            reorder(rows, rowComparator, minGap = 24.0)
        }

        placeGroupsBelowTheirSiblings(nodesById, childrenByParent)
    }

    /**
     * A group node sits directly below the last sibling it continues.
     *
     * It stands for the tail of an ordered run — the manifests after the twenty-fourth — so any
     * other position states something false about the order. None of the comparators above can
     * do this: each one filters to a concrete node type, and a group is not one of them. It runs
     * last within the ordering pass, and the ordering pass runs after alignment, so nothing
     * moves a group back above the run it belongs to.
     */
    private fun placeGroupsBelowTheirSiblings(
        nodesById: Map<String, GraphNode>,
        childrenByParent: Map<String, List<String>>,
    ) {
        nodesById.values.filterIsInstance<GraphNode.GroupNode>().forEach { group ->
            val siblings = childrenByParent[group.parentId].orEmpty()
                .mapNotNull { nodesById[it] }
                .filter { it !is GraphNode.GroupNode && it.aggregationKind() == group.kind }
            val bottom = siblings.maxOfOrNull { it.y + it.height } ?: return@forEach
            group.y = bottom + 16.0
        }
    }

    private fun alignParentsWithChildren(
        nodesById: Map<String, GraphNode>,
        edges: List<GraphEdge>,
    ) {
        val nonSiblingEdges = edges.filter { !it.isSibling && it.affectsLayout }
        val childrenByParentIds = nonSiblingEdges
            .groupBy { it.fromId }
            .mapValues { (_, v) -> v.map { it.toId } }
        val parentsByChildIds = nonSiblingEdges
            .groupBy { it.toId }
            .mapValues { (_, v) -> v.map { it.fromId } }

        fun sortedChildren(parentId: String, childFilter: (GraphNode) -> Boolean): List<GraphNode> =
            childrenByParentIds[parentId]
                .orEmpty()
                .mapNotNull { nodesById[it] }
                .filter(childFilter)
                .sortedWith(compareBy({ it.y }, { it.id }))

        fun siblingParentIds(
            parentId: String,
            layerParentIds: Set<String>,
            upstreamFilter: (GraphNode) -> Boolean
        ): Set<String> {
            val upstreamIds = parentsByChildIds[parentId]
                .orEmpty()
                .filter { upId -> nodesById[upId]?.let(upstreamFilter) == true }
            if (upstreamIds.isEmpty()) return setOf(parentId)

            val siblings = upstreamIds
                .asSequence()
                .flatMap { upstreamId ->
                    childrenByParentIds[upstreamId].orEmpty().asSequence()
                }
                .filter { it in layerParentIds }
                .toSet()

            return if (siblings.isEmpty()) setOf(parentId) else siblings
        }

        fun alignLayer(
            parents: List<GraphNode>,
            upstreamFilter: (GraphNode) -> Boolean,
            childFilter: (GraphNode) -> Boolean
        ) {
            if (parents.isEmpty()) return
            val orderedParents = parents.sortedWith(compareBy({ it.y }, { it.id }))
            val layerParentIds = orderedParents.map { it.id }.toSet()
            val orderIndexByParentId = orderedParents
                .mapIndexed { index, parent -> parent.id to index }
                .toMap()
            val hasChildrenByParentId = orderedParents.associate { parent ->
                parent.id to sortedChildren(parent.id, childFilter).isNotEmpty()
            }

            orderedParents.forEach { parent ->
                val children = sortedChildren(parent.id, childFilter)
                if (children.isEmpty()) return@forEach

                val siblings = siblingParentIds(parent.id, layerParentIds, upstreamFilter)
                val parentOrder = orderIndexByParentId[parent.id] ?: Int.MAX_VALUE
                val previousSiblingIds = siblings.filter { siblingId ->
                    (orderIndexByParentId[siblingId] ?: Int.MAX_VALUE) < parentOrder
                }

                val siblingChildIds = previousSiblingIds
                    .asSequence()
                    .flatMap { siblingId ->
                        sortedChildren(siblingId, childFilter).asSequence().map { it.id }
                    }
                    .toSet()

                val anchorChild = children.firstOrNull { it.id !in siblingChildIds } ?: children.first()
                parent.y = anchorChild.y
            }

            val fallbackGap = 8.0
            var index = 0
            while (index < orderedParents.size) {
                if (hasChildrenByParentId[orderedParents[index].id] == true) {
                    index++
                    continue
                }

                val start = index
                while (
                    index + 1 < orderedParents.size &&
                    hasChildrenByParentId[orderedParents[index + 1].id] == false
                ) {
                    index++
                }
                val end = index

                val prevAnchorIndex = (start - 1 downTo 0).firstOrNull {
                    hasChildrenByParentId[orderedParents[it].id] == true
                }
                val nextAnchorIndex = (end + 1 until orderedParents.size).firstOrNull {
                    hasChildrenByParentId[orderedParents[it].id] == true
                }

                when {
                    prevAnchorIndex != null && nextAnchorIndex != null -> {
                        val runNodes = orderedParents.subList(start, end + 1)
                        val totalRunHeight = runNodes.sumOf { it.height } +
                            fallbackGap * (runNodes.size - 1).coerceAtLeast(0)

                        val prevAnchor = orderedParents[prevAnchorIndex]
                        val nextAnchor = orderedParents[nextAnchorIndex]
                        val minY = prevAnchor.y + prevAnchor.height + fallbackGap
                        val maxY = nextAnchor.y - totalRunHeight
                        var currentY = if (maxY >= minY) {
                            minY + (maxY - minY) / 2.0
                        } else {
                            minY
                        }

                        runNodes.forEach { node ->
                            node.y = currentY
                            currentY += node.height + fallbackGap
                        }
                    }
                    prevAnchorIndex != null -> {
                        var currentY =
                            orderedParents[prevAnchorIndex].y +
                                orderedParents[prevAnchorIndex].height +
                                fallbackGap
                        for (offset in start..end) {
                            val node = orderedParents[offset]
                            node.y = currentY
                            currentY += node.height + fallbackGap
                        }
                    }
                    nextAnchorIndex != null -> {
                        var currentTop = orderedParents[nextAnchorIndex].y
                        for (offset in end downTo start) {
                            val node = orderedParents[offset]
                            currentTop -= node.height
                            node.y = currentTop
                            currentTop -= fallbackGap
                        }
                    }
                    else -> {
                        // No anchored parents in this layer. Keep existing y positions.
                    }
                }

                index++
            }
        }

        // Single pass to partition nodes by type — replaces 10 full-list filterIsInstance scans.
        val byType = nodesById.values.groupBy { it::class }
        fun typed(cls: kotlin.reflect.KClass<out GraphNode>): List<GraphNode> = byType[cls].orEmpty()

        // Iceberg layers
        alignLayer(
            parents = typed(GraphNode.FileNode::class),
            upstreamFilter = { it is GraphNode.ManifestNode },
            childFilter = { it is GraphNode.RowNode }
        )
        alignLayer(
            parents = typed(GraphNode.ManifestNode::class),
            upstreamFilter = { it is GraphNode.SnapshotNode },
            childFilter = { it is GraphNode.FileNode }
        )
        alignLayer(
            parents = typed(GraphNode.SnapshotNode::class),
            upstreamFilter = { it is GraphNode.MetadataNode },
            childFilter = { it is GraphNode.ManifestNode }
        )
        alignLayer(
            parents = typed(GraphNode.MetadataNode::class),
            upstreamFilter = { it is GraphNode.TableNode },
            childFilter = { it is GraphNode.SnapshotNode }
        )
        alignLayer(
            parents = typed(GraphNode.TableNode::class),
            upstreamFilter = { false },
            childFilter = { it is GraphNode.MetadataNode || it is GraphNode.PaimonSnapshotNode }
        )

        // Paimon layers
        alignLayer(
            parents = typed(GraphNode.PaimonDataFileNode::class),
            upstreamFilter = { it is GraphNode.PaimonManifestNode },
            childFilter = { it is GraphNode.RowNode }
        )
        alignLayer(
            parents = typed(GraphNode.PaimonManifestNode::class),
            upstreamFilter = { it is GraphNode.PaimonManifestListNode },
            childFilter = { it is GraphNode.PaimonDataFileNode }
        )
        alignLayer(
            parents = typed(GraphNode.PaimonManifestListNode::class),
            upstreamFilter = { it is GraphNode.PaimonSnapshotNode },
            childFilter = { it is GraphNode.PaimonManifestNode }
        )
        alignLayer(
            parents = typed(GraphNode.PaimonSnapshotNode::class),
            upstreamFilter = { it is GraphNode.TableNode },
            childFilter = { it is GraphNode.PaimonManifestListNode }
        )
    }

    private fun preventOverlaps(nodesById: Map<String, GraphNode>) {
        fun preventOverlapsInLayer(nodes: List<GraphNode>, margin: Double = 8.0) {
            if (nodes.size < 2) return
            val sorted = nodes.sortedBy { it.y }
            for (i in 1 until sorted.size) {
                val prev = sorted[i - 1]
                val curr = sorted[i]
                val prevBottom = prev.y + prev.height
                val minAllowedY = prevBottom + margin
                if (curr.y < minAllowedY) {
                    curr.y = minAllowedY
                }
            }
        }

        // Single pass to partition by layer — replaces 11 full-list filterIsInstance scans. A
        // group node belongs to the layer of the siblings it stands for, so it takes part in
        // that layer's overlap check rather than floating in one of its own.
        val byLayer = nodesById.values.groupBy { node ->
            if (node is GraphNode.GroupNode) node.kind else node.aggregationKind()
        }
        fun layer(kind: AggregationKind): List<GraphNode> = byLayer[kind].orEmpty()

        preventOverlapsInLayer(nodesById.values.filterIsInstance<GraphNode.TableNode>())
        preventOverlapsInLayer(layer(AggregationKind.METADATA))
        preventOverlapsInLayer(layer(AggregationKind.SNAPSHOT))
        preventOverlapsInLayer(layer(AggregationKind.MANIFEST))
        preventOverlapsInLayer(layer(AggregationKind.FILE), margin = 2.0)
        preventOverlapsInLayer(layer(AggregationKind.ROW), margin = 2.0)
        // Paimon layers
        preventOverlapsInLayer(layer(AggregationKind.PAIMON_SNAPSHOT))
        preventOverlapsInLayer(layer(AggregationKind.PAIMON_SCHEMA))
        preventOverlapsInLayer(layer(AggregationKind.PAIMON_MANIFEST_LIST))
        preventOverlapsInLayer(layer(AggregationKind.PAIMON_MANIFEST))
        preventOverlapsInLayer(layer(AggregationKind.PAIMON_FILE), margin = 2.0)
    }

    /**
     * Gives each branch its own column inside the snapshot layer, and returns how much wider the
     * graph became.
     *
     * ELK lays one layer out at one x. That is right for every other layer here, where siblings
     * are genuinely interchangeable, and wrong for snapshots, where two of them being concurrent
     * is the thing a reader came to see. Rather than fight the layering — which would stretch the
     * graph by the length of the commit history, the same reason lineage edges are withheld from
     * ELK — the column is decided afterwards and the layers to the right are pushed over by the
     * width that took. Edges are routed from node positions, not from ELK's own sections, so
     * moving a node afterwards is not the lie it would be in a graph that drew ELK's splines.
     *
     * Nothing happens on a table without branches: every commit lands in track 0 and the function
     * returns before touching a node.
     */
    private fun spreadSnapshotBranches(nodesById: Map<String, GraphNode>): Double {
        val snapshots = nodesById.values.filterIsInstance<GraphNode.SnapshotNode>()
        if (snapshots.size < 2) return 0.0

        // One column for the layer is the assumption the shift rests on: lane zero is where the
        // layer sits, and every node to the right of it moves. A snapshot ELK put somewhere else
        // — one with no manifest list, so nothing pins it to this layer — would be dragged into
        // another layer's band by the arithmetic below, so the whole pass stands down instead.
        val layerX = snapshots.first().x
        if (snapshots.any { kotlin.math.abs(it.x - layerX) > 1.0 }) return 0.0

        val tracks = snapshotTracks(snapshots)
        val widest = tracks.values.maxOrNull() ?: 0
        if (widest == 0) return 0.0

        val pitch = snapshots.maxOf { it.width } + SNAPSHOT_TRACK_GUTTER
        val spread = widest * pitch
        nodesById.values.forEach { node ->
            when {
                node is GraphNode.SnapshotNode -> node.x = layerX + (tracks[node.id] ?: 0) * pitch
                node.x > layerX + 1.0 -> node.x += spread
            }
        }
        return spread
    }

    private fun createElkNode(parent: ElkNode, id: String, w: Double, h: Double): ElkNode {
        val node = ElkGraphUtil.createNode(parent)
        node.identifier = id
        node.setProperty(CoreOptions.NODE_SIZE_CONSTRAINTS, EnumSet.of(SizeConstraint.MINIMUM_SIZE))
        node.setProperty(CoreOptions.NODE_SIZE_MINIMUM, KVector(w, h))
        node.width = w
        node.height = h
        return node
    }
}
