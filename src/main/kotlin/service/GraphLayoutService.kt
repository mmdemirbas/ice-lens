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
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

object GraphLayoutService {

    init {
        // Register ELK Layered Algorithm manually for standalone usage
        LayoutMetaDataService
            .getInstance()
            .registerLayoutMetaDataProviders(LayeredMetaDataProvider())
    }

    fun layoutGraph(
        tableModel: UnifiedTableModel,
        showRows: Boolean,
    ): GraphModel {
        val root = ElkGraphUtil.createGraph()

        // 1. Shift to Rightward Flow and enforce column spacing
        root.setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered")
        root.setProperty(CoreOptions.DIRECTION, Direction.RIGHT)
        root.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.SPLINES)
        root.setProperty(CoreOptions.SPACING_NODE_NODE, 120.0)
        root.setProperty(
            org.eclipse.elk.alg.layered.options.LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS,
            300.0
        )

        val elkNodes = mutableMapOf<String, ElkNode>()
        val logicalNodes = mutableMapOf<String, GraphNode>()
        val edges = mutableListOf<GraphEdge>()
        val edgeIds = mutableSetOf<String>()
        val processedManifests = mutableSetOf<String>()
        val tableNodeId = "table_root"

        val tableSummary = buildTableSummary(tableModel)
        elkNodes[tableNodeId] = createElkNode(root, tableNodeId, 240.0, 96.0)
        logicalNodes[tableNodeId] = GraphNode.TableNode(tableNodeId, tableSummary)

        // Registry to map long Iceberg paths to simple IDs
        var nextFileId = 1
        val filePathToSimpleId = mutableMapOf<String, Int>()
        var nextMetadataSimpleId = 1
        var nextSnapshotSimpleId = 1
        var nextManifestSimpleId = 1
        var nextErrorSimpleId = 1
        val seenErrorKeys = mutableSetOf<String>()

        fun registerFilePathAlias(path: String, simpleId: Int) {
            val trimmed = path.trim()
            if (trimmed.isEmpty()) return
            filePathToSimpleId.putIfAbsent(trimmed, simpleId)
            filePathToSimpleId.putIfAbsent(normalizeFilePath(trimmed), simpleId)
        }

        fun assignSimpleId(path: String): Int {
            val trimmed = path.trim()
            val normalized = normalizeFilePath(trimmed)
            val existing = filePathToSimpleId[trimmed] ?: filePathToSimpleId[normalized]
            if (existing != null) {
                registerFilePathAlias(trimmed, existing)
                return existing
            }
            val simpleId = nextFileId++
            registerFilePathAlias(trimmed, simpleId)
            return simpleId
        }

        fun identifierFieldNamesForSchema(metadata: TableMetadata, schemaId: Int?): List<String> {
            val schema = metadata.schemas
                .firstOrNull { it.schemaId == schemaId }
                ?: metadata.schemas.firstOrNull { it.schemaId == metadata.currentSchemaId }
                ?: metadata.schemas.firstOrNull()
                ?: return emptyList()
            val identifierIds = schema.identifierFieldIds.toSet()
            if (identifierIds.isEmpty()) return emptyList()
            return schema.fields
                .filter { field -> (field.id ?: -1) in identifierIds }
                .mapNotNull { field -> field.name }
        }

        fun addErrorNode(parentId: String, title: String, error: UnifiedReadError) {
            val dedupeKey = "$parentId|$title|${error.stage}|${error.path}|${error.message}"
            if (!seenErrorKeys.add(dedupeKey)) return
            val errorNodeId = "err_${nextErrorSimpleId++}_${parentId.hashCode()}_${error.stage.hashCode()}"
            if (elkNodes.containsKey(errorNodeId)) return
            elkNodes[errorNodeId] = createElkNode(root, errorNodeId, 280.0, 100.0)
            logicalNodes[errorNodeId] = GraphNode.ErrorNode(
                id = errorNodeId,
                title = title,
                stage = error.stage,
                path = error.path,
                message = error.message,
                stackTrace = error.stackTrace,
            )
            val errEdgeId = "e_err_${parentId}_$errorNodeId"
            ElkGraphUtil.createSimpleEdge(elkNodes[parentId], elkNodes[errorNodeId])
            edgeIds.add(errEdgeId)
            edges.add(GraphEdge(errEdgeId, parentId, errorNodeId))
        }

