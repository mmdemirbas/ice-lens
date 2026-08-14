package service

import model.GraphNode
import model.UnifiedTableModel
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `example/iceberg/default/branched`: three commits on `main`, a fourth on an `audit` branch
 * forked from the second, and two tags.
 *
 * Until this fixture, every table had `main` and nothing else, so three shapes were untested:
 * a history that genuinely forks, a snapshot carrying more than one ref, and a tag as distinct
 * from a branch. The fork matters most — two snapshots share a parent, which the graph's
 * vertical ordering cannot express and only the lineage edges show.
 */
class BranchedFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun branchedModel(): UnifiedTableModel {
        val tableDir = File(repoRoot, "example/iceberg/default/branched")
        assertTrue(tableDir.isDirectory, "branched fixture missing at $tableDir")
        return UnifiedTableModel(Paths.get(tableDir.absolutePath))
    }

    private fun snapshots() = IcebergGraphBuilder.buildGraph(branchedModel(), showRows = false)
        .nodes.filterIsInstance<GraphNode.SnapshotNode>()

    @Test
    fun `the branched table decodes with no read errors`() {
        assertEquals(emptyList(), branchedModel().readErrors)
    }

    /**
     * The fork. Two snapshots name the same parent, so the lineage is a tree rather than the
     * chain every other fixture produces — and `SnapshotLineageTest` asserts the chain property
     * on `mor`, which would be wrong here.
     */
    @Test
    fun `two snapshots share a parent, so the lineage forks`() {
        val result = IcebergGraphBuilder.buildGraph(branchedModel(), showRows = false)
        val lineage = result.edges.filter { it.id.startsWith("e_lineage_") }

        assertEquals(4, snapshots().size, "three commits on main plus one on audit")
        assertEquals(3, lineage.size, "four snapshots, one of them the root")

        val childrenByParent = lineage.groupBy { it.fromId }
        val forkPoint = childrenByParent.entries.single { it.value.size == 2 }
        assertEquals(2, forkPoint.value.size, "the fork point has two children")
        assertEquals(
            2, childrenByParent.size,
            "a fork means fewer distinct parents than edges: ${childrenByParent.keys}",
        )
    }

    @Test
    fun `branches and tags are distinguished, and one snapshot carries two refs`() {
        val refsByName = snapshots().flatMap { node -> node.refs.map { it.name to it } }.toMap()

        assertEquals(
            setOf("main", "audit", "v1", "release"), refsByName.keys,
            "all four refs should reach a snapshot",
        )
        assertTrue(refsByName.getValue("main").isBranch, "main is a branch")
        assertTrue(refsByName.getValue("audit").isBranch, "audit is a branch")
        assertTrue(!refsByName.getValue("v1").isBranch, "v1 is a tag")
        assertTrue(!refsByName.getValue("release").isBranch, "release is a tag")
        assertEquals("release (tag)", refsByName.getValue("release").display)

        val multiRef = snapshots().filter { it.refs.size > 1 }
        assertEquals(1, multiRef.size, "the head of main is also tagged release")
        assertEquals(
            listOf("main", "release"), multiRef.single().refs.map { it.name },
            "main sorts first, then the rest alphabetically",
        )
    }

    /**
     * The audit branch's commit is reachable only through that branch. It is not on `main` and
     * not an ancestor of the current snapshot, which is exactly the snapshot a tool that only
     * walks `current-snapshot-id` would never show.
     */
    @Test
    fun `the branch-only commit is present and is not an ancestor of main`() {
        val model = branchedModel()
        val currentId = model.metadatas.last().metadata.currentSnapshotId
        val auditHead = snapshots().single { node -> node.refs.any { it.name == "audit" } }

        assertTrue(auditHead.data.snapshotId != currentId, "audit is not main")

        val byId = model.metadatas.last().metadata.snapshots.associateBy { it.snapshotId }
        val mainAncestry = generateSequence(currentId) { byId[it]?.parentSnapshotId }.toSet()
        assertTrue(
            auditHead.data.snapshotId !in mainAncestry,
            "the audit commit should be off main's ancestry entirely",
        )

        assertNotNull(auditHead.data.parentSnapshotId)
        assertTrue(
            auditHead.data.parentSnapshotId in mainAncestry,
            "audit forked from a commit that is on main",
        )
    }
}
