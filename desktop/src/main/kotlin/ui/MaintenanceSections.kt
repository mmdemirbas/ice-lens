package ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import model.ManifestContent
import model.isEmpty
import model.GraphModel
import model.GraphNode
import model.IcebergMaintenanceInput
import model.PaimonMaintenanceInput
import model.planExpiry
import model.ExpiryCleanup
import model.ExpiryFileKind
import model.ExpiryOptions
import model.planExpiryFiles
import model.coreApiCleanup
import model.PaimonExpiryFileKind
import model.PaimonExpiryFilePlan
import model.PaimonCompactionOptions
import model.ManifestMergeOptions
import model.ManifestMergePlan
import model.ManifestMergeVerdict
import model.assumedManifestBytes
import model.UnreferencedFilesReport
import model.planManifestMerge
import model.PaimonManifestMergeOptions
import model.planOrphanRemoval
import model.IcebergFastForwardVerdict
import model.IcebergRollbackVerdict
import model.CherryPickVerdict
import model.planCherryPick
import model.planRollback
import model.PaimonFastForwardPlan
import model.PaimonUnexistingFilesPlan
import model.RewriteTablePathOptions
import model.planRewriteTablePath
import model.fastForwardPlans
import model.planFastForward
import model.planPurge
import model.planFullCompaction
import model.PaimonFullCompactionAction
import model.PaimonFullCompactionOptions
import model.planPaimonManifestCompaction
import model.planPaimonManifestMerge
import model.RewriteOptions
import model.planRewrite
import model.planDanglingDeletes
import model.DataFileContent
import model.ScanFilter
import model.evaluateScan
import model.normalizeFilePath
import model.renderSparkSql
import model.PositionDeleteRewriteOptions
import model.ManifestRewriteOptions
import model.planManifestRewrite
import model.PositionDeleteRewritePlan
import model.planPositionDeleteRewrite
import service.PositionDeleteRewriteDrops
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import model.PaimonExpiryInput
import model.paimonAppendVerdict
import model.planCompaction
import model.PaimonExpiryOptions
import model.planChangelogExpiry
import model.planPartitionExpiry
import model.planTagExpiry
import model.planRollback
import model.rollbackLeftovers
import model.planTagDeletion
import model.formatPaimonDurationMs
import model.PaimonPartitionExpireStrategy
import model.paimonChangelogLifecycleDecoupled
import model.TableMetadata
import model.describe

/*
 * The inspector's maintenance sections: what each procedure would do if run now, each planned by
 * the core function of the same name and drawn as a verdict table — the Iceberg snapshot's
 * `Rewrite` and `Manifest Merge`, the metadata file's `Expiry` and `Expiry Files`, the Paimon
 * snapshot's `Compaction`, the Paimon table's `Expiry` and `Expiry Files`, and the table panel's
 * `Maintenance` line-per-procedure summary over all of them.
 */

/** The table root, which aggregation never folds and a snapshot filter always reaches. */
private fun GraphModel.tableNode(): GraphNode.TableNode? = nodeById["table_root"] as? GraphNode.TableNode

/**
 * The newest metadata, off the table root's [model.MaintenanceInput] rather than the drawn
 * metadata nodes — the drawn ones stop at the page size and at the snapshot filter, and the
 * newest is exactly the one both leave out.
 */
private fun GraphModel.newestMetadata(): TableMetadata? = (tableNode()?.maintenance?.value as? IcebergMaintenanceInput)?.metadata

/**
 * Each bucket as the LSM tree its writer would restore at this snapshot, and what the next flush
 * would compact — `planCompaction`, the rules of `UniversalCompaction.pick()`, on the same
 * deferred replay the records and partitions above already ran. An append table has no tree, so
 * its side is the small-file count `sys.compact` would pack per partition.
 *
 * The verdict leads: "will the next write compact, and why" is the question, and a bucket that
 * would stall the writer is the one row that has to be findable without reading the others.
 */
