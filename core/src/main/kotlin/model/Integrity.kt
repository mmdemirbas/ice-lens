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
    // One copy per snapshot, the newest metadata's, newest first — the closures the cap leaves
    // unchecked are the oldest, which are the ones a reader is least likely to be asking about.
    val retained = metadatas.asReversed().flatMap { it.snapshots }
        .filter { !it.expired && it.metadata.snapshotId != null }
        .distinctBy { it.metadata.snapshotId }
        .sortedWith(compareByDescending<UnifiedSnapshot> { it.metadata.effectiveSequenceNumber }.thenByDescending { it.metadata.timestampMs ?: Long.MIN_VALUE })
    fun name(s: UnifiedSnapshot) = "snapshot ${s.metadata.snapshotId}" + (s.metadata.summary["operation"]?.let { " ($it)" } ?: "")

    val seenManifests = mutableSetOf<String>()
    retained.forEach { s ->
        s.manifests.forEach { m ->
            val path = m.metadata.manifestPath ?: return@forEach
            if (!seenManifests.add(path)) return@forEach
            manifestTallies(m.metadata, m.dataFiles.map { it.metadata }).forEach {
                t.count(IntegrityCheck.MANIFEST_COUNTS, path.substringAfterLast('/'), it.label, it.recorded, it.counted, it.agrees)
            }
            partitionSummaryTallies(m.partitionSummaries, m.dataFiles.map { it.partition }).forEach {
                t.count(IntegrityCheck.PARTITION_SUMMARIES, path.substringAfterLast('/'), "${it.field}: ${it.figure.lowercase()}", it.recorded, it.counted, it.agrees)
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
    val lines = listOf(Line(null, snapshots + tagOnlySnapshots, tagOnlySnapshots.map { it.metadata.id }.toSet())) +
        branches.map { Line(it.name, it.snapshots + it.tagOnlySnapshots, it.tagOnlySnapshots.map { t -> t.metadata.id }.toSet()) }
    fun name(line: Line, s: PaimonUnifiedSnapshot) =
        "snapshot ${s.metadata.id}" + (s.metadata.commitKind?.let { " ($it)" } ?: "") +
            (if (s.metadata.id in line.tagOnlyIds) ", retained by a tag only" else "") + (line.branch?.let { " on $it" } ?: "")

    val seenManifests = mutableSetOf<String>()
    var readErrors = readErrors.size
    var snapshotCount = 0
    val all = lines.flatMap { line -> line.snapshots.distinctBy { it.metadata.id }.map { line to it } }
    all.forEach { (line, s) ->
        snapshotCount++
        readErrors += s.readErrors.size
        (s.baseManifests + s.deltaManifests + s.changelogManifests).forEach { m ->
            if (!seenManifests.add(paimonManifestKey(m))) return@forEach
            readErrors += m.readErrors.size
            val views = m.entries.mapIndexed { i, e -> PaimonManifestEntryView(i + 1, e.metadata, e.path.toString(), e.partition) }
            paimonManifestTallies(m.metadata, views, m.partitionMin, m.partitionMax).forEach {
                t.count(IntegrityCheck.MANIFEST_COUNTS, "${m.metadata.fileName ?: m.path.fileName}" + (line.branch?.let { b -> " on $b" } ?: ""), it.label, it.recorded, it.counted, it.agrees)
            }
        }
    }
    // Newest first within each line, the cap shared across lines.
    val closures = all.sortedByDescending { it.second.metadata.id ?: Long.MIN_VALUE }.take(maxClosureChecks)
    closures.forEach { (line, s) ->
        val live = paimonLiveFilesOf(replayPaimonSnapshot(s)).sumOf { it.recordCount }
        paimonRecordTallies(s, live).forEach { t.count(IntegrityCheck.RECORD_COUNTS, name(line, s), it.label, it.recorded, it.counted, it.agrees) }
    }
    return IntegrityReport(t.checked, t.findings, closures.size, snapshotCount, readErrors)
}
