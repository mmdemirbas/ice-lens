package model

/**
 * Every recorded figure the panels check against the same figure counted, checked across the
 * whole table at once — the question "is this table's metadata consistent" answered in one
 * place rather than one node at a time.
 *
 * It runs the checks the panels already run and none of its own: `manifestTallies` and
 * `partitionSummaryTallies` on every distinct manifest, `snapshotChangeOf(...).tallies` on every retained commit and
 * `snapshotTotals` on its closure for Iceberg; `paimonManifestTallies` on every distinct
 * manifest and `paimonRecordTallies` on every snapshot's replay, main and branches and the
 * tag-only snapshots, for Paimon. A figure a writer did not record is not a comparison, so
 * [IntegrityReport.checked] counts only the pairs with both sides.
 *
 * Two of those walk a closure per snapshot — the Iceberg totals and the Paimon replay — so they
 * stop after [MAX_CLOSURE_CHECKS] snapshots, newest first, and the report says how many it
 * reached. The per-manifest and per-commit checks fold each entry once and cover everything.
 * What is deliberately not here: the statistics and partition-statistics files, which are file
 * reads each panel does on its own.
 */
enum class IntegrityCheck(val label: String) {
    MANIFEST_COUNTS("manifest counts"),
    PARTITION_SUMMARIES("partition summaries"),
    COMMIT_SUMMARY("commit summary"),
    SNAPSHOT_TOTALS("snapshot totals"),
    RECORD_COUNTS("record counts"),
    /** A data file's recorded column bounds and counts against its rows — the file read, see [sweepFileStats]. */
    FILE_STATISTICS("file statistics"),
    /** A statistics file's Puffin footer against the record `metadata.json` keeps of it — see [checkStatisticsFiles]. */
    STATISTICS_FILES("statistics files"),
    /** A partition statistics file's size and rows against the live files of its snapshot — see [checkStatisticsFiles]. */
    PARTITION_STATISTICS("partition statistics"),
    /** A data file's partition tuple against its own column bounds — see [partitionBoundsChecks]. */
    FILE_PARTITIONS("file partitions"),
    /** The metadata file's own figures against its contents — the ids the next DDL allocates from, what a reader refuses on — see [metadataTallies]. */
    METADATA_FIGURES("metadata figures"),
    /** A data file's recorded statistics against the shape `write.metadata.metrics.*` says for each column — see [metricsModeChecks]. */
    METRICS_MODES("metrics modes"),
    /** A live Paimon file's `_TOTAL_BUCKETS` against the table's `bucket` — the count a write is refused on until a rescale; see [paimonBucketCountChecks]. */
    BUCKET_COUNT("bucket counts"),
}

data class IntegrityFinding(
    val check: IntegrityCheck,
    /** The artifact, the way its panel names it — `snapshot 42 (append)`, a manifest's file name. */
    val where: String,
    val figure: String,
    val recorded: String,
    val counted: String,
)

data class IntegrityReport(
    /** Comparisons with both a recorded and a counted side. */
    val checked: Int,
    val findings: List<IntegrityFinding>,
    /** How many snapshots the closure-walking checks reached, of how many there are. */
    val closuresChecked: Int,
    val snapshotCount: Int,
    /** Read errors the model carries — artifacts that could not be compared at all. */
    val readErrors: Int,
) {
    val describe: String get() = when {
        findings.isEmpty() -> "every one of the $checked comparisons agrees"
        else -> "${findings.size} of $checked comparisons disagree"
    }
}

/** Snapshots whose closure the report walks, newest first. */
const val MAX_CLOSURE_CHECKS = 50

private class Tallying {
    var checked = 0
    val findings = mutableListOf<IntegrityFinding>()
    fun count(check: IntegrityCheck, where: String, figure: String, recorded: Any?, counted: Any?, agrees: Boolean?) {
        if (agrees == null) return
        checked++
        if (!agrees) findings += IntegrityFinding(check, where, figure, recorded.toString(), counted.toString())
    }
}

