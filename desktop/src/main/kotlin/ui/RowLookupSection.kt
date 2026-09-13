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
import model.ScanFilter
import model.evaluateScan
import model.isEmpty
import model.normalizeFilePath
import model.render
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
 * ruled out are not opened, and a file not drawn is read rather than guessed at.
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
    if (!node.rowLookup.isPresent) return

    val ruledOut = remember(graph, filter) {
        val plan = evaluateScan(graph, filter)
        plan.files.filter { it.value.fate == FileFate.SKIPPED }.keys
            .mapNotNull { id -> (graph.nodeById[id] as? GraphNode.FileNode)?.data?.filePath?.let(::normalizeFilePath) }
            .toSet()
    }
    var requestedFor by remember(node.id) { mutableStateOf<ScanFilter?>(if (startRequested) filter else null) }
    val outcome by produceState<Result<RowLookup.Result>?>(null, node.id, requestedFor) {
        value = null
        val asked = requestedFor
        if (asked != null) {
            value = withContext(Dispatchers.IO) {
                runCatching { RowLookup.lookup(requireNotNull(node.rowLookup.value) { "no current snapshot to read" }, asked, ruledOut) }
            }
            onSettled()
        }
    }
    val result = outcome?.getOrNull()
    val title = "Row Lookup" + if (result != null) " — ${formatCounted(result.hits.size, "row")}, ${result.live} live" else ""

    Section(title) {
        Text(
            "The rows the filter matches, read from the live data files it did not rule out, each with " +
                "its fate under the delete files a scan pairs with its file — a vector by the row's " +
                "position, a positional delete by (file_path, pos), an equality delete by the row's own " +
                "values. The one question about a merge-on-read table the metadata cannot settle.",
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
                if (result != null) ResultBody(result)
            }
            outcome == null -> Text("Reading ${filter.render()}…", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            result == null -> Text(
                "Could not read: ${outcome?.exceptionOrNull()?.message ?: "unknown error"}",
                fontSize = TypeScale.small,
                color = colors.error,
            )
            else -> ResultBody(result)
        }
    }
}

@Composable
private fun ResultBody(result: RowLookup.Result) {
    val colors = MaterialTheme.colorScheme
    val read = result.filesRead.size
    val failed = result.filesRead.count { it.error != null }
    Text(
        "${formatCounted(result.hits.size, "matching row")} in ${formatCounted(read, "file")} read" +
            (if (result.filesRuledOut > 0) ", ${result.filesRuledOut} ruled out by the filter" else "") +
            (if (result.filesLeft > 0) ", ${result.filesLeft} left unread by the cap" else "") +
            (if (failed > 0) ", $failed could not be read" else "") +
            ": ${result.live} live, ${result.deleted} deleted" +
            (result.hits.count { it.fate == RowLookup.RowFate.UNKNOWN }.takeIf { it > 0 }?.let { ", $it not decided" } ?: "") + ".",
        fontSize = TypeScale.small,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    result.filesRead.filter { it.error != null }.forEach { Text("Could not read ${fileNameFromPath(it.file.recordedPath)}: ${it.error}", fontSize = TypeScale.small, color = colors.error) }
    if (result.hits.isNotEmpty()) {
        WideTable(
            headers = listOf("Fate", "By", "File", "Position", "Row"),
            // 520dp holds a Spark-written data file name on one line.
            columnWidths = listOf(190.dp, 300.dp, 520.dp, 80.dp, 600.dp),
            rows = result.hits.map { hit ->
                listOf(
                    hit.fate.label + (hit.note?.let { " — $it" } ?: ""),
                    hit.by?.let(::fileNameFromPath) ?: "—",
                    fileNameFromPath(hit.file.recordedPath),
                    hit.position?.toString() ?: "—",
                    hit.cells.entries.take(MAX_ROW_CELLS).joinToString(", ") { "${it.key}=${it.value ?: "null"}" } +
                        (if (hit.cells.size > MAX_ROW_CELLS) ", …" else ""),
                )
            },
            leadCellColors = result.hits.map {
                when (it.fate) {
                    RowLookup.RowFate.LIVE -> null
                    RowLookup.RowFate.UNKNOWN -> verdictUnevaluatedColor()
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
