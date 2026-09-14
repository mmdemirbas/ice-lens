package model

import service.GraphLayoutService
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The statistics files behind the table panel's second click: `stats`' Puffin file against
 * its four blob records, `pstats`' partition statistics file against its record and the live
 * files of its snapshot, and three kinds of drift planted by hand — a blob the file does not
 * hold, a partition figure that differs, a file that is gone — each named as a finding.
 */
class StatisticsFilesCheckTest {

    private fun checksOf(fixture: String): List<StatisticsFileCheck> {
        val graph = GraphLayoutService.layoutGraph(FixtureCatalog.icebergModel(fixture), showRows = false)
        return assertNotNull(graph.nodes.filterIsInstance<GraphNode.TableNode>().single().statisticsFiles.value, fixture)
    }

    @Test
    fun `a statistics file's footer agrees with the record, blob for blob`() {
        val check = checksOf("stats").single()
        assertEquals(StatisticsFileKind.TABLE, check.kind)
        assertTrue(check.read, check.problem.toString())
        assertEquals(8, check.figures, "one ndv per blob against the file's property, and one against the sketch inside it")
        assertEquals(emptyList(), check.findings)
    }

    @Test
    fun `a partition statistics file agrees with its record and with the live files of its snapshot`() {
        val check = checksOf("pstats").single()
        assertEquals(StatisticsFileKind.PARTITION, check.kind)
        assertTrue(check.read, check.problem.toString())
        assertEquals(1 + 3 * PARTITION_STATS_FIGURES, check.figures, "the size, then seven figures for each of three partitions")
        assertEquals(emptyList(), check.findings)
    }

    @Test
    fun `every checked-in table's statistics files are read and agree`() {
        for (fixture in FixtureCatalog.iceberg) {
            val table = GraphLayoutService.layoutGraph(FixtureCatalog.icebergModel(fixture), showRows = false).nodes.filterIsInstance<GraphNode.TableNode>().single()
            val checks = table.statisticsFiles.value.orEmpty()
            assertTrue(checks.all { it.read }, "$fixture: ${checks.filter { !it.read }}")
            assertEquals(emptyList(), checks.flatMap { it.findings }, fixture)
        }
    }

    @Test
    fun `a blob the file lacks, a partition figure that differs and a file that is gone are each a finding`() {
        val stats = FixtureCatalog.icebergModel("stats")
        val statsMeta = stats.metadatas.last().metadata
        val statsFile = statsMeta.statistics.single()
        val recordedStats = assertNotNull(statsFile.statisticsPath)
        // The footer read, minus its last blob: the record names a blob the file does not hold.
        val footer = assertNotNull(service.PuffinReader.readFooter(Paths.get(FixtureCatalog.icebergDir("stats").absolutePath, "metadata", recordedStats.substringAfterLast('/'))))
        val short = footer.copy(blobs = footer.blobs.dropLast(1))
        val missingBlob = stats.checkStatisticsFiles(mapOf(recordedStats to StatisticsFileFooter("x", PathResolution.RECORDED, short, null)), emptyMap()).single()
        assertEquals(4, missingBlob.figures, "every blob the record names is compared once the file is read")
        assertEquals(listOf("ndv"), missingBlob.findings.map { it.figure })
        assertEquals("no such blob in the file", missingBlob.findings.single().counted)
        assertEquals(IntegrityCheck.STATISTICS_FILES, missingBlob.findings.single().check)
        // The file gone: the problem carried, no figures, nothing agreed.
        val gone = stats.checkStatisticsFiles(mapOf(recordedStats to StatisticsFileFooter("x", PathResolution.RECORDED, null, "NoSuchFileException: x")), emptyMap()).single()
        assertEquals(false, gone.read)
        assertEquals("NoSuchFileException: x", gone.problem)
        assertEquals(0, gone.figures)

        val pstats = FixtureCatalog.icebergModel("pstats")
        val pFile = pstats.metadatas.last().metadata.partitionStatistics.single()
        val recordedP = assertNotNull(pFile.statisticsPath)
        val real = readPartitionStatistics(Paths.get(FixtureCatalog.icebergDir("pstats").absolutePath, "metadata", recordedP.substringAfterLast('/')), PathResolution.RECORDED)
        val rows = assertNotNull(real.rows)
        // One partition's data file count moved, and the size on disk differs from the record.
        val moved = rows.mapIndexed { i, row -> if (i == 0) row.copy(dataFileCount = (row.dataFileCount ?: 0) + 9) else row }
        val drifted = pstats.checkStatisticsFiles(emptyMap(), mapOf(recordedP to real.copy(rows = moved, sizeOnDisk = (real.sizeOnDisk ?: 0) + 1))).single()
        assertEquals(listOf("file size", "data files"), drifted.findings.map { it.figure })
        assertTrue(drifted.findings[1].where.startsWith(recordedP.substringAfterLast('/') + ", partition "), drifted.findings[1].where)
        assertEquals(IntegrityCheck.PARTITION_STATISTICS, drifted.findings[1].check)
        // A partition dropped from the file is a finding of its own kind.
        val dropped = pstats.checkStatisticsFiles(emptyMap(), mapOf(recordedP to real.copy(rows = rows.drop(1)))).single()
        assertEquals(listOf("partition"), dropped.findings.map { it.figure })
        assertEquals("not listed" to "holds live files", dropped.findings.single().let { it.recorded to it.counted })
        assertNull(dropped.problem)
    }
}
