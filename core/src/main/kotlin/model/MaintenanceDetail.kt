package model

import service.PositionDeleteRewriteDrops
import java.time.Instant

/**
 * One table of a plan in full: the rows a procedure would act on or leave, in the columns the
 * desktop's section for it draws. A cell with nothing to say is `—`.
 */
data class PlanTable(val title: String, val headers: List<String>, val rows: List<List<String>>)

/**
 * A maintenance procedure's plan in full, for the narrow shells — what `icelens plan <table>
 * <procedure>` prints. [line] is the summary's own line for it, [notes] the facts the plan was
 * made under, and [tables] every row, uncapped: a terminal has no next page to click for.
 *
 * The desktop draws the same plans in sections of its own shape, as its inspector does beside
 * [GraphTree]. What the two could disagree on is the wording of a cell that says something — a
 * verdict, a reason, a kind — and that is the plan types' own (`verdictText`, the fates' labels),
 * which both call.
 */
data class MaintenanceDetail(val line: MaintenanceLine, val notes: List<String>, val tables: List<PlanTable>)

/**
 * The name a call takes for a [MaintenanceLine.procedure]: lower case, `sys.` and a parenthesised
 * option dropped, a space as `_`, and the two formats' next-commit manifest merges one name —
 * `expire_snapshots (older_than = now)` is `expire_snapshots`, `sys.compact` is `compact`,
 * `log cleanup` is `log_cleanup`. Applied to what a reader types too, so either spelling finds it.
 */
fun maintenanceKey(procedure: String): String {
    val bare = procedure.trim().substringBefore(" (").removePrefix("sys.").lowercase()
    return if (bare.endsWith("manifest merge")) "manifest_merge" else bare.replace(' ', '_')
}

val MaintenanceLine.key: String get() = maintenanceKey(procedure)

/** The line of [maintenanceSummary]'s answer that [procedure] names, by [maintenanceKey]; null where the table plans no such procedure. */
fun List<MaintenanceLine>.forProcedure(procedure: String): MaintenanceLine? =
    maintenanceKey(procedure).let { key -> firstOrNull { it.key == key } }

/**
 * [line] — one line of [maintenanceSummary] at [nowMs] — planned in full under the same calls
 * the summary made. The three inputs a summary takes from a caller are taken here for the same
 * reason, and must be the ones the summary was given: each is a read of the directory, and a
 * detail starts none of them. [readFiles] is the one read a detail may start, and only where it
 * is asked for: the rewritten delete files `rewrite_position_delete_files` would read, for which
 * positions it keeps — the desktop's click under that section. [zOrderBy] and [where] plan
 * `OPTIMIZE … WHERE … ZORDER BY …` in place of the bare call the line summarises — the command
 * line's `--zorder` and `--where`, the desktop's two fields.
 */
fun maintenanceDetail(
    node: GraphNode.TableNode,
    line: MaintenanceLine,
    nowMs: Long,
    orphanReport: UnreferencedFilesReport? = null,
    unexistingPlan: PaimonUnexistingFilesPlan? = null,
    vacuumPlan: DeltaVacuumPlan? = null,
    readFiles: Boolean = false,
    zOrderBy: List<String> = emptyList(),
    where: DeltaOptimizeWhere? = null,
): MaintenanceDetail {
    val key = line.key
    val out = DetailBuilder()
    val input = node.maintenance.value
    val paimonExpiry = node.summary.paimonExpiry
    when {
        key == "expire_snapshots" && input is IcebergMaintenanceInput -> out.icebergExpiry(node, input.metadata, nowMs)
        key == "expire_snapshots" && paimonExpiry != null -> out.paimonExpiry(node, paimonExpiry, nowMs)
        key == "expire_changelogs" && paimonExpiry != null -> out.changelogExpiry(paimonExpiry, nowMs)
        key == "expire_partitions" && paimonExpiry != null -> out.partitionExpiry(paimonExpiry, nowMs)
        key == "expire_tags" && paimonExpiry != null -> out.tagExpiry(node, paimonExpiry, nowMs)
        key == "purge_files" -> out.purge(node)
        key == "remove_unexisting_files" -> out.unexisting(node, unexistingPlan)
        key == "remove_orphan_files" -> out.orphans(orphanReport, nowMs)
        key == "rewrite_table_path" -> out.rewriteTablePath(node)
        key == "vacuum" && input is DeltaMaintenanceInput -> out.vacuum(input.model, vacuumPlan, nowMs)
        key == "log_cleanup" && input is DeltaMaintenanceInput -> out.logCleanup(input.model, nowMs)
        key == "optimize" && input is DeltaMaintenanceInput -> out.optimize(input.model, zOrderBy, where)
        key == "rewrite_data_files" && input is IcebergMaintenanceInput -> out.rewriteDataFiles(input)
        key == "rewrite_position_delete_files" && input is IcebergMaintenanceInput -> out.positionDeleteRewrite(input, readFiles)
        key == "manifest_merge" && input is IcebergMaintenanceInput -> out.icebergManifestMerge(input)
        key == "rewrite_manifests" && input is IcebergMaintenanceInput -> out.manifestRewrite(input)
        key == "fast_forward" && input is IcebergMaintenanceInput -> out.icebergFastForward(input.metadata)
        key == "manifest_merge" && input is PaimonMaintenanceInput -> out.paimonManifestMerge(input, compactManifest = false)
        key == "compact_manifest" && input is PaimonMaintenanceInput -> out.paimonManifestMerge(input, compactManifest = true)
        key == "compaction" && input is PaimonMaintenanceInput -> out.compaction(input)
        key == "compact" && input is PaimonMaintenanceInput -> out.fullCompaction(input)
        key == "fast_forward" -> out.paimonFastForward(node)
        // Every line the summary writes has a case above, and MaintenanceDetailTest holds every
        // fixture's lines to that; a line added without one says so rather than printing nothing.
        else -> out.notes += "the plan's tables are not printed for this procedure; the line above is its summary"
    }
    return MaintenanceDetail(line, out.notes, out.tables)
}

