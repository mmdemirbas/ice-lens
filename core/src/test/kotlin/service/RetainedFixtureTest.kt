package service

import model.GraphNode
import model.UnifiedTableModel
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `example/iceberg/default/retained`: refs with retention settings and an expiry that honoured
 * each ref's own, from `docs/fixtures/retained.sql`. The writer's printout is the oracle: `audit`
 * keeps two snapshots (min 2), `release` keeps its one, `main` keeps its tip, and the expiry
 * removed snapshots 1 and 3 — two manifest lists, no manifest and no data file, since later
 * snapshots still carry those forward.
 */
class RetainedFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val model = UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/retained").absolutePath))
    private val newest = model.metadatas.last().metadata

    @Test
    fun `the refs carry the retention each was created with`() {
        val audit = newest.refs.getValue("audit")
        assertEquals("branch", audit.type)
        assertEquals(30L * 86_400_000L, audit.maxRefAgeMs, "RETAIN 30 DAYS")
        assertEquals(2, audit.minSnapshotsToKeep, "WITH SNAPSHOT RETENTION 2 SNAPSHOTS")
        assertNull(audit.maxSnapshotAgeMs, "no age given, so older_than applies")
        val release = newest.refs.getValue("release")
        assertEquals("tag", release.type)
        assertEquals(90L * 86_400_000L, release.maxRefAgeMs)
        assertNull(release.minSnapshotsToKeep)
        val main = newest.refs.getValue("main")
        assertTrue(main.maxRefAgeMs == null && main.maxSnapshotAgeMs == null && main.minSnapshotsToKeep == null, "the table's defaults")
        assertTrue(model.readErrors.isEmpty(), "${model.readErrors}")
    }

    @Test
    fun `the expiry kept what each ref's retention says and dropped the rest`() {
        val before = model.metadatas[model.metadatas.size - 2].metadata
        assertEquals(6, before.snapshots.size)
        assertEquals(4, newest.snapshots.size)
        val byOrder = before.snapshots.sortedBy { it.sequenceNumber }.map { it.snapshotId!! }
        val kept = newest.snapshots.map { it.snapshotId!! }.toSet()
        assertEquals(setOf(byOrder[1], byOrder[3], byOrder[4], byOrder[5]), kept, "2 (the tag), 4 and 5 (audit's two), 6 (main's tip)")
        assertEquals(byOrder[1], newest.refs.getValue("release").snapshotId)
        assertEquals(byOrder[4], newest.refs.getValue("audit").snapshotId)
        assertEquals(byOrder[5], newest.refs.getValue("main").snapshotId)
        // The two audit snapshots kept still name parents that are gone.
        assertEquals(byOrder[2], newest.snapshots.single { it.snapshotId == byOrder[3] }.parentSnapshotId)
        assertTrue(byOrder[2] !in kept)
    }

    @Test
    fun `the graph draws the expired two as expired and keeps the branch column`() {
        val graph = GraphLayoutService.layoutGraph(model, showRows = false)
        val snapshots = graph.nodes.filterIsInstance<GraphNode.SnapshotNode>()
        assertEquals(6, snapshots.size, "older metadata versions still name the expired ones")
        assertEquals(2, snapshots.count { it.expired })
        assertTrue(snapshots.filter { it.expired }.all { it.refs.isEmpty() })
        val audit = snapshots.single { it.refs.any { r -> r.name == "audit" } }
        val main = snapshots.single { it.refs.any { r -> r.name == "main" } }
        assertTrue(graph.layoutPositions.getValue(audit.id).x != graph.layoutPositions.getValue(main.id).x, "the branch has its own column")
    }
}
