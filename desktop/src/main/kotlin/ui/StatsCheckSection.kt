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
import model.NameMapping
import model.RecordedColumnStats
import model.StatsCheckResult
import model.StatsVerdict
import service.StatsCheckReader

/**
 * A data file's recorded column statistics against the same figures counted from its rows,
 * behind a click — the file read the table-level check leaves out, on either format.
 *
 * The pruning section trusts these figures without opening the file, which is what a scan does
 * too, so a bound the writer got wrong is invisible until a row goes missing from a query. The
 * comparison is one-sided on the bounds (a bound may be wider than the values, since Iceberg
 * truncates string metrics and increments the upper one) and exact on the counts; the verdict
 * column leads so a disagreement is findable without reading every row.
 */
@Composable
internal fun StatsCheckSection(
    nodeId: String,
    path: String?,
    recorded: List<RecordedColumnStats>,
    recordedRows: Long?,
    /** The table's name mapping, for a file recording no field ids — Iceberg only. */
    nameMapping: NameMapping? = null,
    startRequested: Boolean = false,
    onSettled: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    if (path.isNullOrBlank() || recorded.isEmpty()) return

    var requested by remember(nodeId) { mutableStateOf(startRequested) }
    val outcome by produceState<Result<StatsCheckResult>?>(null, nodeId, requested) {
        value = null
        if (requested) {
            value = withContext(Dispatchers.IO) { runCatching { StatsCheckReader.check(path, recorded, recordedRows, nameMapping) } }
            onSettled()
        }
    }

    Section("Statistics Check") {
        Text(
            "The bounds and counts above are what a scan prunes on without opening the file, so " +
                "nothing on the read path checks them. This reads the file once and puts each " +
                "recorded figure beside the same figure counted from its rows. A bound may be wider " +
                "than the values — a string bound is truncated and its upper one incremented — so a " +
                "bound disagrees only when a row lies outside it; a count is the count.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        when {
            !requested -> OutlinedButton(onClick = { requested = true }) { Text("Read the file — bounds and counts against its rows") }
            outcome == null -> Text("Reading ${fileNameFromPath(path)}…", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            else -> outcome?.fold(
                onSuccess = { result ->
                    val bad = result.problems.size
                    Text(
                        when {
                            bad > 0 -> "$bad of ${formatCount(result.figures.toLong())} figures disagree with the file's rows."
                            else -> "All ${formatCount(result.figures.toLong())} figures agree with the file's ${formatCount(result.rows)} rows."
                        },
                        fontSize = TypeScale.body,
                        fontWeight = FontWeight.Medium,
                        color = if (bad > 0) colors.error else colors.onSurface,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    result.recordedRows?.let { recordedRows ->
                        Text(
                            "Rows: ${formatCount(result.rows)} counted, ${formatCount(recordedRows)} recorded" +
                                if (result.rowsAgree == true) "." else " — the entry's row count is wrong.",
                            fontSize = TypeScale.small,
                            color = if (result.rowsAgree == false) colors.error else colors.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    WideTable(
                        headers = listOf("Verdict", "Column", "Why", "Recorded Low", "Counted Min", "Recorded High", "Counted Max", "Recorded Nulls", "Counted Nulls"),
                        columnWidths = listOf(100.dp, 140.dp, 300.dp, 160.dp, 160.dp, 160.dp, 160.dp, 110.dp, 110.dp),
                        leadCellColors = result.columns.map {
                            when (it.verdict) {
                                StatsVerdict.DISAGREES -> colors.error
                                StatsVerdict.NOT_CHECKED -> verdictUnevaluatedColor()
                                StatsVerdict.AGREES -> null
                            }
                        },
                        rows = result.columns.map { c ->
                            listOf(
                                when (c.verdict) {
                                    StatsVerdict.AGREES -> "agrees"
                                    StatsVerdict.DISAGREES -> "DISAGREES"
                                    StatsVerdict.NOT_CHECKED -> "not checked"
                                },
                                c.column,
                                c.reason,
                                c.recordedLower ?: "N/A",
                                c.actualMin ?: "N/A",
                                c.recordedUpper ?: "N/A",
                                c.actualMax ?: "N/A",
                                c.recordedNulls?.let { formatCount(it) } ?: "N/A",
                                c.actualNulls?.let { formatCount(it) } ?: "N/A",
                            )
                        },
                    )
                },
                onFailure = { e ->
                    Text("Could not read the file: ${e.message ?: e.javaClass.simpleName}", fontSize = TypeScale.small, color = colors.error)
                },
            )
        }
    }
}
