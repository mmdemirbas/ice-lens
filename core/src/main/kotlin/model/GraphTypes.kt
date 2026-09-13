package model

/**
 * Something a node can read on demand, held so that reading it is not part of the node's identity.
 *
 * A `GraphNode` is a value: two nodes describing the same artifact are the same node, whether or
 * not one of them has since opened a file. Several nodes carry a read they defer — a deletion
 * vector's Puffin blob, the live file set behind a snapshot — because the graph is built for every
 * artifact the metadata names and most are never looked at.
 *
 * A bare lambda in a data class's primary constructor cannot express that. **A `private val`
 * parameter is still a component of the generated `equals`**, and two lambdas written at the same
 * source position are two distinct objects, so two nodes for the same artifact from two graph
 * builds compared *unequal* — which is a comment claiming an invariant the compiler had already
 * decided against, and which costs Compose the chance to skip a card that has not changed.
 *
 * So the lambda lives in here, and this type's `equals` says what the node means: all deferred
 * reads are alike, because none of them is part of what the node *is*. That is the whole reason
 * this class exists; do not reach for it as a general lazy holder, where ignoring the contents in
 * `equals` would be a lie rather than a definition.
 */
class DeferredRead<T : Any> private constructor(private val load: (() -> T?)?) {

    /** The value, read on first ask. Null when there is nothing to read, or the read gave nothing. */
    val value: T? by lazy { load?.invoke() }

    /** Whether there is anything to read at all, answerable without reading it. */
    val isPresent: Boolean get() = load != null

    override fun equals(other: Any?): Boolean = other is DeferredRead<*>
    override fun hashCode(): Int = 0
    override fun toString(): String = if (isPresent) "DeferredRead(unread or read)" else "DeferredRead(nothing)"

    companion object {
        /** Nothing to read — the ordinary case for a node the deferred read does not apply to. */
        fun <T : Any> none(): DeferredRead<T> = DeferredRead(null)

        fun <T : Any> of(load: () -> T?): DeferredRead<T> = DeferredRead(load)
    }
}

/**
 * A snapshot node of any format, in the terms a comparison needs.
 *
 * Two snapshots are compared as a set difference between their live file sets ([snapshotDiff]),
 * and that question is format-agnostic even though the two answers are reached by completely
 * different rules — Iceberg filters and deduplicates entries, Paimon replays a delta over a base.
 * This interface is the seam: the panel reads these six things and never asks which format it is
 * looking at, while each node type answers them its own way.
 *
 * It deliberately does not expose the format's own snapshot object. A comparison that reached for
 * `data.summary` would be an Iceberg comparison wearing a shared name.
 */
interface ComparableSnapshot {
    /** Node id, for keying the panel's memoisation. */
    val nodeId: String

    /** The number the reader sees on the card — "Snapshot 3". */
    val displayNumber: Int

    /** The format's own identifier for this commit. */
    val commitId: Long?

    /** The commit this one follows, where the format records one. Null on a root, and for Paimon. */
    val parentCommitId: Long?

    /**
     * The commit order the format assigns, used to put the older side first.
     *
     * A sequence number, not a clock: two commits from a fast writer can share a millisecond, and
     * a panel whose "from" and "to" swap between recompositions is worse than one that is wrong
     * in a stated way.
     */
    val commitOrder: Long?

    val commitTimeMs: Long?

    /** Every file the table holds at this snapshot, read on first ask. Null when nothing attached. */
    val liveFiles: List<LiveFile>?

    /** Whether this snapshot can be compared at all, answerable without walking it. */
    val canDiff: Boolean
}

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

    val groups: List<GraphNode.GroupNode> by lazy { nodes.filterIsInstance<GraphNode.GroupNode>() }

    /**
     * How many nodes aggregation left out of the drawing.
     *
     * Summed from the groups standing for them rather than recorded alongside, so a graph
     * cannot claim to hide a number of nodes that no group accounts for.
     */
    val hiddenNodeCount: Int get() = groups.sumOf { it.hiddenNodeCount }

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
    /**
     * Of [recordCount], the rows in Paimon data-evolution patch files — columns of rows another
     * live file holds, counted by the writer's own totals and returned by no scan. Zero for
     * Iceberg. See [LiveFile.partial].
     */
    val partialRecordCount: Long = 0L,
) {
    val manifestCount: Int get() = dataManifestCount + deleteManifestCount
    val deleteFileCount: Int get() = posDeleteFileCount + eqDeleteFileCount
    val fileCount: Int get() = dataFileCount + deleteFileCount
    val totalSizeBytes: Long get() = dataSizeBytes + deleteSizeBytes
    /** [recordCount] less the patch rows: the rows a scan returns. */
    val readRecordCount: Long get() = recordCount - partialRecordCount

    operator fun plus(other: ContentStats): ContentStats = ContentStats(
        dataManifestCount = dataManifestCount + other.dataManifestCount,
        deleteManifestCount = deleteManifestCount + other.deleteManifestCount,
        manifestEntryCount = manifestEntryCount + other.manifestEntryCount,
        deletedEntryCount = deletedEntryCount + other.deletedEntryCount,
        dataFileCount = dataFileCount + other.dataFileCount,
        posDeleteFileCount = posDeleteFileCount + other.posDeleteFileCount,
        eqDeleteFileCount = eqDeleteFileCount + other.eqDeleteFileCount,
        recordCount = recordCount + other.recordCount,
        deleteRecordCount = deleteRecordCount + other.deleteRecordCount,
        dataSizeBytes = dataSizeBytes + other.dataSizeBytes,
        deleteSizeBytes = deleteSizeBytes + other.deleteSizeBytes,
        partialRecordCount = partialRecordCount + other.partialRecordCount,
    )
}

