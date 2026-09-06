package model

import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Table statistics, read from a real Spark-written Puffin record.
 *
 * **The oracle is arithmetic done before the fixture existed.** `docs/fixtures/stats.sql` inserts
 * six rows whose distinct counts can be read off the statements — id 6, name 5 because `alpha`
 * appears twice, region 3, amount 6 — and those four numbers were written into the script's
 * comment before it was ever run. Spark's own theta sketches agree with all four, so this asserts
 * what the table actually contains rather than what this code happened to decode.
 */
class TableStatisticsTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun metadataOf(table: String): TableMetadata =
        UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/$table").absolutePath))
            .metadatas.last().metadata

    private val metadata = metadataOf("stats")

    @Test
    fun `the statistics file is read as a record rather than as raw JSON`() {
        val file = metadata.statistics.single()
        assertEquals(6542900942027300342L, file.snapshotId)
        assertTrue(file.statisticsPath.orEmpty().endsWith(".stats"), "got ${file.statisticsPath}")
        assertEquals(1161L, file.fileSizeInBytes)
        assertEquals(881L, file.fileFooterSizeInBytes)
        assertEquals(4, file.blobMetadata.size, "one blob per column")
    }

    @Test
    fun `every blob's distinct count is the one the rows imply`() {
        val byColumn = statisticsRows(metadata).associate { it.column to it.ndv }
        assertEquals(
            mapOf("id" to 6L, "name" to 5L, "region" to 3L, "amount" to 6L),
            byColumn,
            "these four were predicted from the INSERTs before the fixture was generated",
        )
    }

    @Test
    fun `a blob carries the sketch it is and the commit it describes`() {
        val row = statisticsRows(metadata).first { it.column == "name" }
        assertEquals("apache-datasketches-theta-v1", row.type)
        assertEquals(6542900942027300342L, row.snapshotId)
        assertEquals(2L, row.sequenceNumber)
        assertTrue(row.fileName.endsWith(".stats"), "got ${row.fileName}")
    }

    /**
     * The blob names field *ids*, and the schema that names them is the snapshot's own.
     *
     * Asserted by dropping the snapshot's schema out of reach: with no schema able to name field 2,
     * the row must show the id rather than a name borrowed from somewhere else.
     */
    @Test
    fun `a field nothing names is shown as its id, not as a guess`() {
        val withoutSchemas = metadata.copy(schemas = emptyList(), currentSchemaId = null)
        assertEquals(
            listOf("field 1", "field 2", "field 3", "field 4"),
            statisticsRows(withoutSchemas).map { it.column },
        )
    }

    @Test
    fun `partition statistics are empty here, and that is an answer rather than a gap`() {
        // The Iceberg in the fixture image has no procedure that writes one. See docs/fixtures/stats.sql.
        assertEquals(emptyList(), metadata.partitionStatistics)
    }

    /** Every other fixture has no statistics at all, and must still read. */
    @Test
    fun `a table with no statistics reports none`() {
        assertEquals(emptyList(), statisticsRows(metadataOf("test")))
    }

    /**
     * The record in `metadata.json` against the Puffin file it names.
     *
     * `metadata.json` carries a *copy* of the blob metadata so a planner never has to open the
     * file, which is exactly what lets the two drift — a statistics file removed by an
     * orphan-file cleanup leaves its record behind and nothing on the read path notices. This is
     * `manifestTallies` one level up: the claim beside the thing claimed about.
     */
    @Test
    fun `the recorded blobs agree with the file's own footer`() {
        val graph = service.GraphLayoutService.layoutGraph(
            UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/stats").absolutePath)),
            showRows = false,
        )
        val node = graph.nodes.filterIsInstance<GraphNode.MetadataNode>()
            .first { it.data.statistics.isNotEmpty() }
        val footers = node.statisticsFooters.value
        assertNotNull(footers, "the metadata node should offer its statistics footers")

        val footer = footers.values.single()
        assertEquals(null, footer.problem, "the file is beside the metadata and should open")
        assertEquals(4, footer.footer?.blobs?.size)
        assertTrue(
            footer.footer?.createdBy.orEmpty().contains("Iceberg 1.8.1"),
            "the footer names its writer, which the record does not: ${footer.footer?.createdBy}",
        )

        val rows = statisticsRows(node.data) { file -> footers[file.statisticsPath]?.footer }
        assertTrue(rows.all { it.agrees == true }, "disagreements: ${rows.filter { it.agrees != true }}")
        assertEquals(listOf(6L, 5L, 3L, 6L), rows.map { it.fileNdv })
        // Three things only the file knows, so a null here means the footer was never consulted.
        assertTrue(rows.all { it.codec == "zstd" }, "codecs: ${rows.map { it.codec }}")
        assertTrue(rows.all { (it.compressedLength ?: 0L) > 0L })
    }

    /**
     * A blob the record names and the file does not hold is a disagreement, not an absence.
     *
     * The distinction is the whole point of carrying `fileRead`: without it, "the file was never
     * opened" and "the file was opened and has no such blob" are the same three nulls, and the
     * second is the one that means the table is pointing a planner at statistics that are gone.
     */
    @Test
    fun `a blob missing from the file reads as a disagreement, not as unread`() {
        val emptyFooter = PuffinFileMetadata(blobs = emptyList())
        val rows = statisticsRows(metadata) { emptyFooter }
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.agrees == false }, "a file with no blobs disagrees with all four")
        assertTrue(rows.all { it.fileNdv == null })

        val unread = statisticsRows(metadata)
        assertTrue(unread.all { it.agrees == null }, "and a file never opened claims nothing")
    }
}