fun UnifiedTableModel.integrityReport(maxClosureChecks: Int = MAX_CLOSURE_CHECKS): IntegrityReport {
    val t = Tallying()
    // A field a rewritten manifest's schema no longer has is named by the newest schema that had it — the file panel's rule for its bounds.
    val tableFieldsById = metadatas.lastOrNull()?.metadata?.fieldsEverDefined().orEmpty()
    // One copy per snapshot, the newest metadata's, newest first — the closures the cap leaves
    // unchecked are the oldest, which are the ones a reader is least likely to be asking about.
    val retained = metadatas.asReversed().flatMap { it.snapshots }
        .filter { !it.expired && it.metadata.snapshotId != null }
        .distinctBy { it.metadata.snapshotId }
        .sortedWith(compareByDescending<UnifiedSnapshot> { it.metadata.effectiveSequenceNumber }.thenByDescending { it.metadata.timestampMs ?: Long.MIN_VALUE })
    fun name(s: UnifiedSnapshot) = "snapshot ${s.metadata.snapshotId}" + (s.metadata.summary["operation"]?.let { " ($it)" } ?: "")

    // The newest metadata's own figures — the one version a reader opens, and the one the next commit allocates from.
    metadatas.lastOrNull()?.let { newest ->
        metadataTallies(newest.metadata).forEach { t.count(IntegrityCheck.METADATA_FIGURES, newest.path.fileName.toString(), it.label, it.recorded, it.counted, it.agrees) }
    }
    val seenManifests = mutableSetOf<String>()
    val metricsHistory = MetricsConfigHistory(metadatas.map { it.metadata })
    retained.forEach { s ->
        s.manifests.forEach { m ->
            val path = m.metadata.manifestPath ?: return@forEach
            if (!seenManifests.add(path)) return@forEach
            manifestTallies(m.metadata, m.dataFiles.map { it.metadata }, m.sizeOnDisk).forEach {
                t.count(IntegrityCheck.MANIFEST_COUNTS, path.substringAfterLast('/'), it.label, it.recorded, it.counted, it.agrees)
            }
            partitionSummaryTallies(m.partitionSummaries, m.dataFiles.map { it.partition }).forEach {
                t.count(IntegrityCheck.PARTITION_SUMMARIES, path.substringAfterLast('/'), "${it.field}: ${it.figure.lowercase()}", it.recorded, it.counted, it.agrees)
            }
            // Each entry's partition against its own bounds — per entry, since a rewritten manifest carries its own copy of both.
            m.dataFiles.forEach { file ->
                val data = file.metadata.dataFile ?: return@forEach
                val fileName = data.filePath?.substringAfterLast('/') ?: path.substringAfterLast('/')
                val columnStats = columnStatsFor(data, m.schema, tableFieldsById)
                file.partition?.takeIf { !it.isUnpartitioned }?.let { partition ->
                    partitionBoundsChecks(partition, columnStats).forEach {
                        t.count(IntegrityCheck.FILE_PARTITIONS, fileName, "partition ${it.field}", it.recorded, it.fromBounds ?: it.reason, it.agrees)
                    }
                }
                // Each column's statistics against the metrics configuration the file was written under —
                // not for a DELETED entry, whose snapshot_id names the commit that removed the file.
                if (file.metadata.status == ManifestEntryStatus.DELETED) return@forEach
                metricsHistory.at(file.metadata.snapshotId ?: m.metadata.addedSnapshotId)?.let { at ->
                    metricsModeChecks(columnStats, at.schema ?: m.schema, at.config, data).forEach {
                        t.count(IntegrityCheck.METRICS_MODES, fileName, "${it.column}: metrics", it.configured.mode.spelled, it.recorded, it.agrees)
                    }
                }
            }
        }
        snapshotChangeOf(s).tallies.forEach { t.count(IntegrityCheck.COMMIT_SUMMARY, name(s), it.label, it.recorded, it.counted, it.agrees) }
    }
    val closures = retained.take(maxClosureChecks)
    closures.forEach { s ->
        snapshotTotals(s.metadata.summary, liveFilesOf(s)).forEach { t.count(IntegrityCheck.SNAPSHOT_TOTALS, name(s), it.label, it.recorded, it.counted, it.agrees) }
    }
    val readErrors = readErrors.size + retained.sumOf { s -> s.readErrors.size + s.manifests.sumOf { it.readErrors.size } }
    return IntegrityReport(t.checked, t.findings, closures.size, retained.size, readErrors)
}

