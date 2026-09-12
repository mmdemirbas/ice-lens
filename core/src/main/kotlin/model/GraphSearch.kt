package model

/**
 * Finding a node on the canvas by typing what you know about it.
 *
 * The structure tree already searches, and it searches the *label* the tree prints — which is one
 * line chosen to fit a row, so a manifest reads `Manifest (12 adds)` and cannot be found by its
 * path at all, and a snapshot cannot be found by the operation that made it. That is right for a
 * tree, where the label is what the reader is looking at; it is wrong for a search, where the
 * reader is looking for something they cannot see.
 *
 * So this defines a second thing per node kind: **what the artifact is, in words** — every field a
 * reader might plausibly have in hand. A path, a format, a content type, a partition value, a
 * commit operation, a branch name, an error message. That is knowledge about the artifacts rather
 * than about any screen, which is why it is core and not UI, and it is what makes
 * "parquet", "deletes", "name=alpha" and "overwrite" all queries that work.
 */
object GraphSearch {

    /**
     * The words a node can be found by. Lower-cased once here rather than at every comparison.
     *
     * A [GraphNode.GroupNode] contributes nothing on purpose: it is the *absence* of nodes, and
     * matching the card that says "412 more files" when the reader typed a file name would point
     * them at a placeholder rather than tell them the file is not drawn. That is what
     * [GraphSearchResult.notDrawn] is for.
     */
    fun searchableText(node: GraphNode): List<String> = when (node) {
        is GraphNode.TableNode -> listOfNotNull(
            node.summary.tableName,
            node.summary.location,
            node.summary.tableUuid,
            node.summary.formatVersion?.let { "v$it" },
        )

        is GraphNode.MetadataNode -> listOfNotNull(node.fileName, node.localPath)

        is GraphNode.SnapshotNode -> listOfNotNull(
            node.data.snapshotId?.toString(),
            node.data.summary["operation"],
            node.data.manifestList,
            node.localPath,
        ) + node.refs.map { it.name }

        is GraphNode.ManifestNode -> listOfNotNull(
            node.data.manifestPath,
            node.localPath,
            manifestContentWord(node.data.content),
        )

        is GraphNode.FileNode -> listOfNotNull(
            node.data.filePath,
            node.localPath,
            node.data.fileFormat,
            fileContentWord(node.data.content),
            node.partition?.takeIf { !it.isUnpartitioned }?.path,
        )

        // A row is its values, which is the only thing about it a reader could know.
        is GraphNode.RowNode -> node.data.values.mapNotNull { it?.toString() }

        is GraphNode.PaimonSnapshotNode -> node.tags + listOfNotNull(
            node.branch,
            node.data.id?.toString(),
            node.commitKind ?: node.data.commitKind,
            node.data.commitUser,
            node.localPath,
        )

        is GraphNode.PaimonSchemaNode -> listOfNotNull(node.data.id?.let { "schema $it" }, node.localPath) +
            node.data.fields.mapNotNull { it.name } +
            node.data.partitionKeys +
            node.data.primaryKeys

        is GraphNode.PaimonManifestListNode -> listOfNotNull(node.kind, node.localPath)

        is GraphNode.PaimonManifestNode -> listOfNotNull(node.data.fileName, node.localPath)

        is GraphNode.PaimonDataFileNode -> listOfNotNull(
            node.entry.file?.fileName,
            node.localPath,
            node.partition?.display?.takeIf { it.isNotEmpty() },
            node.level?.let { "level $it" },
            node.bucket?.let { "bucket $it" },
            paimonKindWord(node.operationKind),
        )

        is GraphNode.ErrorNode -> listOfNotNull(node.title, node.stage, node.path, node.message)

        is GraphNode.GroupNode -> emptyList()
    }.map { it.lowercase() }

    /**
     * Every drawn node matching [query], in the order the eye reads them.
     *
     * Position comes through [positionOf] rather than from `layoutPositions`, the same rule as
     * `GraphNavigation` and `snapshotColumns`: a reader who has dragged a node is stepping through
     * the drawing they made, not the one the layout produced. A node with no position sorts last
     * rather than being dropped — it is still a match, and silently losing one is worse than
     * ordering it oddly.
     *
     * The order is by column then by height, because that is how these graphs are drawn: ELK lays
     * a layer out at one x, so stepping is "down this layer, then on to the next". [COLUMN_EPSILON]
     * is what makes a branch drawn a few pixels off still count as its own column.
     */
    fun search(
        graph: GraphModel,
        query: String,
        positionOf: (String) -> Point? = { graph.layoutPositions[it] },
    ): GraphSearchResult {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return GraphSearchResult(emptyList(), notDrawn = 0)

        val matches = graph.nodes
            .filter { node -> searchableText(node).any { it.contains(needle) } }
            .sortedWith(
                compareBy(
                    { positionOf(it.id) == null },
                    { positionOf(it.id)?.let { p -> Math.round(p.x / COLUMN_EPSILON) } ?: 0L },
                    { positionOf(it.id)?.y ?: 0f },
                    { it.id },
                )
            )
            .map { it.id }

        return GraphSearchResult(
            matches = matches,
            notDrawn = graph.nodes.filterIsInstance<GraphNode.GroupNode>().sumOf { it.hiddenNodeCount },
        )
    }

    /** Pixels of horizontal slack within which two nodes count as the same column. */
    private const val COLUMN_EPSILON = 8.0

    private fun manifestContentWord(content: Int?): String = when (content) {
        ManifestContent.DELETES -> "deletes"
        else -> "data"
    }

    private fun fileContentWord(content: Int?): String = when (content) {
        DataFileContent.POSITION_DELETES -> "position deletes"
        DataFileContent.EQUALITY_DELETES -> "equality deletes"
        else -> "data"
    }

    private fun paimonKindWord(kind: Int?): String? = when (kind) {
        PaimonEntryKind.ADD -> "add"
        PaimonEntryKind.DELETE -> "delete"
        else -> null
    }
}

/**
 * What a query found, and what it could not look at.
 *
 * [notDrawn] is the whole reason this is a type rather than a `List<String>`. Aggregation folds
 * long sibling runs into a `GroupNode`, and the nodes that left are not in the graph to be
 * matched — so "no matches" is only true of what is drawn, and reporting it without the caveat is
 * the same silence the group cards exist to prevent. The caller states it; a reader who searches a
 * table for a file name and is told "nothing" while four hundred files are folded away has been
 * told something false.
 */
data class GraphSearchResult(
    /** Matching node ids, in the order they are drawn. */
    val matches: List<String>,
    /** How many nodes aggregation folded out of the graph, and so out of this search. */
    val notDrawn: Int,
) {
    val isEmpty: Boolean get() = matches.isEmpty()
    val size: Int get() = matches.size

    /**
     * The match to step to from [current], wrapping at the end.
     *
     * Wrapping rather than stopping, because "find next" that stops at the last match leaves the
     * reader with nothing to do but retype the query. `null` when nothing matched.
     */
    fun next(current: String?): String? {
        if (matches.isEmpty()) return null
        val index = matches.indexOf(current)
        return matches[if (index < 0) 0 else (index + 1) % matches.size]
    }

    /** The same backwards, for Shift+Enter. */
    fun previous(current: String?): String? {
        if (matches.isEmpty()) return null
        val index = matches.indexOf(current)
        return matches[if (index < 0) matches.lastIndex else (index - 1 + matches.size) % matches.size]
    }
}
