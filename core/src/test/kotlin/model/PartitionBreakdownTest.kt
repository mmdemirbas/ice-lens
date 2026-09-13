package model

import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A snapshot's live files by partition, held to two writers' own figures: the per-partition rows
 * Iceberg 1.10's `compute_partition_stats` wrote for `pstats`, and the row counts
 * `paimon-pt.sql` put into each partition of `pt`. Neither was produced by this code.
 */
class PartitionBreakdownTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun `the Iceberg breakdown matches the partition statistics file the writer computed`() {
        val tableDir = Paths.get(File(repoRoot, "example/iceberg/default/pstats").absolutePath)
        val model = UnifiedTableModel(tableDir)
        val current = model.metadatas.last()
        val snapshot = current.snapshots.single { it.metadata.snapshotId == current.metadata.currentSnapshotId }
        val shares = liveFilesOf(snapshot).partitionBreakdown().associateBy { it.partition }
        val statsPath = current.metadata.partitionStatistics.single().statisticsPath!!
        val written = readPartitionStatistics(tableDir.resolve("metadata").resolve(statsPath.substringAfterLast('/')), PathResolution.FORCED_RELATIVE).rows!!
        assertEquals(written.map { it.partition }.toSet(), shares.keys)
        written.forEach { row ->
            val share = shares.getValue(row.partition)
            assertEquals(row.dataFileCount, share.dataFileCount, row.partition)
            assertEquals(row.dataRecordCount, share.dataRecordCount, row.partition)
            assertEquals(row.totalDataFileSizeInBytes, share.dataSizeBytes, row.partition)
            assertEquals(row.positionDeleteFileCount, share.deleteFileCount, row.partition)
            assertEquals(row.positionDeleteRecordCount, share.deleteRecordCount, row.partition)
        }
        // Largest first, and the eu partition — two data files, three rows and the delete — is it.
        assertEquals("p=eu", liveFilesOf(snapshot).partitionBreakdown().first().partition)
    }

    @Test
    fun `the Paimon breakdown matches what the script wrote into each partition`() {
        val model = PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/pt").absolutePath))
        val shares = paimonLiveFilesOf(model.snapshots.last()).partitionBreakdown()
        assertEquals(
            mapOf(
                "dt=2024-03-05, region=eu" to (1 to 2L),
                "dt=2024-03-05, region=north-america" to (2 to 2L),
                "dt=2024-03-06, region=eu" to (2 to 3L),
                "dt=2024-03-06, region=north-america" to (1 to 2L),
                "dt=2024-03-07, region=eu" to (1 to 1L),
            ),
            shares.associate { it.partition to (it.dataFileCount to it.dataRecordCount) },
        )
        assertTrue(shares.all { it.deleteFileCount == 0 && it.deleteRecordCount == 0L }, "Paimon has no delete files")
        assertTrue(shares.zipWithNext().all { (a, b) -> a.dataSizeBytes >= b.dataSizeBytes }, "largest first")
    }

    @Test
    fun `an unpartitioned table is one partition with an empty path, and a patch file adds no rows`() {
        val de = PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/de").absolutePath))
        val share = paimonLiveFilesOf(de.snapshots.last()).partitionBreakdown().single()
        assertEquals("", share.partition)
        assertEquals(3, share.dataFileCount)
        assertEquals(3L, share.dataRecordCount, "the two patch rows are rows another file holds")
        val plain = UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/test").absolutePath))
        val current = plain.metadatas.last()
        val snapshot = current.snapshots.single { it.metadata.snapshotId == current.metadata.currentSnapshotId }
        assertEquals(listOf(""), liveFilesOf(snapshot).partitionBreakdown().map { it.partition })
    }
}