fun PaimonUnifiedTableModel.integrityReport(maxClosureChecks: Int = MAX_CLOSURE_CHECKS): IntegrityReport {
    val t = Tallying()
    data class Line(val branch: String?, val snapshots: List<PaimonUnifiedSnapshot>, val tagOnlyIds: Set<Long?>)
    // A long-lived changelog is checked too — its changelog records against its list — but
    // after the tag-only snapshots, so a snapshot both a tag and a changelog hold is the tag's.
    val lines = listOf(Line(null, snapshots + tagOnlySnapshots + changelogs, tagOnlySnapshots.map { it.metadata.id }.toSet())) +
        branches.map { Line(it.name, it.snapshots + it.tagOnlySnapshots + it.changelogs, it.tagOnlySnapshots.map { t -> t.metadata.id }.toSet()) }
    fun name(line: Line, s: PaimonUnifiedSnapshot) =
        "snapshot ${s.metadata.id}" + (s.metadata.commitKind?.let { " ($it)" } ?: "") +
            (if (s.metadata.id in line.tagOnlyIds) ", retained by a tag only" else if (s.longLivedChangelog) ", a long-lived changelog" else "") + (line.branch?.let { " on $it" } ?: "")

    val seenManifests = mutableSetOf<String>()
    var readErrors = readErrors.size
    var snapshotCount = 0
    val all = lines.flatMap { line -> line.snapshots.distinctBy { it.metadata.id }.map { line to it } }
    // Each line's schemas: the id the next column takes, and each snapshot's schema file present.
    (listOf(null to schemas) + branches.map { it.name to it.schemas }).forEach { (branch, lineSchemas) ->
        lineSchemas.forEach { schema ->
            paimonSchemaTallies(schema).forEach { t.count(IntegrityCheck.METADATA_FIGURES, "schema-${schema.id}" + (branch?.let { " on $it" } ?: ""), it.label, it.recorded, it.counted, it.agrees) }
        }
    }
    val schemaIdsByLine = (listOf(null to schemas) + branches.map { it.name to it.schemas }).associate { (b, ss) -> b to ss.mapNotNull { it.id }.toSet() }
    all.forEach { (line, s) ->
        (paimonSnapshotTallies(s.metadata, schemaIdsByLine[line.branch].orEmpty(), s.sizesOnDisk) + paimonIndexFileTallies(s.indexFiles, s.sizesOnDisk)).forEach { t.count(IntegrityCheck.METADATA_FIGURES, name(line, s), it.label, it.recorded, it.counted, it.agrees) }
        snapshotCount++
        readErrors += s.readErrors.size
        val changelogKeys = s.changelogManifests.map { paimonManifestKey(it) }.toSet()
        (s.baseManifests + s.deltaManifests + s.changelogManifests).forEach { m ->
            if (!seenManifests.add(paimonManifestKey(m))) return@forEach
            readErrors += m.readErrors.size
            val views = m.entries.mapIndexed { i, e -> PaimonManifestEntryView(i + 1, e.metadata, e.path.toString(), e.partition) }
            paimonManifestTallies(m.metadata, views, m.partitionMin, m.partitionMax, m.sizeOnDisk).forEach {
                t.count(IntegrityCheck.MANIFEST_COUNTS, "${m.metadata.fileName ?: m.path.fileName}" + (line.branch?.let { b -> " on $b" } ?: ""), it.label, it.recorded, it.counted, it.agrees)
            }
            val changelog = paimonManifestKey(m) in changelogKeys
            m.entries.forEach { e ->
                val fileName = e.metadata.file?.fileName ?: e.path.fileName.toString()
                e.partition?.takeIf { it.values.isNotEmpty() }?.let { partition ->
                    paimonPartitionBoundsChecks(partition, e.columnBounds, e.metadata.file?.rowCount).forEach {
                        t.count(IntegrityCheck.FILE_PARTITIONS, fileName, "partition ${it.field}", it.recorded, it.fromBounds ?: it.reason, it.agrees)
                    }
                }
                // Each value column's statistics against the stats mode its schema's options give it.
                e.metadata.file?.let { file ->
                    paimonStatsModeChecks(file, e.schema, e.columnBounds, changelog).forEach {
                        t.count(IntegrityCheck.METRICS_MODES, fileName, "${it.column}: stats-mode", it.configured.mode.spelled, it.recorded, it.agrees)
                    }
                }
            }
        }
    }
    // Newest first within each line, the cap shared across lines.
    val closures = all.sortedByDescending { it.second.metadata.id ?: Long.MIN_VALUE }.take(maxClosureChecks)
    closures.forEach { (line, s) ->
        val live = paimonLiveFilesOf(replayPaimonSnapshot(s)).sumOf { it.recordCount }
        paimonRecordTallies(s, live).forEach { t.count(IntegrityCheck.RECORD_COUNTS, name(line, s), it.label, it.recorded, it.counted, it.agrees) }
    }
    // Each line's latest snapshot: its live files' bucket count against the option in force.
    (listOf(null to snapshots to schemas) + branches.map { (it.name to it.snapshots) to it.schemas }).forEach { (lineSnapshots, lineSchemas) ->
        val (branch, snaps) = lineSnapshots
        val latest = snaps.maxByOrNull { it.metadata.id ?: Long.MIN_VALUE } ?: return@forEach
        val bucket = lineSchemas.maxByOrNull { it.id ?: -1 }?.options?.get(PAIMON_BUCKET_OPTION)?.toIntOrNull() ?: PAIMON_DEFAULT_BUCKET
        paimonBucketCountChecks(replayPaimonSnapshot(latest).liveEntries.values, bucket).forEach {
            t.count(IntegrityCheck.BUCKET_COUNT, it.fileName + (branch?.let { b -> " on $b" } ?: ""), "bucket" + (it.partition?.let { p -> " of $p" } ?: ""), it.configured, it.recorded, it.agrees)
        }
    }
    return IntegrityReport(t.checked, t.findings, closures.size, snapshotCount, readErrors)
}