/**
 * What one manifest contributed to a [ContentStats] total.
 *
 * A contribution can be negative. Paimon applies a snapshot's delta manifest list over its base,
 * so a manifest whose entries record removals takes files back out of the running total; writing
 * that as a negative delta keeps the fold honest instead of hiding the removal inside a replay.
 */
data class ManifestContribution(
    val manifestPath: String,
    /** Zero for a manifest that was already counted — see [firstCountedIn]. */
    val delta: ContentStats,
    /** Entries whose data file another manifest had already counted. */
    val entriesSuppressedAsDuplicate: Int = 0,
    /**
     * Where this manifest was first counted, when this sighting is a repeat.
     *
     * Every snapshot re-lists the manifests it carries forward, so most manifests in a table's
     * history are visited many times. Naming the first sighting is what turns "4 data files"
     * from an assertion into something the reader can check — it is the same fact as the count,
     * seen from the other side.
     */
    val firstCountedIn: String? = null,
) {
    val isRepeat: Boolean get() = firstCountedIn != null
}

/**
 * A [ContentStats] total together with the ledger it was folded from.
 *
 * [total] is derived *from* [contributions] rather than accumulated beside them, which is the
 * whole point: an explanation computed separately is a second implementation, and two
 * implementations disagree eventually. Drop a contribution here and the number on screen
 * changes with it.
 */
data class StatsDerivation(val contributions: List<ManifestContribution> = emptyList()) {
    val total: ContentStats = contributions.fold(ContentStats()) { running, it -> running + it.delta }

    /** Manifests skipped because an earlier snapshot had already counted them. */
    val repeats: List<ManifestContribution> get() = contributions.filter { it.isRepeat }

