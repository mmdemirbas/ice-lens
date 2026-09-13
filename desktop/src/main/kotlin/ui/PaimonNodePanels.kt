package ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import model.isEmpty
import model.GraphModel
import model.GraphNode
import model.MAIN_BRANCH
import model.PaimonFileSource
import model.paimonManifestTallies
import java.io.File

// The inspector panels for Paimon's node kinds: a snapshot, a schema, a manifest list, a manifest, a
// data file. Each was a branch of NodeDetailsContent's `when`.

@Composable
internal fun ColumnScope.PaimonSnapshotPanel(
    node: GraphNode.PaimonSnapshotNode,
    currentGraph: GraphModel,
) {
        DetailTable {
            DetailRow("Property", "Value", isHeader = true)
            DetailRow("Snapshot ID", "${node.data.id ?: "N/A"}")
            // Which snapshot/ the file came from: a branch's ids are its own, so
            // this is the row that tells its snapshot 2 from main's.
            DetailRow("Branch", node.branch ?: "$MAIN_BRANCH — the table's own snapshot/")
            // A tag is a copy of the snapshot file under tag/, and after an expiry
            // it can be the only copy — which is a different thing from a commit a
            // reader can time-travel to by id, so the panel says which it is.
            DetailRow("Tags", node.tags.joinToString(", ").ifEmpty { "none" })
            if (node.retainedByTagOnly) {
                DetailRow("Retained By", "its tag only — expired from snapshot/, files kept on disk by the tag")
            }
            DetailRow("Version", "${node.data.version ?: "N/A"}")
            DetailRow("Schema ID", "${node.data.schemaId ?: "N/A"}")
            DetailRow("Commit Kind", node.commitKind ?: "N/A")
            DetailRow("Commit User", node.data.commitUser ?: "N/A", copyable = true)
            DetailRow("Commit Identifier", "${node.data.commitIdentifier ?: "N/A"}")
            DetailRow("Timestamp", ui.formatTimestamp(node.data.timeMillis))
            // The three record counts are checked below, against the manifests.
            DetailRow("Watermark", "${node.data.watermark ?: "N/A"}")
            DetailRow("Base Manifest List", node.data.baseManifestList ?: "N/A", copyable = true)
            DetailRow("Delta Manifest List", node.data.deltaManifestList ?: "N/A", copyable = true)
            DetailRow("Changelog Manifest List", node.data.changelogManifestList ?: "N/A", copyable = true)
            DetailRow("Index Manifest", node.data.indexManifest ?: "N/A", copyable = true)
            DetailRow("Statistics File", node.data.statistics ?: "N/A", copyable = true)
            // Written from snapshot version 3. Absent on an older table, which is
            // a different thing from a table that has produced no rows yet.
            DetailRow("Next Row ID", "${node.data.nextRowId ?: "not recorded"}")
        }
        PaimonRecordsSection(node)
        PaimonMergedCountSection(node)
        PartitionsSection(node)
        PaimonCompactionSection(node)
        PaimonIndexFilesSection(node)
        PaimonStatisticsSection(node)
        RecursiveDataTableSection(node = node, graphModel = currentGraph)
}

@Composable
internal fun ColumnScope.PaimonSchemaPanel(
    node: GraphNode.PaimonSchemaNode,
) {
        DetailTable {
            DetailRow("Property", "Value", isHeader = true)
            DetailRow("Schema ID", "${node.data.id ?: "N/A"}")
            DetailRow("Highest Field ID", "${node.data.highestFieldId ?: "N/A"}")
            DetailRow("Partition Keys", node.data.partitionKeys.joinToString(", ").ifEmpty { "N/A" })
            DetailRow("Primary Keys", node.data.primaryKeys.joinToString(", ").ifEmpty { "N/A" })
            DetailRow("Comment", node.data.comment ?: "N/A")
        }
        if (node.data.fields.isNotEmpty()) {
            Section("Fields") {
                DetailTable {
                    DetailRow("Name", "Type", isHeader = true)
                    node.data.fields.forEach { field ->
                        DetailRow(field.name ?: "?", field.type ?: "?")
                    }
                }
            }
        }
        if (node.data.options.isNotEmpty()) {
            Section("Options") {
                DetailTable {
                    DetailRow("Key", "Value", isHeader = true)
                    node.data.options.forEach { (k, v) -> DetailRow(k, v) }
                }
            }
        }
}

