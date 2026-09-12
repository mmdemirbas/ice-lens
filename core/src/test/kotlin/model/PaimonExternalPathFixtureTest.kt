package model

import service.GraphLayoutService
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `example/paimon/db.db/ep`: a table whose data files were written to `data-file.external-paths`,
 * outside the table directory, from `docs/fixtures/paimon-ep.sql`.
 *
 * The engine wrote `/wh/ep-files/bucket-0/<file>` — the layout below the table, re-rooted at the
 * external path — and recorded it in each entry's `_EXTERNAL_PATH` as `file:/wh/ep-files/…`; the
 * table directory holds no bucket at all. A resolver that builds the path from the table root
 * reports both files missing. The expected values are read off the script and the listing it
 * produced: two commits, one file each, three rows in all.
 */
class PaimonExternalPathFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val tableDir: Path = Paths.get(File(repoRoot, "example/paimon/db.db/ep").absolutePath)
    private val dataDir: Path = Paths.get(File(repoRoot, "example/paimon/ep-files").absolutePath)
    private val model = PaimonUnifiedTableModel(tableDir)

    private fun files() = model.snapshots.last().let { it.baseManifests + it.deltaManifests }.flatMap { it.entries }

    @Test
    fun `the engine put the data outside the table, and recorded it so`() {
        assertTrue(!Files.exists(tableDir.resolve("bucket-0")), "no bucket under the table")
        assertEquals("file:///wh/ep-files", model.schemas.single().options["data-file.external-paths"])
        val recorded = files().map { it.metadata.file?.externalPath }
        assertEquals(2, recorded.size)
        assertTrue(recorded.all { it!!.startsWith("file:/wh/ep-files/bucket-0/") }, "$recorded")
        assertTrue(model.readErrors.isEmpty(), "${model.readErrors}")
    }

    /** Not present at the recorded path, so found by its tail under the local warehouse — and said so. */
    @Test
    fun `an external file is found under the local warehouse and says how`() {
        files().forEach { file ->
            assertEquals(PaimonPathResolution.EXTERNAL_REROOTED, file.pathResolution, file.metadata.file?.externalPath)
            assertEquals(dataDir.resolve("bucket-0"), file.path.parent)
            assertTrue(Files.isRegularFile(file.path), "on disk: ${file.path}")
        }
        assertTrue(model.snapshots.flatMap { it.readErrors }.isEmpty(), "no traversal error for a path the table recorded")
        assertTrue(model.snapshots.flatMap { it.baseManifests + it.deltaManifests }.flatMap { it.readErrors }.isEmpty())
        assertEquals(3L, files().sumOf { it.metadata.file?.rowCount ?: 0L })

        val graph = GraphLayoutService.layoutGraph(model, showRows = true)
        val nodes = graph.nodes.filterIsInstance<GraphNode.PaimonDataFileNode>()
        assertEquals(2, nodes.size)
        assertTrue(nodes.all { it.pathResolution == PaimonPathResolution.EXTERNAL_REROOTED })
        assertTrue(graph.nodes.filterIsInstance<GraphNode.RowNode>().isNotEmpty(), "rows were read from the re-rooted files")
    }

    /** Every table in the table's own layout resolves as it always did, and says nothing about it. */
    @Test
    fun `a file in the table's own layout is resolved by the layout`() {
        val pt = PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/pt").absolutePath))
        val entries = pt.snapshots.last().let { it.baseManifests + it.deltaManifests }.flatMap { it.entries }
        assertTrue(entries.isNotEmpty())
        assertTrue(entries.all { it.pathResolution == PaimonPathResolution.LAYOUT && it.metadata.file?.externalPath == null })
    }

    /**
     * The re-rooting is a search by tail, longest first, under the local warehouse — the table's
     * grandparent when its parent is a `.db` directory — and stands down rather than guessing.
     */
    @Test
    fun `re-rooting looks for the recorded path's tails under the warehouse and stands down otherwise`() {
        val warehouse = createTempDirectory("ep")
        val table = warehouse.resolve("db.db/ep")
        Files.createDirectories(table)
        try {
            assertNull(rerootExternalPath("file:/wh/ep-files/bucket-0/x.parquet", table), "nothing on disk")
            val file = warehouse.resolve("ep-files/bucket-0/x.parquet")
            Files.createDirectories(file.parent)
            Files.writeString(file, "x")
            assertEquals(file, rerootExternalPath("file:/wh/ep-files/bucket-0/x.parquet", table))
            assertEquals(file, rerootExternalPath("s3://bucket/wh/ep-files/bucket-0/x.parquet", table), "a scheme and bucket above are dropped like any other segments")
            assertNull(rerootExternalPath("/wh/ep-files/bucket-0/../../../x.parquet", table), "a tail that escapes the warehouse is skipped")
            assertNull(rerootExternalPath("x.parquet", table), "a bare name is not a path to re-root")
            // Without a .db parent the warehouse is the table's parent, so the same file under
            // `<parent>/ep-files` is what a flat layout finds.
            val flat = warehouse.resolve("flat")
            Files.createDirectories(flat)
            assertNull(rerootExternalPath("file:/wh/ep-files/bucket-0/x.parquet", flat.resolve("t")), "the flat table's warehouse is `flat`, which holds no ep-files")
        } finally {
            warehouse.toFile().deleteRecursively()
        }
    }

    /** The unreferenced-files walk covers the table directory; the external files are named and outside it. */
    @Test
    fun `nothing under the table is unreferenced, and the external files are referenced`() {
        val report = findUnreferencedFiles(model)
        assertTrue(report.problems.isEmpty(), "${report.problems}")
        assertEquals(emptyList(), report.unreferenced.map { report.relativePathOf(it) })
        assertTrue(referencedFiles(model).count { it.startsWith(dataDir) } == 2)
    }
}