    val entriesSuppressedAsDuplicate: Int get() = contributions.sumOf { it.entriesSuppressedAsDuplicate }
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
     * The per-manifest ledger [current] is folded from.
     *
     * The summary carries the derivation rather than the figure because the figure is the
     * derivation summed — see [StatsDerivation].
     */
    val currentDerivation: StatsDerivation = StatsDerivation(),
    /** The per-manifest ledger [history] is folded from. */
    val historyDerivation: StatsDerivation = StatsDerivation(),
    val metadataFileTimes: FileTimeRange,
    val snapshotManifestListFileTimes: FileTimeRange,
    val manifestFileTimes: FileTimeRange,
    val dataFileTimes: FileTimeRange,
    val metadataVersions: List<MetadataVersionInfo> = emptyList(),
    /**
     * What `branch/` holds on a Paimon table, one entry per branch, empty when the directory is
     * absent — and null on an Iceberg table, whose branches are refs on the metadata file and are
     * listed there. Null and empty are kept apart so the panel can say "no branches" only where
     * the format keeps them here.
     */
    val branches: List<BranchSummary>? = null,
    /** What `consumer/` holds on a Paimon table; null on Iceberg, which has no such thing. */
    val consumers: List<ConsumerSummary>? = null,
    /**
     * What a Paimon expiry is decided from — see [PaimonExpiryInput] — so the panel can plan one
     * without the model. Null on Iceberg, whose plan is read off the metadata node instead.
     */
    val paimonExpiry: PaimonExpiryInput? = null,
) {
    /**
     * The table as it is now: the manifest closure of `current-snapshot-id`, live entries
     * only. [ContentStats.recordCount] here is the figure a `SELECT count(*)` should agree
     * with, before delete files are applied. All-zero when the table has no current snapshot.
     */
    val current: ContentStats get() = currentDerivation.total

    /**
     * Everything still reachable from any retained snapshot, deduplicated by manifest path
     * and by data-file path. This is the "what is still on disk / what can I not expire yet"
     * view, so it counts entries of every status — a file removed in a later commit still
     * occupies storage until the snapshot referencing it expires.
     */
    val history: ContentStats get() = historyDerivation.total
}

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
    /** The entry's `_PARTITION`, decoded — see [decodePaimonPartition]. Null when it could not be. */
    val partition: DecodedPaimonPartition? = null,
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
        /**
         * What is under the table root that no metadata names — see [findUnreferencedFiles]. A
         * walk of the whole table directory, so deferred to a click rather than run at build.
         */
        val unreferencedFiles: DeferredRead<UnreferencedFilesReport> = DeferredRead.none(),
        /**
         * What an Iceberg expiry's file plan reads — see [ExpiryFileInput] — built from the model,
         * never from the drawn graph: aggregation folds snapshots and manifests past the page
         * size out of the graph, and a plan over the drawn part alone misses exactly the older
         * lists an expiry removes. Nothing on Paimon.
         */
        val expiryFiles: DeferredRead<ExpiryFileInput> = DeferredRead.none(),
        /** The Paimon twin — see [PaimonExpiryFileInput]; nothing on Iceberg. */
        val paimonExpiryFiles: DeferredRead<PaimonExpiryFileInput> = DeferredRead.none(),
        /**
         * The newest metadata and the current snapshot's node, for the planners — see
         * [MaintenanceInput] for why they are carried here rather than looked up on the drawn
         * graph. Deferred because the builder makes this node before the snapshot nodes exist.
         */
        val maintenance: DeferredRead<MaintenanceInput> = DeferredRead.none(),
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
        /**
         * The Puffin footer of each statistics file this metadata names, keyed by recorded path.
         *
         * Deferred, and a [DeferredRead] rather than a lambda so it stays out of the node's
         * identity: a graph is built for every metadata version a table has, and opening every
         * one's statistics file at build time is a file open per version — a network round trip
         * each, once the table is in object storage — for a panel that shows one.
         */
        val statisticsFooters: DeferredRead<Map<String, StatisticsFileFooter>> = DeferredRead.none(),
        /**
         * Each partition statistics file this metadata names, opened and read, keyed by recorded
         * path — deferred for the same reason as [statisticsFooters]. The record says a file
         * exists; the rows are the figures a planner reads, and they are in the file alone.
         */
        val partitionStatistics: DeferredRead<Map<String, PartitionStatisticsRead>> = DeferredRead.none(),
        val initialX: Double = 0.0,
        val initialY: Double = 0.0,
        // 100, not 96. `CardHeightTest`'s stress pass measured this card at exactly 96.0dp of a
        // declared 96.0 once the file name is a catalog's `00147-<uuid>.metadata.json` rather
        // than a fixture's `v1.metadata.json` — it fit by coincidence, with nothing left for the
        // rounding a different display scale does to a font metric. The name itself is capped at
        // two lines in `MetadataCard`, so this is now a bound rather than a sample.
    ) : GraphNode(id, initialX, initialY, 240.0, 100.0)

    data class SnapshotNode(
        override val id: String,
        val data: Snapshot,
        val simpleId: Int,
        val localPath: String? = null,
        /**
         * Whether [localPath] is where the table said the file is, or where forcing the name
         * relative to the local directory landed. Shown so a missing file names the path that
         * was actually looked at.
         */
        val pathResolution: PathResolution = PathResolution.FORCED_RELATIVE,
        /** See [UnifiedSnapshot.expired]: listed by an older metadata version, gone from the current one. */
        val expired: Boolean = false,
        /**
         * The `snapshot-log` entry that set `main` back past this commit, or null — see
         * [leftBehindBy]. Such a snapshot is on no ref and is not an ancestor of the current
         * one; it is drawn because the metadata still lists it.
         */
        val leftBehindAt: SnapshotLogEntry? = null,
        /**
         * Branch and tag names from `refs` that point at this snapshot, `main` first.
         *
         * A ref is what keeps a snapshot from expiring, so it is the reason a commit is still
         * here — worth showing on the snapshot rather than only in the table's raw metadata.
         */
        val refs: List<SnapshotRefLabel> = emptyList(),
        /**
         * What this commit did, read out of the manifests it wrote — see [SnapshotChange].
         *
         * Computed at build time and carried on the node, the same way [TableNode.summary] is,
         * because the panel showing it has a node and not the table model. It is not drawn on the
         * card, so it costs the layout nothing.
         */
        val change: SnapshotChange? = null,
        /**
         * The manifest list as recorded, every entry in list order — what the next commit's
         * manifest merge starts from. Empty for an expired snapshot, whose list is gone.
         */
        val manifestList: List<ManifestListEntry> = emptyList(),
        /**
         * Every file the table holds at this snapshot, read on first ask — see [liveFiles].
         *
         * A [DeferredRead] for the same reason a deletion vector's blob is one, at a different
         * scale: this walks the snapshot's whole manifest closure, and a table of twenty commits
         * would walk twenty of them at build time to answer a question the reader asks about two.
         */
        private val liveFilesLoader: DeferredRead<List<LiveFile>> = DeferredRead.none(),
        /**
         * Which of this snapshot's delete files reach which of its data files — see [deleteReach].
         *
         * Deferred for the same reason [liveFilesLoader] is, and it costs that walk plus a pass
         * over the entries: computing it for every snapshot at build time would answer a question
         * about one commit for all of them. The panel only forces it when the manifest list says
         * there is a delete manifest to be about, which the list records without being opened.
         */
        private val deleteReachLoader: DeferredRead<List<DeleteReach>> = DeferredRead.none(),
        val initialX: Double = 0.0,
        val initialY: Double = 0.0,
        // The card grows for its ref chips rather than clipping them. Node height is what ELK
        // reserves, so a card that draws more than it declares overflows into its neighbour —
        // and Compose clips nothing by default, so the chip simply disappears under the border
        // with nothing failing. Two chip rows is the cap the card allows for.
        //
        // 68 and 88, down from 84 and 112. Both were set by eye against the fixtures; the sweep
        // measures 64 and 83 as the *bounds* — every text on this card is capped, the chip row at
        // two lines and the file name at two or three depending on whether chips take a line from
        // it — so the reserve was 20dp and 29dp of empty space under every snapshot in the graph.
        // Measured with a path far longer than Iceberg's own naming produces and with more refs
        // than the chip row can hold, which is what makes 64 and 83 upper bounds rather than the
        // tallest thing eight checked-in tables happen to contain.
    ) : GraphNode(id, initialX, initialY, 210.0, if (refs.isEmpty()) 68.0 else 88.0), ComparableSnapshot {

        override val nodeId: String get() = id
        override val displayNumber: Int get() = simpleId
        override val commitId: Long? get() = data.snapshotId
        override val parentCommitId: Long? get() = data.parentSnapshotId
        override val commitOrder: Long? get() = data.effectiveSequenceNumber
        override val commitTimeMs: Long? get() = data.timestampMs

        /**
         * Every file the table holds at this snapshot, deduplicated, live entries only.
         *
         * Null when the builder attached nothing — a snapshot node made by hand in a test. That is
         * a different answer from an empty list, which would be a snapshot holding no files.
         */
        override val liveFiles: List<LiveFile>? get() = liveFilesLoader.value

        override val canDiff: Boolean get() = liveFilesLoader.isPresent

        /** Which delete files reach which data files here, walked on first ask. */
        val deleteReach: List<DeleteReach>? get() = deleteReachLoader.value
    }

    data class ManifestNode(
        override val id: String,
        val data: ManifestListEntry,
        val simpleId: Int,
        /**
         * Every entry in this manifest, in apply order.
         *
         * Every one of them also gets a child [FileNode]; how many of those the graph draws is
         * [GraphNode.GroupNode]'s business, and the group node standing for the rest is what
         * says so on screen.
         */
        val entries: List<ManifestEntryView> = emptyList(),
        /**
         * Per-partition-field bounds over this whole manifest, from `manifest_file.partitions`.
         *
         * The reason a scan does or does not open this file. Empty when the table is
         * unpartitioned, or when the spec and the summaries disagree on length.
         */
        val partitionSummaries: List<PartitionSummary> = emptyList(),
        /**
         * Whether [localPath] is where the table said the file is, or where forcing the name
         * relative to the local directory landed. Shown so a missing file names the path that
         * was actually looked at.
         */
        val pathResolution: PathResolution = PathResolution.FORCED_RELATIVE,

        /** The schema this manifest was written against — the only correct one for its bounds. */
        val schema: IcebergSchemaModel? = null,
        val localPath: String? = null,
        val initialX: Double = 0.0,
        val initialY: Double = 0.0,
    ) : GraphNode(id, initialX, initialY, 200.0, 80.0)

    data class FileNode(
        override val id: String,
        val entry: ManifestEntry,
        val simpleId: Int,
        /** The schema of the manifest this entry came from. See [ManifestNode.schema]. */
        val schema: IcebergSchemaModel? = null,
        /** This file's partition tuple, decoded against the manifest's own spec. */
        val partition: DecodedPartition? = null,
        val localPath: String? = null,
        /** How [localPath] was arrived at — see [UnifiedDataFile.pathResolution]. */
        val pathResolution: PathResolution = PathResolution.FORCED_RELATIVE,
        /**
         * The sort order `sort_order_id` names, looked up in the newest metadata — orders only
         * accumulate, so the newest list holds every id a file can carry. Null when the id names
         * none, which is a fact about the table worth saying rather than smoothing over.
         */
        val sortOrder: SortOrder? = null,
        /**
         * The table's `default-sort-order-id` resolved the same way, so the panel can put the
         * file's claim beside the table's. They differ on every Spark-written file of a table with
         * `WRITE ORDERED BY`: the writer sorts the rows and records 0, so a file's 0 is not a
         * statement that its rows are unordered — the `sorted` fixture.
         */
        val defaultSortOrder: SortOrder? = null,
        /**
         * Every field any of the table's schemas has defined, newest definition winning — what a
         * bound keyed by a field the manifest's schema lacks is named and typed by. See
         * [columnStatsFor].
         */
        val tableFieldsById: Map<Int, NestedField> = emptyMap(),
        /**
         * The sequence number of the manifest this entry came from, so the entry's own can be
         * inherited from it — see [effectiveSequenceNumber] for why a null entry value is not
         * "unknown". Carried on the node because the builder has it and the panel does not: the
         * manifest is a *parent* here, and a file reached from two of them would otherwise have to
         * pick one.
         */
        val manifestSequenceNumber: Long? = null,
        /**
         * v3 row lineage: the `_row_id` of this file's first row, and whether it was inherited
         * from the manifest rather than recorded — see [UnifiedDataFile.firstRowId]. A row's id
         * is this plus its position unless the file carries a `_row_id` column.
         */
        val firstRowId: Long? = null,
        val firstRowIdInherited: Boolean = false,
        val initialX: Double = 0.0,
        val initialY: Double = 0.0,
        /**
         * Opens this file's Puffin blob, for a v3 deletion vector and nothing else.
         *
         * A lambda rather than a decoded value because the graph is built for every artifact the
         * metadata names and drawn for a page of them — the same reason sample rows are attached
         * after aggregation. Reading every vector at build time would open a file per delete on a
         * table where most of them are never looked at.
         *
         * A [DeferredRead] rather than a bare lambda, so it stays out of this node's identity —
         * see that class for why a `private val` lambda could not. The node is a value, and two
         * nodes for the same entry are the same node whether or not one has since read a blob.
         */
        private val deletionVectorLoader: DeferredRead<DeletionVector> = DeferredRead.none(),
        // 68dp, not 60, because the card's first line is `FILE 5: DELETE VECTOR — NOT READ` at its
        // longest and that wraps at 200dp. The verdict is appended when a scan filter is on, which
        // is *after* the layout that reserved this height, so the reservation has to cover the
        // longest label the card can ever draw rather than the one it draws today. It is declared
        // for every file node and not only for a vector: two heights in one layer would make a
        // deletion vector sit 8dp taller than the data file beside it, for a line neither of them
        // is drawing.
            // 72, not 68: the stress pass measured the pruned deletion-vector card at exactly 68.0
        // of a declared 68.0, which is a fit with nothing left for rounding at another scale.
    ) : GraphNode(id, initialX, initialY, 200.0, 72.0) {
        val data: DataFile get() = entry.dataFile ?: DataFile(filePath = "unknown")

        /** This file's sequence number, inherited from its manifest when the entry records none. */
        val sequenceNumber: Long get() = effectiveSequenceNumber(entry, manifestSequenceNumber)

        /** Whether [sequenceNumber] came from the manifest rather than from the entry. */
        val sequenceInherited: Boolean get() = entry.sequenceNumber == null && manifestSequenceNumber != null

        /**
         * Whether [sequenceNumber] is the v1 default: neither the entry nor its manifest records
         * one, and the spec reads both as 0.
         */
        val sequenceDefaulted: Boolean get() = entry.sequenceNumber == null && manifestSequenceNumber == null

        /** Per-column statistics with bounds decoded against [schema]. */
        val columnStats: List<ColumnStats> by lazy { columnStatsFor(data, schema, tableFieldsById) }

        /**
         * True when this delete file is a v3 deletion vector rather than a v2 delete file.
         *
         * Both declare `content = 1`, so without this the two are distinguishable only by a
         * `.puffin` extension — which is a naming convention, not a statement in the metadata.
         * `content_offset` is: only a vector carries one.
         */
        val isDeletionVector: Boolean get() = data.contentOffset != null

        /** The positions this vector marks, read on first ask. Null when it is not a vector. */
        val deletionVector: DeletionVector? get() = deletionVectorLoader.value
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
        /**
         * The positions a v3 deletion vector removes from this row's file, if one covers it.
         *
         * Supplied by the builder because the answer lives in another node — the vector is a file
         * node under some manifest, and which data file it applies to is its
         * `referenced_data_file`. A sampled row is 50 rows deep at most and the decoder emits
         * positions in ascending order, so its cap cannot hide a position this set needs.
         */
        private val deletedPositions: Set<Long> = emptySet(),
        /**
         * Whether [deletedPositions] is an answer at all. The Iceberg builder resolves a file's
         * vector and passes its positions, so an empty set means "none marks this row"; the
         * Paimon builder passes nothing, because a Paimon deletion vector lives in the index
         * manifest and is not mapped to rows here — and "not by a deletion vector" would then be
         * a claim nothing checked.
         */
        val vectorsResolved: Boolean = true,
    ) : GraphNode(id, initialX, initialY, 200.0, 80.0) {
        val isDelete: Boolean get() = content > 0
        val resolvedData: Map<String, Any> by lazy {
            val loaded = dataLoader?.invoke()
            if (loaded.isNullOrEmpty()) data else loaded
        }

        /** This row's physical position in its file, once the row has been read. */
        val filePosition: Long? get() = (resolvedData[ROW_POSITION_KEY] as? Number)?.toLong()

        /**
         * Whether a deletion vector removes this row.
         *
         * False is two different things and is reported as one: no vector covers the file, or one
         * does and this row survives it. The difference is on the file node, which is where a
         * reader asking "is anything deleted here" is looking.
         */
        val isDeletedByVector: Boolean get() = filePosition?.let { it in deletedPositions } == true

        /**
         * A Paimon key-value row's `_VALUE_KIND`, once the row has been read; null for an Iceberg
         * row and for an append-table row, which carry none. See [PaimonRowKind].
         */
        val paimonRowKind: Int? get() = (resolvedData[PaimonRowKind.COLUMN] as? Number)?.toInt()

        /**
         * Whether this row removes rather than states — a Paimon `-U` or `-D`. Drawn the way an
         * Iceberg row under a deletion vector is, because it is the same fact from the reader's
         * side: a query does not return this row.
         */
        val isRetraction: Boolean get() = paimonRowKind?.let { PaimonRowKind.isRetraction(it) } == true

        companion object {
            /** The most row nodes one data file gets. */
            const val MAX_PER_FILE = 5

            /** How many row nodes a file gets: its recorded row count, capped — or the cap where none is recorded. */
            fun countFor(recordedRows: Long?): Int =
                minOf(MAX_PER_FILE.toLong(), recordedRows ?: MAX_PER_FILE.toLong()).toInt()

            /** Where [filePosition] is carried in [resolvedData]. Filtered out of the card. */
            const val ROW_POSITION_KEY = "row_pos"
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
        /**
         * Every file the table holds at this snapshot, read on first ask — see [liveFiles].
         *
         * Reached by [replayPaimonSnapshot], which is a replay rather than a filter: Paimon's
         * delta manifest list applies over its base, so an entry's meaning depends on the entries
         * before it. Deferred for the same reason the Iceberg one is — only two snapshots in a
         * table are ever compared.
         */
        private val liveFilesLoader: DeferredRead<List<LiveFile>> = DeferredRead.none(),
        /**
         * The index files this snapshot's index manifest lists — see [PaimonIndexManifestEntry].
         *
         * A value rather than a [DeferredRead]: an index manifest is one small Avro file per
         * snapshot and the model already read it, where a deletion vector's blob is one file per
         * data file and is not read until asked for.
         */
        val indexFiles: List<PaimonIndexManifestEntry> = emptyList(),
        /** What an `ANALYZE` commit wrote — see [PaimonStatistics]. Null on every other kind. */
        val statistics: PaimonStatistics? = null,
        /**
         * The snapshot file's three record counts against the manifests — see
         * [paimonRecordTallies]. Deferred because the total is checked against the replay, which
         * is the same walk [liveFiles] runs; the builder threads one deferred replay into both.
         */
        private val recordTalliesLoader: DeferredRead<List<CommitTally>> = DeferredRead.none(),
        /**
         * Each bucket's live files as the LSM tree its writer would restore — see
         * [paimonBucketLsms]. Off the same deferred replay as [liveFiles]: the tree is the live
         * set grouped and ordered, and the compaction section is the only thing that asks.
         */
        private val bucketLsmsLoader: DeferredRead<List<PaimonBucketLsm>> = DeferredRead.none(),
        /** The options of the schema this snapshot names — the ones its writer ran under. */
        val tableOptions: Map<String, String> = emptyMap(),
        /** False on an append table, which has no LSM tree and compacts only when asked. */
        val hasPrimaryKey: Boolean = true,
        /** The names of the tags under `tag/` that are copies of this snapshot. */
        val tags: List<String> = emptyList(),
        /**
         * True when `snapshot/` no longer holds this snapshot and a tag is what keeps its files
         * on disk. Such a snapshot is drawn because its files are the table's; it is not a commit
         * a reader can time-travel to by id.
         */
        val retainedByTagOnly: Boolean = false,
        /**
         * The branch whose `snapshot/` holds this file — null for main, whose snapshots sit at
         * the table root. A branch's snapshot ids are its own, so two nodes with one `data.id`
         * on two branches are two commits, and this is what tells them apart.
         */
        val branch: String? = null,
        val initialX: Double = 0.0,
        val initialY: Double = 0.0,
    ) : GraphNode(id, initialX, initialY, 210.0, if (tags.isEmpty() && branch == null) 66.0 else 83.0), ComparableSnapshot {

        override val nodeId: String get() = id
        override val displayNumber: Int get() = simpleId
        override val commitId: Long? get() = data.id
        /**
         * Paimon records no parent on a snapshot — the previous one is `id - 1` by convention and
         * nothing states it — so the comparison says nothing about lineage rather than inferring
         * it from an arithmetic that a rolled-back table breaks.
         */
        override val parentCommitId: Long? get() = null
        override val commitOrder: Long? get() = data.id
        override val commitTimeMs: Long? get() = data.timeMillis
        override val liveFiles: List<LiveFile>? get() = liveFilesLoader.value
        override val canDiff: Boolean get() = liveFilesLoader.isPresent
        val recordTallies: List<CommitTally>? get() = recordTalliesLoader.value
        val bucketLsms: List<PaimonBucketLsm>? get() = bucketLsmsLoader.value
    }

    /** Paimon schema node. */
    data class PaimonSchemaNode(
        override val id: String,
        val data: PaimonSchema,
        val simpleId: Int,
        val localPath: String? = null,
        val initialX: Double = 0.0,
        val initialY: Double = 0.0,
    ) : GraphNode(id, initialX, initialY, 220.0, 54.0)

    /** Paimon manifest list node (base, delta, or changelog). */
    data class PaimonManifestListNode(
        override val id: String,
        val kind: String,                  // "base", "delta", "changelog"
        val simpleId: Int,
        /**
         * How many manifests the list names — every one, not the page the graph draws. The card
         * states it because a list's only content is its length; the manifests under it may be
         * folded into a group, and a count read off the drawn children would be the page size.
         */
        val manifestCount: Int,
        val localPath: String? = null,
        val initialX: Double = 0.0,
        val initialY: Double = 0.0,
    ) : GraphNode(id, initialX, initialY, 220.0, 42.0)

    /** Paimon manifest node (references a single manifest file within a manifest list). */
    data class PaimonManifestNode(
        override val id: String,
        val data: PaimonManifestFileMeta,
        val simpleId: Int,
        /** The manifest list's per-column partition minimums and maximums, decoded — see [PaimonSimpleStats]. */
        val partitionMin: DecodedPaimonPartition? = null,
        val partitionMax: DecodedPaimonPartition? = null,
        /** Every entry in this manifest — see [ManifestNode.entries] for why all of them. */
        val entries: List<PaimonManifestEntryView> = emptyList(),
        val localPath: String? = null,
        /**
         * What each of this manifest's entries did to the live set, when asked.
         *
         * Deferred, and deferred for a reason that is not the usual one: this costs no file open at
         * all, it costs replaying the whole snapshot. A Paimon entry means "remove what is there"
         * or "put this there", so its effect is decided by the entries before it — the trace for a
         * manifest cannot be computed from that manifest. Doing it for every manifest at build time
         * would be a replay per manifest for a panel that shows one at a time, so the builder hands
         * over the replay rather than its result. [DeferredRead] is also what keeps it out of the
         * node's `equals`, the same rule `FileNode.deletionVectorLoader` follows.
         */
        val replayTrace: DeferredRead<List<PaimonEntryTrace>> = DeferredRead.none(),
        val initialX: Double = 0.0,
        val initialY: Double = 0.0,
    ) : GraphNode(id, initialX, initialY, 200.0, 64.0)

    /** Paimon data file node. */
    data class PaimonDataFileNode(
        override val id: String,
        val entry: PaimonManifestEntry,
        val simpleId: Int,
        val bucket: Int? = null,
        /** The partition the file is in, decoded from the entry — the directory half of [localPath]. */
        val partition: DecodedPaimonPartition? = null,
        /** `_MIN_KEY` / `_MAX_KEY` over the trimmed primary key, decoded; null on a table without one or when undecodable. */
        val keyMin: List<PaimonRowValue>? = null,
        val keyMax: List<PaimonRowValue>? = null,
        /** `_VALUE_STATS` per column — the file's own bounds, decoded. Null when undecodable. */
        val columnBounds: List<PaimonColumnBounds>? = null,
        val level: Int? = null,
        val operationKind: Int? = null,     // 0=ADD, 1=DELETE
        val localPath: String? = null,
        /** How [localPath] was arrived at — see [PaimonUnifiedDataFile.pathResolution]. */
        val pathResolution: PaimonPathResolution = PaimonPathResolution.LAYOUT,
        val initialX: Double = 0.0,
        val initialY: Double = 0.0,
        // 64, not 60: the stress pass measured this card at exactly its declared height, which
        // leaves nothing for rounding at another display scale.
    ) : GraphNode(id, initialX, initialY, 200.0, 64.0)

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

    /**
     * Stands in for a run of sibling nodes the graph is not drawing, and expands to reveal them.
     *
     * A production table has more manifests than a screen has room for, and more data files than
     * a layout engine will place in reasonable time. Drawing all of them is not an option; a cap
     * that silently stops at the first handful is worse, because a manifest with 5,000 files then
     * looks exactly like one with 10. This node is the third answer: the tail is still in the
     * graph, as one card that says how much it stands for and can be opened.
     *
     * [memberIds] are the direct siblings; [hiddenNodeCount] also counts the descendants that
     * left the graph with them, and [hiddenErrorCount] counts the read errors among those — an
     * error that disappears into a group is a failure the reader would never learn about.
     */
    data class GroupNode(
        override val id: String,
        val parentId: String,
        val kind: AggregationKind,
        /** The siblings this stands for, in the order they would have been drawn. */
        val memberIds: List<String> = emptyList(),
        /** [memberIds] plus every descendant that left the graph with them. */
        val hiddenNodeCount: Int = 0,
        /** Read-error nodes among what this hides. */
        val hiddenErrorCount: Int = 0,
        /** How many pages of siblings were revealed before this one. 1 is the first group. */
        val pageIndex: Int = 1,
        val initialX: Double = 0.0,
        val initialY: Double = 0.0,
        // The card grows for the lines it actually draws. A node's declared height is what ELK
        // reserves and what the card is sized to, and Compose clips nothing — so a fixed height
        // loses the overflow under the card's own border with nothing failing. At 76dp the
        // subtotal line was sliced through the middle and both the read-error line and the
        // "double-click to open" hint never appeared, which was found by looking at the render
        // and could not have been found any other way.
        //
        // The base was 58 and the plainest group measures 60.5, so every group carrying neither
        // extra line lost its "Double-click to open" hint — the one line that says the card is a
        // control. It survived a render check because the group in front of the reader had a
        // subtotal line and so declared 73; only sweeping every group in every fixture reached
        // the shape that did not. 62 is 60.5 with a dp of margin for the rounding a different
        // display scale does to a font metric.
    ) : GraphNode(
        id, initialX, initialY, 200.0,
        62.0 +
            (if (hiddenNodeCount > memberIds.size) 15.0 else 0.0) +
            (if (hiddenErrorCount > 0) 15.0 else 0.0),
    ) {
        val memberCount: Int get() = memberIds.size
    }
}

