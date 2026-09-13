package plugin

import javax.swing.tree.DefaultMutableTreeNode
import model.DecodedPaimonPartition
import model.GraphModel
import model.GraphNode
import model.PaimonRowKind
import model.PaimonRowValue
import model.ExpiryOptions
import model.IcebergMaintenanceInput
import model.PaimonExpiryOptions
import model.displayLabel
import model.planExpiry
import model.sourceSnapshotId
import model.publishedWapId
import model.wapId
import model.describeRowIds
import model.describe

/**
 * The graph as a tree, and what each node has to say about itself.
 *
 * A tree rather than the desktop shell's canvas because this panel is docked to the side of an
 * editor: it is tall and narrow, which is the one shape a node-and-edge drawing is worst in and a
 * tree is best in. The structure is the same structure — table, metadata versions, snapshots,
 * manifests, files — read off the same `GraphModel` the canvas draws.
 */
object GraphTree {

    /**
     * Roots of the graph as tree nodes, children first-seen-first.
     *
     * Only **structural** edges are followed. An `affectsLayout = false` edge is an annotation —
     * snapshot lineage, or a deletion vector pointing at the data file it covers — and both run
     * between nodes at one depth, so following them would make a snapshot a child of its parent
     * commit and duplicate the whole history under itself.
     *
     * A node reached from several parents is expanded under each, which is not a mistake: one
     * manifest genuinely is a child of every snapshot that carries its files forward, and that is
     * a fact about the table worth seeing. The walk carries the path it came by, so a cycle — which
     * the metadata should not contain but a corrupt table could — stops rather than recursing.
     */
    fun build(graph: GraphModel): List<DefaultMutableTreeNode> {
        val childIds = graph.edges
            .filter { it.affectsLayout && !it.isSibling }
            .groupBy({ it.fromId }, { it.toId })
        val hasParent = graph.edges.filter { it.affectsLayout && !it.isSibling }.map { it.toId }.toSet()
        val roots = graph.nodes.filter { it.id !in hasParent }

        fun node(graphNode: GraphNode, seen: Set<String>): DefaultMutableTreeNode {
            val treeNode = DefaultMutableTreeNode(Item(graphNode))
            if (graphNode.id in seen) return treeNode
            childIds[graphNode.id].orEmpty()
                .mapNotNull { graph.nodeById[it] }
                .forEach { treeNode.add(node(it, seen + graphNode.id)) }
            return treeNode
        }
        return roots.map { node(it, emptySet()) }
    }

    /** One row of the tree. [toString] is what the default renderer draws. */
    class Item(val node: GraphNode) {
        override fun toString(): String = node.displayLabel()
    }

    /**
     * What the details panel lists for a node: the identity, then the figures.
     *
     * Deliberately short. The desktop inspector is a whole panel per node kind with tallies,
     * derivations and drill-downs; this is a docked strip beside an editor, and the question it
     * answers is "what am I looking at" rather than "explain this number". Anything longer belongs
     * in the desktop shell, which is one keystroke away.
     */
    fun details(node: GraphNode, nowMs: Long = System.currentTimeMillis()): List<Pair<String, String>> =
        rows(node, nowMs).map { (field, value) -> field to (value?.takeIf { it.isNotBlank() } ?: ABSENT) }

    /** The label of the one row [deferredDetails] fills, drawn with a placeholder while the read runs. */
    const val HISTORY = "History"

    /** Whether [deferredDetails] has anything to read for the node — a data file's history, on either format. */
    fun hasDeferredDetails(node: GraphNode): Boolean = node is GraphNode.FileNode || node is GraphNode.PaimonDataFileNode

    /**
     * The rows that cost a read: a file's history is a scan of every retained snapshot's manifest
     * entries (`DeferredRead`, memoised on the node), cheap on a developer's table and a stall on
     * a large one, so the panel asks for these off the EDT after [details] is already on screen.
     */
    fun deferredDetails(node: GraphNode): List<Pair<String, String>> {
        val history = when (node) {
            is GraphNode.FileNode -> node.history
            is GraphNode.PaimonDataFileNode -> node.history
            else -> return emptyList()
        }
        return listOf(HISTORY to (history.value?.describe ?: "could not be read"))
    }

