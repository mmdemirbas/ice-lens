package ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import model.CommitTally
import model.DeltaCheckpointCheck
import model.DeltaFileAction
import model.GraphNode
import model.deltaPartitionText
import model.formatBytes
import model.formatBytesExact
import model.formatCount
import model.formatCounted

// The inspector panels for Delta's node kinds: a version, a file action, a checkpoint.

@Composable
internal fun ColumnScope.DeltaVersionPanel(node: GraphNode.DeltaVersionNode) {
    val colors = MaterialTheme.colorScheme
    val info = node.commit?.commitInfo
    DetailTable {
        DetailRow("Property", "Value", isHeader = true)
        DetailRow("Version", "${node.version}")
        DetailRow("Operation", node.operation ?: "N/A")
        DetailRow("Timestamp", formatTimestamp(node.commitTimeMs))
        // The commit file's time is what history and time travel read, unless the table writes
        // in-commit timestamps; the commitInfo figure is the writer's clock and usually agrees.
        info?.inCommitTimestamp?.let { DetailRow("Timestamp Source", "inCommitTimestamp, written by the commit") }
            ?: DetailRow("Timestamp Source", "the commit file's modification time — what DESCRIBE HISTORY and time travel read")
        info?.readVersion?.let { DetailRow("Read Version", "$it — the version the writer read before committing") }
        info?.isolationLevel?.let { DetailRow("Isolation Level", it) }
        info?.isBlindAppend?.let { DetailRow("Blind Append", if (it) "yes — read nothing it wrote over" else "no") }
        info?.engineInfo?.let { DetailRow("Engine", it) }
        info?.txnId?.let { DetailRow("Transaction ID", it, copyable = true) }
        info?.userMetadata?.let { DetailRow("User Metadata", it) }
        DetailRow("Commit File", node.localPath ?: "N/A", copyable = true)
        node.unavailable?.let { DetailRow("Not Reconstructable", it) }
    }
    if (node.commit == null && node.unavailable == null) {
        Text(
            "The commit file for this version is gone — cleaned up after a checkpoint made it unnecessary. " +
                "The checkpoint below is the version's state.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    if (node.tallies.isNotEmpty()) {
        val disagree = node.tallies.count { it.agrees == false }
        Section("Commit Metrics" + if (disagree == 0) " — agree" else " — ${formatCounted(disagree, "disagreement")}") {
            Text(
                "What operationMetrics records against what the commit's own actions count. A file removed and " +
                    "added back under another deletion vector is neither a removed nor an added file.",
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            TallyTable(node.tallies)
        }
    }

    val commit = node.commit
    if (commit != null) {
        info?.operationParameters?.takeIf { it.isNotEmpty() }?.let { params ->
            CountedSection("Operation Parameters", params.size, "parameters") {
                WideTable(
                    headers = listOf("Parameter", "Value"),
                    rows = params.entries.map { (k, v) -> listOf(k, v.toString().trim('"')) },
                    columnWidths = listOf(160.dp, 320.dp),
                )
            }
        }
        CountedSection("File Actions", commit.adds.size + commit.removes.size + commit.cdcs.size, "file actions — the commit changed no file") {
            WideTable(
                headers = listOf("Action", "Rows", "Size", "Deletion Vector", "Path"),
                rows = commit.adds.map { a ->
                    listOf("add", a.parsedStats?.numRecords?.let(::formatCount) ?: "—", formatBytes(a.size), a.deletionVector?.let { "${formatCount(it.cardinality)} rows" } ?: "—", a.path)
                } + commit.removes.map { r ->
                    listOf("remove", r.parsedStats?.numRecords?.let(::formatCount) ?: "—", formatBytes(r.size), r.deletionVector?.let { "${formatCount(it.cardinality)} rows" } ?: "—", r.path)
                } + commit.cdcs.map { c -> listOf("change data", "—", formatBytes(c.size), "—", c.path) },
                columnWidths = listOf(90.dp, 70.dp, 80.dp, 110.dp, 420.dp),
                leadCellColors = commit.adds.map { null } + commit.removes.map { colors.error } + commit.cdcs.map { null },
            )
        }
        val changes = listOfNotNull(
            commit.protocol?.let { "protocol: reader ${it.minReaderVersion}, writer ${it.minWriterVersion}" + (it.features.takeIf { f -> f.isNotEmpty() }?.let { f -> " — ${f.joinToString(", ")}" } ?: "") },
            commit.metadata?.let { "metadata: partitioned by ${it.partitionColumns.joinToString(", ").ifEmpty { "nothing" }}, ${formatCounted(it.configuration.size, "property", "properties")}" },
        ) + commit.actions.mapNotNull { a -> a.txn?.let { "txn: ${it.appId} at ${it.version}" } } +
            commit.actions.mapNotNull { a -> a.domainMetadata?.let { "domain ${it.domain}" + if (it.removed) " removed" else "" } }
        if (changes.isNotEmpty()) {
            Section("Table Changes") {
                DetailTable { changes.forEach { DetailRow(it.substringBefore(':'), it.substringAfter(": ")) } }
            }
        }
    }

    val state = node.state
    if (state != null) {
        Section("State At This Version") {
            DetailTable {
                DetailRow("Property", "Value", isHeader = true)
                DetailRow("Live Files", formatCount(state.files.size))
                DetailRow("Records Written", state.recordCount?.let(::formatCount) ?: "not every file records numRecords")
                if (state.deletedByVectors > 0) {
                    DetailRow("Marked By Vectors", "${formatCount(state.deletedByVectors)} — the rows a read skips; the figure above still counts them")
                    state.recordCount?.let { DetailRow("Rows A Read Returns", formatCount(it - state.deletedByVectors)) }
                }
                DetailRow("Size", formatBytesExact(state.sizeBytes))
                DetailRow("Tombstones", "${formatCount(state.tombstones.size)} — removed files VACUUM may delete once past the retention")
                DetailRow(
                    "Replayed From",
                    (state.fromCheckpoint?.let { "checkpoint $it" } ?: "version 0") +
                        if (state.commitsReplayed.isEmpty()) "" else ", then ${formatCounted(state.commitsReplayed.size, "commit")}",
                )
            }
        }
    }
}

@Composable
internal fun ColumnScope.DeltaFilePanel(node: GraphNode.DeltaFileNode) {
    val stats = node.stats
    DetailTable {
        DetailRow("Property", "Value", isHeader = true)
        DetailRow("Action", node.action.label + " — written by version ${node.version}")
        DetailRow("Path", node.path, copyable = true)
        DetailRow("Local Path", node.localPath ?: "N/A", copyable = true)
        DetailRow("Partition", deltaPartitionText(node.partitionColumns, node.partitionValues) ?: "unpartitioned")
        DetailRow("Size", node.size?.let(::formatBytesExact) ?: "not recorded")
        if (node.action != DeltaFileAction.CDC) {
            DetailRow("Records", stats?.numRecords?.let(::formatCount) ?: "no statistics recorded")
        }
        if (node.action == DeltaFileAction.ADD) {
            DetailRow(
                "Live Now",
                when {
                    node.liveNow -> "yes — the latest version holds this file with this vector"
                    node.pathLiveNow -> "no — a later commit re-added the file with another deletion vector, and it is live under that one"
                    else -> "no — a later commit removed it"
                },
            )
            node.add?.dataChange?.let { DetailRow("Data Change", if (it) "yes" else "no — a rearrangement such as OPTIMIZE; a streaming reader skips it") }
            node.add?.baseRowId?.let { DetailRow("Base Row ID", "$it") }
            node.add?.defaultRowCommitVersion?.let { DetailRow("Default Row Commit Version", "$it") }
            node.add?.clusteringProvider?.let { DetailRow("Clustering", it) }
        }
        node.remove?.let { r ->
            DetailRow("Removed At", formatTimestamp(r.deletionTimestamp))
            r.dataChange?.let { DetailRow("Data Change", if (it) "yes" else "no — a rearrangement such as OPTIMIZE") }
        }
        stats?.tightBounds?.let { DetailRow("Tight Bounds", if (it) "yes" else "no — a deletion vector may have removed the rows the bounds were taken from; still sound for skipping") }
    }
    if (stats != null && (stats.minValues != null || stats.nullCount != null)) {
        val columns = (stats.minValues?.keys.orEmpty() + stats.maxValues?.keys.orEmpty() + stats.nullCount?.keys.orEmpty()).distinct()
        CountedSection("Column Statistics", columns.size, "columns with statistics") {
            WideTable(
                headers = listOf("Column", "Min", "Max", "Nulls"),
                rows = columns.map { c ->
                    listOf(c, stats.minValues?.get(c)?.toString()?.trim('"') ?: "—", stats.maxValues?.get(c)?.toString()?.trim('"') ?: "—", stats.nullCount?.get(c)?.toString() ?: "—")
                },
                columnWidths = listOf(140.dp, 160.dp, 160.dp, 70.dp),
            )
        }
    }
    DeltaDeletionVectorSection(node)
}

@Composable
internal fun ColumnScope.DeltaCheckpointPanel(node: GraphNode.DeltaCheckpointNode) {
    val colors = MaterialTheme.colorScheme
    val checkpoint = node.checkpoint
    DetailTable {
        DetailRow("Property", "Value", isHeader = true)
        DetailRow("Version", "${checkpoint.version}")
        DetailRow(
            "Naming",
            when (checkpoint.naming) {
                service.DeltaCheckpointNaming.CLASSIC -> "classic — one Parquet file"
                service.DeltaCheckpointNaming.MULTI_PART -> "multi-part — ${checkpoint.expectedParts} Parquet files"
                service.DeltaCheckpointNaming.UUID -> "V2, UUID-named — may name sidecar files holding the file actions"
            },
        )
        DetailRow("Parts", if (checkpoint.complete) "${checkpoint.parts.size}" else "${checkpoint.parts.size} of ${checkpoint.expectedParts} — INCOMPLETE, a reader skips it")
        checkpoint.parts.forEach { DetailRow("File", it.toString(), copyable = true) }
    }

    val check by produceState<DeltaCheckpointCheck?>(null, node.id) {
        value = withContext(Dispatchers.IO) { node.check.value }
    }
    val result = check
    Section("Against The Replay" + when {
        result == null -> ""
        result.readError != null -> " — not read"
        !result.fromCommits -> " — not compared"
        result.agrees -> " — agrees"
        else -> " — DISAGREES"
    }) {
        when {
            result == null -> Text("Reading the checkpoint…", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            result.readError != null -> Text("The checkpoint could not be read: ${result.readError}", fontSize = TypeScale.small, color = colors.error)
            else -> {
                Text(
                    if (result.fromCommits) "The checkpoint's state against the replay of commits 0 through ${result.version} — two readings of one state; " +
                        "a reader that starts here reads what the checkpoint holds."
                    else "The commits before this checkpoint are gone, so there is no replay to compare it with — the normal state of a log past its retention.",
                    fontSize = TypeScale.small,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                DetailTable {
                    DetailRow("Property", "Value", isHeader = true)
                    DetailRow("Live Files", formatCount(result.checkpointFileCount))
                    DetailRow("Tombstones", formatCount(result.checkpointTombstoneCount))
                    if (result.fromCommits) {
                        DetailRow("Only In The Checkpoint", if (result.onlyInCheckpoint.isEmpty()) "none" else result.onlyInCheckpoint.joinToString("; "))
                        DetailRow("Missing From The Checkpoint", if (result.onlyInReplay.isEmpty()) "none" else result.onlyInReplay.joinToString("; "))
                        DetailRow("Tombstones Dropped", "${result.tombstonesDropped} — allowed past delta.deletedFileRetentionDuration")
                        DetailRow("Protocol", if (result.protocolAgrees) "agrees" else "DISAGREES")
                        DetailRow("Metadata", if (result.metadataAgrees) "agrees" else "DISAGREES")
                    }
                }
            }
        }
    }
}

/** Recorded against counted, one row per figure, the disagreements coloured. */
@Composable
private fun TallyTable(tallies: List<CommitTally>) {
    val colors = MaterialTheme.colorScheme
    WideTable(
        headers = listOf("Verdict", "Figure", "Recorded", "Counted"),
        rows = tallies.map { listOf(if (it.agrees == false) "DIFFERS" else "agrees", it.label, it.recorded?.let(::formatCount) ?: "—", formatCount(it.counted)) },
        columnWidths = listOf(80.dp, 200.dp, 90.dp, 90.dp),
        leadCellColors = tallies.map { if (it.agrees == false) colors.error else null },
    )
}
