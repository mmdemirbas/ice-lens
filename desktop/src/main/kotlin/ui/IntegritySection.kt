package ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import model.FileStatsSweep
import model.GraphNode
import model.IntegrityReport
import model.MAX_CLOSURE_CHECKS
import model.MAX_FILE_STATS_CHECKS
import model.sweepFileStats
import service.StatsCheckReader

/** Findings the table lists before it says how many more there are. */
internal const val MAX_INTEGRITY_ROWS = 500

/**
 * Every recorded figure against the same figure counted, across the whole table — see
 * [IntegrityReport]. It is the checks the manifest, snapshot and Paimon panels each run on their
 * own node, run everywhere at once, so "is this table consistent" is one click rather than one
 * click per node. Behind that click, like [UnreferencedFilesSection] and for the same reason:
 * two of the checks walk a closure per snapshot, and [startRequested] / [onSettled] let a capture
 * wait for the run the same way. The report is read through the node's `DeferredRead`, so a
 * second look does not run it again.
 *
 * The data files are a second click under the report: the metadata check opens none, and
 * [sweepFileStats] reads up to [MAX_FILE_STATS_CHECKS] of the current snapshot's live files
 * through [StatsCheckReader] — the file panel's own check, run over the table — and lists what
 * disagrees in the same table. [readFilesRequested] is that click for a capture, and
 * [onSettled] then waits for the sweep rather than the report.
 */
