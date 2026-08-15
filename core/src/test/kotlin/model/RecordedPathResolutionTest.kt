package model

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Path resolution prefers what the table recorded, and falls back to discarding the recorded
 * directory only when that path is not there.
 *
 * The fallback is what lets this tool open a table copied down from object storage — every path
 * inside points at `s3://…` or some container's `/wh`, and PyIceberg cannot open those at all.
 * It was also unconditional, so a table whose metadata does *not* sit beside its data resolved
 * to a file that was not there and reported it missing.
 *
 * The table built here is that layout: metadata.json in `metadata/`, its manifest list in a
 * sibling directory, referenced by an absolute path that is genuinely valid. Before the change
 * the manifest list resolved into `metadata/`, found nothing, and the snapshot failed to read.
 */
class RecordedPathResolutionTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    /**
     * Copies the minimal fixture into the layout `write.metadata.path` produces: metadata.json
     * stays in `metadata/`, while the manifest list and the manifests it names move to a
     * separate directory, referenced by an absolute path.
     *
     * The manifest list and the manifests move **together**, because manifests are resolved
     * against the list's own directory rather than the table's metadata directory. Moving only
     * the list is not a layout any engine writes, and the manifests would be orphaned.
     */
    private fun tableWithManifestListElsewhere(): Path {
        val source = File(repoRoot, "example/iceberg/default/test").toPath()
        assertTrue(Files.isDirectory(source), "minimal fixture missing at $source")

        val root = Files.createTempDirectory("recorded-path")
        val table = root.resolve("test")
        source.toFile().copyRecursively(table.toFile())

        val metadataDir = table.resolve("metadata")
        val elsewhere = table.resolve("metadata-elsewhere").also(Files::createDirectories)

        val avroFiles = Files.list(metadataDir).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".avro") }.toList()
        }
        avroFiles.forEach { avro -> Files.move(avro, elsewhere.resolve(avro.fileName)) }
        val moved = avroFiles.single { it.fileName.toString().startsWith("snap-") }
            .let { elsewhere.resolve(it.fileName) }

        Files.list(metadataDir).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".metadata.json") }.forEach { json ->
                // Rewrite whatever manifest-list value the fixture recorded to the moved file.
                val rewritten = json.readText().replace(
                    Regex("\"manifest-list\"\\s*:\\s*\"[^\"]*${Regex.escape(moved.fileName.toString())}\""),
                    "\"manifest-list\" : \"${moved.toAbsolutePath()}\"",
                )
                json.writeText(rewritten)
            }
        }
        return table
    }

    @Test
    fun `a manifest list outside the metadata directory is found at its recorded path`() {
        val table = tableWithManifestListElsewhere()
        val model = UnifiedTableModel(table)

        assertEquals(emptyList(), model.readErrors, "table-level read errors")
        val snapshots = model.metadatas.flatMap { it.snapshots }
        assertTrue(snapshots.isNotEmpty(), "the fixture has a snapshot")

        snapshots.forEach { snapshot ->
            assertEquals(
                emptyList(), snapshot.readErrors,
                "the manifest list should have been read from where the table said it is",
            )
            assertEquals(PathResolution.RECORDED, snapshot.pathResolution)
            assertTrue(
                snapshot.path.toString().contains("metadata-elsewhere"),
                "resolved to ${snapshot.path}, which is not where the table pointed",
            )
            assertTrue(snapshot.manifests.isNotEmpty(), "and its manifests should still load")
        }
    }

    /**
     * Both strategies in use at once, which is the point — the choice is per file, not per table.
     * The manifest list is found where the table said it is; the manifest's own recorded path is
     * the container path the fixture was written with, so it falls back — and lands correctly,
     * because the fallback resolves against the list's directory, which is where it moved to.
     */
    @Test
    fun `a manifest whose recorded path is foreign still falls back, beside its list`() {
        val model = UnifiedTableModel(tableWithManifestListElsewhere())
        val manifests = model.metadatas.flatMap { it.snapshots }.flatMap { it.manifests }

        assertTrue(manifests.isNotEmpty())
        manifests.forEach { manifest ->
            assertEquals(PathResolution.FORCED_RELATIVE, manifest.pathResolution)
            assertEquals(emptyList(), manifest.readErrors)
            assertTrue(
                manifest.path.toString().contains("metadata-elsewhere"),
                "the fallback resolves against the manifest list's directory, got ${manifest.path}",
            )
            assertTrue(manifest.dataFiles.isNotEmpty(), "and its entries should decode")
        }
    }

    /**
     * Every checked-in fixture records paths from the machine that wrote it, so all of them keep
     * the old behaviour exactly. This is the safety half: the change can only affect a table
     * whose recorded paths are real here.
     */
    @Test
    fun `the checked-in fixtures all resolve by falling back, as before`() {
        listOf("test", "parted", "mor", "v3", "eqdel", "evolved", "respec", "branched").forEach { fixture ->
            val dir = File(repoRoot, "example/iceberg/default/$fixture")
            val model = UnifiedTableModel(Paths.get(dir.absolutePath))
            val snapshots = model.metadatas.flatMap { it.snapshots }
            assertTrue(snapshots.isNotEmpty(), "$fixture has snapshots")
            snapshots.forEach { snapshot ->
                assertEquals(
                    PathResolution.FORCED_RELATIVE, snapshot.pathResolution,
                    "$fixture should still fall back — its recorded paths are foreign",
                )
            }
        }
    }

    /** A relative recorded path is never trusted: it would resolve against the working directory. */
    @Test
    fun `a relative recorded path is not used even when a matching file exists`() {
        val root = Files.createTempDirectory("relative-path")
        val metadataDir = root.resolve("metadata").also(Files::createDirectories)
        Files.createFile(metadataDir.resolve("snap-1.avro"))

        val (path, resolution) = resolveRecordedOrRelative(metadataDir, "metadata/snap-1.avro")
        assertEquals(PathResolution.FORCED_RELATIVE, resolution)
        assertEquals(metadataDir.resolve("snap-1.avro"), path)
    }
}
