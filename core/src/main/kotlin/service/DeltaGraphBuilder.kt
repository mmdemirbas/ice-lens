package service

import model.*
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.CRC32

private val logger = LoggerFactory.getLogger(DeltaGraphBuilder::class.java)

/**
 * Builds graph nodes and edges from a Delta [DeltaUnifiedTableModel].
 *
 * Graph structure (left to right):
 * ```
 * TableNode -> DeltaVersionNode(s) -> DeltaFileNode(s) (add | remove | cdc) -> RowNode(s)
 *                                  -> DeltaCheckpointNode(s)
 * ```
 * A file action is drawn under the commit that wrote it and nowhere else. Delta's log is per
 * commit, not per state: a version does not list its files the way an Iceberg manifest list
 * does, it records what changed, and the live set is a replay — which the version node carries
 * deferred, for the comparison and the panels. Drawing each live file under every version that
 * holds it would draw the table once per commit.
 */
object DeltaGraphBuilder {

    fun buildGraph(tableModel: DeltaUnifiedTableModel): GraphBuildResult {
        val nodes = mutableListOf<GraphNode>()
        val edges = mutableListOf<GraphEdge>()
        val sampleRows = mutableMapOf<String, () -> List<GraphNode.RowNode>>()
        val tableNodeId = "table_root"
        val summary = buildTableSummary(tableModel)
        val current = tableModel.current

        nodes += GraphNode.TableNode(
            tableNodeId,
            summary,
            unreferencedFiles = DeferredRead.of { findUnreferencedFiles(tableModel) },
            integrity = DeferredRead.of { tableModel.integrityReport() },
        )

        var nextErrorId = 0
        fun addError(parentId: String, title: String, error: UnifiedReadError) {
            val id = "err_${nextErrorId++}_${parentId.hashCode()}_${error.stage.hashCode()}"
            nodes += GraphNode.ErrorNode(id, title, error.stage, error.path, error.message, error.stackTrace)
            edges += GraphEdge("e_err_${parentId}_$id", parentId, id)
        }
        tableModel.readErrors.forEach { addError(tableNodeId, "TABLE READ ERROR", it) }

        val partitionColumns = current?.metadata?.partitionColumns.orEmpty()
        val livePaths = current?.files?.keys?.mapTo(mutableSetOf()) { it.path }.orEmpty()
        var nextFileId = 1
        var nextCheckpointId = 1
        var previousVersionId: String? = null
        tableModel.versions.forEachIndexed { index, version ->
            val commit = tableModel.commitByVersion[version]
            val versionId = "dver_$version"
            val state = tableModel.stateAt(version)
            nodes += GraphNode.DeltaVersionNode(
                id = versionId,
                version = version,
                commit = commit,
                simpleId = index + 1,
                localPath = (commit?.path ?: tableModel.listing.commits[version])?.toString(),
                checkpoints = tableModel.listing.checkpoints[version].orEmpty(),
                unavailable = (state.exceptionOrNull() as? DeltaVersionUnavailable)?.detail?.reason,
                stateLoader = DeferredRead.of { tableModel.stateAt(version).getOrNull() },
                tallies = commit?.let(::deltaCommitTallies).orEmpty(),
            )
            edges += GraphEdge("e_table_$versionId", tableNodeId, versionId)
            previousVersionId?.let { parent ->
                edges += GraphEdge("e_lineage_${parent}_to_$versionId", parent, versionId, affectsLayout = false)
            }
            previousVersionId = versionId

            commit?.actions?.forEach { action ->
                val fileId = "dfile_${version}_${nextFileId}"
                val node = when {
                    action.add != null -> fileNode(tableModel, fileId, DeltaFileAction.ADD, version, nextFileId, partitionColumns, add = action.add, liveNow = current?.files?.containsKey(action.add.key) == true, pathLiveNow = action.add.path in livePaths)
                    action.remove != null -> fileNode(tableModel, fileId, DeltaFileAction.REMOVE, version, nextFileId, partitionColumns, remove = action.remove)
                    action.cdc != null -> fileNode(tableModel, fileId, DeltaFileAction.CDC, version, nextFileId, partitionColumns, cdc = action.cdc)
                    else -> null
                } ?: return@forEach
                nextFileId++
                nodes += node
                edges += GraphEdge("e_file_${versionId}_$fileId", versionId, fileId)
                if (node.action != DeltaFileAction.REMOVE && node.localPath != null) {
                    sampleRows[fileId] = sampleRowFactory(node)
                }
            }

            tableModel.listing.checkpoints[version].orEmpty().forEach { checkpoint ->
                val checkpointId = "dcp_${version}_${nextCheckpointId}"
                nodes += GraphNode.DeltaCheckpointNode(
                    id = checkpointId,
                    checkpoint = checkpoint,
                    simpleId = nextCheckpointId++,
                    check = DeferredRead.of { tableModel.checkDeltaCheckpoint(checkpoint) },
                )
                edges += GraphEdge("e_cp_${versionId}_$checkpointId", versionId, checkpointId)
            }
        }

        return GraphBuildResult(nodes = nodes, edges = edges, summary = summary, sampleRows = sampleRows)
    }

