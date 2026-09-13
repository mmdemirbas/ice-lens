package service

import model.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Paths
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

    /** The column a row-tracked compaction writes each row's id under, and the key a derived id is put under. */
    const val ROW_ID_COLUMN = "_ROW_ID"

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
            paimonExpiryFiles = DeferredRead.of { tableModel.expiryFileInput() },
            integrity = DeferredRead.of { tableModel.integrityReport() },
            paimonRowLookup = DeferredRead.of { tableModel.paimonRowLookupInput() },
            // Read after the traversal fills `logicalNodes`, so the latest snapshot's node is
            // found whether or not aggregation goes on to draw it.
            maintenance = DeferredRead.of {
                PaimonMaintenanceInput(tableSummary.currentSnapshotId?.let { logicalNodes["psnap_$it"] as? GraphNode.PaimonSnapshotNode })
            },
        )

        // Every data file's deletion vector as of the last index manifest naming it, main's
        // snapshots first and each branch's after: the coordinates only, no index file opened.
        val vectorRanges = vectorRangesOf(tableModel.path, tableModel.snapshots + tableModel.branches.flatMap { it.snapshots })
        fun vectorLoader(range: PaimonVectorRange?): DeferredRead<DeletionVector> {
            if (range == null) return DeferredRead.none()
            return DeferredRead.of {
                runCatching { PaimonDeletionVectorReader.read(Paths.get(range.indexLocalPath), range.offset, range.length, range.dataFileName, range.cardinality) }
                    .onFailure { logger.warn("Could not read the deletion vector in {}: {}", range.indexLocalPath, it.message) }
                    .getOrNull()
            }
        }

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

        // One snapshot node with everything under it. The id carries the branch for a branch's
        // snapshot and not for main's: a branch's ids are its own, so `psnap_2` and `psnap_dev_2`
        // are two commits, while main's ids stay what every export and test has always read.
        fun addSnapshot(
            unifiedSnapshot: PaimonUnifiedSnapshot,
            branch: String?,
            tagNames: List<String>,
            retainedByTagOnly: Boolean,
        ) {
            val snap = unifiedSnapshot.metadata
            val idPrefix = if (branch == null) "" else "${branch}_"
            val snapId = "psnap_$idPrefix${snap.id ?: nextSnapshotSimpleId}"
            val simpleId = nextSnapshotSimpleId++

            if (!logicalNodes.containsKey(snapId)) {
                // Deferred: replaying a snapshot's base and delta is work only a comparison or
                // the record tallies ask for, and one replay serves both.
                val replay = DeferredRead.of { replayPaimonSnapshot(unifiedSnapshot) }
                val liveFiles = DeferredRead.of { replay.value?.let { paimonLiveFilesOf(it) } }
                logicalNodes[snapId] = GraphNode.PaimonSnapshotNode(
                    id = snapId,
                    data = snap,
                    simpleId = simpleId,
                    commitKind = snap.commitKind,
                    localPath = unifiedSnapshot.path.toString(),
                    liveFilesLoader = liveFiles,
                    recordTalliesLoader = DeferredRead.of {
                        paimonRecordTallies(unifiedSnapshot, liveFiles.value.orEmpty().sumOf { it.recordCount })
                    },
                    bucketLsmsLoader = DeferredRead.of { replay.value?.let { paimonBucketLsms(it.liveEntries.values) } },
                    tableOptions = unifiedSnapshot.schema?.options.orEmpty(),
                    hasPrimaryKey = unifiedSnapshot.schema?.primaryKeys?.isNotEmpty() ?: true,
                    indexFiles = unifiedSnapshot.indexFiles,
                    statistics = unifiedSnapshot.statistics,
                    tags = tagNames,
                    retainedByTagOnly = retainedByTagOnly,
                    branch = branch,
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
                val mlId = "pml_$idPrefix${snap.id ?: "?"}_$kind"
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
                            partitionMin = unifiedManifest.partitionMin,
                            partitionMax = unifiedManifest.partitionMax,
                            // Numbered for every entry, not just the ones the graph draws, so
                            // the inspector can list the whole manifest.
                            entries = unifiedManifest.entries.map { unifiedDataFile ->
                                PaimonManifestEntryView(
                                    simpleId = nextFileSimpleId++,
                                    entry = unifiedDataFile.metadata,
                                    localPath = unifiedDataFile.path.toString(),
                                    partition = unifiedDataFile.partition,
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

                            val vectorRange = entry.file?.fileName?.let { vectorRanges[it] }
                            val vector = (logicalNodes[fId] as? GraphNode.PaimonDataFileNode)?.deletionVector ?: vectorLoader(vectorRange)
                            if (!logicalNodes.containsKey(fId)) {
                                logicalNodes[fId] = GraphNode.PaimonDataFileNode(
                                    id = fId,
                                    entry = entry,
                                    simpleId = fileSimpleId,
                                    bucket = entry.bucket,
                                    partition = unifiedDataFile.partition,
                                    keyMin = unifiedDataFile.keyMin,
                                    keyMax = unifiedDataFile.keyMax,
                                    columnBounds = unifiedDataFile.columnBounds,
                                    level = entry.file?.level,
                                    operationKind = entry.kind,
                                    localPath = unifiedDataFile.path.toString(),
                                    pathResolution = unifiedDataFile.pathResolution,
                                    // A changelog file is the change stream, not the table's contents; no snapshot lists it live.
                                    history = if (kind == "changelog") DeferredRead.none()
                                    else paimonDataFileKey(unifiedDataFile).let { key -> DeferredRead.of { tableModel.fileHistoryOf(key, branch) } },
                                    vectorRange = vectorRange,
                                    deletionVector = vector,
                                )
                            }

                            val fileEdgeId = "e_file_${manId}_to_$fId"
                            edgeIds.add(fileEdgeId)
                            edges.add(GraphEdge(fileEdgeId, manId, fId))

                            sampleRows[fId] = sampleRowFactory(fId, unifiedDataFile, fileSimpleId, vector)
                        }
                    }
                }
            }

            addManifestList("base", unifiedSnapshot.baseManifests, snapId)
            addManifestList("delta", unifiedSnapshot.deltaManifests, snapId)
            addManifestList("changelog", unifiedSnapshot.changelogManifests, snapId)
        }

        // Snapshot nodes: what snapshot/ holds, then what only a tag still holds — a snapshot
        // expiry removed whose files a tag keeps on disk. Drawn in id order so the tagged one
        // sits where it was committed rather than after everything.
        val liveIds = tableModel.snapshots.mapNotNull { it.metadata.id }.toSet()
        (tableModel.snapshots + tableModel.tagOnlySnapshots)
            .sortedBy { it.metadata.id ?: Long.MAX_VALUE }
            .forEach { unifiedSnapshot ->
                val id = unifiedSnapshot.metadata.id
                addSnapshot(unifiedSnapshot, null, id?.let { tableModel.tagNamesBySnapshotId[it] }.orEmpty(), id !in liveIds)
            }

        // Then each branch's, the same way and under the same table root: a branch is another
        // line of commits over the same manifests and data, and the layout gives it a column of
        // its own (see SnapshotTracks). A manifest a branch's snapshot shares with main's — the
        // whole of a branch created from a tag, until its first commit — is one node under both.
        tableModel.branches.forEach { branch ->
            branch.readErrors.forEach { addErrorNode(tableNodeId, "BRANCH READ ERROR", it) }
            val branchLiveIds = branch.snapshots.mapNotNull { it.metadata.id }.toSet()
            (branch.snapshots + branch.tagOnlySnapshots)
                .sortedBy { it.metadata.id ?: Long.MAX_VALUE }
                .forEach { unifiedSnapshot ->
                    val id = unifiedSnapshot.metadata.id
                    addSnapshot(unifiedSnapshot, branch.name, id?.let { branch.tagNamesBySnapshotId[it] }.orEmpty(), id !in branchLiveIds)
                }
        }

        addPatchEdges(logicalNodes, edges, edgeIds)

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
        /** The file's vector, shared with its node so it is decoded once — see [GraphNode.PaimonDataFileNode.deletionVector]. */
        vector: DeferredRead<DeletionVector>,
    ): () -> List<GraphNode.RowNode> = {
        if (!Files.exists(dataFile.path)) {
            emptyList()
        } else {
            // Decoded here, at row-load time for a drawn file, the way the Iceberg builder resolves
            // a vector: a sampled row is 50 deep at most and positions come out ascending, so the
            // decoder's cap cannot hide one this set needs. A vector that could not be read leaves
            // the rows unresolved rather than drawn live.
            val decoded = if (vector.isPresent) vector.value else null
            val deleted = decoded?.positions?.toSet().orEmpty()
            val resolved = !vector.isPresent || decoded != null
            // As many nodes as the file has rows, up to the cap: the entry's _ROW_COUNT is known
            // before the file is opened, and a one-row file drew four empty cards.
            (0 until GraphNode.RowNode.countFor(dataFile.metadata.file?.rowCount)).map { rowIndex ->
                GraphNode.RowNode(
                    id = "row_${fileNodeId}_$rowIndex",
                    data = mapOf("file_no" to simpleId, "row_idx" to rowIndex),
                    content = 0,
                    deletedPositions = deleted,
                    vectorsResolved = resolved,
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
                                // The file position, as the Iceberg builder carries it: DuckDB's
                                // statement about the file, not a column the table declares.
                                rowData.position?.let { enriched[GraphNode.RowNode.ROW_POSITION_KEY] = it }
                                // A row-tracked file written by a commit records only its first
                                // row id, and the rest follow in file order; a compaction's
                                // output carries every row's id in a _ROW_ID column instead and
                                // records no first id. So the id is derived here for the first
                                // shape and read as a cell for the second — one column either way.
                                val firstRowId = dataFile.metadata.file?.firstRowId
                                val position = rowData.position
                                if (firstRowId != null && position != null && ROW_ID_COLUMN !in enriched) {
                                    enriched[ROW_ID_COLUMN] = firstRowId + position
                                }
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

    /**
     * A data-evolution patch file points at the file it patches: same partition, bucket and
     * `_FIRST_ROW_ID`, the target holding every column and the source only `_WRITE_COLS`. That is
     * the pairing `DataEvolutionSplitGenerator.split` reads by, and it is drawn the way a v3
     * deletion vector's `e_dv_*` edge is — an annotation withheld from the layout, since both ends
     * sit in one layer. Only `ADD` entries pair: a `DELETE` entry records a removal, not a file.
     */
    private fun addPatchEdges(
        logicalNodes: Map<String, GraphNode>,
        edges: MutableList<GraphEdge>,
        edgeIds: MutableSet<String>,
    ) {
        val added = logicalNodes.values.filterIsInstance<GraphNode.PaimonDataFileNode>()
            .filter { it.operationKind == PaimonEntryKind.ADD && it.entry.file?.firstRowId != null }
        val patches = added.filter { it.entry.file?.writeCols != null }
        if (patches.isEmpty()) return
        val wholeByRowId = added.filter { it.entry.file?.writeCols == null }
            .groupBy { Triple(it.partition?.path, it.bucket, it.entry.file?.firstRowId) }
        patches.forEach { patch ->
            wholeByRowId[Triple(patch.partition?.path, patch.bucket, patch.entry.file?.firstRowId)].orEmpty().forEach { target ->
                val edgeId = "e_patch_${patch.id}_to_${target.id}"
                if (edgeIds.add(edgeId)) edges.add(GraphEdge(edgeId, patch.id, target.id, affectsLayout = false))
            }
        }
    }

    internal fun buildTableSummary(tableModel: PaimonUnifiedTableModel): TableSummary {
        val seenManifests = mutableMapOf<String, String>()
        val seenFiles = mutableSetOf<String>()
        val historyContributions = mutableListOf<ManifestContribution>()

        // History is everything a retained snapshot reaches: a tag retains one, and a branch
        // retains its own line. A branch's copied first snapshot reaches main's manifests, which
        // the deduplication credits to the snapshot that counted them first.
        val retained = tableModel.snapshots + tableModel.tagOnlySnapshots +
            tableModel.branches.flatMap { it.snapshots + it.tagOnlySnapshots }
        retained.forEach { snapshot ->
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
            branches = tableModel.branches.map { branch ->
                BranchSummary(
                    name = branch.name,
                    path = runCatching { tableModel.path.relativize(branch.path).toString() }.getOrDefault(branch.path.toString()),
                    snapshotCount = branch.snapshots.size,
                    latestSnapshotId = branch.snapshots.lastOrNull()?.metadata?.id,
                    schemaCount = branch.schemas.size,
                    tagCount = branch.tags.size,
                    readErrorCount = branch.readErrors.size + branch.snapshots.sumOf { it.readErrors.size },
                )
            },
            consumers = tableModel.consumers.map { consumer ->
                ConsumerSummary(
                    name = consumer.name,
                    path = runCatching { tableModel.path.relativize(consumer.path).toString() }.getOrDefault(consumer.path.toString()),
                    nextSnapshot = consumer.metadata.nextSnapshot,
                    nextSnapshotPresent = consumer.metadata.nextSnapshot?.let { next -> tableModel.snapshots.any { it.metadata.id == next } } ?: false,
                )
            },
            paimonExpiry = tableModel.expiryInput(),
        )
    }
}