        tableModel.readErrors.forEach { error ->
            addErrorNode(tableNodeId, "TABLE READ ERROR", error)
        }

        // Assign simple IDs for all file paths upfront so delete rows can always resolve target file IDs.
        tableModel.metadatas.forEach { metadata ->
            val sortedSnapshots = metadata.snapshots.sortedWith(unifiedSnapshotComparator)
            sortedSnapshots.forEach { snapshot ->
                val manifests = snapshot.manifestLists.sortedWith(unifiedManifestComparator)
                manifests.forEach { unifiedManifest ->
                    val fallbackSeq = unifiedManifest.metadata.sequenceNumber?.toLong() ?: Long.MAX_VALUE
                    val unifiedDataFiles = unifiedManifest.manifests.sortedWith(unifiedDataFileComparator(fallbackSeq))
                    unifiedDataFiles.forEach { unifiedDataFile ->
                        val rawPath = unifiedDataFile.metadata.dataFile?.filePath.orEmpty()
                        assignSimpleId(rawPath)
                    }
                }
            }
        }

        tableModel.metadatas.forEach { metadata ->
            val fileName = metadata.path.fileName.toString()
            val meta = metadata.metadata
            val mId = "meta_${fileName}"
            if (!elkNodes.containsKey(mId)) {
                val simpleMetadataId = nextMetadataSimpleId++
                elkNodes[mId] = createElkNode(root, mId, 240.0, 96.0)
                logicalNodes[mId] = GraphNode.MetadataNode(
                    id = mId,
                    simpleId = simpleMetadataId,
                    fileName = fileName,
                    data = meta,
                    localPath = metadata.path.toString(),
                    rawJson = metadata.rawJson
                )
            }
            val tableEdgeId = "e_table_${tableNodeId}_to_$mId"
            if (edgeIds.add(tableEdgeId)) {
                ElkGraphUtil.createSimpleEdge(elkNodes[tableNodeId], elkNodes[mId])
                edges.add(GraphEdge(tableEdgeId, tableNodeId, mId))
            }

            val sortedSnapshots = metadata.snapshots.sortedWith(unifiedSnapshotComparator)
            sortedSnapshots.forEach { snapshot ->
                val snap = snapshot.metadata
                val snapshotIdentifierFields = identifierFieldNamesForSchema(meta, snap.schemaId)
                val sId = "snap_${snap.snapshotId}"
                if (!elkNodes.containsKey(sId)) {
                    val simpleSnapshotId = nextSnapshotSimpleId++
                    elkNodes[sId] = createElkNode(root, sId, 210.0, 84.0)
                    logicalNodes[sId] = GraphNode.SnapshotNode(
                        id = sId,
                        data = snap,
                        simpleId = simpleSnapshotId,
                        localPath = snapshot.path.toString()
                    )
                }
                snapshot.readErrors.forEach { error ->
                    addErrorNode(sId, "SNAPSHOT READ ERROR", error)
                }

                val snapEdgeId = "e_snap_${mId}_to_$sId"
                if (edgeIds.add(snapEdgeId)) {
                    ElkGraphUtil.createSimpleEdge(elkNodes[mId], elkNodes[sId])
                    edges.add(GraphEdge(snapEdgeId, mId, sId))
                }

                val manifests = snapshot.manifestLists.sortedWith(unifiedManifestComparator)
                manifests.forEach { unifiedManifest ->
                    val manifest = unifiedManifest.metadata
                    val rawManPath = manifest.manifestPath ?: "unknown_${UUID.randomUUID()}"
                    val manId = "man_${rawManPath.hashCode()}"
                    if (!elkNodes.containsKey(manId)) {
                        val simpleManifestId = nextManifestSimpleId++
                        elkNodes[manId] = createElkNode(root, manId, 200.0, 80.0)
                        logicalNodes[manId] = GraphNode.ManifestNode(
                            id = manId,
                            data = manifest,
                            simpleId = simpleManifestId,
                            localPath = unifiedManifest.path.toString()
                        )
                    }
                    unifiedManifest.readErrors.forEach { error ->
                        addErrorNode(manId, "MANIFEST READ ERROR", error)
                    }

                    val manEdgeId = "e_man_${sId}_to_$manId"
                    if (edgeIds.add(manEdgeId)) {
                        ElkGraphUtil.createSimpleEdge(elkNodes[sId], elkNodes[manId])
                        edges.add(GraphEdge(manEdgeId, sId, manId))
                    }

                    if (processedManifests.add(manId)) {
                        val manifestPath = manifest.manifestPath
                        if (manifestPath != null) {
                            val fallbackSeq = manifest.sequenceNumber?.toLong() ?: Long.MAX_VALUE
                            val unifiedDataFiles = unifiedManifest.manifests.sortedWith(unifiedDataFileComparator(fallbackSeq))
                            unifiedDataFiles.take(10).forEachIndexed { fileIndex, unifiedDataFile ->
                                val entry = unifiedDataFile.metadata
                                val dataFile = entry.dataFile ?: DataFile(filePath = "unknown")
                                val rawPath = dataFile.filePath.orEmpty()
                                val simpleId = assignSimpleId(rawPath)
                                // Keep logical file number stable by file path, but node IDs unique per manifest entry
                                // so vertical order can follow manifest timeline without cross-manifest conflicts.
                                val fId = "file_${manId}_${simpleId}_$fileIndex"

                                if (!elkNodes.containsKey(fId)) {
                                    elkNodes[fId] = createElkNode(root, fId, 200.0, 60.0)
                                    logicalNodes[fId] = GraphNode.FileNode(
                                        id = fId,
                                        entry = entry,
                                        simpleId = simpleId,
                                        localPath = unifiedDataFile.path.toString()
                                    )
                                }

                                val edgeId = "e_file_${manId}_to_$fId"
                                edgeIds.add(edgeId)
                                ElkGraphUtil.createSimpleEdge(elkNodes[manId], elkNodes[fId])
                                edges.add(GraphEdge(edgeId, manId, fId))

                                if (showRows) {
                                    val pathWithoutScheme =
                                        if (rawPath.startsWith("file:")) URI(rawPath).path else rawPath
                                    val tableMarker = "/${tableModel.name}/"

                                    val localFile = if (pathWithoutScheme.contains(tableMarker)) {
                                        val relativePart = pathWithoutScheme.substringAfter(tableMarker)
                                        File("${tableModel.path}/$relativePart")
                                    } else {
                                        File(pathWithoutScheme)
                                    }

                                    if (localFile.exists()) {
                                        val contentType = entry.dataFile?.content ?: 0
                                        val maxRows = 5
                                        // Create placeholder row nodes; actual data loads lazily on first access
                                        for (rIdx in 0 until maxRows) {
                                            val rId = "row_${fId}_$rIdx"
                                            if (elkNodes.containsKey(rId)) continue
                                            elkNodes[rId] = createElkNode(root, rId, 200.0, 80.0)

                                            // Capture values for lazy loader
                                            val capturedDataFile = unifiedDataFile
                                            val capturedSimpleId = simpleId
                                            val capturedFilePathMap = filePathToSimpleId
                                            val capturedIdentifierFields = snapshotIdentifierFields

                                            logicalNodes[rId] = GraphNode.RowNode(
                                                id = rId,
                                                data = mapOf("file_no" to capturedSimpleId, "row_idx" to rIdx),
                                                content = contentType,
                                                identifierFields = capturedIdentifierFields,
                                                dataLoader = {
                                                    try {
                                                        val rows = capturedDataFile.rows
                                                        if (rIdx < rows.size) {
                                                            val rowData = rows[rIdx]
                                                            val enriched = mutableMapOf<String, Any>()
                                                            enriched["file_no"] = capturedSimpleId
                                                            enriched["row_idx"] = rIdx
                                                            enriched["local_file_path"] = capturedDataFile.path.toString()
                                                            if (contentType > 0 && rowData.cells.containsKey("file_path")) {
                                                                val targetPath = rowData.cells["file_path"].toString()
                                                                val targetId = capturedFilePathMap[targetPath]
                                                                    ?: capturedFilePathMap[normalizeFilePath(targetPath)]
                                                                    ?: "?"
                                                                enriched["target_file"] = "File $targetId"
                                                                enriched["target_file_no"] = targetId
                                                            }
                                                            enriched.putAll(rowData.cells)
                                                            enriched
                                                        } else emptyMap()
                                                    } catch (_: Exception) { emptyMap() }
                                                }
                                            )

                                            ElkGraphUtil.createSimpleEdge(elkNodes[fId], elkNodes[rId])
                                            edgeIds.add("e_row_$rId")
                                            edges.add(GraphEdge("e_row_$rId", fId, rId))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Run Layout Engine
        val engine = RecursiveGraphLayoutEngine()
        engine.layout(root, BasicProgressMonitor())

        // Sync calculated ELK coordinates back to the node models
        val finalNodes = logicalNodes.values.map { node ->
            val elkNode = elkNodes[node.id]
            if (elkNode != null) {
                node.x = elkNode.x
                node.y = elkNode.y
            }
            node
        }

        enforceChronologicalVerticalOrder(logicalNodes, edges)
        alignParentsWithChildren(logicalNodes, edges)
        preventOverlaps(logicalNodes)

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
                { manifestContentRank(it?.content) },
                { it?.manifestPath ?: "" }
            )
        }

        val fileComparator = Comparator<GraphNode> { a, b ->
            val fa = a as? GraphNode.FileNode
            val fb = b as? GraphNode.FileNode
            compareValuesBy(fa, fb,
                { it?.data?.dataSequenceNumber ?: it?.entry?.sequenceNumber ?: Long.MAX_VALUE },
                { it?.entry?.fileSequenceNumber ?: Long.MAX_VALUE },
                { contentRank(it?.data?.content) },
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

        // File nodes must follow manifest timeline globally (top->bottom), not just inside each manifest.
        // Build desired order as: manifests ordered by y, then each manifest's files by fileComparator.
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

        // Row nodes must follow file timeline globally (top->bottom), not just inside each file.
        // Group rows by snapshot to prevent cross-snapshot interleaving.
        val orderedFiles = nodesById.values
            .filterIsInstance<GraphNode.FileNode>()
            .sortedBy { it.y }

        // Build mapping of row -> parent file -> snapshot
        val rowParentFileByRowId = orderedFiles.flatMap { fileNode ->
            childrenByParent[fileNode.id].orEmpty().map { rowId -> rowId to fileNode }
        }.toMap()

        // Build data rows index by (normalized_path, snapshotId) for position delete targeting
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

        // Group files by snapshot for strict snapshot isolation
        val filesBySnapshot = orderedFiles.groupBy { it.entry.snapshotId }
        val snapshotsInOrder = orderedFiles.map { it.entry.snapshotId }.distinct()

        // Build final row order with snapshot isolation
        val adjustedRowOrder = mutableListOf<GraphNode.RowNode>()

        snapshotsInOrder.forEach { snapshotId ->
            val snapshotFiles = filesBySnapshot[snapshotId] ?: return@forEach

            // Separate rows by content type within this snapshot
            val eqDeleteRows = mutableListOf<GraphNode.RowNode>()
            val dataRows = mutableListOf<GraphNode.RowNode>()
            val posDeleteRows = mutableListOf<GraphNode.RowNode>()

            snapshotFiles.forEach { fileNode ->
                val contentType = fileNode.data.content ?: 0
                val rows = childrenByParent[fileNode.id].orEmpty()
                    .mapNotNull { nodesById[it] as? GraphNode.RowNode }
                    .sortedWith(rowComparator)

                when (contentType) {
                    2 -> eqDeleteRows.addAll(rows)  // Equality deletes
                    0 -> dataRows.addAll(rows)      // Data rows
                    1 -> posDeleteRows.addAll(rows) // Position deletes
                }
            }

            // Add equality deletes first
            adjustedRowOrder.addAll(eqDeleteRows)

            // Add data rows
            adjustedRowOrder.addAll(dataRows)

            // Position deletes: insert after their target data row
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

            // Insert position deletes after their anchor rows (O(n) index lookup)
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

        // Assign Y positions
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

                // Prefer first exclusive child among siblings; if none are exclusive, use first child.
                val anchorChild = children.firstOrNull { it.id !in siblingChildIds } ?: children.first()
                parent.y = anchorChild.y
            }

            // Fallback for parents with no children:
            // preserve parent order by positioning runs of unanchored parents around nearby anchors.
            // This prevents empty metadata versions (e.g. v1 without snapshots) from collapsing onto v2.
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

        // Child -> parent layering order
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
            childFilter = { it is GraphNode.MetadataNode }
        )
    }

    private fun preventOverlaps(nodesById: Map<String, GraphNode>) {
        // Process each layer independently to prevent overlaps
        // Layers are determined by node types and their natural left-to-right order

        fun preventOverlapsInLayer(nodes: List<GraphNode>, margin: Double = 8.0) {
            if (nodes.size < 2) return

            // Sort by current Y position
            val sorted = nodes.sortedBy { it.y }

            // Push down overlapping nodes
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

        // Process each node type as a separate layer
        preventOverlapsInLayer(nodesById.values.filterIsInstance<GraphNode.TableNode>())
        preventOverlapsInLayer(nodesById.values.filterIsInstance<GraphNode.MetadataNode>())
        preventOverlapsInLayer(nodesById.values.filterIsInstance<GraphNode.SnapshotNode>())
        preventOverlapsInLayer(nodesById.values.filterIsInstance<GraphNode.ManifestNode>())
        preventOverlapsInLayer(nodesById.values.filterIsInstance<GraphNode.FileNode>(), margin = 2.0)
        preventOverlapsInLayer(nodesById.values.filterIsInstance<GraphNode.RowNode>(), margin = 2.0)
    }

    private class FileTimeAccumulator {
        private var knownCount: Int = 0
        private var missingCount: Int = 0
        private var oldestMs: Long? = null
        private var newestMs: Long? = null

        fun add(timestampMs: Long?) {
            if (timestampMs == null) {
                missingCount++
                return
            }
            knownCount++
            oldestMs = if (oldestMs == null) timestampMs else minOf(oldestMs!!, timestampMs)
            newestMs = if (newestMs == null) timestampMs else maxOf(newestMs!!, timestampMs)
        }

        fun asRange(): FileTimeRange = FileTimeRange(
            knownCount = knownCount,
            missingCount = missingCount,
            oldestMs = oldestMs,
            newestMs = newestMs
        )
    }


    private fun fileLastModifiedMs(path: Path, cache: MutableMap<String, Long?>): Long? {
        val key = runCatching { path.toAbsolutePath().normalize().toString() }.getOrElse { path.toString() }
        return cache.getOrPut(key) {
            runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrNull()
        }
    }

    private fun buildTableSummary(tableModel: UnifiedTableModel): TableSummary {
        val mtimeCache = mutableMapOf<String, Long?>()
        val metadataTimes = FileTimeAccumulator()
        val snapshotManifestListTimes = FileTimeAccumulator()
        val manifestTimes = FileTimeAccumulator()
        val dataFileTimes = FileTimeAccumulator()

        val uniqueSnapshotKeys = mutableSetOf<String>()
        val uniqueSnapshotManifestListKeys = mutableSetOf<String>()
        val uniqueManifestKeys = mutableSetOf<String>()
        val uniqueDataFileKeys = mutableSetOf<String>()

        var dataManifestCount = 0
        var deleteManifestCount = 0
        var manifestEntryCount = 0
        var dataFileCount = 0
        var posDeleteFileCount = 0
        var eqDeleteFileCount = 0
        var totalRecordCount = 0L

        val metadataVersions = tableModel.metadatas.map { unifiedMetadata ->
            val fileName = unifiedMetadata.path.fileName.toString()
            val fileModifiedMs = fileLastModifiedMs(unifiedMetadata.path, mtimeCache)
            metadataTimes.add(fileModifiedMs)
            MetadataVersionInfo(
                fileName = fileName,
                version = metadataVersionFromFileName(fileName),
                fileLastModifiedMs = fileModifiedMs,
                metadataLastUpdatedMs = unifiedMetadata.metadata.lastUpdatedMs,
                snapshotCount = unifiedMetadata.metadata.snapshots.size,
                currentSnapshotId = unifiedMetadata.metadata.currentSnapshotId
            )
        }

        val inferredTimelineMs = metadataVersions
            .mapNotNull { version -> version.metadataLastUpdatedMs ?: version.fileLastModifiedMs }
        val inferredTableCreationMs = inferredTimelineMs.minOrNull()
        val inferredTableLastUpdateMs = inferredTimelineMs.maxOrNull()

        tableModel.metadatas.forEach { unifiedMetadata ->
            unifiedMetadata.snapshots.forEach { unifiedSnapshot ->
                val snapshotMeta = unifiedSnapshot.metadata
                val snapshotKey = snapshotMeta.snapshotId?.toString() ?: "path:${unifiedSnapshot.path}"
                uniqueSnapshotKeys.add(snapshotKey)
                val snapshotManifestListKey = snapshotMeta.manifestList
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::normalizeFilePath)
                    ?: "path:${unifiedSnapshot.path}"
                if (uniqueSnapshotManifestListKeys.add(snapshotManifestListKey)) {
                    snapshotManifestListTimes.add(fileLastModifiedMs(unifiedSnapshot.path, mtimeCache))
                }

                unifiedSnapshot.manifestLists.forEach { unifiedManifest ->
                    val manifestMeta = unifiedManifest.metadata
                    val manifestKey = manifestMeta.manifestPath
                        ?.takeIf { it.isNotBlank() }
                        ?: "path:${unifiedManifest.path}"

                    if (uniqueManifestKeys.add(manifestKey)) {
                        if (manifestMeta.content == 1) {
                            deleteManifestCount++
                        } else {
                            dataManifestCount++
                        }
                        manifestTimes.add(fileLastModifiedMs(unifiedManifest.path, mtimeCache))
                    }

                    unifiedManifest.manifests.forEach { unifiedDataFile ->
                        val entry = unifiedDataFile.metadata
                        val dataFile = entry.dataFile

                        manifestEntryCount++
                        when (dataFile?.content ?: 0) {
                            1 -> posDeleteFileCount++
                            2 -> eqDeleteFileCount++
                            else -> dataFileCount++
                        }
                        totalRecordCount += dataFile?.recordCount ?: 0L

                        val dataFileKey = dataFile?.filePath
                            ?.takeIf { it.isNotBlank() }
                            ?.let(::normalizeFilePath)
                            ?: "path:${unifiedDataFile.path}"
                        if (uniqueDataFileKeys.add(dataFileKey)) {
                            dataFileTimes.add(fileLastModifiedMs(unifiedDataFile.path, mtimeCache))
                        }
                    }
                }
            }
        }

        val latestMetadataInfo = tableModel.metadatas.lastOrNull()
        val latestMetadata = latestMetadataInfo?.metadata
        val latestMetadataFileName = latestMetadataInfo?.path?.fileName?.toString().orEmpty()
        val currentMetadataVersion = metadataVersionFromFileName(latestMetadataFileName)
        val latestLastUpdatedMs = latestMetadata?.lastUpdatedMs
            ?: tableModel.metadatas.mapNotNull { it.metadata.lastUpdatedMs }.maxOrNull()
            ?: inferredTableLastUpdateMs

        val location = latestMetadata?.location
            ?: tableModel.metadatas.asReversed().firstNotNullOfOrNull { it.metadata.location }

        return TableSummary(
            tableName = tableModel.name,
            tablePath = tableModel.path.toString(),
            location = location,
            tableUuid = latestMetadata?.tableUuid,
            formatVersion = latestMetadata?.formatVersion,
            currentSnapshotId = latestMetadata?.currentSnapshotId,
            currentMetadataVersion = currentMetadataVersion,
            versionHintText = tableModel.versionHint,
            tableCreationMs = inferredTableCreationMs,
            tableLastUpdateMs = inferredTableLastUpdateMs,
            lastUpdatedMs = latestLastUpdatedMs,
            metadataFileCount = tableModel.metadatas.size,
            snapshotCount = uniqueSnapshotKeys.size,
            snapshotManifestListFileCount = uniqueSnapshotManifestListKeys.size,
            manifestCount = uniqueManifestKeys.size,
            dataManifestCount = dataManifestCount,
            deleteManifestCount = deleteManifestCount,
            manifestEntryCount = manifestEntryCount,
            uniqueDataFileCount = uniqueDataFileKeys.size,
            dataFileCount = dataFileCount,
            posDeleteFileCount = posDeleteFileCount,
            eqDeleteFileCount = eqDeleteFileCount,
            totalRecordCount = totalRecordCount,
            metadataFileTimes = metadataTimes.asRange(),
            snapshotManifestListFileTimes = snapshotManifestListTimes.asRange(),
            manifestFileTimes = manifestTimes.asRange(),
            dataFileTimes = dataFileTimes.asRange(),
            metadataVersions = metadataVersions
        )
    }

    private val unifiedSnapshotComparator: Comparator<UnifiedSnapshot> = compareBy(
        { it.metadata.timestampMs ?: Long.MAX_VALUE },
        { it.metadata.sequenceNumber ?: Long.MAX_VALUE },
        { it.metadata.snapshotId ?: Long.MAX_VALUE },
    )

    private val unifiedManifestComparator: Comparator<UnifiedManifest> = compareBy(
        { it.metadata.sequenceNumber ?: Int.MAX_VALUE },
        { it.metadata.minSequenceNumber ?: Int.MAX_VALUE },
        { it.metadata.addedSnapshotId ?: Long.MAX_VALUE },
        { manifestContentRank(it.metadata.content) },
        { it.metadata.manifestPath ?: "" },
    )

    private fun unifiedDataFileComparator(fallbackSequenceNumber: Long): Comparator<UnifiedDataFile> = compareBy(
        { it.metadata.dataFile?.dataSequenceNumber ?: it.metadata.sequenceNumber ?: fallbackSequenceNumber },
        { it.metadata.fileSequenceNumber ?: Long.MAX_VALUE },
        { contentRank(it.metadata.dataFile?.content) },
        { it.metadata.status },
        { it.metadata.dataFile?.filePath ?: "" },
    )

    private fun contentRank(content: Int?): Int = when (content ?: 0) {
        2 -> 0 // Equality deletes first
        0 -> 1 // Data files second
        1 -> 2 // Position deletes last
        else -> 3
    }

    private fun manifestContentRank(content: Int?): Int = when (content ?: 0) {
        1 -> 0 // Delete manifest first
        0 -> 1 // Data manifest second
        else -> 2
    }

    private fun createElkNode(parent: ElkNode, id: String, w: Double, h: Double): ElkNode {
        val node = ElkGraphUtil.createNode(parent)
        node.identifier = id

        // Lock the dimensions so ELK reserves the exact bounding box size during routing
        node.setProperty(CoreOptions.NODE_SIZE_CONSTRAINTS, EnumSet.of(SizeConstraint.MINIMUM_SIZE))
        node.setProperty(CoreOptions.NODE_SIZE_MINIMUM, KVector(w, h))

        node.width = w
        node.height = h
        return node
    }
}
