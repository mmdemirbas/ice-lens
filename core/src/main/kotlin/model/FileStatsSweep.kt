package model

/**
 * The statistics check ([checkStats]) over a snapshot's live files, for the table panel — the
 * file reads the metadata-only [integrityReport] leaves out, behind a click of their own and
 * capped, since a table has as many of these as data files.
 *
 * The targets come from the **model**, never from the drawn graph: aggregation folds the files
 * past the page size out of the graph, and those are files all the same. A disagreement is an
 * [IntegrityFinding] under [IntegrityCheck.FILE_STATISTICS], one per figure, so the table panel
 * lists it beside the metadata findings in the same shape.
 */
data class FileStatsTarget(
    /** The file as its panel names it. */
    val name: String,
    val localPath: String,
    val recorded: List<RecordedColumnStats>,
    val recordedRows: Long?,
    /** The table's name mapping, for a file recording no field ids — Iceberg only. */
    val nameMapping: NameMapping? = null,
)

data class FileStatsSweep(
    /** Live files the snapshot has, and how many of them were read under the cap. */
    val filesTotal: Int,
    val filesRead: Int,
    /** Comparisons with both sides, over the files read. */
    val figures: Int,
    val findings: List<IntegrityFinding>,
    /** Files that could not be read, with the reason. */
    val unreadable: List<Pair<String, String>>,
) {
    val describe: String get() = when {
        filesTotal == 0 -> "no live data file to read"
        filesRead < filesTotal -> "$filesRead of $filesTotal live files read" + (if (findings.isEmpty()) ", every one of their $figures figures agrees" else ", ${findings.size} of their $figures figures disagree")
        findings.isEmpty() -> "every one of the $figures figures agrees on all $filesRead live files"
        else -> "${findings.size} of $figures figures disagree on the $filesRead live files"
    }
}

/** Files read by one sweep, at most. */
const val MAX_FILE_STATS_CHECKS = 64

/** The sweep over [targets], each read by [read] — injected so the fold is testable without DuckDB and so a read that throws is one unreadable file, not a failed sweep. */
fun sweepFileStats(targets: List<FileStatsTarget>, max: Int = MAX_FILE_STATS_CHECKS, read: (FileStatsTarget) -> StatsCheckResult): FileStatsSweep {
    val findings = mutableListOf<IntegrityFinding>()
    val unreadable = mutableListOf<Pair<String, String>>()
    var figures = 0
    val taken = targets.take(max)
    for (target in taken) {
        val result = runCatching { read(target) }.getOrElse { e ->
            unreadable += target.name to (e.message ?: e.javaClass.simpleName)
            continue
        }
        figures += result.figures
        result.problems.forEach { p -> findings += IntegrityFinding(IntegrityCheck.FILE_STATISTICS, target.name, p.figure, p.recorded, p.counted) }
    }
    return FileStatsSweep(targets.size, taken.size, figures, findings, unreadable)
}

/**
 * The current snapshot's live data and delete files with what the manifests record about their
 * columns; empty without a current snapshot. A deletion vector is left out: its Puffin file has
 * no rows to count, and its cardinality is checked against `record_count` on its own panel.
 */
fun UnifiedTableModel.fileStatsTargets(): List<FileStatsTarget> {
    val newest = metadatas.lastOrNull() ?: return emptyList()
    val currentId = newest.metadata.currentSnapshotId ?: return emptyList()
    val snapshot = metadatas.asReversed().firstNotNullOfOrNull { um -> um.snapshots.firstOrNull { it.metadata.snapshotId == currentId } }
        ?.takeIf { !it.expired } ?: return emptyList()
    val live = liveFilesOf(snapshot).map { normalizeFilePath(it.path) }.toSet()
    val mapping = newest.metadata.nameMapping()
    val tableFieldsById = metadatas.lastOrNull()?.metadata?.fieldsEverDefined().orEmpty()
    val seen = mutableSetOf<String>()
    return snapshot.manifests.flatMap { m ->
        m.dataFiles.mapNotNull { unified ->
            val file = unified.metadata.dataFile ?: return@mapNotNull null
            val recorded = file.filePath ?: return@mapNotNull null
            if (deleteKindOf(file) == DeleteFileKind.DELETION_VECTOR) return@mapNotNull null
            val key = normalizeFilePath(recorded)
            if (key !in live || !seen.add(key)) return@mapNotNull null
            FileStatsTarget(
                name = recorded.substringAfterLast('/'),
                localPath = unified.path.toString(),
                recorded = recordedColumnStatsOf(columnStatsFor(file, m.schema, tableFieldsById), file),
                recordedRows = file.recordCount,
                nameMapping = mapping,
            )
        }
    }
}

/** The latest snapshot's live files on `main` with their `_VALUE_STATS` and `_KEY_STATS`; empty without one. */
fun PaimonUnifiedTableModel.fileStatsTargets(): List<FileStatsTarget> {
    val snapshot = snapshots.lastOrNull() ?: return emptyList()
    return replayPaimonSnapshot(snapshot).liveEntries.values.mapNotNull { entry ->
        val meta = entry.metadata.file ?: return@mapNotNull null
        FileStatsTarget(
            name = meta.fileName ?: return@mapNotNull null,
            localPath = entry.path.toString(),
            recorded = paimonRecordedColumnStats(entry.keyBounds, entry.columnBounds),
            recordedRows = meta.rowCount,
        )
    }
}
