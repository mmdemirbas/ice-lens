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
import model.GraphNode
import model.IntegrityReport
import model.MAX_CLOSURE_CHECKS

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
 */
@Composable
internal fun IntegritySection(
    node: GraphNode.TableNode,
    startRequested: Boolean = false,
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
            onSettled()
        }
    }
    val report = outcome?.getOrNull()
    val title = "Integrity" + when {
        report == null -> ""
        report.findings.isEmpty() -> " — agrees"
        else -> " — ${formatCounted(report.findings.size, "disagreement")}"
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
            }
        }
    }
}
