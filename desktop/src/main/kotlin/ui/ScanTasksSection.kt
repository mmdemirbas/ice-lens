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
import model.DataFileContent
import model.GraphModel
import model.GraphNode
import model.ManifestContent
import model.ScanFilter
import model.ScanTaskOptions
import model.ScanTaskFile
import model.ScanTaskPlan
import model.adjustedSplitSize
import model.evaluateScan
import model.isEmpty
import model.newestIcebergMetadata
import model.normalizeFilePath
import model.planScanTasks
import model.render
import model.scanTaskFiles

/** The most task entries listed; a table of thousands of files is thousands of rows nobody scrolls, and the headline carries the count. */
private const val MAX_ENTRY_ROWS = 200

/**
 * `spark.sql.shuffle.partitions`' default, which is the parallelism `adjustSplitSize` sees on a
 * session that set neither it nor `spark.default.parallelism` — and what the oracle ran at.
 */
private const val DEFAULT_PARALLELISM = 200

private val PARALLELISM_FIELD_MAX_WIDTH = 200.dp

/**
 * How many tasks a read of this snapshot takes, the way `TableScanUtil.planTasks` plans them —
 * see [planScanTasks] for the rules. It reads the same live set and delete pairing the sections
 * above it already walked, under the table's `read.split.*` off the newest metadata, and lists
 * one row per task with the file ranges it holds.
 *
 * Two things the section has to say beside the count. The **parallelism** decides Spark's
 * answer: under `read.split.adaptive-size.enabled` the target shrinks when the scan has fewer
 * target-size splits than the job's parallelism, so a table small enough for one task at
 * 128 MiB is read as a task per 16 MiB of weight — the field is seeded with the default 200 and
 * the second figure follows it. And on a snapshot listing **several data manifests** the plan's
 * file order is the worker pool's, so the packing shown is one of the packings Iceberg produces;
 * the count holds to within a task, and the section says so rather than printing a packing as
 * the packing.
 *
 * With the table panel's filter set, a second plan takes the files the scan under it leaves,
 * which is [evaluateScan] over the drawn graph — the `Rewrite` section's `where => …` rule, and
 * for the same reason: a filtered read plans `newScan().filter(…).planFiles()`, and a file the
 * pruning rules out is never a split.
 */
