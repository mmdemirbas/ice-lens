package service

import model.*
import org.slf4j.LoggerFactory
import java.io.File
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

    /** Max data files shown per manifest in the graph. */
    private const val MAX_FILES_PER_MANIFEST = 10

    /** Max sample rows created per data file. */
    private const val MAX_ROWS_PER_FILE = 5

    /**
     * Builds graph nodes and edges for the given Paimon table model.
     * Does not perform layout — call [GraphLayoutService.layoutGraph] with the result
     * (the public entry point that dispatches by format).
     */
    fun buildGraph(
        tableModel: PaimonUnifiedTableModel,
        showRows: Boolean,
    ): GraphBuildResult {
        val logicalNodes = mutableMapOf<String, GraphNode>()
        val edges = mutableListOf<GraphEdge>()
        val edgeIds = mutableSetOf<String>()
        val manifestPathToId = mutableMapOf<String, String>()
        val processedManifestEntries = mutableSetOf<String>()
        val tableNodeId = "table_root"

        val tableSummary = buildTableSummary(tableModel)
        logicalNodes[tableNodeId] = GraphNode.TableNode(tableNodeId, tableSummary)

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
                        simpleId = nextManifestListSimpleId++,
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
                            shownEntryCount = minOf(unifiedManifest.entries.size, MAX_FILES_PER_MANIFEST),
                            localPath = unifiedManifest.path.toString(),
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
                        unifiedManifest.entries.take(MAX_FILES_PER_MANIFEST).forEachIndexed { fileIndex, unifiedDataFile ->
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

    /** Identity of a Paimon manifest file, for deduplicating it across snapshots. */
    private fun manifestKey(manifest: PaimonUnifiedManifest): String =
        manifest.metadata.fileName?.takeIf { it.isNotBlank() } ?: "path:${manifest.path}"

    /** Identity of a Paimon data file, for deduplicating it across manifests. */
    private fun dataFileKey(entry: PaimonUnifiedDataFile): String =
        entry.metadata.file?.fileName?.takeIf { it.isNotBlank() } ?: "path:${entry.path}"

    /**
     * The set of data files the latest snapshot actually exposes.
     *
     * Paimon splits a snapshot's manifest lists into a *base* (the accumulated state carried
     * forward) and a *delta* (this commit's changes), and the delta is applied over the base:
     * a `_KIND=1` entry removes a file the base still lists. Counting ADD entries alone would
     * therefore report every file the table has ever held. The changelog manifest list is
     * deliberately excluded — it carries the change stream, not the table's contents.
     */
    private fun currentStatsFor(snapshot: PaimonUnifiedSnapshot?): StatsDerivation {
        if (snapshot == null) return StatsDerivation()

        val liveFiles = LinkedHashMap<String, PaimonDataFileMeta?>()
        val countedManifests = mutableMapOf<String, String>()
        val contributions = mutableListOf<ManifestContribution>()
        // Running totals rather than a sum over liveFiles per manifest: the delta is the change
        // this manifest made, and re-summing the map for each one would be quadratic.
        var liveRecords = 0L
        var liveBytes = 0L

        fun consume(manifests: List<PaimonUnifiedManifest>, listLabel: String) {
            manifests.forEach { manifest ->
                val key = manifestKey(manifest)
                countedManifests[key]?.let { firstCountedIn ->
                    contributions += ManifestContribution(
                        manifestPath = manifest.path.toString(),
                        delta = ContentStats(),
                        firstCountedIn = firstCountedIn,
                    )
                    return@forEach
                }
                countedManifests[key] = listLabel

                val filesBefore = liveFiles.size
                val recordsBefore = liveRecords
                val bytesBefore = liveBytes
                var entries = 0
                var deletedEntries = 0
                var suppressed = 0

                manifest.entries.forEach { entry ->
                    entries++
                    val fileKey = dataFileKey(entry)
                    val wasLive = liveFiles.containsKey(fileKey)
                    // containsKey rather than the result of remove/put: the map's value type is
                    // itself nullable, so a null return cannot tell "absent" from "present, no
                    // metadata" and the running totals would drift on files with no meta block.
                    if (wasLive) {
                        val previous = liveFiles[fileKey]
                        liveRecords -= previous?.rowCount ?: 0L
                        liveBytes -= previous?.fileSize ?: 0L
                    }
                    if ((entry.metadata.kind ?: PaimonEntryKind.ADD) == PaimonEntryKind.DELETE) {
                        deletedEntries++
                        liveFiles.remove(fileKey)
                    } else {
                        if (wasLive) suppressed++
                        liveFiles[fileKey] = entry.metadata.file
                        liveRecords += entry.metadata.file?.rowCount ?: 0L
                        liveBytes += entry.metadata.file?.fileSize ?: 0L
                    }
                }

                contributions += ManifestContribution(
                    manifestPath = manifest.path.toString(),
                    delta = ContentStats(
                        // Paimon has no data/delete manifest split — every manifest carries both
                        // kinds of entry — so all manifests are data manifests and removals show
                        // as deletedEntryCount.
                        dataManifestCount = 1,
                        manifestEntryCount = entries,
                        deletedEntryCount = deletedEntries,
                        dataFileCount = liveFiles.size - filesBefore,
                        recordCount = liveRecords - recordsBefore,
                        dataSizeBytes = liveBytes - bytesBefore,
                    ),
                    entriesSuppressedAsDuplicate = suppressed,
                )
            }
        }

        consume(snapshot.baseManifests, "base manifest list")
        consume(snapshot.deltaManifests, "delta manifest list")
        return StatsDerivation(contributions.toList())
    }

    internal fun buildTableSummary(tableModel: PaimonUnifiedTableModel): TableSummary {
        val seenManifests = mutableMapOf<String, String>()
        val seenFiles = mutableSetOf<String>()
        val historyContributions = mutableListOf<ManifestContribution>()

        tableModel.snapshots.forEach { snapshot ->
            val countedIn = snapshot.metadata.id?.let { "snapshot $it" }
                ?: "snapshot file ${snapshot.path.fileName}"
            val allManifests = snapshot.baseManifests + snapshot.deltaManifests + snapshot.changelogManifests
            allManifests.forEach { manifest ->
                val key = manifestKey(manifest)
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
                    if (seenFiles.add(dataFileKey(entry))) {
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
