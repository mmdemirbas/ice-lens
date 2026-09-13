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
import model.DeleteFileKind
import model.GraphNode
import model.ManifestContent
import service.LiveRowCount

/**
 * The rows a read of this Iceberg snapshot returns — see [LiveRowCount]. Under the totals
 * because it is the figure `total-records` is not: that counts rows as written, and a
 * merge-on-read delete removes rows without touching it. A snapshot whose manifest list names
 * no delete manifest has the answer in the metadata and draws it at once; one with delete
 * files reads the data files they reach, behind a click.
 */
@Composable
internal fun LiveRowsSection(
    node: GraphNode.SnapshotNode,
    startRequested: Boolean = false,
    onSettled: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    if (!node.readInput.isPresent) return
    val needsRead = node.manifestList.any { it.content == ManifestContent.DELETES }

    var requested by remember(node.id) { mutableStateOf(startRequested || !needsRead) }
    val outcome by produceState<Result<LiveRowCount.Result>?>(null, node.id, requested) {
        value = null
        if (requested) {
            value = withContext(Dispatchers.IO) {
                runCatching { LiveRowCount.count(requireNotNull(node.readInput.value) { "the snapshot could not be read" }) }
            }
            onSettled()
        }
    }
    val result = outcome?.getOrNull()
    val title = "Live Rows" + (result?.live?.let { " — ${formatCount(it)}" } ?: "")

    Section(title) {
        Text(
            if (needsRead) {
                "What SELECT count(*) returns as of this snapshot: each data file's record_count less " +
                    "the rows the delete files a scan pairs with it remove — a vector's cardinality, " +
                    "decoded; a positional delete's positions for the file; an equality delete's matches " +
                    "on its columns — one bit set per file, so a row two deletes remove is one row gone. " +
                    "total-records counts every row as written."
            } else {
                "What SELECT count(*) returns as of this snapshot: the data files' record counts, " +
                    "since this snapshot lists no delete manifest — no file is opened."
            },
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        when {
            !requested -> OutlinedButton(onClick = { requested = true }) {
                Text("Apply the delete files (${LiveRowCount.MAX_FILES} data files opened at most)")
            }
            outcome == null -> Text("Counting…", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            result == null -> Text(
                "Could not count: ${outcome?.exceptionOrNull()?.message ?: "unknown error"}",
                fontSize = TypeScale.small,
                color = colors.error,
            )
            else -> ResultBody(result)
        }
    }
}

@Composable
private fun ResultBody(result: LiveRowCount.Result) {
    val colors = MaterialTheme.colorScheme
    Text(
        (result.live?.let { "${formatCount(it)} rows" } ?: "Not settled") +
            " — ${formatCount(result.recordCount)} in ${formatCounted(result.files.size, "data file")}" +
            (if (result.reached == 0) ", no delete file reaching any of them" else
                ", ${formatCount(result.removed)} removed by delete files reaching ${result.reached} of them (${result.opened} opened)") +
            (if (result.filesLeft > 0) ", ${result.filesLeft} left unopened by the cap" else "") +
            (if (result.failed > 0) ", ${result.failed} could not be counted" else "") + ".",
        fontSize = TypeScale.small,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    val reached = result.files.filter { it.kinds.isNotEmpty() }
    if (reached.isNotEmpty()) {
        WideTable(
            headers = listOf("Removed", "Live", "Records", "By", "File"),
            columnWidths = listOf(110.dp, 100.dp, 100.dp, 260.dp, 520.dp),
            rows = reached.map { f ->
                listOf(
                    f.error?.let { "— $it" } ?: formatCount(f.removed),
                    if (f.error == null) formatCount(f.live) else "—",
                    formatCount(f.recordCount),
                    f.kinds.sortedBy { it.ordinal }.joinToString(", ") { kindLabel(it) } + if (f.opened) "" else " (not opened)",
                    fileNameFromPath(f.file.recordedPath),
                )
            },
            leadCellColors = reached.map { if (it.error != null) colors.error else null },
        )
    }
}

private fun kindLabel(kind: DeleteFileKind): String = when (kind) {
    DeleteFileKind.POSITIONAL -> "positional delete"
    DeleteFileKind.EQUALITY -> "equality delete"
    DeleteFileKind.DELETION_VECTOR -> "vector"
}