private class DetailBuilder {
    val notes = mutableListOf<String>()
    val tables = mutableListOf<PlanTable>()

    fun table(title: String, headers: List<String>, rows: List<List<String>>) {
        if (rows.isNotEmpty()) tables += PlanTable(title, headers, rows)
    }
}

private fun instant(ms: Long?): String = ms?.let { Instant.ofEpochMilli(it).toString() } ?: "—"

private fun bytes(value: Long?): String = value?.let(::formatBytes) ?: "—"

private fun DetailBuilder.icebergExpiry(node: GraphNode.TableNode, meta: TableMetadata, nowMs: Long) {
    val byDefaults = meta.planExpiry(ExpiryOptions(nowMs = nowMs))
    val byAge = meta.planExpiry(ExpiryOptions(nowMs = nowMs, olderThanMs = nowMs))
    notes += "under the defaults: a cutoff of ${instant(byDefaults.defaultCutoffMs)}, min-snapshots-to-keep ${byDefaults.defaultMinSnapshotsToKeep}; " +
        "with older_than = now: a cutoff of ${instant(nowMs)}; a branch's own max-snapshot-age-ms replaces either for what it reaches"
    byDefaults.refs.filter { !it.retained }.takeIf { it.isNotEmpty() }?.let { refs ->
        notes += "refs past their max-ref-age: " + refs.joinToString(", ") { "${it.name} (${it.reason})" }
    }
    val defaults = byDefaults.snapshots.associateBy { it.snapshotId }
    val byAgeById = byAge.snapshots.associateBy { it.snapshotId }
    val ordered = meta.snapshots.sortedBy { it.sequenceNumber ?: Long.MAX_VALUE }.mapNotNull { it.snapshotId }
    table(
        "Snapshots",
        listOf("Under the defaults", "Snapshot ID", "With older_than = now"),
        ordered.mapNotNull { id ->
            val d = defaults[id] ?: return@mapNotNull null
            val a = byAgeById[id] ?: return@mapNotNull null
            listOf(d.verdictText(), id.toString(), a.verdictText())
        },
    )
    val input = node.expiryFiles.value
    val removed = byAge.removed.toSet()
    val freed = input?.planExpiryFiles(removed)
    when {
        input == null -> notes += "what the expiry frees is not readable here: the manifests could not be read"
        freed == null || removed.isEmpty() -> Unit
        else -> {
            notes += "files freed by older_than = now: ${freed.describe}, ${formatBytes(freed.knownBytes)} the metadata accounts for " +
                "(a manifest list records no size); cleanup: ${freed.cleanup.label}"
            val kindOrder = listOf(ExpiryFileKind.DATA_FILE, ExpiryFileKind.DELETE_FILE, ExpiryFileKind.STATISTICS, ExpiryFileKind.MANIFEST, ExpiryFileKind.MANIFEST_LIST)
            table(
                "Files freed (older_than = now)",
                listOf("Kind", "Reason", "Snapshot", "Size", "File"),
                freed.files.sortedBy { kindOrder.indexOf(it.kind) }.map { f ->
                    listOf(f.kind.label, f.reason.label, f.snapshotId?.toString() ?: "—", bytes(f.sizeBytes), f.path)
                },
            )
            val cleanup = input.planMetadataCleanup(removed)
            if (cleanup.removesAnything) {
                notes += "clean_expired_metadata ${cleanup.describe()}"
                table(
                    "Metadata cleanup (clean_expired_metadata)",
                    listOf("Verdict", "Kind", "ID", "Kept By"),
                    cleanup.specs.map { "spec" to it }.plus(cleanup.schemas.map { "schema" to it }).map { (kind, item) ->
                        listOf(if (item.removed) "REMOVED" else "kept", kind, item.id.toString(), item.keptBy ?: "nothing retained reaches it")
                    },
                )
            }
        }
    }
}

private fun DetailBuilder.paimonExpiry(node: GraphNode.TableNode, input: PaimonExpiryInput, nowMs: Long) {
    val bare = runCatching { input.planExpiry(PaimonExpiryOptions(nowMs = nowMs)) }.getOrNull()
    val byAge = runCatching { input.planExpiry(PaimonExpiryOptions(nowMs = nowMs, retainMin = 1, olderThanMs = nowMs)) }.getOrNull()
    if (bare == null || byAge == null) {
        notes += "the table's snapshot.* options are ones the procedure refuses"
        return
    }
    notes += "under the table's options: a cutoff of ${instant(bare.cutoffMs)}; with retain_min = 1, older_than = now: a cutoff of ${instant(nowMs)}; " +
        "a removed snapshot a tag names lives on as the tag"
    val byAgeById = byAge.snapshots.associateBy { it.snapshotId }
    table(
        "Snapshots",
        listOf("Under the table's options", "Snapshot ID", "With retain_min = 1, older_than = now"),
        bare.snapshots.mapNotNull { v -> byAgeById[v.snapshotId]?.let { a -> listOf(v.snapshotVerdictText(), v.snapshotId.toString(), a.snapshotVerdictText()) } },
    )
    val freed = node.paimonExpiryFiles.value?.planExpiryFiles(byAge.removed.map { it.snapshotId }.toSet())
    when {
        freed == null -> notes += "what the expiry frees is not readable here: the manifests could not be read"
        freed.files.isEmpty() -> Unit
        else -> {
            notes += "files freed by retain_min = 1, older_than = now: snapshots ${freed.beginInclusive} to ${freed.endExclusive?.minus(1)} go, " +
                "${freed.describe}, ${formatBytes(freed.knownBytes)} the metadata accounts for (a list and a snapshot file record no size)" +
                if (freed.decoupled) "; the changelog lifecycle is decoupled, so the changelog lists and files stay for expire_changelogs" else ""
            table(
                "Files freed (retain_min = 1, older_than = now)",
                listOf("Kind", "Reason", "Snapshot", "Size", "File"),
                freed.files.map { f -> listOf(f.kind.label, f.reason.label, f.snapshotId?.toString() ?: "—", bytes(f.sizeBytes), f.path ?: f.name) },
            )
            table(
                "Removed, and kept on disk by a tag",
                listOf("File", "Tag", "Tag Snapshot", "Removed By"),
                freed.protectedByTag.map { listOf(it.name, it.tag, it.tagSnapshotId.toString(), "snapshot ${it.removedBy}") },
            )
        }
    }
}

