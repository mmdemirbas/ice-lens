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

    private fun snapshots() = IcebergGraphBuilder.buildGraph(branchedModel())
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
        val result = IcebergGraphBuilder.buildGraph(branchedModel())
        val lineage = result.edges.filter { it.id.startsWith("e_lineage_") }

        assertEquals(5, snapshots().size, "four commits on main plus one on audit")
        assertEquals(4, lineage.size, "five snapshots, one of them the root")

        val childrenByParent = lineage.groupBy { it.fromId }
        val forkPoint = childrenByParent.entries.single { it.value.size == 2 }
        assertEquals(2, forkPoint.value.size, "the fork point has two children")
        assertEquals(
            3, childrenByParent.size,
            "a fork means fewer distinct parents than edges: ${childrenByParent.keys}",
        )
    }

    /**
     * This table has ten metadata versions, which makes it the first fixture that can tell a
     * numeric ordering of `vN.metadata.json` from a lexicographic one — `v9` sorts after `v10`
     * as a string, and the current schema, the current snapshot and every ref are read from
     * whichever file is considered last.
     */
    @Test
    fun `the newest metadata version is v10, not v9`() {
        val metadatas = branchedModel().metadatas
        assertTrue(metadatas.size >= 10, "the fixture should cross the 9-to-10 boundary")
        assertEquals("v10.metadata.json", metadatas.last().path.fileName.toString())
    }

    @Test
    fun `branches and tags are distinguished, and one snapshot carries two refs`() {
        val refsByName = snapshots().flatMap { node -> node.refs.map { it.name to it } }.toMap()

        assertEquals(
            setOf("main", "audit", "v1", "release", "prod"), refsByName.keys,
            "all five refs should reach a snapshot",
        )
        assertTrue(refsByName.getValue("main").isBranch, "main is a branch")
        assertTrue(refsByName.getValue("audit").isBranch, "audit is a branch")
        assertTrue(!refsByName.getValue("v1").isBranch, "v1 is a tag")
        assertTrue(!refsByName.getValue("release").isBranch, "release is a tag")
        assertEquals("release (tag)", refsByName.getValue("release").display)

        val multiRef = snapshots().filter { it.refs.size > 1 }
        assertEquals(1, multiRef.size, "the head of main is also tagged prod")
        assertEquals(
            listOf("main", "prod"), multiRef.single().refs.map { it.name },
            "main sorts first, then the rest alphabetically",
        )
    }

    /**
     * The layout property, and the reason the fixture has a fourth commit on `main` after the
     * audit commit: in wall-clock order the branches interleave, so the two orderings differ and
     * the rule is observable. Without that commit both orderings agree and this test proves
     * nothing.
     *
     * Ordering by lineage keeps `main`'s chain contiguous and puts `audit` after it. Ordering by
     * timestamp splits `main` around the audit commit.
     */
    @Test
    fun `lineage order keeps each branch contiguous where timestamp order does not`() {
        val snapshots = IcebergGraphBuilder.buildGraph(branchedModel())
            .nodes.filterIsInstance<GraphNode.SnapshotNode>()
        val rank = GraphLayoutService.snapshotLineageOrder(snapshots)

        val byLineage = snapshots.sortedBy { rank.getValue(it.id) }
        val chronological = snapshots.sortedBy { it.data.timestampMs }
        assertTrue(
            byLineage.map { it.id } != chronological.map { it.id },
            "the fixture must interleave the branches, or this rule is untestable",
        )

        val auditHead = snapshots.single { node -> node.refs.any { it.name == "audit" } }
        val mainChain = byLineage.filter { it.id != auditHead.id }.map { rank.getValue(it.id) }
        assertEquals(
            mainChain.sorted(), (mainChain.min()..mainChain.max()).toList(),
            "main's commits should occupy one unbroken run",
        )
        assertEquals(
            byLineage.size - 1, rank.getValue(auditHead.id),
            "the branch that forks off should follow the chain it forked from",
        )

        // And the ordering is still a valid walk: no commit precedes its own parent.
        snapshots.forEach { node ->
            val parent = snapshots.firstOrNull { it.data.snapshotId == node.data.parentSnapshotId }
            if (parent != null) {
                assertTrue(
                    rank.getValue(parent.id) < rank.getValue(node.id),
                    "a commit was ordered before its parent",
                )
            }
        }
    }

    /**
     * That the layout actually uses the lineage order, not just that the order exists.
     *
     * The audit commit is older than main's head, so timestamp ordering places it above and
     * lineage ordering below. One assertion separates the two, and it is the only one here that
     * fails if the comparator stops consulting the rank.
     */
    @Test
    fun `the layout places the branch after the chain it forked from, not by timestamp`() {
        val result = IcebergGraphBuilder.buildGraph(branchedModel())
        val graph = GraphLayoutService.layoutNodes(result.nodes, result.edges)

        val auditHead = result.nodes.filterIsInstance<GraphNode.SnapshotNode>()
            .single { node -> node.refs.any { it.name == "audit" } }
        val mainHead = result.nodes.filterIsInstance<GraphNode.SnapshotNode>()
            .single { node -> node.refs.any { it.name == "main" } }

        assertTrue(
            (auditHead.data.timestampMs ?: 0) < (mainHead.data.timestampMs ?: 0),
            "the fixture must commit to audit before main's head, or this proves nothing",
        )
        assertTrue(
            graph.layoutPositions.getValue(auditHead.id).y > graph.layoutPositions.getValue(mainHead.id).y,
            "the branch tip should sit below main's tip, which timestamp ordering would reverse",
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

    /**
     * A row lookup as of the branch tip — the snapshot node's own `readInput`, the input the
     * live-row count reads from — finds the row only the branch holds, and the same lookup as
     * of main's tip does not: `echo-audit-only` was inserted into `branch_audit` alone. The
     * table's history walks `main` and cannot answer this, which is what the snapshot panel's
     * lookup is for.
     */
    @Test
    fun `a row lookup as of the branch tip finds the row only the branch holds`() {
        val snapshots = IcebergGraphBuilder.buildGraph(branchedModel()).nodes.filterIsInstance<GraphNode.SnapshotNode>()
        val auditHead = snapshots.single { node -> node.refs.any { it.name == "audit" } }
        val mainHead = snapshots.single { node -> node.refs.any { it.name == "main" } }
        val filter = model.ScanFilter.Term(model.ScanPredicate("id", model.PredicateOp.EQ, "5"))
        val onAudit = RowLookup.lookup(assertNotNull(auditHead.readInput.value), filter, emptySet())
        assertEquals(listOf("echo-audit-only"), onAudit.hits.map { it.cells["name"] }, onAudit.toString())
        assertEquals(1, onAudit.live)
        val onMain = RowLookup.lookup(assertNotNull(mainHead.readInput.value), filter, emptySet())
        assertEquals(0, onMain.hits.size, "main never had id 5: $onMain")
        assertEquals(4, onMain.filesRead.size + onMain.filesRuledOut, "main's tip holds four files")
    }
}
