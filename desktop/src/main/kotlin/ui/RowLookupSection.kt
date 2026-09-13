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
import model.FileFate
import model.GraphModel
import model.GraphNode
import model.RowFate
import model.RowLookupResult
import model.ScanFilter
import model.evaluateScan
import model.isEmpty
import model.normalizeFilePath
import model.render
import service.PaimonRowLookup
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
    onSettled: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val paimon = node.paimonRowLookup.isPresent
    if (!node.rowLookup.isPresent && !paimon) return

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
    var requestedFor by remember(node.id) { mutableStateOf<ScanFilter?>(if (startRequested) filter else null) }
    val outcome by produceState<Result<RowLookupResult>?>(null, node.id, requestedFor) {
        value = null
        val asked = requestedFor
        if (asked != null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    if (paimon) {
                        PaimonRowLookup.lookup(requireNotNull(node.paimonRowLookup.value) { "no snapshot to read" }, asked, ruledOut)
                    } else {
                        RowLookup.lookup(requireNotNull(node.rowLookup.value) { "no current snapshot to read" }, asked, ruledOut)
                    }
                }
            }
            onSettled()
        }
    }
    val result = outcome?.getOrNull()
    val title = "Row Lookup" + if (result != null) " — ${formatCounted(result.hits.size, "row")}, ${result.live} live" else ""

    Section(title) {
        Text(
            if (paimon) {
                "The rows the filter matches, read from the latest snapshot's live data files it did not " +
                    "rule out, each with its fate: marked by the vector its index file holds, a -D or -U " +
                    "retraction rather than a row, shadowed by a later write for its key, or folded with " +
                    "the key's other records — the merge a read runs under the table's merge engine, " +
                    "applied to one row. The bucket's other files are read for the key whether or not the " +
                    "filter left them."
            } else {
                "The rows the filter matches, read from the live data files it did not rule out, each with " +
                    "its fate under the delete files a scan pairs with its file — a vector by the row's " +
                    "position, a positional delete by (file_path, pos), an equality delete by the row's own " +
                    "values. The one question about a merge-on-read table the metadata cannot settle."
            },
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        when {
            filter.isEmpty() -> Text("Enter a filter above to look rows up.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            requestedFor == null || (requestedFor != filter && outcome != null) -> {
                if (requestedFor != null && result != null) {
                    Text("The filter has changed since these rows were read.", fontSize = TypeScale.small, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                }
                OutlinedButton(onClick = { requestedFor = filter }) {
                    Text("Read the files the filter leaves (${RowLookup.MAX_FILES} at most)")
                }
                if (result != null) ResultBody(result, paimon)
            }
            outcome == null -> Text("Reading ${filter.render()}…", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            result == null -> Text(
                "Could not read: ${outcome?.exceptionOrNull()?.message ?: "unknown error"}",
                fontSize = TypeScale.small,
                color = colors.error,
            )
            else -> ResultBody(result, paimon)
        }
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
            (if (result.filesLeft > 0) ", ${result.filesLeft} left unread by the cap" else "") +
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