private fun DetailBuilder.changelogExpiry(input: PaimonExpiryInput, nowMs: Long) {
    val bare = runCatching { input.planChangelogExpiry(PaimonExpiryOptions(nowMs = nowMs)) }.getOrNull()
    val byAge = runCatching { input.planChangelogExpiry(PaimonExpiryOptions(nowMs = nowMs, retainMin = 1, olderThanMs = nowMs)) }.getOrNull()
    if (bare == null || byAge == null) {
        notes += "the table's changelog.* options are ones the action refuses"
        return
    }
    notes += "under the table's options: a cutoff of ${instant(bare.cutoffMs)}" + (bare.floor?.let { "; everything below changelog $it goes whatever its age" } ?: "") +
        "; it runs at commit time, and Flink's expire_changelogs action is the call"
    val byAgeById = byAge.changelogs.associateBy { it.snapshotId }
    table(
        "Changelogs",
        listOf("Under the table's options", "Changelog ID", "With retain_min = 1, older_than = now"),
        bare.changelogs.mapNotNull { v -> byAgeById[v.snapshotId]?.let { a -> listOf(v.changelogVerdictText(), v.snapshotId.toString(), a.changelogVerdictText()) } },
    )
}

private fun DetailBuilder.partitionExpiry(input: PaimonExpiryInput, nowMs: Long) {
    val plan = input.planPartitionExpiry(nowMs)
    notes += when (val expiration = plan.expirationMs) {
        null -> "no partition.expiration-time is set: a write expires nothing and a bare call is refused"
        else -> "partition.expiration-time ${formatPaimonDurationMs(expiration)}: a cutoff of ${instant(plan.cutoffMs)} under ${plan.strategySpelled}" +
            (plan.pattern?.let { ", partition.timestamp-pattern '$it'" } ?: "") +
            (plan.formatter?.let { ", partition.timestamp-formatter '$it'" } ?: "") +
            "; at most ${plan.maxNum} go in one run, as one OVERWRITE commit"
    }
    if (plan.heldBack > 0) notes += "${formatCounted(plan.heldBack, "more partition")} past the cutoff wait for a later run, past partition.expiration-max-num"
    table(
        "Partitions",
        listOf("Verdict", "Partition", "Why", "Files", "Rows", "Bytes", "Newest File"),
        plan.partitions.sortedWith(compareBy({ !it.expired }, { it.entry.partition.path })).map { v ->
            listOf(
                plan.verdictText(v),
                v.entry.partition.display,
                v.reason,
                formatCount(v.entry.fileCount),
                formatCount(v.entry.recordCount),
                formatBytes(v.entry.fileSizeBytes),
                instant(v.entry.lastFileCreationTimeMs),
            )
        },
    )
}

private fun DetailBuilder.tagExpiry(node: GraphNode.TableNode, input: PaimonExpiryInput, nowMs: Long) {
    val bare = input.planTagExpiry(nowMs)
    val byAge = input.planTagExpiry(nowMs, olderThanMs = nowMs).tags.associateBy { it.tag.name }
    notes += "a tag's create time is a local time with no zone recorded, compared in this machine's zone as the procedure run here would"
    val deletions = node.paimonExpiryFiles.value
    table(
        "Tags",
        listOf("Under a bare call", "Tag", "Snapshot", "Created", "Retained", "Expires", "With older_than = now", "Removal Frees"),
        bare.tags.map { v ->
            val deletion = deletions?.planTagDeletion(v.tag.name)
            listOf(
                v.verdictText(),
                v.tag.name,
                v.tag.snapshotId?.toString() ?: "—",
                v.createdMs?.let(::instant) ?: "not recorded",
                v.tag.timeRetainedMs?.let(::formatPaimonDurationMs) ?: "none",
                instant(v.expiresAtMs),
                byAge[v.tag.name]?.verdictText() ?: "—",
                deletion?.let { "${it.describe} — ${it.note}" } ?: "not readable",
            )
        },
    )
}

private fun DetailBuilder.purge(node: GraphNode.TableNode) {
    val plan = node.paimonExpiryFiles.value?.planPurge()
    if (plan == null) {
        notes += "not readable: the manifests could not be read"
        return
    }
    notes += "writes " + (plan.truncateSnapshotId?.let { "snapshot $it, an OVERWRITE with deltaRecordCount ${plan.deltaRecordCount} and totalRecordCount 0" } ?: "nothing: the table has no snapshot to truncate") +
        ", beside ${formatCounted(plan.newFiles, "new file")} of its own"
    listOfNotNull(
        plan.snapshotIds.takeIf { it.isNotEmpty() }?.let { "snapshots expired: ${it.joinToString(", ")}" },
        plan.branches.takeIf { it.isNotEmpty() }?.let { "branches dropped: ${it.joinToString(", ")}" },
        plan.tags.takeIf { it.isNotEmpty() }?.let { "tags deleted: ${it.joinToString(", ")}" },
        plan.consumers.takeIf { it.isNotEmpty() }?.let { "consumers deleted: ${it.joinToString(", ")}" },
    ).forEach { notes += it }
    table(
        "Taken",
        listOf("Kind", "File", "Bytes", "Named By", "Taken By"),
        plan.removed.map { f -> listOf(f.kind.label, f.path ?: f.name, bytes(f.sizeBytes), f.snapshotId?.let { "snapshot $it" } ?: "—", f.reason.label) },
    )
    table("Kept", listOf("Kind", "File", "Bytes"), plan.kept.map { f -> listOf(f.kind.label, f.path ?: f.name, bytes(f.sizeBytes)) })
}

