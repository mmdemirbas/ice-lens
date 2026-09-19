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
import model.GraphModel
import model.GraphNode
import model.PaimonLookupFile
import model.PaimonSplitOptions
import model.PaimonSplitPlan
import model.ScanFilter
import model.evaluateScan
import model.isEmpty
import model.paimonSparkPartitions
import model.planPaimonSplits
import model.render

/** The most file rows listed under the splits, and the most partition rows under Spark's; the headline carries the counts. */
private const val MAX_SPLIT_ROWS = 200

/**
 * `sparkContext.defaultParallelism` on a session that set neither `spark.sql.leafNodeDefaultParallelism`
 * nor `spark.sql.files.minPartitionNum` is the executor cores; the field is seeded with what
 * `spark.sql.shuffle.partitions` defaults to, the same seed as the Iceberg section's.
 */
private const val DEFAULT_PARALLELISM = 200

private val PARALLELISM_FIELD_MAX_WIDTH = 200.dp

/**
 * The splits a batch read of this snapshot takes — see [planPaimonSplits] for the three
 * generators and the packing — and the input partitions Spark repacks the raw ones into
 * ([paimonSparkPartitions]). The Paimon twin of the Iceberg snapshot panel's `Scan Tasks`: the
 * same headline, the same parallelism field on the line it decides, one row per file under its
 * split, and with the table panel's filter set a second plan over the files the scan leaves.
 * It reads the same deferred replay the merged count and the compaction sections read.
 */
