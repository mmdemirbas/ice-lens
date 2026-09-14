package model

import service.StatsCheckReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every live data file's `file_size_in_bytes` and `split_offsets` against the file itself, on
 * every engine-written table, and then the three ways the figures can be wrong, planted by hand.
 *
 * The sweep is what holds the reading of a row group's start: a start read off the data page
 * where a dictionary page precedes it disagrees on the four files in the corpus that open with
 * one, and `rgs` — the one file with more than one row group — is where an offset read from
 * the wrong row group, or in the wrong order, would show. The two files `add_files` registered
 * record no offsets at all: `TableMigrationUtil.buildDataFile` (1.8.1) sets the size and the
 * metrics and nothing else, so a scan splits a migrated file by size alone.
 */
class FileLayoutCheckFixtureTest {

    private fun icebergTargets(name: String) = FixtureCatalog.icebergModel(name).fileStatsTargets()
        .filter { it.localPath.endsWith(".parquet") || it.localPath.endsWith(".avro") }

    @Test
    fun `every live Iceberg file's size and split offsets are the file's own`() {
        var parquet = 0; var avro = 0
        val severalGroups = mutableListOf<String>()
        val noOffsets = mutableListOf<String>()
        for (fixture in FixtureCatalog.iceberg) {
            for (target in icebergTargets(fixture)) {
                val layout = assertNotNull(StatsCheckReader.check(target).layout, "$fixture/${target.name}")
                assertEquals(true, layout.sizeAgrees, "$fixture/${target.name}: size ${layout.recordedSize} recorded, ${layout.sizeOnDisk} on disk")
                if (target.localPath.endsWith(".avro")) {
                    avro++
                    // Iceberg's Avro writer records no offsets (1.8.1), and DuckDB lists no blocks: nothing on either side.
                    assertNull(layout.rowGroups, "$fixture/${target.name}")
                    assertNull(layout.recordedSplitOffsets, "$fixture/${target.name}")
                    assertNull(layout.offsetsAgree, "$fixture/${target.name}")
                    assertEquals(1, layout.compared)
                } else {
                    parquet++
                    val groups = assertNotNull(layout.rowGroups, "$fixture/${target.name}")
                    if (layout.recordedSplitOffsets == null) {
                        noOffsets += "$fixture/${target.name}"
                        assertNull(layout.offsetsAgree)
                        assertEquals(1, layout.compared)
                    } else {
                        assertEquals(true, layout.offsetsAgree, "$fixture/${target.name}: ${layout.recordedSplitOffsets} recorded, the footer's groups start at ${groups.map { it.start }}")
                        assertEquals(true, layout.offsetsUsable, "$fixture/${target.name}")
                        assertEquals(2, layout.compared)
                    }
                    assertTrue(layout.problems.isEmpty())
                    if (groups.size > 1) severalGroups += "$fixture/${target.name}"
                }
            }
        }
        assertTrue(parquet >= 100 && avro >= 2, "$parquet Parquet files, $avro Avro files")
        assertEquals(1, severalGroups.size, "files of several row groups: $severalGroups")
        assertTrue(severalGroups.single().startsWith("rgs/"), severalGroups.single())
        // Only the plain files `add_files` registered record none — the procedure's builder sets no offsets.
        assertEquals(listOf("migdeep", "migrated"), noOffsets.map { it.substringBefore('/') }.sorted(), "$noOffsets")
        assertTrue(noOffsets.all { it.substringAfter('/').startsWith("part-") }, "$noOffsets")
    }

    @Test
    fun `rgs holds thirteen row groups, and the entry records where each starts`() {
        val big = icebergTargets("rgs").single { it.recordedRows == 5000L }
        val layout = assertNotNull(StatsCheckReader.check(big).layout)
        val groups = assertNotNull(layout.rowGroups)
        // What Spark printed from `rgs.files` when the fixture was written — the writer's own record.
        assertEquals(listOf(4L, 1447L, 2622L, 3762L, 4886L, 6005L, 7117L, 8428L, 9695L, 10961L, 12225L, 13494L, 14758L), layout.recordedSplitOffsets)
        assertEquals(layout.recordedSplitOffsets, groups.map { it.start })
        assertEquals(5000L, groups.sumOf { it.rows })
        assertEquals(21864L, layout.recordedSize)
        assertEquals(layout.recordedSize, layout.sizeOnDisk)
        // The one-row file beside it: one row group, at the first column chunk after the magic.
        val small = icebergTargets("rgs").single { it.recordedRows == 1L }
        assertEquals(listOf(RowGroup(4L, 1L)), StatsCheckReader.check(small).layout?.rowGroups)
    }

    @Test
    fun `a size off by one, an offset moved by one and a list past the file size are each named`() {
        val big = icebergTargets("rgs").single { it.recordedRows == 5000L }
        val size = big.recordedSize!!
        val offsets = big.recordedSplitOffsets!!

        val sizeOff = assertNotNull(StatsCheckReader.check(big.copy(recordedSize = size + 1)).layout)
        assertEquals(listOf(StatsProblem("file size", "${size + 1}", "$size")), sizeOff.problems)
        assertEquals(true, sizeOff.offsetsUsable)   // the last offset is still below the recorded size

        val moved = offsets.toMutableList().also { it[1] = it[1] + 1 }
        val offsetOff = assertNotNull(StatsCheckReader.check(big.copy(recordedSplitOffsets = moved)).layout)
        assertEquals(listOf(StatsProblem("split offsets", moved.joinToString(", "), offsets.joinToString(", "))), offsetOff.problems)
        assertEquals(true, offsetOff.sizeAgrees)

        // Iceberg drops a list whose last offset is not below the file size (`BaseFile.hasWellDefinedOffsets`, #8925).
        val pastEnd = assertNotNull(StatsCheckReader.check(big.copy(recordedSize = offsets.last())).layout)
        assertEquals(false, pastEnd.offsetsUsable)
        assertEquals(true, pastEnd.offsetsAgree)
        assertEquals(listOf(StatsProblem("file size", "${offsets.last()}", "$size")), pastEnd.problems)

        // The sweep lists both figures under the file, beside the column findings, and counts them among the figures.
        val sweep = sweepFileStats(listOf(big.copy(recordedSize = size + 1, recordedSplitOffsets = moved))) { StatsCheckReader.check(it) }
        assertEquals(
            listOf(
                IntegrityFinding(IntegrityCheck.FILE_STATISTICS, big.name, "file size", "${size + 1}", "$size"),
                IntegrityFinding(IntegrityCheck.FILE_STATISTICS, big.name, "split offsets", moved.joinToString(", "), offsets.joinToString(", ")),
            ),
            sweep.findings,
        )
        assertEquals(StatsCheckReader.check(big).figures, sweep.figures)
    }

    @Test
    fun `every live Paimon file's size is the file's, and no offsets are recorded`() {
        var files = 0
        for (fixture in FixtureCatalog.paimon) {
            for (target in FixtureCatalog.paimonModel(fixture).fileStatsTargets().filter { it.localPath.endsWith(".parquet") }) {
                files++
                val layout = assertNotNull(StatsCheckReader.check(target).layout, "$fixture/${target.name}")
                assertEquals(true, layout.sizeAgrees, "$fixture/${target.name}: ${layout.recordedSize} recorded, ${layout.sizeOnDisk} on disk")
                assertNull(layout.recordedSplitOffsets)
                assertNotNull(layout.rowGroups, "$fixture/${target.name}").also { assertEquals(target.recordedRows, it.sumOf { g -> g.rows }) }
                assertEquals(1, layout.compared)
            }
        }
        assertTrue(files >= 60, "$files files")
    }
}
