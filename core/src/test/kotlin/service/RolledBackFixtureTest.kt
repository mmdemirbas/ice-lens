package service

import model.GraphNode
import model.SnapshotLogKind
import model.UnifiedTableModel
import model.currentAncestorIds
import model.leftBehindBy
import model.snapshotHistory
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `example/iceberg/default/rolled`: main set back to an earlier snapshot, from
 * `docs/fixtures/rolled.sql`. A rollback writes no snapshot — `snapshot-log` gains a second
 * entry for the target, the abandoned commit stays in `snapshots` on no ref, and the next commit
 * forks from the target. The oracle is what the writer printed: `.history`'s `is_current_ancestor`
 * is true, true, false, true, true down the log, and the table reads rows 1, 2, 4.
 */
class RolledBackFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val model = UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/rolled").absolutePath))
    private val newest = model.metadatas.last().metadata
    private val history = newest.snapshotHistory()
    private val ids = newest.snapshots.map { it.snapshotId!! }

    @Test
    fun `the log records the rollback as a second entry for its target, and nothing else does`() {
        assertEquals(8, model.metadatas.size)
        assertEquals(4, ids.size, "the abandoned commit is still listed")
        assertEquals(listOf(ids[0], ids[1], ids[2], ids[1], ids[3]), history.map { it.entry.snapshotId })
        assertEquals(
            listOf(SnapshotLogKind.COMMIT, SnapshotLogKind.COMMIT, SnapshotLogKind.COMMIT, SnapshotLogKind.RESET, SnapshotLogKind.COMMIT),
            history.map { it.kind },
        )
        assertEquals(listOf(ids[2]), history[3].leftBehind)
        assertEquals(listOf(true, true, false, true, true), history.map { it.currentAncestor }, "what .history printed as is_current_ancestor")
        assertEquals(setOf(ids[0], ids[1], ids[3]), newest.currentAncestorIds())
        assertEquals(mapOf(ids[2] to history[3].entry), newest.leftBehindBy())
        assertEquals(ids[1], newest.snapshots[3].parentSnapshotId, "the commit after the rollback forked from its target")
        assertEquals(mapOf("main" to ids[3]), newest.refs.mapValues { it.value.snapshotId }, "the tag was dropped")
        assertTrue(model.readErrors.isEmpty(), "${model.readErrors}")
    }

    @Test
    fun `the abandoned commit is drawn on no ref, beside main, and says why`() {
        val graph = GraphLayoutService.layoutGraph(model, showRows = false)
        val nodes = graph.nodes.filterIsInstance<GraphNode.SnapshotNode>().associateBy { it.data.snapshotId }
        val abandoned = assertNotNull(nodes[ids[2]])
        assertTrue(!abandoned.expired, "retained by the metadata, not expired")
        assertTrue(abandoned.refs.isEmpty())
        assertEquals(history[3].entry, abandoned.leftBehindAt)
        listOf(ids[0], ids[1], ids[3]).forEach { assertNull(nodes.getValue(it).leftBehindAt, "$it is on main's line") }
        // Two children of snapshot 2 — the abandoned commit and the one after the rollback — so
        // the branch column gives one of them its own x, and main keeps the trunk's.
        val mainX = graph.layoutPositions.getValue(nodes.getValue(ids[3]).id).x
        assertEquals(mainX, graph.layoutPositions.getValue(nodes.getValue(ids[1]).id).x, "the trunk stays in one column")
        assertTrue(graph.layoutPositions.getValue(abandoned.id).x != mainX, "the abandoned commit takes a column of its own")
    }

    /** A table that was never rolled back reads every entry as a commit and every retained snapshot on the line. */
    @Test
    fun `a linear history has no resets`() {
        val plain = UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/test").absolutePath)).metadatas.last().metadata
        assertTrue(plain.snapshotHistory().all { it.kind == SnapshotLogKind.COMMIT && it.currentAncestor })
        assertTrue(plain.leftBehindBy().isEmpty())
    }
}