@Composable
internal fun ColumnScope.PaimonManifestListPanel(
    node: GraphNode.PaimonManifestListNode,
    currentGraph: GraphModel,
) {
        DetailTable {
            DetailRow("Property", "Value", isHeader = true)
            DetailRow("Kind", node.kind)
            DetailRow("Manifests", formatCount(node.manifestCount))
            DetailRow("Path", node.localPath ?: "N/A", copyable = true)
        }
        RecursiveDataTableSection(node = node, graphModel = currentGraph)
}

@Composable
internal fun ColumnScope.PaimonManifestPanel(
    node: GraphNode.PaimonManifestNode,
    currentGraph: GraphModel,
) {
    val colors = MaterialTheme.colorScheme
        DetailTable {
            DetailRow("Property", "Value", isHeader = true)
            DetailRow("File Name", node.data.fileName ?: "N/A", copyable = true)
            DetailRow("File Size", "${node.data.fileSize ?: "N/A"}")
            DetailRow("Added Files", "${node.data.numAddedFiles ?: "N/A"}")
            DetailRow("Deleted Files", "${node.data.numDeletedFiles ?: "N/A"}")
            DetailRow("Schema ID", "${node.data.schemaId ?: "N/A"}")
        }
        // The same section the Iceberg manifest has, for the same reason: a scan
        // plans against these without opening the manifest, and nothing on the
        // read path checks them. Paimon records ranges as well as counts, and the
        // partition minimum is per column — see paimonManifestTallies.
        Section("Recorded Summary") {
            Text(
                "The manifest list carries these so a scan can plan without opening this " +
                    "manifest — the entry counts, the bucket and level ranges, and a per-column " +
                    "minimum and maximum over the entries' partitions. Each sits beside the same " +
                    "figure folded from the entries.",
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            val tallies = paimonManifestTallies(node.data, node.entries, node.partitionMin, node.partitionMax)
            WideTable(
                headers = listOf("Agrees", "Figure", "In the entries", "Recorded"),
                columnWidths = listOf(110.dp, 170.dp, 160.dp, 160.dp),
                rows = tallies.map { tally ->
                    listOf(
                        when (tally.agrees) {
                            true -> "yes"
                            false -> "NO"
                            null -> "nothing to check"
                        },
                        tally.label,
                        tally.counted ?: "no entries",
                        tally.recorded ?: "not recorded",
                    )
                },
                leadCellColors = tallies.map { tally ->
                    when (tally.agrees) {
                        true -> null
                        false -> colors.error
                        null -> verdictUnevaluatedColor()
                    }
                },
            )
        }
        PaimonReplayTraceSection(node)
        RecursiveDataTableSection(node = node, graphModel = currentGraph)
}

@Composable
internal fun ColumnScope.PaimonDataFilePanel(
    node: GraphNode.PaimonDataFileNode,
    currentGraph: GraphModel,
) {
    val colors = MaterialTheme.colorScheme
        val file = node.entry.file
        DetailTable {
            DetailRow("Property", "Value", isHeader = true)
            DetailRow("File Name", file?.fileName ?: "N/A", copyable = true)
            DetailRow("Kind", if (node.operationKind == 1) "DELETE" else "ADD")
            // Decoded from the entry's _PARTITION, and it is the directory the
            // file lives under: the value a reader wants beside the text Paimon
            // wrote in the path, which for a date is its epoch day.
            val partition = node.partition
            DetailRow(
                "Partition",
                when {
                    partition == null -> "not decoded — the entry's partition could not be read against the schema"
                    partition.values.isEmpty() -> "none — the table is unpartitioned"
                    else -> partition.display + " (${partition.path})"
                },
            )
            DetailRow("Bucket", "${node.bucket ?: "N/A"}")
            DetailRow("Total Buckets", "${node.entry.totalBuckets ?: "N/A"}")
            DetailRow(
                "LSM Level",
                "${node.level ?: "N/A"}" + if (node.unreadByBatchRead) {
                    " — a batch read of this table skips level-0 files, so a row here is not returned until a compaction moves it up"
                } else "",
            )
            DetailRow("File Size", "${file?.fileSize ?: "N/A"}")
            DetailRow("Row Count", "${file?.rowCount ?: "N/A"}")
            DetailRow("Schema ID", "${file?.schemaId ?: "N/A"}")
            DetailRow("Min Seq", "${file?.minSequenceNumber ?: "N/A"}")
            DetailRow("Max Seq", "${file?.maxSequenceNumber ?: "N/A"}")
            DetailRow("Creation Time", ui.formatTimestamp(file?.creationTime))
            // The trimmed primary key's range — which keys a lookup can find in
            // this file — and two facts about how the file was written.
            val keyMin = node.keyMin
            val keyMax = node.keyMax
            DetailRow(
                "Key Range",
                when {
                    keyMin == null || keyMax == null -> "none — no primary key outside the partition, or not decoded"
                    else -> keyMin.joinToString(", ") { "${it.name}=${it.display}" } +
                        " .. " + keyMax.joinToString(", ") { "${it.name}=${it.display}" }
                },
            )
            DetailRow(
                "Delete Rows",
                file?.deleteRowCount?.let { "${formatCount(it)} — rows of kind -D/-U inside the file, not rows a vector marks" } ?: "N/A",
            )
            DetailRow(
                "Source",
                when (file?.fileSource) {
                    PaimonFileSource.APPEND -> "APPEND — written by a commit"
                    PaimonFileSource.COMPACT -> "COMPACT — written by a compaction"
                    null -> "N/A"
                    else -> "${file.fileSource}"
                },
            )
            file?.externalPath?.let { DetailRow("External Path", it, copyable = true) }
            // Only worth a row where there was something to resolve: the layout is
            // the format's rule, and a file written to data-file.external-paths is
            // the one case where "not found" is about a path the table named.
            when (node.pathResolution) {
                model.PaimonPathResolution.LAYOUT -> Unit
                model.PaimonPathResolution.EXTERNAL_RECORDED ->
                    DetailRow("Resolved", "at the external path the entry records")
                model.PaimonPathResolution.EXTERNAL_REROOTED ->
                    DetailRow("Resolved", "by the recorded external path's tail under the local warehouse (the recorded path is not present here)")
                model.PaimonPathResolution.EXTERNAL_MISSING ->
                    DetailRow("Resolved", "not found — the entry records an external path that is not on this machine, and nothing under the local warehouse matches its tail; the layout path above stands in")
            }
            // Row tracking: a file a commit wrote records its first id and the
            // rest follow in file order; a compaction's output records none and
            // carries each row's id in its _ROW_ID column. An APPEND file with no
            // first id is a table without row tracking.
            val firstRowId = file?.firstRowId
            DetailRow(
                "Row IDs",
                when {
                    firstRowId != null -> {
                        val last = firstRowId + (file?.rowCount ?: 1L) - 1L
                        "$firstRowId .. $last — first id recorded, the rest follow in file order"
                    }
                    file?.fileSource == PaimonFileSource.COMPACT ->
                        "carried per row in the file's _ROW_ID column — a compaction's output records no first id"
                    else -> "none — row tracking is not enabled"
                },
            )
            // Data evolution: a MERGE INTO writes the columns it set to a file of
            // their own, and the pairing is the e_patch_* edge the builder drew —
            // same partition, bucket and first row id. Named from both ends,
            // because "this file holds only b" and "this file's b is elsewhere"
            // are each half of one read.
            val writeCols = file?.writeCols?.takeIf { node.partial }
            val patchTargets = currentGraph.edges
                .filter { it.id.startsWith("e_patch_") && it.fromId == node.id }
                .mapNotNull { currentGraph.nodeById[it.toId] as? GraphNode.PaimonDataFileNode }
            val patchedBy = currentGraph.edges
                .filter { it.id.startsWith("e_patch_") && it.toId == node.id }
                .mapNotNull { currentGraph.nodeById[it.fromId] as? GraphNode.PaimonDataFileNode }
            if (writeCols != null) {
                DetailRow(
                    "Columns",
                    writeCols.joinToString(", ") + " only — a partial-column file: a read stitches it with " +
                        (patchTargets.takeIf { it.isNotEmpty() }
                            ?.joinToString(", ") { it.entry.file?.fileName ?: it.id }
                            ?: "the file holding the same row ids") +
                        " by row id, the higher sequence number winning a column",
                )
            } else if (file?.writeCols != null) {
                // Every schema column listed, the row-tracking system columns beside them: a full
                // compaction under row-tracking.enabled writes this, and it is a whole file.
                DetailRow("Columns", file.writeCols.orEmpty().joinToString(", ") + " — every column; recorded by the compaction that wrote the file")
            } else if (patchedBy.isNotEmpty()) {
                DetailRow(
                    "Patched By",
                    patchedBy.joinToString("; ") { p ->
                        "${p.entry.file?.fileName ?: p.id} (${p.entry.file?.writeCols.orEmpty().joinToString(", ")})"
                    } + " — a later MERGE INTO rewrote those columns for these rows without rewriting this file",
                )
            }
            // Where the file index lives is decided by its size against
            // file-index.in-manifest-threshold: beside the data file and named in
            // _EXTRA_FILES, or carried in the entry. Both are stated, and "none" is
            // an answer — a table with no file-index.* property has none.
            val embedded = file?.embeddedFileIndex
            val indexFiles = file?.extraFiles.orEmpty().filter { it.endsWith(".index") }
            // What the index holds is read off its head — which columns, which index types — and
            // said beside where it lives; an index this could not read says that instead.
            val decoded = node.fileIndex.value?.let { read -> read.index?.describe() ?: "not read: ${read.error}" }
            DetailRow(
                "File Index",
                when {
                    embedded != null -> "embedded in the manifest entry, ${formatBytesExact(embedded.size.toLong())}"
                    indexFiles.isNotEmpty() -> indexFiles.joinToString(", ") { "$it, beside the data file" }
                    else -> "none"
                } + (decoded?.let { " — $it" } ?: ""),
            )
            if (!file?.extraFiles.isNullOrEmpty()) DetailRow("Extra Files", file?.extraFiles.orEmpty().joinToString(", "))
        }
        FileHistorySection(node.history, currentGraph)
        PaimonDeletionVectorSection(node)
        // The file's own bounds, the same section the Iceberg data file has: a
        // scan skips a file whose bounds exclude the predicate without opening it.
        val bounds = node.columnBounds
        if (bounds != null) {
            Section("Column Bounds (${formatCount(bounds.size)})") {
                Text(
                    "From the entry's _VALUE_STATS: a per-column minimum, maximum and null " +
                        "count over the rows in this file, two BinaryRows decoded against the " +
                        "schema the file's own _SCHEMA_ID names — the one it was written under, " +
                        "which is not always the manifest's. A string bound is the whole value, " +
                        "not a truncated prefix." +
                        // A subset is a decision the writer recorded — fields.<col>.stats-mode
                        // = none under the dense store — and the columns left out are the
                        // ones a scan cannot prune this file on.
                        (file?.valueStatsCols?.let {
                            " Recorded for the ${formatCount(it.size)} columns _VALUE_STATS_COLS names " +
                                "(${it.joinToString(", ")}); the rest of the schema has no statistics in this file."
                        } ?: " Recorded for every column of the schema, in schema order."),
                    fontSize = TypeScale.small,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                WideTable(
                    headers = listOf("Column", "Minimum", "Maximum", "Nulls", "Type"),
                    columnWidths = listOf(140.dp, 180.dp, 180.dp, 70.dp, 150.dp),
                    rows = bounds.map { bound ->
                        listOf(
                            bound.name,
                            if (bound.decoded) bound.min?.toString() ?: "null" else "not decoded",
                            if (bound.decoded) bound.max?.toString() ?: "null" else "not decoded",
                            bound.nullCount?.let { formatCount(it) } ?: "N/A",
                            bound.type,
                        )
                    },
                )
            }
        }
        RecursiveDataTableSection(node = node, graphModel = currentGraph)
}
