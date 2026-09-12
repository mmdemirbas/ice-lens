package service

import model.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.UUID

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

    /** Max sample rows created per data file. */
    private const val MAX_ROWS_PER_FILE = 5

    /**
     * Builds graph nodes and edges for the given Paimon table model.
     *
     * Every data file gets a node; [GraphAggregation] decides how many are drawn, and sample
     * rows come back as factories for the same reason — see [GraphBuildResult.sampleRows]. Does
     * not perform layout; call [GraphLayoutService.layoutGraph] (the entry point that dispatches
     * by format) with the result.
     */
    fun buildGraph(tableModel: PaimonUnifiedTableModel): GraphBuildResult {
        val logicalNodes = mutableMapOf<String, GraphNode>()
        val sampleRows = mutableMapOf<String, () -> List<GraphNode.RowNode>>()
        val edges = mutableListOf<GraphEdge>()
        val edgeIds = mutableSetOf<String>()
        val manifestPathToId = mutableMapOf<String, String>()
        val processedManifestEntries = mutableSetOf<String>()
        val tableNodeId = "table_root"

        val tableSummary = buildTableSummary(tableModel)
        logicalNodes[tableNodeId] = GraphNode.TableNode(
            tableNodeId,
            tableSummary,
            unreferencedFiles = DeferredRead.of { findUnreferencedFiles(tableModel) },
        )

        var nextManifestSimpleId = 1
        var nextManifestListSimpleId = 1
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

        // Snapshot nodes: what snapshot/ holds, then what only a tag still holds — a snapshot
        // expiry removed whose files a tag keeps on disk. Drawn in id order so the tagged one
        // sits where it was committed rather than after everything.
        val liveIds = tableModel.snapshots.mapNotNull { it.metadata.id }.toSet()
        (tableModel.snapshots + tableModel.tagOnlySnapshots)
            .sortedBy { it.metadata.id ?: Long.MAX_VALUE }
            .forEach { unifiedSnapshot ->
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
                    // Deferred: replaying a snapshot's base and delta is work only a comparison
                    // asks for, and it asks about two of them.
                    liveFilesLoader = DeferredRead.of { paimonLiveFilesOf(unifiedSnapshot) },
                    indexFiles = unifiedSnapshot.indexFiles,
                    statistics = unifiedSnapshot.statistics,
                    tags = snap.id?.let { tableModel.tagNamesBySnapshotId[it] }.orEmpty(),
                    retainedByTagOnly = snap.id !in liveIds,
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
                        simpleId = nextManifestListSimpleId++,
                        manifestCount = manifests.size,
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
                    val existingId = manifestPathToId[manifestKey]
                    val manId: String
                    if (existingId != null) {
                        manId = existingId
                    } else {
                        val manSimpleId = nextManifestSimpleId++
                        manId = "pman_${manSimpleId}"
                        manifestPathToId[manifestKey] = manId
                        logicalNodes[manId] = GraphNode.PaimonManifestNode(
                            id = manId,
                            data = unifiedManifest.metadata,
                            simpleId = manSimpleId,
                            // Numbered for every entry, not just the ones the graph draws, so
                            // the inspector can list the whole manifest.
                            entries = unifiedManifest.entries.map { unifiedDataFile ->
                                PaimonManifestEntryView(
                                    simpleId = nextFileSimpleId++,
                                    entry = unifiedDataFile.metadata,
                                    localPath = unifiedDataFile.path.toString(),
                                )
                            },
                            localPath = unifiedManifest.path.toString(),
                            // The snapshot, not the manifest, because the trace is a replay of
                            // everything ahead of this manifest as well as of it.
                            replayTrace = DeferredRead.of {
                                replayPaimonSnapshot(
                                    unifiedSnapshot,
                                    traceFor = paimonManifestKey(unifiedManifest),
                                ).trace
                            },
                        )
                    }
                    val manifestEntryViews =
                        (logicalNodes[manId] as? GraphNode.PaimonManifestNode)?.entries.orEmpty()

                    val manEdgeId = "e_man_${mlId}_to_$manId"
                    if (edgeIds.add(manEdgeId)) {
                        edges.add(GraphEdge(manEdgeId, mlId, manId))
                    }

                    // Manifest-level errors (reported once per unique manifest)
                    unifiedManifest.readErrors.forEach { error ->
                        addErrorNode(manId, "MANIFEST READ ERROR", error)
                    }

                    if (processedManifestEntries.add(manifestKey)) {
                        unifiedManifest.entries.forEachIndexed { fileIndex, unifiedDataFile ->
                            val entry = unifiedDataFile.metadata
                            val fileSimpleId = manifestEntryViews.getOrNull(fileIndex)?.simpleId ?: (fileIndex + 1)
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

                            sampleRows[fId] = sampleRowFactory(fId, unifiedDataFile, fileSimpleId)
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
            sampleRows = sampleRows,
        )
    }

    /** The sample rows for one data file, read only if the graph draws that file. */
    private fun sampleRowFactory(
        fileNodeId: String,
        dataFile: PaimonUnifiedDataFile,
        simpleId: Int,
    ): () -> List<GraphNode.RowNode> = {
        if (!Files.exists(dataFile.path)) {
            emptyList()
        } else {
            (0 until MAX_ROWS_PER_FILE).map { rowIndex ->
                GraphNode.RowNode(
                    id = "row_${fileNodeId}_$rowIndex",
                    data = mapOf("file_no" to simpleId, "row_idx" to rowIndex),
                    content = 0,
                    dataLoader = {
                        try {
                            val rows = dataFile.rows
                            if (rowIndex < rows.size) {
                                val rowData = rows[rowIndex]
                                val enriched = mutableMapOf<String, Any>()
                                enriched["file_no"] = simpleId
                                enriched["row_idx"] = rowIndex
                                enriched["local_file_path"] = dataFile.path.toString()
                                enriched.putAll(rowData.cells)
                                enriched
                            } else emptyMap()
                        } catch (e: Exception) {
                            logger.warn("Failed to load rows for file {}: {}", dataFile.path, e.message)
                            emptyMap()
                        }
                    }
                )
            }
        }
    }

    /** Identity of a Paimon manifest file, for deduplicating it across snapshots. */


    /**
     * The set of data files the latest snapshot actually exposes.
     *
     * The walk itself is [replayPaimonSnapshot], in core's model layer beside the Iceberg ledger,
     * because it answers a question about a Paimon snapshot rather than about a graph — and
     * because the comparison panel needs the same walk's *other* half, the file set it ends on.
     * Two walks would be two implementations of one rule.
     */
    private fun currentStatsFor(snapshot: PaimonUnifiedSnapshot?): StatsDerivation =
        StatsDerivation(replayPaimonSnapshot(snapshot).contributions)

    internal fun buildTableSummary(tableModel: PaimonUnifiedTableModel): TableSummary {
        val seenManifests = mutableMapOf<String, String>()
        val seenFiles = mutableSetOf<String>()
        val historyContributions = mutableListOf<ManifestContribution>()

        // History is everything a retained snapshot reaches, and a tag retains one.
        (tableModel.snapshots + tableModel.tagOnlySnapshots).forEach { snapshot ->
            val countedIn = snapshot.metadata.id?.let { "snapshot $it" }
                ?: "snapshot file ${snapshot.path.fileName}"
            val allManifests = snapshot.baseManifests + snapshot.deltaManifests + snapshot.changelogManifests
            allManifests.forEach { manifest ->
                val key = paimonManifestKey(manifest)
                seenManifests[key]?.let { firstCountedIn ->
                    historyContributions += ManifestContribution(
                        manifestPath = manifest.path.toString(),
                        delta = ContentStats(),
                        firstCountedIn = firstCountedIn,
                    )
                    return@forEach
                }
                seenManifests[key] = countedIn

                var entries = 0
                var deletedEntries = 0
                var files = 0
                var records = 0L
                var sizeBytes = 0L
                var suppressed = 0
                manifest.entries.forEach { entry ->
                    entries++
                    if ((entry.metadata.kind ?: PaimonEntryKind.ADD) == PaimonEntryKind.DELETE) {
                        deletedEntries++
                    }
                    if (seenFiles.add(paimonDataFileKey(entry))) {
                        files++
                        records += entry.metadata.file?.rowCount ?: 0L
                        sizeBytes += entry.metadata.file?.fileSize ?: 0L
                    } else {
                        suppressed++
                    }
                }

                historyContributions += ManifestContribution(
                    manifestPath = manifest.path.toString(),
                    // Paimon does not have Iceberg's positional/equality delete files, so those
                    // counts stay 0 for every Paimon table by definition, not for lack of parsing.
                    delta = ContentStats(
                        dataManifestCount = 1,
                        manifestEntryCount = entries,
                        deletedEntryCount = deletedEntries,
                        dataFileCount = files,
                        recordCount = records,
                        dataSizeBytes = sizeBytes,
                    ),
                    entriesSuppressedAsDuplicate = suppressed,
                )
            }
        }

        val history = StatsDerivation(historyContributions.toList())

        return TableSummary(
            tableName = tableModel.name,
            tablePath = tableModel.path.toString(),
            location = tableModel.path.toString(),
            tableUuid = null,
            formatVersion = null,
            currentSnapshotId = tableModel.snapshots.lastOrNull()?.metadata?.id,
            currentMetadataVersion = null,
            versionHintText = null,
            tableCreationMs = tableModel.snapshots.firstOrNull()?.metadata?.timeMillis,
            tableLastUpdateMs = tableModel.snapshots.lastOrNull()?.metadata?.timeMillis,
            lastUpdatedMs = tableModel.snapshots.lastOrNull()?.metadata?.timeMillis,
            metadataFileCount = 0,
            snapshotCount = tableModel.snapshots.size,
            snapshotManifestListFileCount = tableModel.snapshots.size,
            currentDerivation = currentStatsFor(tableModel.snapshots.lastOrNull()),
            historyDerivation = history,
            metadataFileTimes = FileTimeRange(),
            snapshotManifestListFileTimes = FileTimeRange(),
            manifestFileTimes = FileTimeRange(),
            dataFileTimes = FileTimeRange(),
        )
    }
}
