package service

import androidx.compose.ui.geometry.Offset
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
        LayoutMetaDataService
            .getInstance()
            .registerLayoutMetaDataProviders(LayeredMetaDataProvider())
    }

    /** Max sample rows read from Parquet via DuckDB. */
    const val MAX_PARQUET_SAMPLE_ROWS = 50

    /**
     * Builds and lays out a graph for the given Iceberg table model.
     * Delegates graph construction to [IcebergGraphBuilder], then runs ELK layout
     * and post-processing.
     */
    fun layoutGraph(
        tableModel: UnifiedTableModel,
        showRows: Boolean,
    ): GraphModel {
        logger.debug("Building Iceberg graph for: {}", tableModel.name)
        val buildResult = IcebergGraphBuilder.buildGraph(tableModel, showRows)
        logger.debug("Iceberg graph built: {} nodes, {} edges", buildResult.nodes.size, buildResult.edges.size)
        return layoutNodes(buildResult.nodes, buildResult.edges)
    }

    /**
     * Builds and lays out a graph for the given Paimon table model.
     * Delegates graph construction to [PaimonGraphBuilder], then runs ELK layout
     * and post-processing.
     */
    fun layoutPaimonGraph(
        tableModel: PaimonUnifiedTableModel,
        showRows: Boolean,
    ): GraphModel {
        logger.debug("Building Paimon graph for: {}", tableModel.name)
        val buildResult = PaimonGraphBuilder.buildGraph(tableModel, showRows)
        logger.debug("Paimon graph built: {} nodes, {} edges", buildResult.nodes.size, buildResult.edges.size)
        return layoutNodes(buildResult.nodes, buildResult.edges)
    }

    /**
     * Positions pre-built nodes and edges using ELK layered layout, then applies
     * chronological ordering, parent alignment, and overlap prevention.
     */
    fun layoutNodes(
        nodes: List<GraphNode>,
        edges: List<GraphEdge>,
    ): GraphModel {
        val root = ElkGraphUtil.createGraph()
        root.setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered")
        root.setProperty(CoreOptions.DIRECTION, Direction.RIGHT)
        root.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.SPLINES)
        root.setProperty(CoreOptions.SPACING_NODE_NODE, 120.0)
        root.setProperty(
            org.eclipse.elk.alg.layered.options.LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS,
            300.0
        )

        // Create ELK nodes from logical nodes
        val elkNodes = mutableMapOf<String, ElkNode>()
        for (node in nodes) {
            elkNodes[node.id] = createElkNode(root, node.id, node.width, node.height)
        }

        // Create ELK edges
        for (edge in edges) {
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
        enforceChronologicalVerticalOrder(nodesById, edges)
        alignParentsWithChildren(nodesById, edges)
        preventOverlaps(nodesById)

        val posMap = finalNodes.associate { it.id to Offset(it.x.toFloat(), it.y.toFloat()) }
        return GraphModel(finalNodes, edges, root.width, root.height, initialPositions = posMap)
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

        val nonSiblingEdges = edges.filter { !it.isSibling }
        val childrenByParent = nonSiblingEdges.groupBy { it.fromId }
            .mapValues { (_, v) -> v.map { it.toId } }

        val metadataComparator = Comparator<GraphNode> { a, b ->
            val ma = a as? GraphNode.MetadataNode
            val mb = b as? GraphNode.MetadataNode
            val va = ma?.simpleId ?: Int.MAX_VALUE
            val vb = mb?.simpleId ?: Int.MAX_VALUE
            va.compareTo(vb)
        }

        val snapshotComparator = Comparator<GraphNode> { a, b ->
            val sa = (a as? GraphNode.SnapshotNode)?.data
            val sb = (b as? GraphNode.SnapshotNode)?.data
            compareValuesBy(sa, sb,
                { it?.timestampMs ?: Long.MAX_VALUE },
                { it?.sequenceNumber ?: Long.MAX_VALUE },
                { it?.snapshotId ?: Long.MAX_VALUE }
            )
        }

        val manifestComparator = Comparator<GraphNode> { a, b ->
            val ma = (a as? GraphNode.ManifestNode)?.data
            val mb = (b as? GraphNode.ManifestNode)?.data
            compareValuesBy(ma, mb,
                { it?.sequenceNumber ?: Int.MAX_VALUE },
                { it?.minSequenceNumber ?: Int.MAX_VALUE },
                { it?.addedSnapshotId ?: Long.MAX_VALUE },
                { IcebergGraphBuilder.manifestContentRank(it?.content) },
                { it?.manifestPath ?: "" }
            )
        }

        val fileComparator = Comparator<GraphNode> { a, b ->
            val fa = a as? GraphNode.FileNode
            val fb = b as? GraphNode.FileNode
            compareValuesBy(fa, fb,
                { it?.data?.dataSequenceNumber ?: it?.entry?.sequenceNumber ?: Long.MAX_VALUE },
                { it?.entry?.fileSequenceNumber ?: Long.MAX_VALUE },
                { IcebergGraphBuilder.contentRank(it?.data?.content) },
                { it?.entry?.status ?: Int.MAX_VALUE },
                { it?.data?.filePath ?: "" }
            )
        }

        val rowComparator = Comparator<GraphNode> { a, b ->
            val ra = (a as? GraphNode.RowNode)?.id ?: ""
            val rb = (b as? GraphNode.RowNode)?.id ?: ""
            ra.compareTo(rb)
        }

        fun parsePosition(data: Map<String, Any>): Int? {
            val raw = data["pos"] ?: data["position"] ?: return null
            return when (raw) {
                is Number -> raw.toInt()
                else -> raw.toString().toLongOrNull()?.toInt()
            }
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
        val paimonSnapshotComparator = Comparator<GraphNode> { a, b ->
            val sa = (a as? GraphNode.PaimonSnapshotNode)?.data
            val sb = (b as? GraphNode.PaimonSnapshotNode)?.data
            compareValuesBy(sa, sb,
                { it?.timeMillis ?: Long.MAX_VALUE },
                { it?.id ?: Long.MAX_VALUE }
            )
        }
        val paimonSnapshots = nodesById.values.filterIsInstance<GraphNode.PaimonSnapshotNode>()
        reorder(paimonSnapshots, paimonSnapshotComparator, minGap = 40.0)

        // Paimon: order manifest list nodes (base before delta before changelog)
        val manifestListKindRank = mapOf("base" to 0, "delta" to 1, "changelog" to 2)
        val paimonManifestListComparator = Comparator<GraphNode> { a, b ->
            val ka = (a as? GraphNode.PaimonManifestListNode)?.kind
            val kb = (b as? GraphNode.PaimonManifestListNode)?.kind
            (manifestListKindRank[ka] ?: 3).compareTo(manifestListKindRank[kb] ?: 3)
        }
        paimonSnapshots.forEach { parent ->
            val mlChildren = childrenByParent[parent.id].orEmpty()
                .mapNotNull { nodesById[it] as? GraphNode.PaimonManifestListNode }
            reorder(mlChildren, paimonManifestListComparator, minGap = 34.0)
        }

        // Paimon: order manifest nodes by simpleId
        val paimonManifestComparator = Comparator<GraphNode> { a, b ->
            val ma = (a as? GraphNode.PaimonManifestNode)?.simpleId ?: Int.MAX_VALUE
            val mb = (b as? GraphNode.PaimonManifestNode)?.simpleId ?: Int.MAX_VALUE
            ma.compareTo(mb)
        }
        nodesById.values.filterIsInstance<GraphNode.PaimonManifestListNode>().forEach { parent ->
            val manChildren = childrenByParent[parent.id].orEmpty()
                .mapNotNull { nodesById[it] as? GraphNode.PaimonManifestNode }
            reorder(manChildren, paimonManifestComparator, minGap = 34.0)
        }

        // Paimon: order data file nodes by simpleId
        val paimonFileComparator = Comparator<GraphNode> { a, b ->
            val fa = (a as? GraphNode.PaimonDataFileNode)?.simpleId ?: Int.MAX_VALUE
            val fb = (b as? GraphNode.PaimonDataFileNode)?.simpleId ?: Int.MAX_VALUE
            fa.compareTo(fb)
        }
        nodesById.values.filterIsInstance<GraphNode.PaimonManifestNode>().forEach { parent ->
            val fileChildren = childrenByParent[parent.id].orEmpty()
                .mapNotNull { nodesById[it] as? GraphNode.PaimonDataFileNode }
            reorder(fileChildren, paimonFileComparator, minGap = 30.0)
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
    }

    private fun alignParentsWithChildren(
        nodesById: Map<String, GraphNode>,
        edges: List<GraphEdge>,
    ) {
        val nonSiblingEdges = edges.filter { !it.isSibling }
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

        // Iceberg layers
        alignLayer(
            parents = nodesById.values.filterIsInstance<GraphNode.FileNode>(),
            upstreamFilter = { it is GraphNode.ManifestNode },
            childFilter = { it is GraphNode.RowNode }
        )
        alignLayer(
            parents = nodesById.values.filterIsInstance<GraphNode.ManifestNode>(),
            upstreamFilter = { it is GraphNode.SnapshotNode },
            childFilter = { it is GraphNode.FileNode }
        )
        alignLayer(
            parents = nodesById.values.filterIsInstance<GraphNode.SnapshotNode>(),
            upstreamFilter = { it is GraphNode.MetadataNode },
            childFilter = { it is GraphNode.ManifestNode }
        )
        alignLayer(
            parents = nodesById.values.filterIsInstance<GraphNode.MetadataNode>(),
            upstreamFilter = { it is GraphNode.TableNode },
            childFilter = { it is GraphNode.SnapshotNode }
        )
        alignLayer(
            parents = nodesById.values.filterIsInstance<GraphNode.TableNode>(),
            upstreamFilter = { false },
            childFilter = { it is GraphNode.MetadataNode || it is GraphNode.PaimonSnapshotNode }
        )

        // Paimon layers
        alignLayer(
            parents = nodesById.values.filterIsInstance<GraphNode.PaimonDataFileNode>(),
            upstreamFilter = { it is GraphNode.PaimonManifestNode },
            childFilter = { it is GraphNode.RowNode }
        )
        alignLayer(
            parents = nodesById.values.filterIsInstance<GraphNode.PaimonManifestNode>(),
            upstreamFilter = { it is GraphNode.PaimonManifestListNode },
            childFilter = { it is GraphNode.PaimonDataFileNode }
        )
        alignLayer(
            parents = nodesById.values.filterIsInstance<GraphNode.PaimonManifestListNode>(),
            upstreamFilter = { it is GraphNode.PaimonSnapshotNode },
            childFilter = { it is GraphNode.PaimonManifestNode }
        )
        alignLayer(
            parents = nodesById.values.filterIsInstance<GraphNode.PaimonSnapshotNode>(),
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

        preventOverlapsInLayer(nodesById.values.filterIsInstance<GraphNode.TableNode>())
        preventOverlapsInLayer(nodesById.values.filterIsInstance<GraphNode.MetadataNode>())
        preventOverlapsInLayer(nodesById.values.filterIsInstance<GraphNode.SnapshotNode>())
        preventOverlapsInLayer(nodesById.values.filterIsInstance<GraphNode.ManifestNode>())
        preventOverlapsInLayer(nodesById.values.filterIsInstance<GraphNode.FileNode>(), margin = 2.0)
        preventOverlapsInLayer(nodesById.values.filterIsInstance<GraphNode.RowNode>(), margin = 2.0)
        // Paimon layers
        preventOverlapsInLayer(nodesById.values.filterIsInstance<GraphNode.PaimonSnapshotNode>())
        preventOverlapsInLayer(nodesById.values.filterIsInstance<GraphNode.PaimonSchemaNode>())
        preventOverlapsInLayer(nodesById.values.filterIsInstance<GraphNode.PaimonManifestListNode>())
        preventOverlapsInLayer(nodesById.values.filterIsInstance<GraphNode.PaimonManifestNode>())
        preventOverlapsInLayer(nodesById.values.filterIsInstance<GraphNode.PaimonDataFileNode>(), margin = 2.0)
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
