package model

import service.GraphLayoutService
import service.PaimonGraphBuilder
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `example/paimon/db.db/rt`: an append table with `row-tracking.enabled`, two appends and a full
 * compaction, from `docs/fixtures/paimon-rt.sql`.
 *
 * Row tracking gives each row a stable id, and the fixture settled where the id is kept: a file
 * a commit wrote records `_FIRST_ROW_ID` and its rows take consecutive ids in file order, while
 * a compaction's output records **no** first id and carries every row's id in a `_ROW_ID` column
 * of the file itself — beside a `_SEQUENCE_NUMBER` column holding the sequence of the commit that
 * wrote the row. The compaction also reordered the rows (4, 5, 1, 2, 3), which is why the id has
 * to travel with the row. The expected values are read off the script: ids 0–2, then 3–4, next
 * id 3 then 5, and the compaction leaving the next id where it was.
 */
class PaimonRowTrackingFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun model(name: String) =
        PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/$name").absolutePath))

    private val model = model("rt")

    @Test
    fun `each commit records the next id, and each appended file its first`() {
        assertEquals(listOf(3L, 5L, 5L), model.snapshots.map { it.metadata.nextRowId }, "two appends of 3 and 2 rows, then a compaction that hands out none")
        assertEquals(listOf("APPEND", "APPEND", "COMPACT"), model.snapshots.map { it.metadata.commitKind })
        val appended = model.snapshots.take(2).map { it.deltaManifests.single().entries.single().metadata.file!! }
        assertEquals(listOf(0L, 3L), appended.map { it.firstRowId })
        assertEquals(listOf(3L, 2L), appended.map { it.rowCount })
        assertEquals("true", model.schemas.single().options["row-tracking.enabled"])
    }

    /** The compaction's output records no first id; its rows carry their own, and their writer's sequence number. */
    @Test
    fun `a compacted file carries every row's id inside it`() {
        val compaction = model.snapshots.last()
        val added = compaction.deltaManifests.single().entries.filter { (it.metadata.kind ?: 0) == PaimonEntryKind.ADD }
        val compacted = added.single().metadata.file!!
        assertNull(compacted.firstRowId)
        assertEquals(PaimonFileSource.COMPACT, compacted.fileSource)
        assertEquals(5L, compacted.rowCount)

        val rows = added.single().rows
        assertEquals(5, rows.size)
        assertEquals(setOf(0L, 1L, 2L, 3L, 4L), rows.map { (it.cells["_ROW_ID"] as Number).toLong() }.toSet(), "every id the two appends handed out, once")
        assertEquals(listOf(4, 5, 1, 2, 3), rows.map { (it.cells["k"] as Number).toInt() }, "the compaction reordered the rows, which is why the id travels with the row")
        assertEquals(listOf(2L, 2L, 1L, 1L, 1L), rows.map { (it.cells["_SEQUENCE_NUMBER"] as Number).toLong() }, "each row keeps the sequence number of the commit that wrote it")
        // The removed files are the two appends, listed with their first ids still on them.
        val removed = compaction.deltaManifests.single().entries.filter { (it.metadata.kind ?: 0) == PaimonEntryKind.DELETE }
        assertEquals(setOf(0L, 3L), removed.map { it.metadata.file?.firstRowId }.toSet())
    }

    /** The row cards say the id either way: derived from the first id for an appended file, read from the column for a compacted one. */
    @Test
    fun `a row node carries its row id whether the file recorded a first id or the column`() {
        val graph = GraphLayoutService.layoutGraph(model, showRows = true)
        val files = graph.nodes.filterIsInstance<GraphNode.PaimonDataFileNode>()
        val rowsOf = { file: GraphNode.PaimonDataFileNode ->
            graph.edges.filter { it.fromId == file.id }.mapNotNull { graph.nodeById[it.toId] as? GraphNode.RowNode }
                .map { it.resolvedData }.filter { it.containsKey("k") }
        }
        val appended = files.first { it.entry.file?.firstRowId == 3L && it.operationKind == PaimonEntryKind.ADD }
        assertEquals(listOf(3L, 4L), rowsOf(appended).map { (it[PaimonGraphBuilder.ROW_ID_COLUMN] as Number).toLong() }, "first id plus position")
        val compacted = files.first { it.entry.file?.fileSource == PaimonFileSource.COMPACT }
        assertEquals(setOf(0L, 1L, 2L, 3L, 4L), rowsOf(compacted).map { (it[PaimonGraphBuilder.ROW_ID_COLUMN] as Number).toLong() }.toSet(), "read from the file's own column")
    }

    /**
     * The invariant across every checked-in table: a file never accounts for an id at or past the
     * snapshot's next one, and the next id never goes backwards. Vacuous on the tables without row
     * tracking, and asserted to be non-vacuous on this one.
     */
    @Test
    fun `no file claims an id past the next one, on any table`() {
        var checked = 0
        listOf("test", "dv", "cl", "tg", "pt", "ao", "br", "cs", "fi", "ep", "rt", "sm", "se").forEach { name ->
            val m = model(name)
            var previousNext = Long.MIN_VALUE
            m.snapshots.forEach { snapshot ->
                val next = snapshot.metadata.nextRowId ?: return@forEach
                assertTrue(next >= previousNext, "$name snapshot ${snapshot.metadata.id}: next id went from $previousNext to $next")
                previousNext = next
                (snapshot.baseManifests + snapshot.deltaManifests).flatMap { it.entries }.forEach { entry ->
                    val first = entry.metadata.file?.firstRowId ?: return@forEach
                    checked++
                    assertTrue(first + (entry.metadata.file?.rowCount ?: 0L) <= next, "$name snapshot ${snapshot.metadata.id}: a file at $first claims ids past $next")
                }
            }
        }
        assertTrue(checked >= 5, "the rt fixture alone lists five row-tracked entries across its snapshots: $checked")
    }
}
