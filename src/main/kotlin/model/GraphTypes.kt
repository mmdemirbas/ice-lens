package model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// The logical graph model used by the UI
data class GraphModel(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val width: Double,
    val height: Double,
) {
    val nodeById: Map<String, GraphNode> by lazy { nodes.associateBy { it.id } }
}

data class FileTimeRange(
    val knownCount: Int = 0,
    val missingCount: Int = 0,
    val oldestMs: Long? = null,
    val newestMs: Long? = null,
)

data class MetadataVersionInfo(
    val fileName: String,
    val version: Int?,
    val fileLastModifiedMs: Long?,
    val metadataLastUpdatedMs: Long?,
    val snapshotCount: Int,
    val currentSnapshotId: Long?,
)

data class TableSummary(
    val tableName: String,
    val tablePath: String,
    val location: String?,
    val tableUuid: String?,
    val formatVersion: Int?,
    val currentSnapshotId: Long?,
    val currentMetadataVersion: Int?,
    val versionHintText: String,
    val tableCreationMs: Long?,
    val tableLastUpdateMs: Long?,
    val lastUpdatedMs: Long?,
    val metadataFileCount: Int,
    val snapshotCount: Int,
    val snapshotManifestListFileCount: Int,
    val manifestCount: Int,
    val dataManifestCount: Int,
    val deleteManifestCount: Int,
    val manifestEntryCount: Int,
    val uniqueDataFileCount: Int,
    val dataFileCount: Int,
    val posDeleteFileCount: Int,
    val eqDeleteFileCount: Int,
    val totalRecordCount: Long,
    val metadataFileTimes: FileTimeRange,
    val snapshotManifestListFileTimes: FileTimeRange,
    val manifestFileTimes: FileTimeRange,
    val dataFileTimes: FileTimeRange,
    val metadataVersions: List<MetadataVersionInfo> = emptyList(),
)

sealed class GraphNode(
    open val id: String,
    initialX: Double,
    initialY: Double,
    open val width: Double,
    open val height: Double,
) {
    var x by mutableStateOf(initialX)
    var y by mutableStateOf(initialY)

    data class TableNode(
        override val id: String,
        val summary: TableSummary,
        val initialX: Double = 0.0,
        val initialY: Double = 0.0,
    ) : GraphNode(id, initialX, initialY, 240.0, 96.0)

    data class MetadataNode(
        override val id: String,
        val simpleId: Int,
        val fileName: String,
        val data: TableMetadata,
        val localPath: String? = null,
        val rawJson: String? = null,
        val initialX: Double = 0.0,
        val initialY: Double = 0.0,
    ) : GraphNode(id, initialX, initialY, 240.0, 96.0)

    data class SnapshotNode(
        override val id: String,
        val data: Snapshot,
        val simpleId: Int,
        val localPath: String? = null,
        val initialX: Double = 0.0,
        val initialY: Double = 0.0,
    ) : GraphNode(id, initialX, initialY, 210.0, 84.0)

    data class ManifestNode(
        override val id: String,
        val data: ManifestListEntry,
        val simpleId: Int,
        val localPath: String? = null,
        val initialX: Double = 0.0,
        val initialY: Double = 0.0,
    ) : GraphNode(id, initialX, initialY, 200.0, 80.0)

    data class FileNode(
        override val id: String,
        val entry: ManifestEntry,
        val simpleId: Int,
        val localPath: String? = null,
        val initialX: Double = 0.0,
        val initialY: Double = 0.0,
    ) : GraphNode(id, initialX, initialY, 200.0, 60.0) {
        val data: DataFile get() = entry.dataFile ?: DataFile(filePath = "unknown")
    }

    data class RowNode(
        override val id: String,
        val data: Map<String, Any>,
        val content: Int = 0, // 0=Data, 1=Pos Delete, 2=Eq Delete
        val identifierFields: List<String> = emptyList(),
        val initialX: Double = 0.0,
        val initialY: Double = 0.0,
    ) : GraphNode(id, initialX, initialY, 200.0, 80.0) {
        val isDelete: Boolean get() = content > 0
    }

    data class ErrorNode(
        override val id: String,
        val title: String,
        val stage: String,
        val path: String,
        val message: String,
        val stackTrace: String? = null,
        val initialX: Double = 0.0,
        val initialY: Double = 0.0,
    ) : GraphNode(id, initialX, initialY, 280.0, 100.0)
}

data class GraphEdge(
    val id: String,
    val fromId: String,
    val toId: String,
    val isSibling: Boolean = false,
    val sections: List<EdgeSection> = emptyList(),
)

data class EdgeSection(
    val startX: Double,
    val startY: Double,
    val endX: Double,
    val endY: Double,
)
