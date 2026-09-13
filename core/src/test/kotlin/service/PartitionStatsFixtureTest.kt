package service

import model.DataFileContent
import model.GraphNode
import model.ManifestEntryStatus
import model.PathResolution
import model.UnifiedTableModel
import model.readPartitionStatistics
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `example/iceberg/default/pstats`: a partitioned table with a partition statistics file, from
 * `docs/fixtures/pstats.sql`, written with the Iceberg 1.10 runtime — `compute_partition_stats`
 * does not exist in the image's 1.8.1, which is why `partition-statistics` was modelled from the
 * spec with no oracle until this table.
 *
 * Two oracles. The writer printed its own `pstats.partitions` at the end of the script —
 * `apac 1 record 1 file 862 B; eu 3 records 2 files 1,759 B, 1 delete record in 1 delete file;
 * us 1 record 1 file 855 B` — and the file has to agree with it. And this app's own live-file
 * walk of the current snapshot, grouped by partition, has to come to the same figures: two ways
 * of counting one set, the same rule `SnapshotDiffTest` holds `TableSummary.current` to.
 */
class PartitionStatsFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val tableDir: Path = Paths.get(File(repoRoot, "example/iceberg/default/pstats").absolutePath)
    private val model = UnifiedTableModel(tableDir)
    private val current = model.metadatas.last()
    private val record = current.metadata.partitionStatistics.single()

    @Test
    fun `the record names the current snapshot's file and the size it has on disk`() {
        assertEquals(current.metadata.currentSnapshotId, record.snapshotId)
        val name = record.statisticsPath!!.substringAfterLast('/')
        assertTrue(name.startsWith("partition-stats-${record.snapshotId}-") && name.endsWith(".parquet"), name)
        assertEquals(4191L, record.fileSizeInBytes)
        assertEquals(4191L, Files.size(tableDir.resolve("metadata").resolve(name)))
        assertTrue(current.metadata.statistics.isEmpty(), "no Puffin statistics; the two are separate records")
    }

    @Test
    fun `the file holds one row per partition with the writer's own figures`() {
        val read = readPartitionStatistics(tableDir.resolve("metadata").resolve(record.statisticsPath!!.substringAfterLast('/')), PathResolution.FORCED_RELATIVE)
        assertNull(read.problem)
        assertEquals(4191L, read.sizeOnDisk)
        val rows = assertNotNull(read.rows).sortedBy { it.partition }
        assertEquals(listOf("p=apac", "p=eu", "p=us"), rows.map { it.partition })
        assertEquals(listOf(1L, 3L, 1L), rows.map { it.dataRecordCount })
        assertEquals(listOf(1, 2, 1), rows.map { it.dataFileCount })
        assertEquals(listOf(862L, 1759L, 855L), rows.map { it.totalDataFileSizeInBytes })
        assertEquals(listOf(0L, 1L, 0L), rows.map { it.positionDeleteRecordCount })
        assertEquals(listOf(0, 1, 0), rows.map { it.positionDeleteFileCount })
        assertTrue(rows.all { it.equalityDeleteRecordCount == 0L && it.equalityDeleteFileCount == 0 && it.specId == 0 })
        assertTrue(rows.all { it.totalRecordCount == null }, "optional in the spec, and 1.10 writes it null")
        assertTrue(!read.truncated)
        // Each partition's last snapshot is the one that last wrote into it: us by the first
        // insert, apac by the second, eu by the delete — which is also the current snapshot.
        val bySequence = current.snapshots.sortedBy { it.metadata.sequenceNumber }.map { it.metadata.snapshotId }
        assertEquals(3, bySequence.size)
        assertEquals(listOf(bySequence[1], bySequence[2], bySequence[0]), rows.map { it.lastUpdatedSnapshotId })
        assertTrue(rows.all { (it.lastUpdatedAtMs ?: 0) > 1_700_000_000_000L })
    }

    @Test
    fun `this app's own walk of the live files comes to the same figures`() {
        val snapshot = current.snapshots.single { it.metadata.snapshotId == current.metadata.currentSnapshotId }
        val live = snapshot.manifests.flatMap { it.dataFiles }
            .filter { it.metadata.status != ManifestEntryStatus.DELETED }
            .distinctBy { it.metadata.dataFile?.filePath }
        val byPartition = live.groupBy { it.partition!!.path }
        val read = readPartitionStatistics(tableDir.resolve("metadata").resolve(record.statisticsPath!!.substringAfterLast('/')), PathResolution.FORCED_RELATIVE)
        val rows = read.rows!!.associateBy { it.partition }
        assertEquals(rows.keys, byPartition.keys)
        rows.forEach { (partition, row) ->
            val data = byPartition.getValue(partition).filter { it.metadata.dataFile?.content == DataFileContent.DATA }
            val deletes = byPartition.getValue(partition).filter { it.metadata.dataFile?.content == DataFileContent.POSITION_DELETES }
            assertEquals(data.size, row.dataFileCount, partition)
            assertEquals(data.sumOf { it.metadata.dataFile?.recordCount ?: 0L }, row.dataRecordCount, partition)
            assertEquals(data.sumOf { it.metadata.dataFile?.fileSizeInBytes ?: 0L }, row.totalDataFileSizeInBytes, partition)
            assertEquals(deletes.size, row.positionDeleteFileCount, partition)
            assertEquals(deletes.sumOf { it.metadata.dataFile?.recordCount ?: 0L }, row.positionDeleteRecordCount, partition)
        }
    }

    @Test
    fun `the metadata node opens the file on demand and reports how`() {
        val graph = GraphLayoutService.layoutGraph(model, showRows = false)
        val newest = graph.nodes.filterIsInstance<GraphNode.MetadataNode>().single { it.data.partitionStatistics.isNotEmpty() }
        val reads = assertNotNull(newest.partitionStatistics.value)
        val read = assertNotNull(reads[record.statisticsPath])
        assertEquals(PathResolution.FORCED_RELATIVE, read.resolution, "the recorded /wh path is not on this machine")
        assertTrue(read.fileRead)
        assertEquals(3, read.rows?.size)
        // Every older metadata version names no file and defers nothing.
        graph.nodes.filterIsInstance<GraphNode.MetadataNode>().filter { it !== newest }.forEach {
            assertTrue(!it.partitionStatistics.isPresent, it.fileName)
        }
    }
}
