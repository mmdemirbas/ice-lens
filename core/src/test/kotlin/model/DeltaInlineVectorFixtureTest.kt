package model

import service.AggregationPolicy
import service.DeltaGraphBuilder
import service.GraphLayoutService
import service.LiveRowCount
import service.PuffinReader
import service.RowHistoryTrace
import service.RowLookup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Inline deletion vectors (`storageType = i`), which delta-spark 3.2.1 reads and never writes from
 * SQL: `dinl`'s two were committed through its own classes (`dinl.scala`), and its reads after
 * them — the script's `SELECT`s at version 4 and at the latest — are the oracle for the lookup,
 * the count, the history and the row cards. `PROTOCOL.md`'s own inline example is refused, as
 * delta-spark refuses it.
 */
class DeltaInlineVectorFixtureTest {

    private val dinl = FixtureCatalog.deltaModel("dinl")

    private fun filter(text: String) = (parseScanFilter(text) as ScanFilterParse.Parsed).filter

    private fun fates(version: Long): Map<Int, RowFate> =
        RowLookup.lookup(dinl.readInputAt(version)!!, filter("id >= 1"), emptySet()).hits.associate { (it.cells["id"] as Number).toInt() to it.fate }

    @Test
    fun `the protocol's inline example is refused, as delta-spark 3_2_1 refuses it`() {
        // Its bytes are the Native layout written big-endian: delta-spark's little-endian reader
        // reads magic -791463580 and throws (dinl-protocol-example.scala), and so does this one.
        val blob = deltaInlineVectorBlob("wi5b=000010000siXQKl0rr91000f55c8Xg0@@D72lkbi5=-{L", 40)
        assertEquals("64 39 d3 d0 00 00 00 01 00 00 00 1c", blob.copyOfRange(4, 16).joinToString(" ") { "%02x".format(it) })
        assertFailsWith<PuffinReader.PuffinFormatException> { PuffinReader.decodeDeletionVector(blob, recordedCardinality = 6) }
        // dinl's second vector, which delta-spark reads as 1, 3, 5, is framed and read the same way.
        val dinl = deltaInlineVectorBlob("^Bg9^0rr910000000000iXQKl0rr91000625c8Xg0rri41POJ5", 38)
        assertEquals(listOf(1, 3, 5), PuffinReader.positionsOf(dinl).stream().toArray().toList())
    }

    @Test
    fun `dinl's version 4 holds two inline vectors, the one DELETE's position and three more`() {
        val state = dinl.stateAt(4).getOrThrow()
        val vectors = state.files.values.mapNotNull { it.deletionVector }
        assertEquals(listOf("i", "i"), vectors.map { it.storageType })
        assertEquals(setOf(1L, 3L), vectors.map { it.cardinality }.toSet())
        // Each decodes, from the log alone, to the positions dinl.scala printed.
        val positions = state.files.values.associate { add ->
            add.path to DeltaGraphBuilder.readDeletionVector(dinl, add.deletionVector!!)!!.positions.toList()
        }
        assertEquals(setOf(listOf(1L), listOf(1L, 3L, 5L)), positions.values.toSet())
        // The lookup's input carries the text; no file is named for it.
        val deletes = dinl.readInputAt(4)!!.deleteFiles
        assertTrue(deletes.all { it.inlineVector != null && it.recordedPath.startsWith("inline vector on ") }, deletes.toString())
    }

    @Test
    fun `a row an inline vector marks is deleted, as delta-spark read it`() {
        // The script's SELECT at version 4: 1 3 4 5 7 9.
        assertEquals((1..10).associateWith { if (it in setOf(2, 6, 8, 10)) RowFate.VECTOR_DELETED else RowFate.LIVE }, fates(4))
        // After the DELETE at 5 the first file's vector is a file again, the second's still inline: 1 4 5 7 9.
        assertEquals((1..10).associateWith { if (it in setOf(2, 3, 6, 8, 10)) RowFate.VECTOR_DELETED else RowFate.LIVE }, fates(5))
        assertEquals(listOf("i", "u"), dinl.stateAt(5).getOrThrow().files.values.mapNotNull { it.deletionVector?.storageType }.sorted())
    }

    @Test
    fun `the live count applies an inline vector without opening the file`() {
        assertEquals(listOf(0L, 4L, 10L, 9L, 6L, 5L), dinl.versions.map { LiveRowCount.count(dinl.readInputAt(it)!!).live })
        val atFour = LiveRowCount.count(dinl.readInputAt(4)!!)
        assertTrue(atFour.files.none { it.opened || it.error != null }, atFour.files.toString())
    }

    @Test
    fun `the history sees version 4 change nothing for id 2 and remove id 6`() {
        fun changes(text: String) = RowHistoryTrace.trace(dinl.rowHistoryInputs()!!, filter(text), emptySet()).let { h ->
            h.steps.zip(h.changes).map { (step, change) -> step.snapshot.snapshotId to change }.filter { it.second != null && it.second != RowChange.UNCHANGED }
        }
        // Re-expressing id 2's vector inline at 4 is not a change a read shows.
        assertEquals(listOf(3L to RowChange.GONE, 1L to RowChange.APPEARED), changes("id = 2"))
        assertEquals(listOf(4L to RowChange.GONE, 2L to RowChange.APPEARED), changes("id = 6"))
        assertEquals(listOf(5L to RowChange.GONE, 1L to RowChange.APPEARED), changes("id = 3"))
    }

    @Test
    fun `the row cards under version 4's second file are struck where the inline vector marks them`() {
        val graph = GraphLayoutService.assembleGraph(dinl, showRows = true, policy = AggregationPolicy.NONE)
        val second = graph.nodes.filterIsInstance<GraphNode.DeltaFileNode>()
            .single { it.version == 4L && it.action == DeltaFileAction.ADD && it.vector?.cardinality == 3L }
        val rows = graph.edges.filter { it.fromId == second.id }.mapNotNull { e -> graph.nodes.firstOrNull { it.id == e.toId } as? GraphNode.RowNode }
        // RowNode.countFor caps a file's cards at five, so the sixth row, id 10, is not drawn.
        assertEquals(5, rows.size)
        assertEquals(setOf(6, 8), rows.filter { it.isDeletedByVector }.map { it.resolvedData.getValue("id").toString().toInt() }.toSet())
        assertNotNull(second.deletionVector.value)
    }
}
