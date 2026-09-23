package model

/**
 * One maintenance procedure summed to a line: what it would do if run now, the procedure, the
 * figures behind the verdict, and the panel that holds the reasoning. The table panel's
 * `Maintenance` section draws these and `icelens plan` prints them, so the two shells cannot
 * say different things about one table.
 */
data class MaintenanceLine(val verdict: String, val procedure: String, val detail: String, val where: String, val tone: MaintenanceTone)

/**
 * Which lines are the exception. [ACTS] is a procedure that would change something; [ALERT] is
 * one that would destroy data, be refused, or block a writer — the desktop's error colour.
 */
enum class MaintenanceTone { PLAIN, ACTS, ALERT }

/**
 * Every maintenance procedure the planners cover, asked at the table's current snapshot at
 * [nowMs]. Nothing here plans anything: each line calls the same planner the procedure's own
 * section calls. `remove_orphan_files` needs a walk of the directory and `remove_unexisting_files`
 * a stat per needed file, so they are planned from [orphanReport] and [unexistingPlan] when a
 * caller has run them and say so when it has not, rather than starting either from a summary.
 */
fun maintenanceSummary(
    node: GraphNode.TableNode,
    nowMs: Long,
    orphanReport: UnreferencedFilesReport? = null,
    unexistingPlan: PaimonUnexistingFilesPlan? = null,
): List<MaintenanceLine> {
    val summary = node.summary
    val rows = mutableListOf<MaintenanceLine>()
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
            // What remove-dangling-deletes would take after that rewrite — only said where it takes something.
            val danglingLine = rewrite?.let { plan ->
                val live = current.liveFiles ?: return@let ""
                val unpartitionedSingleSpec = meta.partitionSpecs.size == 1 && meta.partitionSpecs.single().fields.isEmpty()
                val dangling = planDanglingDeletes(live, plan, current.data.sequenceNumber ?: 0L, unpartitionedSingleSpec)
                if (dangling.removed.isEmpty()) "" else "; remove-dangling-deletes would then take ${formatCounted(dangling.removed.size, "delete file")}"
            }.orEmpty()
            rows += when {
                rewrite == null -> MaintenanceLine("not readable", "rewrite_data_files", "the current snapshot's manifests are not retained", "$snapshotPanel → Rewrite", MaintenanceTone.PLAIN)
                rewritten.isNotEmpty() -> MaintenanceLine("would rewrite ${formatCounted(rewritten.sumOf { it.files.size }, "file")}", "rewrite_data_files", "${rewritten.size} of ${formatCounted(rewrite.groups.size, "group")}, ${formatBytes(rewritten.sumOf { it.inputBytes })}$danglingLine", "$snapshotPanel → Rewrite", MaintenanceTone.ACTS)
                rewrite.candidateCount > 0 -> MaintenanceLine("left alone", "rewrite_data_files", "${formatCounted(rewrite.candidateCount, "candidate")} in ${formatCounted(rewrite.groups.size, "group")}, none reaching min-input-files (${rewrite.options.minInputFiles})", "$snapshotPanel → Rewrite", MaintenanceTone.PLAIN)
                else -> MaintenanceLine("nothing to do", "rewrite_data_files", "every live file is within the size range and under the delete ratio", "$snapshotPanel → Rewrite", MaintenanceTone.PLAIN)
            }
            val deleteRewrite = current.liveFiles?.let { planPositionDeleteRewrite(it, PositionDeleteRewriteOptions.forTable(meta.properties, meta.formatVersion)) }
            val deleteRewritten = deleteRewrite?.rewrittenFiles.orEmpty()
            rows += when {
                deleteRewrite == null -> MaintenanceLine("not readable", "rewrite_position_delete_files", "the current snapshot's manifests are not retained", "$snapshotPanel → Position Delete Rewrite", MaintenanceTone.PLAIN)
                deleteRewrite.refused != null -> MaintenanceLine("refused", "rewrite_position_delete_files", "${deleteRewrite.refused}; ${formatCounted(deleteRewrite.deleteFileCount, "positional delete file")} live", "$snapshotPanel → Position Delete Rewrite", MaintenanceTone.ALERT)
                deleteRewrite.deleteFileCount == 0 -> MaintenanceLine("nothing to do", "rewrite_position_delete_files", "no live positional delete file", "$snapshotPanel → Position Delete Rewrite", MaintenanceTone.PLAIN)
                deleteRewritten.isNotEmpty() -> MaintenanceLine("would rewrite ${formatCounted(deleteRewritten.size, "delete file")}", "rewrite_position_delete_files", "${deleteRewrite.rewrittenGroups.size} of ${formatCounted(deleteRewrite.groups.size, "group")}, ${formatBytes(deleteRewrite.rewrittenGroups.sumOf { it.inputBytes })}; the dangling positions go with them", "$snapshotPanel → Position Delete Rewrite", MaintenanceTone.ACTS)
                deleteRewrite.candidateCount > 0 -> MaintenanceLine("left alone", "rewrite_position_delete_files", "${formatCounted(deleteRewrite.candidateCount, "candidate")} in ${formatCounted(deleteRewrite.groups.size, "group")}, none reaching min-input-files (${deleteRewrite.options.minInputFiles}); rewrite-all takes them", "$snapshotPanel → Position Delete Rewrite", MaintenanceTone.PLAIN)
                else -> MaintenanceLine("nothing to do", "rewrite_position_delete_files", "every live positional delete file is within the size range", "$snapshotPanel → Position Delete Rewrite", MaintenanceTone.PLAIN)
            }
            val mergeOptions = ManifestMergeOptions.forTable(meta.properties)
            val listed = current.manifestList
            val merge = planManifestMerge(listed, ManifestContent.DATA, assumedManifestBytes(listed, ManifestContent.DATA), meta.defaultSpecId, mergeOptions)
            rows += MaintenanceLine(
                if (merge.mergedBins.isNotEmpty()) "would merge ${formatCounted(merge.mergedBins.sumOf { it.manifests.size }, "manifest")}" else "nothing merges",
                "next append's manifest merge",
                "${formatCounted(listed.count { (it.content ?: ManifestContent.DATA) == ManifestContent.DATA }, "data manifest")} listed; ${merge.describe}" + if (mergeOptions.enabled) " under min-count-to-merge ${mergeOptions.minCountToMerge}" else "",
                "$snapshotPanel → Manifest Merge",
                if (merge.mergedBins.isNotEmpty()) MaintenanceTone.ACTS else MaintenanceTone.PLAIN,
            )
            val manifestRewrite = planManifestRewrite(listed, ManifestRewriteOptions.forTable(meta.properties, meta.defaultSpecId))
            rows += MaintenanceLine(
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
                    manifestRewrite.rewrites -> MaintenanceTone.ACTS
                    manifestRewrite.refused.isNotEmpty() -> MaintenanceTone.ALERT
                    else -> MaintenanceTone.PLAIN
                },
            )
        }
        val expiry = meta.planExpiry(ExpiryOptions(nowMs = nowMs, olderThanMs = nowMs))
        val expiryInput = node.expiryFiles.value
        val freed = expiryInput?.planExpiryFiles(expiry.removed.toSet())
        val cleanup = expiryInput?.planMetadataCleanup(expiry.removed.toSet())?.takeIf { it.removesAnything }
        rows += MaintenanceLine(
            if (expiry.removed.isEmpty()) "nothing expires" else "would remove ${formatCounted(expiry.removed.size, "snapshot")}",
            "expire_snapshots (older_than = now)",
            when {
                meta.snapshots.isEmpty() -> "the table has no snapshots"
                expiry.removed.isEmpty() -> "every snapshot is kept by a ref"
                freed == null -> "what that frees is not readable here"
                else -> "frees ${freed.describe} — ${formatBytes(freed.knownBytes)} the metadata accounts for, ${freed.cleanup.label}" +
                    (cleanup?.let { "; clean_expired_metadata ${it.describe()}" } ?: "")
            },
            "${input.metadataFileName} → Expiry, Expiry Files, Metadata Cleanup",
            if (expiry.removed.isEmpty()) MaintenanceTone.PLAIN else MaintenanceTone.ACTS,
        )
        if (meta.refs.size > 1) {
            val pairs = meta.fastForwardPlans()
            val moving = pairs.filter { it.moves }
            rows += MaintenanceLine(
                if (moving.isEmpty()) "nothing moves" else "${moving.size} of ${formatCounted(pairs.size, "pair")} would move",
                "fast_forward",
                if (moving.isEmpty()) "no branch's tip is an ancestor of another ref's snapshot: every line has moved on since it forked"
                else moving.joinToString("; ") { "${it.branch} → ${it.to} takes on ${formatCounted(it.gained.size, "commit")}" },
                "${input.metadataFileName} → Fast-Forward",
                if (moving.isEmpty()) MaintenanceTone.PLAIN else MaintenanceTone.ACTS,
            )
        }
    } else if (input is PaimonMaintenanceInput && paimonExpiry != null) {
        val current = input.current
        if (current != null) {
            val snapshotPanel = "snapshot ${current.simpleId}"
            val manifestInput = current.manifestMergeInput.value.orEmpty()
            val manifestMerge = planPaimonManifestMerge(manifestInput, PaimonManifestMergeOptions.forTable(current.tableOptions))
            rows += MaintenanceLine(
                if (manifestMerge.mergedBins.isNotEmpty()) "would merge ${formatCounted(manifestMerge.mergedManifests, "manifest")}" else "nothing merges",
                "next commit's manifest merge",
                "${formatCounted(manifestInput.size, "data manifest")} listed; ${manifestMerge.describe}",
                "$snapshotPanel → Manifest Merge",
                if (manifestMerge.mergedBins.isNotEmpty()) MaintenanceTone.ACTS else MaintenanceTone.PLAIN,
            )
            val manifestCompaction = planPaimonManifestCompaction(manifestInput, PaimonManifestMergeOptions.forTable(current.tableOptions))
            rows += MaintenanceLine(
                if (manifestCompaction.writesNewList) "would rewrite ${formatCounted(manifestCompaction.mergedManifests, "manifest")}" else "nothing to compact",
                "compact_manifest",
                manifestCompaction.describeCompaction,
                "$snapshotPanel → Manifest Merge",
                if (manifestCompaction.writesNewList) MaintenanceTone.ACTS else MaintenanceTone.PLAIN,
            )
            val branches = node.summary.branches.orEmpty()
            if (branches.isNotEmpty()) {
                val plans = node.paimonExpiryFiles.value?.let { files -> branches.mapNotNull { files.planFastForward(it.name) } }
                val dropping = plans.orEmpty().filter { it.refused == null && it.leftovers.isNotEmpty() }
                rows += when {
                    plans == null -> MaintenanceLine("not readable", "fast_forward", "the manifests could not be read", "table → Fast-Forward", MaintenanceTone.PLAIN)
                    dropping.isNotEmpty() -> MaintenanceLine(
                        "would drop ${formatCounted(dropping.sumOf { it.droppedCommits }, "commit")} of main",
                        "fast_forward",
                        dropping.joinToString("; ") { "${it.branch}: main from snapshot ${it.earliestId} on replaced, ${formatCounted(it.leftovers.size, "file")} left named by nothing" },
                        "table → Fast-Forward",
                        MaintenanceTone.ALERT,
                    )
                    plans.any { it.refused == null } -> MaintenanceLine("replaces nothing main lacks", "fast_forward", plans.filter { it.refused == null }.joinToString("; ") { "${it.branch}: main from snapshot ${it.earliestId} on is the branch's own line" }, "table → Fast-Forward", MaintenanceTone.PLAIN)
                    else -> MaintenanceLine("refused", "fast_forward", plans.joinToString("; ") { "${it.branch}: ${it.refused}" }, "table → Fast-Forward", MaintenanceTone.PLAIN)
                }
            }
            val lsms = current.bucketLsms
            if (current.hasPrimaryKey) {
                val options = PaimonCompactionOptions.from(current.tableOptions)
                val verdicts = lsms?.map { it.planCompaction(options) }.orEmpty()
                val due = verdicts.count { it.compacts }
                val stalled = verdicts.count { it.stalls }
                rows += when {
                    lsms == null -> MaintenanceLine("not readable", "compaction", "the latest snapshot's manifests could not be replayed", "$snapshotPanel → Compaction", MaintenanceTone.PLAIN)
                    options.writeOnly -> MaintenanceLine("never — write-only", "compaction", "${formatCounted(lsms.size, "bucket")}, ${formatCounted(lsms.sumOf { it.level0FileCount }, "level-0 file")} piling up", "$snapshotPanel → Compaction", MaintenanceTone.PLAIN)
                    stalled > 0 -> MaintenanceLine("a writer would wait on $stalled", "compaction", "$due of ${lsms.size} buckets due, $stalled past num-sorted-run.stop-trigger (${options.stopTrigger})", "$snapshotPanel → Compaction", MaintenanceTone.ALERT)
                    due > 0 -> MaintenanceLine("${formatCounted(due, "bucket")} due", "compaction", "$due of ${formatCounted(lsms.size, "bucket")} would compact on the next flush", "$snapshotPanel → Compaction", MaintenanceTone.ACTS)
                    else -> MaintenanceLine("not yet", "compaction", "${formatCounted(lsms.size, "bucket")}, every one under num-sorted-run.compaction-trigger (${options.trigger})", "$snapshotPanel → Compaction", MaintenanceTone.PLAIN)
                }
                val fullOptions = PaimonFullCompactionOptions.from(current.tableOptions)
                val vectored = current.readInput.value?.vectors?.map { it.dataFileName }?.toSet().orEmpty()
                val full = lsms?.map { it.planFullCompaction(fullOptions, vectored) }.orEmpty()
                val rewritten = full.sumOf { it.rewritten.size }
                val upgraded = full.sumOf { it.upgraded.size }
                rows += when {
                    lsms == null -> MaintenanceLine("not readable", "sys.compact", "the latest snapshot's manifests could not be replayed", "$snapshotPanel → Full Compaction", MaintenanceTone.PLAIN)
                    rewritten + upgraded > 0 -> MaintenanceLine(
                        "would rewrite ${formatCounted(rewritten, "file")}, upgrade $upgraded",
                        "sys.compact",
                        "${full.count { it.compacts }} of ${formatCounted(full.size, "bucket")} under compact_strategy full, the default; " +
                            "${formatCounted(full.sumOf { it.deleteRowsDropped }.toInt(), "-D row")} dropped",
                        "$snapshotPanel → Full Compaction",
                        MaintenanceTone.ACTS,
                    )
                    else -> MaintenanceLine("nothing to do", "sys.compact", "${formatCounted(full.size, "bucket")}, every one a single run at the top level with no vector", "$snapshotPanel → Full Compaction", MaintenanceTone.PLAIN)
                }
            } else {
                val verdicts = lsms?.groupBy { it.partition }?.entries?.map { (partition, trees) ->
                    paimonAppendVerdict(partition, trees.flatMap { t -> t.runs.flatMap { it.files } }, current.tableOptions)
                }.orEmpty()
                val packing = verdicts.count { it.wouldPack }
                rows += if (packing > 0) MaintenanceLine("sys.compact would run on $packing", "compaction", "$packing of ${formatCounted(verdicts.size, "partition")} have enough small files; a write never compacts an append table", "$snapshotPanel → Compaction", MaintenanceTone.ACTS)
                else MaintenanceLine("nothing to pack", "compaction", "${formatCounted(verdicts.size, "partition")}, none at compaction.min.file-num small files", "$snapshotPanel → Compaction", MaintenanceTone.PLAIN)
            }
        }
        val bare = runCatching { paimonExpiry.planExpiry(PaimonExpiryOptions(nowMs = nowMs)) }.getOrNull()
        val byAge = runCatching { paimonExpiry.planExpiry(PaimonExpiryOptions(nowMs = nowMs, retainMin = 1, olderThanMs = nowMs)) }.getOrNull()
        val freed = byAge?.let { p -> node.paimonExpiryFiles.value?.planExpiryFiles(p.removed.map { it.snapshotId }.toSet()) }
        val freeing = freed?.let { ", freeing ${it.describe}" + if (it.protectedByTag.isNotEmpty()) " (${it.protectedByTag.size} kept by a tag)" else "" } ?: ""
        rows += when {
            bare == null || byAge == null -> MaintenanceLine("rejected", "expire_snapshots", "the table's snapshot.* options are ones the procedure refuses", "table → Expiry", MaintenanceTone.ALERT)
            bare.removed.isNotEmpty() -> MaintenanceLine("would remove ${formatCounted(bare.removed.size, "snapshot")}", "expire_snapshots", "a bare call removes ${bare.removed.size}; retain_min = 1 with older_than = now removes ${byAge.removed.size}$freeing", "table → Expiry, Expiry Files", MaintenanceTone.ACTS)
            byAge.removed.isNotEmpty() -> MaintenanceLine("nothing on a bare call", "expire_snapshots", "retain_min = 1 with older_than = now would remove ${byAge.removed.size}$freeing", "table → Expiry, Expiry Files", MaintenanceTone.PLAIN)
            else -> MaintenanceLine("nothing expires", "expire_snapshots", "no call removes anything: the bounds, a consumer or a tag keep every snapshot", "table → Expiry", MaintenanceTone.PLAIN)
        }
        if (paimonExpiry.changelogTimes.isNotEmpty() || paimonChangelogLifecycleDecoupled(paimonExpiry.tableOptions)) {
            val changelogBare = runCatching { paimonExpiry.planChangelogExpiry(PaimonExpiryOptions(nowMs = nowMs)) }.getOrNull()
            val changelogByAge = runCatching { paimonExpiry.planChangelogExpiry(PaimonExpiryOptions(nowMs = nowMs, retainMin = 1, olderThanMs = nowMs)) }.getOrNull()
            rows += when {
                changelogBare == null || changelogByAge == null -> MaintenanceLine("rejected", "expire_changelogs", "the table's changelog.* options are ones the action refuses", "table → Changelog Expiry", MaintenanceTone.ALERT)
                paimonExpiry.changelogTimes.isEmpty() -> MaintenanceLine("nothing to expire", "expire_changelogs", "the changelog lifecycle is decoupled and changelog/ holds nothing yet", "table → Changelog Expiry", MaintenanceTone.PLAIN)
                changelogBare.removed.isNotEmpty() -> MaintenanceLine("would remove ${formatCounted(changelogBare.removed.size, "changelog")}", "expire_changelogs", "a bare call removes ${changelogBare.removed.size} of ${paimonExpiry.changelogTimes.size}; retain_min = 1 with older_than = now removes ${changelogByAge.removed.size}", "table → Changelog Expiry", MaintenanceTone.ACTS)
                changelogByAge.removed.isNotEmpty() -> MaintenanceLine("nothing on a bare call", "expire_changelogs", "retain_min = 1 with older_than = now would remove ${changelogByAge.removed.size} of ${paimonExpiry.changelogTimes.size}", "table → Changelog Expiry", MaintenanceTone.PLAIN)
                else -> MaintenanceLine("nothing expires", "expire_changelogs", "${formatCounted(paimonExpiry.changelogTimes.size, "long-lived changelog")}, every one within the bounds", "table → Changelog Expiry", MaintenanceTone.PLAIN)
            }
        }
        if (paimonExpiry.partitions.isNotEmpty()) {
            val partitions = paimonExpiry.planPartitionExpiry(nowMs)
            rows += when {
                partitions.expirationMs == null -> MaintenanceLine("not configured", "expire_partitions", "no partition.expiration-time is set: a write expires nothing and a bare call is refused; ${formatCounted(partitions.partitions.size, "partition")} listed", "table → Partition Expiry", MaintenanceTone.PLAIN)
                partitions.dropped.isNotEmpty() -> MaintenanceLine("would drop ${formatCounted(partitions.dropped.size, "partition")}", "expire_partitions", "of ${partitions.partitions.size} under ${partitions.strategySpelled}" + (if (partitions.heldBack > 0) ", ${partitions.heldBack} more past partition.expiration-max-num" else ""), "table → Partition Expiry", MaintenanceTone.ACTS)
                else -> MaintenanceLine("nothing expires", "expire_partitions", "${formatCounted(partitions.partitions.size, "partition")}, none past the cutoff under ${partitions.strategySpelled}", "table → Partition Expiry", MaintenanceTone.PLAIN)
            }
        }
        if (paimonExpiry.tags.isNotEmpty()) {
            val bareTags = paimonExpiry.planTagExpiry(nowMs)
            val byAgeTags = paimonExpiry.planTagExpiry(nowMs, olderThanMs = nowMs)
            val freed = bareTags.removed.sumOf { v -> node.paimonExpiryFiles.value?.planTagDeletion(v.tag.name)?.dataFiles?.size ?: 0 }
            rows += when {
                bareTags.removed.isNotEmpty() -> MaintenanceLine("would remove ${formatCounted(bareTags.removed.size, "tag")}", "expire_tags", "a bare call removes ${bareTags.removed.size} of ${paimonExpiry.tags.size}, whose retention ran out" + (if (freed > 0) ", freeing ${formatCounted(freed, "data file")}" else "") + "; older_than = now removes ${byAgeTags.removed.size}", "table → Tag Expiry", MaintenanceTone.ACTS)
                else -> MaintenanceLine("nothing on a bare call", "expire_tags", "${formatCounted(paimonExpiry.tags.size, "tag")}, ${paimonExpiry.tags.count { it.timeRetainedMs != null }} with a retention, none run out; older_than = now removes ${byAgeTags.removed.size}", "table → Tag Expiry", MaintenanceTone.PLAIN)
            }
        }
        val purge = node.paimonExpiryFiles.value?.planPurge()
        rows += when {
            purge == null -> MaintenanceLine("not readable", "purge_files", "the manifests could not be read", "table → Purge", MaintenanceTone.PLAIN)
            !purge.removesAnything -> MaintenanceLine("nothing to purge", "purge_files", "no snapshot, tag, branch or consumer: the truncate commit alone", "table → Purge", MaintenanceTone.PLAIN)
            else -> MaintenanceLine(
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
                MaintenanceTone.ALERT,
            )
        }
    }
    val recordedLocation = summary.location
    if (node.rewriteTablePath.isPresent && recordedLocation != null && recordedLocation != summary.tablePath) {
        // Planned under the prefixes a copied table has in hand; the section holds the form.
        val plan = node.rewriteTablePath.value?.planRewriteTablePath(RewriteTablePathOptions(recordedLocation, summary.tablePath))
        val refusal = plan?.refusal
        rows += when {
            plan == null -> MaintenanceLine("not readable", "rewrite_table_path", "the table's versions could not be read", "table → Rewrite Table Path", MaintenanceTone.PLAIN)
            refusal != null -> MaintenanceLine("refused", "rewrite_table_path", refusal, "table → Rewrite Table Path", MaintenanceTone.ALERT)
            else -> MaintenanceLine("would list ${formatCounted(plan.fileCount, "file")}", "rewrite_table_path", "${formatCounted(plan.versions.size, "version")}, ${formatCounted(plan.snapshotIds.size, "list")}, ${formatCounted(plan.manifests.size, "manifest")} rewritten into staging; $recordedLocation → ${summary.tablePath}", "table → Rewrite Table Path", MaintenanceTone.ACTS)
        }
    }
    if (paimonExpiry != null && node.missingFiles.isPresent) {
        rows += when {
            unexistingPlan == null -> MaintenanceLine("not checked", "remove_unexisting_files", "stat the files the retained snapshots need under Missing Files to plan it", "table → Missing Files", MaintenanceTone.PLAIN)
            unexistingPlan.rows.isEmpty() -> MaintenanceLine("nothing to do", "remove_unexisting_files", "every file the retained snapshots need is there", "table → Missing Files", MaintenanceTone.PLAIN)
            unexistingPlan.commits -> MaintenanceLine("would remove ${formatCounted(unexistingPlan.removed.size, "entry", "entries")}", "remove_unexisting_files", "an APPEND with a DELETE entry per missing data file of snapshot ${unexistingPlan.snapshotId}, deltaRecordCount ${unexistingPlan.deltaRecordCount}" + (if (unexistingPlan.notReached.size + unexistingPlan.unread.size > 0) "; ${unexistingPlan.notReached.size + unexistingPlan.unread.size} missing it does not list" else ""), "table → Missing Files", MaintenanceTone.ACTS)
            else -> MaintenanceLine("nothing on a call", "remove_unexisting_files", "${formatCounted(unexistingPlan.rows.size, "missing file")}, none a data file the latest snapshot's batch scan opens", "table → Missing Files", MaintenanceTone.PLAIN)
        }
    }
    if (node.unreferencedFiles.isPresent) {
        val orphans = orphanReport?.let { planOrphanRemoval(it, nowMs) }
        rows += when {
            orphans == null -> MaintenanceLine("not walked", "remove_orphan_files", "walk the table directory under Unreferenced Files to plan it", "table → Unreferenced Files", MaintenanceTone.PLAIN)
            orphans.rows.isEmpty() -> MaintenanceLine("nothing to delete", "remove_orphan_files", "every file on disk is named by the metadata the procedure reads", "table → Unreferenced Files", MaintenanceTone.PLAIN)
            orphans.removed.isEmpty() -> MaintenanceLine("nothing on a bare call", "remove_orphan_files", "${formatCounted(orphans.rows.size, "file")} named by nothing: ${orphans.tooYoung} younger than ${orphans.defaultIntervalText}, ${orphans.unlisted} where it never lists", "table → Unreferenced Files", MaintenanceTone.PLAIN)
            else -> MaintenanceLine("would delete ${formatCounted(orphans.removed.size, "file")}", "remove_orphan_files", "${formatBytes(orphans.removedBytes)}, older than ${orphans.defaultIntervalText}; ${orphans.tooYoung} younger held back, ${orphans.unlisted} never listed", "table → Unreferenced Files", MaintenanceTone.ACTS)
        }
    }
    return rows
}