private fun DetailBuilder.unexisting(node: GraphNode.TableNode, plan: PaimonUnexistingFilesPlan?) {
    if (plan == null) {
        notes += "not checked: the files the retained snapshots need were not stat-ed"
        return
    }
    notes += "planned over snapshot ${plan.snapshotId}'s batch scan" +
        if (plan.commits) "; the commit is an APPEND with deltaRecordCount ${plan.deltaRecordCount}" else ""
    val report = if (node.missingFiles.isRead) node.missingFiles.value else null
    table(
        "Missing files",
        listOf("Verdict", "Kind", "File", "Needed By", "Why"),
        plan.rows.map { row -> listOf(row.verdict.label, row.file.kind.label, report?.relativePathOf(row.file) ?: row.file.path.toString(), row.file.neededBy.joinToString(", "), row.reason) },
    )
}

private fun DetailBuilder.orphans(report: UnreferencedFilesReport?, nowMs: Long) {
    if (report == null) {
        notes += "not walked: the table directory was not listed"
        return
    }
    val plan = planOrphanRemoval(report, nowMs)
    notes += "a bare ${plan.procedure} now: older_than = ${plan.defaultIntervalText} ago, the default — a cutoff of ${instant(plan.cutoffMs)}"
    if (report.unreachedFromCurrent.isNotEmpty()) {
        notes += "${formatCounted(report.unreachedFromCurrent.size, "file")} among them the metadata names, but only an older metadata version or a DELETED entry, which the procedure reads neither of"
    }
    report.problems.forEach { notes += "could not read: $it" }
    table(
        "Files named by nothing",
        listOf("Verdict", "File", "Size", "Modified", "Why"),
        plan.rows.map { row -> listOf(row.fate.label, row.relativePath, formatBytes(row.file.sizeBytes), instant(row.file.modifiedMs), row.reason) },
    )
}

private fun DetailBuilder.rewriteTablePath(node: GraphNode.TableNode) {
    val recorded = node.summary.location
    val plan = recorded?.let { node.rewriteTablePath.value?.planRewriteTablePath(RewriteTablePathOptions(it, node.summary.tablePath)) }
    when {
        plan == null -> notes += "not readable: the table's versions could not be read"
        plan.refusal != null -> notes += "refused: ${plan.refusal}"
        else -> {
            notes += "source_prefix $recorded, target_prefix ${node.summary.tablePath}; staged under ${plan.stagingDir}"
            if (plan.notStaged.isNotEmpty()) {
                notes += "${formatCounted(plan.notStaged.size, "statistics file")} listed from staging and never written there — a copy fails on that line"
            }
            table(
                "File list",
                listOf("Kind", "Copy From", "To"),
                plan.copies.map { listOf(it.kind.label + if (it.staged) " (rewritten)" else "", it.from, it.to) },
            )
        }
    }
}

private fun DetailBuilder.vacuum(model: DeltaUnifiedTableModel, plan: DeltaVacuumPlan?, nowMs: Long) {
    if (plan == null) {
        notes += "not listed: the table directory was not walked"
        return
    }
    notes += "a bare VACUUM at version ${plan.version}: files kept by nothing and modified before ${instant(plan.deleteBeforeMs)} go — " +
        "delta.deletedFileRetentionDuration ${formatRetention(plan.tableRetentionMs)}"
    plan.problems.forEach { notes += "could not read: $it" }
    model.planVacuum(nowMs, retainMs = 0L).getOrNull()?.let { zero ->
        notes += "VACUUM RETAIN 0 HOURS would delete ${formatCounted(zero.toDelete.size, "file")} (${formatBytes(zero.sizeOfDataToDelete)}) — " +
            (zero.refusal?.let { "and is $it" } ?: "and runs")
    }
    table(
        "Listing",
        listOf("Fate", "Path", "Why", "Size", "Modified"),
        plan.rows.sortedBy { it.fate.ordinal }.map { r ->
            listOf(
                r.fate.label,
                r.relativePath + if (r.isDirectory) "/" else "",
                r.reason + if (r.companions.isNotEmpty()) "; its .crc goes with it" else "",
                if (r.isDirectory) "" else formatBytes(r.sizeBytes),
                instant(r.modifiedMs),
            )
        },
    )
}

private fun DetailBuilder.logCleanup(model: DeltaUnifiedTableModel, nowMs: Long) {
    val next = model.nextCheckpointVersion()
    val plan = next?.let { model.planLogCleanup(nowMs, it) }?.getOrElse {
        notes += "not planned: ${it.message}"
        return
    }
    if (plan == null) {
        notes += "the log holds no version"
        return
    }
    notes += "the checkpoint at version $next, the next delta.checkpointInterval writes: files below it modified at or before ${instant(plan.cutoffMs)} go — " +
        "now less delta.logRetentionDuration (${formatRetention(plan.retentionMs)}), to the UTC midnight before; version ${plan.earliestReadableAfter} the earliest readable after"
    if (!plan.enabled) notes += "delta.enableExpiredLogCleanup is false: nothing is deleted"
    if (plan.writesCompatCheckpoint) notes += "a V2-checkpoint table: the cleanup first writes a classic checkpoint for readers that cannot read V2"
    table(
        "Log files",
        listOf("Fate", "File", "Why", "Modified"),
        plan.rows.sortedBy { it.fate != LogCleanupFate.DELETED }.map { r -> listOf(r.fate.label, r.path.fileName.toString(), r.reason, instant(r.modifiedMs)) },
    )
    table("Sidecars deleted", listOf("File"), plan.sidecarsDeleted.map { listOf(it.fileName.toString()) })
}

