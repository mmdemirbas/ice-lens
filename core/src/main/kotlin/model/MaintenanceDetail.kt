package model

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
 * detail starts none of them.
 */
fun maintenanceDetail(
    node: GraphNode.TableNode,
    line: MaintenanceLine,
    nowMs: Long,
    orphanReport: UnreferencedFilesReport? = null,
    unexistingPlan: PaimonUnexistingFilesPlan? = null,
    vacuumPlan: DeltaVacuumPlan? = null,
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
        else -> out.notes += "the plan's tables are not printed for this procedure yet; the line above is its summary"
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
