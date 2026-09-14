package ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import model.GraphNode
import model.RewriteTablePathCopyKind
import model.RewriteTablePathOptions
import model.RewriteTablePathPlan
import model.planRewriteTablePath

/** The copy list a plan prints before it says how many more there are. */
internal const val MAX_REWRITE_PATH_ROWS = 200

private val PREFIX_FIELD_MAX_WIDTH = 520.dp

/**
 * What `rewrite_table_path(source_prefix, target_prefix)` would stage and list for this table —
 * see [planRewriteTablePath]. Seeded with the prefixes a reader of a copied table has in hand:
 * the location the metadata records, and the directory the table was opened from. It is on the
 * table panel because it is a whole-table operation, and it is a form because the two prefixes
 * are the whole of what the procedure decides by.
 */
@Composable
internal fun RewriteTablePathSection(node: GraphNode.TableNode) {
    val colors = MaterialTheme.colorScheme
    if (!node.rewriteTablePath.isPresent) return
    val recorded = node.summary.location ?: return
    var source by remember(node.id) { mutableStateOf(recorded) }
    var target by remember(node.id) { mutableStateOf(node.summary.tablePath) }
    val plan: RewriteTablePathPlan? = remember(node.id, source, target) {
        node.rewriteTablePath.value?.planRewriteTablePath(RewriteTablePathOptions(source, target))
    }
    val title = when {
        plan == null -> "Rewrite Table Path"
        plan.refusal != null -> "Rewrite Table Path — refused"
        else -> "Rewrite Table Path — would list ${formatCounted(plan.fileCount, "file")}"
    }
    Section(title) {
        Text(
            "What rewrite_table_path(source_prefix, target_prefix) does, the way RewriteTablePathSparkAction " +
                "does it: nothing is copied into place. The metadata is rewritten into a staging directory " +
                "with every path under the source prefix moved under the target — the end version and its " +
                "metadata-log back to the start version, the manifest list of every snapshot the end holds " +
                "and the start does not, every manifest those snapshots list, a positional delete's file_path " +
                "column — and a file-list CSV names what to copy where: the staged files, and the live data " +
                "files and equality deletes as they are. The prefixes are seeded with what a copied table has " +
                "in hand, the location the metadata records and the directory this table was opened from.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        OutlinedTextField(
            value = source, onValueChange = { source = it }, singleLine = true,
            label = { Text("source_prefix", fontSize = TypeScale.small) },
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.widthIn(max = PREFIX_FIELD_MAX_WIDTH).fillMaxWidth(),
        )
        OutlinedTextField(
            value = target, onValueChange = { target = it }, singleLine = true,
            label = { Text("target_prefix", fontSize = TypeScale.small) },
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.widthIn(max = PREFIX_FIELD_MAX_WIDTH).fillMaxWidth().padding(top = 4.dp),
        )
        if (plan == null) {
            Text("The table's versions could not be read.", fontSize = TypeScale.small, color = colors.error, modifier = Modifier.padding(top = 6.dp))
            return@Section
        }
        val refusal = plan.refusal
        if (refusal != null) {
            Text("REFUSED — $refusal", fontSize = TypeScale.small, fontWeight = FontWeight.Bold, color = colors.error, modifier = Modifier.padding(top = 6.dp))
            Text(
                "The action checks the prefixes, then the versions, then partition statistics, then every path a rewritten " +
                    "file carries — a manifest list, a metadata-log entry, a write.*.path property, a manifest, a data file — " +
                    "and refuses the first not under the source prefix; a v3 deletion vector it cannot rebuild at all.",
                fontSize = TypeScale.small, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
            return@Section
        }
        val byKind = RewriteTablePathCopyKind.entries.associateWith { plan.copiesOf(it).size }
        val staged = plan.copies.count { it.staged && it !in plan.notStaged }
        Text(
            "Would stage ${formatCounted(staged, "file")} under ${plan.stagingDir} and list ${formatCounted(plan.fileCount, "file")} to copy: " +
                RewriteTablePathCopyKind.entries.filter { byKind.getValue(it) > 0 }.joinToString(", ") { formatCounted(byKind.getValue(it), it.label) } +
                " — latest_version ${plan.endVersion}, ${formatCounted(plan.versions.size, "version")} rewritten back to " +
                (plan.options.startVersion?.let { "$it (exclusive)" } ?: "the first in the log") + ".",
            fontSize = TypeScale.small,
            fontWeight = FontWeight.Bold,
            color = verdictSkippedColor(),
            modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
        )
        if (plan.notStaged.isNotEmpty()) {
            Text(
                "${formatCounted(plan.notStaged.size, "statistics file")} listed from staging and never written there — the action " +
                    "rewrites the path in metadata.json and stages nothing for it, so a copy tool fails on that line unless the " +
                    "file is put into staging by hand.",
                fontSize = TypeScale.small, color = colors.error, modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        WideTable(
            headers = listOf("Kind", "Copy From", "To"),
            columnWidths = listOf(150.dp, 560.dp, 560.dp),
            leadCellColors = plan.copies.take(MAX_REWRITE_PATH_ROWS).map { if (it in plan.notStaged) colors.error else null },
            rows = plan.copies.take(MAX_REWRITE_PATH_ROWS).map { listOf(it.kind.label + if (it.staged) " (rewritten)" else "", it.from, it.to) },
        )
        if (plan.copies.size > MAX_REWRITE_PATH_ROWS) {
            Text("…and ${formatCount(plan.copies.size - MAX_REWRITE_PATH_ROWS)} more.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
        }
        Text(
            "A DELETED entry keeps its rewritten path in the manifest and is not listed, so the list carries every data file " +
                "any rewritten manifest holds live — what every retained snapshot reads, not the current one alone. Every version " +
                "in the log is rewritten, an older one naming an expired snapshot's list included; that list is not copied, as it " +
                "is not here. Partition statistics refuse the whole call at 1.8.1.",
            fontSize = TypeScale.small, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
        )
    }
}
