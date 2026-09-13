package model

import service.GraphLayoutService
import service.LiveRowCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * `eqpart`: two equality deletes on `id` alone, one under the partitioned spec in `p=y` and one
 * under the unpartitioned spec the table started with, over three data files that all hold ids
 * 1..2 — see `docs/fixtures/eqpart.scala`. It exists because `fupp` cannot separate the partition
 * rule from the bounds rule: its partition column is an equality column, so the bounds on it
 * already differ across partitions. Here every file's bounds admit both deletes, and what rules
 * one out is the partition alone.
 *
 * Iceberg keys an equality delete written under a partitioned spec by `(spec id, partition)` and
 * never weighs it against a file in another partition or under another spec; one written under an
 * unpartitioned spec is global and weighed against every file. Its plan
 * (`core/src/test/resources/iceberg-scan-plans/deletes.txt`) attaches the global delete to all
 * three files and the `p=y` delete to the `p=y` file only; Spark read back `(1, x), (1, x)`.
 */
class EqualityPartitionFixtureTest {

    private val model by lazy { FixtureCatalog.icebergModel("eqpart") }
    private val current by lazy { model.metadatas.last().let { m -> m.snapshots.single { it.metadata.snapshotId == m.metadata.currentSnapshotId } } }

    @Test
    fun `a partitioned equality delete is scoped to its spec and partition, an unpartitioned one is global`() {
        val graph = GraphLayoutService.layoutGraph(model, showRows = false)
        val files = graph.nodes.filterIsInstance<GraphNode.FileNode>()
        val data = files.filter { deleteKindOf(it.data) == null }
        assertEquals(3, data.size)
        fun verdicts(specId: Int, partition: String) = deleteCandidatesFor(data.single { it.specId == specId && it.partition?.path == partition }, files)
            .associate { "${it.delete.specId}/${it.delete.partition?.path}" to it.verdict }

        val partitioned = "1/p=y"
        val global = "0/"
        assertEquals(mapOf(partitioned to DeleteReachVerdict.RULED_OUT_BY_PARTITION, global to DeleteReachVerdict.MAY_REACH), verdicts(0, ""), "the spec-0 file")
        assertEquals(mapOf(partitioned to DeleteReachVerdict.RULED_OUT_BY_PARTITION, global to DeleteReachVerdict.MAY_REACH), verdicts(1, "p=x"))
        assertEquals(mapOf(partitioned to DeleteReachVerdict.MAY_REACH, global to DeleteReachVerdict.MAY_REACH), verdicts(1, "p=y"))
    }

    @Test
    fun `the table reads as the two rows Spark read back`() {
        val input = assertNotNull(model.rowLookupInputOf(current, liveFilesOf(current), deleteReach(current)))
        val count = LiveRowCount.count(input)
        assertEquals(2L, count.live, "$count")
        assertEquals(0, count.failed + count.filesLeft)
    }
}
