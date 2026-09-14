package ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import model.IntegrityFinding
import model.StatisticsFileCheck
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
 * disagrees in the same table. A table past the cap is read a page at a time, each click
 * reading the next [pageSize] and folding them into what was read ([FileStatsSweep.plus]), so
 * the whole table is reachable and no single click opens more than a page. [readFilesRequested]
 * is the first click for a capture, and [onSettled] then waits for that page rather than the
 * report; [pageSize] is the cap, a parameter so a capture can show the paging on a small table.
 */
@Composable
internal fun IntegritySection(
    node: GraphNode.TableNode,
    startRequested: Boolean = false,
    readFilesRequested: Boolean = false,
    pageSize: Int = MAX_FILE_STATS_CHECKS,
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
    // Pages asked for so far; each one is read on top of the last, so the pages read stay on
    // screen while the next is opened rather than being cleared for it.
    var pagesRequested by remember(node.id) { mutableStateOf(if (readFilesRequested) 1 else 0) }
    var sweep by remember(node.id) { mutableStateOf<Result<FileReads>?>(null) }
    var readingPage by remember(node.id) { mutableStateOf(false) }
    LaunchedEffect(node.id, pagesRequested) {
        if (pagesRequested == 0) return@LaunchedEffect
        readingPage = true
        val soFar = sweep?.getOrNull()
        sweep = withContext(Dispatchers.IO) {
            runCatching {
                val targets = requireNotNull(node.fileStats.value) { "no files to read" }
                val page = sweepFileStats(targets, max = pageSize, from = soFar?.sweep?.filesRead ?: 0) {
                    StatsCheckReader.check(it.localPath, it.recorded, it.recordedRows, it.nameMapping)
                }
                FileReads(soFar?.sweep?.plus(page) ?: page, node.statisticsFiles.value.orEmpty())
            }
        }
        readingPage = false
        if (readFilesRequested && pagesRequested == 1) onSettled()
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
            "and the data files are file reads, behind the second click below."
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
                if (node.fileStats.isPresent) {
                    FileStatsStage(pagesRequested > 0, readingPage, sweep, pageSize, onRequest = { pagesRequested++ })
                }
            }
        }
    }
}

/** What the second click reads: the data files' sweep and, on Iceberg, the statistics files against their records. */
internal data class FileReads(val sweep: FileStatsSweep, val statistics: List<StatisticsFileCheck>) {
    val findings: List<IntegrityFinding> get() = sweep.findings + statistics.flatMap { it.findings }
    /** Files that could not be read, the data files first, each with the reason. */
    val unreadable: List<Pair<String, String>> get() = sweep.unreadable + statistics.filter { !it.read }.map { it.name to (it.problem ?: "not read") }

    /** `2 of 2 statistics files read, every one of their 5 figures agrees` — null where the metadata names none. */
    val describeStatistics: String? get() {
        if (statistics.isEmpty()) return null
        val read = statistics.filter { it.read }
        val figures = read.sumOf { it.figures }
        val findings = statistics.sumOf { it.findings.size }
        val opened = "${read.size} of ${formatCounted(statistics.size, "statistics file")} read"
        return when {
            read.isEmpty() -> opened
            findings == 0 -> "$opened, every one of their ${formatCounted(figures, "figure")} agrees"
            else -> "$opened, $findings of their ${formatCounted(figures, "figure")} disagree"
        }
    }
}

/**
 * The second click: the current snapshot's data files read, their recorded bounds and counts
 * against their rows — and the statistics files against their records. [onRequest] asks for
 * the first page and for each next one; the pages read so far stay drawn while [reading].
 */
@Composable
private fun FileStatsStage(requested: Boolean, reading: Boolean, outcome: Result<FileReads>?, pageSize: Int, onRequest: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val reads = outcome?.getOrNull()
    when {
        !requested -> {
            Text(
                "None of that opens a data file. Reading them puts each file's recorded bounds and counts — " +
                    "what a scan prunes on — against its rows, the file panel's own check run over the current " +
                    "snapshot's live files, $pageSize of them at a click; and opens each statistics " +
                    "file the metadata names against the record it keeps of it.",
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            )
            OutlinedButton(onClick = onRequest) {
                Text("Also read the data files (up to $pageSize)")
            }
        }
        outcome == null -> Text("Reading the files…", fontSize = TypeScale.small, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        reads == null -> Text(
            "Could not read the files: ${outcome.exceptionOrNull()?.message ?: "unknown error"}",
            fontSize = TypeScale.small,
            color = colors.error,
            modifier = Modifier.padding(top = 12.dp),
        )
        else -> {
            val sweep = reads.sweep
            Text(
                "Data files: " + sweep.describe +
                    (if (sweep.unreadable.isNotEmpty()) "; ${formatCounted(sweep.unreadable.size, "file")} could not be read" else "") + ".",
                fontSize = TypeScale.small,
                fontWeight = FontWeight.Bold,
                color = if (sweep.findings.isEmpty() && sweep.unreadable.isEmpty()) colors.onSurface else colors.error,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            reads.describeStatistics?.let { line ->
                val clean = reads.statistics.all { it.read && it.findings.isEmpty() }
                Text(
                    "Statistics files: $line.",
                    fontSize = TypeScale.small,
                    fontWeight = FontWeight.Bold,
                    color = if (clean) colors.onSurface else colors.error,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            val findings = reads.findings
            if (findings.isNotEmpty()) {
                val shown = findings.take(MAX_INTEGRITY_ROWS)
                WideTable(
                    headers = listOf("Figure", "Recorded", "Counted", "Where", "Check"),
                    columnWidths = listOf(190.dp, 120.dp, 120.dp, 320.dp, 150.dp),
                    rows = shown.map { listOf(it.figure, it.recorded, it.counted, it.where, it.check.label) },
                    leadCellColors = shown.map { colors.error },
                )
                if (findings.size > shown.size) {
                    Text("…and ${formatCount(findings.size - shown.size)} more.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
                }
            }
            val unreadable = reads.unreadable
            for ((name, why) in unreadable.take(MAX_UNREADABLE_ROWS)) {
                Text("Could not read $name: $why", fontSize = TypeScale.small, color = colors.error)
            }
            if (unreadable.size > MAX_UNREADABLE_ROWS) {
                Text("…and ${formatCount(unreadable.size - MAX_UNREADABLE_ROWS)} more could not be read.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            }
            when {
                reading -> Text("Reading the next files…", fontSize = TypeScale.small, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                sweep.filesLeft > 0 -> OutlinedButton(onClick = onRequest, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Read the next ${minOf(pageSize, sweep.filesLeft)} (${formatCounted(sweep.filesLeft, "file")} left)")
                }
            }
        }
    }
}

/** Unreadable files named before the rest are counted. */
private const val MAX_UNREADABLE_ROWS = 20