/**
 * The kinds of sibling a graph aggregates, and what to call a pile of them.
 *
 * Aggregation groups by kind as well as by parent because a snapshot's manifests and a
 * snapshot's read errors are not interchangeable: collapsing a mixed set would produce a card
 * that can only be described as "37 things".
 */
enum class AggregationKind(val key: String, val plural: String) {
    METADATA("metadata", "metadata versions"),
    SNAPSHOT("snapshot", "snapshots"),
    MANIFEST("manifest", "manifests"),
    FILE("file", "files"),
    ROW("row", "sample rows"),
    PAIMON_SNAPSHOT("psnapshot", "snapshots"),
    PAIMON_SCHEMA("pschema", "schemas"),
    PAIMON_MANIFEST_LIST("pmanifestlist", "manifest lists"),
    PAIMON_MANIFEST("pmanifest", "manifests"),
    PAIMON_FILE("pfile", "files"),
}

/**
 * The kind this node aggregates as, or null for a node that must never disappear into a group.
 *
 * A table root has no siblings to be piled up with. An error node is excluded on purpose: the
 * whole reason to draw one is that something failed, and a failure folded into "and 40 more"
 * is a failure nobody reads. A group node cannot nest inside another group — the tail of a
 * tail is expressed as the next page, not as depth.
 */
