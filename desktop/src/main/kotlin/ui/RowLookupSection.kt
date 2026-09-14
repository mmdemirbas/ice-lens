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
import model.FileFate
import model.GraphModel
import model.DeferredRead
import model.GraphNode
import model.LookupInput
import model.PaimonReadInput
import model.RowChange
import model.RowLookupInput
import model.RowFate
import model.RowHistory
import model.PaimonChangelog
import model.ChangelogRecord
import model.PaimonRowKind
import service.PaimonChangelogTrace
import model.RowLookupResult
import model.ScanFilter
import model.evaluateScan
import model.isEmpty
import model.normalizeFilePath
import model.render
import service.PaimonRowLookup
import service.RowHistoryTrace
import service.RowLookup

/** Cells a hit's row prints before it stops. */
private const val MAX_ROW_CELLS = 8

/**
 * The rows the scan filter matches, read from the files it leaves, with each row's fate — see
 * [RowLookup]. It sits under the pruning section because it is the same filter one step
 * further: pruning says which files a scan opens, this opens them and says what is in them and
 * whether a delete file removes it, which is the one question about a merge-on-read table the
 * metadata cannot answer. Behind a click, since it is a DuckDB read per file, and it runs
 * against the filter as it stands when the click lands; the files the drawn part of the graph
 * ruled out are not opened, and a file not drawn is read rather than guessed at. Both formats
 * land here — the result is one shape — and the file a Paimon row is looked up in is named by
 * its file name, since that is what a vector range and a manifest entry name it by.
 */
@Composable
internal fun RowLookupSection(
    node: GraphNode.TableNode,
    graph: GraphModel,
    filter: ScanFilter,
    startRequested: Boolean = false,
    /** Runs the history trace under the lookup without a click — a capture's way in, like [startRequested]. */
    historyRequested: Boolean = false,
    onHistorySettled: () -> Unit = {},
    /** The same for the changelog trace, on a Paimon table whose snapshots name one. */
    changelogRequested: Boolean = false,
    onChangelogSettled: () -> Unit = {},
    /** Files opened per click; a capture lowers it to reach the paged state on a small table. */
    pageSize: Int = RowLookup.MAX_FILES,
    /** Last, so a caller's trailing lambda is the lookup's. */
    onSettled: () -> Unit = {},
) {
    val paimon = node.paimonRowLookup.isPresent
    if (!node.rowLookup.isPresent && !paimon) return
    LookupSection(
        id = node.id, graph = graph, filter = filter, paimon = paimon,
        scope = LookupScope.TABLE,
        read = { if (paimon) node.paimonRowLookup.value else node.rowLookup.value },
        startRequested = startRequested, pageSize = pageSize, onSettled = onSettled,
    ) { ruledOut ->
        // The changelog, once read, is joined onto the history's rows by snapshot — what each
        // commit published beside what each snapshot returns — so the reader pairs "changed at
        // the APPEND" with "-U/+U at the COMPACT" in one table rather than across two.
        var changelog by remember(node.id, filter) { mutableStateOf<PaimonChangelog?>(null) }
        if (node.rowHistory.isPresent) RowHistoryStage(node, filter, ruledOut, paimon, historyRequested, onHistorySettled, published = changelog?.let { c -> c.records.groupBy { it.snapshotId } })
        if (node.paimonChangelog.isPresent) ChangelogStage(node, filter, changelogRequested, onChangelogSettled, onRead = { changelog = it })
    }
}

/**
 * The same lookup as of one snapshot, on its panel — the answer the table's history cannot
 * give for a branch tip, a tag-only snapshot, or a commit past [RowHistory]'s cap, since the
 * history walks `main`'s retained snapshots alone. The snapshot's [readInput] is the input
 * the live-row count and the merged count read from, so what is looked up is what a read as
 * of this snapshot returns. No history or changelog stage: those are the table's.
 */
@Composable
internal fun SnapshotRowLookupSection(
    id: String,
    readInput: DeferredRead<out LookupInput>,
    paimon: Boolean,
    graph: GraphModel,
    filter: ScanFilter,
    startRequested: Boolean = false,
    onSettled: () -> Unit = {},
) {
    if (!readInput.isPresent) return
    LookupSection(
        id = id, graph = graph, filter = filter, paimon = paimon,
        scope = LookupScope.SNAPSHOT,
        read = { readInput.value },
        startRequested = startRequested, pageSize = RowLookup.MAX_FILES, onSettled = onSettled,
    ) {}
}

