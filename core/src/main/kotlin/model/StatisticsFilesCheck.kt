package model

/**
 * The newest metadata's statistics and partition-statistics files, each opened and put against
 * the record `metadata.json` keeps of it — behind the same click as the data files
 * ([sweepFileStats]), because each is a file read. The metadata panel draws the same
 * comparison per file; this is the whole table's, listed beside the other findings.
 *
 * What a table statistics file is held to is its Puffin footer: a blob the record names and
 * the file does not hold, or an `ndv` the two disagree on ([StatisticsBlobRow.agrees]). A
 * partition statistics file is held to its size on disk against `file-size-in-bytes`, and its
 * rows to the live files of the snapshot it names ([checkPartitionStatistics]) — the figures a
 * planner reads instead of walking the manifests. A file that could not be opened is the
 * finding that matters most: `metadata.json` carries a copy of the blob metadata so a planner
 * never opens the file, which is what lets an orphan cleanup delete it with nothing noticing.
 */
enum class StatisticsFileKind(val label: String) { TABLE("statistics file"), PARTITION("partition statistics file") }

data class StatisticsFileCheck(
    val name: String,
    val kind: StatisticsFileKind,
    val snapshotId: Long?,
    /** Comparisons with both sides. */
    val figures: Int,
    val findings: List<IntegrityFinding>,
    /** Why the file could not be read, when it could not. */
    val problem: String? = null,
) {
    val read: Boolean get() = problem == null
}

/**
 * [StatisticsFileCheck] per file the newest metadata names, from the footers and reads already
 * done — injected so the builder's readers stay the one place a statistics path is resolved.
 */
fun UnifiedTableModel.checkStatisticsFiles(
    footers: Map<String, StatisticsFileFooter>,
    partitions: Map<String, PartitionStatisticsRead>,
): List<StatisticsFileCheck> {
    val newest = metadatas.lastOrNull()?.metadata ?: return emptyList()
    val rows = statisticsRows(newest) { file -> file.statisticsPath?.let { footers[it]?.footer } }.groupBy { it.fileName }
    val tableFiles = newest.statistics.mapNotNull { file ->
        val recorded = file.statisticsPath ?: return@mapNotNull null
        val name = recorded.substringAfterLast('/')
        val footer = footers[recorded]
        val blobRows = rows[name].orEmpty()
        val findings = blobRows.filter { it.agrees == false }.map { row ->
            IntegrityFinding(IntegrityCheck.STATISTICS_FILES, "$name (${row.column})", "ndv", row.ndv?.toString() ?: "—", row.fileNdv?.toString() ?: "no such blob in the file")
        }
        StatisticsFileCheck(name, StatisticsFileKind.TABLE, file.snapshotId, blobRows.count { it.fileRead }, findings, footer?.problem ?: if (footer == null) "not read" else null)
    }
    val partitionFiles = newest.partitionStatistics.mapNotNull { file ->
        val recorded = file.statisticsPath ?: return@mapNotNull null
        val name = recorded.substringAfterLast('/')
        val read = partitions[recorded]
        if (read?.rows == null) return@mapNotNull StatisticsFileCheck(name, StatisticsFileKind.PARTITION, file.snapshotId, 0, emptyList(), read?.problem ?: "not read")
        val findings = mutableListOf<IntegrityFinding>()
        var figures = 0
        if (file.fileSizeInBytes != null && read.sizeOnDisk != null) {
            figures++
            if (file.fileSizeInBytes != read.sizeOnDisk) findings += IntegrityFinding(IntegrityCheck.PARTITION_STATISTICS, name, "file size", file.fileSizeInBytes.toString(), read.sizeOnDisk.toString())
        }
        val snapshot = file.snapshotId?.let { id -> metadatas.asReversed().firstNotNullOfOrNull { um -> um.snapshots.firstOrNull { it.metadata.snapshotId == id } } }
        if (snapshot != null && !snapshot.expired) {
            for (verdict in checkPartitionStatistics(read.rows, liveFilesOf(snapshot))) {
                val where = "$name, partition ${verdict.partition}"
                when {
                    verdict.recorded == null -> findings += IntegrityFinding(IntegrityCheck.PARTITION_STATISTICS, where, "partition", "not listed", "holds live files")
                    verdict.counted == null -> findings += IntegrityFinding(IntegrityCheck.PARTITION_STATISTICS, where, "partition", "listed", "holds no live file")
                    else -> {
                        figures += PARTITION_STATS_FIGURES
                        verdict.differences.forEach { d -> findings += IntegrityFinding(IntegrityCheck.PARTITION_STATISTICS, where, d.figure, d.recorded.toString(), d.counted.toString()) }
                    }
                }
            }
        }
        StatisticsFileCheck(name, StatisticsFileKind.PARTITION, file.snapshotId, figures, findings)
    }
    return tableFiles + partitionFiles
}
