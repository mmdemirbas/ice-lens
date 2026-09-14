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
import model.FileLayoutCheck
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
 * column leads so a disagreement is findable without reading every row. The entry's size and
 * split offsets are put beside the file's own size and row groups on the same read
 * ([FileLayoutCheck]), each on a line of its own above the table.
 */
@Composable
internal fun StatsCheckSection(
    nodeId: String,
    path: String?,
    recorded: List<RecordedColumnStats>,
    recordedRows: Long?,
    /** The table's name mapping, for a file recording no field ids — Iceberg only. */
    nameMapping: NameMapping? = null,
    /** The entry's `file_size_in_bytes` (`_FILE_SIZE`) and `split_offsets`; Paimon records no offsets. */
    recordedSize: Long? = null,
    recordedSplitOffsets: List<Long>? = null,
    startRequested: Boolean = false,
    onSettled: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    if (path.isNullOrBlank() || recorded.isEmpty()) return

    var requested by remember(nodeId) { mutableStateOf(startRequested) }
    val outcome by produceState<Result<StatsCheckResult>?>(null, nodeId, requested) {
        value = null
        if (requested) {
            value = withContext(Dispatchers.IO) { runCatching { StatsCheckReader.check(path, recorded, recordedRows, nameMapping, recordedSize, recordedSplitOffsets) } }
            onSettled()
        }
    }

    Section("Statistics Check") {
        Text(
            "The bounds and counts above are what a scan prunes on without opening the file, so " +
                "nothing on the read path checks them. This reads the file once and puts each " +
                "recorded figure beside the same figure counted from its rows. A bound may be wider " +
                "than the values — a string bound is truncated and its upper one incremented — so a " +
                "bound disagrees only when a row lies outside it; a count is the count. The entry's " +
                "file size and split offsets are put beside the file's own size and row groups on " +
                "the same read: a reader opens the file at the recorded length, and a scan cuts it " +
                "into tasks at the recorded offsets.",
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
                            bad > 0 -> "$bad of ${formatCount(result.figures.toLong())} figures disagree with the file."
                            else -> "All ${formatCount(result.figures.toLong())} figures agree with the file and its ${formatCount(result.rows)} rows."
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
                    result.layout?.let { layout ->
                        sizeLine(layout)?.let { line ->
                            Text(line, fontSize = TypeScale.small, color = if (layout.sizeAgrees == false) colors.error else colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                        }
                        Text(rowGroupsLine(layout), fontSize = TypeScale.small, color = if (layout.offsetsAgree == false) colors.error else colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
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

/** The entry's size beside the file's, or which of the two is missing; null when neither is known. */
internal fun sizeLine(layout: FileLayoutCheck): String? {
    val recorded = layout.recordedSize
    val onDisk = layout.sizeOnDisk
    return when {
        recorded == null && onDisk == null -> null
        onDisk == null -> "Size: ${formatCount(recorded)} B recorded; the size on disk could not be read."
        recorded == null -> "Size: ${formatCount(onDisk)} B on disk, none recorded."
        layout.sizeAgrees == true -> "Size: ${formatCount(onDisk)} B on disk, the same recorded."
        else -> "Size: ${formatCount(onDisk)} B on disk, ${formatCount(recorded)} B recorded — a reader opens the file at the recorded length and looks for the footer by it."
    }
}

/**
 * The row groups the footer lists beside the offsets the entry records — the same or not, and
 * whether Iceberg would use the list at all. Lists past [MAX_OFFSETS_SHOWN] are cut with the count.
 */
internal fun rowGroupsLine(layout: FileLayoutCheck): String {
    fun shown(xs: List<Long>): String =
        xs.take(MAX_OFFSETS_SHOWN).joinToString(", ") { formatCount(it) } + (if (xs.size > MAX_OFFSETS_SHOWN) " … and ${xs.size - MAX_OFFSETS_SHOWN} more" else "")
    val recorded = layout.recordedSplitOffsets
    val groups = layout.rowGroups
    val line = StringBuilder()
    if (groups == null) {
        line.append("Row groups: none DuckDB lists — an Avro file is read in blocks")
        line.append(if (recorded == null) ", and no split_offsets is recorded." else ", so the split_offsets recorded (${shown(recorded)}) are not checked.")
    } else {
        line.append("Row groups: ${formatCount(groups.size)}")
        when (groups.size) {
            0 -> Unit
            1 -> line.append(", of ${formatCounted(groups.single().rows.toInt(), "row")}, starting at ${formatCount(groups.single().start)}")
            else -> line.append(", of ${shown(groups.map { it.rows })} rows, starting at ${shown(groups.map { it.start })}")
        }
        line.append(
            when {
                recorded == null -> "; no split_offsets recorded, so a scan splits the file by size alone."
                layout.offsetsAgree == true -> "; split_offsets records the same."
                else -> "; split_offsets records ${shown(recorded)} — a scan makes one task per recorded offset, from it to the next."
            },
        )
    }
    if (recorded != null && layout.offsetsUsable == false) {
        line.append(" Iceberg drops the list before planning: its last offset is not below the recorded file size (BaseFile.hasWellDefinedOffsets).")
    }
    return line.toString()
}

private const val MAX_OFFSETS_SHOWN = 16