/** Whose files a lookup reads: the table's current snapshot, where the filter form sits, or the snapshot whose panel it is on. */
private enum class LookupScope { TABLE, SNAPSHOT }

/**
 * The lookup itself: the files the filter leaves opened a page per click, the hits with their
 * fates, and [stages] drawn under the result once one is on screen.
 */
@Composable
private fun LookupSection(
    id: String,
    graph: GraphModel,
    filter: ScanFilter,
    paimon: Boolean,
    scope: LookupScope,
    read: () -> LookupInput?,
    startRequested: Boolean,
    pageSize: Int,
    onSettled: () -> Unit,
    stages: @Composable (ruledOut: Set<String>) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val ruledOut = remember(graph, filter) {
        val plan = evaluateScan(graph, filter)
        plan.files.filter { it.value.fate == FileFate.SKIPPED }.keys
            .mapNotNull { id ->
                when (val file = graph.nodeById[id]) {
                    is GraphNode.FileNode -> file.data.filePath?.let(::normalizeFilePath)
                    is GraphNode.PaimonDataFileNode -> file.entry.file?.fileName
                    else -> null
                }
            }
            .toSet()
    }
    var requestedFor by remember(id) { mutableStateOf<ScanFilter?>(if (startRequested) filter else null) }
    // A table past the cap is read a page per click, the pages folded with `plus` — the same
    // shape as the integrity panel's file sweep. A new filter starts the pages over.
    var pagesRequested by remember(id) { mutableStateOf(if (startRequested) 1 else 0) }
    var outcome by remember(id) { mutableStateOf<Result<RowLookupResult>?>(null) }
    var readingPage by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id, requestedFor, pagesRequested) {
        val asked = requestedFor ?: return@LaunchedEffect
        if (pagesRequested == 0) return@LaunchedEffect
        readingPage = true
        val soFar = outcome?.getOrNull()
        val from = soFar?.filesRead?.size ?: 0
        outcome = withContext(Dispatchers.IO) {
            runCatching {
                val page = when (val input = requireNotNull(read()) { "no snapshot to read" }) {
                    is PaimonReadInput -> PaimonRowLookup.lookup(input, asked, ruledOut, from = from, max = pageSize)
                    is RowLookupInput -> RowLookup.lookup(input, asked, ruledOut, from = from, max = pageSize)
                }
                soFar?.plus(page) ?: page
            }
        }
        readingPage = false
        if (pagesRequested == 1) onSettled()
    }
    val result = outcome?.getOrNull()
    val title = "Row Lookup" + if (result != null) " — ${formatCounted(result.hits.size, "row")}, ${result.live} live" else ""

    val whose = if (scope == LookupScope.SNAPSHOT) "this snapshot's" else "the latest snapshot's"
    Section(title) {
        Text(
            if (paimon) {
                "The rows the filter matches, read from $whose live data files it did not " +
                    "rule out, each with its fate: marked by the vector its index file holds, a -D or -U " +
                    "retraction rather than a row, shadowed by a later write for its key, or folded with " +
                    "the key's other records — the merge a read runs under the table's merge engine, " +
                    "applied to one row. The bucket's other files whose key range may hold the key are " +
                    "read for it whether or not the filter left them."
            } else {
                "The rows the filter matches, read from $whose live data files it did not rule out, each with " +
                    "its fate under the delete files a scan pairs with its file — a vector by the row's " +
                    "position, a positional delete by (file_path, pos), an equality delete by the row's own " +
                    "values. The one question about a merge-on-read table the metadata cannot settle."
            },
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        // The form the filter is typed in sits on the table panel, so this panel names it.
        if (scope == LookupScope.SNAPSHOT && !filter.isEmpty()) {
            Text("The table panel's filter: ${filter.render()}.", fontSize = TypeScale.small, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        }
        when {
            filter.isEmpty() -> Text(
                if (scope == LookupScope.SNAPSHOT) "Enter a filter on the table panel to look rows up as of this snapshot." else "Enter a filter above to look rows up.",
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
            )
            requestedFor == null || (requestedFor != filter && outcome != null) -> {
                if (requestedFor != null && result != null) {
                    Text("The filter has changed since these rows were read.", fontSize = TypeScale.small, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                }
                OutlinedButton(onClick = { requestedFor = filter; outcome = null; pagesRequested = 1 }) {
                    Text("Read the files the filter leaves ($pageSize at most)")
                }
                if (result != null) ResultBody(result, paimon)
            }
            outcome == null -> Text("Reading ${filter.render()}…", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            result == null -> Text(
                "Could not read: ${outcome?.exceptionOrNull()?.message ?: "unknown error"}",
                fontSize = TypeScale.small,
                color = colors.error,
            )
            else -> {
                ResultBody(result, paimon)
                when {
                    readingPage -> Text("Reading the next files…", fontSize = TypeScale.small, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    result.filesLeft > 0 -> OutlinedButton(onClick = { pagesRequested++ }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Read the next ${minOf(pageSize, result.filesLeft)} (${formatCounted(result.filesLeft, "file")} left)")
                    }
                }
                stages(ruledOut)
            }
        }
    }
}

/**
 * What each commit *published* for the rows — the changelog files its snapshot names, read for
 * the same filter — behind a third click; see [PaimonChangelog]. The history above is what a
 * batch read returns at each snapshot, and this is the other side of `changelog-producer`: the
 * stream a downstream consumer receives, which under `lookup` carries the change in the COMPACT
 * commit after the append that made it, and under `input` carries the write as it arrived.
 */
@Composable
private fun ChangelogStage(
    node: GraphNode.TableNode,
    filter: ScanFilter,
    startRequested: Boolean,
    onSettled: () -> Unit,
    /** The changelog once read, for the history's `Published` column. */
    onRead: (PaimonChangelog?) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    var requested by remember(node.id, filter) { mutableStateOf(startRequested) }
    val outcome by produceState<Result<PaimonChangelog>?>(null, node.id, filter, requested) {
        value = null
        onRead(null)
        if (requested) {
            value = withContext(Dispatchers.IO) {
                runCatching { PaimonChangelogTrace.trace(requireNotNull(node.paimonChangelog.value) { "no changelog to read" }, filter) }
            }
            onRead(value?.getOrNull())
            onSettled()
        }
    }
    val changelog = outcome?.getOrNull()
    Text(
        "Changelog",
        fontSize = TypeScale.small,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
    when {
        !requested -> OutlinedButton(onClick = { requested = true }) {
            Text("Read what each commit published for these rows")
        }
        outcome == null -> Text("Reading each snapshot's changelog…", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
        changelog == null -> Text("Could not read: ${outcome?.exceptionOrNull()?.message ?: "unknown error"}", fontSize = TypeScale.small, color = colors.error)
        else -> ChangelogBody(changelog)
    }
}

@Composable
private fun ChangelogBody(changelog: PaimonChangelog) {
    val colors = MaterialTheme.colorScheme
    val published = changelog.publishedAt
    Text(
        (if (changelog.capped) "The last ${changelog.snapshotsRead} of ${changelog.withChangelog} snapshots naming a changelog" else "All ${formatCounted(changelog.snapshotsRead, "snapshot")} naming a changelog") +
            (if (changelog.records.isEmpty()) ": nothing was published for the matching rows." else
                ": ${formatCounted(changelog.records.size, "record")} published for the matching rows, at ${published.joinToString(", ") { "snapshot $it" }}."),
        fontSize = TypeScale.small,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    Text(
        changelog.producerRule + (if (changelog.capped) ". Older snapshots are not read." else "."),
        fontSize = TypeScale.small,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    WideTable(
        headers = listOf("Kind", "Snapshot", "Commit", "Sequence", "File", "Row"),
        columnWidths = listOf(260.dp, 120.dp, 100.dp, 90.dp, 340.dp, 600.dp),
        rows = changelog.records.map { record ->
            listOf(
                record.kind?.let(PaimonRowKind::describe) ?: "—",
                record.snapshotId.toString(),
                record.commitKind ?: "—",
                record.sequenceNumber?.toString() ?: "—",
                record.fileName,
                record.cells.entries.take(MAX_ROW_CELLS).joinToString(", ") { "${it.key}=${it.value ?: "null"}" } + (if (record.cells.size > MAX_ROW_CELLS) ", …" else ""),
            )
        },
        // A retraction is the exception a stream's reader looks for; an insert or an after-image is the ordinary row.
        leadCellColors = changelog.records.map { if (it.isRetraction) verdictSkippedColor() else null },
    )
    if (changelog.unreadable > 0) {
        Text("${formatCounted(changelog.unreadable, "changelog file")} could not be read; the stream shown is incomplete.", fontSize = TypeScale.small, color = colors.error, modifier = Modifier.padding(top = 4.dp))
    }
    if (changelog.filesRead.any { it.matched >= RowLookup.MAX_HITS_PER_FILE }) {
        Text("A file's records stop at ${RowLookup.MAX_HITS_PER_FILE}; narrow the filter to read the rest.", fontSize = TypeScale.small, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * The same lookup at every retained snapshot on `main`, behind a second click — see
 * [RowHistory]. It answers the question the fate above cannot: a row deleted three commits ago
 * and one deleted by the last commit look the same there, and this names the commit. The
 * change column marks the commits that did something to the rows; the rest say `unchanged`
 * so a column of them reads as a history and not as a table with holes.
 */
@Composable
private fun RowHistoryStage(
    node: GraphNode.TableNode,
    filter: ScanFilter,
    ruledOut: Set<String>,
    paimon: Boolean,
    startRequested: Boolean,
    onSettled: () -> Unit,
    /** The changelog's records by snapshot, once the stage below has read them; null until then. */
    published: Map<Long, List<ChangelogRecord>>? = null,
) {
    val colors = MaterialTheme.colorScheme
    var requested by remember(node.id, filter) { mutableStateOf(startRequested) }
    val outcome by produceState<Result<RowHistory>?>(null, node.id, filter, requested) {
        value = null
        if (requested) {
            value = withContext(Dispatchers.IO) {
                runCatching { RowHistoryTrace.trace(requireNotNull(node.rowHistory.value) { "no snapshot to read" }, filter, ruledOut) }
            }
            onSettled()
        }
    }
    val history = outcome?.getOrNull()
    Text(
        "History",
        fontSize = TypeScale.small,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
    when {
        !requested -> OutlinedButton(onClick = { requested = true }) {
            Text("Trace these rows through the last ${model.MAX_HISTORY_SNAPSHOTS} snapshots on main")
        }
        outcome == null -> Text("Reading each snapshot…", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
        history == null -> Text("Could not trace: ${outcome?.exceptionOrNull()?.message ?: "unknown error"}", fontSize = TypeScale.small, color = colors.error)
        else -> HistoryBody(history, paimon, published)
    }
}

@Composable
private fun HistoryBody(history: RowHistory, paimon: Boolean, published: Map<Long, List<ChangelogRecord>>? = null) {
    val colors = MaterialTheme.colorScheme
    val changed = history.changedSteps
    val traced = history.steps.size
    Text(
        (if (history.capped) "The last $traced of ${history.onMain} snapshots on main" else "All ${formatCounted(traced, "snapshot")} on main") +
            (if (changed.isEmpty()) ": the matching rows are the same at every one traced." else
                ": the rows changed at ${changed.asReversed().joinToString(", ") { step -> "snapshot ${step.snapshot.snapshotId} (${step.snapshot.operation ?: "?"}, ${history.changes[history.steps.indexOf(step)]?.label})" }}."),
        fontSize = TypeScale.small,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    Text(
        (if (paimon) "Each snapshot is read under its own schema; " else "Every snapshot is read under the current schema; ") +
            "a step compares the live rows a read returns with the snapshot before it, on the row's own columns" +
            (if (history.capped) ". Older snapshots are not traced." else ".") +
            (if (published != null) " Published is what the snapshot's changelog carries for these rows, from the stage below — under a lookup producer the change a snapshot made is published by the COMPACT after it." else ""),
        fontSize = TypeScale.small,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    WideTable(
        headers = listOf("Change", "Snapshot", "Operation") + (if (published != null) listOf("Published") else emptyList()) + listOf("When", "Live", if (paimon) "Not live" else "Deleted", "Rows"),
        columnWidths = listOf(120.dp, 190.dp, 110.dp) + (if (published != null) listOf(160.dp) else emptyList()) + listOf(190.dp, 60.dp, 80.dp, 600.dp),
        rows = history.steps.mapIndexed { i, step ->
            val result = step.result
            listOf(
                history.changes[i]?.label ?: "—",
                step.snapshot.snapshotId.toString(),
                step.snapshot.operation ?: "—",
            ) + (if (published != null) listOf(published[step.snapshot.snapshotId]?.joinToString(", ") { r -> r.kind?.let(PaimonRowKind::symbol) ?: "?" }?.ifEmpty { "—" } ?: "—") else emptyList()) + listOf(
                step.snapshot.timestampMs?.let(::formatAppTimestamp) ?: "—",
                result.live.toString(),
                result.deleted.toString() + (result.undecided.takeIf { it > 0 }?.let { " ($it not decided)" } ?: ""),
                step.liveRows.joinToString("; ") { row ->
                    row.entries.take(MAX_ROW_CELLS).joinToString(", ") { "${it.key}=${it.value ?: "null"}" } + (if (row.size > MAX_ROW_CELLS) ", …" else "")
                }.ifEmpty { "—" },
            )
        },
        leadCellColors = history.steps.mapIndexed { i, _ ->
            when (history.changes[i]) {
                null, RowChange.UNCHANGED -> null
                RowChange.GONE -> verdictSkippedColor()
                RowChange.APPEARED, RowChange.CHANGED -> verdictUnevaluatedColor()
            }
        },
    )
    val unreadable = history.steps.count { step -> step.result.filesRead.any { it.error != null } }
    if (unreadable > 0) {
        Text("${formatCounted(unreadable, "snapshot")} had a file that could not be read; its rows may be incomplete.", fontSize = TypeScale.small, color = colors.error, modifier = Modifier.padding(top = 4.dp))
    }
    if (history.steps.any { it.result.filesLeft > 0 }) {
        Text("A snapshot's files stop at ${RowLookup.MAX_FILES}; narrow the filter to read the rest.", fontSize = TypeScale.small, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ResultBody(result: RowLookupResult, paimon: Boolean) {
    val colors = MaterialTheme.colorScheme
    val read = result.filesRead.size
    val failed = result.filesRead.count { it.error != null }
    Text(
        "${formatCounted(result.hits.size, "matching row")} in ${formatCounted(read, "file")} read" +
            (if (result.filesRuledOut > 0) ", ${result.filesRuledOut} ruled out by the filter" else "") +
            (if (result.filesLeft > 0) ", ${result.filesLeft} not read yet" else "") +
            (if (failed > 0) ", $failed could not be read" else "") +
            ": ${result.live} live, ${result.deleted} ${if (paimon) "not live" else "deleted"}" +
            (result.undecided.takeIf { it > 0 }?.let { ", $it not decided" } ?: "") + ".",
        fontSize = TypeScale.small,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    result.rule?.let { Text(it + ".", fontSize = TypeScale.small, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp)) }
    if (result.skippedFiles > 0) {
        Text(
            "${formatCounted(result.skippedFiles, "live file")} at level 0, holding ${formatCounted(result.skippedRows.toInt(), "row")}, " +
                "${if (result.skippedFiles == 1) "is" else "are"} not read by a batch read of this table; a record found there is marked not read.",
            fontSize = TypeScale.small,
            color = verdictUnevaluatedColor(),
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
    if (result.bucketFilesRead + result.bucketFilesPruned > 0) {
        Text(
            "The hits' keys were asked of their buckets' other files: ${formatCounted(result.bucketFilesRead, "file")} opened" +
                (if (result.bucketFilesPruned > 0) ", ${result.bucketFilesPruned} left unopened because ${if (result.bucketFilesPruned == 1) "its" else "their"} key range excludes every key asked" else "") + ".",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
    result.filesRead.filter { it.error != null }.forEach { Text("Could not read ${fileNameFromPath(it.filePath)}: ${it.error}", fontSize = TypeScale.small, color = colors.error) }
    if (result.hits.isNotEmpty()) {
        WideTable(
            // The fate, then why, then by what, then where: a Paimon superseded record carries a note
            // on every row, and folded into the fate cell it wrapped the column to three lines.
            headers = listOf("Fate", "Note", "By", "File", "Position", "Row"),
            // 520dp holds a Spark-written data file name on one line.
            columnWidths = listOf(190.dp, 300.dp, 300.dp, 520.dp, 80.dp, 600.dp),
            rows = result.hits.map { hit ->
                listOf(
                    hit.fate.label,
                    hit.note ?: "—",
                    hit.by?.let(::fileNameFromPath) ?: "—",
                    fileNameFromPath(hit.filePath),
                    hit.position?.toString() ?: "—",
                    // A Paimon key-value row leads with three system columns; the row's own come first, as on the card.
                    hit.cells.entries.sortedBy { it.key.startsWith("_") }.take(MAX_ROW_CELLS).joinToString(", ") { "${it.key}=${it.value ?: "null"}" } +
                        (if (hit.cells.size > MAX_ROW_CELLS) ", …" else ""),
                )
            },
            leadCellColors = result.hits.map {
                when (it.fate) {
                    RowFate.LIVE, RowFate.MERGED -> null
                    RowFate.UNKNOWN, RowFate.SKIPPED -> verdictUnevaluatedColor()
                    else -> verdictSkippedColor()
                }
            },
        )
        if (result.filesRead.any { it.hits >= RowLookup.MAX_HITS_PER_FILE }) {
            Text(
                "A file's hits stop at ${RowLookup.MAX_HITS_PER_FILE}; narrow the filter to see the rest.",
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