@Composable
internal fun IntegritySection(
    node: GraphNode.TableNode,
    startRequested: Boolean = false,
    readFilesRequested: Boolean = false,
    onSettled: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    if (!node.integrity.isPresent) return

    var requested by remember(node.id) { mutableStateOf(startRequested) }
    val outcome by produceState<Result<IntegrityReport>?>(null, node.id, requested) {
        value = null
        if (requested) {
            value = withContext(Dispatchers.IO) {
                runCatching { requireNotNull(node.integrity.value) { "no report" } }
            }
            if (!readFilesRequested) onSettled()
        }
    }
    var filesRequested by remember(node.id) { mutableStateOf(readFilesRequested) }
    val sweep by produceState<Result<FileStatsSweep>?>(null, node.id, filesRequested) {
        value = null
        if (filesRequested) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val targets = requireNotNull(node.fileStats.value) { "no files to read" }
                    sweepFileStats(targets) { StatsCheckReader.check(it.localPath, it.recorded, it.recordedRows, it.nameMapping) }
                }
            }
            if (readFilesRequested) onSettled()
        }
    }
    val report = outcome?.getOrNull()
    val disagreements = (report?.findings?.size ?: 0) + (sweep?.getOrNull()?.findings?.size ?: 0)
    val title = "Integrity" + when {
        report == null -> ""
        disagreements == 0 -> " — agrees"
        else -> " — ${formatCounted(disagreements, "disagreement")}"
    }

    Section(title) {
        val scope = "Manifest counts on every manifest and each commit's summary against the manifests it " +
            "wrote fold every entry once; the snapshot totals (Iceberg) and record counts (Paimon) walk a " +
            "closure per snapshot and stop after $MAX_CLOSURE_CHECKS, newest first. The statistics files " +
            "are checked on their own panels, since each is a file read."
        when {
            !requested -> {
                Text(
                    "Every figure the metadata records against the same figure counted from the entries, " +
                        "over the whole table — what each panel checks for its own node, run everywhere at once.",
                    fontSize = TypeScale.small,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedButton(onClick = { requested = true }) {
                    Text("Check the whole table")
                }
            }
            outcome == null -> Text("Checking…", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            report == null -> Text(
                "Could not check: ${outcome?.exceptionOrNull()?.message ?: "unknown error"}",
                fontSize = TypeScale.small,
                color = colors.error,
            )
            else -> {
                Text(
                    report.describe.replaceFirstChar { it.uppercase() } +
                        " — closures walked on ${report.closuresChecked} of ${formatCounted(report.snapshotCount, "snapshot")}" +
                        (if (report.readErrors > 0) "; ${formatCounted(report.readErrors, "artifact")} could not be read and drew a read error" else "") + ".",
                    fontSize = TypeScale.small,
                    fontWeight = FontWeight.Bold,
                    color = if (report.findings.isEmpty()) colors.onSurface else colors.error,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                if (report.findings.isNotEmpty()) {
                    val shown = report.findings.take(MAX_INTEGRITY_ROWS)
                    WideTable(
                        headers = listOf("Figure", "Recorded", "Counted", "Where", "Check"),
                        columnWidths = listOf(190.dp, 120.dp, 120.dp, 320.dp, 150.dp),
                        rows = shown.map { listOf(it.figure, it.recorded, it.counted, it.where, it.check.label) },
                        leadCellColors = shown.map { colors.error },
                    )
                    if (report.findings.size > shown.size) {
                        Text(
                            "…and ${formatCount(report.findings.size - shown.size)} more.",
                            fontSize = TypeScale.small,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                Text(scope, fontSize = TypeScale.small, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                if (node.fileStats.isPresent) FileStatsStage(filesRequested, sweep, onRequest = { filesRequested = true })
            }
        }
    }
}

/** The second click: the current snapshot's data files read, their recorded bounds and counts against their rows. */
@Composable
private fun FileStatsStage(requested: Boolean, outcome: Result<FileStatsSweep>?, onRequest: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val sweep = outcome?.getOrNull()
    when {
        !requested -> {
            Text(
                "None of that opens a data file. Reading them puts each file's recorded bounds and counts — " +
                    "what a scan prunes on — against its rows, the file panel's own check run over the current " +
                    "snapshot's live files, at most $MAX_FILE_STATS_CHECKS of them.",
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            )
            OutlinedButton(onClick = onRequest) {
                Text("Also read the data files (up to $MAX_FILE_STATS_CHECKS)")
            }
        }
        outcome == null -> Text("Reading the files…", fontSize = TypeScale.small, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        sweep == null -> Text(
            "Could not read the files: ${outcome.exceptionOrNull()?.message ?: "unknown error"}",
            fontSize = TypeScale.small,
            color = colors.error,
            modifier = Modifier.padding(top = 12.dp),
        )
        else -> {
            Text(
                "Data files: " + sweep.describe +
                    (if (sweep.unreadable.isNotEmpty()) "; ${formatCounted(sweep.unreadable.size, "file")} could not be read" else "") + ".",
                fontSize = TypeScale.small,
                fontWeight = FontWeight.Bold,
                color = if (sweep.findings.isEmpty() && sweep.unreadable.isEmpty()) colors.onSurface else colors.error,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            if (sweep.findings.isNotEmpty()) {
                val shown = sweep.findings.take(MAX_INTEGRITY_ROWS)
                WideTable(
                    headers = listOf("Figure", "Recorded", "Counted", "Where", "Check"),
                    columnWidths = listOf(190.dp, 120.dp, 120.dp, 320.dp, 150.dp),
                    rows = shown.map { listOf(it.figure, it.recorded, it.counted, it.where, it.check.label) },
                    leadCellColors = shown.map { colors.error },
                )
                if (sweep.findings.size > shown.size) {
                    Text("…and ${formatCount(sweep.findings.size - shown.size)} more.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
                }
            }
            for ((name, why) in sweep.unreadable.take(MAX_UNREADABLE_ROWS)) {
                Text("Could not read $name: $why", fontSize = TypeScale.small, color = colors.error)
            }
            if (sweep.unreadable.size > MAX_UNREADABLE_ROWS) {
                Text("…and ${formatCount(sweep.unreadable.size - MAX_UNREADABLE_ROWS)} more could not be read.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** Unreadable files named before the rest are counted. */
private const val MAX_UNREADABLE_ROWS = 20