@Composable
internal fun PaimonCompactionSection(node: GraphNode.PaimonSnapshotNode) {
    val colors = MaterialTheme.colorScheme
    if (!node.hasPrimaryKey) {
        AppendCompactionSection(node)
        return
    }
    val lsms = node.bucketLsms
    val options = PaimonCompactionOptions.from(node.tableOptions)
    val verdicts = lsms?.map { it.planCompaction(options) }.orEmpty()
    val compacting = verdicts.count { it.compacts }
    val title = "Compaction" + when {
        verdicts.any { it.stalls } -> " — a writer would wait"
        compacting > 0 -> " — $compacting due"
        else -> ""
    }
    CountedSection(title, verdicts.size, "buckets") {
        Text(
            "Each bucket is an LSM tree: every level-0 file is a sorted run of its own, each higher " +
                "level is one run, and the writer asks UniversalCompaction.pick() on every flush. " +
                "Under num-sorted-run.compaction-trigger (${options.trigger}) runs it picks nothing; " +
                "at it, by size — the runs newer than the oldest against " +
                "${options.maxSizeAmplificationPercent}% of the oldest, else the newest runs within " +
                "${options.sizeRatioPercent}% of each other; above it, regardless. Past " +
                "num-sorted-run.stop-trigger (${options.stopTrigger}) the writer waits for the " +
                "compaction." +
                (if (options.forceUpLevel0) " This table forces level 0 up on every flush (lookup, deletion vectors or first-row)." else "") +
                (if (options.writeOnly) " This table is write-only: nothing compacts." else ""),
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (lsms == null) {
            Text("Not readable here — this snapshot's manifests are not retained.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
        } else if (verdicts.isNotEmpty()) {
            WideTable(
                headers = listOf("Next Flush", "Partition", "Bucket", "Sorted Runs", "Levels", "Files", "Bytes"),
                columnWidths = listOf(190.dp, 190.dp, 70.dp, 100.dp, 190.dp, 70.dp, 110.dp),
                rows = verdicts.map { v ->
                    listOf(
                        v.describe(),
                        v.lsm.partition.ifEmpty { "(unpartitioned)" },
                        "${v.lsm.bucket}",
                        "${v.lsm.sortedRunCount} of ${options.trigger}",
                        v.lsm.describeLevels(),
                        "${v.lsm.fileCount}",
                        formatBytes(v.lsm.runs.sumOf { it.sizeBytes }),
                    )
                },
                leadCellColors = verdicts.map { v ->
                    when {
                        v.stalls -> colors.error
                        v.compacts -> verdictSkippedColor()
                        else -> null
                    }
                },
            )
        }
    }
}

/**
 * What `sys.compact(table)` — `compact_strategy => 'full'`, the default — does to each bucket:
 * [planFullCompaction], `pickFullCompaction` and `MergeTreeCompactTask` at release-1.3.1. Drawn
 * under the writer's compaction, since the two answer different questions: the writer's is
 * "will the next flush compact", this is "what does an explicit call do to what is there".
 */
@Composable
internal fun PaimonFullCompactionSection(node: GraphNode.PaimonSnapshotNode) {
    val colors = MaterialTheme.colorScheme
    if (!node.hasPrimaryKey) return
    val lsms = node.bucketLsms
    val options = PaimonFullCompactionOptions.from(node.tableOptions)
    val vectored = node.readInput.value?.vectors?.map { it.dataFileName }?.toSet().orEmpty()
    val verdicts = lsms?.map { it.planFullCompaction(options, vectored) }.orEmpty()
    val acting = verdicts.count { it.compacts }
    val title = "Full Compaction" + when {
        lsms == null -> ""
        acting > 0 -> " — " + listOfNotNull(
            verdicts.sumOf { it.rewritten.size }.takeIf { it > 0 }?.let { "${formatCounted(it, "file")} rewritten" },
            verdicts.sumOf { it.upgraded.size }.takeIf { it > 0 }?.let { "$it upgraded" },
        ).joinToString(", ")
        else -> " — nothing to do"
    }
    CountedSection(title, verdicts.size, "buckets") {
        Text(
            "What sys.compact(table) does to each bucket — compact_strategy full, the default — the way " +
                "pickFullCompaction and MergeTreeCompactTask do it: a bucket whose only run is at the top level " +
                "(${verdicts.firstOrNull()?.outputLevel ?: options.numLevels - 1}) is left alone unless a file carries a deletion vector, which is " +
                "rewritten in place to apply it; otherwise every run goes into one unit, cut into sections of " +
                "intersecting key ranges: a section of several files is rewritten together, a lone file under " +
                "compaction.file-size (${formatBytes(options.minFileSizeBytes)}) joins the pending rewrite, and a lone file at " +
                "or over it is upgraded — renamed to the top level, not rewritten — unless it holds -D rows, " +
                "which the top level drops. compact_strategy minor is the writer's own pick, above." +
                (if (options.changelogProducer == "lookup") " Under changelog-producer lookup an upgraded level-0 file is read once to emit its changelog." else "") +
                (if (options.forceRewriteAllFiles) " compaction.force-rewrite-all-files is set: nothing is upgraded, everything rewritten." else "") +
                (if (options.recordLevelExpire) " record-level.expire-time is set: files holding expired records are rewritten too, not evaluated here." else ""),
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (lsms == null) {
            Text("Not readable here — this snapshot's manifests are not retained.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            return@CountedSection
        }
        if (verdicts.isEmpty()) return@CountedSection
        WideTable(
            headers = listOf("sys.compact", "Partition", "Bucket", "Levels", "Files", "-D Rows Dropped"),
            columnWidths = listOf(260.dp, 190.dp, 70.dp, 190.dp, 70.dp, 120.dp),
            rows = verdicts.map { v ->
                listOf(v.describe(), v.lsm.partition.ifEmpty { "(unpartitioned)" }, "${v.lsm.bucket}", v.lsm.describeLevels(), "${v.lsm.fileCount}", "${v.deleteRowsDropped}")
            },
            leadCellColors = verdicts.map { if (it.compacts) verdictSkippedColor() else null },
        )
        val files = verdicts.flatMap { v -> v.files.map { v to it } }
        if (files.any { (_, f) -> f.action != PaimonFullCompactionAction.KEEP }) {
            WideTable(
                headers = listOf("Action", "File", "Level", "Group", "Rows", "-D Rows", "Bytes", "Bucket", "Why"),
                columnWidths = listOf(130.dp, 400.dp, 70.dp, 60.dp, 80.dp, 70.dp, 90.dp, 70.dp, 520.dp),
                rows = files.map { (v, f) ->
                    listOf(
                        f.action.label,
                        f.file.fileName ?: "—",
                        "${f.file.level ?: 0}" + (if (f.action != PaimonFullCompactionAction.KEEP && (f.file.level ?: 0) != v.outputLevel) " → ${v.outputLevel}" else ""),
                        f.group?.toString() ?: "—",
                        f.file.rowCount?.let { formatCount(it) } ?: "—",
                        f.file.deleteRowCount?.let { formatCount(it) } ?: "—",
                        f.file.fileSize?.let(::formatBytes) ?: "—",
                        "${v.lsm.bucket}",
                        f.reason,
                    )
                },
                leadCellColors = files.map { (_, f) ->
                    when (f.action) {
                        PaimonFullCompactionAction.REWRITE, PaimonFullCompactionAction.REWRITE_IN_PLACE -> verdictSkippedColor()
                        PaimonFullCompactionAction.UPGRADE -> verdictUnevaluatedColor()
                        PaimonFullCompactionAction.KEEP -> null
                    }
                },
            )
        }
    }
}

@Composable
private fun AppendCompactionSection(node: GraphNode.PaimonSnapshotNode) {
    val colors = MaterialTheme.colorScheme
    val entries = node.bucketLsms
    val verdicts = entries?.let { lsms ->
        // The append rule is per partition and size-only; the trees carry the same files.
        lsms.groupBy { it.partition }.entries.sortedBy { it.key }.map { (partition, trees) ->
            val files = trees.flatMap { t -> t.runs.flatMap { it.files } }
            paimonAppendVerdict(partition, files, node.tableOptions)
        }
    }.orEmpty()
    val packing = verdicts.count { it.wouldPack }
    CountedSection("Compaction" + (if (packing > 0) " — sys.compact would run on $packing" else ""), verdicts.size, "partitions") {
        Text(
            "An append table has no levels and compacts only when sys.compact or a compaction job " +
                "runs. It packs the files under 7/10 of target-file-size per partition, and a pack is " +
                "a task once it holds compaction.min.file-num files or twice the target in bytes.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (entries == null) {
            Text("Not readable here — this snapshot's manifests are not retained.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
        } else if (verdicts.isNotEmpty()) {
            WideTable(
                headers = listOf("sys.compact", "Partition", "Small Files", "Files", "Small Bytes", "Threshold"),
                columnWidths = listOf(190.dp, 190.dp, 90.dp, 70.dp, 110.dp, 110.dp),
                rows = verdicts.map { v ->
                    listOf(
                        v.describe(),
                        v.partition.ifEmpty { "(unpartitioned)" },
                        "${v.smallFileCount}",
                        "${v.fileCount}",
                        formatBytes(v.smallFileBytes),
                        formatBytes(v.compactionFileSizeBytes),
                    )
                },
                leadCellColors = verdicts.map { if (it.wouldPack) verdictSkippedColor() else null },
            )
        }
    }
}

/**
 * What `expire_snapshots` would remove from this metadata, decided by [planExpiry] — the rules of
 * Iceberg's `RemoveSnapshots`, ref by ref — under two cutoffs side by side: the table's defaults,
 * and `older_than = now`, which is the most a procedure call can ask by age. Two columns rather
 * than a form, because the question a reader arrives with is "what is protecting this snapshot",
 * and the answer is the same ref either way; only the age rule moves between the columns.
 */
@Composable
internal fun ExpirySection(metadata: TableMetadata, nowMs: Long) {
    val colors = MaterialTheme.colorScheme
    val byDefaults = metadata.planExpiry(ExpiryOptions(nowMs = nowMs))
    val byAge = metadata.planExpiry(ExpiryOptions(nowMs = nowMs, olderThanMs = nowMs))
    val title = "Expiry" + if (byAge.removed.isNotEmpty()) " — ${byAge.removed.size} would go" else ""
    Section(title) {
        Text(
            "What expire_snapshots would keep, and why, the way RemoveSnapshots decides it: a ref " +
                "keeps its snapshot, a branch keeps its ancestors while they are within its " +
                "min-snapshots-to-keep or newer than its cutoff, and anything on no ref goes once " +
                "it is older than the cutoff. A branch's own max-snapshot-age-ms replaces older_than " +
                "for everything the branch reaches. The first column is the table's defaults " +
                "(${formatRetentionMs(nowMs - byDefaults.defaultCutoffMs).substringBefore(" (")} cutoff, " +
                "keep ${byDefaults.defaultMinSnapshotsToKeep}); the second is older_than = now.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        val expiringRefs = byDefaults.refs.filter { !it.retained }
        if (expiringRefs.isNotEmpty()) {
            Text(
                "Refs past their max-ref-age: " + expiringRefs.joinToString(", ") { "${it.name} (${it.reason})" } + ".",
                fontSize = TypeScale.small,
                fontWeight = FontWeight.Bold,
                color = colors.error,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        val byAgeById = byAge.snapshots.associateBy { it.snapshotId }
        val ordered = metadata.snapshots.sortedBy { it.sequenceNumber ?: Long.MAX_VALUE }.mapNotNull { it.snapshotId }
        WideTable(
            headers = listOf("Under the defaults", "Snapshot ID", "With older_than = now"),
            // The verdict leads and wraps; the panel opens at 300dp, so the leading column must
            // fit there or the reader scrolls before reading anything.
            columnWidths = listOf(190.dp, 190.dp, 300.dp),
            rows = ordered.map { id ->
                val defaults = byDefaults.snapshots.first { it.snapshotId == id }
                val age = byAgeById.getValue(id)
                listOf(
                    if (defaults.retained) "kept — " + defaults.describeKeptBy() else "REMOVED",
                    id.toString(),
                    if (age.retained) "kept — " + age.describeKeptBy() else "REMOVED",
                )
            },
            leadCellColors = ordered.map { id ->
                if (byDefaults.snapshots.first { it.snapshotId == id }.retained) null else colors.error
            },
        )
        // What the older_than = now column leaves of the snapshot log — the cut runs before the
        // last removed entry, so a retained snapshot can lose its entry too.
        if (byAge.logEntriesDropped > 0) {
            val retainedCut = byAge.logAfter.let { after -> metadata.snapshotLog.count { it.snapshotId !in byAge.removed } - after.size }
            val earliestAfter = byAge.logAfter.firstOrNull()?.timestampMs
            Text(
                "With older_than = now the snapshot log keeps ${formatCounted(byAge.logAfter.size, "entry", "entries")} of ${metadata.snapshotLog.size}" +
                    (if (retainedCut > 0) ", and ${formatCounted(retainedCut, "retained snapshot")} ${if (retainedCut == 1) "loses its entry" else "lose their entries"}, cut with the removed ones after ${if (retainedCut == 1) "it" else "them"} (TableMetadata.updateSnapshotLog cuts the history before a removed entry rather than leave a gap)" else "") +
                    (if (earliestAfter != null) "; the earliest TIMESTAMP AS OF that resolves becomes ${formatAppTimestamp(earliestAfter)}." else "; no TIMESTAMP AS OF resolves afterwards."),
                fontSize = TypeScale.small,
                color = if (retainedCut > 0) verdictUnevaluatedColor() else colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * One row per maintenance procedure, with what running it now would do — the four planners
 * asked at the table's current snapshot and summed to a verdict each, with the panel that holds
 * the detail named beside it. It is on the table panel because that is where a reader starts,
 * and the verdicts otherwise live three panels deep; the figures are the same functions the
 * detail sections call, so nothing here can drift from them. The leading cell is coloured only
 * where a procedure would act, and in the error colour where a writer would block.
 *
 * `remove_orphan_files` is the one row that needs a walk of the directory, so it is planned from
 * [orphanReport] — what `Unreferenced Files` found, once its button has been pressed — and says
 * so until then, rather than starting the walk from a summary. Paimon's
 * `remove_unexisting_files` is the same shape over the stat `Missing Files` runs, carried in
 * [unexistingPlan].
 */
@Composable
internal fun MaintenanceSection(node: GraphNode.TableNode, orphanReport: UnreferencedFilesReport? = null, unexistingPlan: PaimonUnexistingFilesPlan? = null) {
    val colors = MaterialTheme.colorScheme
    val summary = node.summary
    val nowMs = expiryClock()
    data class Row(val verdict: String, val procedure: String, val detail: String, val where: String, val color: androidx.compose.ui.graphics.Color?)
    val rows = mutableListOf<Row>()
    val paimonExpiry = summary.paimonExpiry
    val input = node.maintenance.value
    if (input is IcebergMaintenanceInput) {
        val meta = input.metadata
        // A current snapshot whose manifests are gone plans no rewrite and no merge; the
        // expiry row needs only the metadata, so a table with no snapshot still gets its line.
        val current = input.current?.takeIf { !it.expired }
        if (current != null) {
            val snapshotPanel = "snapshot ${current.simpleId}"
            val rewrite = current.liveFiles?.let { planRewrite(it, current.deleteReach.orEmpty(), RewriteOptions.forTable(meta.properties, meta.defaultSpecId)) }
            val rewritten = rewrite?.rewrittenGroups.orEmpty()
            rows += when {
                rewrite == null -> Row("not readable", "rewrite_data_files", "the current snapshot's manifests are not retained", "$snapshotPanel → Rewrite", null)
                rewritten.isNotEmpty() -> Row("would rewrite ${formatCounted(rewritten.sumOf { it.files.size }, "file")}", "rewrite_data_files", "${rewritten.size} of ${formatCounted(rewrite.groups.size, "group")}, ${formatBytes(rewritten.sumOf { it.inputBytes })}", "$snapshotPanel → Rewrite", verdictSkippedColor())
                rewrite.candidateCount > 0 -> Row("left alone", "rewrite_data_files", "${formatCounted(rewrite.candidateCount, "candidate")} in ${formatCounted(rewrite.groups.size, "group")}, none reaching min-input-files (${rewrite.options.minInputFiles})", "$snapshotPanel → Rewrite", null)
                else -> Row("nothing to do", "rewrite_data_files", "every live file is within the size range and under the delete ratio", "$snapshotPanel → Rewrite", null)
            }
            val deleteRewrite = current.liveFiles?.let { planPositionDeleteRewrite(it, PositionDeleteRewriteOptions.forTable(meta.properties, meta.formatVersion)) }
            val deleteRewritten = deleteRewrite?.rewrittenFiles.orEmpty()
            rows += when {
                deleteRewrite == null -> Row("not readable", "rewrite_position_delete_files", "the current snapshot's manifests are not retained", "$snapshotPanel → Position Delete Rewrite", null)
                deleteRewrite.refused != null -> Row("refused", "rewrite_position_delete_files", "${deleteRewrite.refused}; ${formatCounted(deleteRewrite.deleteFileCount, "positional delete file")} live", "$snapshotPanel → Position Delete Rewrite", colors.error)
                deleteRewrite.deleteFileCount == 0 -> Row("nothing to do", "rewrite_position_delete_files", "no live positional delete file", "$snapshotPanel → Position Delete Rewrite", null)
                deleteRewritten.isNotEmpty() -> Row("would rewrite ${formatCounted(deleteRewritten.size, "delete file")}", "rewrite_position_delete_files", "${deleteRewrite.rewrittenGroups.size} of ${formatCounted(deleteRewrite.groups.size, "group")}, ${formatBytes(deleteRewrite.rewrittenGroups.sumOf { it.inputBytes })}; the dangling positions go with them", "$snapshotPanel → Position Delete Rewrite", verdictSkippedColor())
                deleteRewrite.candidateCount > 0 -> Row("left alone", "rewrite_position_delete_files", "${formatCounted(deleteRewrite.candidateCount, "candidate")} in ${formatCounted(deleteRewrite.groups.size, "group")}, none reaching min-input-files (${deleteRewrite.options.minInputFiles}); rewrite-all takes them", "$snapshotPanel → Position Delete Rewrite", null)
                else -> Row("nothing to do", "rewrite_position_delete_files", "every live positional delete file is within the size range", "$snapshotPanel → Position Delete Rewrite", null)
            }
            val mergeOptions = ManifestMergeOptions.forTable(meta.properties)
            val listed = current.manifestList
            val merge = planManifestMerge(listed, ManifestContent.DATA, assumedManifestBytes(listed, ManifestContent.DATA), meta.defaultSpecId, mergeOptions)
            rows += Row(
                if (merge.mergedBins.isNotEmpty()) "would merge ${formatCounted(merge.mergedBins.sumOf { it.manifests.size }, "manifest")}" else "nothing merges",
                "next append's manifest merge",
                "${formatCounted(listed.count { (it.content ?: ManifestContent.DATA) == ManifestContent.DATA }, "data manifest")} listed; ${merge.describe}" + if (mergeOptions.enabled) " under min-count-to-merge ${mergeOptions.minCountToMerge}" else "",
                "$snapshotPanel → Manifest Merge",
                if (merge.mergedBins.isNotEmpty()) verdictSkippedColor() else null,
            )
            val manifestRewrite = planManifestRewrite(listed, ManifestRewriteOptions.forTable(meta.properties, meta.defaultSpecId))
            rows += Row(
                when {
                    manifestRewrite.rewrites -> "would replace ${formatCounted(manifestRewrite.replaced, "manifest")} with ${manifestRewrite.created}"
                    manifestRewrite.refused.isNotEmpty() -> "refused"
                    else -> "nothing to do"
                },
                "rewrite_manifests",
                when {
                    manifestRewrite.rewrites -> manifestRewrite.kinds.filter { it.rewritten }.joinToString("; ") { "${formatCounted(it.matching.size, "${it.label} manifest")} into ${it.targetNumManifests}" } + "; ${formatCounted(manifestRewrite.kept, "manifest")} kept"
                    manifestRewrite.refused.isNotEmpty() -> manifestRewrite.refused.joinToString("; ") { it.leftAlone.orEmpty() }
                    else -> "each kind is one manifest within commit.manifest.target-size-bytes, or none"
                },
                "$snapshotPanel → Manifest Rewrite",
                when {
                    manifestRewrite.rewrites -> verdictSkippedColor()
                    manifestRewrite.refused.isNotEmpty() -> colors.error
                    else -> null
                },
            )
        }
        val expiry = meta.planExpiry(ExpiryOptions(nowMs = nowMs, olderThanMs = nowMs))
        val freed = node.expiryFiles.value?.planExpiryFiles(expiry.removed.toSet())
        rows += Row(
            if (expiry.removed.isEmpty()) "nothing expires" else "would remove ${formatCounted(expiry.removed.size, "snapshot")}",
            "expire_snapshots (older_than = now)",
            when {
                meta.snapshots.isEmpty() -> "the table has no snapshots"
                expiry.removed.isEmpty() -> "every snapshot is kept by a ref"
                freed == null -> "what that frees is not readable here"
                else -> "frees ${freed.describe} — ${formatBytes(freed.knownBytes)} the metadata accounts for, ${freed.cleanup.label}"
            },
            "${input.metadataFileName} → Expiry, Expiry Files",
            if (expiry.removed.isEmpty()) null else verdictSkippedColor(),
        )
        if (meta.refs.size > 1) {
            val pairs = meta.fastForwardPlans()
            val moving = pairs.filter { it.moves }
            rows += Row(
                if (moving.isEmpty()) "nothing moves" else "${moving.size} of ${formatCounted(pairs.size, "pair")} would move",
                "fast_forward",
                if (moving.isEmpty()) "no branch's tip is an ancestor of another ref's snapshot: every line has moved on since it forked"
                else moving.joinToString("; ") { "${it.branch} → ${it.to} takes on ${formatCounted(it.gained.size, "commit")}" },
                "${input.metadataFileName} → Fast-Forward",
                if (moving.isEmpty()) null else verdictSkippedColor(),
            )
        }
    } else if (input is PaimonMaintenanceInput && paimonExpiry != null) {
        val current = input.current
        if (current != null) {
            val snapshotPanel = "snapshot ${current.simpleId}"
            val manifestInput = current.manifestMergeInput.value.orEmpty()
            val manifestMerge = planPaimonManifestMerge(manifestInput, PaimonManifestMergeOptions.forTable(current.tableOptions))
            rows += Row(
                if (manifestMerge.mergedBins.isNotEmpty()) "would merge ${formatCounted(manifestMerge.mergedManifests, "manifest")}" else "nothing merges",
                "next commit's manifest merge",
                "${formatCounted(manifestInput.size, "data manifest")} listed; ${manifestMerge.describe}",
                "$snapshotPanel → Manifest Merge",
                if (manifestMerge.mergedBins.isNotEmpty()) verdictSkippedColor() else null,
            )
            val manifestCompaction = planPaimonManifestCompaction(manifestInput, PaimonManifestMergeOptions.forTable(current.tableOptions))
            rows += Row(
                if (manifestCompaction.writesNewList) "would rewrite ${formatCounted(manifestCompaction.mergedManifests, "manifest")}" else "nothing to compact",
                "compact_manifest",
                manifestCompaction.describeCompaction,
                "$snapshotPanel → Manifest Merge",
                if (manifestCompaction.writesNewList) verdictSkippedColor() else null,
            )
            val branches = node.summary.branches.orEmpty()
            if (branches.isNotEmpty()) {
                val plans = node.paimonExpiryFiles.value?.let { files -> branches.mapNotNull { files.planFastForward(it.name) } }
                val dropping = plans.orEmpty().filter { it.refused == null && it.leftovers.isNotEmpty() }
                rows += when {
                    plans == null -> Row("not readable", "fast_forward", "the manifests could not be read", "table → Fast-Forward", null)
                    dropping.isNotEmpty() -> Row(
                        "would drop ${formatCounted(dropping.sumOf { it.droppedCommits }, "commit")} of main",
                        "fast_forward",
                        dropping.joinToString("; ") { "${it.branch}: main from snapshot ${it.earliestId} on replaced, ${formatCounted(it.leftovers.size, "file")} left named by nothing" },
                        "table → Fast-Forward",
                        colors.error,
                    )
                    plans.any { it.refused == null } -> Row("replaces nothing main lacks", "fast_forward", plans.filter { it.refused == null }.joinToString("; ") { "${it.branch}: main from snapshot ${it.earliestId} on is the branch's own line" }, "table → Fast-Forward", null)
                    else -> Row("refused", "fast_forward", plans.joinToString("; ") { "${it.branch}: ${it.refused}" }, "table → Fast-Forward", null)
                }
            }
            val lsms = current.bucketLsms
            if (current.hasPrimaryKey) {
                val options = PaimonCompactionOptions.from(current.tableOptions)
                val verdicts = lsms?.map { it.planCompaction(options) }.orEmpty()
                val due = verdicts.count { it.compacts }
                val stalled = verdicts.count { it.stalls }
                rows += when {
                    lsms == null -> Row("not readable", "compaction", "the latest snapshot's manifests could not be replayed", "$snapshotPanel → Compaction", null)
                    options.writeOnly -> Row("never — write-only", "compaction", "${formatCounted(lsms.size, "bucket")}, ${formatCounted(lsms.sumOf { it.level0FileCount }, "level-0 file")} piling up", "$snapshotPanel → Compaction", null)
                    stalled > 0 -> Row("a writer would wait on $stalled", "compaction", "$due of ${lsms.size} buckets due, $stalled past num-sorted-run.stop-trigger (${options.stopTrigger})", "$snapshotPanel → Compaction", colors.error)
                    due > 0 -> Row("${formatCounted(due, "bucket")} due", "compaction", "$due of ${formatCounted(lsms.size, "bucket")} would compact on the next flush", "$snapshotPanel → Compaction", verdictSkippedColor())
                    else -> Row("not yet", "compaction", "${formatCounted(lsms.size, "bucket")}, every one under num-sorted-run.compaction-trigger (${options.trigger})", "$snapshotPanel → Compaction", null)
                }
                val fullOptions = PaimonFullCompactionOptions.from(current.tableOptions)
                val vectored = current.readInput.value?.vectors?.map { it.dataFileName }?.toSet().orEmpty()
                val full = lsms?.map { it.planFullCompaction(fullOptions, vectored) }.orEmpty()
                val rewritten = full.sumOf { it.rewritten.size }
                val upgraded = full.sumOf { it.upgraded.size }
                rows += when {
                    lsms == null -> Row("not readable", "sys.compact", "the latest snapshot's manifests could not be replayed", "$snapshotPanel → Full Compaction", null)
                    rewritten + upgraded > 0 -> Row(
                        "would rewrite ${formatCounted(rewritten, "file")}, upgrade $upgraded",
                        "sys.compact",
                        "${full.count { it.compacts }} of ${formatCounted(full.size, "bucket")} under compact_strategy full, the default; " +
                            "${formatCounted(full.sumOf { it.deleteRowsDropped }.toInt(), "-D row")} dropped",
                        "$snapshotPanel → Full Compaction",
                        verdictSkippedColor(),
                    )
                    else -> Row("nothing to do", "sys.compact", "${formatCounted(full.size, "bucket")}, every one a single run at the top level with no vector", "$snapshotPanel → Full Compaction", null)
                }
            } else {
                val verdicts = lsms?.groupBy { it.partition }?.entries?.map { (partition, trees) ->
                    paimonAppendVerdict(partition, trees.flatMap { t -> t.runs.flatMap { it.files } }, current.tableOptions)
                }.orEmpty()
                val packing = verdicts.count { it.wouldPack }
                rows += if (packing > 0) Row("sys.compact would run on $packing", "compaction", "$packing of ${formatCounted(verdicts.size, "partition")} have enough small files; a write never compacts an append table", "$snapshotPanel → Compaction", verdictSkippedColor())
                else Row("nothing to pack", "compaction", "${formatCounted(verdicts.size, "partition")}, none at compaction.min.file-num small files", "$snapshotPanel → Compaction", null)
            }
        }
        val bare = runCatching { paimonExpiry.planExpiry(PaimonExpiryOptions(nowMs = nowMs)) }.getOrNull()
        val byAge = runCatching { paimonExpiry.planExpiry(PaimonExpiryOptions(nowMs = nowMs, retainMin = 1, olderThanMs = nowMs)) }.getOrNull()
        val freed = byAge?.let { p -> node.paimonExpiryFiles.value?.planExpiryFiles(p.removed.map { it.snapshotId }.toSet()) }
        val freeing = freed?.let { ", freeing ${it.describe}" + if (it.protectedByTag.isNotEmpty()) " (${it.protectedByTag.size} kept by a tag)" else "" } ?: ""
        rows += when {
            bare == null || byAge == null -> Row("rejected", "expire_snapshots", "the table's snapshot.* options are ones the procedure refuses", "table → Expiry", colors.error)
            bare.removed.isNotEmpty() -> Row("would remove ${formatCounted(bare.removed.size, "snapshot")}", "expire_snapshots", "a bare call removes ${bare.removed.size}; retain_min = 1 with older_than = now removes ${byAge.removed.size}$freeing", "table → Expiry, Expiry Files", verdictSkippedColor())
            byAge.removed.isNotEmpty() -> Row("nothing on a bare call", "expire_snapshots", "retain_min = 1 with older_than = now would remove ${byAge.removed.size}$freeing", "table → Expiry, Expiry Files", null)
            else -> Row("nothing expires", "expire_snapshots", "no call removes anything: the bounds, a consumer or a tag keep every snapshot", "table → Expiry", null)
        }
        if (paimonExpiry.changelogTimes.isNotEmpty() || paimonChangelogLifecycleDecoupled(paimonExpiry.tableOptions)) {
            val changelogBare = runCatching { paimonExpiry.planChangelogExpiry(PaimonExpiryOptions(nowMs = nowMs)) }.getOrNull()
            val changelogByAge = runCatching { paimonExpiry.planChangelogExpiry(PaimonExpiryOptions(nowMs = nowMs, retainMin = 1, olderThanMs = nowMs)) }.getOrNull()
            rows += when {
                changelogBare == null || changelogByAge == null -> Row("rejected", "expire_changelogs", "the table's changelog.* options are ones the action refuses", "table → Changelog Expiry", colors.error)
                paimonExpiry.changelogTimes.isEmpty() -> Row("nothing to expire", "expire_changelogs", "the changelog lifecycle is decoupled and changelog/ holds nothing yet", "table → Changelog Expiry", null)
                changelogBare.removed.isNotEmpty() -> Row("would remove ${formatCounted(changelogBare.removed.size, "changelog")}", "expire_changelogs", "a bare call removes ${changelogBare.removed.size} of ${paimonExpiry.changelogTimes.size}; retain_min = 1 with older_than = now removes ${changelogByAge.removed.size}", "table → Changelog Expiry", verdictSkippedColor())
                changelogByAge.removed.isNotEmpty() -> Row("nothing on a bare call", "expire_changelogs", "retain_min = 1 with older_than = now would remove ${changelogByAge.removed.size} of ${paimonExpiry.changelogTimes.size}", "table → Changelog Expiry", null)
                else -> Row("nothing expires", "expire_changelogs", "${formatCounted(paimonExpiry.changelogTimes.size, "long-lived changelog")}, every one within the bounds", "table → Changelog Expiry", null)
            }
        }
        if (paimonExpiry.partitions.isNotEmpty()) {
            val partitions = paimonExpiry.planPartitionExpiry(nowMs)
            rows += when {
                partitions.expirationMs == null -> Row("not configured", "expire_partitions", "no partition.expiration-time is set: a write expires nothing and a bare call is refused; ${formatCounted(partitions.partitions.size, "partition")} listed", "table → Partition Expiry", null)
                partitions.dropped.isNotEmpty() -> Row("would drop ${formatCounted(partitions.dropped.size, "partition")}", "expire_partitions", "of ${partitions.partitions.size} under ${partitions.strategySpelled}" + (if (partitions.heldBack > 0) ", ${partitions.heldBack} more past partition.expiration-max-num" else ""), "table → Partition Expiry", verdictSkippedColor())
                else -> Row("nothing expires", "expire_partitions", "${formatCounted(partitions.partitions.size, "partition")}, none past the cutoff under ${partitions.strategySpelled}", "table → Partition Expiry", null)
            }
        }
        if (paimonExpiry.tags.isNotEmpty()) {
            val bareTags = paimonExpiry.planTagExpiry(nowMs)
            val byAgeTags = paimonExpiry.planTagExpiry(nowMs, olderThanMs = nowMs)
            val freed = bareTags.removed.sumOf { v -> node.paimonExpiryFiles.value?.planTagDeletion(v.tag.name)?.dataFiles?.size ?: 0 }
            rows += when {
                bareTags.removed.isNotEmpty() -> Row("would remove ${formatCounted(bareTags.removed.size, "tag")}", "expire_tags", "a bare call removes ${bareTags.removed.size} of ${paimonExpiry.tags.size}, whose retention ran out" + (if (freed > 0) ", freeing ${formatCounted(freed, "data file")}" else "") + "; older_than = now removes ${byAgeTags.removed.size}", "table → Tag Expiry", verdictSkippedColor())
                else -> Row("nothing on a bare call", "expire_tags", "${formatCounted(paimonExpiry.tags.size, "tag")}, ${paimonExpiry.tags.count { it.timeRetainedMs != null }} with a retention, none run out; older_than = now removes ${byAgeTags.removed.size}", "table → Tag Expiry", null)
            }
        }
        val purge = node.paimonExpiryFiles.value?.planPurge()
        rows += when {
            purge == null -> Row("not readable", "purge_files", "the manifests could not be read", "table → Purge", null)
            !purge.removesAnything -> Row("nothing to purge", "purge_files", "no snapshot, tag, branch or consumer: the truncate commit alone", "table → Purge", null)
            else -> Row(
                "would take ${formatCounted(purge.removed.size, "file")}",
                "purge_files",
                "${formatCounted(purge.ofKind(PaimonExpiryFileKind.DATA_FILE).size, "data file")}, ${formatBytes(purge.removedBytes)} the metadata accounts for; " +
                    "${formatCounted(purge.snapshotIds.size, "snapshot")} expired behind a truncating OVERWRITE" +
                    listOfNotNull(
                        purge.branches.takeIf { it.isNotEmpty() }?.let { "${formatCounted(it.size, "branch")} dropped" },
                        purge.tags.takeIf { it.isNotEmpty() }?.let { "${formatCounted(it.size, "tag")} deleted" },
                        purge.consumers.takeIf { it.isNotEmpty() }?.let { "${formatCounted(it.size, "consumer")} deleted" },
                    ).joinToString("") { ", $it" },
                "table → Purge",
                colors.error,
            )
        }
    }
    val recordedLocation = summary.location
    if (node.rewriteTablePath.isPresent && recordedLocation != null && recordedLocation != summary.tablePath) {
        // Planned under the prefixes a copied table has in hand; the section holds the form.
        val plan = node.rewriteTablePath.value?.planRewriteTablePath(RewriteTablePathOptions(recordedLocation, summary.tablePath))
        val refusal = plan?.refusal
        rows += when {
            plan == null -> Row("not readable", "rewrite_table_path", "the table's versions could not be read", "table → Rewrite Table Path", null)
            refusal != null -> Row("refused", "rewrite_table_path", refusal, "table → Rewrite Table Path", colors.error)
            else -> Row("would list ${formatCounted(plan.fileCount, "file")}", "rewrite_table_path", "${formatCounted(plan.versions.size, "version")}, ${formatCounted(plan.snapshotIds.size, "list")}, ${formatCounted(plan.manifests.size, "manifest")} rewritten into staging; $recordedLocation → ${summary.tablePath}", "table → Rewrite Table Path", verdictSkippedColor())
        }
    }
    if (paimonExpiry != null && node.missingFiles.isPresent) {
        rows += when {
            unexistingPlan == null -> Row("not checked", "remove_unexisting_files", "stat the files the retained snapshots need under Missing Files to plan it", "table → Missing Files", null)
            unexistingPlan.rows.isEmpty() -> Row("nothing to do", "remove_unexisting_files", "every file the retained snapshots need is there", "table → Missing Files", null)
            unexistingPlan.commits -> Row("would remove ${formatCounted(unexistingPlan.removed.size, "entry", "entries")}", "remove_unexisting_files", "an APPEND with a DELETE entry per missing data file of snapshot ${unexistingPlan.snapshotId}, deltaRecordCount ${unexistingPlan.deltaRecordCount}" + (if (unexistingPlan.notReached.size + unexistingPlan.unread.size > 0) "; ${unexistingPlan.notReached.size + unexistingPlan.unread.size} missing it does not list" else ""), "table → Missing Files", verdictSkippedColor())
            else -> Row("nothing on a call", "remove_unexisting_files", "${formatCounted(unexistingPlan.rows.size, "missing file")}, none a data file the latest snapshot's batch scan opens", "table → Missing Files", null)
        }
    }
    if (node.unreferencedFiles.isPresent) {
        val orphans = orphanReport?.let { planOrphanRemoval(it, nowMs) }
        rows += when {
            orphans == null -> Row("not walked", "remove_orphan_files", "walk the table directory under Unreferenced Files to plan it", "table → Unreferenced Files", null)
            orphans.rows.isEmpty() -> Row("nothing to delete", "remove_orphan_files", "every file on disk is named by the metadata the procedure reads", "table → Unreferenced Files", null)
            orphans.removed.isEmpty() -> Row("nothing on a bare call", "remove_orphan_files", "${formatCounted(orphans.rows.size, "file")} named by nothing: ${orphans.tooYoung} younger than ${orphans.defaultIntervalText}, ${orphans.unlisted} where it never lists", "table → Unreferenced Files", null)
            else -> Row("would delete ${formatCounted(orphans.removed.size, "file")}", "remove_orphan_files", "${formatBytes(orphans.removedBytes)}, older than ${orphans.defaultIntervalText}; ${orphans.tooYoung} younger held back, ${orphans.unlisted} never listed", "table → Unreferenced Files", verdictSkippedColor())
        }
    }
    if (rows.isEmpty()) return
    val acting = rows.count { it.color != null }
    Section("Maintenance" + if (acting > 0) " — $acting would act" else " — nothing to do") {
        Text(
            "What each maintenance procedure would do if run now, planned the way the engine plans it " +
                "and summed to a line; the panel named in the last column holds the reasoning.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        WideTable(
            headers = listOf("Verdict", "Procedure", "Detail", "Where"),
            columnWidths = listOf(190.dp, 190.dp, 420.dp, 220.dp),
            rows = rows.map { listOf(it.verdict, it.procedure, it.detail, it.where) },
            leadCellColors = rows.map { it.color },
        )
    }
}

/** The file list a plan prints before it says how many more there are. */
internal const val MAX_EXPIRY_FILE_ROWS = 200

/**
 * The files the `older_than = now` expiry above would delete, decided by [planExpiryFiles] — the
 * rules of `IncrementalFileCleanup` and `ReachableFileCleanup`, chosen by the ref count the
 * expiry leaves. Its own section under [ExpirySection] rather than a column in it, because the
 * question changes: that table says what protects a snapshot, this one says what freeing the rest
 * is worth, which is the figure a reader deciding whether to run the procedure came for. The
 * lists and entries come off the drawn graph, so an expired snapshot — whose list is gone —
 * contributes nothing, which is right: the expiry that removed it already ran.
 */
@Composable
internal fun ExpiryFilesSection(metadata: TableMetadata, graph: GraphModel, nowMs: Long) {
    val colors = MaterialTheme.colorScheme
    val removed = metadata.planExpiry(ExpiryOptions(nowMs = nowMs, olderThanMs = nowMs)).removed.toSet()
    // The input comes off the table node's model-built read, never the drawn nodes: aggregation
    // folds older snapshots and manifests out of the graph, which are the ones an expiry removes.
    val input = graph.tableNode()?.expiryFiles?.value?.copy(metadata = metadata)
    val plan = input?.planExpiryFiles(removed)
    // The core API's cleanup differs from the procedure's diff only with one ref: what it leaves is
    // the answer to "why did my Flink or Java expiry leave files behind".
    val apiCleanup = input?.coreApiCleanup(removed)
    val leftByApi = if (plan != null && input != null && apiCleanup == ExpiryCleanup.INCREMENTAL) plan.paths - input.planExpiryFiles(removed, apiCleanup).paths else emptySet()
    CountedSection("Expiry Files — ${plan?.describe ?: "not readable"}", plan?.files?.size ?: 0, "files") {
        Text(
            "What the older_than = now expiry above would delete, the way the Spark procedure deletes " +
                "it: every file an expired snapshot reaches — its manifest list, its manifests, the data " +
                "and delete files live in them, its statistics — except what a retained snapshot still " +
                "reaches the same way (ExpireSnapshotsSparkAction's diff, whatever the ref count). The " +
                "core API — table.expireSnapshots().commit() from Java, Flink or Trino — cleans up by the " +
                "ref count instead: with more than one ref the same reachable rule; with exactly one, " +
                "RemoveSnapshots' incremental rule, which frees a file only when an expired commit on the " +
                "live line removed it or an expired commit off the live line added it — and so leaves a " +
                "file an expired commit added and a retained one removed on disk, named by nothing.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        when (plan?.cleanup) {
            null -> Text("Not readable here — the table's manifests could not be read.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            ExpiryCleanup.NONE -> Text("Nothing expires under older_than = now, so nothing is freed.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            else -> {
                Text(
                    "Cleanup: ${plan.cleanup.label}. ${formatBytes(plan.knownBytes)} the metadata can account for" +
                        " — a manifest list records no size." +
                        when {
                            apiCleanup != ExpiryCleanup.INCREMENTAL -> ""
                            leftByApi.isEmpty() -> " The core API's incremental cleanup (one ref) frees the same files here."
                            else -> " The core API's incremental cleanup (one ref) would leave ${leftByApi.size} of these on disk, named by nothing: ${leftByApi.joinToString(", ") { it.substringAfterLast('/') }}."
                        },
                    fontSize = TypeScale.small,
                    color = if (leftByApi.isEmpty()) colors.onSurfaceVariant else verdictUnevaluatedColor(),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                if (plan.files.isEmpty()) Text("Every file of the expired snapshots is still read by a retained one.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
                else {
                    // Data first: the files are what freeing is worth, the lists are bookkeeping.
                    val kindOrder = listOf(ExpiryFileKind.DATA_FILE, ExpiryFileKind.DELETE_FILE, ExpiryFileKind.STATISTICS, ExpiryFileKind.MANIFEST, ExpiryFileKind.MANIFEST_LIST)
                    val shown = plan.files.sortedBy { kindOrder.indexOf(it.kind) }.take(MAX_EXPIRY_FILE_ROWS)
                    WideTable(
                        headers = listOf("Kind", "Reason", "Snapshot", "Size", "File"),
                        columnWidths = listOf(120.dp, 390.dp, 190.dp, 90.dp, 600.dp),
                        rows = shown.map { f ->
                            listOf(
                                f.kind.label,
                                f.reason.label,
                                f.snapshotId?.toString() ?: "—",
                                f.sizeBytes?.let { formatBytes(it) } ?: "—",
                                f.path.substringAfterLast('/'),
                            )
                        },
                        leadCellColors = shown.map { if (it.kind == ExpiryFileKind.DATA_FILE || it.kind == ExpiryFileKind.DELETE_FILE) colors.error else null },
                    )
                    if (plan.files.size > shown.size) Text(
                        "${plan.files.size - shown.size} more not listed.",
                        fontSize = TypeScale.small,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * The files the `retain_min = 1, older_than = now` expiry beside it would delete on a Paimon
 * table — [PaimonExpiryFilePlan], the four passes of `ExpireSnapshotsImpl.expireUntil`. The
 * Iceberg section's twin, with one more thing to say: which removed files a tag holds on to, which
 * is the answer to "I expired everything and the bucket is still full". The input rides the table
 * node, built from the model, for the reason [ExpiryFilesSection] gives.
 */
@Composable
internal fun PaimonExpiryFilesSection(node: GraphNode.TableNode, input: PaimonExpiryInput, nowMs: Long) {
    val colors = MaterialTheme.colorScheme
    val byAge = runCatching { input.planExpiry(PaimonExpiryOptions(nowMs = nowMs, retainMin = 1, olderThanMs = nowMs)) }.getOrNull()
    val plan = byAge?.let { p -> node.paimonExpiryFiles.value?.planExpiryFiles(p.removed.map { it.snapshotId }.toSet()) }
    CountedSection("Expiry Files — ${plan?.describe ?: "not readable"}", plan?.files?.size ?: 0, "files") {
        Text(
            "What the retain_min = 1, older_than = now expiry above would delete, the way " +
                "ExpireSnapshotsImpl.expireUntil does it over the removed range: data files that a " +
                "later commit's delta recorded as removed — the first retained snapshot's included, " +
                "since the last snapshot that read them expires — unless the nearest earlier tag still " +
                "holds them; the changelog files the removed snapshots added; their manifest lists, " +
                "manifests, index manifests, index files and statistics that neither a tag in the range " +
                "nor the first retained snapshot names — a tag's changelog list among them; then the " +
                "snapshot files.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        when {
            plan == null -> Text("Not readable here — the table's options are ones the procedure refuses, or its manifests could not be read.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            plan.files.isEmpty() -> Text("Nothing expires under retain_min = 1, older_than = now, so nothing is freed.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            else -> {
                Text(
                    "Snapshots ${plan.beginInclusive} to ${plan.endExclusive?.minus(1)} go. ${formatBytes(plan.knownBytes)} the metadata can account for" +
                        " — a list and a snapshot file record no size." +
                        (if (plan.decoupled) " The changelog lifecycle is decoupled (changelog.num-retained.* or changelog.time-retained above the snapshot setting): the changelog lists and files stay for expire_changelogs, and each expired snapshot is written again under changelog/." else ""),
                    fontSize = TypeScale.small,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                if (plan.protectedByTag.isNotEmpty()) Text(
                    "${formatCounted(plan.protectedByTag.size, "removed file")} stay on disk because a tag still holds them: " +
                        plan.protectedByTag.groupBy { it.tag }.entries.joinToString("; ") { (tag, files) -> "$tag (snapshot ${files.first().tagSnapshotId}) keeps ${files.size}" } + ".",
                    fontSize = TypeScale.small,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                val shown = plan.files.take(MAX_EXPIRY_FILE_ROWS)
                WideTable(
                    headers = listOf("Kind", "Reason", "Snapshot", "Size", "File"),
                    columnWidths = listOf(120.dp, 390.dp, 90.dp, 90.dp, 600.dp),
                    rows = shown.map { f -> listOf(f.kind.label, f.reason.label, f.snapshotId?.toString() ?: "—", f.sizeBytes?.let { formatBytes(it) } ?: "—", f.name) },
                    leadCellColors = shown.map { if (it.kind == PaimonExpiryFileKind.DATA_FILE || it.kind == PaimonExpiryFileKind.CHANGELOG_FILE) colors.error else null },
                )
                if (plan.files.size > shown.size) Text("${plan.files.size - shown.size} more not listed.", fontSize = TypeScale.small, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/**
 * What `expire_snapshots` would remove from this Paimon table, decided by
 * [PaimonExpiryInput.planExpiry] — the rules of `ExpireSnapshotsImpl.expire()` — under two calls
 * side by side: a bare call, which runs under the table's own `snapshot.*` options, and
 * `retain_min = 1, older_than = now`, which is as far as a call can go without `retain_max` — so
 * what the second column still keeps is what no call can remove: a consumer's bookmark, or the
 * run's limit. The same two-column shape as the Iceberg section, because the reader's question
 * is the same — "what is protecting this snapshot".
 */
@Composable
internal fun PaimonExpirySection(input: PaimonExpiryInput, nowMs: Long) {
    val colors = MaterialTheme.colorScheme
    val plans = runCatching {
        input.planExpiry(PaimonExpiryOptions(nowMs = nowMs)) to
            input.planExpiry(PaimonExpiryOptions(nowMs = nowMs, retainMin = 1, olderThanMs = nowMs))
    }
    val byAge = plans.getOrNull()?.second
    val title = "Expiry" + if (byAge != null && byAge.removed.isNotEmpty()) " — ${byAge.removed.size} would go" else ""
    Section(title) {
        val (byDefaults, byAgePlan) = plans.getOrElse { failure ->
            // Paimon rejects the same options before running: the panel says so rather than guessing.
            Text(
                "expire_snapshots would refuse this table's options: ${failure.message}",
                fontSize = TypeScale.small,
                fontWeight = FontWeight.Bold,
                color = colors.error,
            )
            return@Section
        }
        val retainMaxLabel = if (byDefaults.retainMax == Int.MAX_VALUE) "unbounded" else "${byDefaults.retainMax}"
        Text(
            "What expire_snapshots would keep, and why, the way ExpireSnapshotsImpl decides it: the " +
                "newest snapshot.num-retained.min stay (${byDefaults.retainMin} here), anything beyond " +
                "snapshot.num-retained.max goes whatever its age ($retainMaxLabel here), a consumer's " +
                "next snapshot and everything after it stay, at most snapshot.expire.limit " +
                "(${byDefaults.maxDeletes}) go in one run, and between those bounds the run stops at " +
                "the first snapshot younger than the cutoff. The first column is a bare call, under the " +
                "table's snapshot.time-retained " +
                "(${formatRetentionMs(nowMs - byDefaults.cutoffMs).substringBefore(" (")}); the second " +
                "is retain_min = 1 with older_than = now, as far as a call goes without retain_max. " +
                "A removed snapshot a tag names lives on as the tag.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        val byAgeById = byAgePlan.snapshots.associateBy { it.snapshotId }
        fun verdict(v: model.PaimonSnapshotExpiryVerdict): String = when {
            v.retained -> "kept — " + v.describeKeptBy()
            v.tags.isNotEmpty() -> "REMOVED — lives on as tag " + v.tags.joinToString(", ")
            else -> "REMOVED"
        }
        WideTable(
            headers = listOf("Under the table's options", "Snapshot ID", "With retain_min = 1, older_than = now"),
            columnWidths = listOf(190.dp, 120.dp, 300.dp),
            rows = byDefaults.snapshots.map { v ->
                listOf(verdict(v), v.snapshotId.toString(), verdict(byAgeById.getValue(v.snapshotId)))
            },
            leadCellColors = byDefaults.snapshots.map { if (it.retained) null else colors.error },
        )
    }
}

/**
 * What `expire_changelogs` would remove from `changelog/` — [planChangelogExpiry], the twin of
 * [PaimonExpirySection] over the long-lived changelogs, drawn only on a table whose changelog
 * lifecycle is decoupled or whose `changelog/` holds something. The counts are against the
 * latest *snapshot* id, so the section says the floor, which is the figure that explains why a
 * maximum of four holds two changelogs beside two snapshots.
 */
@Composable
internal fun PaimonChangelogExpirySection(input: PaimonExpiryInput, nowMs: Long) {
    val colors = MaterialTheme.colorScheme
    if (input.changelogTimes.isEmpty() && !paimonChangelogLifecycleDecoupled(input.tableOptions)) return
    val plans = runCatching {
        input.planChangelogExpiry(PaimonExpiryOptions(nowMs = nowMs)) to
            input.planChangelogExpiry(PaimonExpiryOptions(nowMs = nowMs, retainMin = 1, olderThanMs = nowMs))
    }
    val byAge = plans.getOrNull()?.second
    val title = "Changelog Expiry" + if (byAge != null && byAge.removed.isNotEmpty()) " — ${byAge.removed.size} would go" else ""
    CountedSection(title, input.changelogTimes.size, "long-lived changelogs") {
        val (byDefaults, byAgePlan) = plans.getOrElse { failure ->
            Text(
                "expire_changelogs would refuse this table's options: ${failure.message}",
                fontSize = TypeScale.small,
                fontWeight = FontWeight.Bold,
                color = colors.error,
            )
            return@CountedSection
        }
        val retainMaxLabel = if (byDefaults.retainMax == Int.MAX_VALUE) "unbounded" else "${byDefaults.retainMax}"
        Text(
            "What expire_changelogs would keep of changelog/, the way ExpireChangelogImpl decides it: " +
                "the changelog lifecycle is decoupled here (changelog.num-retained.* or changelog.time-retained " +
                "above the snapshot setting), so an expired snapshot is written again under changelog/ with its " +
                "change stream kept, and this walk retires those. The counts are against the latest snapshot id — " +
                "the live snapshots count toward changelog.num-retained.max ($retainMaxLabel here) and " +
                ".min (${byDefaults.retainMin}) — so everything below " +
                "${byDefaults.floor?.let { "changelog $it" } ?: "the floor"} goes whatever its age; a consumer's next " +
                "snapshot, snapshot.expire.limit (${byDefaults.maxDeletes}) and the latest changelog bound the end, " +
                "and between them the run stops at the first changelog younger than changelog.time-retained " +
                "(${formatRetentionMs(nowMs - byDefaults.cutoffMs).substringBefore(" (")}). It runs at commit time; " +
                "Spark has no procedure for it and Flink's expire_changelogs action is the call.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (byDefaults.changelogs.isEmpty()) {
            Text("changelog/ holds nothing yet: the next snapshot to expire is written there.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            return@CountedSection
        }
        val byAgeById = byAgePlan.changelogs.associateBy { it.snapshotId }
        fun verdict(v: model.PaimonSnapshotExpiryVerdict): String = if (v.retained) "kept — " + v.describeKeptBy().ifEmpty { "the latest changelog, never removed by the run" } else "REMOVED"
        WideTable(
            headers = listOf("Under the table's options", "Changelog ID", "With retain_min = 1, older_than = now"),
            columnWidths = listOf(190.dp, 120.dp, 300.dp),
            rows = byDefaults.changelogs.map { v ->
                listOf(verdict(v), v.snapshotId.toString(), verdict(byAgeById.getValue(v.snapshotId)))
            },
            leadCellColors = byDefaults.changelogs.map { if (it.retained) null else colors.error },
        )
    }
}

/**
 * Which partitions `expire_partitions` would drop — or a write would, once
 * `partition.expiration-check-interval` has passed — under the table's own options; see
 * [planPaimonPartitionExpiry]. Drawn on a partitioned table only. One column, like the rewrite
 * section: the option a reader reaches for is `partition.expiration-time`, and a table without it
 * has nothing a call could do.
 */
@Composable
internal fun PaimonPartitionExpirySection(input: PaimonExpiryInput, nowMs: Long) {
    val colors = MaterialTheme.colorScheme
    if (input.partitions.isEmpty()) return
    val plan = input.planPartitionExpiry(nowMs)
    val title = "Partition Expiry" + if (plan.dropped.isNotEmpty()) " — ${plan.dropped.size} would go" else ""
    CountedSection(title, plan.partitions.size, "partitions") {
        val strategy = when (plan.strategy) {
            PaimonPartitionExpireStrategy.VALUES_TIME -> "values-time reads a time off the partition's values — " +
                (plan.pattern?.let { "partition.timestamp-pattern '$it' with each \$field filled in" } ?: "the first partition field's value, no partition.timestamp-pattern being set") +
                ", through " + (plan.formatter?.let { "partition.timestamp-formatter '$it'" } ?: "the default yyyy-MM-dd[ HH:mm:ss] formatter") +
                " — and a partition whose time is before the cutoff goes; a value that does not parse, or a null, is warned about and kept, which is every DATE partition column, since the row's getter spells one as its epoch day"
            PaimonPartitionExpireStrategy.UPDATE_TIME -> "update-time drops a partition whose newest file — the greatest _CREATION_TIME over the manifests' entries, removals included — is before the cutoff"
            PaimonPartitionExpireStrategy.CUSTOM -> "custom names a factory of the table's own, which is not read here"
            null -> "'${plan.strategySpelled}' is not a strategy release-1.3.1 knows"
        }
        Text(
            "What expire_partitions would drop, the way PartitionExpire decides it: the latest snapshot's partitions " +
                "folded from every manifest entry, a removal subtracting; " +
                (plan.expirationMs?.let { "the cutoff is now less partition.expiration-time (${formatRetentionMs(it).substringBefore(" (")}); " } ?: "no partition.expiration-time is set, so a write expires nothing and a bare call is refused; ") +
                "$strategy. At most partition.expiration-max-num (${plan.maxNum}) go in one run, smallest values first, as one OVERWRITE commit removing their files from the manifests — the files stay on disk until a snapshot expiry reaches that commit's removals. " +
                "A write checks this every partition.expiration-check-interval (${formatRetentionMs(plan.checkIntervalMs).substringBefore(" (")}); the procedure checks at once. A write-only table never runs it.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (plan.heldBack > 0) Text(
            "${formatCounted(plan.heldBack, "more partition")} past the cutoff wait for a later run, past partition.expiration-max-num.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        val droppedPaths = plan.dropped.map { it.entry.partition.path }.toSet()
        val rows = plan.partitions.sortedWith(compareBy({ !it.expired }, { it.entry.partition.path }))
        WideTable(
            headers = listOf("Verdict", "Partition", "Why", "Files", "Rows", "Bytes", "Newest File"),
            columnWidths = listOf(120.dp, 220.dp, 360.dp, 60.dp, 80.dp, 100.dp, 200.dp),
            rows = rows.map { v ->
                listOf(
                    if (v.entry.partition.path in droppedPaths) "DROPPED" else if (v.expired) "held back" else "kept",
                    v.entry.partition.display,
                    v.reason,
                    formatCount(v.entry.fileCount),
                    formatCount(v.entry.recordCount),
                    formatBytes(v.entry.fileSizeBytes),
                    v.entry.lastFileCreationTimeMs?.let(::formatAppTimestamp) ?: "—",
                )
            },
            leadCellColors = rows.map { if (it.entry.partition.path in droppedPaths) colors.error else null },
        )
    }
}

/**
 * Which tags `expire_tags` removes, under a bare call and under `older_than = now`, and what
 * removing each would free — see [planTagExpiry] and [planTagDeletion]. Drawn where the table
 * has tags; it is also the panel's list of them, since a tag otherwise appears only as a chip on
 * its snapshot.
 */
@Composable
internal fun PaimonTagExpirySection(node: GraphNode.TableNode, input: PaimonExpiryInput, nowMs: Long) {
    val colors = MaterialTheme.colorScheme
    if (input.tags.isEmpty()) return
    val bare = input.planTagExpiry(nowMs)
    val byAge = input.planTagExpiry(nowMs, olderThanMs = nowMs)
    val deletions = input.tags.associate { it.name to node.paimonExpiryFiles.value?.planTagDeletion(it.name) }
    val title = "Tag Expiry" + if (bare.removed.isNotEmpty()) " — ${bare.removed.size} would go" else ""
    CountedSection(title, input.tags.size, "tags") {
        Text(
            "What expire_tags would remove, the way TagTimeExpire decides it: a tag records a create time and a " +
                "retention only when created with one (time_retained, or tag.default-time-retained), and a bare call " +
                "removes a tag whose create time plus retention is before now — never one recording neither; " +
                "older_than removes any tag created before it, a tag recording no create time going by its file's " +
                "modification time — the create time is a local time with no zone recorded, compared in this " +
                "machine's zone as the procedure run here would. The same check runs at every commit. Removing a tag whose snapshot is still " +
                "retained deletes the tag file alone; one whose snapshot has expired also frees what the tag alone " +
                "held, against the tag before it and the nearer of the earliest snapshot and the tag after it.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        val byAgeByName = byAge.tags.associateBy { it.tag.name }
        fun verdict(v: model.PaimonTagExpiryVerdict): String = if (v.expired) "REMOVED — " + v.reason else "kept — " + v.reason
        WideTable(
            headers = listOf("Under a bare call", "Tag", "Snapshot", "Created", "Retained", "Expires", "With older_than = now", "Removal Frees"),
            columnWidths = listOf(230.dp, 120.dp, 80.dp, 170.dp, 90.dp, 170.dp, 300.dp, 360.dp),
            rows = bare.tags.map { v ->
                val deletion = deletions[v.tag.name]
                listOf(
                    verdict(v),
                    v.tag.name,
                    v.tag.snapshotId?.toString() ?: "—",
                    v.createdMs?.let(::formatAppTimestamp) ?: "not recorded",
                    v.tag.timeRetainedMs?.let(::formatPaimonDurationMs) ?: "none",
                    v.expiresAtMs?.let(::formatAppTimestamp) ?: "—",
                    verdict(byAgeByName.getValue(v.tag.name)),
                    deletion?.let { d -> d.describe + " — " + d.note } ?: "not readable",
                )
            },
            leadCellColors = bare.tags.map { if (it.expired) colors.error else null },
        )
    }
}

/**
 * What rolling the table back to this snapshot removes, and what it leaves on disk — see
 * [planRollback] and [rollbackLeftovers]. On `main`'s retained snapshots only: a branch rolls back
 * on its own ids, and a tag-only or changelog-only snapshot is not in `snapshot/` to roll back to
 * (rolling back to a *tag* writes the snapshot file back, which is the same plan from its id).
 */
@Composable
internal fun PaimonRollbackSection(node: GraphNode.PaimonSnapshotNode, graph: GraphModel) {
    val colors = MaterialTheme.colorScheme
    if (node.branch != null || node.retainedByTagOnly || node.retainedByChangelogOnly) return
    val id = node.data.id ?: return
    val table = graph.nodes.filterIsInstance<GraphNode.TableNode>().firstOrNull() ?: return
    val input = table.summary.paimonExpiry ?: return
    val plan = input.planRollback(id) ?: return
    val leftovers = if (plan.removesAnything) table.paimonExpiryFiles.value?.rollbackLeftovers(id) else emptyList()
    val title = "Rollback" + if (plan.removesAnything) " — ${formatCounted(plan.removedSnapshots.size, "snapshot")} would go" else ""
    Section(title) {
        Text(
            "What rollback(snapshot => $id) removes, the way RollbackHelper.cleanLargerThan does it: the snapshot " +
                "files above this one, with LATEST moved here; the long-lived changelog files above it; and every tag " +
                "whose snapshot is above it — and nothing else. The data files, manifests and manifest lists those " +
                "commits wrote stay on disk, named by nothing, until remove_orphan_files; Iceberg's rollback keeps " +
                "the abandoned commit for an expiry to remove, Paimon's forgets it at once.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (!plan.removesAnything) {
            Text("This is the latest snapshot: a rollback to it removes nothing.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            return@Section
        }
        Text(
            "Removes snapshot${if (plan.removedSnapshots.size == 1) "" else "s"} ${plan.removedSnapshots.sorted().joinToString(", ")}" +
                (if (plan.removedChangelogs.isNotEmpty()) ", changelog${if (plan.removedChangelogs.size == 1) "" else "s"} ${plan.removedChangelogs.sorted().joinToString(", ")}" else "") +
                (if (plan.removedTags.isNotEmpty()) ", and tag${if (plan.removedTags.size == 1) "" else "s"} ${plan.removedTags.joinToString(", ")}" else "; no tag") +
                (if (plan.keptTags.isNotEmpty()) " (${plan.keptTags.joinToString(", ")} kept)" else "") + ".",
            fontSize = TypeScale.small,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        when {
            leftovers == null -> Text("What it leaves on disk is not readable here: the manifests could not be read.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            leftovers.isEmpty() -> Text("It leaves nothing behind: every file those commits name is also named by what stays.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            else -> {
                val byKind = PaimonExpiryFileKind.entries.mapNotNull { k -> leftovers.count { it.kind == k }.takeIf { it > 0 }?.let { "$it ${k.label}${if (it == 1) "" else "s"}" } }
                Text(
                    "Left on disk, named by nothing: ${byKind.joinToString(", ")}, ${formatBytes(leftovers.sumOf { it.sizeBytes ?: 0L })} the metadata can account for.",
                    fontSize = TypeScale.small,
                    color = verdictUnevaluatedColor(),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                WideTable(
                    headers = listOf("Kind", "File", "Bytes", "Written By"),
                    columnWidths = listOf(120.dp, 520.dp, 100.dp, 90.dp),
                    rows = leftovers.take(MAX_EXPIRY_FILE_ROWS).map { f -> listOf(f.kind.label, f.path ?: f.name, f.sizeBytes?.let(::formatBytes) ?: "—", f.snapshotId?.let { "snapshot $it" } ?: "—") },
                )
                if (leftovers.size > MAX_EXPIRY_FILE_ROWS) Text("${leftovers.size - MAX_EXPIRY_FILE_ROWS} more not listed.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            }
        }
    }
}

/**
 * What setting `main` back to this snapshot does, and what the expiry after it would free — see
 * [planRollback] on the Iceberg side: `rollback_to_snapshot` only onto a current ancestor,
 * `set_current_snapshot` onto anything, `rollback_to_timestamp` onto the newest ancestor before
 * a time; none writes a snapshot, and the commits main moves past wait for an expiry. The
 * Paimon twin is [PaimonRollbackSection], and the difference is the point: Paimon deletes the
 * snapshot files at once and leaves the data as orphans, Iceberg keeps everything until an
 * expiry frees the reverted commits' files. Drawn on every retained, unexpired snapshot; the
 * current one says a rollback to it does nothing.
 */
/**
 * What `expire_snapshots(snapshot_ids => array(<id>))` does with this snapshot — `expireSnapshotId`
 * in `RemoveSnapshots` at 1.8.1: refused when a surviving ref names it (`Cannot expire %s. Still
 * referenced by refs: %s`), else removed whatever its age or place on a branch, its children left
 * naming a parent the table no longer holds; the age rules run beside it, held at the epoch here so
 * the line is this snapshot's alone. What the removal frees is the same cleanup the `Expiry Files`
 * section plans, over this one id.
 */
@Composable
internal fun ExpireByIdSection(node: GraphNode.SnapshotNode, graph: GraphModel, nowMs: Long) {
    val colors = MaterialTheme.colorScheme
    if (node.expired) return
    val meta = graph.newestMetadata() ?: return
    val id = node.data.snapshotId ?: return
    val plan = meta.planExpiry(ExpiryOptions(nowMs = nowMs, olderThanMs = 0L, snapshotIds = setOf(id)))
    val refusal = plan.refusal
    val verdict = plan.snapshots.firstOrNull { it.snapshotId == id }
    val files = if (refusal == null) graph.tableNode()?.expiryFiles?.value?.copy(metadata = meta)?.planExpiryFiles(setOf(id)) else null
    val children = meta.snapshots.filter { it.parentSnapshotId == id }.mapNotNull { it.snapshotId }
    val title = "Expire By Id" + when {
        refusal != null -> " — refused"
        files != null -> " — ${files.describe} would go"
        else -> ""
    }
    Section(title) {
        Text(
            "What expire_snapshots(snapshot_ids => array($id)) does, the way RemoveSnapshots.expireSnapshotId does " +
                "it: the id is refused while a surviving ref names it, and otherwise removed whatever its age or its " +
                "place on a branch — within min-snapshots-to-keep or not — with the age rules running beside it. The " +
                "commits after it keep a parent-snapshot-id the table no longer holds, so the line reads as starting " +
                "at the child. The files freed are the same cleanup Expiry Files plans, over this one id.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (refusal != null) {
            Text("REFUSED — $refusal", fontSize = TypeScale.small, fontWeight = FontWeight.Bold, color = colors.error)
            return@Section
        }
        val kept = verdict?.keptBy.orEmpty()
        Text(
            "REMOVES it" + (if (kept.isNotEmpty()) " — a bare call would keep it, ${verdict!!.describeKeptBy()}" else " — on no ref, which a bare call under older_than = now removes too") +
                (if (children.isNotEmpty()) "; ${formatCounted(children.size, "child")} (${children.joinToString(", ")}) left naming it as parent" else "; no child names it") + ".",
            fontSize = TypeScale.small,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        // The snapshot log is cut before the removed entry: what a TIMESTAMP AS OF can still reach.
        if (refusal == null && plan.logEntriesDropped > 0) {
            val earliestBefore = plan.snapshotLog.firstOrNull()?.timestampMs
            val earliestAfter = plan.logAfter.firstOrNull()?.timestampMs
            val retainedBefore = plan.logEntriesDropped - 1
            Text(
                "Cuts the snapshot log: ${formatCounted(plan.logEntriesDropped, "entry", "entries")} of ${plan.snapshotLog.size} ${if (plan.snapshotLog.size == 1) "goes" else "go"} — its own" +
                    (if (retainedBefore > 0) " and the ${formatCounted(retainedBefore, "entry", "entries")} before it, which ${if (retainedBefore == 1) "names a retained snapshot" else "name retained snapshots"}" else "") +
                    " (TableMetadata.updateSnapshotLog: a gap would read the child as current over the removed commit's time). " +
                    when {
                        earliestAfter == null -> "No TIMESTAMP AS OF resolves afterwards."
                        retainedBefore > 0 -> "The earliest TIMESTAMP AS OF that resolves moves from ${formatAppTimestamp(earliestBefore)} to ${formatAppTimestamp(earliestAfter)}; VERSION AS OF still reads the retained snapshots before it."
                        else -> "The earliest TIMESTAMP AS OF that resolves moves from ${formatAppTimestamp(earliestBefore)} to ${formatAppTimestamp(earliestAfter)}."
                    },
                fontSize = TypeScale.small,
                color = if (retainedBefore > 0) verdictUnevaluatedColor() else colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        when {
            files == null -> Text("What it frees is not readable here: the manifest lists could not be read.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            files.files.isEmpty() -> Text("Frees nothing but the snapshot's entry: its manifest list and manifests are still listed by a retained snapshot, and no data file was live in it alone.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            else -> {
                Text(
                    "Frees ${files.describe} by the reachable diff — the procedure's, and the core API's too once an id is specified — ${formatBytes(files.knownBytes)} the metadata accounts for" +
                        (if (files.knownBytes == 0L) " (a manifest list records no size)" else "") + ":",
                    fontSize = TypeScale.small,
                    color = verdictUnevaluatedColor(),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                WideTable(
                    headers = listOf("Kind", "File", "Bytes", "Why"),
                    columnWidths = listOf(120.dp, 520.dp, 100.dp, 420.dp),
                    rows = files.files.take(MAX_EXPIRY_FILE_ROWS).map { f -> listOf(f.kind.label, f.path, f.sizeBytes?.let(::formatBytes) ?: "—", f.reason.label) },
                    leadCellColors = files.files.take(MAX_EXPIRY_FILE_ROWS).map { if (it.kind == ExpiryFileKind.DATA_FILE || it.kind == ExpiryFileKind.DELETE_FILE) colors.error else null },
                )
                if (files.files.size > MAX_EXPIRY_FILE_ROWS) Text("${files.files.size - MAX_EXPIRY_FILE_ROWS} more not listed.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun IcebergRollbackSection(node: GraphNode.SnapshotNode, graph: GraphModel, nowMs: Long) {
    val colors = MaterialTheme.colorScheme
    if (node.expired) return
    val meta = graph.newestMetadata() ?: return
    val id = node.data.snapshotId ?: return
    val plan = meta.planRollback(id, nowMs)
    val title = "Rollback" + when (plan.verdict) {
        IcebergRollbackVerdict.MOVES -> " — ${formatCounted(plan.leftBehind.size, "commit")} would be left behind"
        IcebergRollbackVerdict.NOT_AN_ANCESTOR -> " — refused, set_current_snapshot would leave ${formatCounted(plan.leftBehind.size, "commit")}"
        else -> ""
    }
    Section(title) {
        Text(
            "What rollback_to_snapshot($id) does, the way SetSnapshotOperation does it: main is pointed at " +
                "this snapshot — only ever at an ancestor of the current one — and a snapshot-log entry records the " +
                "moment; set_current_snapshot takes any snapshot the table holds, and rollback_to_timestamp picks " +
                "the newest current ancestor before the time. No snapshot is written and nothing is deleted: the " +
                "commits main moves past stay in the metadata, on no ref unless a tag or branch names them, until " +
                "an expiry removes them and frees the files they added. Paimon's rollback deletes the snapshot files " +
                "at once and leaves their data as orphans.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        when (plan.verdict) {
            IcebergRollbackVerdict.NOTHING_TO_DO -> {
                Text("This is the current snapshot: a rollback to it changes nothing.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
                plan.timestampWindow?.let { w -> Text(windowText(w), fontSize = TypeScale.small, color = colors.onSurfaceVariant) }
                return@Section
            }
            IcebergRollbackVerdict.UNKNOWN_SNAPSHOT -> {
                Text(plan.reason, fontSize = TypeScale.small, color = colors.error)
                return@Section
            }
            else -> {}
        }
        Text(
            when (plan.verdict) {
                IcebergRollbackVerdict.MOVES -> "rollback_to_snapshot: ALLOWED — ${plan.reason}."
                else -> "rollback_to_snapshot: REFUSED — ${plan.reason}."
            },
            fontSize = TypeScale.small,
            fontWeight = FontWeight.Bold,
            color = if (plan.verdict == IcebergRollbackVerdict.MOVES) colors.onSurface else colors.error,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        plan.timestampWindow?.let { w -> Text(windowText(w), fontSize = TypeScale.small, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp)) }
        if (plan.leftBehind.isNotEmpty()) {
            WideTable(
                headers = listOf("Left Behind", "Still Held By", "Then"),
                columnWidths = listOf(200.dp, 200.dp, 520.dp),
                rows = plan.leftBehind.map { s ->
                    listOf(
                        "snapshot ${graph.nodeById["snap_${s.snapshotId}"]?.let { (it as? GraphNode.SnapshotNode)?.simpleId } ?: s.snapshotId}",
                        if (s.heldBy.isEmpty()) "no ref" else s.heldBy.joinToString(", "),
                        if (s.heldBy.isEmpty()) "on no ref and off the current line: the next expiry removes it and frees what it added"
                        else "kept by the ref for as long as the ref lasts; its files stay",
                    )
                },
                leadCellColors = plan.leftBehind.map { if (it.heldBy.isEmpty()) verdictSkippedColor() else null },
            )
        }
        // The expiry after: planned on the metadata the rollback writes, under older_than = now.
        val after = plan.after
        if (after != null) {
            val expiry = after.planExpiry(ExpiryOptions(nowMs = nowMs, olderThanMs = nowMs))
            val removedBehind = plan.leftBehind.filter { it.snapshotId in expiry.removed }
            val files = graph.tableNode()?.expiryFiles?.value?.copy(metadata = after)?.planExpiryFiles(expiry.removed.toSet())
            Text(
                "An expire_snapshots(older_than = now) after it would remove ${formatCounted(expiry.removed.size, "snapshot")}" +
                    (if (removedBehind.isNotEmpty()) ", the ${formatCounted(removedBehind.size, "left-behind commit")} among them" else ", none of them left behind") +
                    (files?.let { f -> ", and free ${f.describe}" + (if (f.files.isNotEmpty()) " — ${formatBytes(f.knownBytes)} the metadata accounts for, ${f.cleanup.label}" else "") } ?: "") + ".",
                fontSize = TypeScale.small,
                color = if (removedBehind.isNotEmpty()) verdictUnevaluatedColor() else colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private fun windowText(w: LongRange): String =
    "rollback_to_timestamp lands here for a time from ${formatAppTimestampExact(w.first)}" +
        (if (w.last == Long.MAX_VALUE) " on" else " up to ${formatAppTimestampExact(w.last)}") + "."

/**
 * What `cherrypick_snapshot` would do with this snapshot — see [planCherryPick]: fast-forward
 * main onto it when its parent is the current snapshot, publish an append (or a dynamic
 * overwrite) as a new commit carrying its files, or refuse — a duplicate wap id, an ancestor,
 * a snapshot already picked, a delete. Drawn on every unexpired snapshot, since which refusal
 * applies is the answer on the current line and the publish is the answer off it.
 */
@Composable
internal fun CherryPickSection(node: GraphNode.SnapshotNode, graph: GraphModel) {
    val colors = MaterialTheme.colorScheme
    if (node.expired) return
    val meta = graph.newestMetadata() ?: return
    val id = node.data.snapshotId ?: return
    val plan = meta.planCherryPick(id)
    val title = "Cherry-Pick — " + when (plan.verdict) {
        CherryPickVerdict.FAST_FORWARD -> "would fast-forward main here"
        CherryPickVerdict.PUBLISH -> "would publish " + (plan.addedDataFiles?.let { formatCounted(it, "data file") } ?: "its files")
        else -> "refused"
    }
    Section(title) {
        Text(
            "What cherrypick_snapshot($id) does, the way CherryPickOperation decides it: a snapshot whose parent " +
                "is the current snapshot fast-forwards main onto it with no new snapshot; otherwise an append — or an " +
                "overwrite written by replace-partitions — is published as a new commit on main carrying its files, " +
                "source-snapshot-id naming it and published-wap-id its staged wap.id; a snapshot already on the " +
                "current line, one some ancestor was already picked from, a wap id already published, or a delete " +
                "is refused. This is the publish half of write-audit-publish.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            "${plan.verdict.label} — ${plan.reason}.",
            fontSize = TypeScale.small,
            fontWeight = FontWeight.Bold,
            color = if (plan.verdict.moves) verdictSkippedColor() else colors.onSurface,
        )
        if (plan.verdict == CherryPickVerdict.PUBLISH) {
            Text(
                "The published commit lists the same data files — copied by reference into a manifest of its own, nothing rewritten" +
                    (if (plan.replacesPartitions) "; its ${plan.deletedDataFiles ?: "?"} deleted files are deleted again, and the commit fails if any partition it replaces has been written to since its parent" else "") + ".",
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * What `fast_forward(table, branch, to)` would do for every branch against every other ref —
 * see [planFastForward]: a move when the branch's tip is an ancestor of the target's snapshot,
 * nothing when the two are at one snapshot, refused otherwise. Drawn on the metadata panel
 * beside the refs it moves, and only where there is more than one ref to move between.
 */
@Composable
internal fun IcebergFastForwardSection(node: GraphNode.MetadataNode) {
    val colors = MaterialTheme.colorScheme
    val meta = node.data
    if (meta.refs.size < 2) return
    val plans = meta.fastForwardPlans()
    val moving = plans.count { it.moves }
    val title = "Fast-Forward — " + if (moving == 0) "nothing moves" else "$moving of ${formatCounted(plans.size, "pair")} would move"
    CountedSection(title, plans.size, "pairs") {
        Text(
            "What fast_forward(branch, to) does for each branch against each other ref, the way " +
                "UpdateSnapshotReferencesOperation decides it: the branch moves to the ref's snapshot when its own " +
                "tip is an ancestor of that snapshot, and is refused otherwise — two lines that have both " +
                "committed since they forked cannot be fast-forwarded either way. A name the table has no ref " +
                "for is created there. Nothing is deleted and no snapshot is written: the old tip stays reachable " +
                "from the new one.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        WideTable(
            headers = listOf("Verdict", "Branch", "To", "Gains", "Why"),
            columnWidths = listOf(120.dp, 110.dp, 110.dp, 90.dp, 620.dp),
            rows = plans.map { p ->
                listOf(
                    p.verdict.label,
                    p.branch,
                    p.to,
                    if (p.verdict == IcebergFastForwardVerdict.MOVES) formatCounted(p.gained.size, "commit") else "—",
                    p.reason,
                )
            },
            leadCellColors = plans.map { if (it.moves) verdictSkippedColor() else null },
        )
    }
}

/**
 * What `sys.fast_forward(table, branch)` does to main for each branch — see [planFastForward]
 * on the Paimon side: main's snapshot, schema and tag files from the branch's earliest snapshot
 * id on are deleted and the branch's copied in, so main's own commits from that id are dropped,
 * not merged, and the files they wrote are left named by nothing. Drawn on the table panel
 * where the table has a branch, with the leftovers listed the way the rollback lists its own.
 */
@Composable
internal fun PaimonFastForwardSection(node: GraphNode.TableNode) {
    val colors = MaterialTheme.colorScheme
    val branches = node.summary.branches.orEmpty()
    if (branches.isEmpty()) return
    val files = node.paimonExpiryFiles.value
    val plans = files?.let { f -> branches.mapNotNull { f.planFastForward(it.name) } }
    val dropping = plans.orEmpty().count { it.refused == null && it.droppedCommits > 0 }
    val title = "Fast-Forward" + when {
        plans == null -> ""
        dropping > 0 -> " — ${formatCounted(plans.filter { it.refused == null }.sumOf { it.droppedCommits }, "commit")} of main would go"
        else -> " — main loses nothing"
    }
    CountedSection(title, branches.size, "branches") {
        Text(
            "What sys.fast_forward(branch) does to main, the way FileSystemBranchManager.fastForward does it: " +
                "main's snapshot files from the branch's earliest snapshot id on, its schema files from that " +
                "snapshot's schema id on, and every tag at or above the id are deleted, and the branch's snapshot/, " +
                "schema/ and tag/ are copied over main's. Main's own commits from that id on are not merged — they " +
                "are gone, and the data files, manifests and manifest lists they wrote stay on disk named by " +
                "nothing, until remove_orphan_files. The branch itself is left in place.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (plans == null) {
            Text("Not readable here: the manifests could not be read.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            return@CountedSection
        }
        fun ids(xs: List<Long>) = xs.sorted().joinToString(", ")
        WideTable(
            headers = listOf("Verdict", "Branch", "Main Loses", "Main Gains", "Left Named By Nothing", "Why"),
            columnWidths = listOf(200.dp, 90.dp, 260.dp, 240.dp, 200.dp, 520.dp),
            rows = plans.map { p ->
                val refused = p.refused
                if (refused != null) listOf("REFUSED", p.branch, "—", "—", "—", refused)
                else listOf(
                    if (p.droppedCommits > 0) "DROPS ${formatCounted(p.droppedCommits, "commit")} of main" else "replaces main with its own line",
                    p.branch,
                    listOfNotNull(
                        p.removedSnapshots.takeIf { it.isNotEmpty() }?.let { "snapshot${if (it.size == 1) "" else "s"} ${ids(it)}" },
                        p.removedSchemas.takeIf { it.isNotEmpty() }?.let { "schema${if (it.size == 1) "" else "s"} ${it.sorted().joinToString(", ")}" },
                        p.removedTags.takeIf { it.isNotEmpty() }?.let { "tag${if (it.size == 1) "" else "s"} ${it.joinToString(", ")}" },
                    ).joinToString("; ").ifEmpty { "nothing" },
                    listOfNotNull(
                        "snapshot${if (p.arrivingSnapshots.size == 1) "" else "s"} ${ids(p.arrivingSnapshots)}",
                        p.arrivingSchemas.takeIf { it.isNotEmpty() }?.let { "schema${if (it.size == 1) "" else "s"} ${it.sorted().joinToString(", ")}" },
                        p.arrivingTags.takeIf { it.isNotEmpty() }?.let { "tag${if (it.size == 1) "" else "s"} ${it.joinToString(", ")}" },
                    ).joinToString("; "),
                    if (p.leftovers.isEmpty()) "nothing" else "${formatCounted(p.leftovers.size, "file")}, ${formatBytes(p.leftoverBytes)}",
                    "${p.branch}'s earliest snapshot is ${p.earliestId}" +
                        (if (p.identicalSnapshots.isNotEmpty()) "; snapshot${if (p.identicalSnapshots.size == 1) "" else "s"} ${ids(p.identicalSnapshots)} the branch carries verbatim" else "") +
                        (if (p.droppedCommits > 0) "; main's ${formatCounted(p.droppedCommits, "later commit")} ${if (p.droppedCommits == 1) "is" else "are"} forgotten" else ""),
                )
            },
            leadCellColors = plans.map { if (it.refused == null && it.droppedCommits > 0) colors.error else null },
        )
        val leftovers = plans.flatMap { p -> p.leftovers.map { p.branch to it } }
        if (leftovers.isNotEmpty()) {
            Text(
                "Left on disk after the call, named by nothing:",
                fontSize = TypeScale.small,
                color = verdictUnevaluatedColor(),
                modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
            )
            WideTable(
                headers = listOf("Kind", "File", "Bytes", "Written By", "After"),
                columnWidths = listOf(120.dp, 520.dp, 100.dp, 90.dp, 90.dp),
                rows = leftovers.take(MAX_EXPIRY_FILE_ROWS).map { (branch, f) -> listOf(f.kind.label, f.path ?: f.name, f.sizeBytes?.let(::formatBytes) ?: "—", f.snapshotId?.let { "snapshot $it" } ?: "—", branch) },
            )
            if (leftovers.size > MAX_EXPIRY_FILE_ROWS) Text("${leftovers.size - MAX_EXPIRY_FILE_ROWS} more not listed.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
        }
    }
}

/**
 * What `sys.purge_files(table)` leaves and takes — [planPurge], read off `FileStoreTable.purgeFiles()`:
 * every branch, tag and consumer, a truncating `OVERWRITE`, every snapshot expired behind it,
 * `changelog/` whole, then an orphan clean. Drawn on the table panel, in the error colour where
 * anything goes, because it is the one procedure that empties the table by design; `brp` is
 * `br` after it.
 */
@Composable
internal fun PaimonPurgeSection(node: GraphNode.TableNode) {
    val colors = MaterialTheme.colorScheme
    if (node.summary.paimonExpiry == null) return
    val plan = node.paimonExpiryFiles.value?.planPurge()
    val title = "Purge" + when {
        plan == null -> ""
        plan.removesAnything -> " — ${formatCounted(plan.removed.size, "file")} would go"
        else -> " — nothing to take"
    }
    Section(title) {
        Text(
            "What sys.purge_files(table) does, in the order FileStoreTable.purgeFiles runs it: every branch is " +
                "dropped, every tag and every consumer deleted; the table is truncated by one OVERWRITE commit " +
                "whose delta holds a DELETE entry per live file of the latest snapshot and whose base list carries " +
                "the latest snapshot's own manifests forward; changelog/ is deleted whole; every snapshot but the " +
                "truncate is expired at once, whatever its age, which frees the data files; and an orphan clean at " +
                "now takes what nothing names any more. The schema files stay, and the table reads as empty and " +
                "takes the next INSERT.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (plan == null) {
            Text("Not readable here: the manifests could not be read.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            return@Section
        }
        val truncate = plan.truncateSnapshotId?.let { "snapshot $it, an OVERWRITE with deltaRecordCount ${plan.deltaRecordCount} and totalRecordCount 0" } ?: "nothing: the table has no snapshot to truncate"
        Text(
            "Writes $truncate; keeps " +
                (PaimonExpiryFileKind.entries.mapNotNull { k -> plan.kept.count { it.kind == k }.takeIf { it > 0 }?.let { "$it ${k.label}${if (it == 1) "" else "s"}" } }.joinToString(", ").ifEmpty { "nothing" }) +
                " of the metadata, beside the truncate's own ${formatCounted(plan.newFiles, "new file")}.",
            fontSize = TypeScale.small,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        val named = listOfNotNull(
            plan.snapshotIds.takeIf { it.isNotEmpty() }?.let { "snapshot${if (it.size == 1) "" else "s"} ${it.joinToString(", ")} expired" },
            plan.branches.takeIf { it.isNotEmpty() }?.let { "branch${if (it.size == 1) "" else "es"} ${it.joinToString(", ")} dropped" },
            plan.tags.takeIf { it.isNotEmpty() }?.let { "tag${if (it.size == 1) "" else "s"} ${it.joinToString(", ")} deleted" },
            plan.consumers.takeIf { it.isNotEmpty() }?.let { "consumer${if (it.size == 1) "" else "s"} ${it.joinToString(", ")} deleted" },
        )
        if (!plan.removesAnything) {
            Text("Nothing to take: the table holds no snapshot, tag, branch or consumer.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            return@Section
        }
        Text(named.joinToString("; ") + ".", fontSize = TypeScale.small, color = colors.error, modifier = Modifier.padding(bottom = 4.dp))
        val byKind = PaimonExpiryFileKind.entries.mapNotNull { k -> plan.ofKind(k).size.takeIf { it > 0 }?.let { "$it ${k.label}${if (it == 1) "" else "s"}" } }
        Text(
            "Taken from disk: ${byKind.joinToString(", ")}, ${formatBytes(plan.removedBytes)} the metadata can account for.",
            fontSize = TypeScale.small,
            color = colors.error,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        WideTable(
            headers = listOf("Kind", "File", "Bytes", "Named By", "Taken By"),
            columnWidths = listOf(120.dp, 520.dp, 100.dp, 110.dp, 360.dp),
            rows = plan.removed.take(MAX_EXPIRY_FILE_ROWS).map { f -> listOf(f.kind.label, f.path ?: f.name, f.sizeBytes?.let(::formatBytes) ?: "—", f.snapshotId?.let { "snapshot $it" } ?: "—", f.reason.label) },
            leadCellColors = plan.removed.take(MAX_EXPIRY_FILE_ROWS).map { if (it.kind == PaimonExpiryFileKind.DATA_FILE) colors.error else null },
        )
        if (plan.removed.size > MAX_EXPIRY_FILE_ROWS) Text("${plan.removed.size - MAX_EXPIRY_FILE_ROWS} more not listed.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
    }
}

/**
 * What `rewrite_data_files` would rewrite at this snapshot under a bare call — [planRewrite], the
 * rules of Iceberg's `SizeBasedDataRewriter`, over the same live set and delete pairing the
 * sections above already read. The options come from the table: `write.target-file-size-bytes`
 * off the latest metadata, and the current spec, since a file under an older spec is planned as
 * unpartitioned.
 *
 * One column, unlike the expiry sections: the only option a reader reaches for is
 * `min-input-files`, and the row already says how many files the group has against it. With the
 * table panel's filter set, a second table plans `where => <filter>`: the files the scan under
 * it leaves, which is [evaluateScan] over the drawn graph — a live file not drawn is considered,
 * and said to be.
 */
@Composable
internal fun RewriteSection(node: GraphNode.SnapshotNode, graph: GraphModel, scanFilter: ScanFilter = ScanFilter.of(emptyList())) {
    val colors = MaterialTheme.colorScheme
    val latest = graph.newestMetadata()
    val options = RewriteOptions.forTable(latest?.properties.orEmpty(), latest?.defaultSpecId)
    val live = node.liveFiles
    val plan = live?.let { planRewrite(it, node.deleteReach.orEmpty(), options) }
    val rewritten = plan?.rewrittenGroups.orEmpty()
    val title = "Rewrite" + if (rewritten.isNotEmpty()) " — ${rewritten.sumOf { it.files.size }} files would go" else ""
    CountedSection(title, plan?.groups?.size ?: 0, "groups") {
        Text(
            "What rewrite_data_files would rewrite, the way SizeBasedDataRewriter plans it: a file is " +
                "a candidate when it is outside ${formatBytes(options.minFileSizeBytes)}–" +
                "${formatBytes(options.maxFileSizeBytes)} (75% and 180% of write.target-file-size-bytes, " +
                "${formatBytes(options.targetFileSizeBytes)}) or when file-scoped deletes mark " +
                "${(options.deleteRatioThreshold * 100).toInt()}% of its rows; candidates are packed per " +
                "partition, and a group is rewritten with at least ${options.minInputFiles} files " +
                "(min-input-files), more than the target in bytes, or a file past the delete ratio. " +
                "Every small file is a candidate; it takes ${options.minInputFiles} of them.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        when {
            plan == null -> Text("Not readable here — this snapshot's manifests are not retained.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            plan.groups.isEmpty() -> Text("No candidates: every live data file is within the size range and under the delete ratio.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            else -> WideTable(
                headers = listOf("Verdict", "Partition", "Files", "Bytes", "Output Files", "Highest Delete Ratio"),
                columnWidths = listOf(190.dp, 190.dp, 70.dp, 110.dp, 100.dp, 150.dp),
                rows = plan.groups.map { g ->
                    listOf(
                        if (g.rewritten) "REWRITTEN — " + g.reasons.joinToString("; ") { it.label }
                        else "left alone — ${g.files.size} of ${options.minInputFiles} files",
                        g.partition.ifEmpty { "(unpartitioned)" },
                        "${g.files.size}",
                        formatBytes(g.inputBytes),
                        if (g.rewritten) "${g.outputFiles}" else "—",
                        g.files.maxOfOrNull { it.deleteRatio }?.let { "${(it * 100).toInt()}%" } ?: "0%",
                    )
                },
                leadCellColors = plan.groups.map { if (it.rewritten) verdictSkippedColor() else null },
            )
        }
        if (plan != null && !scanFilter.isEmpty()) {
            // `where => …` goes through the scan: the files its pruning leaves are the only ones considered.
            val scan = remember(graph, scanFilter) { evaluateScan(graph, scanFilter) }
            val ruledOut = remember(scan) { scan.ruledOutFileKeys(graph) }
            val drawn = remember(graph) {
                graph.nodes.asSequence().filterIsInstance<GraphNode.FileNode>().mapNotNull { it.data.filePath?.let(::normalizeFilePath) }.toSet()
            }
            val where = planRewrite(live, node.deleteReach.orEmpty(), options, ruledOut)
            val considered = where.filesByPartition.values.sumOf { it.size }
            val undrawn = where.filesByPartition.values.flatten().count { normalizeFilePath(it.path) !in drawn }
            val whereRewritten = where.rewrittenGroups
            Text(
                "With where => \"${scanFilter.renderSparkSql()}\" (in spark-sql a quote inside the string is \\', since '' is two literals joined): " +
                    "the rewrite plans the files a scan under the filter opens " +
                    "(RewriteDataFilesSparkAction.planFileGroups: newScan().filter(where).ignoreResiduals(), so a " +
                    "file left is rewritten whole) — ${formatCounted(considered, "file")} considered, " +
                    "${formatCounted(where.filteredOut.size, "file")} ruled out by the partition summaries or the bounds" +
                    (if (undrawn > 0) ", $undrawn not drawn and so considered without a verdict" else "") + ". " +
                    (if (whereRewritten.isEmpty()) "Nothing is rewritten: ${if (where.groups.isEmpty()) "no candidate among them" else "no group of them reaches min-input-files, the target, or a delete threshold"}."
                    else "${formatCounted(whereRewritten.sumOf { it.files.size }, "file")} in ${formatCounted(whereRewritten.size, "group")} would go."),
                fontSize = TypeScale.small,
                color = if (whereRewritten.isNotEmpty() && whereRewritten.sumOf { it.files.size } != rewritten.sumOf { it.files.size }) verdictUnevaluatedColor() else colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            if (where.groups.isNotEmpty()) {
                WideTable(
                    headers = listOf("Verdict", "Partition", "Files", "Bytes", "Output Files", "Highest Delete Ratio"),
                    columnWidths = listOf(190.dp, 190.dp, 70.dp, 110.dp, 100.dp, 150.dp),
                    rows = where.groups.map { g ->
                        listOf(
                            if (g.rewritten) "REWRITTEN — " + g.reasons.joinToString("; ") { it.label }
                            else "left alone — ${g.files.size} of ${options.minInputFiles} files",
                            g.partition.ifEmpty { "(unpartitioned)" },
                            "${g.files.size}",
                            formatBytes(g.inputBytes),
                            if (g.rewritten) "${g.outputFiles}" else "—",
                            g.files.maxOfOrNull { it.deleteRatio }?.let { "${(it * 100).toInt()}%" } ?: "0%",
                        )
                    },
                    leadCellColors = where.groups.map { if (it.rewritten) verdictSkippedColor() else null },
                )
            }
        }
        // remove-dangling-deletes, after whichever rewrite is planned above.
        val deletes = live?.filter { it.content != DataFileContent.DATA }.orEmpty()
        if (plan != null && deletes.isNotEmpty()) {
            val planned = if (!scanFilter.isEmpty()) planRewrite(live, node.deleteReach.orEmpty(), options, evaluateScan(graph, scanFilter).ruledOutFileKeys(graph)) else plan
            val unpartitionedSingleSpec = latest?.let { it.partitionSpecs.size == 1 && it.partitionSpecs.single().fields.isEmpty() } == true
            val dangling = planDanglingDeletes(live, planned, node.data.sequenceNumber ?: 0L, unpartitionedSingleSpec)
            val byRule = dangling.files.count { it.removed }
            Text(
                "With remove-dangling-deletes: " + when {
                    dangling.skipped != null -> "nothing — ${dangling.skipped}." + (if (byRule > 0) " The rule itself would take ${formatCounted(byRule, "delete file")}." else "")
                    dangling.removed.isEmpty() -> "nothing — every delete file is at or above its partition's floor after the rewrite."
                    else -> "${formatCounted(dangling.removed.size, "delete file")} of ${deletes.size} removed in a second replace — RemoveDanglingDeletesSparkAction: a positional delete below its partition's lowest data sequence number after the rewrite, an equality delete at or below it; the rewritten groups' output lands at this snapshot's number, ${node.data.sequenceNumber}, which is what moves the floor."
                },
                fontSize = TypeScale.small,
                color = if (dangling.removed.isNotEmpty()) verdictUnevaluatedColor() else colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            WideTable(
                headers = listOf("Verdict", "Delete File", "Kind", "Seq", "Partition Floor", "Partition"),
                columnWidths = listOf(190.dp, 260.dp, 100.dp, 60.dp, 110.dp, 190.dp),
                rows = dangling.files.map { d ->
                    listOf(
                        when {
                            dangling.skipped != null && d.removed -> "kept — by the rule, gone"
                            d.removed -> "REMOVED — ${d.reason}"
                            else -> "kept — ${d.reason}"
                        },
                        fileNameFromPath(d.path),
                        if (d.content == DataFileContent.EQUALITY_DELETES) "equality" else "positional",
                        "${d.sequenceNumber}",
                        d.floor?.toString() ?: "no data file",
                        d.partition.ifEmpty { "(unpartitioned)" },
                    )
                },
                leadCellColors = dangling.files.map { if (dangling.skipped == null && it.removed) verdictSkippedColor() else null },
            )
        }
    }
}

/**
 * What `rewrite_manifests` would do to this snapshot's manifest list, the way
 * `RewriteManifestsSparkAction` plans it — see [planManifestRewrite]. Two rows, one per content
 * kind, the verdict first; the manifests under another spec listed as kept, since the action
 * matches the output spec alone. The figures are the three the commit's summary records, which
 * is how `maint`'s manifest rewrite holds them.
 */
@Composable
internal fun ManifestRewriteSection(node: GraphNode.SnapshotNode, graph: GraphModel) {
    val colors = MaterialTheme.colorScheme
    val latest = graph.newestMetadata()
    val options = ManifestRewriteOptions.forTable(latest?.properties.orEmpty(), latest?.defaultSpecId)
    val listed = node.manifestList
    val plan = planManifestRewrite(listed, options)
    val title = "Manifest Rewrite" + when {
        node.expired -> ""
        plan.rewrites -> " — ${plan.replaced} into ${plan.created}"
        plan.refused.isNotEmpty() -> " — refused"
        else -> " — nothing to do"
    }
    CountedSection(title, listed.size, "manifests") {
        Text(
            "What rewrite_manifests would do, the way RewriteManifestsSparkAction plans it: per content " +
                "kind, the manifests under the output spec (spec-id, else the table's current spec " +
                "${options.specId ?: "— none known here"}) are rewritten whole into their total length over " +
                "commit.manifest.target-size-bytes (${formatBytes(options.targetManifestSizeBytes)}), rounded " +
                "up — unless the kind is one manifest that fits one target, which is left alone. A manifest " +
                "under another spec is kept. The commit records the outcome as manifests-created, " +
                "manifests-kept and manifests-replaced.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (node.expired) {
            Text("Not readable here — this snapshot's manifest list is gone.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            return@CountedSection
        }
        Text(
            "Created ${plan.created}, kept ${plan.kept}, replaced ${plan.replaced}.",
            fontSize = TypeScale.small,
            fontWeight = FontWeight.SemiBold,
            color = if (plan.rewrites) verdictSkippedColor() else colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        val rows = plan.kinds.map { k ->
            listOf(
                when {
                    k.rewritten -> "REWRITTEN — ${k.matching.size} into ${k.targetNumManifests}"
                    k.leftAlone?.startsWith(model.ManifestRewritePlan.REFUSED_PREFIX) == true -> k.leftAlone.orEmpty()
                    else -> "left alone — ${k.leftAlone}"
                },
                "${k.label} manifests",
                "${k.matching.size}",
                formatBytes(k.inputBytes),
                if (k.rewritten) "${k.targetNumManifests}" else "—",
            )
        } + plan.unmatched.map { m ->
            listOf(
                "kept — spec ${m.partitionSpecId ?: "?"} is not the output spec",
                if ((m.content ?: ManifestContent.DATA) == ManifestContent.DELETES) "delete manifest" else "data manifest",
                "1",
                m.manifestLength?.let { formatBytes(it) } ?: "N/A",
                "—",
            )
        }
        WideTable(
            headers = listOf("Verdict", "Kind", "Manifests", "Bytes", "Written"),
            columnWidths = listOf(250.dp, 130.dp, 80.dp, 100.dp, 70.dp),
            rows = rows,
            leadCellColors = plan.kinds.map {
                when {
                    it.rewritten -> verdictSkippedColor()
                    it.leftAlone?.startsWith(model.ManifestRewritePlan.REFUSED_PREFIX) == true -> colors.error
                    else -> null
                }
            } + plan.unmatched.map { null },
        )
    }
}

/**
 * What `rewrite_position_delete_files` would rewrite, the way `SizeBasedPositionDeletesRewriter`
 * plans it — see [planPositionDeleteRewrite] — and, behind a click, what it would drop: the
 * positions naming no live data file of the group's partition, which are the dangling records a
 * compaction leaves ([PositionDeleteRewriteDrops]). The plan is read from the metadata; the drop
 * takes opening each rewritten delete file, so it waits for the button. `rewrite-all` is offered
 * as a second plan because a bare call on a small table takes nothing — every file is a
 * candidate and it takes five — and the reader's question after a compaction is what the call
 * that would run does. [startRequested] and [onSettled] follow `PositionalDeleteTargets`.
 */
@Composable
internal fun PositionDeleteRewriteSection(
    node: GraphNode.SnapshotNode,
    graph: GraphModel,
    startRequested: Boolean = false,
    onSettled: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val latest = graph.newestMetadata()
    val options = PositionDeleteRewriteOptions.forTable(latest?.properties.orEmpty(), latest?.formatVersion)
    val live = node.liveFiles
    val plan = live?.let { planPositionDeleteRewrite(it, options) }
    val all = live?.let { planPositionDeleteRewrite(it, options.copy(rewriteAll = true)) }
    val rewritten = plan?.rewrittenFiles.orEmpty()
    val title = "Position Delete Rewrite" + when {
        plan?.refused != null -> " — refused"
        rewritten.isNotEmpty() -> " — ${rewritten.size} delete files would go"
        else -> ""
    }
    CountedSection(title, plan?.deleteFileCount ?: 0, "delete files") {
        Text(
            "What rewrite_position_delete_files would rewrite, the way SizeBasedPositionDeletesRewriter " +
                "plans it: the live positional delete files grouped by partition, a file a candidate when " +
                "outside ${formatBytes(options.minFileSizeBytes)}–${formatBytes(options.maxFileSizeBytes)} " +
                "(75% and 180% of write.delete.target-file-size-bytes, ${formatBytes(options.targetFileSizeBytes)}), " +
                "and a group rewritten with at least ${options.minInputFiles} files (min-input-files), more than " +
                "the target in bytes, or a file past the maximum; rewrite-all takes every file. What it writes " +
                "back is each position whose file_path names a live data file of the partition — the rest are " +
                "dangling records and are dropped, which is why the procedure follows a compaction.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        when {
            plan == null || all == null -> Text("Not readable here — this snapshot's manifests are not retained.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            plan.refused != null -> Text(
                "${plan.refused}: the action refuses format version ${options.formatVersion} outright, " +
                    "so its ${formatCounted(plan.deleteFileCount, "positional delete file")} — deletion vectors among them — are never rewritten by it.",
                fontSize = TypeScale.small,
                color = colors.error,
            )
            plan.deleteFileCount == 0 -> Text("No live positional delete file: nothing to rewrite.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            else -> {
                val rows = listOf("bare call" to plan, "rewrite-all" to all).flatMap { (call, p) ->
                    p.groups.map { g ->
                        listOf(
                            if (g.rewritten) "REWRITTEN — " + g.reasons.joinToString("; ") { it.label }
                            else "left alone — ${g.files.size} of ${options.minInputFiles} files",
                            call,
                            g.partition.ifEmpty { "(unpartitioned)" },
                            "${g.files.size}",
                            formatBytes(g.inputBytes),
                            formatCount(g.recordCount),
                            if (g.rewritten) "${g.outputFiles}" else "—",
                        )
                    }.ifEmpty {
                        listOf(listOf("nothing to do — every file within the size range", call, "", "0", "0 B", "0", "—"))
                    }
                }
                val colorsOf = listOf(plan, all).flatMap { p -> p.groups.map { if (it.rewritten) verdictSkippedColor() else null }.ifEmpty { listOf(null) } }
                WideTable(
                    headers = listOf("Verdict", "Call", "Partition", "Files", "Bytes", "Positions", "Output Files"),
                    columnWidths = listOf(230.dp, 90.dp, 170.dp, 60.dp, 100.dp, 90.dp, 100.dp),
                    rows = rows,
                    leadCellColors = colorsOf,
                )
                PositionDeleteRewriteDrops(node, all, startRequested, onSettled)
            }
        }
    }
}

/** The read half of [PositionDeleteRewriteSection]: the `rewrite-all` plan's files opened, and what each would keep and drop. */
@Composable
private fun PositionDeleteRewriteDrops(
    node: GraphNode.SnapshotNode,
    plan: PositionDeleteRewritePlan,
    startRequested: Boolean,
    onSettled: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var requested by remember(node.id) { mutableStateOf(startRequested) }
    val outcome by produceState<Result<PositionDeleteRewriteDrops.Result>?>(null, node.id, requested) {
        value = null
        if (requested) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val live = node.liveFiles ?: throw IllegalStateException("the snapshot's live files could not be read")
                    val input = node.readInput.value ?: throw IllegalStateException("the snapshot's files could not be read")
                    PositionDeleteRewriteDrops.read(plan, live, input)
                }
            }
            onSettled()
        }
    }
    Spacer(Modifier.height(8.dp))
    when {
        !requested -> OutlinedButton(onClick = { requested = true }) {
            Text("Read the delete files — which positions rewrite-all keeps, and which it drops as dangling")
        }
        outcome == null -> Text("Reading ${formatCounted(plan.rewrittenFiles.size, "delete file")}…", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
        else -> outcome?.fold(
            onSuccess = { result ->
                Text(
                    buildString {
                        append("${formatCount(result.kept)} ${if (result.kept == 1L) "position" else "positions"} kept and ${formatCount(result.dropped)} dropped as dangling")
                        append(" across ${formatCounted(result.files.size, "delete file")}")
                        if (result.failed > 0) append("; ${formatCounted(result.failed, "file")} could not be read")
                        if (result.filesLeft > 0) append("; ${formatCounted(result.filesLeft, "file")} left unread by the cap of ${PositionDeleteRewriteDrops.MAX_FILES}")
                        append(". A rewrite's summary records these as added-position-deletes and removed-position-deletes less added.")
                    },
                    fontSize = TypeScale.small,
                    color = if (result.dropped > 0) verdictSkippedColor() else colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                val rows = result.files.flatMap { f ->
                    if (f.error != null) listOf(listOf("not read", fileNameFromPath(f.delete.path), "", "", f.error.orEmpty()))
                    else f.targets.map { t ->
                        listOf(if (t.kept) "kept" else "DROPPED", fileNameFromPath(f.delete.path), formatCount(t.positions), fileNameFromPath(t.dataFilePath), t.reason)
                    }
                }
                WideTable(
                    headers = listOf("Verdict", "Delete file", "Positions", "Names data file", "Why"),
                    columnWidths = listOf(90.dp, 300.dp, 80.dp, 300.dp, 360.dp),
                    rows = rows,
                    leadCellColors = result.files.flatMap { f ->
                        if (f.error != null) listOf(colors.error) else f.targets.map { if (it.kept) null else verdictSkippedColor() }
                    },
                )
            },
            onFailure = { failure ->
                Text("Could not read: ${failure.message ?: failure::class.simpleName}", fontSize = TypeScale.small, color = colors.error)
            },
        )
    }
}

/**
 * What the next commit would do to this snapshot's manifest list, the way `ManifestMergeManager`
 * decides it — see [planManifestMerge]. Two plans, because the two halves of the list merge
 * separately and a commit writes to one of them: an append writes a data manifest, a
 * merge-on-read delete a delete manifest. The new manifest is assumed at the newest listed one's
 * length, which only decides whether it fits the open bin. The options come off the latest
 * metadata, since a merge runs under the properties in force at the commit.
 *
 * One row per bin, the verdict first: a column of "kept" with one "MERGED" in it has to be
 * findable, and the row says how far the bin is from `min-count-to-merge`, which is the figure
 * a reader with forty manifests came for.
 */
@Composable
internal fun ManifestMergeSection(node: GraphNode.SnapshotNode, graph: GraphModel) {
    val colors = MaterialTheme.colorScheme
    val latest = graph.newestMetadata()
    val options = ManifestMergeOptions.forTable(latest?.properties.orEmpty())
    val listed = node.manifestList
    fun planFor(content: Int): ManifestMergePlan =
        planManifestMerge(listed, content, assumedManifestBytes(listed, content), latest?.defaultSpecId, options)
    val data = planFor(ManifestContent.DATA)
    val deletes = planFor(ManifestContent.DELETES)
    val title = "Manifest Merge — next append: ${data.describe}"
    CountedSection(title, listed.size, "manifests") {
        Text(
            "What the next commit would do to this manifest list, the way ManifestMergeManager does " +
                "it on every batch write: the manifests are grouped by partition spec and packed from " +
                "the oldest end into ${formatBytes(options.targetSizeBytes)} bins " +
                "(commit.manifest.target-size-bytes); a bin of one is kept, a bin holding the new manifest " +
                "is kept under ${options.minCountToMerge} (commit.manifest.min-count-to-merge), and any " +
                "other bin of two or more is merged into one manifest — whatever the count, so a spec " +
                "change merges the old spec's manifests at the next commit. Data and delete manifests " +
                "merge separately; the first plan is for an append, the second for a merge-on-read delete.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        when {
            node.expired -> Text("Not readable here — this snapshot's manifest list is gone.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            !options.enabled -> Text("Merging is off (commit.manifest-merge.enabled = false): every commit lists what it wrote beside everything kept.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
            else -> {
                val rows = listOf("append" to data, "merge-on-read delete" to deletes).flatMap { (commit, plan) ->
                    plan.bins.map { bin ->
                        listOf(
                            when (bin.verdict) {
                                ManifestMergeVerdict.MERGED -> "MERGED — ${bin.manifests.size} into 1" + if (bin.holdsFirst) "" else ", holds no new manifest"
                                ManifestMergeVerdict.UNDER_MIN_COUNT -> "kept — ${bin.manifests.size} of ${options.minCountToMerge}"
                                ManifestMergeVerdict.ALONE -> "kept — alone in its bin"
                            },
                            commit,
                            if (plan.content == ManifestContent.DATA) "data" else "deletes",
                            "${bin.specId}",
                            "${bin.manifests.size}" + if (bin.holdsFirst) " incl. new" else "",
                            formatBytes(bin.bytes),
                        ) to bin
                    }
                }
                if (rows.isEmpty()) Text("Nothing to merge: the list is empty.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
                else WideTable(
                    headers = listOf("Verdict", "Next Commit", "Content", "Spec", "Manifests", "Bytes"),
                    columnWidths = listOf(190.dp, 190.dp, 70.dp, 50.dp, 110.dp, 90.dp),
                    rows = rows.map { it.first },
                    leadCellColors = rows.map { if (it.second.merged) verdictSkippedColor() else null },
                )
            }
        }
    }
}

/**
 * The Paimon twin, decided the way `ManifestFileMerger.merge` decides it on every commit — see
 * [planPaimonManifestMerge]: the next commit rebuilds the base list from this snapshot's base and
 * delta manifests, full compaction first (the manifests that must change against
 * `manifest.full-compaction-threshold-size`), else the minor one (bins that close on
 * `manifest.target-file-size`, the leftover merged at `manifest.merge-min-count`). One row per bin,
 * the verdict first, and the leftover's row says how far it is from the count, which is the
 * figure a reader with twenty-nine manifests came for. Under the table, what `sys.compact_manifest`
 * would do instead ([planPaimonManifestCompaction]) — the call that reader runs next.
 */
@Composable
internal fun PaimonManifestMergeSection(node: GraphNode.PaimonSnapshotNode) {
    val colors = MaterialTheme.colorScheme
    if (!node.manifestMergeInput.isPresent) return
    val input = node.manifestMergeInput.value.orEmpty()
    val options = PaimonManifestMergeOptions.forTable(node.tableOptions)
    val plan = planPaimonManifestMerge(input, options)
    CountedSection("Manifest Merge — next commit: ${plan.describe}", input.size, "manifests") {
        Text(
            "What the next commit does to the base manifest list, the way ManifestFileMerger does it on " +
                "every commit over this snapshot's base and delta manifests in that order. A full " +
                "compaction when the manifests that must change — any with a DELETE entry, or under " +
                "${formatBytes(options.targetSizeBytes)} (manifest.target-file-size) — sum to " +
                "${formatBytes(options.fullCompactionThresholdBytes)} (manifest.full-compaction-threshold-size), " +
                "rewriting them with every DELETE and the ADD it cancels dropped; otherwise the manifests fill " +
                "bins that close at the target size, and the leftover bin is merged only at " +
                "${options.mergeMinCount} (manifest.merge-min-count) manifests. A merge folds an ADD and a DELETE " +
                "of one file away, so it can write fewer entries than it reads, or nothing.",
            fontSize = TypeScale.small,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (plan.bins.isEmpty()) {
            Text("Nothing to merge: this snapshot lists no data manifest.", fontSize = TypeScale.small, color = colors.onSurfaceVariant)
        } else {
            Text(
                "The manifests that must change sum to ${formatBytes(plan.mustChangeBytes)} against the " +
                    "${formatBytes(options.fullCompactionThresholdBytes)} that triggers a full compaction.",
                fontSize = TypeScale.small,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            WideTable(
                headers = listOf("Verdict", "Manifests", "Bytes", "Entries In", "Entries Out", "Why"),
                columnWidths = listOf(190.dp, 90.dp, 90.dp, 90.dp, 90.dp, 520.dp),
                rows = plan.bins.map { bin ->
                    listOf(
                        when {
                            bin.merged && bin.mergedEntries == 0 -> "MERGED — ${bin.manifests.size} into nothing"
                            bin.merged -> "MERGED — ${bin.manifests.size} into 1"
                            bin.manifests.size == 1 -> "kept — alone"
                            else -> "kept — ${bin.manifests.size} of ${options.mergeMinCount}"
                        },
                        "${bin.manifests.size}",
                        formatBytes(bin.bytes),
                        formatCount(bin.manifests.sumOf { it.entries.size }),
                        bin.mergedEntries?.let { formatCount(it.toLong()) } ?: "as they are",
                        bin.reason,
                    )
                },
                leadCellColors = plan.bins.map { if (it.merged) verdictSkippedColor() else null },
            )
            val compaction = planPaimonManifestCompaction(input, options)
            Text(
                "CALL sys.compact_manifest runs the same merge with merge-min-count and the full-compaction " +
                    "threshold both at 1, so every manifest under the target size or holding a DELETE is " +
                    "rewritten at once and a list that comes out the same commits nothing: " +
                    (if (compaction.writesNewList) "it would write a COMPACT snapshot with an empty delta list — " else "here ") +
                    "${compaction.describeCompaction}.",
                fontSize = TypeScale.small,
                color = if (compaction.writesNewList) verdictSkippedColor() else colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
