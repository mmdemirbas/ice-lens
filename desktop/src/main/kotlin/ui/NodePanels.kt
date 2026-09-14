package ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import model.ScanFilter
import model.GraphModel
import model.GraphNode
import model.snapshotAsOf
import model.MAIN_BRANCH
import model.PaimonRowKind
import model.describe
import java.io.File

// The inspector panels shared by both formats: the table, a sample row, a read error, a folded page.
// Each was a branch of NodeDetailsContent's `when`; the helpers they call stay in NodeDetails.kt.

@Composable
internal fun ColumnScope.TablePanel(
    node: GraphNode.TableNode,
    currentGraph: GraphModel,
    children: List<GraphNode>,
    scanFilter: ScanFilter,
    onScanFilterChange: (ScanFilter) -> Unit,
) {
        val summary = node.summary
        val metadataChildren = children
            .filterIsInstance<GraphNode.MetadataNode>()
            .sortedBy { it.simpleId }
        val metadataNodeByFileName = metadataChildren.associateBy { it.fileName }
        val versionFileNames = summary.metadataVersions.map { it.fileName }.toSet()
        val mergedMetadataRows = buildList {
            summary.metadataVersions.forEachIndexed { index, version ->
                val metadataNode = metadataNodeByFileName[version.fileName]
                add(
                    listOf(
                        "${index + 1}",
                        metadataNode?.id ?: "N/A",
                        version.fileName,
                        "${version.version ?: "N/A"}",
                        formatTimestampShort(version.fileLastModifiedMs),
                        formatTimestampShort(version.metadataLastUpdatedMs),
                        "${version.snapshotCount}",
                        currentSnapshotLabel(version.currentSnapshotId)
                    )
                )
            }
            metadataChildren
                .filter { it.fileName !in versionFileNames }
                .forEachIndexed { index, metadataNode ->
                    add(
                        listOf(
                            "${summary.metadataVersions.size + index + 1}",
                            metadataNode.id,
                            metadataNode.fileName,
                            "N/A",
                            "N/A",
                            formatTimestampShort(metadataNode.data.lastUpdatedMs),
                            "${metadataNode.data.snapshots.size}",
                            currentSnapshotLabel(metadataNode.data.currentSnapshotId)
                        )
                    )
                }
        }

        DetailTable {
            DetailRow("Property", "Value", isHeader = true)
            DetailRow("Name", summary.tableName)
            DetailRow("Table Path", summary.tablePath, copyable = true)
            DetailRow("Location", summary.location ?: "N/A", copyable = true)
            // Only where the metadata is not under the location: the data files are under the
            // location and this directory holds the metadata alone — a `write.metadata.path`
            // layout, or the catalog-storage export a Paimon table writes (`pih`).
            summary.metadataKeptApartAt?.let { DetailRow("Metadata Kept At", it, copyable = true) }
            DetailRow("Table UUID", summary.tableUuid ?: "N/A", copyable = true)
            DetailRow("Format Version", "${summary.formatVersion ?: "N/A"}")
            DetailRow("Current Snapshot ID", currentSnapshotLabel(summary.currentSnapshotId))
            DetailRow("Current Metadata Version", "${summary.currentMetadataVersion ?: "N/A"}")
            DetailRow(
                "version-hint.text",
                summary.versionHintText?.takeIf { it.isNotBlank() }
                    ?: "Not present — normal unless the table is HadoopCatalog-managed"
            )
        }
        if (summary.metadataKeptApartAt != null) {
            Text(
                "The metadata is kept apart from the table's location: this directory holds the metadata " +
                    "and the data files are under the location — a write.metadata.path layout, or the " +
                    "Iceberg metadata a Paimon table writes in catalog storage.",
                fontSize = TypeScale.small,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // Only where the format keeps branches under the table: a Paimon branch is
        // a directory of its own snapshots over the table's data, and an Iceberg
        // branch is a ref on the metadata file, listed on that node. Null is the
        // second case; an empty list is a Paimon table with no branch/, which is
        // an answer.
        summary.branches?.let { branches ->
            CountedSection("Branches", branches.size, "branches — the table's own snapshot/ is $MAIN_BRANCH") {
                WideTable(
                    headers = listOf("Branch", "Snapshots", "Latest", "Schemas", "Tags", "Read Errors", "Path"),
                    rows = branches.map { branch ->
                        listOf(
                            branch.name,
                            "${branch.snapshotCount}",
                            branch.latestSnapshotId?.toString() ?: "none — created empty",
                            "${branch.schemaCount}",
                            "${branch.tagCount}",
                            "${branch.readErrorCount}",
                            branch.path,
                        )
                    },
                    columnWidths = listOf(110.dp, 80.dp, 200.dp, 70.dp, 60.dp, 90.dp, 220.dp),
                )
            }
        }

        // A consumer is a streaming reader's bookmark, and the reason an expiry
        // stops short: expire_snapshots keeps every snapshot from the reader's
        // next one on. Null on Iceberg, which has no such thing.
        summary.consumers?.let { consumers ->
            CountedSection("Consumers", consumers.size, "consumers — no streaming reader has left a bookmark under consumer/") {
                Text(
                    "Each is a streaming reader's bookmark: the snapshot it will consume next, " +
                        "which expire_snapshots will not expire, nor anything after it. A reader " +
                        "standing on an old snapshot is why a table keeps more history than its " +
                        "retention says.",
                    fontSize = TypeScale.small,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                // The exception leads: a bookmark whose snapshot is gone is a
                // reader that will fail on its next read, and it is the one row
                // that has to be findable without reading the others.
                WideTable(
                    headers = listOf("Next in snapshot/", "Consumer", "Next Snapshot", "Path"),
                    rows = consumers.map { consumer ->
                        listOf(
                            if (consumer.nextSnapshotPresent) "yes" else "NO — expired from under the reader",
                            consumer.name,
                            consumer.nextSnapshot?.toString() ?: "not recorded",
                            consumer.path,
                        )
                    },
                    columnWidths = listOf(240.dp, 120.dp, 120.dp, 220.dp),
                    leadCellColors = consumers.map { if (it.nextSnapshotPresent) null else MaterialTheme.colorScheme.error },
                )
            }
        }

        // Beside the consumers, because a consumer is the usual answer to the
        // question this section asks. Iceberg's plan sits on the metadata node,
        // which is where Iceberg keeps the refs and the retention properties.
        MaintenanceSection(node)
        summary.paimonExpiry?.let { PaimonExpirySection(it, nowMs = expiryClock()) }
        summary.paimonExpiry?.let { PaimonExpiryFilesSection(node, it, nowMs = expiryClock()) }
        summary.paimonExpiry?.let { input ->
            TimeTravelSection(
                intro = "Which snapshot scan.timestamp-millis lands on: the latest snapshot whose commit time " +
                    "is at or before it, the way SnapshotManager.earlierOrEqualTimeMills searches — nothing " +
                    "when the earliest retained snapshot is already later, and never a tag.",
                initialMs = expiryClock(),
                resolve = { input.snapshotAsOf(it) },
                operationOf = { id -> (currentGraph.nodeById["psnap_$id"] as? GraphNode.PaimonSnapshotNode)?.let { it.commitKind ?: it.data.commitKind } },
                currentSnapshotId = summary.currentSnapshotId,
            )
        }

        RecursiveDataTableSection(node = node, graphModel = currentGraph)

        // Directly under the table's identity, because it is the only control on
        // this panel and everything below it is a readout. It was first placed
        // after the history figures, which reads well in a listing of section
        // names and put it 6,000dp down the rendered panel — past the metadata
        // file times, the manifest list times, both stat blocks and both
        // derivation ledgers. A control nobody scrolls to is a control nobody
        // has. It costs about eighty dp here while no filter is entered.
        currentGraph.let { graph ->
            ScanPruningSection(graph, scanFilter, onScanFilterChange)
            RowLookupSection(node, graph, scanFilter)
        }
        // The panel's other controls, kept beside the first for the same reason.
        IntegritySection(node)
        UnreferencedFilesSection(node)
        IcebergExportSection(node)

        // Folded, and out of the identity table above, because none of the three
        // is identity and together they were the largest thing on the panel: each
        // renders local, UTC and epoch, so three rows are nine lines and about
        // 600dp — roughly half of the ~1,300dp that stood between "Collapse all"
        // and the list of section names it produces. The same check on the other
        // node kinds says this is not a general rule about timestamps: a
        // snapshot's `Timestamp` and a Paimon data file's `Creation Time` are
        // recorded, singular, and part of what identifies the artifact, so they
        // stay where they are.
        Section("Table Times") {
            Text(
                "None of these is recorded by Iceberg as a table creation or " +
                    "update time — there is no such field. The first two are the " +
                    "ends of the retained metadata timeline, so expiring old " +
                    "metadata moves \"created\" forward and the table can be far " +
                    "older than it says.",
                fontSize = TypeScale.small,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            DetailTable {
                DetailRow("Property", "Value", isHeader = true)
                DetailRow(
                    "Oldest retained metadata",
                    formatTimestamp(summary.tableCreationMs),
                )
                DetailRow(
                    "Newest retained metadata",
                    formatTimestamp(summary.tableLastUpdateMs),
                )
                DetailRow(
                    "Current metadata last-updated-ms",
                    formatTimestamp(summary.lastUpdatedMs),
                )
            }
        }

        Section("Metadata Files") {
            DetailTable {
                DetailRow("Metric", "Value", isHeader = true)
                DetailRow("File Count", "${summary.metadataFileCount}")
                DetailRow("Known", "${summary.metadataFileTimes.knownCount}")
                DetailRow("Missing", "${summary.metadataFileTimes.missingCount}")
                DetailRow("Oldest", formatTimestamp(summary.metadataFileTimes.oldestMs))
                DetailRow("Latest", formatTimestamp(summary.metadataFileTimes.newestMs))
            }
        }

        Section("Snapshot Manifest Lists") {
            DetailTable {
                DetailRow("Metric", "Value", isHeader = true)
                DetailRow("Unique Snapshots", "${summary.snapshotCount}")
                DetailRow("File Count", "${summary.snapshotManifestListFileCount}")
                DetailRow("Known", "${summary.snapshotManifestListFileTimes.knownCount}")
                DetailRow("Missing", "${summary.snapshotManifestListFileTimes.missingCount}")
                DetailRow("Oldest", formatTimestamp(summary.snapshotManifestListFileTimes.oldestMs))
                DetailRow("Latest", formatTimestamp(summary.snapshotManifestListFileTimes.newestMs))
            }
        }

        Section("Current Snapshot") {
            val current = summary.current
            if (summary.currentSnapshotId == null) {
                DetailTable {
                    DetailRow("Metric", "Value", isHeader = true)
                    DetailRow("State", "No current snapshot — the table has no committed data")
                }
            } else {
                DetailTable {
                    DetailRow("Metric", "Value", isHeader = true)
                    DetailRow("Snapshot ID", currentSnapshotLabel(summary.currentSnapshotId))
                    DetailRow(
                        "Records",
                        formatCount(current.recordCount) + if (current.partialRecordCount > 0) {
                            " — ${formatCount(current.partialRecordCount)} of them in partial-column files, columns of rows other files hold; ${formatCount(current.readRecordCount)} rows read"
                        } else "",
                    )
                    DetailRow("Data Files", "${formatCount(current.dataFileCount)}  (${formatBytes(current.dataSizeBytes)})")
                    DetailRow(
                        "Delete Files",
                        "${formatCount(current.deleteFileCount)}  (${formatBytes(current.deleteSizeBytes)})" +
                            " — ${formatCount(current.posDeleteFileCount)} pos / ${formatCount(current.eqDeleteFileCount)} eq"
                    )
                    DetailRow("Delete Records", formatCount(current.deleteRecordCount))
                    DetailRow("Total Size", formatBytes(current.totalSizeBytes))
                    DetailRow("Manifests", "${formatCount(current.manifestCount)}  (${formatCount(current.dataManifestCount)} data / ${formatCount(current.deleteManifestCount)} delete)")
                    DetailRow("Manifest Entries", "${formatCount(current.manifestEntryCount)}  (${formatCount(current.deletedEntryCount)} recording a removal)")
                }
                DerivationSection("How the current figures were derived", summary.currentDerivation)
            }
        }

        Section("All Retained History") {
            val history = summary.history
            DetailTable {
                DetailRow("Metric", "Value", isHeader = true)
                DetailRow("Snapshots", formatCount(summary.snapshotCount))
                DetailRow("Metadata Versions", formatCount(summary.metadataFileCount))
                DetailRow(
                    "Manifests",
                    "${formatCount(history.manifestCount)}  (${formatCount(history.dataManifestCount)} data / ${formatCount(history.deleteManifestCount)} delete)"
                )
                DetailRow("Manifest Entries", "${formatCount(history.manifestEntryCount)}  (${formatCount(history.deletedEntryCount)} recording a removal)")
                DetailRow("Distinct Data Files", "${formatCount(history.dataFileCount)}  (${formatBytes(history.dataSizeBytes)})")
                DetailRow(
                    "Distinct Delete Files",
                    "${formatCount(history.deleteFileCount)}  (${formatBytes(history.deleteSizeBytes)})"
                )
                DetailRow("Referenced Bytes", formatBytes(history.totalSizeBytes))
                DetailRow("Manifest Files Known / Missing", "${summary.manifestFileTimes.knownCount} / ${summary.manifestFileTimes.missingCount}")
                // One row per timestamp. formatTimestamp returns three lines (local,
                // UTC, epoch), so joining two of them into one cell runs the second
                // block's first line onto the first block's last one and pushes the
                // rest past maxLines — the arrow ends up mid-paragraph and the second
                // epoch is simply not drawn. The Metadata Files section above already
                // uses separate rows; this follows it.
                DetailRow("Manifest Files Oldest", formatTimestamp(summary.manifestFileTimes.oldestMs))
                DetailRow("Manifest Files Latest", formatTimestamp(summary.manifestFileTimes.newestMs))
                DetailRow("Data Files Known / Missing", "${summary.dataFileTimes.knownCount} / ${summary.dataFileTimes.missingCount}")
                DetailRow("Data Files Oldest", formatTimestamp(summary.dataFileTimes.oldestMs))
                DetailRow("Data Files Latest", formatTimestamp(summary.dataFileTimes.newestMs))
            }
            DerivationSection("How the history figures were derived", summary.historyDerivation)
        }

        if (mergedMetadataRows.isNotEmpty()) {
            Section("Metadata Nodes & Timeline") {
                WideTable(
                    headers = listOf(
                        "Order",
                        "Node",
                        "File",
                        "Version",
                        "File Updated",
                        "Metadata Last Updated",
                        "Snapshots",
                        "Current Snapshot ID"
                    ),
                    rows = mergedMetadataRows
                )
            }
        }

        SchemaEvolutionSection(node.schemaEvolution)
        PropertiesEvolutionSection(summary.metadataVersions)
}

@Composable
internal fun ColumnScope.RowPanel(
    node: GraphNode.RowNode,
    currentGraph: GraphModel,
) {
        // The delete files still open for a data row's file — what `RowDeletesSection` asks.
        val rowDeletes = remember(node.id, currentGraph.nodes) {
            if (node.content != 0) emptyList()
            else rowParentFile(node, currentGraph)?.let { rowDeleteCandidates(it, currentGraph) }.orEmpty()
        }
        DetailTable {
            val typeStr = when (node.content) {
                1 -> "Position Delete Row"
                2 -> "Equality Delete Row"
                else -> "Data Row"
            }
            DetailRow("Column", "Value ($typeStr)", isHeader = true)
            DetailRow("file_no", node.data["file_no"]?.toString() ?: "N/A")
            DetailRow("row_idx", node.data["row_idx"]?.toString() ?: "N/A")
            // Physical position, and whether a v3 deletion vector removes it.
            // `row_idx` is this tool's counter over the sample; the position is
            // the file's own, and it is the one a delete addresses.
            node.filePosition?.let { position ->
                DetailRow("Position in file", position.toString())
                // Only where the builder resolved the file's vector — on either format;
                // a Paimon vector that could not be read leaves the row unresolved. A vector
                // is the one delete decided at build time; where the pairing leaves a
                // positional or equality delete for the file, the row says the question is
                // still open and where it is asked, or it contradicts the section below it.
                if (node.vectorsResolved) {
                    DetailRow(
                        "Deleted",
                        when {
                            node.isDeletedByVector -> "yes — a deletion vector marks this position"
                            rowDeletes.isNotEmpty() -> "not by a deletion vector; ${formatCounted(rowDeletes.size, "delete file")} paired with this file " +
                                (if (rowDeletes.size == 1) "is" else "are") + " asked under Delete Files below"
                            else -> "not by a deletion vector"
                        },
                    )
                }
            }
            // A Paimon key-value row says what it does to its key, and the byte
            // is read here rather than printed as 0..3 — a `-D` row is stored like
            // any other and only this says it is a deletion.
            node.paimonRowKind?.let { kind ->
                DetailRow(
                    "Row Kind",
                    PaimonRowKind.describe(kind) + if (PaimonRowKind.isRetraction(kind)) {
                        " — a retraction: merged with the levels below, it removes the key's earlier value"
                    } else "",
                )
            }
            // The row's cells, once read. `data` is the placeholder the builder
            // emits before any file is opened — file_no and row_idx and nothing
            // else — and listing it here left the panel without a single cell of
            // the row that was selected, while the card beside it drew five.
            node.readError?.let { DetailRow("Not Read", it) }
            node.resolvedData.entries
                .filterNot { it.key == "file_no" || it.key == "row_idx" || it.key == GraphNode.RowNode.ROW_POSITION_KEY || it.key == "local_file_path" || it.key == GraphNode.RowNode.ROW_READ_ERROR_KEY }
                .sortedBy { it.key }
                .forEach { (k, v) -> DetailRow(k, "$v") }
        }

        // What a query returns for the row, where the table's schema has moved on since the file.
        ReadAsSection(node)
        // A v2 positional or an equality delete is a file read, so what the `Deleted` row above
        // cannot say for them is asked behind a click here.
        if (node.content == 0) RowDeletesSection(node, currentGraph)
        // And the Paimon twin: a record's fate under the merge engine, decided against the key's
        // other records in the bucket.
        PaimonRowMergeSection(node, currentGraph)
        RecursiveDataTableSection(node = node, graphModel = currentGraph)
}

@Composable
internal fun ColumnScope.ErrorPanel(
    node: GraphNode.ErrorNode,
) {
    val colors = MaterialTheme.colorScheme
        DetailTable {
            DetailRow("Property", "Value", isHeader = true)
            DetailRow("Title", node.title)
            DetailRow("Stage", node.stage)
            DetailRow("Path", node.path, copyable = true)
            DetailRow("Message", node.message)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Section("Stack Trace") {
                Spacer(Modifier.weight(1f))
                val trace = node.stackTrace
                if (!trace.isNullOrBlank()) {
                    TextButton(
                        onClick = {
                            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                            clipboard.setContents(java.awt.datatransfer.StringSelection(trace), null)
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text("Copy", fontSize = TypeScale.small)
                    }
                }
            }
        }
        val stackScroll = rememberScrollState()
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                .border(1.dp, colors.outlineVariant, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                .background(colors.surfaceVariant)
                .verticalScroll(stackScroll)
                .padding(8.dp)
        ) {
            Text(
                text = node.stackTrace ?: "N/A",
                fontFamily = FontFamily.Monospace,
                fontSize = TypeScale.small
            )
        }
}

@Composable
internal fun ColumnScope.GroupPanel(
    node: GraphNode.GroupNode,
    onExpandGroup: (String) -> Unit,
    onExpandGroupFully: (GraphNode.GroupNode) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
        Section("Not Drawn") {
            Text(
                "The graph draws a page of siblings at a time. This stands for the " +
                    "${formatCount(node.memberCount)} ${node.kind.plural} after the ones above it, and for " +
                    "everything below them — ${formatCount(node.hiddenNodeCount)} nodes in all. " +
                    "The metadata behind them is read and counted either way: the table " +
                    "summary and every parent's own figures cover the whole table, drawn or not.",
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (node.hiddenErrorCount > 0) {
                Text(
                    "${formatCount(node.hiddenErrorCount)} read errors are inside this group. " +
                        "Open it to see which files failed.",
                    fontSize = TypeScale.small,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            // FlowRow, not Row: the second label grows with the count, and a Row
            // would place the button that does not fit past the panel's edge, where
            // Compose neither wraps it nor clips it.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { onExpandGroup(node.id) }) {
                    Text("Show the next page", fontSize = TypeScale.small)
                }
                // The whole tail, for the reader who would rather wait than click
                // two hundred times. The count is on the button because it is what
                // decides whether they want to; what it costs in nodes is the
                // paragraph above.
                OutlinedButton(onClick = { onExpandGroupFully(node) }) {
                    Text(
                        "Show all ${formatCount(node.memberCount)} ${node.kind.plural}",
                        fontSize = TypeScale.small,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            DetailTable {
                DetailRow("Property", "Value", isHeader = true)
                DetailRow("Kind", node.kind.plural)
                DetailRow("Siblings not drawn", formatCount(node.memberCount))
                DetailRow("Nodes not drawn", formatCount(node.hiddenNodeCount))
                DetailRow("Read errors inside", formatCount(node.hiddenErrorCount))
                DetailRow("Page", "${node.pageIndex}")
                DetailRow("Parent Node ID", node.parentId, copyable = true)
            }
        }
}
