package model

import service.GraphLayoutService
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A catalog table names its metadata `00001-<uuid>.metadata.json` — `BaseMetastoreTableOperations`
 * at 1.8.1, `%05d-<uuid>` from `00000` — and keeps no `version-hint.text`; the corpus is all
 * Hadoop tables, `v<N>.metadata.json`, so this copies the minimal one under the catalog's
 * names. Every version read as unnumbered before, ordered only by `last-updated-ms`, with the
 * card's eyebrow saying nothing where a Hadoop table's says `METADATA 2`.
 */
class MetastoreMetadataNamingTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun `a catalog-named table's versions are numbered from the name, ordered, and drawn without a version hint`() {
        val copy = Files.createTempDirectory("catalog-named").toFile()
        try {
            File(repoRoot, "example/iceberg/default/test").copyRecursively(File(copy, "test"))
            val metadata = File(copy, "test/metadata")
            File(metadata, "version-hint.text").delete()
            val uuids = mutableMapOf<Int, String>()
            metadata.listFiles()!!.filter { it.name.endsWith(".metadata.json") }.forEach { file ->
                val version = requireNotNull(metadataVersionFromFileName(file.name)) - 1  // a catalog counts from 00000
                val uuid = UUID.randomUUID().toString()
                uuids[version] = uuid
                assertTrue(file.renameTo(File(metadata, String.format(java.util.Locale.ROOT, "%05d-%s.metadata.json", version, uuid))))
            }
            val model = UnifiedTableModel(Paths.get(File(copy, "test").absolutePath))
            assertEquals(emptyList(), model.readErrors)
            assertNull(model.versionHint)
            assertEquals(listOf(0, 1), model.metadatas.map { metadataVersionFromFileName(it.path.fileName.toString()) })
            val graph = GraphLayoutService.layoutGraph(model, showRows = false)
            val nodes = graph.nodes.filterIsInstance<GraphNode.MetadataNode>().sortedBy { it.simpleId }
            assertEquals(listOf("00000-${uuids[0]}.metadata.json", "00001-${uuids[1]}.metadata.json"), nodes.map { it.fileName })
            val table = graph.nodes.filterIsInstance<GraphNode.TableNode>().single()
            assertEquals(listOf(0, 1), table.summary.metadataVersions.map { it.version })
        } finally {
            copy.deleteRecursively()
        }
    }
}
