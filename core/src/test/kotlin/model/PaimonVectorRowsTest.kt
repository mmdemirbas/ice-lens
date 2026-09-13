package model

import service.GraphLayoutService
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A Paimon row under a deletion vector is marked the way an Iceberg row under a Puffin vector
 * is: the file node carries the vector the latest index manifest names for it, decoded on
 * first use, and the sampled rows are resolved against its positions. `dv` deleted k IN (2,
 * 1001, 1500) — the second row of the 1..1000 file and the first of the 1001..1500 file are
 * within the five rows drawn; `ad` deleted id IN (2, 6), the second row of one file and the
 * first of the other.
 */
class PaimonVectorRowsTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun graph(name: String) =
        GraphLayoutService.layoutGraph(PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/$name").absolutePath)), showRows = true)

    @Test
    fun `the file node carries the vector its index manifest names, and no other file does`() {
        val files = graph("dv").nodes.filterIsInstance<GraphNode.PaimonDataFileNode>()
        val withVector = files.filter { it.vectorRange != null }.distinctBy { it.entry.file?.fileName }
        assertEquals(setOf(1000L, 500L), withVector.map { it.entry.file?.rowCount }.toSet(), "the two files the DELETE touched")
        withVector.forEach { node ->
            val range = assertNotNull(node.vectorRange)
            val vector = assertNotNull(node.deletionVector.value, "the vector in ${range.indexFileName} decodes")
            assertEquals(range.cardinality, vector.cardinality)
            assertTrue(vector.checksumMatches)
        }
        assertTrue(files.filter { it.vectorRange == null }.all { !it.deletionVector.isPresent })
    }

    @Test
    fun `the rows a vector marks are drawn deleted, and every other row is resolved live`() {
        val rows = graph("dv").nodes.filterIsInstance<GraphNode.RowNode>().filter { "k" in it.resolvedData }
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.vectorsResolved }, "every Paimon row is resolved against its file's vector now")
        val deleted = rows.filter { it.isDeletedByVector }.map { it.resolvedData["k"] }.toSet()
        // k = 1500 is the last row of a 500-row file, past the five rows drawn.
        assertEquals(setOf<Any>(2, 1001), deleted)
        // The -D rows are retractions in a file no vector covers.
        assertTrue(rows.filter { it.isRetraction }.none { it.isDeletedByVector })
    }

    @Test
    fun `an append table's rows are marked the same way`() {
        val rows = graph("ad").nodes.filterIsInstance<GraphNode.RowNode>().filter { "id" in it.resolvedData }
        assertEquals(7, rows.size)
        assertEquals(setOf<Any>(2, 6), rows.filter { it.isDeletedByVector }.map { it.resolvedData["id"] }.toSet())
        assertTrue(rows.all { it.vectorsResolved })
    }

    /** A table with no vectors: its rows are resolved, and resolved live. */
    @Test
    fun `a table with no index manifest resolves every row live`() {
        // Every manifest entry's file draws rows, the compacted-away ones under their older snapshots included.
        val rows = graph("pc").nodes.filterIsInstance<GraphNode.RowNode>().filter { "k" in it.resolvedData }
        assertTrue(rows.size >= 7, "${rows.size}")
        assertTrue(rows.all { it.vectorsResolved && !it.isDeletedByVector })
    }
}
