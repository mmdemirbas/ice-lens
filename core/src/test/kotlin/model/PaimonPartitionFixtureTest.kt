package model

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A partitioned Paimon table, which every other Paimon fixture is not.
 *
 * A manifest entry names its file by `_FILE_NAME` and its partition by `_PARTITION`, a serialised
 * `BinaryRow` this tool did not decode — so every data file of a partitioned table resolved to
 * `<table>/<file>`, a path that does not exist, and the table read as one where every file was
 * missing and every file on disk was an orphan. The oracle for the decoder is the directory
 * layout Paimon wrote: the partition read out of each entry has to be the directory its file is
 * in, and the file has to be there.
 *
 * The expected values come from `docs/fixtures/paimon-pt.sql`: two dates, two regions (one
 * string short enough to be stored inline, one long enough for the variable-length tail), four
 * partitions, five files.
 */
class PaimonPartitionFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val tableDir = File(repoRoot, "example/paimon/db.db/pt")
    private val model = PaimonUnifiedTableModel(Paths.get(tableDir.absolutePath))

    private val latest = model.snapshots.last()
    private val entries = (latest.baseManifests + latest.deltaManifests).flatMap { it.entries }

    @Test
    fun `the table reads with no errors and every entry's partition decodes`() {
        assertEquals(emptyList(), model.readErrors)
        assertEquals(emptyList(), model.snapshots.flatMap { it.readErrors })
        assertEquals(listOf("dt", "region"), model.schemas.single().partitionKeys)
        assertEquals(5, entries.size, "four partitions, one of them written twice")
        entries.forEach { entry ->
            assertNotNull(entry.partition, "${entry.metadata.file?.fileName}")
            assertEquals(listOf("dt", "region"), entry.partition.values.map { it.name })
        }
    }

    /**
     * The whole point: the decoded partition *is* the directory, for both string encodings.
     *
     * `eu` (2 bytes) is stored inline in its 8-byte slot with the length in the last byte;
     * `north-america` (13 bytes) is in the row's variable-length tail behind an offset. A decoder
     * that read one shape and not the other would place half the files in a directory that is
     * not there.
     */
    @Test
    fun `every file resolves to the directory its decoded partition names, and is there`() {
        entries.forEach { entry ->
            val partition = entry.partition!!
            val relative = tableDir.toPath().relativize(entry.path).toString()
            assertEquals(
                "${partition.path}/bucket-${entry.metadata.bucket}/${entry.metadata.file?.fileName}",
                relative,
                "the resolved path is built from the decoded partition",
            )
            assertTrue(Files.isRegularFile(entry.path), "the file is where the partition says: ${entry.path}")
        }
        val regions = entries.map { it.partition!!.values[1].value }.toSet()
        assertEquals(setOf("eu", "north-america"), regions, "both string encodings are exercised")
    }

    /** A DATE is stored as its epoch day and written into the path as that number under the default legacy naming. */
    @Test
    fun `a date partition decodes to the date and paths as its epoch day`() {
        val byFile = entries.associateBy { it.metadata.file?.fileName }
        val dates = entries.map { it.partition!!.values[0] }
        assertEquals(setOf(LocalDate.of(2024, 3, 5), LocalDate.of(2024, 3, 6)), dates.map { it.value }.toSet())
        assertEquals(setOf("19787", "19788"), dates.map { it.pathText }.toSet())
        assertTrue(dates.all { it.type.startsWith("DATE") }, dates.map { it.type }.toString())
        // The directory on disk says the same, which is the whole claim.
        byFile.values.forEach { entry ->
            val dir = entry.path.parent.parent.parent.fileName.toString()
            assertEquals("dt=${entry.partition!!.values[0].pathText}", dir)
        }
    }

    /** Row counts per partition, from the script: 2 + 1 + (1 + 2) + 2. */
    @Test
    fun `the rows land in the partitions the script wrote them to`() {
        val rowsByPartition = entries.groupBy { it.partition!!.display }
            .mapValues { (_, files) -> files.sumOf { it.metadata.file?.rowCount ?: 0L } }
        assertEquals(
            mapOf(
                "dt=2024-03-05, region=eu" to 2L,
                "dt=2024-03-05, region=north-america" to 1L,
                "dt=2024-03-06, region=eu" to 3L,
                "dt=2024-03-06, region=north-america" to 2L,
            ),
            rowsByPartition,
        )
        assertEquals(2, entries.count { it.partition!!.display == "dt=2024-03-06, region=eu" }, "written twice, two files")
    }

    /** With the paths right, nothing on disk is an orphan — which was every data file before. */
    @Test
    fun `no file of a partitioned table is unreferenced`() {
        val report = findUnreferencedFiles(model)
        assertEquals(emptyList(), report.problems)
        assertEquals(emptyList(), report.unreferenced.map { it.path.toString() })
        assertEquals(report.filesOnDisk, report.referencedOnDisk)
        assertTrue(report.filesOnDisk >= 15, "five data files and the metadata: ${report.filesOnDisk}")
    }

    /** The unpartitioned fixtures decode to an empty partition, not to a failure. */
    @Test
    fun `an unpartitioned table's entries decode to no partition values`() {
        val dv = PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/dv").absolutePath))
        val dvEntries = dv.snapshots.last().let { it.baseManifests + it.deltaManifests }.flatMap { it.entries }
        assertTrue(dvEntries.isNotEmpty())
        dvEntries.forEach { entry ->
            assertEquals(DecodedPaimonPartition(emptyList()), entry.partition, "${entry.metadata.file?.fileName}")
            assertTrue(Files.isRegularFile(entry.path), "${entry.path}")
        }
    }
}
