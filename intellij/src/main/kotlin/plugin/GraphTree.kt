package plugin

import javax.swing.tree.DefaultMutableTreeNode
import model.GraphModel
import model.GraphNode
import model.displayLabel

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
    fun details(node: GraphNode): List<Pair<String, String>> =
        rows(node).map { (field, value) -> field to (value?.takeIf { it.isNotBlank() } ?: ABSENT) }

    /**
     * The rows before absent values are rendered.
     *
     * Nullable on purpose: a metadata field the format leaves optional is genuinely absent, and
     * coercing it in one place means every one of them looks the same on screen rather than each
     * branch choosing its own dash — and a branch that forgets cannot print "null".
     */
    private fun rows(node: GraphNode): List<Pair<String, String?>> = when (node) {
        is GraphNode.TableNode -> listOf(
            "Name" to node.summary.tableName,
            "Location" to (node.summary.location ?: node.summary.tablePath),
            "Format version" to (node.summary.formatVersion?.toString() ?: "—"),
            "Snapshots" to node.summary.snapshotCount.toString(),
            "Current snapshot" to (node.summary.currentSnapshotId?.toString() ?: "—"),
            "Data files (current)" to node.summary.current.dataFileCount.toString(),
            "Records (current)" to "%,d".format(node.summary.current.recordCount),
            "Size (current)" to "%,d bytes".format(node.summary.current.dataSizeBytes),
        )
        is GraphNode.MetadataNode -> listOf(
            "File" to node.fileName,
            "Format version" to (node.data.formatVersion?.toString() ?: "—"),
            "Current snapshot" to (node.data.currentSnapshotId?.toString() ?: "—"),
            "Snapshots listed" to (node.data.snapshots?.size ?: 0).toString(),
        )
        is GraphNode.SnapshotNode -> listOf(
            "Snapshot id" to node.data.snapshotId.toString(),
            "Parent" to (node.data.parentSnapshotId?.toString() ?: "none"),
            "Sequence number" to (node.data.sequenceNumber?.toString() ?: "—"),
            "Operation" to (node.data.summary?.get("operation") ?: "—"),
            "Manifest list" to (node.data.manifestList ?: "—"),
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
        )
        is GraphNode.RowNode -> node.resolvedData.entries.map { it.key to it.value.toString() }
        is GraphNode.ErrorNode -> listOf("Error" to node.title, "Detail" to node.message)
        is GraphNode.PaimonSnapshotNode -> listOf(
            "Snapshot id" to node.data.id.toString(),
            "Schema id" to node.data.schemaId.toString(),
            "Commit kind" to (node.data.commitKind ?: "—"),
            "Base manifest list" to (node.data.baseManifestList ?: "—"),
            "Delta manifest list" to (node.data.deltaManifestList ?: "—"),
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
        )
        is GraphNode.PaimonDataFileNode -> listOf(
            "File" to (node.entry.file?.fileName ?: "—"),
            "Level" to (node.entry.file?.level?.toString() ?: "—"),
            "Records" to (node.entry.file?.rowCount?.toString() ?: "—"),
            "Kind" to node.entry.kind.toString(),
        )
        // A group is the one node that is not an artifact — it stands for the ones this drawing
        // left out, and saying how many is the whole of what it has to say.
        is GraphNode.GroupNode -> listOf(
            "Not drawn" to "%,d %s".format(node.memberCount, node.kind.plural),
            "Errors inside" to node.hiddenErrorCount.toString(),
        )
    }

    /** What an absent optional field looks like. One rendering, so a column of them scans. */
    const val ABSENT = "\u2014"
}
