package model

import service.GraphLayoutService
import service.LiveRowCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `fupp`: `fup` partitioned by `p`, with `p` in the primary key — the one shape where the two
 * rules that scope a delete beyond sequence and target both bite. See
 * `docs/fixtures/flink-upsert-partitioned.sql` for what each commit wrote.
 *
 * Commit 2's equality delete in `p=y` names key 2, and Iceberg keys equality deletes by spec and
 * partition, so it is never weighed against the `p=x` files at all. Commit 2's equality delete in
 * `p=x` names key 4, and the only earlier `p=x` file holds ids 1..1 — `canContainEqDeletesForFile`
 * compares the two files' bounds on the equality columns and rules it out, so that delete applies
 * to nothing though its sequence number and partition both allow it. Iceberg's own plan
 * (`core/src/test/resources/iceberg-scan-plans/deletes.txt`) attaches exactly one delete file to
 * each of commit 1's two data files and none to commit 2's; Flink read back `(1, x, a2), (2, y,
 * b2), (4, x, d)`.
 */
class FlinkUpsertPartitionedFixtureTest {

    private val model by lazy { FixtureCatalog.icebergModel("fupp") }
    private val current by lazy { model.metadatas.last().let { m -> m.snapshots.single { it.metadata.snapshotId == m.metadata.currentSnapshotId } } }

    private fun tail(path: String) = path.substringAfterLast('/')

    @Test
    fun `an equality delete is scoped to its partition, and ruled out by bounds where the partition allows it`() {
        val graph = GraphLayoutService.layoutGraph(model, showRows = false)
        val files = graph.nodes.filterIsInstance<GraphNode.FileNode>()
        val data = files.filter { deleteKindOf(it.data) == null }
        assertEquals(4, data.size)
        fun dataFile(sequence: Long, partition: String) = data.single { it.sequenceNumber == sequence && it.partition?.path == partition }

        fun verdicts(file: GraphNode.FileNode) = deleteCandidatesFor(file, files)
            .associate { "${it.delete.sequenceNumber}/${it.delete.partition?.path}/${it.kind}" to it.verdict }

        // Commit 1's p=x file: its own commit's positional delete names it; commit 2's equality delete in
        // p=x may not touch it (id 4 against 1..1), and the one in p=y is another partition's.
        assertEquals(
            mapOf(
                "1/p=x/POSITIONAL" to DeleteReachVerdict.REACHES,
                "1/p=x/EQUALITY" to DeleteReachVerdict.RULED_OUT_BY_SEQUENCE,
                "1/p=y/EQUALITY" to DeleteReachVerdict.RULED_OUT_BY_SEQUENCE,
                "2/p=x/EQUALITY" to DeleteReachVerdict.RULED_OUT_BY_BOUNDS,
                "2/p=y/EQUALITY" to DeleteReachVerdict.RULED_OUT_BY_PARTITION,
            ),
            verdicts(dataFile(1, "p=x")),
        )
        // Commit 1's p=y file: commit 2's equality delete in p=y names key 2, and the file holds it.
        assertEquals(
            mapOf(
                "1/p=x/POSITIONAL" to DeleteReachVerdict.RULED_OUT_BY_TARGET,
                "1/p=x/EQUALITY" to DeleteReachVerdict.RULED_OUT_BY_SEQUENCE,
                "1/p=y/EQUALITY" to DeleteReachVerdict.RULED_OUT_BY_SEQUENCE,
                "2/p=x/EQUALITY" to DeleteReachVerdict.RULED_OUT_BY_PARTITION,
                "2/p=y/EQUALITY" to DeleteReachVerdict.MAY_REACH,
            ),
            verdicts(dataFile(1, "p=y")),
        )
        // Commit 2's files: nothing reaches them — the sequence rule alone settles every delete.
        for (partition in listOf("p=x", "p=y")) {
            val verdicts = verdicts(dataFile(2, partition))
            assertEquals(5, verdicts.size)
            assertTrue(verdicts.values.all { it == DeleteReachVerdict.RULED_OUT_BY_SEQUENCE || it == DeleteReachVerdict.RULED_OUT_BY_TARGET }, "$partition: $verdicts")
        }
    }

    @Test
    fun `from the delete files' end, one equality delete of commit 2 is dangling and the other reaches one file`() {
        val reach = deleteReach(current)
        val commit1y = liveFilesOf(current).single { it.content == 0 && it.partition == "p=y" && effectiveSeq(it) == 1L }
        val commit2 = reach.filter { it.kind == DeleteFileKind.EQUALITY && it.sequenceNumber == 2L }
        assertEquals(2, commit2.size)
        val (dangling, reaching) = commit2.partition { it.isDangling }
        assertEquals(1, dangling.size, "the p=x delete of key 4 applies to no earlier file: $commit2")
        assertEquals(listOf(tail(commit1y.path)), reaching.single().mayReach.map(::tail))
        assertTrue(reach.filter { it.sequenceNumber == 1L && it.kind == DeleteFileKind.EQUALITY }.all { it.isDangling })
    }

    @Test
    fun `the table reads as the three rows Flink read back`() {
        val input = assertNotNull(model.rowLookupInputOf(current, liveFilesOf(current), deleteReach(current)))
        val count = LiveRowCount.count(input)
        assertEquals(3L, count.live, "$count")
        assertEquals(0, count.failed + count.filesLeft)
    }

    private fun effectiveSeq(file: LiveFile): Long = current.manifests
        .flatMap { m -> m.dataFiles.map { m to it } }
        .first { (_, f) -> tail(f.metadata.dataFile?.filePath.orEmpty()) == tail(file.path) }
        .let { (m, f) -> effectiveSequenceNumber(f.metadata, m.metadata.sequenceNumber) }
}
