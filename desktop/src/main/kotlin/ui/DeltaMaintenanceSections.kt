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
import model.DeltaUnifiedTableModel
import model.DeltaVacuumPlan
import model.LogCleanupFate
import model.VacuumFate
import model.formatBytes
import model.formatCounted
import model.formatOrphanAge
import model.formatRetention
import model.nextCheckpointVersion
import model.planLogCleanup
import model.planOptimize
import model.planVacuum

/** The file list a Delta plan prints before it says how many more there are. */
private const val MAX_DELTA_PLAN_ROWS = 200

/**
 * What `OPTIMIZE` would rewrite — see [model.planDeltaOptimize] for the rules. Drawn from the log
 * alone, so no click: the bins first, the rewritten ones marked, then every live file with why it
 * is a candidate or not.
 */
@Composable
internal fun DeltaOptimizeSection(model: DeltaUnifiedTableModel) {
    val colors = MaterialTheme.colorScheme
    val plan = remember(model) { model.planOptimize() }.getOrNull()
    val title = "Optimize" + when {
        plan == null -> ""
        plan.commits -> " — ${formatCounted(plan.removed.size, "file")} into ${plan.filesAdded}"
        else -> " — nothing to rewrite"
    }
    Section(title) {
        Text(
            "What OPTIMIZE does without ZORDER BY, as OptimizeExecutor plans it: a live file under optimize.minFileSize " +
                "(1 GiB) or whose vector marks over optimize.maxDeletedRowsRatio (5%) of its rows is a candidate; candidates " +
                "are grouped by partition, sorted smallest first and packed into bins of at most optimize.maxFileSize " +
                "(1 GiB), and only a bin of two or more is rewritten — into one file, with dataChange false.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (plan == null) {
            Text("Not planned: the latest version could not be rebuilt.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            return@Section
        }
        if (plan.clustered) {
            Text("A CLUSTER BY table: its OPTIMIZE clusters every file, which this does not plan.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            return@Section
        }
        Text(
            when {
                plan.commits -> "Would rewrite ${formatCounted(plan.removed.size, "file")} (${formatBytes(plan.removedBytes)}) into ${formatCounted(plan.filesAdded, "file")}" +
                    if (plan.leftAlone.isNotEmpty()) "; ${formatCounted(plan.leftAlone.size, "candidate")} alone in a bin stays." else "."
                plan.leftAlone.isNotEmpty() -> "Writes nothing: ${formatCounted(plan.leftAlone.size, "candidate")}, each alone in its bin."
                else -> "Writes nothing: no live file is a candidate."
            },
            fontSize = TypeScale.small,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (plan.bins.isNotEmpty()) {
            WideTable(
                headers = listOf("Bin", "Partition", "Files", "Bytes"),
                rows = plan.bins.map { bin ->
                    listOf(
                        if (bin.rewritten) "rewritten into one" else "left — one file",
                        bin.partitionValues.entries.joinToString(", ") { "${it.key}=${it.value ?: "null"}" }.ifEmpty { "unpartitioned" },
                        bin.files.size.toString(),
                        formatBytes(bin.bytes),
                    )
                },
                columnWidths = listOf(170.dp, 200.dp, 70.dp, 110.dp),
                leadCellColors = plan.bins.map { if (it.rewritten) verdictSkippedColor() else null },
            )
        }
        val files = plan.files.take(MAX_DELTA_PLAN_ROWS)
        WideTable(
            headers = listOf("Candidate", "File", "Size", "Why"),
            rows = files.map { f ->
                listOf(
                    if (f.candidateBecause != null) "yes" else "no",
                    f.add.path.substringAfterLast('/'),
                    formatBytes(f.add.size),
                    f.candidateBecause ?: "at or over optimize.minFileSize, and under the deleted-rows ratio",
                )
            },
            columnWidths = listOf(90.dp, 320.dp, 100.dp, 420.dp),
        )
        if (plan.files.size > files.size) {
            Text("…and ${formatCounted(plan.files.size - files.size, "more file")}.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
        }
    }
}

/**
 * What the next checkpoint's log cleanup would delete — see [model.planLogCleanup]. It reads the
 * modification times of the log's files, a stat each, which is the log listing's cost again.
 */
@Composable
internal fun DeltaLogCleanupSection(model: DeltaUnifiedTableModel) {
    val colors = MaterialTheme.colorScheme
    val next = model.nextCheckpointVersion() ?: return
    // The clock is read once per table: it answers the current time on every call, and a plan
    // keyed on it would be planned again on every frame.
    val clock = LocalExpiryClock.current
    val plan = remember(model) { model.planLogCleanup(clock(), next) }
    val p = plan.getOrNull()
    val title = "Log Cleanup" + when {
        p == null -> ""
        !p.enabled -> " — switched off"
        p.deleted.isNotEmpty() -> " — ${formatCounted(p.deleted.size, "file")} at the next checkpoint"
        else -> " — nothing past retention"
    }
    Section(title) {
        Text(
            "What the cleanup a checkpoint runs deletes from _delta_log/, as MetadataCleanup does it: every commit and " +
                "checkpoint file below the checkpoint's version modified at or before now less delta.logRetentionDuration " +
                "(30 days), rounded down to UTC midnight. Times are first made increasing — a file not after the one " +
                "before it is read as a millisecond after — and a run of such files goes or stays with its last. " +
                "Planned for the next checkpoint, at version $next.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (p == null) {
            Text("Not planned: ${plan.exceptionOrNull()?.message}", fontSize = TypeScale.small, color = colors.error)
            return@Section
        }
        Text(
            when {
                !p.enabled -> "delta.enableExpiredLogCleanup is false: nothing is deleted."
                p.deleted.isEmpty() -> "Deletes nothing: no file below version $next is older than the cutoff, ${java.time.Instant.ofEpochMilli(p.cutoffMs)}."
                else -> "Deletes ${formatCounted(p.deleted.size, "file")}; the earliest version a reader can rebuild after it is ${p.earliestReadableAfter}." +
                    if (p.sidecarsDeleted.isNotEmpty()) " ${formatCounted(p.sidecarsDeleted.size, "sidecar")} no checkpoint left names go with them." else ""
            },
            fontSize = TypeScale.small,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (p.writesCompatCheckpoint) {
            Text(
                "A V2-checkpoint table: before deleting, the cleanup writes a classic checkpoint of the snapshot for readers that cannot read V2.",
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        val rows = p.rows.sortedBy { it.fate != LogCleanupFate.DELETED }.take(MAX_DELTA_PLAN_ROWS)
        WideTable(
            headers = listOf("Fate", "File", "Why", "Modified"),
            rows = rows.map { r ->
                listOf(
                    r.fate.label,
                    r.path.fileName.toString(),
                    r.reason,
                    r.modifiedMs?.let { "${formatOrphanAge(p.nowMs - it)} ago" } ?: "unknown",
                )
            },
            columnWidths = listOf(180.dp, 330.dp, 420.dp, 110.dp),
            leadCellColors = rows.map { r ->
                when (r.fate) {
                    LogCleanupFate.DELETED -> verdictSkippedColor()
                    LogCleanupFate.KEPT_WITH_ITS_RUN -> verdictUnevaluatedColor()
                    else -> null
                }
            },
        )
    }
}

/**
 * What `VACUUM` would delete — see [model.planVacuum]. A listing of the whole table directory, so
 * it sits behind a click, and what it finds is handed to the maintenance summary through
 * [onPlanned]. The bare call is drawn in full; `RETAIN 0 HOURS`, what goes once the check is off,
 * is one line under it.
 */
@Composable
internal fun DeltaVacuumSection(
    model: DeltaUnifiedTableModel,
    startRequested: Boolean = false,
    onPlanned: (DeltaVacuumPlan) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    var requested by remember(model) { mutableStateOf(startRequested) }
    val clock = LocalExpiryClock.current
    val outcome by produceState<Pair<Result<DeltaVacuumPlan>, Result<DeltaVacuumPlan>>?>(null, model, requested) {
        value = null
        if (requested) {
            val nowMs = clock()
            value = withContext(Dispatchers.IO) { model.planVacuum(nowMs) to model.planVacuum(nowMs, retainMs = 0) }
            value?.first?.getOrNull()?.let(onPlanned)
        }
    }
    val plan = outcome?.first?.getOrNull()
    val title = "Vacuum" + when {
        plan == null -> ""
        plan.toDelete.isEmpty() -> " — nothing to delete"
        else -> " — ${formatCounted(plan.toDelete.size, "file")} would go"
    }
    Section(title) {
        Text(
            "What VACUUM deletes, as VacuumCommand.gc decides it: the table directory listed, names starting _ or . " +
                "skipped (not _change_data, not a partition directory); a file older than now less " +
                "delta.deletedFileRetentionDuration (a week) goes unless a live add, a tombstone removed within that " +
                "time, or one's deletion vector names it. A cdc file is never in the state, so nothing keeps one.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        when {
            !requested -> OutlinedButton(onClick = { requested = true }) { Text("List the table directory") }
            outcome == null -> Text("Listing the table directory…", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            plan == null -> Text("Not planned: ${outcome?.first?.exceptionOrNull()?.message}", fontSize = TypeScale.small, color = colors.error)
            else -> VacuumPlanBody(plan, outcome?.second?.getOrNull())
        }
    }
}

@Composable
private fun VacuumPlanBody(plan: DeltaVacuumPlan, retainZero: DeltaVacuumPlan?) {
    val colors = MaterialTheme.colorScheme
    Text(
        if (plan.toDelete.isEmpty()) {
            "A bare VACUUM deletes nothing: ${formatCounted(plan.kept, "file")} kept" +
                (if (plan.tooYoung > 0) ", ${plan.tooYoung} kept by nothing but younger than ${formatRetention(plan.tableRetentionMs)}." else ".")
        } else {
            "A bare VACUUM deletes ${formatCounted(plan.toDelete.size, "file")} (${formatBytes(plan.sizeOfDataToDelete)})" +
                (if (plan.tooYoung > 0) "; ${plan.tooYoung} kept by nothing are younger than ${formatRetention(plan.tableRetentionMs)}." else ".")
        },
        fontSize = TypeScale.small,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    retainZero?.let { zero ->
        Text(
            "VACUUM RETAIN 0 HOURS would delete ${formatCounted(zero.toDelete.size, "file")} (${formatBytes(zero.sizeOfDataToDelete)}) — " +
                (zero.refusal?.let { "and is $it." } ?: "and runs."),
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
    val rows = plan.rows.take(MAX_DELTA_PLAN_ROWS)
    WideTable(
        headers = listOf("Fate", "Path", "Why", "Size", "Modified"),
        rows = rows.map { r ->
            listOf(
                r.fate.label,
                r.relativePath + if (r.isDirectory) "/" else "",
                r.reason + if (r.companions.isNotEmpty()) "; its .crc goes with it" else "",
                if (r.isDirectory) "" else formatBytes(r.sizeBytes),
                "${formatOrphanAge(plan.nowMs - r.modifiedMs)} ago",
            )
        },
        columnWidths = listOf(150.dp, 330.dp, 420.dp, 90.dp, 100.dp),
        leadCellColors = rows.map { r ->
            when (r.fate) {
                VacuumFate.DELETED -> verdictSkippedColor()
                VacuumFate.DIRECTORY_NOT_EMPTY -> verdictUnevaluatedColor()
                else -> null
            }
        },
    )
    if (plan.rows.size > rows.size) {
        Text("…and ${formatCounted(plan.rows.size - rows.size, "more")}.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
    }
}

/**
 * UniForm's Iceberg metadata against the log — see [model.DeltaUniFormCheck]. It reads the
 * Iceberg metadata tree whole, so it sits behind a click, the Paimon export's shape; the verdict
 * leads, then the files only one side lists, the ones a reader of the other format would miss or
 * find extra.
 */
@Composable
internal fun DeltaUniFormSection(node: model.GraphNode.TableNode, startRequested: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    if (!node.deltaUniForm.isPresent) return
    var requested by remember(node.id) { mutableStateOf(startRequested || node.deltaUniForm.isRead) }
    val outcome by produceState<Result<model.DeltaUniFormCheck>?>(null, node.id, requested) {
        value = null
        if (requested) value = withContext(Dispatchers.IO) { runCatching { requireNotNull(node.deltaUniForm.value) { "no check" } } }
    }
    val check = outcome?.getOrNull()
    Section("UniForm" + (check?.let { if (it.filesAgree && !it.behind) " — the same files" else if (it.filesAgree) " — behind" else " — DIFFERS" } ?: "")) {
        Text(
            "Under delta.universalFormat.enabledFormats = iceberg every commit is converted into Iceberg metadata under " +
                "metadata/, naming the same Parquet files, and each metadata version records the Delta version it " +
                "converted as delta-version. Checked here: that version against the latest, and the Iceberg current " +
                "snapshot's files against the Delta state at it.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        when {
            !requested -> OutlinedButton(onClick = { requested = true }) { Text("Read the Iceberg metadata") }
            outcome == null -> Text("Reading the Iceberg metadata…", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            check == null -> Text("Not read: ${outcome?.exceptionOrNull()?.message}", fontSize = TypeScale.small, color = colors.error)
            else -> {
                Text(
                    check.describe().replaceFirstChar { it.uppercase() } + ".",
                    fontSize = TypeScale.small,
                    fontWeight = FontWeight.Bold,
                    color = if (check.exportPresent && check.readError == null && check.exportedVersion != null && !check.filesAgree) colors.error else colors.onSurface,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                check.metadataFile?.let { DetailTable { DetailRow("Newest Metadata", it); DetailRow("Iceberg Snapshot", check.icebergSnapshotId?.toString() ?: "none") } }
                val rows = check.missingFromIceberg.map { "only in Delta" to it } + check.extraInIceberg.map { "only in Iceberg" to it }
                if (rows.isNotEmpty()) {
                    WideTable(
                        headers = listOf("Side", "File"),
                        rows = rows.take(MAX_DELTA_PLAN_ROWS).map { (side, p) -> listOf(side, runCatching { java.nio.file.Paths.get(node.summary.tablePath).toAbsolutePath().normalize().relativize(p).toString() }.getOrNull() ?: p.toString()) },
                        columnWidths = listOf(150.dp, 600.dp),
                        leadCellColors = rows.take(MAX_DELTA_PLAN_ROWS).map { colors.error },
                    )
                }
            }
        }
    }
}