const val PAIMON_BUCKET_OPTION = "bucket"
const val PAIMON_DEFAULT_BUCKET = -1

data class PaimonBucketCountCheck(val fileName: String, val partition: String?, val configured: Int, val recorded: Int, val agrees: Boolean?)

/**
 * Each live file's `_TOTAL_BUCKETS` against the table's `bucket` (release-1.3.1). A write
 * restores a bucket's files and refuses when the count they record differs from the option
 * (`AbstractFileStoreWrite.scanExistingFileMetas`: "Try to write … with a new bucket num N, but
 * the previous bucket num is M. Please switch to batch mode, and perform INSERT OVERWRITE to
 * rescale current data layout first."), and a commit refuses one partition's entries under two
 * counts unless it is an `OVERWRITE`. `SchemaManager` lets the option change — never from or to
 * -1, never through a dynamic option — and nothing else checks it, so the table reads and every
 * write fails until the rescale. An entry recording no count, or a count at or below zero, is
 * not compared: the commit-time check skips those too. `pbk` is the fixture.
 */
fun paimonBucketCountChecks(live: Collection<PaimonUnifiedDataFile>, configuredBucket: Int): List<PaimonBucketCountCheck> =
    live.map { e ->
        val recorded = e.metadata.totalBuckets
        val fileName = e.metadata.file?.fileName ?: e.path.fileName.toString()
        val partition = e.partition?.takeIf { it.values.isNotEmpty() }?.display
        if (recorded == null || recorded <= 0) PaimonBucketCountCheck(fileName, partition, configuredBucket, recorded ?: -1, null)
        else PaimonBucketCountCheck(fileName, partition, configuredBucket, recorded, recorded == configuredBucket)
    }