private fun partitionCell(partition: String): String = partition.ifEmpty { "(unpartitioned)" }

/** The current snapshot of an Iceberg table whose manifests are still there, or a note saying why not. */
private fun DetailBuilder.readableCurrent(input: IcebergMaintenanceInput): GraphNode.SnapshotNode? =
    input.current?.takeIf { !it.expired } ?: run {
        notes += "not readable: the current snapshot's manifests are not retained"
        null
    }

private fun DetailBuilder.rewriteDataFiles(input: IcebergMaintenanceInput) {
    val meta = input.metadata
    val current = readableCurrent(input) ?: return
    val live = current.liveFiles ?: return run { notes += "not readable: the current snapshot's manifests are not retained" }
    val options = RewriteOptions.forTable(meta.properties, meta.defaultSpecId)
    val plan = planRewrite(live, current.deleteReach.orEmpty(), options)
    notes += "planned over snapshot ${current.simpleId}'s live files: a file is a candidate outside " +
        "${formatBytes(options.minFileSizeBytes)}–${formatBytes(options.maxFileSizeBytes)} (75% and 180% of write.target-file-size-bytes, " +
        "${formatBytes(options.targetFileSizeBytes)}) or when file-scoped deletes mark ${(options.deleteRatioThreshold * 100).toInt()}% of its rows; " +
        "a group is rewritten with min-input-files (${options.minInputFiles}), more than the target in bytes, or a file past the delete ratio"
    table(
        "Groups",
        listOf("Verdict", "Partition", "Files", "Bytes", "Output Files", "Highest Delete Ratio"),
        plan.groups.map { g ->
            listOf(
                g.verdictText(options.minInputFiles), partitionCell(g.partition), "${g.files.size}", formatBytes(g.inputBytes),
                if (g.rewritten) "${g.outputFiles}" else "—", "${((g.files.maxOfOrNull { it.deleteRatio } ?: 0.0) * 100).toInt()}%",
            )
        },
    )
    table(
        "Candidates",
        listOf("Group", "File", "Bytes", "Rows", "Delete Files", "Deleted Rows", "Why"),
        plan.groups.flatMap { g ->
            g.files.map { c ->
                listOf(
                    if (g.rewritten) "rewritten" else "left alone", c.path, formatBytes(c.sizeBytes), formatCount(c.recordCount),
                    "${c.deleteFileCount}", formatCount(c.knownDeletedRecords), c.reasons.joinToString("; ") { it.label },
                )
            }
        },
    )
    val deletes = live.count { it.content != DataFileContent.DATA }
    if (deletes == 0) return
    val unpartitionedSingleSpec = meta.partitionSpecs.size == 1 && meta.partitionSpecs.single().fields.isEmpty()
    val dangling = planDanglingDeletes(live, plan, current.data.sequenceNumber ?: 0L, unpartitionedSingleSpec)
    notes += "with remove-dangling-deletes: " + when {
        dangling.skipped != null -> "nothing — ${dangling.skipped}"
        dangling.removed.isEmpty() -> "nothing — every delete file is at or above its partition's floor after the rewrite"
        else -> "${formatCounted(dangling.removed.size, "delete file")} of $deletes removed in a second replace"
    }
    table(
        "Delete files after the rewrite (remove-dangling-deletes)",
        listOf("Verdict", "Delete File", "Kind", "Seq", "Partition Floor", "Partition"),
        dangling.files.map { d ->
            listOf(dangling.verdictText(d), d.path, d.kindLabel, "${d.sequenceNumber}", d.floor?.toString() ?: "no data file", partitionCell(d.partition))
        },
    )
}

