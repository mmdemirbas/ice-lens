package model

import java.nio.file.Files
import java.nio.file.Path

/**
 * One row of a partition statistics file: what one partition holds, as the writer summed it.
 *
 * `metadata.json` names the file and nothing more (`PartitionStatisticsFile`); the figures are
 * inside it, one row per partition, and a planner reads them instead of walking every manifest to
 * answer "how big is partition `eu`". The columns are the spec's, by their spec names, and the
 * file is Parquet — so it is read through the same DuckDB connection a data file's rows are, with
 * a cap of its own: a table has as many rows here as it has partitions, and fifty was chosen for
 * a *sample* of a data file.
 *
 * [totalRecordCount] is optional in the spec and Iceberg 1.10's `compute_partition_stats` writes
 * it null, which is what a reader should expect to see rather than a figure.
 */
data class PartitionStatsRow(
    /** `p=eu` — the partition as `name=value` pairs, the form the file path carries. */
    val partition: String,
    val specId: Int?,
    val dataRecordCount: Long?,
    val dataFileCount: Int?,
    val totalDataFileSizeInBytes: Long?,
    val positionDeleteRecordCount: Long?,
    val positionDeleteFileCount: Int?,
    val equalityDeleteRecordCount: Long?,
    val equalityDeleteFileCount: Int?,
    val totalRecordCount: Long?,
    val lastUpdatedAtMs: Long?,
    val lastUpdatedSnapshotId: Long?,
)

/** The most rows read from one partition statistics file. */
const val MAX_PARTITION_STATS_ROWS = 1_000

/**
 * A partition statistics file as opened, or why it was not — the same three outcomes a Puffin
 * statistics file has ([StatisticsFileFooter]), because the record in `metadata.json` can outlive
 * the file it names.
 */
data class PartitionStatisticsRead(
    val localPath: String,
    val resolution: PathResolution,
    /** Bytes on disk, to put beside the `file-size-in-bytes` the record claims. Null when absent. */
    val sizeOnDisk: Long?,
    /** The rows, in file order; null when the file could not be read. */
    val rows: List<PartitionStatsRow>?,
    /** True when the file holds more rows than [MAX_PARTITION_STATS_ROWS] and [rows] is a prefix. */
    val truncated: Boolean = false,
    val problem: String? = null,
) {
    val fileRead: Boolean get() = rows != null
}

/** Opens [path] and reads its rows; the DuckDB query itself is [service.SampleRowReader.queryPartitionStats]. */
fun readPartitionStatistics(path: Path, resolution: PathResolution): PartitionStatisticsRead {
    val size = runCatching { Files.size(path) }.getOrNull()
    val read = runCatching { service.SampleRowReader.queryPartitionStats(path.toString(), MAX_PARTITION_STATS_ROWS + 1) }
    val raw = read.getOrNull()
    val rows = raw?.take(MAX_PARTITION_STATS_ROWS)?.map { row ->
        PartitionStatsRow(
            partition = row["partition"]?.toString() ?: "",
            specId = (row["spec_id"] as? Number)?.toInt(),
            dataRecordCount = (row["data_record_count"] as? Number)?.toLong(),
            dataFileCount = (row["data_file_count"] as? Number)?.toInt(),
            totalDataFileSizeInBytes = (row["total_data_file_size_in_bytes"] as? Number)?.toLong(),
            positionDeleteRecordCount = (row["position_delete_record_count"] as? Number)?.toLong(),
            positionDeleteFileCount = (row["position_delete_file_count"] as? Number)?.toInt(),
            equalityDeleteRecordCount = (row["equality_delete_record_count"] as? Number)?.toLong(),
            equalityDeleteFileCount = (row["equality_delete_file_count"] as? Number)?.toInt(),
            totalRecordCount = (row["total_record_count"] as? Number)?.toLong(),
            lastUpdatedAtMs = (row["last_updated_at"] as? Number)?.toLong(),
            lastUpdatedSnapshotId = (row["last_updated_snapshot_id"] as? Number)?.toLong(),
        )
    }
    return PartitionStatisticsRead(
        localPath = path.toString(),
        resolution = resolution,
        sizeOnDisk = size,
        rows = rows,
        truncated = (raw?.size ?: 0) > MAX_PARTITION_STATS_ROWS,
        problem = read.exceptionOrNull()?.let { it.message ?: it::class.simpleName },
    )
}
