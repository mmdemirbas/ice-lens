package service

import model.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

private val logger = LoggerFactory.getLogger(IcebergGraphBuilder::class.java)

/**
 * Builds graph nodes and edges from an Iceberg [UnifiedTableModel].
 *
 * This class is format-specific (Iceberg). For other table formats (e.g., Paimon),
 * create a separate builder that produces `List<GraphNode>` + `List<GraphEdge>`.
 */
object IcebergGraphBuilder {

    /** Max sample rows created per data file. */

    /** The two v3 row-lineage columns, by the reserved names the spec gives them (field ids 2147483540 and 2147483539). */
    const val ROW_ID_COLUMN = "_row_id"
    const val LAST_UPDATED_SEQUENCE_NUMBER_COLUMN = "_last_updated_sequence_number"

    private fun Any?.isNullCell(): Boolean = this == null || this == "null"

    /**
     * Builds graph nodes and edges for the given Iceberg table model.
     *
     * Every data file gets a node. How many of them are drawn is decided afterwards by
     * [GraphAggregation], which is also why sample rows are returned as factories rather than
     * nodes — see [GraphBuildResult.sampleRows]. Does not perform layout; call
     * [GraphLayoutService.layoutGraph] with the result.
     */
    fun buildGraph(tableModel: UnifiedTableModel): GraphBuildResult {
        val logicalNodes = mutableMapOf<String, GraphNode>()
        val sampleRows = mutableMapOf<String, () -> List<GraphNode.RowNode>>()
        val edges = mutableListOf<GraphEdge>()
        val edgeIds = mutableSetOf<String>()
        val pendingLineage = mutableListOf<Pair<Long, Long?>>()
        val currentRefs = currentRefsBySnapshot(tableModel)
        // Read off the newest metadata's log: a rollback writes no snapshot, so this is the one
        // place a commit that main was moved back past is recorded as such.
        val leftBehind = tableModel.metadatas.lastOrNull()?.metadata?.leftBehindBy().orEmpty()
        val processedManifests = mutableSetOf<String>()
        val manifestPathToId = mutableMapOf<String, String>()
        val tableNodeId = "table_root"

        val tableSummary = buildTableSummary(tableModel)
        // Sort orders only accumulate, so the newest metadata's list holds every id a file can name.
        val newestMetadata = tableModel.metadatas.lastOrNull()?.metadata
        val sortOrdersById = newestMetadata?.sortOrders.orEmpty()
            .mapNotNull { order -> order.orderId?.let { it to order } }.toMap()
        val defaultSortOrder = newestMetadata?.defaultSortOrderId?.let { sortOrdersById[it] }
        // Every field the table has ever defined, the newest definition of each id winning — so a
        // bound for a column dropped before its manifest was rewritten still has a name and a type.
        val tableFieldsById = newestMetadata?.schemas.orEmpty()
            .sortedBy { it.schemaId ?: -1 }
            .flatMap { tableSchema -> tableSchemaModel(tableSchema).fieldsById.values }
            .associateBy { it.id }
        logicalNodes[tableNodeId] = GraphNode.TableNode(
            tableNodeId,
            tableSummary,
            unreferencedFiles = DeferredRead.of { findUnreferencedFiles(tableModel) },
            expiryFiles = DeferredRead.of { tableModel.expiryFileInput() },
            integrity = DeferredRead.of { tableModel.integrityReport() },
            rowLookup = DeferredRead.of { tableModel.rowLookupInput() },
            // Closes over `logicalNodes` like the vector index below: it is read after the
            // traversal has filled it, so the current snapshot's node is there whether or not
            // aggregation goes on to draw it.
            maintenance = DeferredRead.of {
                tableModel.metadatas.lastOrNull()?.let { newest ->
                    val meta = newest.metadata
                    IcebergMaintenanceInput(meta, newest.path.fileName.toString(), meta.currentSnapshotId?.let { logicalNodes["snap_$it"] as? GraphNode.SnapshotNode })
                }
            },
        )

        // Deletion vectors by the data file each one covers, built on first use rather than here:
        // the factories below close over this, and they run after the traversal has finished
        // filling `logicalNodes`. Building it eagerly would index an empty map. No blob is read --
        // `referenced_data_file` is in the manifest entry.
        val vectorIndex = lazy {
            logicalNodes.values.asSequence()
                .filterIsInstance<GraphNode.FileNode>()
                .filter { it.isDeletionVector }
                .mapNotNull { vector ->
                    vector.data.referencedDataFile?.let { normalizeFilePath(it) to vector }
                }
                .toMap()
        }
        val vectorsByReferencedPath: () -> Map<String, GraphNode.FileNode> = { vectorIndex.value }

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

        tableModel.readErrors.forEach { error ->
            addErrorNode(tableNodeId, "TABLE READ ERROR", error)
        }

        // Assign simple IDs for all file paths upfront so delete rows can always resolve target file IDs.
        tableModel.metadatas.forEach { metadata ->
            val sortedSnapshots = metadata.snapshots.sortedWith(unifiedSnapshotComparator)
            sortedSnapshots.forEach { snapshot ->
                val manifests = snapshot.manifests.sortedWith(unifiedManifestComparator)
                manifests.forEach { unifiedManifest ->
                    val fallbackSeq = unifiedManifest.metadata.sequenceNumber ?: Long.MAX_VALUE
                    val unifiedDataFiles = unifiedManifest.dataFiles.sortedWith(unifiedDataFileComparator(fallbackSeq))
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
            if (!logicalNodes.containsKey(mId)) {
                val simpleMetadataId = nextMetadataSimpleId++
                logicalNodes[mId] = GraphNode.MetadataNode(
                    id = mId,
                    simpleId = simpleMetadataId,
                    fileName = fileName,
                    data = meta,
                    localPath = metadata.path.toString(),
                    rawJson = metadata.rawJson,
                    statisticsFooters = if (meta.statistics.isEmpty()) DeferredRead.none()
                    else DeferredRead.of { readStatisticsFooters(metadata.path, meta) },
                    partitionStatistics = if (meta.partitionStatistics.isEmpty()) DeferredRead.none()
                    else DeferredRead.of { readPartitionStatisticsFiles(metadata.path, meta) },
                )
            }
            val tableEdgeId = "e_table_${tableNodeId}_to_$mId"
            if (edgeIds.add(tableEdgeId)) {
                edges.add(GraphEdge(tableEdgeId, tableNodeId, mId))
            }

            val sortedSnapshots = metadata.snapshots.sortedWith(unifiedSnapshotComparator)
            sortedSnapshots.forEach { snapshot ->
                val snap = snapshot.metadata
                val snapshotIdentifierFields = identifierFieldNamesForSchema(meta, snap.schemaId)
                val sId = "snap_${snap.snapshotId}"
                if (!logicalNodes.containsKey(sId)) {
                    val simpleSnapshotId = nextSnapshotSimpleId++
                    val liveFiles = if (snapshot.expired) DeferredRead.none() else DeferredRead.of { liveFilesOf(snapshot) }
                    val reach = if (snapshot.expired) DeferredRead.none() else DeferredRead.of { deleteReach(snapshot) }
                    logicalNodes[sId] = GraphNode.SnapshotNode(
                        id = sId,
                        data = snap,
                        simpleId = simpleSnapshotId,
                        localPath = snapshot.path.toString(),
                        pathResolution = snapshot.pathResolution,
                        expired = snapshot.expired,
                        refs = snap.snapshotId?.let { currentRefs[it] }.orEmpty(),
                        leftBehindAt = snap.snapshotId?.let { leftBehind[it] },
                        // An expired snapshot has no manifests to read a change from, compare,
                        // or pair deletes across; its summary is all that is left of it.
                        change = if (snapshot.expired) null else snapshotChangeOf(snapshot),
                        manifestList = snapshot.manifests.map { it.metadata },
                        // Deferred, not computed: this walks the snapshot's whole manifest
                        // closure, and only two snapshots in a table are ever compared.
                        liveFilesLoader = liveFiles,
                        // Deferred for the same reason, and it costs that walk again: the pairing
                        // is a question about one commit, asked of one panel.
                        deleteReachLoader = reach,
                        readInput = if (snapshot.expired) DeferredRead.none() else DeferredRead.of {
                            tableModel.rowLookupInputOf(snapshot, liveFiles.value.orEmpty(), reach.value.orEmpty())
                        },
                    )
                }
                snapshot.readErrors.forEach { error ->
                    addErrorNode(sId, "SNAPSHOT READ ERROR", error)
                }

                val snapEdgeId = "e_snap_${mId}_to_$sId"
                if (edgeIds.add(snapEdgeId)) {
                    edges.add(GraphEdge(snapEdgeId, mId, sId))
                }

                // Commit lineage. Deferred until every snapshot node exists, because a parent is
                // reached through a different metadata version than its child as often as not,
                // and an edge to a node that has not been created yet is silently dropped.
                snap.parentSnapshotId?.let { parentId ->
                    pendingLineage += parentId to snap.snapshotId
                }

                val manifests = snapshot.manifests.sortedWith(unifiedManifestComparator)
                manifests.forEach { unifiedManifest ->
                    val manifest = unifiedManifest.metadata
                    val rawManPath = manifest.manifestPath ?: "unknown_${UUID.randomUUID()}"
                    val manId = manifestPathToId.getOrPut(rawManPath) { "man_${manifestPathToId.size + 1}" }
                    val fallbackSeq = manifest.effectiveSequenceNumber
                    val unifiedDataFiles = unifiedManifest.dataFiles.sortedWith(unifiedDataFileComparator(fallbackSeq))
                    if (!logicalNodes.containsKey(manId)) {
                        val simpleManifestId = nextManifestSimpleId++
                        logicalNodes[manId] = GraphNode.ManifestNode(
                            id = manId,
                            data = manifest,
                            simpleId = simpleManifestId,
                            entries = unifiedDataFiles.map { unifiedDataFile ->
                                ManifestEntryView(
                                    simpleId = assignSimpleId(unifiedDataFile.metadata.dataFile?.filePath.orEmpty()),
                                    entry = unifiedDataFile.metadata,
                                    localPath = unifiedDataFile.path.toString(),
                                    partition = unifiedDataFile.partition,
                                )
                            },
                            partitionSummaries = unifiedManifest.partitionSummaries,
                            pathResolution = unifiedManifest.pathResolution,
                            schema = unifiedManifest.schema,
                            localPath = unifiedManifest.path.toString()
                        )
                    }
                    unifiedManifest.readErrors.forEach { error ->
                        addErrorNode(manId, "MANIFEST READ ERROR", error)
                    }

                    val manEdgeId = "e_man_${sId}_to_$manId"
                    if (edgeIds.add(manEdgeId)) {
                        edges.add(GraphEdge(manEdgeId, sId, manId))
                    }

                    if (processedManifests.add(manId)) {
                        val manifestPath = manifest.manifestPath
                        if (manifestPath != null) {
                            unifiedDataFiles.forEachIndexed { fileIndex, unifiedDataFile ->
                                val entry = unifiedDataFile.metadata
                                val dataFile = entry.dataFile ?: DataFile(filePath = "unknown")
                                val rawPath = dataFile.filePath.orEmpty()
                                val simpleId = assignSimpleId(rawPath)
                                val fId = "file_${manId}_${simpleId}_$fileIndex"

                                if (!logicalNodes.containsKey(fId)) {
                                    logicalNodes[fId] = GraphNode.FileNode(
                                        id = fId,
                                        entry = entry,
                                        simpleId = simpleId,
                                        schema = unifiedManifest.schema,
                                        partition = unifiedDataFile.partition,
                                        specId = unifiedManifest.metadata.partitionSpecId,
                                        localPath = unifiedDataFile.path.toString(),
                                        pathResolution = unifiedDataFile.pathResolution,
                                        sortOrder = dataFile.sortOrderId?.let { sortOrdersById[it.toInt()] },
                                        defaultSortOrder = defaultSortOrder,
                                        tableFieldsById = tableFieldsById,
                                        manifestSequenceNumber = unifiedManifest.metadata.sequenceNumber,
                                        firstRowId = unifiedDataFile.firstRowId,
                                        firstRowIdInherited = unifiedDataFile.firstRowIdInherited,
                                        deletionVectorLoader = deletionVectorLoader(dataFile, unifiedDataFile.path),
                                        history = unifiedDataFile.ledgerFileKey().let { key -> DeferredRead.of { tableModel.fileHistoryOf(key) } },
                                    )
                                }

                                val edgeId = "e_file_${manId}_to_$fId"
                                edgeIds.add(edgeId)
                                edges.add(GraphEdge(edgeId, manId, fId))

                                sampleRows[fId] = sampleRowFactory(
                                    fileNodeId = fId,
                                    dataFile = unifiedDataFile,
                                    simpleId = simpleId,
                                    identifierFields = snapshotIdentifierFields,
                                    filePathToSimpleId = filePathToSimpleId,
                                    vectorFor = { path -> vectorsByReferencedPath()[path] },
                                    dataSequenceNumber = effectiveSequenceNumber(entry, unifiedManifest.metadata.sequenceNumber),
                                )
                            }
                        }
                    }
                }
            }
        }

        // A parent that is no longer retained leaves no node to point at. That is expiry working
        // as intended, not a missing edge, so the lineage simply stops there.
        pendingLineage.forEach { (parentId, childId) ->
            val parentNodeId = "snap_$parentId"
            val childNodeId = "snap_$childId"
            if (!logicalNodes.containsKey(parentNodeId) || !logicalNodes.containsKey(childNodeId)) return@forEach
            val lineageEdgeId = "e_lineage_${parentId}_to_$childId"
            if (edgeIds.add(lineageEdgeId)) {
                edges.add(
                    GraphEdge(
                        id = lineageEdgeId,
                        fromId = parentNodeId,
                        toId = childNodeId,
                        affectsLayout = false,
                    )
                )
            }
        }

        // A published write-audit-publish commit names the staged snapshot its files came from.
        // Drawn like lineage — between two snapshots, withheld from ELK, dashed — because the
        // parent edge says where the commit sits on main and this says where its files came from,
        // and a reader of a WAP table came to see the second.
        logicalNodes.values.filterIsInstance<GraphNode.SnapshotNode>().forEach { node ->
            val sourceId = node.data.sourceSnapshotId ?: return@forEach
            val sourceNodeId = "snap_$sourceId"
            if (!logicalNodes.containsKey(sourceNodeId)) return@forEach
            val edgeId = "e_source_${sourceId}_to_${node.data.snapshotId}"
            if (edgeIds.add(edgeId)) {
                edges.add(GraphEdge(id = edgeId, fromId = sourceNodeId, toId = node.id, affectsLayout = false))
            }
        }

        addDeletionVectorEdges(logicalNodes, edges, edgeIds)

        return GraphBuildResult(
            nodes = logicalNodes.values.toList(),
            edges = edges,
            summary = tableSummary,
            sampleRows = sampleRows,
        )
    }

    /**
     * The sample rows for one data file, read only if the graph ends up drawing that file.
     *
     * The `isRegularFile` check is inside the factory for the same reason the rows are: a stat
     * per data file is a real cost on a table with a hundred thousand of them, and the answer is
     * only needed for the ones on screen.
     *
     * [UnifiedManifest] has already resolved this file against the table directory and
     * `UnifiedDataFile.rows` reads from that same path. Re-deriving it here from a
     * `"/<tableName>/"` marker gave a second answer that could disagree with the first, and when
     * it did the rows were silently dropped for a file that exists.
     */
    /**
     * Draws the one delete-to-data link the format records.
     *
     * A v3 deletion vector carries `referenced_data_file`, which names exactly one data file. No
     * other delete kind has an equivalent: a positional delete file's targets are inside its
     * rows, one per row, and an equality delete has no target at all — it applies by predicate.
     * So this is the only such edge the graph can honestly draw, and the file inspector keeps
     * saying so for the other two.
     *
     * `affectsLayout = false`, for the same reason as commit lineage: both ends are data files
     * and belong in the same layer, and letting ELK see the edge would push the referenced file
     * a whole layer to the right of a file it is a sibling of.
     *
     * One data file path can have a node under more than one manifest, when several retained
     * manifests still list it. Each of those nodes is that file, so each gets the edge; drawing
     * one and picking which would be a claim about which manifest the vector "belongs" to, and
     * the vector does not record one.
     */
    /**
     * The lambda a [GraphNode.FileNode] opens its Puffin blob with, or null when there is nothing
     * to open.
     *
     * Only a v3 deletion vector records `content_offset`, so that field is the test rather than a
     * `.puffin` extension — a file name is a convention and this is a statement in the metadata.
     * A read that fails returns null rather than throwing: the vector is a few lines of an
     * inspector panel, and a table whose delete file has been moved should still draw.
     */
    private fun deletionVectorLoader(dataFile: DataFile, path: Path): DeferredRead<DeletionVector> {
        val offset = dataFile.contentOffset ?: return DeferredRead.none()
        val length = dataFile.contentSizeInBytes ?: return DeferredRead.none()
        val referenced = dataFile.referencedDataFile
        // The manifest's own `record_count`, which the spec requires to be the vector's
        // cardinality. It is what a scan plans against without opening the Puffin file at all,
        // so it is the figure worth putting beside the count decoded from the bytes.
        val recorded = dataFile.recordCount
        return DeferredRead.of {
            runCatching { PuffinReader.readDeletionVector(path, offset, length, referenced, recorded) }
                .onFailure { logger.warn("Could not read the deletion vector in {}: {}", path, it.message) }
                .getOrNull()
        }
    }

    private fun addDeletionVectorEdges(
        logicalNodes: Map<String, GraphNode>,
        edges: MutableList<GraphEdge>,
        edgeIds: MutableSet<String>,
    ) {
        val fileNodes = logicalNodes.values.filterIsInstance<GraphNode.FileNode>()
        val vectors = fileNodes.filter { it.data.referencedDataFile != null }
        if (vectors.isEmpty()) return

        val dataFilesByPath = fileNodes
            .filter { (it.data.content ?: DataFileContent.DATA) == DataFileContent.DATA }
            .groupBy { normalizeFilePath(it.data.filePath.orEmpty()) }

        vectors.forEach { vector ->
            val referenced = normalizeFilePath(vector.data.referencedDataFile.orEmpty())
            dataFilesByPath[referenced].orEmpty().forEach { target ->
                val edgeId = "e_dv_${vector.id}_to_${target.id}"
                if (edgeIds.add(edgeId)) {
                    edges.add(GraphEdge(edgeId, vector.id, target.id, affectsLayout = false))
                }
            }
        }
    }

    /**
     * The positions a deletion vector removes from one data file, or an empty set.
     *
     * Resolved through [vectorFor] at row-load time rather than at build time, and one vector at a
     * time: the graph is built for every artifact the metadata names, so reading every `.puffin`
     * file to answer a question about the four data files on screen is the cost the whole lazy
     * arrangement exists to avoid.
     */
    private fun deletedPositionsFor(
        dataFilePath: String?,
        vectorFor: (String) -> GraphNode.FileNode?,
    ): Set<Long> {
        val path = dataFilePath?.takeIf { it.isNotEmpty() } ?: return emptySet()
        val vector = vectorFor(normalizeFilePath(path)) ?: return emptySet()
        return vector.deletionVector?.positions?.toSet().orEmpty()
    }

    private fun sampleRowFactory(
        fileNodeId: String,
        dataFile: UnifiedDataFile,
        simpleId: Int,
        identifierFields: List<String>,
        filePathToSimpleId: Map<String, Int>,
        vectorFor: (String) -> GraphNode.FileNode?,
        dataSequenceNumber: Long,
    ): () -> List<GraphNode.RowNode> = {
        if (!Files.isRegularFile(dataFile.path)) {
            emptyList()
        } else {
            val contentType = dataFile.metadata.dataFile?.content ?: 0
            val deleted = if (contentType == DataFileContent.DATA) {
                deletedPositionsFor(dataFile.metadata.dataFile?.filePath, vectorFor)
            } else {
                emptySet()
            }
            // As many nodes as the file has rows, up to the cap: record_count is known before
            // the file is opened, and a one-row file drew four empty cards.
            (0 until GraphNode.RowNode.countFor(dataFile.metadata.dataFile?.recordCount)).map { rowIndex ->
                GraphNode.RowNode(
                    id = "row_${fileNodeId}_$rowIndex",
                    data = mapOf("file_no" to simpleId, "row_idx" to rowIndex),
                    content = contentType,
                    identifierFields = identifierFields,
                    deletedPositions = deleted,
                    dataLoader = {
                        try {
                            val rows = dataFile.rows
                            if (rowIndex < rows.size) {
                                val rowData = rows[rowIndex]
                                val enriched = mutableMapOf<String, Any>()
                                enriched["file_no"] = simpleId
                                enriched["row_idx"] = rowIndex
                                enriched["local_file_path"] = dataFile.path.toString()
                                rowData.position?.let { enriched[GraphNode.RowNode.ROW_POSITION_KEY] = it }
                                if (contentType > 0 && rowData.cells.containsKey("file_path")) {
                                    val targetPath = rowData.cells["file_path"].toString()
                                    val targetId = filePathToSimpleId[targetPath]
                                        ?: filePathToSimpleId[normalizeFilePath(targetPath)]
                                        ?: "?"
                                    enriched["target_file"] = "File $targetId"
                                    enriched["target_file_no"] = targetId
                                }
                                enriched.putAll(rowData.cells)
                                // v3 row lineage is read by inheritance: a row's `_row_id` is the
                                // file's first id plus its position unless the file carries the
                                // column, and a null `_last_updated_sequence_number` is the file's
                                // data sequence number — a rewrite writes null for the rows it
                                // changed and copies the value for the rows it did not. So the
                                // two columns are filled here where they are absent or null, and
                                // a file with the columns keeps every value it wrote.
                                // (A null cell arrives as the string "null" — see SampleRowReader.)
                                if (contentType == DataFileContent.DATA && dataFile.firstRowId != null) {
                                    if (enriched[ROW_ID_COLUMN].isNullCell()) {
                                        rowData.position?.let { enriched[ROW_ID_COLUMN] = dataFile.firstRowId + it }
                                    }
                                    if (enriched[LAST_UPDATED_SEQUENCE_NUMBER_COLUMN].isNullCell()) {
                                        enriched[LAST_UPDATED_SEQUENCE_NUMBER_COLUMN] = dataSequenceNumber
                                    }
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

    /**
     * Branch and tag names by the snapshot they point at, from the **latest** metadata version.
     *
     * A snapshot node is created by whichever metadata version first mentions it, and that
     * version's `refs` describe where the branches were then, not now. `main` moves with every
     * commit, so labelling each snapshot from the file that introduced it would put `main` on
     * every snapshot in the table. Refs are a statement about the table's present state, so they
     * come from one place: the current metadata.
     */
    private fun currentRefsBySnapshot(tableModel: UnifiedTableModel): Map<Long, List<SnapshotRefLabel>> {
        // The version that first lists each name: which of two branches is the older line, when
        // they fork from one commit and the metadata log still holds the version that created it.
        val createdIn = mutableMapOf<String, Int>()
        tableModel.metadatas.forEach { meta ->
            val version = metadataVersionFromFileName(meta.path.fileName.toString()) ?: return@forEach
            meta.metadata.refs.orEmpty().keys.forEach { name -> createdIn.merge(name, version, ::minOf) }
        }
        return tableModel.metadatas.lastOrNull()?.metadata?.refs.orEmpty()
            .mapNotNull { (name, ref) ->
                ref.snapshotId?.let { id ->
                    id to SnapshotRefLabel(name, isBranch = !ref.type.equals("tag", ignoreCase = true), createdInVersion = createdIn[name])
                }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, labels) -> labels.sortedWith(compareBy({ it.name != "main" }, { it.name })) }
    }

    // --- Table summary ---

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

    /** Identity of a manifest file, for deduplicating the same manifest across snapshots. */
    private fun manifestKey(manifest: UnifiedManifest): String =
        manifest.metadata.manifestPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::normalizeFilePath)
            ?: "path:${manifest.path}"

    /** Identity of a data file, for deduplicating the same file across manifests. */
    private fun dataFileKey(dataFile: UnifiedDataFile): String =
        dataFile.metadata.dataFile?.filePath
            ?.takeIf { it.isNotBlank() }
            ?.let(::normalizeFilePath)
            ?: "path:${dataFile.path}"

    /**
     * Accumulates [ContentStats] over manifests fed in any order, ignoring any manifest or
     * data file it has already counted. The deduplication is what makes the figures describe
     * the table rather than the traversal — see [ContentStats].
     *
     * With [liveEntriesOnly] the accumulator skips `DELETED` manifest entries, which is the
     * right reading for "what does my table contain now"; without it every referenced file is
     * counted once, which is the right reading for "what is still on disk".
     */
    private class ContentStatsAccumulator(private val liveEntriesOnly: Boolean) {
        /** Manifest key to the traversal position that first counted it. */
        private val seenManifests = mutableMapOf<String, String>()
        private val seenFiles = mutableSetOf<String>()
        private val contributions = mutableListOf<ManifestContribution>()

        /**
         * Counts a manifest and its entries, recording what it contributed. [countedIn] labels
         * where in the traversal this sighting happened, so a later repeat can name it.
         *
         * Returns false if this manifest was already counted.
         */
        fun addManifest(manifest: UnifiedManifest, countedIn: String): Boolean {
            val key = manifestKey(manifest)
            seenManifests[key]?.let { firstCountedIn ->
                // Still recorded. A manifest carried forward by twelve snapshots is visited
                // twelve times and counted once, and the eleven repeats are the evidence for
                // why the total is not twelve times larger.
                contributions += ManifestContribution(
                    manifestPath = manifest.path.toString(),
                    delta = ContentStats(),
                    firstCountedIn = firstCountedIn,
                )
                return false
            }
            seenManifests[key] = countedIn
            contributions += contributionOf(manifest)
            return true
        }

        /**
         * Folds this manifest's entries through the shared per-entry ledger.
         *
         * The manifest-level counts are the only part that is not per entry, so they seed the
         * fold rather than being added beside it — a total accumulated next to the rows it is
         * supposed to explain is a second implementation of the number.
         */
        private fun contributionOf(manifest: UnifiedManifest): ManifestContribution {
            val ledger = manifestLedger(
                entries = manifest.dataFiles.map {
                    LedgerEntry(dataFileKey(it), it.metadata.status, it.metadata.dataFile)
                },
                liveEntriesOnly = liveEntriesOnly,
                seenFileKeys = seenFiles,
            )
            val isDeleteManifest = manifest.metadata.content == ManifestContent.DELETES
            val seed = ContentStats(
                dataManifestCount = if (isDeleteManifest) 0 else 1,
                deleteManifestCount = if (isDeleteManifest) 1 else 0,
            )
            return ManifestContribution(
                manifestPath = manifest.path.toString(),
                delta = ledger.fold(seed) { running, entry -> running + entry.delta },
                entriesSuppressedAsDuplicate = ledger.count { it.fate == EntryFate.DUPLICATE },
            )
        }

        fun build(): StatsDerivation = StatsDerivation(contributions.toList())
    }

    internal fun buildTableSummary(tableModel: UnifiedTableModel): TableSummary {
        val mtimeCache = mutableMapOf<String, Long?>()
        val metadataTimes = FileTimeAccumulator()
        val snapshotManifestListTimes = FileTimeAccumulator()
        val manifestTimes = FileTimeAccumulator()
        val dataFileTimes = FileTimeAccumulator()

        val uniqueSnapshotKeys = mutableSetOf<String>()
        val uniqueSnapshotManifestListKeys = mutableSetOf<String>()
        val uniqueDataFileKeys = mutableSetOf<String>()

        val historyStats = ContentStatsAccumulator(liveEntriesOnly = false)

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

                val countedIn = snapshotMeta.snapshotId?.let { "snapshot $it" }
                    ?: "manifest list ${unifiedSnapshot.path.fileName}"
                unifiedSnapshot.manifests.forEach { unifiedManifest ->
                    if (historyStats.addManifest(unifiedManifest, countedIn)) {
                        manifestTimes.add(fileLastModifiedMs(unifiedManifest.path, mtimeCache))

                        unifiedManifest.dataFiles.forEach { unifiedDataFile ->
                            if (uniqueDataFileKeys.add(dataFileKey(unifiedDataFile))) {
                                dataFileTimes.add(fileLastModifiedMs(unifiedDataFile.path, mtimeCache))
                            }
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

        // The table as it is now: the manifest closure of the current snapshot, live entries
        // only. Absent when the table has never been committed to, or when the snapshot the
        // latest metadata points at is no longer present in any metadata file we could read.
        val currentSnapshot = latestMetadata?.currentSnapshotId?.let { snapshotId ->
            tableModel.metadatas.asReversed().firstNotNullOfOrNull { unifiedMetadata ->
                unifiedMetadata.snapshots.firstOrNull { it.metadata.snapshotId == snapshotId }
            }
        }
        val currentCountedIn = currentSnapshot?.metadata?.snapshotId
            ?.let { "snapshot $it" } ?: "current snapshot"
        val currentStats = ContentStatsAccumulator(liveEntriesOnly = true)
            .apply { currentSnapshot?.manifests?.forEach { addManifest(it, currentCountedIn) } }
            .build()

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
            currentDerivation = currentStats,
            historyDerivation = historyStats.build(),
            metadataFileTimes = metadataTimes.asRange(),
            snapshotManifestListFileTimes = snapshotManifestListTimes.asRange(),
            manifestFileTimes = manifestTimes.asRange(),
            dataFileTimes = dataFileTimes.asRange(),
            metadataVersions = metadataVersions
        )
    }

    // --- Comparators (shared between graph construction and layout post-processing) ---

    internal val unifiedSnapshotComparator: Comparator<UnifiedSnapshot> = compareBy(
        { it.metadata.timestampMs ?: Long.MAX_VALUE },
        { it.metadata.sequenceNumber ?: Long.MAX_VALUE },
        { it.metadata.snapshotId ?: Long.MAX_VALUE },
    )

    internal val unifiedManifestComparator: Comparator<UnifiedManifest> = compareBy(
        { it.metadata.sequenceNumber ?: Long.MAX_VALUE },
        { it.metadata.minSequenceNumber ?: Long.MAX_VALUE },
        { it.metadata.addedSnapshotId ?: Long.MAX_VALUE },
        { manifestContentRank(it.metadata.content) },
        { it.metadata.manifestPath ?: "" },
    )

    internal fun unifiedDataFileComparator(fallbackSequenceNumber: Long): Comparator<UnifiedDataFile> = compareBy(
        { it.metadata.dataFile?.dataSequenceNumber ?: it.metadata.sequenceNumber ?: fallbackSequenceNumber },
        { it.metadata.fileSequenceNumber ?: Long.MAX_VALUE },
        { contentRank(it.metadata.dataFile?.content) },
        { it.metadata.status },
        { it.metadata.dataFile?.filePath ?: "" },
    )

    internal fun contentRank(content: Int?): Int = when (content ?: 0) {
        2 -> 0 // Equality deletes first
        0 -> 1 // Data files second
        1 -> 2 // Position deletes last
        else -> 3
    }

    fun manifestContentRank(content: Int?): Int = when (content ?: 0) {
        1 -> 0 // Delete manifest first
        0 -> 1 // Data manifest second
        else -> 2
    }
}

/**
 * Opens each statistics file a metadata version names, and says what happened to each.
 *
 * The path is resolved recorded-first, the same rule a manifest list follows: a table written by
 * a container records `/wh/...` and a table copied down from object storage records `s3://...`,
 * and neither is on this machine — the file name against the local metadata directory is what
 * opens both. A failure is captured rather than thrown, because a statistics file that has been
 * cleaned up is a thing to *report*, not a reason a metadata panel cannot be drawn.
 */
/** Same resolution as [readStatisticsFooters]: the recorded path when it is there, its name under the metadata dir otherwise. */
private fun readPartitionStatisticsFiles(
    metadataPath: java.nio.file.Path,
    metadata: TableMetadata,
): Map<String, PartitionStatisticsRead> {
    val metadataDir = metadataPath.parent ?: metadataPath
    return metadata.partitionStatistics.mapNotNull { file ->
        val recorded = file.statisticsPath ?: return@mapNotNull null
        val (path, resolution) = resolveRecordedOrRelative(metadataDir, recorded)
        recorded to readPartitionStatistics(path, resolution)
    }.toMap()
}

private fun readStatisticsFooters(
    metadataPath: java.nio.file.Path,
    metadata: TableMetadata,
): Map<String, StatisticsFileFooter> {
    // The metadata *directory*, not the metadata.json: the fallback resolves a recorded path's
    // last segment against what it is given, so handing it the file produces
    // `v4.metadata.json/<name>.stats` — a path that is not a directory and never will be. Every
    // other caller passes `metadataDir` for the same reason.
    val metadataDir = metadataPath.parent ?: metadataPath
    return metadata.statistics.mapNotNull { file ->
        val recorded = file.statisticsPath ?: return@mapNotNull null
        val (path, resolution) = resolveRecordedOrRelative(metadataDir, recorded)
        val read = runCatching { PuffinReader.readFooter(path) }
        recorded to StatisticsFileFooter(
            localPath = path.toString(),
            resolution = resolution,
            footer = read.getOrNull(),
            problem = read.exceptionOrNull()?.let { it.message ?: it::class.simpleName },
        )
    }.toMap()
}
