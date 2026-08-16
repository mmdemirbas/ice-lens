package service

import model.AggregationKind
import model.GraphNode

/**
 * The order siblings of one kind are read in.
 *
 * One definition with two consumers, and that is the whole point of the file. [GraphLayoutService]
 * uses it to decide which sibling sits above which on the canvas; [GraphAggregation] uses it to
 * decide which siblings are drawn at all. When those two disagree, the page a reader is given is
 * not the head of the run they are looking at — the manifests drawn under a snapshot would be the
 * ones the builder happened to emit first, which for a manifest several snapshots carry forward is
 * the order of the snapshot that first wrote it, not this one's.
 *
 * Every comparator is total on its own node type and puts anything else last, so a list of mixed
 * types never throws — it just orders the ones it understands.
 */
internal object SiblingOrder {

    val METADATA: Comparator<GraphNode> = Comparator { a, b ->
        val va = (a as? GraphNode.MetadataNode)?.simpleId ?: Int.MAX_VALUE
        val vb = (b as? GraphNode.MetadataNode)?.simpleId ?: Int.MAX_VALUE
        va.compareTo(vb)
    }

    /**
     * Commits sit next to the commit they came from — see [GraphLayoutService.snapshotLineageOrder]
     * for why lineage rather than wall-clock time. [lineageRank] may be empty, in which case this
     * falls back to timestamp order, which is what a graph with no snapshots in it wants.
     */
    fun snapshot(lineageRank: Map<String, Int>): Comparator<GraphNode> = Comparator { a, b ->
        val ra = lineageRank[a.id]
        val rb = lineageRank[b.id]
        if (ra != null && rb != null) return@Comparator ra.compareTo(rb)

        val sa = (a as? GraphNode.SnapshotNode)?.data
        val sb = (b as? GraphNode.SnapshotNode)?.data
        compareValuesBy(sa, sb,
            { it?.timestampMs ?: Long.MAX_VALUE },
            { it?.sequenceNumber ?: Long.MAX_VALUE },
            { it?.snapshotId ?: Long.MAX_VALUE }
        )
    }

    val MANIFEST: Comparator<GraphNode> = Comparator { a, b ->
        val ma = (a as? GraphNode.ManifestNode)?.data
        val mb = (b as? GraphNode.ManifestNode)?.data
        compareValuesBy(ma, mb,
            { it?.sequenceNumber ?: Long.MAX_VALUE },
            { it?.minSequenceNumber ?: Long.MAX_VALUE },
            { it?.addedSnapshotId ?: Long.MAX_VALUE },
            { IcebergGraphBuilder.manifestContentRank(it?.content) },
            { it?.manifestPath ?: "" }
        )
    }

    val FILE: Comparator<GraphNode> = Comparator { a, b ->
        val fa = a as? GraphNode.FileNode
        val fb = b as? GraphNode.FileNode
        compareValuesBy(fa, fb,
            { it?.data?.dataSequenceNumber ?: it?.entry?.sequenceNumber ?: Long.MAX_VALUE },
            { it?.entry?.fileSequenceNumber ?: Long.MAX_VALUE },
            { IcebergGraphBuilder.contentRank(it?.data?.content) },
            { it?.entry?.status ?: Int.MAX_VALUE },
            { it?.data?.filePath ?: "" }
        )
    }

    val ROW: Comparator<GraphNode> = Comparator { a, b ->
        val ra = (a as? GraphNode.RowNode)?.id ?: ""
        val rb = (b as? GraphNode.RowNode)?.id ?: ""
        ra.compareTo(rb)
    }

    val PAIMON_SNAPSHOT: Comparator<GraphNode> = Comparator { a, b ->
        val sa = (a as? GraphNode.PaimonSnapshotNode)?.data
        val sb = (b as? GraphNode.PaimonSnapshotNode)?.data
        compareValuesBy(sa, sb,
            { it?.timeMillis ?: Long.MAX_VALUE },
            { it?.id ?: Long.MAX_VALUE }
        )
    }

    val PAIMON_SCHEMA: Comparator<GraphNode> = Comparator { a, b ->
        val sa = (a as? GraphNode.PaimonSchemaNode)?.simpleId ?: Int.MAX_VALUE
        val sb = (b as? GraphNode.PaimonSchemaNode)?.simpleId ?: Int.MAX_VALUE
        sa.compareTo(sb)
    }

    /** Base before delta before changelog: what the snapshot is, then what it changed. */
    private val manifestListKindRank = mapOf("base" to 0, "delta" to 1, "changelog" to 2)

    val PAIMON_MANIFEST_LIST: Comparator<GraphNode> = Comparator { a, b ->
        val ka = (a as? GraphNode.PaimonManifestListNode)?.kind
        val kb = (b as? GraphNode.PaimonManifestListNode)?.kind
        (manifestListKindRank[ka] ?: 3).compareTo(manifestListKindRank[kb] ?: 3)
    }

    val PAIMON_MANIFEST: Comparator<GraphNode> = Comparator { a, b ->
        val ma = (a as? GraphNode.PaimonManifestNode)?.simpleId ?: Int.MAX_VALUE
        val mb = (b as? GraphNode.PaimonManifestNode)?.simpleId ?: Int.MAX_VALUE
        ma.compareTo(mb)
    }

    val PAIMON_FILE: Comparator<GraphNode> = Comparator { a, b ->
        val fa = (a as? GraphNode.PaimonDataFileNode)?.simpleId ?: Int.MAX_VALUE
        val fb = (b as? GraphNode.PaimonDataFileNode)?.simpleId ?: Int.MAX_VALUE
        fa.compareTo(fb)
    }

    fun forKind(kind: AggregationKind, lineageRank: Map<String, Int>): Comparator<GraphNode> = when (kind) {
        AggregationKind.METADATA -> METADATA
        AggregationKind.SNAPSHOT -> snapshot(lineageRank)
        AggregationKind.MANIFEST -> MANIFEST
        AggregationKind.FILE -> FILE
        AggregationKind.ROW -> ROW
        AggregationKind.PAIMON_SNAPSHOT -> PAIMON_SNAPSHOT
        AggregationKind.PAIMON_SCHEMA -> PAIMON_SCHEMA
        AggregationKind.PAIMON_MANIFEST_LIST -> PAIMON_MANIFEST_LIST
        AggregationKind.PAIMON_MANIFEST -> PAIMON_MANIFEST
        AggregationKind.PAIMON_FILE -> PAIMON_FILE
    }
}
