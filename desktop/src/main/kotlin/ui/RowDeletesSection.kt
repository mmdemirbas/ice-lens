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
import model.DeleteCandidate
import model.DeleteFileKind
import model.DeleteReachVerdict
import model.GraphModel
import model.GraphNode
import model.RowFate
import model.RowHit
import model.asLookupDataFile
import model.asLookupDeleteFile
import model.deleteCandidatesFor
import service.RowLookup

/** The data file a sampled Iceberg row was read from, or null for a row of a delete file or of a Paimon file. */
internal fun rowParentFile(node: GraphNode.RowNode, graph: GraphModel): GraphNode.FileNode? =
    graph.edges.firstOrNull { it.toId == node.id }?.let { graph.nodeById[it.fromId] as? GraphNode.FileNode }

/**
 * The delete files the pairing did not rule out for [parent] among the files the graph draws —
 * what the row's panel asks behind a click, and what its `Deleted` row says is still open.
 */
internal fun rowDeleteCandidates(parent: GraphNode.FileNode, graph: GraphModel): List<DeleteCandidate> =
    deleteCandidatesFor(parent, graph.nodes.filterIsInstance<GraphNode.FileNode>())
        .filter { it.verdict == DeleteReachVerdict.REACHES || it.verdict == DeleteReachVerdict.MAY_REACH }

/**
 * Whether a sampled Iceberg row is one a read returns, decided against the delete files the
 * graph draws for its file — the row panel's `Deleted` line answers for a v3 vector alone,
 * because a vector is decoded at build time and a positional or equality delete is a file
 * read. So this is behind a click, and it is the decision [RowLookup] makes for a hit
 * ([RowLookup.fateOf]) with the row's position and cells already in hand: a positional delete
 * asked for `(file_path, pos)`, an equality delete for the row's values on its columns, in the
 * order the pairing lists them. The candidates are what [deleteCandidatesFor] weighs for the
 * file's own panel, so the two agree on which files are asked; a delete the pairing ruled out
 * is not opened. Drawn only where the graph holds a delete file the pairing did not rule out.
 */
@Composable
internal fun RowDeletesSection(
    node: GraphNode.RowNode,
    graph: GraphModel,
    startRequested: Boolean = false,
    onSettled: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val parent = remember(node.id, graph.nodes) { rowParentFile(node, graph) } ?: return
    val candidates = remember(parent.id, graph.nodes) { rowDeleteCandidates(parent, graph) }
    if (candidates.isEmpty()) return
    val position = node.filePosition

    var requested by remember(node.id) { mutableStateOf(startRequested) }
    val outcome by produceState<Result<RowHit>?>(null, node.id, requested) {
        value = null
        if (requested) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val file = requireNotNull(parent.asLookupDataFile()) { "the file records no path" }
                    val cells = node.resolvedData.filterKeys { it != "file_no" && it != "row_idx" && it != GraphNode.RowNode.ROW_POSITION_KEY && it != "local_file_path" }
                    RowLookup.fateOf(file, position, cells, candidates.mapNotNull { it.delete.asLookupDeleteFile() })
                }
            }
            onSettled()
        }
    }
    val hit = outcome?.getOrNull()
    val title = "Delete Files" + when (hit?.fate) {
        null -> ""
        RowFate.LIVE -> " — live"
        RowFate.UNKNOWN -> " — not settled"
        else -> " — deleted"
    }

    Section(title) {
        Text(
            "Whether a read returns this row: the ${formatCounted(candidates.size, "delete file")} a scan pairs with " +
                "its data file (the file's own panel lists them and why), each opened for this row — a " +
                "positional delete for its position, an equality delete for its values on the columns the " +
                "delete names — the way the table's row lookup decides a hit.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        when {
            !requested -> OutlinedButton(onClick = { requested = true }) { Text("Ask the delete files") }
            outcome == null -> Text("Reading…", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            hit == null -> Text(
                "Could not decide: ${outcome?.exceptionOrNull()?.message ?: "unknown error"}",
                fontSize = TypeScale.small,
                color = colors.error,
            )
            else -> {
                val what = when (hit.fate) {
                    RowFate.LIVE -> "Live — no delete file paired with its file removes this row."
                    RowFate.POSITION_DELETED -> "Deleted — ${fileNameFromPath(hit.by.orEmpty())} holds this file's path and position ${hit.position}."
                    RowFate.EQUALITY_DELETED -> "Deleted — ${fileNameFromPath(hit.by.orEmpty())} holds a row equal to this one on the columns it names."
                    RowFate.VECTOR_DELETED -> "Deleted — the vector in ${fileNameFromPath(hit.by.orEmpty())} marks position ${hit.position}."
                    else -> "Not settled" + (hit.note?.let { ": $it" } ?: ".")
                }
                Text(
                    what,
                    fontSize = TypeScale.small,
                    fontWeight = FontWeight.Bold,
                    color = when (hit.fate) {
                        RowFate.LIVE -> colors.onSurface
                        RowFate.UNKNOWN -> verdictUnevaluatedColor()
                        else -> verdictSkippedColor()
                    },
                )
                if (hit.fate == RowFate.LIVE && hit.note != null) {
                    Text(hit.note.orEmpty(), fontSize = TypeScale.small, color = colors.onSurfaceVariant)
                }
                val kinds = candidates.map { it.kind }.toSet()
                if (DeleteFileKind.EQUALITY in kinds && hit.fate == RowFate.LIVE) {
                    Text(
                        "An equality delete was asked by value, so this holds for the row's cells as sampled.",
                        fontSize = TypeScale.small,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