private fun DetailBuilder.positionDeleteRewrite(input: IcebergMaintenanceInput, readFiles: Boolean) {
    val meta = input.metadata
    val current = readableCurrent(input) ?: return
    val live = current.liveFiles ?: return run { notes += "not readable: the current snapshot's manifests are not retained" }
    val options = PositionDeleteRewriteOptions.forTable(meta.properties, meta.formatVersion)
    val plan = planPositionDeleteRewrite(live, options)
    val all = planPositionDeleteRewrite(live, options.copy(rewriteAll = true))
    if (plan.refused != null) {
        notes += "${plan.refused}: the action refuses format version ${options.formatVersion} outright, so its " +
            "${formatCounted(plan.deleteFileCount, "positional delete file")} are never rewritten by it"
        return
    }
    if (plan.deleteFileCount == 0) return
    notes += "a live positional delete file is a candidate outside ${formatBytes(options.minFileSizeBytes)}–${formatBytes(options.maxFileSizeBytes)} " +
        "(75% and 180% of write.delete.target-file-size-bytes, ${formatBytes(options.targetFileSizeBytes)}); a group is rewritten with " +
        "min-input-files (${options.minInputFiles}), more than the target in bytes, or a file past the maximum; rewrite-all takes every file, " +
        "and writes back only the positions whose file_path names a live data file of the partition"
    val calls = listOf("bare call" to plan, "rewrite-all" to all)
    table(
        "Groups",
        listOf("Verdict", "Call", "Partition", "Files", "Bytes", "Positions", "Output Files"),
        calls.flatMap { (call, p) ->
            p.groups.map { g ->
                listOf(
                    g.verdictText(options.minInputFiles), call, partitionCell(g.partition), "${g.files.size}", formatBytes(g.inputBytes),
                    formatCount(g.recordCount), if (g.rewritten) "${g.outputFiles}" else "—",
                )
            }
        },
    )
    val bare = plan.rewrittenFiles.map { it.path }.toSet()
    val everyCall = all.rewrittenFiles.map { it.path }.toSet()
    table(
        "Delete files",
        listOf("Bare Call", "Rewrite-All", "File", "Partition", "Bytes", "Positions"),
        all.filesByPartition.flatMap { (partition, files) ->
            files.map { f ->
                listOf(
                    if (f.path in bare) "rewritten" else "kept", if (f.path in everyCall) "rewritten" else "kept",
                    f.path, partitionCell(partition), formatBytes(f.sizeBytes), formatCount(f.recordCount),
                )
            }
        },
    )
    if (!readFiles || all.rewrittenFiles.isEmpty()) {
        if (all.rewrittenFiles.isNotEmpty()) notes += "not read: which positions rewrite-all keeps and which it drops as dangling takes a read of its delete files"
        return
    }
    val readInput = current.readInput.value ?: return run { notes += "not read: the snapshot's files could not be read" }
    val result = runCatching { PositionDeleteRewriteDrops.read(all, live, readInput) }.getOrElse {
        notes += "could not read the delete files: ${it.message ?: it::class.simpleName}"
        return
    }
    notes += "rewrite-all keeps ${formatCount(result.kept)} ${if (result.kept == 1L) "position" else "positions"} and drops ${formatCount(result.dropped)} as dangling" +
        " across ${formatCounted(result.files.size, "delete file")}" +
        (if (result.failed > 0) "; ${formatCounted(result.failed, "file")} could not be read" else "") +
        (if (result.filesLeft > 0) "; ${formatCounted(result.filesLeft, "file")} left unread by the cap of ${PositionDeleteRewriteDrops.MAX_FILES}" else "")
    table(
        "What rewrite-all writes back",
        listOf("Verdict", "Delete File", "Positions", "Names Data File", "Why"),
        result.files.flatMap { f ->
            val error = f.error
            if (error != null) listOf(listOf("not read", f.delete.path, "—", "—", error))
            else f.targets.map { t -> listOf(if (t.kept) "kept" else "DROPPED", f.delete.path, formatCount(t.positions), t.dataFilePath, t.reason) }
        },
    )
}

private fun DetailBuilder.icebergManifestMerge(input: IcebergMaintenanceInput) {
    val meta = input.metadata
    val current = readableCurrent(input) ?: return
    val options = ManifestMergeOptions.forTable(meta.properties)
    if (!options.enabled) {
        notes += "merging is off (commit.manifest-merge.enabled = false): every commit lists what it wrote beside everything kept"
        return
    }
    val listed = current.manifestList
    notes += "the next commit groups snapshot ${current.simpleId}'s manifests by partition spec and packs them from the oldest end into " +
        "${formatBytes(options.targetSizeBytes)} bins (commit.manifest.target-size-bytes): a bin of one is kept, a bin holding the new manifest is " +
        "kept under ${options.minCountToMerge} (commit.manifest.min-count-to-merge), and any other bin of two or more is merged whatever the count; " +
        "data and delete manifests merge apart, the first plan for an append and the second for a merge-on-read delete"
    val plans = listOf(ManifestContent.DATA, ManifestContent.DELETES).map { content ->
        (if (content == ManifestContent.DATA) "append" else "merge-on-read delete") to
            planManifestMerge(listed, content, assumedManifestBytes(listed, content), meta.defaultSpecId, options)
    }
    table(
        "Bins",
        listOf("Verdict", "Next Commit", "Content", "Spec", "Manifests", "Bytes"),
        plans.flatMap { (commit, plan) ->
            plan.bins.map { bin ->
                listOf(
                    bin.verdictText(options.minCountToMerge), commit, if (plan.content == ManifestContent.DATA) "data" else "deletes",
                    "${bin.specId}", "${bin.manifests.size}" + if (bin.holdsFirst) " incl. new" else "", formatBytes(bin.bytes),
                )
            }
        },
    )
    table(
        "Manifests",
        listOf("Next Commit", "Bin", "Merged", "Manifest", "Spec", "Bytes"),
        plans.flatMap { (commit, plan) ->
            plan.bins.flatMapIndexed { i, bin ->
                bin.manifests.map { m ->
                    listOf(
                        commit, "${i + 1}", if (bin.merged) "yes" else "no", m.entry?.manifestPath ?: "(the manifest the commit writes)",
                        "${m.specId}", formatBytes(m.lengthBytes),
                    )
                }
            }
        },
    )
}

private fun DetailBuilder.manifestRewrite(input: IcebergMaintenanceInput) {
    val meta = input.metadata
    val current = readableCurrent(input) ?: return
    val options = ManifestRewriteOptions.forTable(meta.properties, meta.defaultSpecId)
    val plan = planManifestRewrite(current.manifestList, options)
    notes += "per content kind, the manifests under the output spec (${options.specId ?: "none known"}) are rewritten whole into their total length " +
        "over commit.manifest.target-size-bytes (${formatBytes(options.targetManifestSizeBytes)}), rounded up — unless the kind is one manifest that " +
        "fits one target; a manifest under another spec is kept"
    notes += "the commit records manifests-created ${plan.created}, manifests-kept ${plan.kept}, manifests-replaced ${plan.replaced}"
    table(
        "Kinds",
        listOf("Verdict", "Kind", "Manifests", "Bytes", "Written"),
        plan.kinds.map { k -> listOf(k.verdictText, "${k.label} manifests", "${k.matching.size}", formatBytes(k.inputBytes), if (k.rewritten) "${k.targetNumManifests}" else "—") } +
            plan.unmatched.map { m -> listOf(m.unmatchedVerdictText, m.kindText, "1", bytes(m.manifestLength), "—") },
    )
    table(
        "Manifests",
        listOf("Verdict", "Kind", "Manifest", "Spec", "Bytes"),
        plan.kinds.flatMap { k ->
            k.matching.map { m -> listOf(if (k.rewritten) "replaced" else "kept", m.kindText, m.manifestPath ?: "—", m.partitionSpecId?.toString() ?: "?", bytes(m.manifestLength)) }
        } + plan.unmatched.map { m -> listOf("kept", m.kindText, m.manifestPath ?: "—", m.partitionSpecId?.toString() ?: "?", bytes(m.manifestLength)) },
    )
}

