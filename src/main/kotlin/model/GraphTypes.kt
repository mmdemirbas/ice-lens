package model

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Offset

// The logical graph model used by the UI
data class GraphModel(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val width: Double,
    val height: Double,
    /** Thread-safe initial positions set during layout. NOT Compose state — safe on any thread. */
    val initialPositions: Map<String, Offset> = emptyMap(),
) {
    val nodeById: Map<String, GraphNode> by lazy { nodes.associateBy { it.id } }

    /** Compose-observable node positions for the UI. Must only be read/written on the main thread. */
    val positions = mutableStateMapOf<String, Offset>()

    fun getPosition(nodeId: String): Offset =
        positions[nodeId] ?: initialPositions[nodeId] ?: Offset(0f, 0f)

    /** Update a position in the Compose-observable map. Call on main thread only. */
    fun setPosition(nodeId: String, x: Double, y: Double) {
        positions[nodeId] = Offset(x.toFloat(), y.toFloat())
    }

    /** Sync initial positions into the Compose-observable map. Call on main thread only. */
    fun syncPositionsToUI() {
        initialPositions.forEach { (id, pos) ->
            if (id !in positions) {
                positions[id] = pos
            }
        }
    }
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

/**
 * Graph node base class. Subclasses are data classes for clean equality/hashing.
 * `x` and `y` are plain mutable vars used during layout computation (NOT Compose state).
 * The UI reads positions from `GraphModel.positions` (Compose-observable).
 */
sealed class GraphNode(
    open val id: String,
    initialX: Double,
    initialY: Double,
    open val width: Double,
    open val height: Double,
) {
    var x: Double = initialX
    var y: Double = initialY

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
        /** Placeholder row nodes have empty data; actual data is loaded lazily via this supplier. */
        private val dataLoader: (() -> Map<String, Any>)? = null,
    ) : GraphNode(id, initialX, initialY, 200.0, 80.0) {
        val isDelete: Boolean get() = content > 0
        val resolvedData: Map<String, Any> by lazy { dataLoader?.invoke() ?: data }
    }

    // --- Paimon node types ---

    /** Paimon snapshot node. */
    data class PaimonSnapshotNode(
        override val id: String,
        val data: PaimonSnapshot,
        val simpleId: Int,
        val commitKind: String? = null,
        val localPath: String? = null,
        val initialX: Double = 0.0,
        val initialY: Double = 0.0,
    ) : GraphNode(id, initialX, initialY, 210.0, 84.0)

    /** Paimon schema node. */
    data class PaimonSchemaNode(
        override val id: String,
        val data: PaimonSchema,
        val simpleId: Int,
        val localPath: String? = null,
        val initialX: Double = 0.0,
        val initialY: Double = 0.0,
    ) : GraphNode(id, initialX, initialY, 220.0, 80.0)

    /** Paimon manifest list node (base, delta, or changelog). */
    data class PaimonManifestListNode(
        override val id: String,
        val kind: String,                  // "base", "delta", "changelog"
        val simpleId: Int,
        val localPath: String? = null,
        val initialX: Double = 0.0,
        val initialY: Double = 0.0,
    ) : GraphNode(id, initialX, initialY, 220.0, 80.0)

    /** Paimon manifest node (references a single manifest file within a manifest list). */
    data class PaimonManifestNode(
        override val id: String,
        val data: PaimonManifestFileMeta,
        val simpleId: Int,
        val localPath: String? = null,
        val initialX: Double = 0.0,
        val initialY: Double = 0.0,
    ) : GraphNode(id, initialX, initialY, 200.0, 80.0)

    /** Paimon data file node. */
    data class PaimonDataFileNode(
        override val id: String,
        val entry: PaimonManifestEntry,
        val simpleId: Int,
        val bucket: Int? = null,
        val level: Int? = null,
        val operationKind: Int? = null,     // 0=ADD, 1=DELETE
        val localPath: String? = null,
        val initialX: Double = 0.0,
        val initialY: Double = 0.0,
    ) : GraphNode(id, initialX, initialY, 200.0, 60.0)

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
