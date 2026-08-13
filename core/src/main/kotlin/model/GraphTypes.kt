package model

/**
 * A point in graph coordinate space.
 *
 * Core carries its own point type rather than a UI toolkit's, so that the engine can be used
 * from a shell that has no toolkit at all — a server, a CLI, an IDE plugin.
 */
data class Point(val x: Float, val y: Float) {
    companion object {
        val ORIGIN = Point(0f, 0f)
    }
}

/**
 * The laid-out graph: what the table's metadata tree contains, and where layout decided to put
 * each node.
 *
 * [layoutPositions] is immutable and computed once. A user dragging a node does not change the
 * graph — that is shell state, layered over this by whichever front end is displaying it. Keeping
 * the distinction means a [GraphModel] is safe to cache, to compare, to serialise and to build
 * off the main thread.
 */
data class GraphModel(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val width: Double,
    val height: Double,
    val layoutPositions: Map<String, Point> = emptyMap(),
) {
    val nodeById: Map<String, GraphNode> by lazy { nodes.associateBy { it.id } }

    fun layoutPosition(nodeId: String): Point = layoutPositions[nodeId] ?: Point.ORIGIN
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

/**
 * File, record and byte figures over one deduplicated set of manifests.
 *
 * Deduplication is not an optimization here, it is the definition. Table formats share
 * structure on purpose: one Iceberg manifest is referenced by every snapshot that carries
 * its files forward, and every snapshot is re-listed in every `metadata.json` written after
 * it. Counting per traversal therefore multiplies with commit history rather than with data.
 *
 * [manifestEntryCount] counts every entry in the covered manifests regardless of status,
 * because it measures how much a reader has to scan. The file counts, record counts and
 * byte totals cover live entries only, because they describe the table's contents.
 * Delete-file records are counted in [deleteRecordCount], never in [recordCount] — a
 * positional delete file's `record_count` is a number of delete records, not of table rows.
 */
data class ContentStats(
    val dataManifestCount: Int = 0,
    val deleteManifestCount: Int = 0,
    val manifestEntryCount: Int = 0,
    /**
     * Entries recording a file's removal — Iceberg `status=DELETED`, Paimon `_KIND=1`. These
     * are counted in [manifestEntryCount] but contribute no files, records or bytes.
     */
    val deletedEntryCount: Int = 0,
    val dataFileCount: Int = 0,
    val posDeleteFileCount: Int = 0,
    val eqDeleteFileCount: Int = 0,
    val recordCount: Long = 0L,
    val deleteRecordCount: Long = 0L,
    val dataSizeBytes: Long = 0L,
    val deleteSizeBytes: Long = 0L,
) {
    val manifestCount: Int get() = dataManifestCount + deleteManifestCount
    val deleteFileCount: Int get() = posDeleteFileCount + eqDeleteFileCount
    val fileCount: Int get() = dataFileCount + deleteFileCount
    val totalSizeBytes: Long get() = dataSizeBytes + deleteSizeBytes
}

data class TableSummary(
    val tableName: String,
    val tablePath: String,
    val location: String?,
    val tableUuid: String?,
    val formatVersion: Int?,
    val currentSnapshotId: Long?,
    val currentMetadataVersion: Int?,
    /** `metadata/version-hint.text`, or null when absent — normal for catalog-managed tables. */
    val versionHintText: String?,
    val tableCreationMs: Long?,
    val tableLastUpdateMs: Long?,
    val lastUpdatedMs: Long?,
    val metadataFileCount: Int,
    val snapshotCount: Int,
    val snapshotManifestListFileCount: Int,
    /**
     * The table as it is now: the manifest closure of `current-snapshot-id`, live entries
     * only. [ContentStats.recordCount] here is the figure a `SELECT count(*)` should agree
     * with, before delete files are applied. All-zero when the table has no current snapshot.
     */
    val current: ContentStats = ContentStats(),
    /**
     * Everything still reachable from any retained snapshot, deduplicated by manifest path
     * and by data-file path. This is the "what is still on disk / what can I not expire yet"
     * view, so it counts entries of every status — a file removed in a later commit still
     * occupies storage until the snapshot referencing it expires.
     */
    val history: ContentStats = ContentStats(),
    val metadataFileTimes: FileTimeRange,
    val snapshotManifestListFileTimes: FileTimeRange,
    val manifestFileTimes: FileTimeRange,
    val dataFileTimes: FileTimeRange,
    val metadataVersions: List<MetadataVersionInfo> = emptyList(),
)

/**
 * One manifest entry as the inspector shows it: the entry itself, the stable per-file number
 * the graph labels it with, and where the file resolved to on disk.
 */
data class ManifestEntryView(
    val simpleId: Int,
    val entry: ManifestEntry,
    val localPath: String,
    /** This entry's partition tuple, decoded against the manifest's own spec. */
    val partition: DecodedPartition? = null,
)

/** Paimon counterpart of [ManifestEntryView]. */
data class PaimonManifestEntryView(
    val simpleId: Int,
    val entry: PaimonManifestEntry,
    val localPath: String,
)

/**
 * Graph node base class. Subclasses are data classes for clean equality/hashing.
 *
 * `x` and `y` are scratch vars the layout engine writes while it works. The result it publishes
 * is [GraphModel.layoutPositions]; a shell reads from there, and layers its own drag state over
 * it rather than writing back here.
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
        /**
         * Every entry in this manifest, in apply order.
         *
         * The graph renders at most a handful of them as child [FileNode]s so a manifest with
         * thousands of files stays readable, but the inspector lists all of them — a cap that
         * hides data without saying so is indistinguishable from a manifest that really is
         * that small.
         */
        val entries: List<ManifestEntryView> = emptyList(),
        /** How many of [entries] were given a child node in the graph. */
        val shownEntryCount: Int = 0,
        /** The schema this manifest was written against — the only correct one for its bounds. */
        val schema: IcebergSchemaModel? = null,
        val localPath: String? = null,
        val initialX: Double = 0.0,
        val initialY: Double = 0.0,
    ) : GraphNode(id, initialX, initialY, 200.0, 80.0) {
        val hiddenEntryCount: Int get() = (entries.size - shownEntryCount).coerceAtLeast(0)
    }

    data class FileNode(
        override val id: String,
        val entry: ManifestEntry,
        val simpleId: Int,
        /** The schema of the manifest this entry came from. See [ManifestNode.schema]. */
        val schema: IcebergSchemaModel? = null,
        /** This file's partition tuple, decoded against the manifest's own spec. */
        val partition: DecodedPartition? = null,
        val localPath: String? = null,
        val initialX: Double = 0.0,
        val initialY: Double = 0.0,
    ) : GraphNode(id, initialX, initialY, 200.0, 60.0) {
        val data: DataFile get() = entry.dataFile ?: DataFile(filePath = "unknown")

        /** Per-column statistics with bounds decoded against [schema]. */
        val columnStats: List<ColumnStats> by lazy { columnStatsFor(data, schema) }
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
        val resolvedData: Map<String, Any> by lazy {
            val loaded = dataLoader?.invoke()
            if (loaded.isNullOrEmpty()) data else loaded
        }
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
        /** Every entry in this manifest — see [ManifestNode.entries] for why all of them. */
        val entries: List<PaimonManifestEntryView> = emptyList(),
        /** How many of [entries] were given a child node in the graph. */
        val shownEntryCount: Int = 0,
        val localPath: String? = null,
        val initialX: Double = 0.0,
        val initialY: Double = 0.0,
    ) : GraphNode(id, initialX, initialY, 200.0, 80.0) {
        val hiddenEntryCount: Int get() = (entries.size - shownEntryCount).coerceAtLeast(0)
    }

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