    /**
     * The rows before absent values are rendered.
     *
     * Nullable on purpose: a metadata field the format leaves optional is genuinely absent, and
     * coercing it in one place means every one of them looks the same on screen rather than each
     * branch choosing its own dash — and a branch that forgets cannot print "null".
     */
    private fun rows(node: GraphNode, nowMs: Long): List<Pair<String, String?>> = when (node) {
        is GraphNode.TableNode -> listOf(
            "Name" to node.summary.tableName,
            "Location" to (node.summary.location ?: node.summary.tablePath),
            "Format version" to (node.summary.formatVersion?.toString() ?: "—"),
            "Snapshots" to node.summary.snapshotCount.toString(),
            "Current snapshot" to (node.summary.currentSnapshotId?.toString() ?: "—"),
            "Data files (current)" to node.summary.current.dataFileCount.toString(),
            "Records (current)" to "%,d".format(node.summary.current.recordCount) +
                (node.summary.current.partialRecordCount.takeIf { it > 0 }?.let { " (%,d in partial-column files; %,d read)".format(it, node.summary.current.readRecordCount) } ?: ""),
            "Size (current)" to "%,d bytes".format(node.summary.current.dataSizeBytes),
            // The one maintenance line that costs no walk: an expiry is planned from the
            // metadata alone, and "what would expire_snapshots do now" is the question a table's
            // row is opened for most. The rest of the maintenance summary walks a closure and
            // stays in the desktop shell.
            "Expiry (older_than = now)" to expiryLine(node, nowMs),
        )
        is GraphNode.MetadataNode -> listOf(
            "File" to node.fileName,
            "Format version" to (node.data.formatVersion?.toString() ?: "—"),
            "Current snapshot" to (node.data.currentSnapshotId?.toString() ?: "—"),
            "Snapshots listed" to (node.data.snapshots?.size ?: 0).toString(),
        ) + listOfNotNull(
            node.data.nextRowId?.let { "Next row id" to it.toString() },
        )
        // Rows a table may not have are listed only when it has them, so a v2 table's strip
        // stays the strip it was.
        is GraphNode.SnapshotNode -> listOf(
            "Snapshot id" to node.data.snapshotId.toString(),
            "Expired" to if (node.expired) "yes — gone from the current metadata" else "no",
            "Parent" to (node.data.parentSnapshotId?.toString() ?: "none"),
            "Sequence number" to (node.data.sequenceNumber?.toString() ?: "0 (v1 — none recorded)"),
            "Operation" to (node.data.summary?.get("operation") ?: "—"),
            "Manifest list" to (node.data.manifestList ?: "—"),
        ) + listOfNotNull(
            node.data.describeRowIds()?.let { "Row ids" to it },
            node.leftBehindAt?.let { "Rolled back" to "main set back to ${it.snapshotId}, leaving this commit behind" },
            node.data.wapId?.let { "WAP id" to "$it — staged, on no branch until published" },
            node.data.sourceSnapshotId?.let { "Published from" to "snapshot $it" + (node.data.publishedWapId?.let { id -> " (wap.id $id)" } ?: "") },
        )
        is GraphNode.ManifestNode -> listOf(
            "Path" to (node.data.manifestPath ?: "—"),
            "Content" to if (node.data.content == 1) "deletes" else "data",
            "Added" to (node.data.addedFilesCount?.toString() ?: "—"),
            "Existing" to (node.data.existingFilesCount?.toString() ?: "—"),
            "Deleted" to (node.data.deletedFilesCount?.toString() ?: "—"),
            "Entries read" to node.entries.size.toString(),
        )
        is GraphNode.FileNode -> listOf(
            "Path" to (node.data.filePath ?: "—"),
            "Format" to (node.data.fileFormat ?: "—"),
            "Records" to (node.data.recordCount?.toString() ?: "—"),
            "Size" to (node.data.fileSizeInBytes?.let { "%,d bytes".format(it) } ?: "—"),
            "Partition" to (node.partition?.values?.joinToString(", ") { "${it.field.name}=${it.human}" } ?: "—"),
        ) + listOfNotNull(
            node.sortOrder?.takeIf { it.fields.isNotEmpty() }?.let { "Sort order" to "${it.orderId} — ${it.describe { id -> node.schema?.nameOf(id) }}" },
            node.firstRowId?.let { first ->
                val records = node.data.recordCount ?: 0L
                "Row ids" to if (records > 0) "$first..${first + records - 1}" else first.toString()
            },
        )
        is GraphNode.RowNode -> node.resolvedData.entries.map { (key, value) ->
            // The one cell whose number means nothing on its own.
            if (key == PaimonRowKind.COLUMN) "Row kind" to ((value as? Number)?.toInt()?.let(PaimonRowKind::describe) ?: value.toString())
            else key to value.toString()
        }
        is GraphNode.ErrorNode -> listOf("Error" to node.title, "Detail" to node.message)
        is GraphNode.PaimonSnapshotNode -> listOf(
            "Snapshot id" to node.data.id.toString(),
            "Branch" to (node.branch ?: "main"),
            "Schema id" to node.data.schemaId.toString(),
            "Commit kind" to (node.data.commitKind ?: "—"),
            "Tags" to node.tags.joinToString(", ").ifEmpty { "—" },
            "Base manifest list" to (node.data.baseManifestList ?: "—"),
            "Delta manifest list" to (node.data.deltaManifestList ?: "—"),
            "Changelog manifest list" to (node.data.changelogManifestList ?: "—"),
            "Merged rows" to (node.statistics?.mergedRecordCount?.toString() ?: "—"),
            "Total records" to (node.data.totalRecordCount?.toString() ?: "—"),
            "Delta records" to (node.data.deltaRecordCount?.toString() ?: "—"),
        )
        is GraphNode.PaimonSchemaNode -> listOf(
            "Schema id" to node.data.id.toString(),
            "Fields" to node.data.fields.size.toString(),
        )
        is GraphNode.PaimonManifestListNode -> listOf(
            "Kind" to node.kind,
            "Manifests" to node.manifestCount.toString(),
            "Path" to (node.localPath ?: "—"),
        )
        is GraphNode.PaimonManifestNode -> listOf(
            "File" to node.data.fileName,
            "Entries" to node.entries.size.toString(),
            "Added" to node.data.numAddedFiles.toString(),
            "Deleted" to node.data.numDeletedFiles.toString(),
            "Buckets" to rangeText(node.data.minBucket, node.data.maxBucket),
            "Levels" to rangeText(node.data.minLevel, node.data.maxLevel),
            "Partitions" to partitionRangeText(node.partitionMin, node.partitionMax),
        )
        is GraphNode.PaimonDataFileNode -> listOf(
            "File" to (node.entry.file?.fileName ?: "—"),
            "Partition" to (node.partition?.display?.ifEmpty { "none" } ?: "not decoded"),
            "Key range" to keyRangeText(node.keyMin, node.keyMax),
            "Level" to ((node.entry.file?.level?.toString() ?: "—") + if (node.unreadByBatchRead) " — not read by a batch read of this table" else ""),
            "Records" to (node.entry.file?.rowCount?.toString() ?: "—"),
            "Kind" to node.entry.kind.toString(),
        ) + listOfNotNull(
            node.entry.file?.writeCols?.takeIf { node.partial }?.let { "Columns" to it.joinToString(", ") + " only — a partial-column file, stitched by row id on read" },
            // From the index manifest, not the index file: the strip is drawn on selection, on the EDT.
            node.vectorRange?.let { "Deleted rows" to "${it.cardinality ?: "?"} marked by the vector in ${it.indexFileName}" },
        )
        // A group is the one node that is not an artifact — it stands for the ones this drawing
        // left out, and saying how many is the whole of what it has to say.
        is GraphNode.GroupNode -> listOf(
            "Not drawn" to "%,d %s".format(node.memberCount, node.kind.plural),
            "Errors inside" to node.hiddenErrorCount.toString(),
        )
    }

