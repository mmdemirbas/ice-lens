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

    /** Max data files shown per manifest in the graph. */
    private const val MAX_FILES_PER_MANIFEST = 10

    /** Max sample rows created per data file. */
    private const val MAX_ROWS_PER_FILE = 5

    /**
     * Builds graph nodes and edges for the given Iceberg table model.
     * Does not perform layout — call [GraphLayoutService.layoutGraph] with the result.
     */
    fun buildGraph(
        tableModel: UnifiedTableModel,
        showRows: Boolean,
    ): GraphBuildResult {
        val logicalNodes = mutableMapOf<String, GraphNode>()
        val edges = mutableListOf<GraphEdge>()
        val edgeIds = mutableSetOf<String>()
        val pendingLineage = mutableListOf<Pair<Long, Long?>>()
        val currentRefs = currentRefsBySnapshot(tableModel)
        val processedManifests = mutableSetOf<String>()
        val manifestPathToId = mutableMapOf<String, String>()
        val tableNodeId = "table_root"

        val tableSummary = buildTableSummary(tableModel)
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
                    rawJson = metadata.rawJson
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
                    logicalNodes[sId] = GraphNode.SnapshotNode(
                        id = sId,
                        data = snap,
                        simpleId = simpleSnapshotId,
                        localPath = snapshot.path.toString(),
                        refs = snap.snapshotId?.let { currentRefs[it] }.orEmpty(),
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
                    val fallbackSeq = manifest.sequenceNumber ?: Long.MAX_VALUE
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
                            shownEntryCount = minOf(unifiedDataFiles.size, MAX_FILES_PER_MANIFEST),
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
                            unifiedDataFiles.take(MAX_FILES_PER_MANIFEST).forEachIndexed { fileIndex, unifiedDataFile ->
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
                                        localPath = unifiedDataFile.path.toString()
                                    )
                                }

                                val edgeId = "e_file_${manId}_to_$fId"
                                edgeIds.add(edgeId)
                                edges.add(GraphEdge(edgeId, manId, fId))

                                if (showRows) {
                                    // UnifiedManifest already resolved this file against the table
                                    // directory, and UnifiedDataFile.rows reads from that same
                                    // path. Re-deriving it here from a "/<tableName>/" marker gave
                                    // a second answer that could disagree with the first, and when
                                    // it did the rows were silently dropped for a file that exists.
                                    if (Files.isRegularFile(unifiedDataFile.path)) {
                                        val contentType = entry.dataFile?.content ?: 0
                                        val maxRows = MAX_ROWS_PER_FILE
                                        for (rIdx in 0 until maxRows) {
                                            val rId = "row_${fId}_$rIdx"
                                            if (logicalNodes.containsKey(rId)) continue

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

        return GraphBuildResult(
            nodes = logicalNodes.values.toList(),
            edges = edges,
            summary = tableSummary,
        )
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
    private fun currentRefsBySnapshot(tableModel: UnifiedTableModel): Map<Long, List<SnapshotRefLabel>> =
        tableModel.metadatas.lastOrNull()?.metadata?.refs.orEmpty()
            .mapNotNull { (name, ref) ->
                ref.snapshotId?.let { id ->
                    id to SnapshotRefLabel(name, isBranch = !ref.type.equals("tag", ignoreCase = true))
                }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, labels) -> labels.sortedWith(compareBy({ it.name != "main" }, { it.name })) }

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

        private fun contributionOf(manifest: UnifiedManifest): ManifestContribution {
            var entries = 0
            var deletedEntries = 0
            var dataFiles = 0
            var posDeleteFiles = 0
            var eqDeleteFiles = 0
            var records = 0L
            var deleteRecords = 0L
            var dataBytes = 0L
            var deleteBytes = 0L
            var suppressed = 0

            manifest.dataFiles.forEach { unifiedDataFile ->
                // Every entry counts toward scan cost, including the ones recording a removal.
                entries++
                val isRemoval = unifiedDataFile.metadata.status == ManifestEntryStatus.DELETED
                if (isRemoval) deletedEntries++
                if (liveEntriesOnly && isRemoval) return@forEach
                if (!seenFiles.add(dataFileKey(unifiedDataFile))) {
                    suppressed++
                    return@forEach
                }

                val dataFile = unifiedDataFile.metadata.dataFile
                val rows = dataFile?.recordCount ?: 0L
                val bytes = dataFile?.fileSizeInBytes ?: 0L
                when (dataFile?.content ?: DataFileContent.DATA) {
                    DataFileContent.POSITION_DELETES -> {
                        posDeleteFiles++; deleteRecords += rows; deleteBytes += bytes
                    }
                    DataFileContent.EQUALITY_DELETES -> {
                        eqDeleteFiles++; deleteRecords += rows; deleteBytes += bytes
                    }
                    else -> {
                        dataFiles++; records += rows; dataBytes += bytes
                    }
                }
            }

            val isDeleteManifest = manifest.metadata.content == ManifestContent.DELETES
            return ManifestContribution(
                manifestPath = manifest.path.toString(),
                delta = ContentStats(
                    dataManifestCount = if (isDeleteManifest) 0 else 1,
                    deleteManifestCount = if (isDeleteManifest) 1 else 0,
                    manifestEntryCount = entries,
                    deletedEntryCount = deletedEntries,
                    dataFileCount = dataFiles,
                    posDeleteFileCount = posDeleteFiles,
                    eqDeleteFileCount = eqDeleteFiles,
                    recordCount = records,
                    deleteRecordCount = deleteRecords,
                    dataSizeBytes = dataBytes,
                    deleteSizeBytes = deleteBytes,
                ),
                entriesSuppressedAsDuplicate = suppressed,
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