private fun DetailBuilder.icebergFastForward(meta: TableMetadata) {
    notes += "fast_forward(branch, to) moves the branch to the ref's snapshot when its own tip is an ancestor of it, and is refused otherwise; " +
        "a name with no ref is created there; nothing is deleted and no snapshot is written"
    table(
        "Pairs",
        listOf("Verdict", "Branch", "To", "Gains", "Why"),
        meta.fastForwardPlans().map { p -> listOf(p.verdict.label, p.branch, p.to, p.gainsText, p.reason) },
    )
}

private fun DetailBuilder.paimonManifestMerge(input: PaimonMaintenanceInput, compactManifest: Boolean) {
    val current = input.current ?: return
    val manifests = current.manifestMergeInput.value.orEmpty()
    val tableOptions = PaimonManifestMergeOptions.forTable(current.tableOptions)
    val plan = if (compactManifest) planPaimonManifestCompaction(manifests, tableOptions) else planPaimonManifestMerge(manifests, tableOptions)
    notes += if (compactManifest) {
        "sys.compact_manifest runs the next commit's merge with manifest.merge-min-count and the full-compaction threshold both at 1: every manifest " +
            "under the target size or holding a DELETE is rewritten at once, as a COMPACT snapshot with an empty delta list, and a list that comes " +
            "out the same commits nothing — ${plan.describeCompaction}"
    } else {
        "the next commit's merge over snapshot ${current.simpleId}'s base and delta manifests, in that order: a full compaction when the manifests " +
            "that must change — a DELETE entry, or under ${formatBytes(tableOptions.targetSizeBytes)} (manifest.target-file-size) — sum to " +
            "${formatBytes(tableOptions.fullCompactionThresholdBytes)} (manifest.full-compaction-threshold-size), here ${formatBytes(plan.mustChangeBytes)}; " +
            "otherwise bins close at the target size and the leftover bin merges at ${tableOptions.mergeMinCount} (manifest.merge-min-count) manifests — ${plan.describe}"
    }
    table(
        "Bins",
        listOf("Verdict", "Manifests", "Bytes", "Entries In", "Entries Out", "Why"),
        plan.bins.map { bin ->
            listOf(
                bin.verdictText(plan.options.mergeMinCount), "${bin.manifests.size}", formatBytes(bin.bytes),
                formatCount(bin.manifests.sumOf { it.entries.size }), bin.mergedEntries?.let { formatCount(it.toLong()) } ?: "as they are", bin.reason,
            )
        },
    )
    table(
        "Manifests",
        listOf("Bin", "Merged", "Manifest", "Bytes", "Adds", "Deletes"),
        plan.bins.flatMapIndexed { i, bin ->
            bin.manifests.map { m -> listOf("${i + 1}", if (bin.merged) "yes" else "no", m.name, formatBytes(m.sizeBytes), formatCount(m.addedFiles), formatCount(m.deletedFiles)) }
        },
    )
}

private fun DetailBuilder.compaction(input: PaimonMaintenanceInput) {
    val current = input.current ?: return
    val lsms = current.bucketLsms ?: return run { notes += "not readable: the latest snapshot's manifests could not be replayed" }
    if (!current.hasPrimaryKey) {
        val verdicts = lsms.groupBy { it.partition }.entries.sortedBy { it.key }.map { (partition, trees) ->
            paimonAppendVerdict(partition, trees.flatMap { t -> t.runs.flatMap { it.files } }, current.tableOptions)
        }
        notes += "an append table compacts only when sys.compact or a compaction job runs: it packs the files under 7/10 of target-file-size " +
            "per partition, and a pack is a task once it holds compaction.min.file-num files or twice the target in bytes"
        table(
            "Partitions",
            listOf("sys.compact", "Partition", "Small Files", "Files", "Small Bytes", "Threshold"),
            verdicts.map { v -> listOf(v.describe(), partitionCell(v.partition), "${v.smallFileCount}", "${v.fileCount}", formatBytes(v.smallFileBytes), formatBytes(v.compactionFileSizeBytes)) },
        )
        return
    }
    val options = PaimonCompactionOptions.from(current.tableOptions)
    notes += "each bucket is an LSM tree — every level-0 file a sorted run, each higher level one — and the writer asks UniversalCompaction.pick() on " +
        "every flush: under num-sorted-run.compaction-trigger (${options.trigger}) runs nothing; at it, by size — the runs newer than the oldest " +
        "against ${options.maxSizeAmplificationPercent}% of the oldest, else the newest runs within ${options.sizeRatioPercent}% of each other; above " +
        "it, regardless; past num-sorted-run.stop-trigger (${options.stopTrigger}) the writer waits" +
        (if (options.forceUpLevel0) "; this table forces level 0 up on every flush (lookup, deletion vectors or first-row)" else "") +
        (if (options.writeOnly) "; this table is write-only: nothing compacts" else "")
    table(
        "Buckets",
        listOf("Next Flush", "Partition", "Bucket", "Sorted Runs", "Levels", "Files", "Bytes"),
        lsms.map { it.planCompaction(options) }.map { v ->
            listOf(
                v.describe(), partitionCell(v.lsm.partition), "${v.lsm.bucket}", "${v.lsm.sortedRunCount} of ${options.trigger}",
                v.lsm.describeLevels(), "${v.lsm.fileCount}", formatBytes(v.lsm.runs.sumOf { it.sizeBytes }),
            )
        },
    )
}

