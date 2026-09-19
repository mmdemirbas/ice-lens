package service

import model.FixtureCatalog
import model.GraphNode
import model.PathResolution
import model.ScanFilterParse
import model.UnifiedTableModel
import model.findMissingFiles
import model.findUnreferencedFiles
import model.metadataVersionFromFileName
import model.parseScanFilter
import model.rowLookupInput
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `example/iceberg/default/wmp`: a table whose metadata is kept apart from its location by
 * `write.metadata.path`, written by Iceberg's `JdbcCatalog` over a SQLite file
 * (`docs/fixtures/wmp.sql`) — the one catalog family that honours the property, since a
 * HadoopCatalog table keeps its metadata under its location whatever the property says. Until
 * this table the layout was only ever built at runtime (`RecordedPathResolutionTest`, a
 * rearrangement of `test`), and the metastore's `00000-<uuid>` version names only ever renamed
 * onto a copy (`MetastoreMetadataNamingTest`).
 *
 * What the engine wrote is the point: `metadata/` under `/wh/default/wmp` with every version,
 * list and manifest in it and no `version-hint.text`; the data under the `location`,
 * `/wh/wmp-data/data/`, which the table directory does not hold at all. The table is opened
 * from the directory holding `metadata/`, its data files are re-rooted beside it the way
 * `extdata`'s are, and the panel says its metadata is kept apart from its location — the
 * `pih` row, on an Iceberg table this time.
 */
class WriteMetadataPathFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val tableDir: Path = Paths.get(File(repoRoot, "example/iceberg/default/wmp").absolutePath)
    private val dataDir: Path = Paths.get(File(repoRoot, "example/iceberg/wmp-data/data").absolutePath)
    private val model = UnifiedTableModel(tableDir)

    @Test
    fun `the engine kept the metadata apart from the location, named the versions the metastore way, and wrote no version hint`() {
        assertEquals(TableFormat.ICEBERG, TableFormatDetector.detect(tableDir))
        assertTrue(!Files.exists(tableDir.resolve("data")), "no data/ under the table")
        assertEquals(2, Files.list(dataDir).filter { it.fileName.toString().endsWith(".parquet") }.count().toInt())
        assertEquals(emptyList(), model.readErrors)
        assertNull(model.versionHint, "a catalog keeps no version hint")
        assertEquals(listOf(0, 1, 2), model.metadatas.map { metadataVersionFromFileName(it.path.fileName.toString()) }, "numbered off the names, from 00000")
        val newest = model.metadatas.last()
        assertEquals("file:/wh/wmp-data", newest.metadata.location, "Spark spells LOCATION as a URI")
        assertEquals("/wh/default/wmp/metadata", newest.metadata.properties["write.metadata.path"])
        assertEquals(2, newest.snapshots.size)
        assertTrue(newest.snapshots.all { it.metadata.manifestList.orEmpty().startsWith("/wh/default/wmp/metadata/snap-") }, newest.snapshots.map { it.metadata.manifestList }.toString())
        assertEquals(listOf(0, 1), newest.metadata.metadataLog.map { metadataVersionFromFileName(it.metadataFile.orEmpty().substringAfterLast('/')) }, "the log names the versions before it")
    }

    /**
     * The recorded `file:/wh/wmp-data/data/…` shares nothing with the table directory below the
     * warehouse, so the sub-path rule cannot place it; the recorded table directory, taken from
     * the manifest list's path, and the local one agree on `default/wmp`, and the file is
     * re-rooted from `/wh` to the local counterpart of `/wh` — where the fixture keeps it.
     */
    @Test
    fun `the data files are found beside the table by re-rooting, and read`() {
        // The current snapshot lists both manifests, the first commit's carried forward.
        val files = model.metadatas.last().snapshots.last().manifests.flatMap { it.dataFiles }
        assertEquals(2, files.size)
        files.forEach { file ->
            assertEquals(PathResolution.REBUILT_BESIDE_TABLE, file.pathResolution, file.metadata.dataFile?.filePath)
            assertEquals(dataDir, file.path.parent, "under the local counterpart of /wh/wmp-data/data")
            assertTrue(Files.isRegularFile(file.path), "on disk: ${file.path}")
        }
        assertTrue(model.metadatas.last().snapshots.all { it.manifests.all { m -> m.pathResolution == PathResolution.FORCED_RELATIVE && m.readErrors.isEmpty() } })

        val input = requireNotNull(model.rowLookupInput())
        val filter = (parseScanFilter("id >= 1") as ScanFilterParse.Parsed).filter
        val rows = RowLookup.lookup(input, filter, emptySet()).hits.associate { (it.cells["id"] as Number).toInt() to it.cells["name"] }
        assertEquals(mapOf(1 to "alpha", 2 to "bravo", 3 to "charlie"), rows, "the script's SELECT")
        assertEquals(3L, LiveRowCount.count(input).live)
    }

    /** The table panel's row for it: the metadata is under /wh/default/wmp and the location is /wh/wmp-data — the `pih` shape, on an Iceberg table. */
    @Test
    fun `the summary says the metadata is kept apart from the location`() {
        val summary = IcebergGraphBuilder.buildGraph(model).nodes.filterIsInstance<GraphNode.TableNode>().single().summary
        assertEquals("/wh/default/wmp", summary.metadataKeptApartAt)
        assertNull(summary.locationIsPaimonTable, "the location holds data, not a Paimon table")
        assertEquals(listOf(0, 1, 2), summary.metadataVersions.map { it.version })
        assertEquals(3L, summary.current.recordCount)
        assertEquals(2, summary.current.dataFileCount)
    }

    /** Nothing under the table root is unreferenced — the root holds the metadata alone — and every file the two snapshots need is where the resolver put it. */
    @Test
    fun `the walk and the stat both come back empty`() {
        assertEquals(emptyList(), findUnreferencedFiles(model).unreferenced)
        assertEquals(emptyList(), model.findMissingFiles().missing)
        assertTrue("wmp" in FixtureCatalog.iceberg, "swept with the corpus")
    }
}