fun GraphNode.aggregationKind(): AggregationKind? = when (this) {
    is GraphNode.MetadataNode -> AggregationKind.METADATA
    is GraphNode.SnapshotNode -> AggregationKind.SNAPSHOT
    is GraphNode.ManifestNode -> AggregationKind.MANIFEST
    is GraphNode.FileNode -> AggregationKind.FILE
    is GraphNode.RowNode -> AggregationKind.ROW
    is GraphNode.PaimonSnapshotNode -> AggregationKind.PAIMON_SNAPSHOT
    is GraphNode.PaimonSchemaNode -> AggregationKind.PAIMON_SCHEMA
    is GraphNode.PaimonManifestListNode -> AggregationKind.PAIMON_MANIFEST_LIST
    is GraphNode.PaimonManifestNode -> AggregationKind.PAIMON_MANIFEST
    is GraphNode.PaimonDataFileNode -> AggregationKind.PAIMON_FILE
    is GraphNode.TableNode -> null
    is GraphNode.ErrorNode -> null
    is GraphNode.GroupNode -> null
}

data class GraphEdge(
    val id: String,
    val fromId: String,
    val toId: String,
    val isSibling: Boolean = false,
    /**
     * False for an edge that records a relationship without implying containment.
     *
     * Snapshot lineage is the case this exists for. A snapshot's parent is another snapshot, so
     * feeding those edges to a layered layout puts every commit in its own layer and drags its
     * manifests and files along with it — the graph stretches by the length of the table's
     * history and stops being readable at the third commit. The edge is still drawn, because the
     * canvas routes from node positions rather than from ELK's sections; it just does not get a
     * say in where the nodes go. Layout post-processing ignores it for the same reason.
     */
    val affectsLayout: Boolean = true,
    val sections: List<EdgeSection> = emptyList(),
)

