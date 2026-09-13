package model

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Files under the table root that no metadata names, checked in the direction that matters.
 *
 * The easy assertion is that the one known orphan is found. The one that carries the weight is
 * that **seventeen engine-written tables report none**: the referenced set has to cover every kind
 * of file both formats write — manifest lists, manifests, data and delete files, Puffin vectors and
 * statistics, metadata versions, version hints, Paimon's snapshot and schema files, index
 * manifests and index files, changelog files, statistics — or a checked-in table shows a false
 * orphan. A file kind added to either format shows up here first.
 */
class UnreferencedFilesTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val icebergFixtures = listOf("test", "parted", "mor", "eqdel", "v3", "evolved", "respec", "branched", "stats", "branched3", "expired", "maint", "v1", "extdata", "sorted", "promoted", "lineage", "pstats")

    private fun iceberg(name: String) = UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/$name").absolutePath))
    private fun paimon(name: String) = PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/$name").absolutePath))

    @Test
    fun `every engine-written table with no orphan reports none`() {
        val models: List<FormatTableModel> = icebergFixtures.map(::iceberg) + listOf("test", "dv", "pt", "ao", "br", "cs", "fi", "ep", "rt", "sm", "se").map(::paimon)
        assertEquals(29, models.size)
        models.forEach { model ->
            val report = findUnreferencedFiles(model)
            assertTrue(report.problems.isEmpty(), "${model.name}: ${report.problems}")
            assertTrue(report.filesOnDisk > 0, "${model.name}: the walk saw nothing")
            assertEquals(
                emptyList(),
                report.unreferenced.map { it.path.fileName.toString() },
                "${model.name}: ${report.filesOnDisk} files on disk, ${report.referencedOnDisk} referenced",
            )
            assertEquals(report.filesOnDisk, report.referencedOnDisk)
        }
    }

    /**
     * The orphan Paimon's overwrite left behind — the file `PaimonChangelogFixtureTest` finds by
     * listing the bucket by hand, found by the code that will do it for any table.
     */
    @Test
    fun `the changelog file an overwrite wrote and never committed is the one unreferenced file`() {
        val report = findUnreferencedFiles(paimon("cl"))
        assertTrue(report.problems.isEmpty(), "${report.problems}")
        val orphan = report.unreferenced.single()
        assertTrue(orphan.path.fileName.toString().startsWith("changelog-"), "got ${orphan.path}")
        assertEquals("bucket-0", orphan.path.parent.fileName.toString())
        assertEquals(1164L, orphan.sizeBytes, "the size Paimon's own warning printed for the ignored file")
        assertEquals(1164L, report.unreferencedBytes)
        assertEquals(report.filesOnDisk - 1, report.referencedOnDisk)
    }

    /** Hidden files are the filesystem's, not the table's: the `test` fixture carries Hadoop `.crc` sidecars. */
    @Test
    fun `hidden files are neither counted nor reported`() {
        val dir = File(repoRoot, "example/iceberg/default/test")
        val crcs = dir.walk().filter { it.isFile && it.name.startsWith(".") && it.name.endsWith(".crc") }.count()
        assertTrue(crcs > 0, "the fixture should carry .crc sidecars for this to check anything")
        val report = findUnreferencedFiles(iceberg("test"))
        assertTrue(report.unreferenced.isEmpty())
        val visible = dir.walk().filter { it.isFile && !it.name.startsWith(".") }.count()
        assertEquals(visible, report.filesOnDisk)
    }

    /** A stray file dropped into a copy of a clean table is reported, with its size. */
    @Test
    fun `a file the metadata does not name is reported with its size`() {
        val copy = Files.createTempDirectory("unreferenced").toFile()
        try {
            File(repoRoot, "example/iceberg/default/test").copyRecursively(File(copy, "test"))
            val stray = File(copy, "test/data/00000-9-deadbeef-00001.parquet").apply { writeBytes(ByteArray(321)) }
            val report = findUnreferencedFiles(UnifiedTableModel(copy.toPath().resolve("test")))
            assertEquals(listOf(stray.name), report.unreferenced.map { it.path.fileName.toString() })
            assertEquals(321L, report.unreferenced.single().sizeBytes)
        } finally {
            copy.deleteRecursively()
        }
    }
}