private fun DetailBuilder.fullCompaction(input: PaimonMaintenanceInput) {
    val current = input.current ?: return
    val lsms = current.bucketLsms ?: return run { notes += "not readable: the latest snapshot's manifests could not be replayed" }
    val options = PaimonFullCompactionOptions.from(current.tableOptions)
    val vectored = current.readInput.value?.vectors?.map { it.dataFileName }?.toSet().orEmpty()
    val verdicts = lsms.map { it.planFullCompaction(options, vectored) }
    notes += "compact_strategy full, the default, as pickFullCompaction and MergeTreeCompactTask do it: a bucket whose only run is at the top level " +
        "(${verdicts.firstOrNull()?.outputLevel ?: (options.numLevels - 1)}) is left alone unless a file carries a deletion vector, rewritten in " +
        "place; otherwise every run goes into one unit cut into sections of intersecting key ranges — a section of several files is rewritten " +
        "together, a lone file under compaction.file-size (${formatBytes(options.minFileSizeBytes)}) joins the pending rewrite, and a lone file at " +
        "or over it is upgraded to the top level unless it holds -D rows" +
        (if (options.forceRewriteAllFiles) "; compaction.force-rewrite-all-files is set: nothing is upgraded" else "") +
        (if (options.recordLevelExpire) "; record-level.expire-time is set: files holding expired records are rewritten too, not evaluated here" else "")
    table(
        "Buckets",
        listOf("sys.compact", "Partition", "Bucket", "Levels", "Files", "-D Rows Dropped"),
        verdicts.map { v -> listOf(v.describe(), partitionCell(v.lsm.partition), "${v.lsm.bucket}", v.lsm.describeLevels(), "${v.lsm.fileCount}", "${v.deleteRowsDropped}") },
    )
    val files = verdicts.flatMap { v -> v.files.map { v to it } }
    if (files.none { (_, f) -> f.action != PaimonFullCompactionAction.KEEP }) return
    table(
        "Files",
        listOf("Action", "File", "Level", "Group", "Rows", "-D Rows", "Bytes", "Bucket", "Why"),
        files.map { (v, f) ->
            listOf(
                f.action.label,
                f.file.fileName ?: "—",
                "${f.file.level ?: 0}" + (if (f.action != PaimonFullCompactionAction.KEEP && (f.file.level ?: 0) != v.outputLevel) " → ${v.outputLevel}" else ""),
                f.group?.toString() ?: "—",
                f.file.rowCount?.let { formatCount(it) } ?: "—",
                f.file.deleteRowCount?.let { formatCount(it) } ?: "—",
                bytes(f.file.fileSize),
                "${v.lsm.bucket}",
                f.reason,
            )
        },
    )
}

private fun DetailBuilder.paimonFastForward(node: GraphNode.TableNode) {
    val branches = node.summary.branches.orEmpty()
    val plans = node.paimonExpiryFiles.value?.let { f -> branches.mapNotNull { f.planFastForward(it.name) } }
        ?: return run { notes += "not readable: the manifests could not be read" }
    notes += "sys.fast_forward(branch) replaces main from the branch's earliest snapshot id on: main's snapshot files from that id, its schema " +
        "files from that snapshot's schema id and every tag at or above the id are deleted, and the branch's snapshot/, schema/ and tag/ are " +
        "copied over main's; main's own commits from that id are not merged, and the files they wrote stay on disk named by nothing"
    table(
        "Branches",
        listOf("Verdict", "Branch", "Main Loses", "Main Gains", "Left Named By Nothing", "Why"),
        plans.map { p -> listOf(p.verdictText, p.branch, p.losesText, p.gainsText, p.leftoversText, p.whyText) },
    )
    table(
        "Left named by nothing",
        listOf("Kind", "File", "Bytes", "Written By", "After"),
        plans.flatMap { p -> p.leftovers.map { f -> listOf(f.kind.label, f.path ?: f.name, bytes(f.sizeBytes), f.snapshotId?.let { "snapshot $it" } ?: "—", p.branch) } },
    )
}

private fun DetailBuilder.optimize(model: DeltaUnifiedTableModel, zOrderBy: List<String>, where: DeltaOptimizeWhere?) {
    val plan = model.planOptimize(zOrderBy, where).getOrElse {
        notes += "not planned: ${it.message ?: "the latest version could not be rebuilt"}"
        return
    }
    if (zOrderBy.isNotEmpty() || where != null) notes += "${deltaOptimizeCall(where, zOrderBy)}, in place of the bare call above: ${plan.headline}"
    if (plan.refusal != null) return
    notes += "at version ${plan.version}, ${plan.mode.label}: ${plan.ruleText}"
    if (plan.deletionVectorsCounted > 0) {
        notes += "numDeletionVectorsRemoved would record ${plan.deletionVectorsCounted}: the vectors on every file the run considers, not only the ones it removes"
    }
    if (plan.bins.isNotEmpty()) {
        table(
            if (plan.mode == DeltaOptimizeMode.CLUSTERING) "New cubes" else "Bins",
            listOf("Bin", "Partition", "Files", "Bytes"),
            plan.bins.map { bin -> listOf(bin.verdictText, bin.partitionText, "${bin.files.size}", formatBytes(bin.bytes)) },
        )
    }
    val binOf = plan.bins.flatMapIndexed { i, bin -> bin.files.map { it.add.key to i + 1 } }.toMap()
    table(
        "Files",
        listOf("Candidate", "Bin", "File", "Size", "Why"),
        plan.files.map { f -> listOf(if (f.candidateBecause != null) "yes" else "no", binOf[f.add.key]?.toString() ?: "—", f.add.path, bytes(f.add.size), f.whyText) },
    )
}