/**
 * A branch or tag pointing at a snapshot. [isBranch] is false for a tag, which cannot move.
 */
/** One Paimon branch as the table panel lists it — see [TableSummary.branches]. */
data class BranchSummary(
    val name: String,
    /** `branch/branch-<name>`, relative to the table root. */
    val path: String,
    val snapshotCount: Int,
    val latestSnapshotId: Long?,
    val schemaCount: Int,
    val tagCount: Int,
    val readErrorCount: Int,
)

/** One Paimon consumer as the table panel lists it — see [TableSummary.consumers]. */
data class ConsumerSummary(
    val name: String,
    /** `consumer/consumer-<id>`, relative to the table root. */
    val path: String,
    /** The snapshot the reader consumes next; what expiry will not go past. */
    val nextSnapshot: Long?,
    /** Whether `snapshot/` still holds it — false is a reader that has fallen behind an expiry. */
    val nextSnapshotPresent: Boolean,
)

data class SnapshotRefLabel(val name: String, val isBranch: Boolean) {
    val display: String get() = if (isBranch) name else "$name (tag)"
}

data class EdgeSection(
    val startX: Double,
    val startY: Double,
    val endX: Double,
    val endY: Double,
)

/**
 * One line naming what a node is, for a list.
 *
 * Core, and shared by every shell, because what an artifact is *called* is knowledge about the
 * artifact rather than about a screen — the desktop tree and the IDE tool window would otherwise
 * be two vocabularies for one set of things, and they would drift the first time a node type was
 * added. It is deliberately **not** what `GraphSearch.searchableText` answers: this is one line
 * chosen to fit a row, so a manifest reads by its add count and cannot be found by its path, which
 * is right for a label and wrong for a search.
 */
