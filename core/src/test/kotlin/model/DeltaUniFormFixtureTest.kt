package model

import org.junit.jupiter.api.io.TempDir
import service.RowLookup
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * UniForm on `duni`, written by delta-spark 3.2.1 with delta-iceberg: the Iceberg metadata under
 * `metadata/` held to the log it was converted from. The oracle is the script's own rows, which
 * the Delta read and the Iceberg read of the export must both return, and every metadata version's
 * `delta-version`, whose Delta state must list exactly the files that version's snapshot does.
 */
class DeltaUniFormFixtureTest {

    private fun filter(text: String) = (parseScanFilter(text) as ScanFilterParse.Parsed).filter

    private val scriptRows: Set<Int> = File(FixtureCatalog.repoRoot, "docs/fixtures/delta/duni.out").readLines()
        .filter { it.startsWith("rows\t") }.map { it.split('\t')[1].toInt() }.toSet()

    @Test
    fun `the export is the latest version, the same files, and both reads return the script's rows`() {
        val model = FixtureCatalog.deltaModel("duni")
        val check = assertNotNull(model.uniFormCheck())
        assertTrue(check.enabled && check.exportPresent)
        assertEquals(4L, check.exportedVersion)
        assertFalse(check.behind)
        assertTrue(check.filesAgree, check.describe())
        assertEquals(3, check.icebergFiles.size)

        val deltaIds = RowLookup.lookup(model.readInputAt(4)!!, filter("id >= 1"), emptySet()).hits
            .filter { it.fate == RowFate.LIVE }.map { (it.cells["id"] as Number).toInt() }.toSet()
        val export = assertNotNull(model.icebergExport)
        val icebergIds = RowLookup.lookup(export.rowLookupInput()!!, filter("id >= 1"), emptySet()).hits
            .filter { it.fate == RowFate.LIVE }.map { (it.cells["id"] as Number).toInt() }.toSet()
        assertEquals(setOf(1, 3, 4), scriptRows)
        assertEquals(scriptRows, deltaIds)
        assertEquals(scriptRows, icebergIds, "the Iceberg read of the column-mapped files, placed by field id")
    }

    @Test
    fun `every metadata version lists the files of the Delta version it records`() {
        val model = FixtureCatalog.deltaModel("duni")
        val export = model.icebergExport!!
        val checked = export.metadatas.mapNotNull { version ->
            val deltaVersion = version.metadata.properties["delta-version"]?.toLongOrNull() ?: return@mapNotNull null
            val snapshot = version.snapshots.firstOrNull { it.metadata.snapshotId == version.metadata.currentSnapshotId } ?: return@mapNotNull null
            val iceberg = snapshot.manifests.flatMap { it.dataFiles }.filter { it.metadata.status != ManifestEntryStatus.DELETED }.map { it.path.toAbsolutePath().normalize() }.toSet()
            val delta = model.stateAt(deltaVersion).getOrThrow().files.values.map { model.resolve(it.path).toAbsolutePath().normalize() }.toSet()
            assertEquals(delta, iceberg, "${version.path.name} at delta-version $deltaVersion")
            deltaVersion
        }
        assertEquals(listOf(1L, 2L, 3L, 4L), checked, "one converted version per commit that wrote files")
    }

    private fun copyOfDuni(dir: Path): Path {
        val target = dir.resolve("duni")
        FixtureCatalog.deltaDir("duni").toPath().let { src ->
            Files.walk(src).use { s -> s.forEach { p -> val t = target.resolve(src.relativize(p).toString()); if (Files.isDirectory(p)) Files.createDirectories(t) else Files.copy(p, t) } }
        }
        return target
    }

    @Test
    fun `an export a version behind is said to be, and one naming another version's files differs`(@TempDir dir: Path) {
        val table = copyOfDuni(dir)
        val newest = Files.list(table.resolve("metadata")).use { s -> s.filter { it.name.endsWith(".metadata.json") }.toList() }.maxBy { it.name }
        // The conversion of version 4 never landed: the metadata before it records 3.
        Files.delete(newest)
        val behind = DeltaUnifiedTableModel(table).uniFormCheck()!!
        assertEquals(3L, behind.exportedVersion)
        assertTrue(behind.behind && behind.filesAgree, behind.describe())

        // A delta-version the snapshot was not converted from: version 2 lists one file fewer.
        val second = copyOfDuni(dir.resolve("b"))
        val last = Files.list(second.resolve("metadata")).use { s -> s.filter { it.name.endsWith(".metadata.json") }.toList() }.maxBy { it.name }
        Files.writeString(last, Files.readString(last).replace("\"delta-version\" : \"4\"", "\"delta-version\" : \"2\""))
        val differs = DeltaUnifiedTableModel(second).uniFormCheck()!!
        assertFalse(differs.filesAgree)
        assertTrue("DIFFER" in differs.describe(), differs.describe())
        val report = DeltaUnifiedTableModel(second).integrityReport()
        assertEquals(listOf(IntegrityCheck.UNIFORM), report.findings.map { it.check })
    }
}