    /** What an absent optional field looks like. One rendering, so a column of them scans. */
    private fun expiryLine(node: GraphNode.TableNode, nowMs: Long): String? {
        val paimon = node.summary.paimonExpiry
        if (paimon != null) {
            val plan = runCatching { paimon.planExpiry(PaimonExpiryOptions(nowMs = nowMs)) }.getOrNull()
                ?: return "the table's snapshot.* options are ones the procedure refuses"
            return if (plan.removed.isEmpty()) "a bare call removes nothing"
            else "a bare call would remove %,d of %,d snapshots".format(plan.removed.size, plan.snapshots.size)
        }
        val meta = (node.maintenance.value as? IcebergMaintenanceInput)?.metadata ?: return null
        val plan = meta.planExpiry(ExpiryOptions(nowMs = nowMs, olderThanMs = nowMs))
        return when {
            meta.snapshots.isEmpty() -> "no snapshots"
            plan.removed.isEmpty() -> "nothing — every snapshot is kept by a ref"
            else -> "would remove %,d of %,d snapshots".format(plan.removed.size, plan.snapshots.size)
        }
    }

    const val ABSENT = "\u2014"

    /** `0..3`, or the absent mark when the manifest list recorded no range. */
    private fun rangeText(low: Int?, high: Int?): String =
        if (low == null || high == null) ABSENT else "$low..$high"

    /** `k=1 .. k=1000`, or the absent mark when the file records no key bounds. */
    private fun keyRangeText(min: List<PaimonRowValue>?, max: List<PaimonRowValue>?): String =
        if (min == null || max == null) ABSENT
        else min.joinToString(", ") { "${it.name}=${it.display}" } + " .. " + max.joinToString(", ") { "${it.name}=${it.display}" }

    /** The per-column partition range a manifest list records, or `none` for an unpartitioned table. */
    private fun partitionRangeText(min: DecodedPaimonPartition?, max: DecodedPaimonPartition?): String = when {
        min == null || max == null -> ABSENT
        min.values.isEmpty() -> "none"
        else -> "${min.display} .. ${max.display}"
    }
}
