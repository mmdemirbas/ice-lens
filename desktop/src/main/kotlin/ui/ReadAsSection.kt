package ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import model.GraphNode
import model.ProjectedCellSource
import model.ProjectedRow

/**
 * An Iceberg data row as a read of the table returns it — see [ProjectedRow]. The card prints
 * the file's columns under the file's names, and on a table that evolved after the file was
 * written that is not what a query returns: a renamed column reads under its new name, a
 * dropped one not at all, an added one as its initial default or null. Drawn at once — the
 * projection opens the file's footer for its field ids, which the row's own read has already
 * paid for — and only when it differs from the file, since a section that says "the same" on
 * every row of every table is noise; the title carries the count so the difference is findable.
 */
@Composable
internal fun ReadAsSection(node: GraphNode.RowNode, onSettled: () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    if (!node.readAs.isPresent) return
    val read by produceState<ProjectedRow?>(null, node.id) {
        value = withContext(Dispatchers.IO) { runCatching { node.readAs.value }.getOrNull() }
        onSettled()
    }
    val projected = read ?: return
    if (!projected.differsFromFile) return

    val changed = verdictUnevaluatedColor()
    Section("Read As (${projected.cells.size} columns)") {
        Text(
            projected.describe.replaceFirstChar { it.uppercase() } + ". Columns are placed by field id against the table's current schema, " +
                "which is how a query reads the file; the card above shows the file's own columns.",
            fontSize = TypeScale.small,
            fontWeight = FontWeight.Bold,
            color = colors.onSurface,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        WideTable(
            headers = listOf("Column", "Value", "From", "Type", "Field ID"),
            columnWidths = listOf(160.dp, 200.dp, 300.dp, 110.dp, 70.dp),
            rows = projected.cells.map { cell ->
                val from = when (cell.source) {
                    ProjectedCellSource.FILE -> if (cell.fileColumn == cell.name) "the file" else "the file's ${cell.fileColumn}, renamed since"
                    ProjectedCellSource.INITIAL_DEFAULT -> "initial default; the file predates the column"
                    ProjectedCellSource.ABSENT -> "absent from the file; read as null"
                }
                listOf(cell.name, cell.value, from, cell.type, "${cell.fieldId}")
            },
            leadCellColors = projected.cells.map { if (it.source == ProjectedCellSource.FILE && it.fileColumn == it.name) null else changed },
        )
        if (projected.dropped.isNotEmpty()) {
            Text(
                "Not read — dropped from the table since the file was written: " +
                    projected.dropped.joinToString(", ") { "${it.fileColumn} = ${it.value} (field ${it.fieldId})" } + ".",
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (projected.unmatched.isNotEmpty()) {
            Text(
                "Not placed — the file records no field id for: ${projected.unmatched.joinToString(", ")}. A read resolves such a column through the table's name mapping, which this does not apply.",
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