    private fun fileNode(
        tableModel: DeltaUnifiedTableModel,
        id: String,
        action: DeltaFileAction,
        version: Long,
        simpleId: Int,
        partitionColumns: List<String>,
        add: DeltaAddFile? = null,
        remove: DeltaRemoveFile? = null,
        cdc: DeltaCdcFile? = null,
        liveNow: Boolean = false,
        pathLiveNow: Boolean = false,
    ): GraphNode.DeltaFileNode {
        val recorded = add?.path ?: remove?.path ?: cdc?.path ?: ""
        val localPath = runCatching { tableModel.resolve(recorded).toString() }.getOrNull()
        val vector = add?.deletionVector ?: remove?.deletionVector
        return GraphNode.DeltaFileNode(
            id = id,
            action = action,
            version = version,
            simpleId = simpleId,
            add = add,
            remove = remove,
            cdc = cdc,
            localPath = localPath,
            partitionColumns = partitionColumns,
            liveNow = liveNow,
            pathLiveNow = pathLiveNow,
            deletionVectorPath = vector?.filePath(tableModel.path)?.toString(),
            deletionVector = vector?.let { v -> DeferredRead.of { readDeletionVector(tableModel, v) } } ?: DeferredRead.none(),
        )
    }

    /**
     * A vector's bytes decoded. A stored vector is read at `offset` (1 where it is absent: the
     * file opens with a version byte) for its 4-byte size, its `sizeInBytes` of magic and bitmap,
     * and its 4-byte CRC — Iceberg's Puffin blob byte for byte, which the spec made it on purpose.
     * An inline vector carries the magic and bitmap alone, so it is framed here and its CRC is
     * computed rather than read: there is none to check.
     */
    internal fun readDeletionVector(tableModel: DeltaUnifiedTableModel, vector: DeltaDeletionVector): DeletionVector? = runCatching {
        if (vector.storageType == "i") {
            val data = Z85.decode(vector.pathOrInlineDv.orEmpty()).let { bytes -> vector.sizeInBytes?.let { bytes.copyOf(it) } ?: bytes }
            val crc = CRC32().apply { update(data) }.value.toInt()
            val blob = ByteBuffer.allocate(4 + data.size + 4).order(ByteOrder.BIG_ENDIAN).putInt(data.size).put(data).putInt(crc).array()
            PuffinReader.decodeDeletionVector(blob, recordedCardinality = vector.cardinality)
        } else {
            val file = requireNotNull(vector.filePath(tableModel.path)) { "the vector's location ${vector.pathOrInlineDv} does not decode" }
            val size = requireNotNull(vector.sizeInBytes) { "the vector records no sizeInBytes" }
            PuffinReader.readDeletionVector(file, (vector.offset ?: 1).toLong(), 4L + size + 4L, recordedCardinality = vector.cardinality)
        }
    }.onFailure { logger.warn("Could not read the deletion vector {}: {}", vector.uniqueId, it.message) }.getOrNull()

    /**
     * The rows of a drawn file, with the partition values beside them: a Delta writer does not
     * put a partition column into the file — the value is in the log and the directory — so a
     * row read from the file alone would be missing it.
     */
    private fun sampleRowFactory(node: GraphNode.DeltaFileNode): () -> List<GraphNode.RowNode> = {
        val localPath = node.localPath.orEmpty()
        if (!Files.exists(StorageLocation.pathOf(localPath))) {
            emptyList()
        } else {
            val decoded = if (node.deletionVector.isPresent) node.deletionVector.value else null
            val deleted = decoded?.positions?.toSet().orEmpty()
            val resolved = !node.deletionVector.isPresent || decoded != null
            val rows by lazy { SampleRowReader.querySampleRows(localPath).map(::unifiedRowOf) }
            (0 until GraphNode.RowNode.countFor(node.stats?.numRecords)).map { rowIndex ->
                GraphNode.RowNode(
                    id = "row_${node.id}_$rowIndex",
                    data = mapOf("file_no" to node.simpleId, "row_idx" to rowIndex),
                    deletedPositions = deleted,
                    vectorsResolved = resolved,
                    dataLoader = {
                        try {
                            rows.getOrNull(rowIndex)?.let { row ->
                                buildMap<String, Any> {
                                    put("file_no", node.simpleId)
                                    put("row_idx", rowIndex)
                                    put("local_file_path", localPath)
                                    putAll(row.cells)
                                    node.partitionColumns.forEach { c -> put(c, node.partitionValues[c] ?: "null") }
                                    row.position?.let { put(GraphNode.RowNode.ROW_POSITION_KEY, it) }
                                }
                            } ?: emptyMap()
                        } catch (e: Exception) {
                            logger.warn("Failed to load rows for file {}: {}", localPath, e.message)
                            mapOf("file_no" to node.simpleId, "row_idx" to rowIndex, GraphNode.RowNode.ROW_READ_ERROR_KEY to (e.message ?: e.javaClass.simpleName))
                        }
                    },
                )
            }
        }
    }

