package service

import model.GraphNode
import model.UnifiedTableModel
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Snapshot lineage: `parent-snapshot-id` drawn as an edge, and `refs` carried on the snapshot.
 *
 * The graph's subject is a commit history, and until these edges existed it drew every snapshot
 * as an unrelated child of a metadata file — the one relationship the format is actually built
 * around was the one relationship missing.
 *
 * The interesting half is what lineage must NOT do. A parent is another snapshot, so feeding
 * these edges to a layered layout puts each commit in its own layer and drags its manifests and
 * files with it. `example/iceberg/default/mor` has six commits, which is enough for that to show
 * up as a graph several times wider than it should be.
 */
class SnapshotLineageTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun modelFor(name: String) =
        UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/$name").absolutePath))

    @Test
    fun `every snapshot with a retained parent gets a lineage edge`() {
        val model = modelFor("mor")
        val result = IcebergGraphBuilder.buildGraph(model)
        val snapshotIds = result.nodes.filterIsInstance<GraphNode.SnapshotNode>()
            .mapNotNull { it.data.snapshotId }.toSet()

        val expected = model.metadatas.flatMap { it.metadata.snapshots }
            .distinctBy { it.snapshotId }
            .mapNotNull { snap ->
                val parent = snap.parentSnapshotId ?: return@mapNotNull null
                if (parent !in snapshotIds || snap.snapshotId !in snapshotIds) null
                else "e_lineage_${parent}_to_${snap.snapshotId}"
            }
            .toSet()

        assertTrue(expected.isNotEmpty(), "the six-commit fixture should have lineage to draw")
        val actual = result.edges.filter { it.id.startsWith("e_lineage_") }.map { it.id }.toSet()
        assertEquals(expected, actual)
    }

    /**
     * The lineage chain has to be a chain: five edges over six commits, each snapshot having at
     * most one parent. A duplicate would mean a snapshot reached through two metadata versions
     * produced two edges.
     */
    @Test
    fun `lineage forms one chain over the commit history`() {
        val result = IcebergGraphBuilder.buildGraph(modelFor("mor"))
        val lineage = result.edges.filter { it.id.startsWith("e_lineage_") }
        val snapshots = result.nodes.filterIsInstance<GraphNode.SnapshotNode>()

        assertEquals(6, snapshots.size, "the fixture has six commits")
        assertEquals(5, lineage.size, "six commits in a line have five parent links")
        assertEquals(lineage.size, lineage.map { it.toId }.distinct().size, "a snapshot has one parent")
        assertEquals(lineage.size, lineage.map { it.fromId }.distinct().size, "no branching in this fixture")
    }

    /**
     * The property the whole `affectsLayout` flag exists for. Lineage must not reach ELK, or the
     * layout stretches by the length of the commit history.
     */
    @Test
    fun `lineage edges are marked as not affecting layout`() {
        val result = IcebergGraphBuilder.buildGraph(modelFor("mor"))
        val lineage = result.edges.filter { it.id.startsWith("e_lineage_") }

        assertTrue(lineage.isNotEmpty())
        assertTrue(lineage.none { it.affectsLayout }, "lineage would constrain layering")
        assertTrue(
            result.edges.filter { !it.id.startsWith("e_lineage_") }.all { it.affectsLayout },
            "only lineage should opt out of layout",
        )
    }

    /**
     * Measured, not argued. The same graph is laid out with lineage withheld from ELK (what ships)
     * and with it included, and the difference is the cost of getting this wrong.
     */
    @Test
    fun `withholding lineage from the layout keeps the graph from stretching`() {
        val result = IcebergGraphBuilder.buildGraph(modelFor("mor"))

        val shipped = GraphLayoutService.layoutNodes(result.nodes, result.edges)
        val ifLineageCounted = GraphLayoutService.layoutNodes(
            result.nodes.map { it },
            result.edges.map { if (it.id.startsWith("e_lineage_")) it.copy(affectsLayout = true) else it },
        )

        assertTrue(
            shipped.width < ifLineageCounted.width,
            "expected a narrower graph without lineage layering: ${shipped.width} vs ${ifLineageCounted.width}",
        )
    }

    /**
     * The safety invariant behind ordering snapshots by lineage rather than by timestamp: on a
     * history with no branches the two orderings are the same, so every unbranched table keeps
     * the order it had before.
     *
     * Asserted on the ordering itself rather than on final y positions. Snapshot nodes are shared
     * between metadata versions and reordered once per metadata parent, so the y a snapshot ends
     * up with depends on which parent was processed last — a separate wrinkle, noted in TODO.md,
     * that would make this test measure the wrong thing.
     */
    @Test
    fun `on a linear history the lineage order is the chronological order`() {
        listOf("mor", "evolved", "v3", "parted").forEach { fixture ->
            val snapshots = IcebergGraphBuilder.buildGraph(modelFor(fixture))
                .nodes.filterIsInstance<GraphNode.SnapshotNode>()
            val rank = GraphLayoutService.snapshotLineageOrder(snapshots)

            val byLineage = snapshots.sortedBy { rank.getValue(it.id) }.mapNotNull { it.data.snapshotId }
            val chronological = snapshots
                .sortedWith(compareBy({ it.data.timestampMs ?: Long.MAX_VALUE }, { it.data.snapshotId }))
                .mapNotNull { it.data.snapshotId }

            assertEquals(chronological, byLineage, "$fixture is linear and should be unchanged")
        }
    }

    /**
     * Refs come from the latest metadata version, never from the one that introduced a snapshot.
     * `main` moves with every commit, so labelling each snapshot from the file that first
     * mentioned it would put `main` on all six of them.
     */
    @Test
    fun `refs describe the table now, so main lands on exactly one snapshot`() {
        val result = IcebergGraphBuilder.buildGraph(modelFor("mor"))
        val snapshots = result.nodes.filterIsInstance<GraphNode.SnapshotNode>()

        val carryingMain = snapshots.filter { node -> node.refs.any { it.name == "main" } }
        assertEquals(1, carryingMain.size, "main points at one snapshot, got ${carryingMain.size}")

        val currentSnapshotId = modelFor("mor").metadatas.last().metadata.currentSnapshotId
        assertEquals(currentSnapshotId, carryingMain.single().data.snapshotId, "main should be the current snapshot")
        assertTrue(carryingMain.single().refs.first().isBranch, "main is a branch, not a tag")
    }
}
