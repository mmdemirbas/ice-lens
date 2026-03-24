package service

import model.*
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.util.*

private val logger = LoggerFactory.getLogger(PaimonGraphBuilder::class.java)

/**
 * Builds graph nodes and edges from a Paimon [PaimonUnifiedTableModel].
 *
 * Graph structure (left to right):
 * ```
 * TableNode -> PaimonSnapshotNode(s) -> PaimonManifestListNode("base"|"delta"|"changelog")
 *                                         -> PaimonManifestNode(s) -> PaimonDataFileNode(s) -> RowNode(s)
 *           -> PaimonSchemaNode(s)     (sibling edge from snapshots that reference them)
 * ```
 */
object PaimonGraphBuilder {

    /** Max data files shown per manifest in the graph. */
    private const val MAX_FILES_PER_MANIFEST = 10

    /** Max sample rows created per data file. */
    private const val MAX_ROWS_PER_FILE = 5

    /**
     * Builds graph nodes and edges for the given Paimon table model.
     * Does not perform layout — call [GraphLayoutService.layoutNodes] with the result.
     */
    fun buildGraph(
        tableModel: PaimonUnifiedTableModel,
        showRows: Boolean,
    ): GraphBuildResult {
        val logicalNodes = mutableMapOf<String, GraphNode>()
        val edges = mutableListOf<GraphEdge>()
        val edgeIds = mutableSetOf<String>()
        val processedManifests = mutableSetOf<String>()
        val tableNodeId = "table_root"

        val tableSummary = buildTableSummary(tableModel)
        logicalNodes[tableNodeId] = GraphNode.TableNode(tableNodeId, tableSummary)

        var nextManifestSimpleId = 1
        var nextFileSimpleId = 1
        var nextSnapshotSimpleId = 1
        var nextSchemaSimpleId = 1
        var nextErrorSimpleId = 1
        val seenErrorKeys = mutableSetOf<String>()

        fun addErrorNode(parentId: String, title: String, error: UnifiedReadError) {
            val dedupeKey = "$parentId|$title|${error.stage}|${error.path}|${error.message}"
            if (!seenErrorKeys.add(dedupeKey)) return
            val errorNodeId = "err_${nextErrorSimpleId++}_${parentId.hashCode()}_${error.stage.hashCode()}"
            if (logicalNodes.containsKey(errorNodeId)) return
            logicalNodes[errorNodeId] = GraphNode.ErrorNode(
                id = errorNodeId,
                title = title,
                stage = error.stage,
                path = error.path,
                message = error.message,
                stackTrace = error.stackTrace,
            )
            val errEdgeId = "e_err_${parentId}_$errorNodeId"
            edgeIds.add(errEdgeId)
            edges.add(GraphEdge(errEdgeId, parentId, errorNodeId))
        }

        // Table-level errors
        tableModel.readErrors.forEach { error ->
            addErrorNode(tableNodeId, "TABLE READ ERROR", error)
        }

        // Schema nodes
        val schemaNodeById = mutableMapOf<Int?, String>()
        tableModel.schemas.forEach { schema ->
            val sId = "pschema_${schema.id ?: nextSchemaSimpleId}"
            val simpleId = nextSchemaSimpleId++
            if (!logicalNodes.containsKey(sId)) {
                logicalNodes[sId] = GraphNode.PaimonSchemaNode(
                    id = sId,
                    data = schema,
                    simpleId = simpleId,
                )
                schemaNodeById[schema.id] = sId
            }
        }

        // Snapshot nodes
        tableModel.snapshots.forEach { unifiedSnapshot ->
            val snap = unifiedSnapshot.metadata
            val snapId = "psnap_${snap.id ?: nextSnapshotSimpleId}"
            val simpleId = nextSnapshotSimpleId++

            if (!logicalNodes.containsKey(snapId)) {
                logicalNodes[snapId] = GraphNode.PaimonSnapshotNode(
                    id = snapId,
                    data = snap,
                    simpleId = simpleId,
                    commitKind = snap.commitKind,
                    localPath = unifiedSnapshot.path.toString(),
                )
            }

            // Table -> Snapshot edge
            val tableEdgeId = "e_table_${tableNodeId}_to_$snapId"
            if (edgeIds.add(tableEdgeId)) {
                edges.add(GraphEdge(tableEdgeId, tableNodeId, snapId))
            }

            // Snapshot -> Schema sibling edge
            val schemaNodeId = schemaNodeById[snap.schemaId]
            if (schemaNodeId != null) {
                val schemaEdgeId = "e_schema_${snapId}_to_$schemaNodeId"
                if (edgeIds.add(schemaEdgeId)) {
                    edges.add(GraphEdge(schemaEdgeId, snapId, schemaNodeId, isSibling = true))
                }
            }

            // Snapshot-level errors
            unifiedSnapshot.readErrors.forEach { error ->
                addErrorNode(snapId, "SNAPSHOT READ ERROR", error)
            }

            // Manifest lists (base, delta, changelog)
            fun addManifestList(
                kind: String,
                manifests: List<PaimonUnifiedManifest>,
                parentId: String,
            ) {
                if (manifests.isEmpty()) return
                val mlId = "pml_${snap.id ?: "?"}_$kind"
                if (!logicalNodes.containsKey(mlId)) {
                    logicalNodes[mlId] = GraphNode.PaimonManifestListNode(
                        id = mlId,
                        kind = kind,
                        simpleId = simpleId,
                        localPath = snap.let {
                            when (kind) {
                                "base" -> it.baseManifestList
                                "delta" -> it.deltaManifestList
                                "changelog" -> it.changelogManifestList
                                else -> null
                            }
                        },
                    )
                }

                val mlEdgeId = "e_ml_${parentId}_to_$mlId"
                if (edgeIds.add(mlEdgeId)) {
                    edges.add(GraphEdge(mlEdgeId, parentId, mlId))
                }

                manifests.forEach { unifiedManifest ->
                    val manifestKey = unifiedManifest.metadata.fileName ?: UUID.randomUUID().toString()
                    val manId = "pman_${nextManifestSimpleId}"
                    val manSimpleId = nextManifestSimpleId++

                    if (!logicalNodes.containsKey(manId)) {
                        logicalNodes[manId] = GraphNode.PaimonManifestNode(
                            id = manId,
                            data = unifiedManifest.metadata,
                            simpleId = manSimpleId,
                            localPath = unifiedManifest.path.toString(),
                        )
                    }

                    val manEdgeId = "e_man_${mlId}_to_$manId"
                    if (edgeIds.add(manEdgeId)) {
                        edges.add(GraphEdge(manEdgeId, mlId, manId))
                    }

                    // Manifest-level errors
                    unifiedManifest.readErrors.forEach { error ->
                        addErrorNode(manId, "MANIFEST READ ERROR", error)
                    }

                    if (processedManifests.add(manifestKey)) {
                        unifiedManifest.entries.take(MAX_FILES_PER_MANIFEST).forEachIndexed { fileIndex, unifiedDataFile ->
                            val entry = unifiedDataFile.metadata
                            val fileSimpleId = nextFileSimpleId++
                            val fId = "pdf_${manId}_${fileSimpleId}_$fileIndex"

                            if (!logicalNodes.containsKey(fId)) {
                                logicalNodes[fId] = GraphNode.PaimonDataFileNode(
                                    id = fId,
                                    entry = entry,
                                    simpleId = fileSimpleId,
                                    bucket = entry.bucket,
                                    level = entry.file?.level,
                                    operationKind = entry.kind,
                                    localPath = unifiedDataFile.path.toString(),
                                )
                            }

                            val fileEdgeId = "e_file_${manId}_to_$fId"
                            edgeIds.add(fileEdgeId)
                            edges.add(GraphEdge(fileEdgeId, manId, fId))

                            // Row nodes (if showRows enabled)
                            if (showRows) {
                                val rawPath = unifiedDataFile.path.toString()
                                val localFile = File(rawPath)
                                if (localFile.exists()) {
                                    for (rIdx in 0 until MAX_ROWS_PER_FILE) {
                                        val rId = "row_${fId}_$rIdx"
                                        if (logicalNodes.containsKey(rId)) continue

                                        val capturedDataFile = unifiedDataFile
                                        val capturedSimpleId = fileSimpleId

                                        logicalNodes[rId] = GraphNode.RowNode(
                                            id = rId,
                                            data = mapOf("file_no" to capturedSimpleId, "row_idx" to rIdx),
                                            content = 0,
                                            dataLoader = {
                                                try {
                                                    val rows = capturedDataFile.rows
                                                    if (rIdx < rows.size) {
                                                        val rowData = rows[rIdx]
                                                        val enriched = mutableMapOf<String, Any>()
                                                        enriched["file_no"] = capturedSimpleId
                                                        enriched["row_idx"] = rIdx
                                                        enriched["local_file_path"] = capturedDataFile.path.toString()
                                                        enriched.putAll(rowData.cells)
                                                        enriched
                                                    } else emptyMap()
                                                } catch (e: Exception) {
                                                    logger.warn("Failed to load rows for file {}: {}", capturedDataFile.path, e.message)
                                                    emptyMap()
                                                }
                                            }
                                        )

                                        edgeIds.add("e_row_$rId")
                                        edges.add(GraphEdge("e_row_$rId", fId, rId))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            addManifestList("base", unifiedSnapshot.baseManifests, snapId)
            addManifestList("delta", unifiedSnapshot.deltaManifests, snapId)
            addManifestList("changelog", unifiedSnapshot.changelogManifests, snapId)
        }

        return GraphBuildResult(
            nodes = logicalNodes.values.toList(),
            edges = edges,
            summary = tableSummary,
        )
    }

    internal fun buildTableSummary(tableModel: PaimonUnifiedTableModel): TableSummary {
        var snapshotCount = 0
        var manifestCount = 0
        var dataFileCount = 0
        var totalRecordCount = 0L

        tableModel.snapshots.forEach { snapshot ->
            snapshotCount++
            val allManifests = snapshot.baseManifests + snapshot.deltaManifests + snapshot.changelogManifests
            allManifests.forEach { manifest ->
                manifestCount++
                manifest.entries.forEach { entry ->
                    dataFileCount++
                    totalRecordCount += entry.metadata.file?.rowCount ?: 0L
                }
            }
        }

        return TableSummary(
            tableName = tableModel.name,
            tablePath = tableModel.path.toString(),
            location = tableModel.path.toString(),
            tableUuid = null,
            formatVersion = null,
            currentSnapshotId = tableModel.snapshots.lastOrNull()?.metadata?.id,
            currentMetadataVersion = null,
            versionHintText = "",
            tableCreationMs = tableModel.snapshots.firstOrNull()?.metadata?.timeMillis,
            tableLastUpdateMs = tableModel.snapshots.lastOrNull()?.metadata?.timeMillis,
            lastUpdatedMs = tableModel.snapshots.lastOrNull()?.metadata?.timeMillis,
            metadataFileCount = 0,
            snapshotCount = snapshotCount,
            snapshotManifestListFileCount = snapshotCount,
            manifestCount = manifestCount,
            dataManifestCount = manifestCount,
            deleteManifestCount = 0,
            manifestEntryCount = dataFileCount,
            uniqueDataFileCount = dataFileCount,
            dataFileCount = dataFileCount,
            posDeleteFileCount = 0,
            eqDeleteFileCount = 0,
            totalRecordCount = totalRecordCount,
            metadataFileTimes = FileTimeRange(),
            snapshotManifestListFileTimes = FileTimeRange(),
            manifestFileTimes = FileTimeRange(),
            dataFileTimes = FileTimeRange(),
        )
    }
}
