package ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import model.GraphModel
import model.GraphNode
import model.ManifestPruneResult
import model.PredicateOp
import model.PredicateOutcome
import model.PrunableColumn
import model.ScanPredicate
import model.TermEffect
import model.FileFate
import model.FilePruneResult
import model.evaluateScan
import model.prunableColumns

/**
 * Enter a scan filter; see which manifests it would let a query skip, and why.
 *
 * The input is a form rather than a `WHERE` clause on purpose. Every field here is one a
 * partition reads, offered from the table's own specs with its own type, so there is no parser to
 * disagree with a query engine's and no way to name a column that cannot prune anything. A typed
 * clause would be more familiar and is worth having later; it would also mean a second place
 * where a literal is read, and this one has to be right first.
 *
 * The verdict is asymmetric and the wording keeps it that way. **Skipped** is a proof: some term
 * showed the manifest's own recorded bounds cannot contain a matching row. **Would be read** is
 * only the absence of such a proof — it does not say the manifest holds anything.
 */
@Composable
fun ScanPruningSection(graph: GraphModel, predicates: List<ScanPredicate>, onChange: (List<ScanPredicate>) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val columns = remember(graph) { prunableColumns(graph) }
    if (columns.isEmpty()) {
        Section("Scan Pruning") {
            Text(
                "Nothing here records a bound: no partition summary and no column statistics, so " +
                    "there is nothing a predicate could be intersected with. Every scan reads " +
                    "every file.",
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
            )
        }
        return
    }

    Section("Scan Pruning") {
        Text(
            "Iceberg prunes twice: a manifest is ruled out by the partition bounds its list records, " +
                "then a file by the column bounds it records about itself. A query reports how many " +
                "files it read and never which it skipped. Enter the filter and this says which — " +
                "and which term did it.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        // Worth saying once, above the form: an unpartitioned table used to get "nothing to do
        // here", which is true of the manifest stage and wrong about the question the reader
        // arrived with.
        if (columns.none { it.prunesManifests }) {
            Text(
                "No column here prunes a manifest — nothing is partitioned on one a range can be " +
                    "intersected with. File bounds still apply.",
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        // The three controls are unlabelled pills on their own — a reader sees `d` and `=` and has no
        // way to know which is the column and which the operator, nor that either opens a menu. The
        // header names them once, above the first row, the way the field it names sits above it.
        if (predicates.isNotEmpty()) {
            DisableSelection {
                Row(modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp)) {
                    FormLabel("Field", COLUMN_PICKER_WIDTH)
                    Spacer(Modifier.width(6.dp))
                    FormLabel("Condition", OP_PICKER_WIDTH)
                    Spacer(Modifier.width(6.dp))
                    FormLabel("Value", LITERAL_FIELD_WIDTH)
                }
            }
        }

        predicates.forEachIndexed { index, predicate ->
            PredicateRow(
                predicate = predicate,
                columns = columns,
                onChange = { updated -> onChange(predicates.toMutableList().also { it[index] = updated }) },
                onRemove = { onChange(predicates.toMutableList().also { it.removeAt(index) }) },
            )
        }

        DisableSelection {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        val first = columns.firstOrNull { it.isPrunable } ?: columns.first()
                        onChange(predicates + ScanPredicate(first.name, PredicateOp.EQ, ""))
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) { Text(if (predicates.isEmpty()) "Add a condition" else "Add another", fontSize = TypeScale.small) }
                if (predicates.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { onChange(emptyList()) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) { Text("Clear", fontSize = TypeScale.small) }
                }
            }
        }

        if (predicates.isEmpty()) return@Section

        val plan = remember(graph, predicates) { evaluateScan(graph, predicates) }
        val results = plan.manifests
        val manifests = remember(graph) { graph.nodes.filterIsInstance<GraphNode.ManifestNode>() }
        val files = remember(graph) { graph.nodes.filterIsInstance<GraphNode.FileNode>() }
        val unevaluated = manifests.count { results[it.id]?.isUnevaluated == true }

        Spacer(Modifier.height(8.dp))
        // The file line first: it is the number the reader came for. A scan reports how many files
        // it read and never which, and the manifest count is the intermediate step that produced
        // it — worth showing, and worth showing second.
        Text(
            "Would read ${formatCount(plan.readFiles)} of ${formatCounted(files.size, "data file")} drawn",
            fontSize = TypeScale.body,
            fontWeight = FontWeight.Bold,
        )
        // One clause per stage, in the order a scan runs them, so the two numbers add up in front
        // of the reader rather than needing to be reconciled after.
        Text(
            "${formatCount(plan.skippedManifests)} of ${formatCounted(manifests.size, "manifest")} " +
                "ruled out, so ${formatCounted(plan.unreachedFiles, "file")} never opened. " +
                "${formatCounted(plan.skippedFiles, "file")} ruled out by their own bounds.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
        )
        // Named separately because a manifest nothing could be evaluated against is not a manifest a
        // scan decided to read — folding the two together would overstate what this screen knows.
        if (unevaluated > 0) {
            Text(
                "${formatCounted(unevaluated, "manifest")} could not be evaluated at all; " +
                    "the reason is on each row.",
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
            )
        }

        val skippedColor = verdictSkippedColor()
        val unevaluatedColor = verdictUnevaluatedColor()

        Spacer(Modifier.height(10.dp))
        Text("Manifests", fontSize = TypeScale.body, fontWeight = FontWeight.Bold)
        Text(
            "Ruled out by the partition summaries the manifest list records, before the manifest " +
                "is opened.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        WideTable(
            headers = listOf("Verdict", "Manifest", "Because"),
            // 634dp of table in a panel that is rarely wider: the reason is the payload, and a
            // payload only reachable by dragging a horizontal scrollbar is a payload most readers
            // never see. The two identifier columns are sized to their longest value and no more.
            columnWidths = listOf(100.dp, 100.dp, 400.dp),
            rows = manifests.map { manifest ->
                val result = results[manifest.id]
                listOf(
                    when {
                        result == null -> "would be read"
                        result.isSkipped -> "SKIPPED"
                        result.isUnevaluated -> "not evaluated"
                        else -> "would be read"
                    },
                    "MANIFEST ${manifest.simpleId}",
                    result.summarise(),
                )
            },
            leadCellColors = manifests.map { manifest ->
                val result = results[manifest.id]
                when {
                    result?.isSkipped == true -> skippedColor
                    result?.isUnevaluated == true -> unevaluatedColor
                    // No colour and no weight. This is the ordinary row, and a column where
                    // every cell is bold has spent its emphasis before the exception arrives.
                    else -> null
                }
            },
        )

        Spacer(Modifier.height(12.dp))
        Text("Data files", fontSize = TypeScale.body, fontWeight = FontWeight.Bold)
        Text(
            "Ruled out by the bounds each file records about its own columns — which every column " +
                "carries, partitioned or not. This is the stage that answers a file count.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        val unreachedColor = colors.onSurfaceVariant.copy(alpha = 0.7f)
        WideTable(
            headers = listOf("Verdict", "File", "Because"),
            columnWidths = listOf(110.dp, 90.dp, 400.dp),
            rows = files.map { file ->
                val result = plan.files[file.id]
                listOf(
                    when (result?.fate) {
                        FileFate.SKIPPED -> "SKIPPED"
                        FileFate.NOT_REACHED -> "not reached"
                        FileFate.UNEVALUATED -> "not evaluated"
                        else -> "would be read"
                    },
                    "FILE ${file.simpleId}",
                    result.summarise(),
                )
            },
            leadCellColors = files.map { file ->
                when (plan.files[file.id]?.fate) {
                    FileFate.SKIPPED -> skippedColor
                    // Dimmer than the others on purpose: it is not this file's verdict. The row
                    // is here so the count adds up, not because anything was decided about it.
                    FileFate.NOT_REACHED -> unreachedColor
                    FileFate.UNEVALUATED -> unevaluatedColor
                    else -> null
                }
            },
        )
    }
}

private val COLUMN_PICKER_WIDTH = 150.dp
private val OP_PICKER_WIDTH = 110.dp
private val LITERAL_FIELD_WIDTH = 190.dp

/** Names one form control, above it and left-aligned with it. */
@Composable
private fun FormLabel(text: String, width: Dp) {
    Text(
        text,
        modifier = Modifier.width(width),
        fontSize = TypeScale.micro,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The one sentence that explains a manifest's verdict: the proof if there is one, else the gap.
 *
 * Each is prefixed with the partition field rather than the whole condition. The condition is
 * already on screen in the form above, and the reason names the literal itself — printing the
 * condition too put `2024-03-06` in the cell three times. The field is the part that is not
 * anywhere else: one term over `ts` is evaluated separately against `ts_year`, `ts_month` and
 * `ts_hour`, and they do not all reach the same verdict.
 */
/**
 * The one sentence for a file's verdict.
 *
 * `not reached` says whose decision it was, because the file's own bounds are irrelevant once the
 * manifest above it is gone — printing what its bounds happen to say would invite the reader to
 * credit the wrong term.
 */
private fun FilePruneResult?.summarise(): String {
    if (this == null) return "no filter"
    if (fate == FileFate.NOT_REACHED) return "its manifest was ruled out, so a scan never opens it"
    return outcomes.summarise()
}

private fun ManifestPruneResult?.summarise(): String =
    if (this == null) "no filter" else outcomes.summarise()

/**
 * A proof if there is one, otherwise every term that did not produce one.
 *
 * A term that **could not be evaluated** is kept beside the ones that were, rather than dropped
 * as soon as some other term reports a range it checked. It is the more interesting of the two:
 * "would be read" earned by checking is a different statement from "would be read" because
 * nothing here turns on that column, and a row showing only the first hides the second. On
 * `parted` this is the whole difference between the two tables — `id` is bucketed, so the
 * manifest stage cannot use it and the file stage can.
 */
private fun List<PredicateOutcome>.summarise(): String {
    fun PredicateOutcome.line() = "${fieldName ?: predicate.column} — $reason"
    firstOrNull { it.effect == TermEffect.SKIPS }?.let { return it.line() }
    return joinToString("; ") { it.line() }
}

@Composable
private fun PredicateRow(
    predicate: ScanPredicate,
    columns: List<PrunableColumn>,
    onChange: (ScanPredicate) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val column = columns.firstOrNull { it.name == predicate.column }

    // The panel is inside a SelectionContainer so its findings can be copied; a text field there
    // fights the selection gesture for the same drag. The controls opt out, the findings do not.
    DisableSelection {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dropdown(
            label = predicate.column,
            width = COLUMN_PICKER_WIDTH,
            options = columns.map { it.name },
        ) { onChange(predicate.copy(column = it)) }
        Spacer(Modifier.width(6.dp))
        Dropdown(
            label = predicate.op.symbol,
            width = OP_PICKER_WIDTH,
            options = PredicateOp.entries.map { it.symbol },
        ) { symbol -> onChange(predicate.copy(op = PredicateOp.entries.first { it.symbol == symbol })) }
        Spacer(Modifier.width(6.dp))
        if (predicate.op.takesLiteral) {
            OutlinedTextField(
                value = predicate.literal,
                onValueChange = { onChange(predicate.copy(literal = it)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                placeholder = { Text(column?.type?.typeName ?: "value", fontSize = TypeScale.small) },
                modifier = Modifier.width(LITERAL_FIELD_WIDTH),
            )
        } else {
            Spacer(Modifier.width(LITERAL_FIELD_WIDTH))
        }
        Spacer(Modifier.width(6.dp))
        TextButton(onClick = onRemove, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
            Text("Remove", fontSize = TypeScale.small)
        }
    }
    }
    // Said next to the field rather than in the verdict, because it decides whether entering a
    // value here is worth anything at all.
    if (column != null && !column.isPrunable) {
        Text(
            "partitioned only by ${column.transforms.joinToString(", ")}, which cannot prune — " +
                "every manifest will come back as read",
            fontSize = TypeScale.micro,
            color = verdictUnevaluatedColor(),
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
        )
    }
}

@Composable
private fun Dropdown(label: String, width: Dp, options: List<String>, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            modifier = Modifier.width(width),
        ) {
            // Left-aligned with the caret pinned right: centred text in a pill reads as a button
            // whose label happens to be `d`, and gives no sign that pressing it opens a list.
            Text(label, fontSize = TypeScale.small, maxLines = 1, modifier = Modifier.weight(1f))
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = "open the list",
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, fontSize = TypeScale.small) },
                    onClick = {
                        onPick(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