    internal fun buildTableSummary(tableModel: DeltaUnifiedTableModel): TableSummary {
        val current = tableModel.current
        val metadata = current?.metadata

        // Current: the live set, one contribution per place a live file came from — the commit
        // that added it, or the checkpoint the replay started at — so the drill-down says where.
        val sourceOf = mutableMapOf<DeltaFileKey, String>()
        current?.commitsReplayed?.forEach { v ->
            tableModel.commitByVersion[v]?.adds?.forEach { sourceOf[it.key] = "commit $v" }
        }
        val currentContributions = current?.files?.entries
            ?.groupBy { (key, _) -> sourceOf[key] ?: "checkpoint ${current.fromCheckpoint}" }
            ?.map { (source, entries) -> ManifestContribution(manifestPath = source, delta = statsOf(entries.map { it.value })) }
            .orEmpty()

        // History: every logical file any retained commit or the starting checkpoint added,
        // counted once — what the log still reaches, which VACUUM has not necessarily removed.
        val seen = mutableSetOf<DeltaFileKey>()
        val historyContributions = mutableListOf<ManifestContribution>()
        current?.fromCheckpoint?.let { cp ->
            tableModel.stateAt(cp).getOrNull()?.files?.values?.filter { seen.add(it.key) }?.takeIf { it.isNotEmpty() }?.let {
                historyContributions += ManifestContribution("checkpoint $cp", statsOf(it))
            }
        }
        tableModel.commits.forEach { commit ->
            val fresh = commit.adds.filter { seen.add(it.key) }
            if (fresh.isNotEmpty()) historyContributions += ManifestContribution("commit ${commit.version}", statsOf(fresh))
        }

        val times = tableModel.commits.mapNotNull { it.timestampMs }
        return TableSummary(
            tableName = tableModel.name,
            tablePath = tableModel.path.toString(),
            location = tableModel.path.toString(),
            tableUuid = metadata?.id,
            formatVersion = null,
            currentSnapshotId = tableModel.latestVersion,
            currentMetadataVersion = null,
            versionHintText = null,
            tableCreationMs = metadata?.createdTime ?: times.minOrNull(),
            tableLastUpdateMs = times.maxOrNull(),
            lastUpdatedMs = times.maxOrNull(),
            metadataFileCount = 0,
            snapshotCount = tableModel.versions.size,
            snapshotManifestListFileCount = tableModel.commits.size,
            currentDerivation = StatsDerivation(currentContributions),
            historyDerivation = StatsDerivation(historyContributions),
            metadataFileTimes = FileTimeRange(),
            snapshotManifestListFileTimes = FileTimeRange(),
            manifestFileTimes = FileTimeRange(),
            dataFileTimes = FileTimeRange(),
            delta = tableModel.tableFacts(),
        )
    }

    /**
     * A set of live files as figures. A deletion vector is counted the way Iceberg counts its v3
     * vector — a positional delete file whose rows are the positions it marks — because that is
     * what it is, stored apart from the data file, so the table's delete figures mean the same on
     * all three formats. Vectors are counted once by identity; two files' vectors share a `.bin`.
     */
    private fun statsOf(files: List<DeltaAddFile>): ContentStats {
        val vectors = files.mapNotNull { it.deletionVector }.distinctBy { it.uniqueId }
        return ContentStats(
            manifestEntryCount = files.size,
            dataFileCount = files.size,
            posDeleteFileCount = vectors.size,
            recordCount = files.sumOf { it.parsedStats?.numRecords ?: 0L },
            deleteRecordCount = vectors.sumOf { it.cardinality ?: 0L },
            dataSizeBytes = files.sumOf { it.size ?: 0L },
            deleteSizeBytes = vectors.sumOf { (it.sizeInBytes ?: 0).toLong() },
        )
    }
}
