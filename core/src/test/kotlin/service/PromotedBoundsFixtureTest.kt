package service

import model.GraphNode
import model.IcebergType
import model.ManifestEntryStatus
import model.UnifiedTableModel
import model.columnStatsFor
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `example/iceberg/default/promoted`: `evolved`'s three schemas, then `rewrite_manifests`, from
 * `docs/fixtures/promoted.sql`.
 *
 * `evolved` proves bounds are decoded against the schema the manifest carries. This is the case
 * that proof does not reach: the rewrite writes every live entry into one manifest under the
 * table's current schema and copies each file's bounds verbatim, so the schema-0 file's `id` is
 * four bytes under a schema that says `long`, its `amount` four under `double`, and its `name`
 * bound is keyed by a field id the manifest's schema no longer has. The expected values are the
 * script's: id 1..2, amount 1.5..2.5, name alpha..bravo — what a decoder that required eight bytes
 * reported as a decode failure, and what Iceberg's own reader has always read.
 */
class PromotedBoundsFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val tableDir: Path = Paths.get(File(repoRoot, "example/iceberg/default/promoted").absolutePath)
    private val model = UnifiedTableModel(tableDir)
    private val rewrite = model.metadatas.last().snapshots.maxBy { it.metadata.sequenceNumber ?: 0 }
    private val rewritten = rewrite.manifests.single()

    @Test
    fun `the rewrite put every live file into one manifest under the current schema`() {
        assertEquals("replace", rewrite.metadata.summary["operation"])
        assertEquals("3", rewrite.metadata.summary["manifests-replaced"])
        assertEquals("1", rewrite.metadata.summary["manifests-created"])
        assertEquals(listOf("id" to "long", "amount" to "double", "note" to "string"), rewritten.schema?.struct?.fields?.map { it.name to it.type.typeName })
        assertEquals(3, rewritten.dataFiles.size)
        assertTrue(rewritten.dataFiles.all { it.metadata.status == ManifestEntryStatus.EXISTING })
        assertTrue(model.readErrors.isEmpty() && rewritten.readErrors.isEmpty(), "${model.readErrors + rewritten.readErrors}")
    }

    private val oldFile = rewritten.dataFiles.single { file -> file.metadata.dataFile?.lowerBounds?.any { it.key == 1 && it.value.size == 4 } == true }

    @Test
    fun `a bound written before the promotion keeps its four bytes and is read at that width`() {
        val stats = columnStatsFor(oldFile.metadata.dataFile!!, rewritten.schema)
        val id = stats.single { it.fieldId == 1 }
        assertEquals(IcebergType.LongType, id.type, "the manifest's schema says long")
        assertEquals(4, id.lowerBound?.raw?.size)
        assertEquals(1L to 2L, id.lowerBound?.value to id.upperBound?.value)
        assertEquals(IcebergType.IntType, id.lowerBound?.writtenAs)
        assertNull(id.lowerBound?.error)
        val amount = stats.single { it.fieldId == 3 }
        assertEquals(IcebergType.DoubleType, amount.type)
        assertEquals(1.5 to 2.5, amount.lowerBound?.value to amount.upperBound?.value)
        assertEquals(IcebergType.FloatType, amount.upperBound?.writtenAs)
        // The files written after the promotion carry eight bytes and no note.
        rewritten.dataFiles.filter { it !== oldFile }.forEach { file ->
            val later = columnStatsFor(file.metadata.dataFile!!, rewritten.schema).single { it.fieldId == 1 }
            assertEquals(8, later.lowerBound?.raw?.size)
            assertNull(later.lowerBound?.writtenAs)
        }
    }

    @Test
    fun `a bound for a dropped column is named by the table schema that last had it`() {
        assertNull(rewritten.schema?.nameOf(2), "field 2 is gone from the manifest's schema")
        val bare = columnStatsFor(oldFile.metadata.dataFile!!, rewritten.schema).single { it.fieldId == 2 }
        assertEquals("field 2", bare.displayName)
        assertNull(bare.type)
        assertNull(bare.lowerBound, "without a type the bytes are left undecoded rather than guessed at")

        // The same file is drawn twice: ADDED under the manifest its commit wrote, whose schema-0
        // still names field 2 `name`, and EXISTING under the rewritten one, where it is dropped.
        val graph = GraphLayoutService.layoutGraph(model, showRows = false)
        val nodes = graph.nodes.filterIsInstance<GraphNode.FileNode>().filter { it.data.filePath == oldFile.metadata.dataFile?.filePath }
        assertEquals(setOf(ManifestEntryStatus.ADDED, ManifestEntryStatus.EXISTING), nodes.map { it.entry.status }.toSet())
        val original = nodes.single { it.entry.status == ManifestEntryStatus.ADDED }.columnStats.single { it.fieldId == 2 }
        assertEquals("name" to false, original.columnName to original.dropped, "under its own manifest the field is simply there")
        val node = nodes.single { it.entry.status == ManifestEntryStatus.EXISTING }
        val named = node.columnStats.single { it.fieldId == 2 }
        assertEquals("label", named.columnName, "schema 1 renamed name to label before schema 2 dropped it — the newest definition wins")
        assertTrue(named.dropped)
        assertEquals(IcebergType.StringType, named.type)
        assertEquals("alpha" to "bravo", named.lowerBound?.value to named.upperBound?.value)
        assertTrue(node.columnStats.filter { it.fieldId != 2 }.none { it.dropped })
    }

    /** The rule stands down where the manifest's own schema answers; `evolved` is untouched by it. */
    @Test
    fun `every other fixture's file has no dropped column`() {
        listOf("evolved", "test", "parted").forEach { name ->
            val graph = GraphLayoutService.layoutGraph(UnifiedTableModel(tableDir.resolveSibling(name)), showRows = false)
            val files = graph.nodes.filterIsInstance<GraphNode.FileNode>()
            assertTrue(files.isNotEmpty())
            assertTrue(files.flatMap { it.columnStats }.none { it.dropped }, name)
            assertNotNull(files.first().tableFieldsById[1], "$name: field 1 is defined by some table schema")
        }
    }
}