@Composable
internal fun ScanTasksSection(node: GraphNode.SnapshotNode, graph: GraphModel, scanFilter: ScanFilter = ScanFilter.of(emptyList())) {
    val colors = MaterialTheme.colorScheme
    val latest = graph.newestIcebergMetadata()
    val options = ScanTaskOptions.from(latest?.properties.orEmpty())
    val live = node.liveFiles
    val reach = node.deleteReach.orEmpty()
    val files = live?.let { scanTaskFiles(it, reach) }
    val plan = files?.let { planScanTasks(it, options) }
    var parallelismText by remember { mutableStateOf("$DEFAULT_PARALLELISM") }
    val parallelism = parallelismText.trim().replace(",", "").toIntOrNull()?.takeIf { it > 0 }
    val dataManifests = node.manifestList.count { (it.content ?: ManifestContent.DATA) == ManifestContent.DATA }
    Section("Scan Tasks" + (plan?.let { " (${formatCount(it.tasks.size)})" } ?: "")) {
        Text(
            "How many tasks a read of this snapshot takes, the way TableScanUtil.planTasks plans them: " +
                "a Parquet, ORC or Avro file with well-defined split_offsets is one split per row group " +
                "whatever the target size, any other file is cut into read.split.target-size " +
                "(${formatBytes(options.splitSize)}) slices; a split weighs its bytes plus its paired delete " +
                "files' content bytes, or (1 + delete files) × read.split.open-file-cost " +
                "(${formatBytes(options.openFileCost)}), whichever is more — so a small file, and a small row " +
                "group, costs a task ${formatBytes(options.openFileCost)} whatever it holds; the splits are " +
                "bin-packed to the target with read.split.planning-lookback (${options.lookback}) bins open, " +
                "the heaviest closed first, and a task is a bin.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (live == null || files == null || plan == null) {
            Text("Not readable here — this snapshot's manifests are not retained.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            return@Section
        }
        ScanTaskPlanBody(files, plan, options, parallelism, dataManifests, headline = "A read of this snapshot") {
            // The field sits on the line it decides: Spark's figure follows it, and nothing else does.
            OutlinedTextField(
                value = parallelismText,
                onValueChange = { parallelismText = it },
                singleLine = true,
                isError = parallelism == null,
                label = { Text("Spark parallelism", fontSize = TypeScale.small) },
                placeholder = { Text("max(spark.default.parallelism, spark.sql.shuffle.partitions)", fontSize = TypeScale.small) },
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp).widthIn(max = PARALLELISM_FIELD_MAX_WIDTH).fillMaxWidth(),
            )
            if (parallelism == null) {
                Text(
                    "Not a parallelism this reads: a positive whole number, the greater of spark.default.parallelism and spark.sql.shuffle.partitions on the session.",
                    fontSize = TypeScale.small,
                    color = colors.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (!scanFilter.isEmpty()) {
            // A filtered read plans over the files the scan leaves; the pruning is the drawn graph's,
            // so a live file that is not drawn has no verdict and is read.
            val scan = remember(graph, scanFilter) { evaluateScan(graph, scanFilter) }
            val ruledOut = remember(scan) { scan.ruledOutFileKeys(graph) }
            val drawn = remember(graph) {
                graph.nodes.asSequence().filterIsInstance<GraphNode.FileNode>().mapNotNull { it.data.filePath?.let(::normalizeFilePath) }.toSet()
            }
            val left = live.filter { it.content != DataFileContent.DATA || normalizeFilePath(it.path) !in ruledOut }
            val undrawn = left.count { it.content == DataFileContent.DATA && normalizeFilePath(it.path) !in drawn }
            val leftFiles = scanTaskFiles(left, reach)
            val filtered = planScanTasks(leftFiles, options)
            Text(
                "With the filter ${scanFilter.render()}: a read plans newScan().filter(…).planFiles(), so only the files " +
                    "the partition summaries and the bounds leave are split and packed — " +
                    "${formatCounted(filtered.dataFiles, "data file")} of ${plan.dataFiles}, " +
                    "${formatCounted(plan.dataFiles - filtered.dataFiles, "file")} ruled out" +
                    (if (undrawn > 0) ", $undrawn not drawn and so read without a verdict" else "") + ".",
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
            )
            ScanTaskPlanBody(leftFiles, filtered, options, parallelism, dataManifests, headline = "A read under the filter")
        }
    }
}

/**
 * The plan's headline, Spark's partition count under it, the packing-order caveat where it
 * applies, and one row per task. [parallelismField] is drawn on the Spark line it decides, by
 * the one body of the two that owns it.
 */
@Composable
private fun ScanTaskPlanBody(
    files: List<ScanTaskFile>,
    plan: ScanTaskPlan,
    options: ScanTaskOptions,
    parallelism: Int?,
    dataManifests: Int,
    headline: String,
    parallelismField: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val deleteFiles = plan.splits.groupBy { it.path }.values.sumOf { it.first().deleteFiles }
    Text(
        "$headline takes ${plan.describe}.",
        fontSize = TypeScale.body,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp),
    )
    Text(
        "${formatBytes(plan.scanBytes)} of data and paired deletes, weighed at ${formatBytes(plan.tasks.sumOf { it.weight })} " +
            "with the open-file cost" +
            (if (deleteFiles > 0) "; ${formatCounted(deleteFiles, "delete file")} paired, ${if (deleteFiles == 1) "paying a cost on every split of its data file" else "each paying a cost on every split of its data file"}" else "") + ".",
        fontSize = TypeScale.small,
        color = colors.onSurfaceVariant,
    )
    parallelismField?.invoke()
    if (parallelism != null) {
        val adjusted = adjustedSplitSize(plan.scanBytes, parallelism, options.splitSize)
        val spark = if (adjusted == options.splitSize) plan else planScanTasks(files, options, adjusted)
        Text(
            "Spark at a parallelism of ${formatCount(parallelism)}: " + if (adjusted == options.splitSize) {
                "the scan has at least that many target-size splits, so the adaptive size leaves the target at " +
                    "${formatBytes(options.splitSize)} and the read is ${formatCounted(plan.tasks.size, "partition")}."
            } else {
                "fewer target-size splits than that, so read.split.adaptive-size.enabled shrinks the target to " +
                    "${formatBytes(adjusted)} — the greater of the scan's bytes over the parallelism and 16 MiB — and the read is " +
                    "${formatCounted(spark.tasks.size, "partition")}."
            },
            fontSize = TypeScale.small,
            color = if (adjusted != options.splitSize && spark.tasks.size != plan.tasks.size) verdictUnevaluatedColor() else colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    if (dataManifests > 1 && plan.tasks.size > 1) {
        Text(
            "This snapshot lists ${formatCounted(dataManifests, "data manifest")}, and ManifestGroup.planFiles returns their " +
                "files in the order the worker pool finished them, so the packing follows an order this cannot know: " +
                "which file sits in which task, and the count by a task, can differ between runs. This is one packing.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    if (plan.tasks.isEmpty()) return
    // One row per entry — a task's ranges are what the reader came to see, and a cell holding
    // twenty of them would be clipped at the table's four lines. The task's figures repeat on
    // each of its rows so a column reads by scanning down it.
    val rows = plan.tasks.flatMapIndexed { i, t -> t.entries.map { e -> Triple(i + 1, t, e) } }
    val shown = rows.take(MAX_ENTRY_ROWS)
    if (shown.size < rows.size) {
        Text(
            "The first ${formatCount(shown.size)} of ${formatCounted(rows.size, "entry", "entries")} listed, " +
                "through task ${shown.last().first} of ${plan.tasks.size}.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    WideTable(
        headers = listOf("Task", "Task Weight", "Task Files", "File", "Offset", "Length", "Delete Files"),
        columnWidths = listOf(60.dp, 110.dp, 80.dp, 320.dp, 100.dp, 100.dp, 90.dp),
        rows = shown.map { (task, t, e) ->
            listOf(
                "$task",
                formatBytes(t.weight),
                "${t.files}",
                fileNameFromPath(e.path),
                formatCount(e.start),
                formatCount(e.length),
                "${e.deleteFiles}",
            )
        },
    )
}