fun GraphNode.displayLabel(): String = when (this) {
    is GraphNode.TableNode -> "Table: ${summary.tableName}"
    is GraphNode.MetadataNode -> "Meta $simpleId: $fileName"
    is GraphNode.SnapshotNode -> "Snap: ${data.snapshotId}"
    is GraphNode.ManifestNode -> "Manifest (${data.addedFilesCount} adds)"
    is GraphNode.FileNode -> "File $simpleId: ${data.filePath?.substringAfterLast("/")}"
    is GraphNode.RowNode -> "Row: ${resolvedData.values.firstOrNull() ?: "..."}"
    is GraphNode.ErrorNode -> "Error: $title"
    is GraphNode.PaimonSnapshotNode -> "PSnap $simpleId: ${data.commitKind ?: ""}" + (branch?.let { " ($it)" } ?: "")
    is GraphNode.PaimonSchemaNode -> "PSchema $simpleId"
    is GraphNode.PaimonManifestListNode -> "PManifestList: $kind"
    is GraphNode.PaimonManifestNode -> "PManifest $simpleId"
    is GraphNode.PaimonDataFileNode ->
        "PFile $simpleId: ${entry.file?.fileName?.substringAfterLast("/") ?: ""}"
    is GraphNode.GroupNode -> "Not drawn: ${"%,d".format(memberCount)} more ${kind.plural}"
}
