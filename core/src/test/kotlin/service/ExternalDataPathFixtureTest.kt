package service

import model.GraphNode
import model.PathResolution
import model.UnifiedTableModel
import model.rebuildBesideTable
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
 * `example/iceberg/default/extdata`: an Iceberg table written with `write.data.path` pointing
 * outside its own directory, from `docs/fixtures/extdata.sql`.
 *
 * The two other path layouts the resolver handles are built at runtime by rearranging the minimal
 * fixture; this one is engine-written, and what the engine wrote is the point: the table directory
 * holds `metadata/` and no `data/` at all, the two data files sit directly under
 * `/wh/extdata-files/` with no partition path, and every `file_path` in the manifests is absolute
 * and shares nothing with `/wh/default/extdata` below the warehouse. The expected values are read
 * off the script: two commits, one file each, ids 1–2 and 3.
 */
class ExternalDataPathFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val tableDir: Path = Paths.get(File(repoRoot, "example/iceberg/default/extdata").absolutePath)
    private val dataDir: Path = Paths.get(File(repoRoot, "example/iceberg/extdata-files").absolutePath)
    private val model = UnifiedTableModel(tableDir)

    @Test
    fun `the engine put the data outside the table, and recorded it so`() {
        assertTrue(!Files.exists(tableDir.resolve("data")), "no data/ under the table")
        assertEquals(2, Files.list(dataDir).filter { it.fileName.toString().endsWith(".parquet") }.count().toInt())
        val current = model.metadatas.last()
        assertEquals("/wh/extdata-files", current.metadata.properties["write.data.path"])
        assertEquals("/wh/default/extdata", current.metadata.location)
        val recorded = current.snapshots.last().manifests.flatMap { it.dataFiles }.map { it.metadata.dataFile?.filePath }
        assertEquals(2, recorded.size)
        assertTrue(recorded.all { it!!.startsWith("/wh/extdata-files/") }, "$recorded")
        assertTrue(model.readErrors.isEmpty(), "${model.readErrors}")
    }

    /**
     * Neither of the two older rules can find these files: the recorded path is not on this
     * machine, and the sub-path rule has no sub-path under the table to work with. The third
     * re-roots the recorded path from `/wh` to the local counterpart of `/wh`, which is where
     * the fixture keeps them.
     */
    @Test
    fun `a write-data-path file is found beside the table and says how`() {
        val files = model.metadatas.last().snapshots.last().manifests.flatMap { it.dataFiles }
        assertEquals(2, files.size)
        files.forEach { file ->
            assertEquals(PathResolution.REBUILT_BESIDE_TABLE, file.pathResolution, file.metadata.dataFile?.filePath)
            assertEquals(dataDir, file.path.parent, "under the local counterpart of /wh/extdata-files")
            assertTrue(Files.isRegularFile(file.path), "on disk: ${file.path}")
        }
        assertTrue(
            model.metadatas.flatMap { it.snapshots }.flatMap { it.manifests }.all { it.readErrors.isEmpty() },
            "no traversal or read error: ${model.metadatas.flatMap { it.snapshots }.flatMap { it.manifests }.flatMap { it.readErrors }}",
        )
        // The manifests themselves are under the table, and resolve by the older rule as before.
        assertTrue(model.metadatas.last().snapshots.last().manifests.all { it.pathResolution == PathResolution.FORCED_RELATIVE })
        assertEquals(3L, files.sumOf { it.metadata.dataFile?.recordCount ?: 0L }, "ids 1, 2 and 3")
    }

    /** The graph carries the resolution to the panel, and sample rows can be read from the file it found. */
    @Test
    fun `the file node reports the rule that found it`() {
        val graph = GraphLayoutService.layoutGraph(model, showRows = true)
        val fileNodes = graph.nodes.filterIsInstance<GraphNode.FileNode>()
        assertEquals(2, fileNodes.size)
        assertTrue(fileNodes.all { it.pathResolution == PathResolution.REBUILT_BESIDE_TABLE })
        assertTrue(fileNodes.all { Files.isRegularFile(Paths.get(it.localPath!!)) })
        assertTrue(graph.nodes.filterIsInstance<GraphNode.RowNode>().isNotEmpty(), "rows were read from the re-rooted files")
    }

    /**
     * The rule stands down rather than guessing. Each null is a case where the recorded path could
     * land anywhere, and a plausible wrong path is what turns a missing file into a wrong file.
     */
    @Test
    fun `re-rooting needs a shared table suffix, a recorded warehouse, and a path under it`() {
        val local = createTempDirectory("extdata").resolve("iceberg/default/extdata")
        Files.createDirectories(local)
        val warehouse = local.parent.parent
        try {
            assertEquals(
                warehouse.resolve("extdata-files/x.parquet"),
                rebuildBesideTable("/wh/extdata-files/x.parquet", "/wh/default/extdata", local),
            )
            assertEquals(
                warehouse.resolve("extdata-files/x.parquet"),
                rebuildBesideTable("s3://bucket/wh/extdata-files/x.parquet", "s3://bucket/wh/default/extdata", local),
                "a scheme and bucket above the warehouse re-root the same way",
            )
            assertNull(rebuildBesideTable("/wh/extdata-files/x.parquet", "/wh/other/table", local), "no trailing segment shared")
            assertNull(rebuildBesideTable("/elsewhere/x.parquet", "/wh/default/extdata", local), "not under the recorded warehouse")
            assertNull(rebuildBesideTable("/wh/../../etc/passwd", "/wh/default/extdata", local), "escapes the local warehouse")
            assertNull(rebuildBesideTable("default/extdata/x.parquet", "default/extdata", local), "no warehouse above the shared segments")
        } finally {
            warehouse.parent.toFile().deleteRecursively()
        }
    }
}