@Composable
internal fun PaimonScanSplitsSection(node: GraphNode.PaimonSnapshotNode, graph: GraphModel, scanFilter: ScanFilter = ScanFilter.of(emptyList())) {
    val colors = MaterialTheme.colorScheme
    if (!node.readInput.isPresent) return
    val input = node.readInput.value
    val options = input?.let { PaimonSplitOptions.from(it.schema.options) } ?: PaimonSplitOptions()
    val plan = input?.let { planPaimonSplits(it, options) }
    var parallelismText by remember { mutableStateOf("$DEFAULT_PARALLELISM") }
    val parallelism = parallelismText.trim().replace(",", "").toIntOrNull()?.takeIf { it > 0 }
    Section("Scan Splits" + (plan?.let { " (${formatCount(it.splits.size)})" } ?: "")) {
        Text(
            "How many splits a batch read of this snapshot takes, the way SnapshotReaderImpl.generateSplits plans " +
                "them: each bucket's files go through the table's SplitGenerator — a primary-key table's packs the " +
                "files whole when every one is above level 0 with no -D row and the table has deletion vectors, is " +
                "first-row, or holds them all at one level, and otherwise cuts them into sections of intersecting key " +
                "ranges and packs the sections, a split reading raw (unmerged) only when it holds one file; an append " +
                "table's sorts by minimum sequence number and packs; a data-evolution table's packs the groups sharing a " +
                "first row id. Packing is BinPacking.packForOrdered to source.split.target-size " +
                "(${formatBytes(options.targetSplitSize)}), an item weighing its bytes or source.split.open-file-cost " +
                "(${formatBytes(options.openFileCost)}), whichever is more, and never reordering: an item that would " +
                "carry the split past the target opens the next. A split is one DataSplit, and one task in Flink.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (input == null || plan == null) {
            Text("Not readable here — this snapshot's manifests could not be replayed.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            return@Section
        }
        PaimonSplitPlanBody(plan, parallelism, headline = "A read of this snapshot") {
            OutlinedTextField(
                value = parallelismText,
                onValueChange = { parallelismText = it },
                singleLine = true,
                isError = parallelism == null,
                label = { Text("Spark parallelism", fontSize = TypeScale.small) },
                placeholder = { Text("spark.sql.files.minPartitionNum, else the leaf-node default parallelism", fontSize = TypeScale.small) },
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp).widthIn(max = PARALLELISM_FIELD_MAX_WIDTH).fillMaxWidth(),
            )
            if (parallelism == null) {
                Text(
                    "Not a parallelism this reads: a positive whole number — spark.sql.files.minPartitionNum, else spark.sql.leafNodeDefaultParallelism, else the session's default parallelism.",
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
                graph.nodes.asSequence().filterIsInstance<GraphNode.PaimonDataFileNode>().mapNotNull { it.entry.file?.fileName }.toSet()
            }
            val left = input.readFiles.filter { it.fileName !in ruledOut }
            val undrawn = left.count { it.fileName !in drawn }
            val filtered = planPaimonSplits(input, left, options)
            Text(
                "With the filter ${scanFilter.render()}: only the files the scan leaves are cut and packed — " +
                    "${formatCounted(filtered.files, "data file")} of ${plan.files}, " +
                    "${formatCounted(plan.files - filtered.files, "file")} ruled out" +
                    (if (undrawn > 0) ", $undrawn not drawn and so read without a verdict" else "") + ".",
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
            )
            PaimonSplitPlanBody(filtered, parallelism, headline = "A read under the filter")
        }
    }
}

/** The plan's headline, the rule each bucket went through, Spark's partitions under the field, and one row per file under its split. */
@Composable
private fun PaimonSplitPlanBody(plan: PaimonSplitPlan, parallelism: Int?, headline: String, parallelismField: (@Composable () -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val merged = plan.splits.size - plan.rawSplits
    Text(
        "$headline takes ${plan.describe}.",
        fontSize = TypeScale.body,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp),
    )
    if (plan.splits.isNotEmpty()) {
        Text(
            "${formatBytes(plan.splits.sumOf { it.bytes })} of data, weighed at ${formatBytes(plan.splits.sumOf { it.weight })} with the open-file cost; " +
                plan.rules.joinToString("; ") { it.label } + ".",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
        )
    }
    parallelismField?.invoke()
    if (parallelism != null && plan.splits.isNotEmpty()) {
        val spark = paimonSparkPartitions(plan, parallelism)
        val repacked = spark.partitions.count { it.reshuffled }
        Text(
            "Spark at a parallelism of ${formatCount(parallelism)}: ${formatCounted(spark.partitions.size, "partition")} — " +
                (if (merged > 0) "${formatCounted(merged, "merge split")} taken whole, and " else "") +
                "the raw splits' files repacked into $repacked " +
                "under a bound of ${formatBytes(spark.maxSplitBytes)} (the target, or the raw files' ${formatBytes(spark.rawBytes)} " +
                "with the open cost over the parallelism, whichever is less, never under the open cost)" +
                (if (plan.deletionVectors && plan.vectorLengths.isNotEmpty()) "; a file's vector is charged to its partition and not to the bound" else "") + ".",
            fontSize = TypeScale.small,
            color = if (spark.partitions.size != plan.splits.size) verdictUnevaluatedColor() else colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    if (plan.splits.isEmpty()) return
    val rows = plan.splits.flatMapIndexed { i, s -> s.files.map { f -> Triple(i + 1, s, f) } }
    val shown = rows.take(MAX_SPLIT_ROWS)
    if (shown.size < rows.size) {
        Text(
            "The first ${formatCount(shown.size)} of ${formatCounted(rows.size, "file")} listed, through split ${shown.last().first} of ${plan.splits.size}.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    WideTable(
        headers = listOf("Split", "Read", "Bucket", "Split Weight", "Split Files", "File", "Level", "Bytes", "Vector Bytes"),
        columnWidths = listOf(60.dp, 80.dp, 220.dp, 110.dp, 80.dp, 320.dp, 60.dp, 100.dp, 100.dp),
        rows = shown.map { (split, s, f) ->
            listOf(
                "$split",
                if (s.rawConvertible) "raw" else "merged",
                bucketLabel(f),
                formatBytes(s.weight),
                "${s.files.size}",
                f.fileName,
                f.level?.toString() ?: "—",
                formatBytes(f.fileSize),
                plan.vectorLengths[f.fileName]?.let { formatBytesExact(it) } ?: "—",
            )
        },
    )
}

private fun bucketLabel(f: PaimonLookupFile): String = (if (f.partition.isEmpty()) "" else "${f.partition} ") + "bucket-${f.bucket}"
