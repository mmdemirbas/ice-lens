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

/** The lookup for the record's key on a primary-key table; the stitched row on a data-evolution table; neither on a plain append table. */
private class Answer(val lookup: RowLookupResult?, val keys: List<String>, val stitched: PaimonRowLookup.StitchedRow?)

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
    val outcome by produceState<Result<Answer>?>(null, node.id, requested) {
        value = null
        if (requested) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val input = requireNotNull(table.paimonRowLookup.value) { "the table's latest snapshot could not be read" }
                    val keys = input.trimmedPrimaryKeys
                    val file = input.files.firstOrNull { it.fileName == fileName }
                        ?: throw IllegalStateException("the latest snapshot does not list this file, so a read of the table never opens it")
                    // An append table merges nothing; under data evolution its row is still not
                    // the file's own, and the stitched one is what a read returns.
                    if (keys.isEmpty()) return@runCatching Answer(null, emptyList(), if (input.dataEvolution) PaimonRowLookup.stitchedRowAt(input, file, position) else null)
                    // The key under the schema's names — the projection's, since a key column
                    // renamed after the file was written is `_KEY_k` on the card and `id` in the schema.
                    val cells = node.cellsForRead
                    val terms = keys.map { key ->
                        val value = cells[key] ?: node.resolvedData[PaimonRowLookup.KEY_PREFIX + key]
                        ScanPredicate(key, if (value == null) PredicateOp.IS_NULL else PredicateOp.EQ, value?.toString() ?: "")
                    }
                    Answer(PaimonRowLookup.lookup(input, ScanFilter.And(terms.map { ScanFilter.Term(it) }), emptySet()), keys, null)
                }
            }
            onSettled()
        }
    }
    val answer = outcome?.getOrNull()
    val result = answer?.lookup
    val own = result?.hits?.firstOrNull { it.filePath == fileName && it.position == position }
    val stitched = answer?.stitched
    // Known before the click, off the table's options: a data-evolution table stitches rather than merges.
    val options = table.summary.paimonExpiry?.tableOptions.orEmpty()
    val dataEvolution = options[model.PAIMON_DATA_EVOLUTION_KEY] == "true"
    val title = (if (dataEvolution) "Read As" else "Merge") + (own?.let { " — ${it.fate.label}" } ?: stitched?.let { " — stitched" } ?: "")

    Section(title) {
        Text(
            if (dataEvolution) {
                "What a read returns for this row: the files sharing this file's first row id, stitched by " +
                    "position, each column from the freshest one holding it — so a column a later MERGE INTO " +
                    "patched is not the value this file holds."
            } else {
                "Whether a read returns this record: the table's merge engine over every record of its key " +
                    "in the bucket's live files — the row lookup, run for this key — with this record's line " +
                    "first and the key's other records under it."
            },
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        when {
            !requested -> OutlinedButton(onClick = { requested = true }) { Text(if (dataEvolution) "Read the split" else "Ask the bucket") }
            outcome == null -> Text("Reading…", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            outcome?.isFailure == true -> Text(
                "Could not decide: ${outcome?.exceptionOrNull()?.message ?: "unknown error"}",
                fontSize = TypeScale.small,
                color = colors.error,
            )
            result == null && stitched == null && dataEvolution -> Text("This file is read alone — no other file shares its first row id.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            result == null && stitched != null -> {
                // The row as a data-evolution read returns it: a column from a patch is named with its file.
                Text(
                    "Read as: " + stitched.cells.entries.joinToString(", ") { (column, value) ->
                        "$column=${value ?: "null"}" + (stitched.sourceOf[column]?.takeIf { it != fileName }?.let { " (from ${fileNameFromPath(it)})" } ?: "")
                    } + ".",
                    fontSize = TypeScale.small,
                    fontWeight = FontWeight.Bold,
                )
                Text("This file's own cells are above.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            }
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
