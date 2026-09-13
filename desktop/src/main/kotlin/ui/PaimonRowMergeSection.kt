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
import model.GraphModel
import model.GraphNode
import model.PredicateOp
import model.RowFate
import model.RowLookupResult
import model.ScanFilter
import model.ScanPredicate
import service.PaimonRowLookup

/**
 * What the merge engine does with a sampled record of a Paimon primary-key table — the Iceberg
 * row panel's [RowDeletesSection] on the other format. A record is a version of a row, and
 * whether a read returns it depends on the key's other records in the bucket, which are in
 * other files; so this is the table's row lookup ([PaimonRowLookup]) run for the record's own
 * key, behind a click, and the record's line in the answer is led with while the key's other
 * records are listed under it. The lookup reads the latest snapshot's live files, so a record
 * in a file that snapshot no longer lists is said to be one rather than looked for.
 */
@Composable
internal fun PaimonRowMergeSection(
    node: GraphNode.RowNode,
    graph: GraphModel,
    startRequested: Boolean = false,
    onSettled: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val parent = remember(node.id, graph.nodes) {
        graph.edges.firstOrNull { it.toId == node.id }?.let { graph.nodeById[it.fromId] as? GraphNode.PaimonDataFileNode }
    } ?: return
    val table = remember(graph.nodes) { graph.nodes.filterIsInstance<GraphNode.TableNode>().firstOrNull() } ?: return
    if (!table.paimonRowLookup.isPresent) return
    val fileName = parent.entry.file?.fileName ?: return
    val position = node.filePosition ?: return

    var requested by remember(node.id) { mutableStateOf(startRequested) }
    val outcome by produceState<Result<Pair<RowLookupResult, List<String>>?>?>(null, node.id, requested) {
        value = null
        if (requested) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val input = requireNotNull(table.paimonRowLookup.value) { "the table's latest snapshot could not be read" }
                    val keys = input.trimmedPrimaryKeys
                    if (keys.isEmpty()) return@runCatching null
                    if (input.files.none { it.fileName == fileName }) throw IllegalStateException("the latest snapshot does not list this file, so a read of the table never opens it")
                    val terms = keys.map { key ->
                        val value = node.resolvedData[PaimonRowLookup.KEY_PREFIX + key] ?: node.resolvedData[key]
                        ScanPredicate(key, if (value == null) PredicateOp.IS_NULL else PredicateOp.EQ, value?.toString() ?: "")
                    }
                    PaimonRowLookup.lookup(input, ScanFilter.And(terms.map { ScanFilter.Term(it) }), emptySet()) to keys
                }
            }
            onSettled()
        }
    }
    val answer = outcome?.getOrNull()
    val result = answer?.first
    val own = result?.hits?.firstOrNull { it.filePath == fileName && it.position == position }
    val title = "Merge" + (own?.let { " — ${it.fate.label}" } ?: "")

    Section(title) {
        Text(
            "Whether a read returns this record: the table's merge engine over every record of its key " +
                "in the bucket's live files — the row lookup, run for this key — with this record's line " +
                "first and the key's other records under it.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        when {
            !requested -> OutlinedButton(onClick = { requested = true }) { Text("Ask the bucket") }
            outcome == null -> Text("Reading…", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            outcome?.isFailure == true -> Text(
                "Could not decide: ${outcome?.exceptionOrNull()?.message ?: "unknown error"}",
                fontSize = TypeScale.small,
                color = colors.error,
            )
            result == null -> Text("An append table has no key to merge on; a vector is the only thing that removes a row.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            else -> {
                result.rule?.let { Text("$it.", fontSize = TypeScale.small, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp)) }
                if (own == null) {
                    Text(
                        "This record was not among the key's ${formatCounted(result.hits.size, "record")} the lookup read" +
                            (if (result.filesRead.any { it.error != null }) " — a file could not be read" else "") + ".",
                        fontSize = TypeScale.small,
                        color = verdictUnevaluatedColor(),
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    Text(
                        own.fate.label.replaceFirstChar { it.uppercase() } +
                            (own.by?.let { " — by ${fileNameFromPath(it)}" } ?: "") +
                            (own.note?.let { " — $it" } ?: "") + ".",
                        fontSize = TypeScale.small,
                        fontWeight = FontWeight.Bold,
                        color = when (own.fate) {
                            RowFate.LIVE, RowFate.MERGED -> colors.onSurface
                            RowFate.UNKNOWN, RowFate.SKIPPED -> verdictUnevaluatedColor()
                            else -> verdictSkippedColor()
                        },
                    )
                }
                val others = result.hits.filter { it !== own }
                if (others.isNotEmpty()) {
                    WideTable(
                        headers = listOf("Fate", "Sequence", "Kind", "File", "Position", "Note"),
                        columnWidths = listOf(190.dp, 90.dp, 120.dp, 520.dp, 80.dp, 300.dp),
                        rows = others.map { hit ->
                            listOf(
                                hit.fate.label,
                                hit.cells[PaimonRowLookup.SEQUENCE_NUMBER]?.toString() ?: "—",
                                (hit.cells[model.PaimonRowKind.COLUMN] as? Number)?.let { model.PaimonRowKind.describe(it.toInt()) } ?: "—",
                                fileNameFromPath(hit.filePath),
                                hit.position?.toString() ?: "—",
                                hit.note ?: "—",
                            )
                        },
                        leadCellColors = others.map {
                            when (it.fate) {
                                RowFate.LIVE, RowFate.MERGED -> null
                                RowFate.UNKNOWN, RowFate.SKIPPED -> verdictUnevaluatedColor()
                                else -> verdictSkippedColor()
                            }
                        },
                    )
                }
            }
        }
    }
}
