package ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import model.newestIcebergMetadata
import model.fieldRows
import model.tableSchemaModel
import model.nameMapping
import model.deleteTargetsOf
import model.effectiveMinSequenceNumber
import model.effectiveSequenceNumber
import model.DataFile
import model.statisticsRows
import model.ScanFilter
import model.isEmpty
import model.DataFileContent
import model.GraphModel
import model.GraphNode
import model.recordedColumnStats
import model.ManifestEntryStatus
import model.TermEffect
import model.evaluatePruning
import model.manifestTallies
import model.metadataTallies
import model.partitionBoundsChecks
import model.snapshotAsOf
import model.partitionSummaryTallies
import model.sourceSnapshotId
import model.publishedWapId
import model.wapId
import model.describeRowIds
import model.snapshotHistory
import model.SnapshotLogKind
import model.checkPartitionStatistics
import model.PartitionStatsVerdict
import model.describe
import java.io.File

// The inspector panels for Iceberg's own node kinds: a metadata file, a snapshot, a manifest, a data
// or delete file. Each was a branch of NodeDetailsContent's `when`.

@Composable
internal fun ColumnScope.MetadataPanel(
    node: GraphNode.MetadataNode,
    currentGraph: GraphModel,
) {
    val colors = MaterialTheme.colorScheme
        DetailTable {
            DetailRow("Property", "Value", isHeader = true)
            DetailRow("File Name", node.fileName)
            DetailRow("Format Version", "${node.data.formatVersion}")
            DetailRow("Table UUID", "${node.data.tableUuid ?: "N/A"}", copyable = true)
            DetailRow("Location", "${node.data.location ?: "N/A"}", copyable = true)
            DetailRow("Last Seq. Num.", "${node.data.lastSequenceNumber ?: "N/A"}")
            // The v3 row-id counter: where the next commit's ids start. Drawn on
            // every version because "none" is a fact about a v2 table.
            DetailRow(
                "Next Row ID",
                node.data.nextRowId?.toString() ?: "N/A — row lineage is tracked from format version 3",
            )
            DetailRow("Last Updated", formatTimestamp(node.data.lastUpdatedMs))
            DetailRow("Last Column ID", "${node.data.lastColumnId ?: "N/A"}")
            DetailRow("Current Schema ID", "${node.data.currentSchemaId ?: "N/A"}")
            DetailRow("Default Spec ID", "${node.data.defaultSpecId ?: "N/A"}")
            DetailRow("Last Partition ID", "${node.data.lastPartitionId ?: "N/A"}")
            DetailRow("Default Sort Order ID", "${node.data.defaultSortOrderId ?: "N/A"}")
            DetailRow("Current Snapshot ID", currentSnapshotLabel(node.data.currentSnapshotId))
        }

        MetadataTalliesSection(metadataTallies(node.data))

        RecursiveDataTableSection(node = node, graphModel = currentGraph)

        CountedSection("Properties", node.data.properties.size, "properties") {
            DetailTable {
                DetailRow("Key", "Value", isHeader = true)
                node.data.properties.toSortedMap().forEach { (k, v) ->
                    DetailRow(k, v)
                }
            }
        }

        CountedSection("Schemas", node.data.schemas.size, "schemas") {
            node.data.schemas
                .sortedBy { it.schemaId ?: Int.MAX_VALUE }
                .forEach { schema ->
                    Text(
                        "Schema ${schema.schemaId ?: "Unknown"}",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = TypeScale.body
                    )
                    Spacer(Modifier.height(4.dp))
                    // One row per field, a struct's fields under it by path (`deep`); v3 column
                    // defaults drawn only where the schema records one — the initial default is
                    // what a read returns for rows written before the column, the write default
                    // what a writer stores when a row omits it, two figures that differ once
                    // `updateColumnDefault` has run (`defaults`).
                    val model = tableSchemaModel(schema)
                    SchemaFieldsTable(model.fieldRows(), identifierIds = model.identifierFieldIds)
                    Spacer(Modifier.height(12.dp))
                }
        }

        CountedSection("Partition Specs", node.data.partitionSpecs.size, "partition specs") {
            node.data.partitionSpecs
                .sortedBy { it.specId ?: Int.MAX_VALUE }
                .forEach { spec ->
                    // Which spec new writes take is a fact about the metadata,
                    // said on the heading of the one it names — the same rule as
                    // the sort orders below.
                    Text(
                        "Spec ${spec.specId ?: "Unknown"}" +
                            if (spec.specId != null && spec.specId == node.data.defaultSpecId) " (default)" else "",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = TypeScale.body
                    )
                    Spacer(Modifier.height(4.dp))
                    // A spec with no fields is the unpartitioned one, which is an
                    // answer; a table of N/A read as a decode that failed.
                    if (spec.fields.isEmpty()) {
                        Text(
                            "Unpartitioned — no fields, so every file is in the one partition.",
                            fontSize = TypeScale.small,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        WideTable(
                            headers = listOf("Source ID", "Field ID", "Name", "Transform"),
                            rows = spec.fields.map { field ->
                                listOf(
                                    "${field.sourceId ?: "N/A"}",
                                    "${field.fieldId ?: "N/A"}",
                                    field.name ?: "N/A",
                                    normalizeText(field.transform?.toString())
                                )
                            }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
        }

        CountedSection("Sort Orders", node.data.sortOrders.size, "sort orders") {
            node.data.sortOrders
                .sortedBy { it.orderId ?: Int.MAX_VALUE }
                .forEach { order ->
                    // Which one new writes take is a fact about the metadata, not
                    // about any order, so it is said on the heading of the one it names.
                    Text(
                        "Order ${order.orderId ?: "Unknown"}" +
                            if (order.orderId != null && order.orderId == node.data.defaultSortOrderId) " (default)" else "",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = TypeScale.body
                    )
                    Spacer(Modifier.height(4.dp))
                    // The column by name, resolved through this metadata's current
                    // schema, beside the id the order records; order 0 has no fields
                    // and is the unsorted order, which the one row says.
                    val currentSchema = node.data.schemas.firstOrNull { it.schemaId == node.data.currentSchemaId }
                    if (order.fields.isEmpty()) {
                        Text(
                            "Unsorted — no fields; a file written under it holds its rows in write order.",
                            fontSize = TypeScale.small,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        WideTable(
                            headers = listOf("Column", "Source ID", "Transform", "Direction", "Null Order"),
                            rows = order.fields.map { field ->
                                listOf(
                                    currentSchema?.fields?.firstOrNull { it.id == field.sourceId }?.name ?: "field ${field.sourceId ?: "?"}",
                                    "${field.sourceId ?: "N/A"}",
                                    field.transformName.ifEmpty { "N/A" },
                                    field.direction ?: "N/A",
                                    field.nullOrder ?: "N/A"
                                )
                            },
                            columnWidths = listOf(140.dp, 90.dp, 110.dp, 90.dp, 110.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
        }

        // A ref's three retention settings are what expire_snapshots reads for
        // the snapshots that ref reaches, in place of the table's defaults and of
        // the procedure's own older_than — the retained fixture is where a
        // branch's age shielded every snapshot of the table from an expiry.
        CountedSection("Refs", node.data.refs.size, "refs") {
            WideTable(
                headers = listOf("Name", "Type", "Snapshot ID", "Max Ref Age", "Max Snapshot Age", "Min Snapshots To Keep"),
                columnWidths = listOf(110.dp, 80.dp, 190.dp, 250.dp, 250.dp, 170.dp),
                rows = node.data.refs.toSortedMap().map { (name, ref) ->
                    listOf(
                        name,
                        ref.type ?: "N/A",
                        "${ref.snapshotId ?: "N/A"}",
                        formatRetentionMs(ref.maxRefAgeMs),
                        formatRetentionMs(ref.maxSnapshotAgeMs),
                        ref.minSnapshotsToKeep?.toString() ?: "not set",
                    )
                }
            )
        }

        IcebergFastForwardSection(node)
        ExpirySection(node.data, nowMs = expiryClock())
        ExpiryFilesSection(node.data, currentGraph, nowMs = expiryClock())

        CountedSection("Snapshots", node.data.snapshots.size, "snapshots") {
            val snapshots = node.data.snapshots.sortedBy { it.timestampMs ?: Long.MAX_VALUE }
            WideTable(
                headers = listOf(
                    "Snapshot ID",
                    "Parent Snapshot ID",
                    "Sequence Number",
                    "Schema ID",
                    "Timestamp",
                    "Manifest List",
                    "Operation",
                    "Summary"
                ),
                rows = snapshots.map { snapshot ->
                    listOf(
                        "${snapshot.snapshotId ?: "N/A"}",
                        "${snapshot.parentSnapshotId ?: "None"}",
                        snapshot.sequenceNumber?.toString() ?: "0 (v1)",
                        "${snapshot.schemaId ?: "N/A"}",
                        formatTimestampShort(snapshot.timestampMs),
                        normalizeText(snapshot.manifestList),
                        snapshot.summary["operation"] ?: "N/A",
                        if (snapshot.summary.isEmpty()) "N/A"
                        else snapshot.summary.toSortedMap().entries.joinToString(", ") { "${it.key}=${it.value}" }
                    )
                }
            )
        }

        // Beside the log it reads, because a rolled-back table is where the answer surprises.
        TimeTravelSection(
            intro = "Which snapshot TIMESTAMP AS OF lands on: the last snapshot-log entry at or before the " +
                "time, the way SnapshotUtil resolves it. A log entry is a moment main was pointed at a " +
                "snapshot, so on a rolled-back table a time between the abandoned commit and the reset " +
                "lands on the abandoned commit, and a time after the reset lands on its target.",
            initialMs = expiryClock(),
            resolve = { node.data.snapshotAsOf(it) },
            operationOf = { id -> node.data.snapshots.firstOrNull { it.snapshotId == id }?.summary?.get("operation") },
            currentSnapshotId = node.data.currentSnapshotId,
        )
        // Every entry is a moment main was pointed somewhere. An id appearing a
        // second time is a rollback, which writes no snapshot and is recorded
        // nowhere else; the ancestor column is what Iceberg's own .history table
        // prints as is_current_ancestor.
        CountedSection("Snapshot Log", node.data.snapshotLog.size, "snapshot log entries") {
            val history = node.data.snapshotHistory()
            WideTable(
                headers = listOf("Event", "Snapshot ID", "Timestamp", "Ancestor of Current"),
                columnWidths = listOf(360.dp, 180.dp, 230.dp, 130.dp),
                rows = history.map { event ->
                    listOf(
                        when (event.kind) {
                            SnapshotLogKind.COMMIT -> "made current — its commit"
                            SnapshotLogKind.RESET ->
                                "main set back to it" +
                                    event.leftBehind.takeIf { it.isNotEmpty() }
                                        ?.let { " — left behind: ${it.joinToString(", ")}" }.orEmpty()
                        },
                        "${event.entry.snapshotId ?: "N/A"}",
                        formatTimestampShort(event.entry.timestampMs),
                        if (event.currentAncestor) "yes" else "no",
                    )
                },
                leadCellColors = history.map { if (it.kind == SnapshotLogKind.RESET) verdictSkippedColor() else null },
            )
        }

        CountedSection("Metadata Log", node.data.metadataLog.size, "metadata log entries") {
            DetailTable {
                DetailRow("Timestamp", "Metadata File", isHeader = true)
                renderMetadataLogRows(node.data.metadataLog).forEach { row ->
                    DetailRow(row.getOrElse(0) { "N/A" }, row.getOrElse(1) { "N/A" })
                }
            }
        }

        // One row per *blob*, not per file. The list in metadata.json is a list of
        // files each holding a list of blobs, and neither level is the question a
        // reader has: that is "how many distinct values does this column have",
        // which is one row per blob with its field ids resolved to a name. The
        // column goes first because it is the answer, then the figure — the
        // WideTable ordering rule, since the panel is narrower than the table.
        // The Puffin footers are a DeferredRead on the node: opened on the first
        // render of this panel and never again, rather than at graph-build time
        // where it would be a file open per metadata version of the table.
        val footers = node.statisticsFooters.value.orEmpty()
        val statsRows = remember(node.data, footers) {
            statisticsRows(
                node.data,
                footerOf = { file -> footers[file.statisticsPath]?.footer },
                sketchOf = { file, fields -> footers[file.statisticsPath]?.sketches?.get(fields) },
            )
        }
        CountedSection("Statistics", node.data.statistics.size, "statistics files") {
            node.data.statistics.forEach { file ->
                val read = footers[file.statisticsPath]
                DetailTable {
                    DetailRow("File", fileNameFromPath(file.statisticsPath.orEmpty()))
                    DetailRow("Snapshot", file.snapshotId?.toString() ?: "N/A")
                    // Recorded beside the file's own, because a reader opens the file at the
                    // recorded length and reads the footer as its last recorded-footer-size bytes.
                    DetailRow("Size", recordedAgainstFile(file.fileSizeInBytes, read?.sizeOnDisk))
                    DetailRow("Footer Size", recordedAgainstFile(file.fileFooterSizeInBytes, read?.footerSizeOnDisk))
                    // Recorded beside read, the same idea as a manifest's tallies:
                    // metadata.json holds a copy of the blob list so a planner
                    // never opens the file, and that copy is what can go stale.
                    DetailRow(
                        "Blobs",
                        when {
                            read?.footer != null ->
                                "${file.blobMetadata.size} recorded, " +
                                    "${read.footer!!.blobs.size} in the file"
                            else -> "${file.blobMetadata.size} recorded, file not read"
                        },
                    )
                    read?.footer?.createdBy?.let { DetailRow("Written By", it) }
                    read?.problem?.let {
                        // The path is named because the recorded one is usually
                        // some other machine's, and a reader told a file is
                        // missing needs to know which one was looked at.
                        DetailRow("Could Not Open", "${read.localPath}: $it")
                    }
                }
            }
            WideTable(
                headers = listOf(
                    "Column", "Distinct Values", "In File", "Sketch", "Type",
                    "Size", "Codec", "Snapshot", "Sequence",
                ),
                rows = statsRows.map { row ->
                    listOf(
                        row.column,
                        row.ndv?.let { formatCount(it) } ?: "N/A",
                        // Three states, not two: a file never opened and a file
                        // opened without this blob would otherwise read the same,
                        // and the second means the table is pointing a planner at
                        // statistics that are not there.
                        when {
                            !row.fileRead -> "not read"
                            row.fileNdv == null -> "missing"
                            else -> formatCount(row.fileNdv!!)
                        },
                        // The sketch the figure was read off, and whether it is
                        // exact: the record keeps the number and not how it was
                        // made, and 20,158 on a column of 20,000 values is the
                        // ordinary look of an estimate. A sketch whose estimate is
                        // not the recorded figure is named with what it gives.
                        when {
                            row.sketch != null && row.sketchAgrees == false -> "${row.sketch!!.describe()} — gives ${formatCount(row.sketch!!.ndv)}, not the recorded figure"
                            row.sketch != null -> row.sketch!!.describe()
                            row.sketchProblem != null -> "not decoded: ${row.sketchProblem}"
                            !row.fileRead -> "not read"
                            else -> "—"
                        },
                        row.type,
                        row.compressedLength?.let { formatBytes(it) } ?: "N/A",
                        row.codec ?: "N/A",
                        row.snapshotId?.toString() ?: "N/A",
                        row.sequenceNumber?.toString() ?: "N/A",
                    )
                },
                // Sized to the longest value each column can hold rather than to
                // this fixture: the sketch type is a fixed vocabulary string and a
                // snapshot id is 19 digits, and at the first two widths tried both
                // wrapped on every row, which doubled the height of the whole table
                // to say nothing.
                columnWidths = listOf(
                    120.dp, 110.dp, 90.dp, 350.dp, 250.dp, 80.dp, 70.dp, 180.dp, 80.dp,
                ),
                // Only a row whose file disagreed is coloured. A column bolded on
                // every row has spent its emphasis before the exception arrives.
                leadCellColors = statsRows.map { row ->
                    if (row.agrees == false || row.sketchAgrees == false) colors.error else null
                },
            )
            if (statsRows.any { it.sketch?.exact == false }) {
                Text(
                    "A theta sketch keeps every hash while a column's distinct values fit its nominal " +
                        "entries (4,096 by default) and its count is then exact; past that it keeps the hashes " +
                        "below a threshold θ and the count is the retained hashes divided by θ — an estimate, " +
                        "which is what a planner reads as ndv. Nothing in the record says which kind a figure is.",
                    fontSize = TypeScale.small,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        CountedSection(
            "Partition Statistics",
            node.data.partitionStatistics.size,
            "partition statistics files",
        ) {
            // The record, with the file's size on disk beside the size it claims —
            // the same "record against the file" rule as the Puffin statistics
            // above, because a cleanup can remove the file and leave the record.
            val reads = node.partitionStatistics.value.orEmpty()
            WideTable(
                headers = listOf("File", "Snapshot", "Size", "On Disk", "Resolved"),
                rows = node.data.partitionStatistics.map { file ->
                    val read = reads[file.statisticsPath]
                    listOf(
                        fileNameFromPath(file.statisticsPath.orEmpty()),
                        file.snapshotId?.toString() ?: "N/A",
                        // Exact bytes on both sides: the comparison is the point.
                        file.fileSizeInBytes?.let { "${formatCount(it)} B" } ?: "N/A",
                        read?.sizeOnDisk?.let { "${formatCount(it)} B" } ?: "missing",
                        read?.let { pathResolutionLabel(it.resolution) } ?: "not read",
                    )
                },
                columnWidths = listOf(260.dp, 150.dp, 130.dp, 130.dp, 420.dp),
            )
            // Then what is inside: one row per partition, the figures a planner
            // reads instead of walking the manifests. Widest-read columns first,
            // the partition's identity leading, snapshot and spec at the end.
            node.data.partitionStatistics.forEach { file ->
                val read = reads[file.statisticsPath] ?: return@forEach
                Spacer(Modifier.height(8.dp))
                val rows = read.rows
                if (rows == null) {
                    Text(
                        "${fileNameFromPath(file.statisticsPath.orEmpty())}: not read — ${read.problem ?: "unknown problem"}",
                        fontSize = TypeScale.small,
                        color = colors.error,
                    )
                    return@forEach
                }
                // The file against the live files of the snapshot it names —
                // the same rule as manifestTallies. The snapshot node's walk is
                // deferred and shared with the totals it also serves; a snapshot
                // the graph no longer draws leaves the column saying so.
                val live = (currentGraph.nodeById["snap_${file.snapshotId}"] as? GraphNode.SnapshotNode)?.liveFiles
                val verdicts = live?.let { checkPartitionStatistics(rows, it) }
                val disagreeing = verdicts?.count { !it.agrees } ?: 0
                Text(
                    "${fileNameFromPath(file.statisticsPath.orEmpty())}: ${formatCount(rows.size.toLong())} partitions" +
                        (if (read.truncated) " shown of more — the first ${formatCount(model.MAX_PARTITION_STATS_ROWS.toLong())} rows of the file" else "") +
                        ". Record counts are before deletes; a delete file's records are the positions or keys it holds. " +
                        when {
                            verdicts == null -> "Not checked: snapshot ${file.snapshotId} is not drawn, so its live files were not walked."
                            disagreeing == 0 -> "Every row agrees with the live files of snapshot ${file.snapshotId}."
                            else -> "$disagreeing of ${verdicts.size} partitions DISAGREE with the live files of snapshot ${file.snapshotId} — the file is stale, or the walk is wrong."
                        },
                    fontSize = TypeScale.small,
                    color = if (disagreeing > 0) colors.error else colors.onSurfaceVariant,
                    fontWeight = if (disagreeing > 0) FontWeight.Bold else null,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                val listed = verdicts ?: rows.map { PartitionStatsVerdict(it.partition, it, null, emptyList()) }
                WideTable(
                    headers = listOf(
                        "Agrees", "Partition", "Data Records", "Data Files", "Data Size",
                        "Pos. Delete Records", "Pos. Delete Files", "Eq. Delete Records", "Eq. Delete Files",
                        "Total Records", "Last Updated", "Last Snapshot", "Spec",
                    ),
                    rows = listed.map { verdict ->
                        val row = verdict.recorded
                        listOf(
                            when {
                                verdicts == null -> "not checked"
                                row == null -> "NO — live partition the file omits"
                                verdict.counted == null -> "NO — no live file in it"
                                verdict.agrees -> "yes"
                                else -> "NO — " + verdict.disagreements.joinToString("; ")
                            },
                            verdict.partition.ifEmpty { "(unpartitioned)" },
                            row?.dataRecordCount?.let { formatCount(it) } ?: "N/A",
                            row?.dataFileCount?.let { formatCount(it.toLong()) } ?: "N/A",
                            row?.totalDataFileSizeInBytes?.let { formatBytes(it) } ?: "N/A",
                            row?.positionDeleteRecordCount?.let { formatCount(it) } ?: "N/A",
                            row?.positionDeleteFileCount?.let { formatCount(it.toLong()) } ?: "N/A",
                            row?.equalityDeleteRecordCount?.let { formatCount(it) } ?: "N/A",
                            row?.equalityDeleteFileCount?.let { formatCount(it.toLong()) } ?: "N/A",
                            row?.totalRecordCount?.let { formatCount(it) } ?: "not written",
                            formatTimestampShort(row?.lastUpdatedAtMs),
                            row?.lastUpdatedSnapshotId?.toString() ?: "N/A",
                            row?.specId?.toString() ?: "N/A",
                        )
                    },
                    columnWidths = listOf(
                        190.dp, 160.dp, 110.dp, 90.dp, 100.dp,
                        150.dp, 130.dp, 140.dp, 130.dp,
                        110.dp, 200.dp, 200.dp, 60.dp,
                    ),
                    leadCellColors = listed.map { if (verdicts != null && !it.agrees) colors.error else null },
                )
            }
        }

        Section("Raw metadata.json") {
            val rawJson = node.rawJson
            if (!rawJson.isNullOrBlank()) {
                val rawJsonScrollX = rememberScrollState()
                val rawJsonScrollY = rememberScrollState()
                // H-4: highlight once per (rawJson, theme), not on every recomposition.
                val highlightedJson = remember(rawJson, colors) { jsonToAnnotatedString(rawJson, colors) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp, max = 420.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                        .border(1.dp, colors.outlineVariant, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                        .background(colors.surface)
                        .horizontalScroll(rawJsonScrollX)
                        .verticalScroll(rawJsonScrollY)
                        .padding(8.dp)
                ) {
                    Text(
                        text = highlightedJson,
                        fontFamily = FontFamily.Monospace,
                        fontSize = TypeScale.small
                    )
                }
            } else {
                DetailTable {
                    DetailRow("Raw JSON", "N/A", isHeader = true)
                }
            }
        }
}

@Composable
internal fun ColumnScope.SnapshotPanel(
    node: GraphNode.SnapshotNode,
    currentGraph: GraphModel,
    children: List<GraphNode>,
    scanFilter: ScanFilter = ScanFilter.of(emptyList()),
) {
    val colors = MaterialTheme.colorScheme
        DetailTable {
            DetailRow("Property", "Value", isHeader = true)
            DetailRow("Snapshot ID", "${node.data.snapshotId}")
            if (node.expired) {
                DetailRow(
                    "Expired",
                    "yes — its manifest list is gone and the current metadata no longer " +
                        "lists it; this older metadata version still does. The summary " +
                        "below is what the writer recorded; nothing under it can be read.",
                )
            }
            // "None" is the root commit; a parent id with no snapshot behind it
            // means the parent has been expired away, and the two are different
            // facts about the table's history.
            val parentId = node.data.parentSnapshotId
            DetailRow(
                "Parent ID",
                when {
                    parentId == null -> "None — this is the table's first commit"
                    currentGraph.nodeById.containsKey("snap_$parentId") -> "$parentId"
                    else -> "$parentId (expired — no longer retained)"
                },
            )
            DetailRow(
                "Refs",
                node.refs.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.display }
                    ?: if (node.data.wapId != null) {
                        "None — staged by write-audit-publish (wap.id ${node.data.wapId}); on no branch until published"
                    } else {
                        "None — kept only by a metadata version, not by a branch or tag"
                    },
            )
            // A rollback writes no snapshot: the log entry that set main back is
            // the only record, and the commit it moved past stays listed with no
            // ref and no place in the current lineage.
            node.leftBehindAt?.let { reset ->
                DetailRow(
                    "Rolled Back",
                    "main was set back to snapshot ${reset.snapshotId} at ${formatTimestampShort(reset.timestampMs)}, " +
                        "leaving this commit behind — it is not an ancestor of the current snapshot, and the next commit forked from ${reset.snapshotId}",
                )
            }
            // Write-audit-publish, from the summary: a staged write names its
            // audit id; a published commit names the staged snapshot its files
            // came from, which its parent edge does not say.
            if (node.data.wapId != null) DetailRow("WAP ID", node.data.wapId!!)
            if (node.data.sourceSnapshotId != null || node.data.publishedWapId != null) {
                DetailRow(
                    "Published From",
                    listOfNotNull(
                        node.data.sourceSnapshotId?.let { "snapshot $it" },
                        node.data.publishedWapId?.let { "wap.id $it" },
                    ).joinToString(", ") + " — cherry-picked: the files are the staged snapshot's, the parent is main's tip",
                )
            }
            DetailRow(
                "Sequence Number",
                node.data.sequenceNumber?.toString()
                    ?: "0 — a v1 snapshot records none, and the format reads it as 0",
            )
            DetailRow("Schema ID", "${node.data.schemaId ?: "N/A"}")
            // The ids this commit took, from next-row-id as it stood. A commit
            // adding no data files records where it started and takes none.
            node.data.describeRowIds()?.let { DetailRow("Row IDs", it) }
            DetailRow("Timestamp", formatTimestamp(node.data.timestampMs))
            val manifestList = node.data.manifestList
            val manifestListLabel = if (manifestList == null) "N/A" else "${manifestList.substringAfterLast("/")} ($manifestList)"
            DetailRow("Manifest List", manifestListLabel)
            DetailRow("Resolved", pathResolutionLabel(node.pathResolution))
        }

        RecursiveDataTableSection(node = node, graphModel = currentGraph)

        node.change?.let { CommitSection(it) }

        TotalsSection(node)
        LiveRowsSection(node)
        SnapshotRowLookupSection(node.id, node.readInput, paimon = false, currentGraph, scanFilter)

        PartitionsSection(node)

        RewriteSection(node, currentGraph)
        PositionDeleteRewriteSection(node, currentGraph)
        ManifestMergeSection(node, currentGraph)
        ManifestRewriteSection(node, currentGraph)

        DeleteReachSection(node, children)

        if (node.data.summary.isNotEmpty()) {
            Section("Summary") {
                // Copied out of the snapshot as the writer left them. The section
                // above is where the ones describing this commit are checked
                // against the manifests it wrote; the rest — engine name, app id,
                // the running totals — have nothing here to check them against.
                Text(
                    "Written by whatever engine made the commit, and read back verbatim — " +
                        "nothing here recomputed them. \"What this commit did\" above checks " +
                        "the figures describing this commit against the manifests it wrote, " +
                        "and \"What this commit left\" the running totals against its closure.",
                    fontSize = TypeScale.small,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                DetailTable {
                    DetailRow("Key", "Value", isHeader = true)
                    node.data.summary.toSortedMap().forEach { (k, v) ->
                        DetailRow(k, v)
                    }
                }
            }
        }

        val manifestChildren = children
            .filterIsInstance<GraphNode.ManifestNode>()
            .sortedWith(
                compareBy(
                    { it.data.effectiveSequenceNumber },
                    { it.data.effectiveMinSequenceNumber },
                    { manifestContentRank(it.data.content) },
                    { it.data.manifestPath ?: "" }
                )
            )

        if (manifestChildren.isNotEmpty()) {
            Section("Manifest List Rows") {
                Text(
                    "One row per manifest this snapshot lists, in apply order. The six count " +
                        "columns are what the manifest list claims; \"Summary\" is that claim " +
                        "checked against the entries of the manifest itself, which is a check " +
                        "nothing on the read path performs.",
                    fontSize = TypeScale.small,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                WideTable(
                    headers = listOf(
                        "Apply Order",
                        "Summary",
                        "Manifest Path",
                        "Content",
                        "Manifest Length",
                        "Partition Spec ID",
                        "Sequence Number",
                        "Min Sequence Number",
                        "Added Snapshot ID",
                        "Added Files",
                        "Existing Files",
                        "Deleted Files",
                        "Added Rows",
                        "Existing Rows",
                        "Deleted Rows"
                    ),
                    rows = manifestChildren.mapIndexed { index, manifestNode ->
                        val manifest = manifestNode.data
                        val tallies = manifestTallies(manifest, manifestNode.entries.map { it.entry }, manifestNode.sizeOnDisk)
                        val checkable = tallies.filter { it.agrees != null }
                        listOf(
                            "${index + 1}",
                            when {
                                manifestNode.entries.isEmpty() -> "no entries read"
                                checkable.isEmpty() -> "nothing recorded"
                                checkable.all { it.agrees == true } -> "matches"
                                else -> "${checkable.count { it.agrees == false }} of " +
                                    "${checkable.size} DIFFER"
                            },
                            normalizeText(manifest.manifestPath),
                            if (manifest.content == 1) "Deletes (1)" else "Data (0)",
                            "${manifest.manifestLength ?: "N/A"}",
                            "${manifest.partitionSpecId ?: "N/A"}",
                            manifest.sequenceNumber?.toString() ?: "0 (v1)",
                            "${manifest.minSequenceNumber ?: "N/A"}",
                            "${manifest.addedSnapshotId ?: "N/A"}",
                            "${manifest.addedFilesCount ?: 0}",
                            "${manifest.existingFilesCount ?: 0}",
                            "${manifest.deletedFilesCount ?: 0}",
                            "${manifest.addedRowsCount ?: 0}",
                            "${manifest.existingRowsCount ?: 0}",
                            "${manifest.deletedRowsCount ?: 0}"
                        )
                    }
                )
            }
        }
}

@Composable
internal fun ColumnScope.ManifestPanel(
    node: GraphNode.ManifestNode,
    currentGraph: GraphModel,
    scanFilter: ScanFilter,
) {
    val colors = MaterialTheme.colorScheme
        DetailTable {
            val contentType = when (val c = node.data.content) {
                1 -> "Deletes ($c)"
                0 -> "Data ($c)"
                else -> "Unknown ($c)"
            }
            DetailRow("Property", "Value", isHeader = true)
            DetailRow("Content Type", contentType)
            // A v1 manifest list records neither; the format reads both as 0.
            DetailRow(
                "Sequence Num.",
                node.data.sequenceNumber?.toString() ?: "0 — a v1 manifest list records none, read as 0",
            )
            DetailRow(
                "Min Sequence Num.",
                node.data.minSequenceNumber?.toString() ?: "0 — a v1 manifest list records none, read as 0",
            )
            DetailRow("Partition Spec ID", "${node.data.partitionSpecId ?: "N/A"}")
            DetailRow("Added Snapshot", "${node.data.addedSnapshotId ?: "N/A"}")
            // The id the manifest's data files without one of their own count up
            // from, in entry order; a delete manifest is never assigned one.
            if (node.data.firstRowId != null) DetailRow("First Row ID", "${node.data.firstRowId}")
            DetailRow("Manifest Length", recordedAgainstFile(node.data.manifestLength, node.sizeOnDisk))
            val manifestPath = node.data.manifestPath
            val manifestPathLabel = if (manifestPath == null) "N/A" else "${manifestPath.substringAfterLast("/")} ($manifestPath)"
            DetailRow("Path", manifestPathLabel, copyable = true)
            DetailRow("Resolved", pathResolutionLabel(node.pathResolution))
        }

        RecursiveDataTableSection(node = node, graphModel = currentGraph)

        // Every entry, not just the ones the graph drew. The graph caps child
        // nodes per manifest so a manifest holding thousands of files stays
        // readable; the inspector is a table and has no such constraint.
        val manifestEntries = node.entries

        // What the reader's filter did to this one manifest, next to the bounds it
        // did it with. The table node says how many were skipped; this says why
        // this one was, which is the question asked from here.
        if (!scanFilter.isEmpty()) {
            val result = evaluatePruning(node.partitionSummaries, scanFilter)
            Section(
                if (result.isSkipped) "Scan Pruning — this manifest would be skipped"
                else "Scan Pruning — this manifest would be read"
            ) {
                val skippedColor = verdictSkippedColor()
                val unevaluatedColor = verdictUnevaluatedColor()
                WideTable(
                    headers = listOf("This term", "Condition", "Field", "Because"),
                    columnWidths = listOf(120.dp, 120.dp, 90.dp, 320.dp),
                    rows = result.outcomes.map { outcome ->
                        listOf(
                            // "cannot" on its own named no object: cannot what? The
                            // column says what each term did to this manifest, so
                            // every value has to be a complete answer to that.
                            when (outcome.effect) {
                                TermEffect.SKIPS -> "skips it"
                                TermEffect.KEEPS -> "cannot skip it"
                                TermEffect.NOT_EVALUATED -> "not evaluated"
                            },
                            outcome.predicate.toString(),
                            outcome.fieldName ?: "—",
                            outcome.reason,
                        )
                    },
                    leadCellColors = result.outcomes.map { outcome ->
                        when (outcome.effect) {
                            TermEffect.SKIPS -> skippedColor
                            TermEffect.KEEPS -> null
                            TermEffect.NOT_EVALUATED -> unevaluatedColor
                        }
                    },
                )
            }
        }

        // Outside the entries check on purpose: a manifest list claiming three
        // added files over a manifest that yielded no entries is the case most
        // worth seeing, and it is the case where there is nothing below to look at.
        Section("Recorded Summary") {
            Text(
                "The manifest list carries these counts so a scan can plan without opening this " +
                    "manifest, and nothing on the read path checks them. Each one sits beside the " +
                    "same figure counted from the entries; the min sequence number beside the lowest " +
                    "live entry's, since the next commit drops every delete file below the lowest " +
                    "such figure among the data manifests it keeps; and the manifest's length, which " +
                    "a reader opens the file at, beside the file's own.",
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            val tallies = manifestTallies(node.data, manifestEntries.map { it.entry }, node.sizeOnDisk)
            WideTable(
                // The verdict leads, as it does on the pruning tables: the reader is
                // here to find out whether anything disagrees, not to read six pairs
                // of numbers and compare them by eye.
                headers = listOf("Agrees", "Figure", "In the file", "Recorded"),
                columnWidths = listOf(110.dp, 190.dp, 130.dp, 130.dp),
                rows = tallies.map { tally ->
                    listOf(
                        when (tally.agrees) {
                            true -> "yes"
                            false -> "NO"
                            null -> "nothing to check"
                        },
                        tally.label,
                        formatCount(tally.counted),
                        tally.recorded?.let { formatCount(it) } ?: "not recorded",
                    )
                },
                // Agreement is the ordinary case and stays neutral — colouring every
                // row spends the attention this table needs for the one that differs.
                leadCellColors = tallies.map { tally ->
                    when (tally.agrees) {
                        true -> null
                        false -> colors.error
                        null -> verdictUnevaluatedColor()
                    }
                },
            )
        }

        ManifestLedgerSection(manifestEntries)

        if (manifestEntries.isNotEmpty()) {
            // Above the entries on purpose: this is what a planner reads to decide
            // whether to open the manifest at all, so it is the answer to "would
            // my query touch this file" — a question asked before any entry is.
            val summaries = node.partitionSummaries
            if (summaries.isNotEmpty()) {
                // The recorded bounds against the entries under them — the manifestTallies rule
                // for the figures a scan prunes on. One verdict per field, leading, coloured only
                // where a figure disagrees, since a column of "agrees" is read by its exception.
                val tallies = partitionSummaryTallies(summaries, manifestEntries.map { it.partition })
                val byField = tallies.groupBy { it.field }
                fun verdictOf(name: String): Pair<String, Boolean?> {
                    val own = byField[name].orEmpty()
                    val wrong = own.filter { it.agrees == false }
                    return when {
                        wrong.isNotEmpty() -> "DISAGREES: ${wrong.joinToString(", ") { it.figure.lowercase() }}" to false
                        own.any { it.agrees == true } -> "agrees" to true
                        else -> "not counted" to null
                    }
                }
                fun countedOf(name: String, figure: String) = byField[name]?.firstOrNull { it.figure == figure }?.counted ?: "not counted"
                val disagreeing = summaries.count { verdictOf(it.field.name ?: "N/A").second == false }
                Section("Partition Ranges (${formatCount(summaries.size)})" + if (disagreeing > 0) " — $disagreeing disagree" else "") {
                    Text(
                        "The bounds a scan intersects with a partition predicate to decide whether to " +
                            "open this manifest. One row per partition field, covering every entry in it " +
                            "whatever its status, with the same bound folded from the entries beside it.",
                        fontSize = TypeScale.small,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    WideTable(
                        headers = listOf(
                            "Verdict", "Field", "Lower", "Counted Lower", "Upper", "Counted Upper", "Holds", "Nulls", "Counted Nulls", "NaNs",
                            "Transform", "Result Type"
                        ),
                        columnWidths = listOf(
                            190.dp, 150.dp, 150.dp, 150.dp, 150.dp, 150.dp, 110.dp, 70.dp, 110.dp, 70.dp, 120.dp, 120.dp
                        ),
                        rows = summaries.map { summary ->
                            val name = summary.field.name ?: "N/A"
                            listOf(
                                verdictOf(name).first,
                                name,
                                summary.humanLower ?: "N/A",
                                countedOf(name, "Lower bound"),
                                summary.humanUpper ?: "N/A",
                                countedOf(name, "Upper bound"),
                                // A manifest whose bounds meet holds exactly one
                                // partition, which is what a well-clustered write
                                // produces and what makes pruning effective.
                                if (summary.isSingleValue) "one partition" else "a range",
                                if (summary.containsNull) "yes" else "no",
                                countedOf(name, "Contains null"),
                                summary.containsNan?.let { if (it) "yes" else "no" } ?: "not recorded",
                                summary.field.transformName.ifEmpty { "N/A" },
                                summary.type.typeName,
                            )
                        },
                        leadCellColors = summaries.map { summary ->
                            when (verdictOf(summary.field.name ?: "N/A").second) {
                                false -> colors.error
                                null -> verdictUnevaluatedColor()
                                else -> null
                            }
                        },
                    )
                }
            }

            Section("Manifest Entries (${formatCount(manifestEntries.size)})") {
                WideTable(
                    headers = listOf(
                        "Apply Order",
                        "Simple ID",
                        "Status",
                        "Content",
                        "Snapshot ID",
                        "Sequence Number",
                        "File Sequence Number",
                        "File Path",
                        "Format",
                        "Record Count",
                        "File Size Bytes",
                        "Partition",
                        "Column Sizes",
                        "Value Counts",
                        "Null Value Counts",
                        "NaN Value Counts",
                        "Lower Bounds",
                        "Upper Bounds",
                        "Key Metadata",
                        "Split Offsets",
                        "Equality IDs",
                        "Sort Order ID"
                    ),
                    // Sized to the content. At a uniform width the partition tuple
                    // and the bounds maps each wrapped to the line cap, and a row is
                    // as tall as its tallest cell — so every row of this table was
                    // eight lines high and two entries did not fit on a screen.
                    columnWidths = listOf(
                        90.dp, 80.dp, 110.dp, 100.dp, 170.dp, 120.dp, 150.dp,
                        320.dp, 90.dp, 110.dp, 120.dp, 420.dp,
                        160.dp, 160.dp, 170.dp, 170.dp, 220.dp, 220.dp,
                        160.dp, 130.dp, 110.dp, 110.dp,
                    ),
                    rows = manifestEntries.mapIndexed { index, view ->
                        val data = view.entry.dataFile ?: DataFile(filePath = "unknown")
                        val status = when (view.entry.status) {
                            ManifestEntryStatus.EXISTING -> "EXISTING (0)"
                            ManifestEntryStatus.ADDED -> "ADDED (1)"
                            ManifestEntryStatus.DELETED -> "DELETED (2)"
                            else -> "Unknown (${view.entry.status})"
                        }
                        val content = when (data.content ?: DataFileContent.DATA) {
                            DataFileContent.POSITION_DELETES -> "Position Delete (1)"
                            DataFileContent.EQUALITY_DELETES -> "Equality Delete (2)"
                            else -> "Data (0)"
                        }
                        listOf(
                            "${index + 1}",
                            "${view.simpleId}",
                            status,
                            content,
                            "${view.entry.snapshotId ?: "N/A"}",
                            "${effectiveSequenceNumber(view.entry, node.data.sequenceNumber)}",
                            "${view.entry.fileSequenceNumber ?: "N/A"}",
                            normalizeText(data.filePath),
                            data.fileFormat ?: "N/A",
                            formatCount(data.recordCount ?: 0L),
                            formatBytes(data.fileSizeInBytes ?: 0L),
                            partitionCell(view.partition),
                            kvLongs(data.columnSizes),
                            kvLongs(data.valueCounts),
                            kvLongs(data.nullValueCounts),
                            kvLongs(data.nanValueCounts),
                            kvBytes(data.lowerBounds),
                            kvBytes(data.upperBounds),
                            data.keyMetadata?.toHexShort() ?: "N/A",
                            longs(data.splitOffsets),
                            data.equalityIds?.joinToString(", ") ?: "N/A",
                            "${data.sortOrderId ?: "N/A"}"
                        )
                    }
                )
            }
        }
}

@Composable
internal fun ColumnScope.FilePanel(
    node: GraphNode.FileNode,
    currentGraph: GraphModel,
) {
    val colors = MaterialTheme.colorScheme
        DetailTable {
            val contentType = when (val c = node.data.content ?: 0) {
                1 -> "Position Delete ($c)"
                2 -> "Equality Delete ($c)"
                else -> "Data ($c)"
            }
            val status = when (val s = node.entry.status) {
                0 -> "EXISTING ($s)"
                1 -> "ADDED ($s)"
                2 -> "DELETED ($s)"
                else -> "Unknown ($s)"
            }
            DetailRow("Property", "Value", isHeader = true)
            DetailRow("Simple ID", "${node.simpleId}")
            DetailRow("Content Type", contentType)
            DetailRow("Status", status)
            DetailRow("Snapshot ID", "${node.entry.snapshotId ?: "N/A"}")
            // Inherited from the manifest when the entry records none, which is
            // the ordinary case — and said out loud, because a number the file did
            // not write is a different fact from one it did. A v1 manifest has no
            // number to inherit, and the format reads that as 0.
            DetailRow(
                "Sequence Num.",
                "${node.sequenceNumber}" + when {
                    node.sequenceDefaulted -> " (v1 manifest — none recorded, read as 0)"
                    node.sequenceInherited -> " (inherited from the manifest)"
                    else -> ""
                },
            )
            DetailRow("File Seq. Num.", "${node.entry.fileSequenceNumber ?: "N/A"}")
            // v3 row lineage, the same inheritance as the sequence number: an
            // entry the manifest's own commit wrote records nothing and counts
            // up from the manifest's first id in entry order; an entry carried
            // into a later manifest records its own. The range is what a reader
            // wants — which ids live in this file — unless the file carries a
            // `_row_id` column, which a rewrite does so its rows keep theirs, and
            // then the first id is only where rows without one would start.
            val firstRowId = node.firstRowId
            if (firstRowId != null) {
                val records = node.data.recordCount ?: 0L
                DetailRow(
                    "Row IDs",
                    (if (records > 0) "$firstRowId..${firstRowId + records - 1}" else "$firstRowId, no rows") +
                        (if (node.firstRowIdInherited) " (first id inherited from the manifest)" else " (first id recorded on the entry)"),
                )
            }
            DetailRow("File Format", "${node.data.fileFormat ?: "N/A"}")
            DetailRow("Record Count", "${node.data.recordCount ?: 0}")
            DetailRow("File Size", "${node.data.fileSizeInBytes ?: 0} bytes")
            // The id resolved to the order it names, the way WRITE ORDERED BY
            // states it, and the table's default beside it when the two differ:
            // a writer that sorts without recording the order writes 0, so the
            // file's claim and the table's are two facts, not one.
            val nameOf = { fieldId: Int -> node.schema?.nameOf(fieldId) }
            DetailRow(
                "Sort Order",
                node.data.sortOrderId?.let { id ->
                    node.sortOrder?.let { "$id — ${it.describe(nameOf)}" }
                        ?: "$id — not among the table's sort orders"
                } ?: "N/A",
            )
            val defaultOrder = node.defaultSortOrder
            if (defaultOrder != null && defaultOrder.orderId != node.data.sortOrderId?.toInt()) {
                DetailRow(
                    "Table Default Order",
                    "${defaultOrder.orderId} — ${defaultOrder.describe(nameOf)} — the table's, which this file does not claim",
                )
            }
            DetailRow("Split Offsets", longs(node.data.splitOffsets))
            DetailRow("Equality IDs", node.data.equalityIds?.joinToString(", ") ?: "N/A")
            val filePath = node.data.filePath
            DetailRow("Path", "${filePath ?: "N/A"}", copyable = true)
            DetailRow("Reading", node.localPath ?: "N/A", copyable = true)
            DetailRow("Resolved", dataFileResolutionLabel(node.pathResolution))
        }

        // The partition tuple, decoded against the spec this file's manifest was
        // written with. Value and Stored differ for the ordinal time transforms —
        // a `year` partition for 2024 holds 54 — so both are shown: one says what
        // the file contains, the other what it means.
        val partition = node.partition
        if (partition == null) {
            Section("Partition") {
                Text(
                    "This manifest carried no partition spec, so the partition tuple " +
                        "cannot be decoded. That is not the same as an unpartitioned table.",
                    fontSize = TypeScale.small,
                    color = colors.onSurfaceVariant,
                )
            }
        } else if (partition.isUnpartitioned) {
            Section("Partition") {
                Text(
                    "This table is not partitioned — its spec has no fields.",
                    fontSize = TypeScale.small,
                    color = colors.onSurfaceVariant,
                )
            }
        } else {
            Section("Partition (${formatCount(partition.values.size)})") {
                Text(
                    "Iceberg stores the transform's result, not the source value. " +
                        "Value is how Iceberg renders it; Stored is what is on disk.",
                    fontSize = TypeScale.small,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                WideTable(
                    headers = listOf(
                        "Field", "Value", "Stored", "Transform",
                        "Source Column", "Result Type", "Field ID", "Raw"
                    ),
                    columnWidths = listOf(
                        150.dp, 170.dp, 150.dp, 120.dp, 140.dp, 120.dp, 70.dp, 180.dp
                    ),
                    rows = partition.values.map { value ->
                        listOf(
                            value.field.name ?: "N/A",
                            value.human,
                            boundDisplay(value.stored),
                            value.field.transformName.ifEmpty { "N/A" },
                            value.field.sourceId?.let { sourceId ->
                                node.schema?.nameOf(sourceId) ?: "field $sourceId"
                            } ?: "N/A",
                            value.type.typeName,
                            "${value.field.fieldId ?: "N/A"}",
                            value.stored.raw.takeIf { it.isNotEmpty() }?.toHexShort() ?: "N/A",
                        )
                    }
                )
            }
            PartitionBoundsSection(partitionBoundsChecks(partition, node.columnStats))
        }

        FileHistorySection(node.history, currentGraph)

        // What this delete file applies to. Asked of every delete file, and the
        // honest answer is different for each of the three kinds — including one
        // where the answer is that the format does not record it. Leaving the
        // relationship blank would read as "nothing", which is a different claim.
        val deleteContent = node.data.content
        if (deleteContent == DataFileContent.POSITION_DELETES ||
            deleteContent == DataFileContent.EQUALITY_DELETES
        ) {
            Section("What this deletes from") {
                val referenced = node.data.referencedDataFile
                DetailTable {
                    DetailRow("Property", "Value", isHeader = true)
                    when {
                        deleteContent == DataFileContent.EQUALITY_DELETES -> {
                            DetailRow("Applies by", "Predicate over the equality field ids below")
                            DetailRow(
                                "Equality Field IDs",
                                node.data.equalityIds?.joinToString(", ") { id ->
                                    node.schema?.nameOf(id)?.let { "$id ($it)" } ?: "$id"
                                } ?: "N/A",
                            )
                            DetailRow(
                                "Target Files",
                                "Not recorded. An equality delete matches rows by value across " +
                                    "every data file in its scope, so the format stores no link " +
                                    "from it to any particular file.",
                            )
                        }
                        referenced != null -> {
                            DetailRow("Applies by", "Row position, as a v3 deletion vector")
                            DetailRow("Referenced Data File", referenced)
                            DetailRow(
                                "Vector Location",
                                "offset ${node.data.contentOffset ?: "N/A"}, " +
                                    "${node.data.contentSizeInBytes?.let { formatBytes(it) } ?: "N/A"} " +
                                    "inside the Puffin blob above",
                            )
                        }
                        else -> {
                            DetailRow("Applies by", "Row position")
                            // The exact targets are inside the file, but the range
                            // of them is not: the manifest records bounds on this
                            // file's own file_path column, and that is what a
                            // planner prunes with before opening anything.
                            val targets = deleteTargetsOf(node.data)
                            DetailRow(
                                "Target Files",
                                "Named row by row inside this file — its file_path column names " +
                                    "the data files and pos the rows, read them below. The " +
                                    "manifest records the range those names fall in, which is " +
                                    "what a scan prunes with without opening this file.",
                            )
                            DetailRow(
                                "Recorded Range",
                                when {
                                    targets.namesOneFile ->
                                        "${targets.onlyPath} — the bounds meet, so this file " +
                                            "targets exactly one data file"
                                    targets.low != null ->
                                        "${targets.low} … ${targets.high}"
                                    else ->
                                        "Not recorded. Without bounds on file_path a scan cannot " +
                                            "rule this delete file out of any data file."
                                },
                            )
                        }
                    }
                }
                if (deleteContent == DataFileContent.POSITION_DELETES && referenced == null) {
                    PositionalDeleteTargets(node)
                }
                if (deleteContent == DataFileContent.EQUALITY_DELETES) {
                    EqualityDeleteTargetsSection(node, currentGraph)
                }
            }
            DeletionVectorSection(node)
        }

        DeletesReachingSection(node, currentGraph)

        // The five per-column statistics maps, pivoted into one row per column and
        // decoded against the schema this file's manifest was written with. Stored
        // as parallel maps keyed by field id, they are unreadable in their raw form.
        val columnStats = node.columnStats
        if (columnStats.isNotEmpty()) {
            Section("Column Statistics (${formatCount(columnStats.size)})") {
                Text(
                    if (node.schema == null) {
                        "This manifest carried no schema, so bounds are shown as raw bytes. " +
                            "Decoding without a type would produce a plausible wrong value."
                    } else {
                        "A bound is a byte array in the manifest, keyed by field id, with no " +
                            "type beside it. It is read as the type that field id has in the " +
                            "schema this manifest carries under its own Avro `schema` key — the " +
                            "manifest's schema, not the table's current one, because a file " +
                            "written before a column was widened still describes itself by the " +
                            "type in force then. A manifest rewritten after the widening carries " +
                            "the file's bytes as written, so a four-byte bound under a long or a " +
                            "double is read at the width it was written and says so; a field the " +
                            "manifest's schema lacks was dropped before the manifest was written, " +
                            "and is named by the table schema that last had it. The field id and " +
                            "the bytes sit next to each decoded value so the reading can be " +
                            "checked rather than trusted."
                    },
                    fontSize = TypeScale.small,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                WideTable(
                    // Each decoded bound is followed by the bytes it was decoded from.
                    // The two were at opposite ends of the table, which put the value
                    // on screen and its evidence four columns past the panel edge.
                    headers = listOf(
                        "Column", "Field ID", "Type",
                        "Lower Bound", "Lower (raw)", "Upper Bound", "Upper (raw)",
                        "Values", "Nulls", "NaNs", "Column Size"
                    ),
                    columnWidths = listOf(
                        150.dp, 70.dp, 110.dp,
                        180.dp, 150.dp, 180.dp, 150.dp,
                        80.dp, 90.dp, 70.dp, 100.dp
                    ),
                    rows = columnStats.map { stat ->
                        listOf(
                            // A column the manifest's schema lacks is named by the
                            // table schema that last had it, and says so.
                            stat.displayName + if (stat.dropped) " (dropped)" else "",
                            "${stat.fieldId}",
                            stat.type?.typeName ?: "unknown",
                            boundDisplay(stat.lowerBound),
                            stat.lowerBound?.raw?.toHexShort() ?: "N/A",
                            boundDisplay(stat.upperBound),
                            stat.upperBound?.raw?.toHexShort() ?: "N/A",
                            stat.valueCount?.let { formatCount(it) } ?: "N/A",
                            stat.nullValueCount?.let { formatCount(it) }
                                ?.plus(if (stat.isAllNull) " (all)" else "") ?: "N/A",
                            stat.nanValueCount?.let { formatCount(it) } ?: "N/A",
                            stat.columnSizeBytes?.let { formatBytes(it) } ?: "N/A",
                        )
                    }
                )
            }
            // The name mapping places a registered file's columns, which record no field ids.
            val nameMapping = currentGraph.newestIcebergMetadata()?.nameMapping()
            StatsCheckSection(node.id, node.localPath, node.recordedColumnStats(), node.data.recordCount, nameMapping = nameMapping, recordedSize = node.data.fileSizeInBytes, recordedSplitOffsets = node.data.splitOffsets)
        }
        // Why a column has the statistics it has — drawn whether or not any were recorded, since
        // a file with none is the one the question is asked of.
        MetricsModesSection(node)

        RecursiveDataTableSection(node = node, graphModel = currentGraph)
}


/** A recorded length beside the file's own: `1,234 B recorded, the same in the file`, or the two figures where they differ, or which side is missing. */
internal fun recordedAgainstFile(recorded: Long?, onDisk: Long?): String = when {
    recorded == null && onDisk == null -> "N/A"
    onDisk == null -> "${formatCount(recorded)} B recorded, file not read"
    recorded == null -> "none recorded, ${formatCount(onDisk)} B in the file"
    recorded == onDisk -> "${formatCount(recorded)} B recorded, the same in the file"
    else -> "${formatCount(recorded)} B recorded, but the file is ${formatCount(onDisk)} B"
}
