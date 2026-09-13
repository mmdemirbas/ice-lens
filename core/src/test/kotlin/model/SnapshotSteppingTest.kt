package model

import service.GraphLayoutService
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stepping one side of a comparison: the neighbour in commit order among the snapshots that can
 * be compared, null off either end, and an expired snapshot never a stop.
 */
class SnapshotSteppingTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun graph(format: String, name: String) = GraphLayoutService.layoutGraph(
        if (format == "paimon") PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/$name").absolutePath))
        else UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/$name").absolutePath)),
        showRows = false,
    )

    @Test
    fun `steps follow commit order and stop at the ends`() {
        val graph = graph("iceberg", "sorted")
        val ordered = graph.comparableSnapshotsInOrder()
        assertEquals(4, ordered.size)
        assertEquals(ordered.map { it.commitOrder }, ordered.map { it.commitOrder }.sortedBy { it })
        assertEquals(ordered[1].nodeId, graph.stepComparableSnapshot(ordered[0].nodeId, 1)?.nodeId)
        assertEquals(ordered[0].nodeId, graph.stepComparableSnapshot(ordered[1].nodeId, -1)?.nodeId)
        assertEquals(ordered[3].nodeId, graph.stepComparableSnapshot(ordered[1].nodeId, 2)?.nodeId)
        assertNull(graph.stepComparableSnapshot(ordered[0].nodeId, -1), "nothing older than the first")
        assertNull(graph.stepComparableSnapshot(ordered[3].nodeId, 1), "nothing newer than the last")
        assertNull(graph.stepComparableSnapshot("table_root", 1), "not a snapshot")
    }

    @Test
    fun `an expired snapshot is drawn but never a stop, and Paimon steps by snapshot id`() {
        val expired = graph("iceberg", "expired")
        val drawn = expired.nodes.filterIsInstance<GraphNode.SnapshotNode>()
        assertTrue(drawn.any { it.expired }, "the fixture draws an expired snapshot")
        assertTrue(expired.comparableSnapshotsInOrder().none { (it as GraphNode.SnapshotNode).expired })
        val paimon = graph("paimon", "lk")
        val ordered = paimon.comparableSnapshotsInOrder()
        assertEquals((1L..6L).toList(), ordered.map { it.commitId })
        assertEquals(3L, paimon.stepComparableSnapshot(ordered[1].nodeId, 1)?.commitId)
    }
}
