package model

import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which delete files a scan would pair with which data files, from the metadata alone.
 *
 * The strongest thing here is that `mor` was already *documented* as having two dangling deletes —
 * `MergeOnReadFixtureTest` explains that "after the compaction two of this table's three delete
 * files are dangling" and pins the consequence, that records minus delete-records is not the live
 * row count. Nothing asserted the claim itself, because nothing could compute it. This does, from a
 * completely different direction: the delete files' own recorded `file_path` bounds against the
 * live data files' paths.
 */
class DeleteAssignmentTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun currentSnapshot(name: String): UnifiedSnapshot =
        UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/$name").absolutePath))
            .metadatas.last().snapshots.last()

    private fun tail(path: String) = path.substringAfterLast('/')

    /**
     * `mor` after its compaction: one live data file and three delete files, two of which point at
     * data that the compaction rewrote away.
     *
     * The one that still applies does so by a proof rather than by an absence — its bounds meet on
     * the surviving file's path, so the metadata names it without the delete file being opened.
     */
    @Test
    fun `two of mor's three delete files reach nothing, which is what dangling means`() {
        val reach = deleteReach(currentSnapshot("mor"))
        assertEquals(3, reach.size, "three delete files")
        assertEquals(2, reach.count { it.isDangling }, "the two the compaction left behind")

        val live = reach.single { !it.isDangling }
        assertEquals(6L, live.sequenceNumber)
        assertEquals(DeleteFileKind.POSITIONAL, live.kind)
        assertEquals(1, live.reaches.size)
        assertTrue(live.mayReach.isEmpty(), "the bounds meet, so nothing is left unsettled")
        assertEquals(
            "00000-8-8bb56de0-bda3-4465-be13-0a3f8fd26149-0-00001.parquet",
            tail(live.reaches.single()),
        )
        assertTrue(live.targets.namesOneFile, "lower and upper bound are the same path here")
    }

    /**
     * A v3 deletion vector names its data file outright, so the pairing needs no bounds at all.
     *
     * `v3` carries two of them, and neither is dangling — which is the other half of the check,
     * since a rule that returns "dangling" for everything would pass the `mor` assertion above.
     */
    @Test
    fun `a deletion vector reaches the one file it names`() {
        val reach = deleteReach(currentSnapshot("v3"))
        assertEquals(2, reach.size)
        assertTrue(reach.all { it.kind == DeleteFileKind.DELETION_VECTOR })
        assertTrue(reach.none { it.isDangling }, "both vectors name a file that is still here")
        assertEquals(listOf(1, 1), reach.map { it.reaches.size })
        reach.forEach { vector ->
            assertEquals(
                tail(vector.targets.referenced.orEmpty()),
                tail(vector.reaches.single()),
                "a vector reaches exactly what it references",
            )
        }
    }

    /**
     * An equality delete has no target, so every candidate stays unsettled — and unsettled is not
     * dangling.
     *
     * The distinction is the whole point of carrying both lists: "the metadata ruled every data
     * file out" and "the metadata could not rule any of them out" would otherwise both read as an
     * empty `reaches`, and only the first means the delete file is doing nothing.
     */
    @Test
    fun `an equality delete rules nothing out and is not therefore dangling`() {
        val equality = deleteReach(currentSnapshot("eqdel"))
            .single { it.kind == DeleteFileKind.EQUALITY }
        assertTrue(equality.reaches.isEmpty(), "no target means no proof that it reaches anything")
        assertTrue(equality.mayReach.isNotEmpty(), "and no proof that it does not")
        assertTrue(!equality.isDangling)
    }

    /**
     * The sequence rule, which is the half no path comparison would catch.
     *
     * An equality delete applies strictly below its own number and a positional one at or below,
     * because a positional delete addresses rows that already exist by position and may be written
     * in the same commit as the data it deletes from. Asserted directly on the rule rather than
     * hunting for a fixture that happens to exercise it — none of the eight has an equality delete
     * at the same sequence number as a data file.
     */
    @Test
    fun `an equality delete does not reach data written in its own commit, and a positional one does`() {
        val targets = DeleteTargets()
        assertEquals(
            DeleteReachVerdict.RULED_OUT_BY_SEQUENCE,
            reachVerdict(DeleteFileKind.EQUALITY, deleteSequence = 4, targets, "data/f.parquet", dataSequence = 4),
        )
        assertEquals(
            DeleteReachVerdict.MAY_REACH,
            reachVerdict(DeleteFileKind.POSITIONAL, deleteSequence = 4, targets, "data/f.parquet", dataSequence = 4),
        )
        assertEquals(
            DeleteReachVerdict.RULED_OUT_BY_SEQUENCE,
            reachVerdict(DeleteFileKind.POSITIONAL, deleteSequence = 3, targets, "data/f.parquet", dataSequence = 4),
        )
    }

    @Test
    fun `a delete is keyed by spec and partition unless it names a file or is a global equality delete`() {
        val targets = DeleteTargets()
        val x0 = PartitionScope(0, "p=x")
        val y0 = PartitionScope(0, "p=y")
        val x1 = PartitionScope(1, "p=x")
        val none = PartitionScope(0, "")
        fun v(kind: DeleteFileKind, delete: PartitionScope, data: PartitionScope, t: DeleteTargets = targets) =
            reachVerdict(kind, deleteSequence = 5, t, "data/f.parquet", dataSequence = 4, deleteScope = delete, dataScope = data)
        assertEquals(DeleteReachVerdict.RULED_OUT_BY_PARTITION, v(DeleteFileKind.EQUALITY, y0, x0))
        assertEquals(DeleteReachVerdict.RULED_OUT_BY_PARTITION, v(DeleteFileKind.EQUALITY, x1, x0), "same tuple, another spec")
        assertEquals(DeleteReachVerdict.MAY_REACH, v(DeleteFileKind.EQUALITY, x0, x0))
        assertEquals(DeleteReachVerdict.MAY_REACH, v(DeleteFileKind.EQUALITY, none, x0), "an unpartitioned equality delete is global")
        assertEquals(DeleteReachVerdict.RULED_OUT_BY_PARTITION, v(DeleteFileKind.POSITIONAL, none, x0), "a positional one is not")
        assertEquals(DeleteReachVerdict.RULED_OUT_BY_PARTITION, v(DeleteFileKind.POSITIONAL, y0, x0))
        // A vector, or a positional delete that names one file, is keyed by path and the partition is not asked.
        assertEquals(DeleteReachVerdict.REACHES, v(DeleteFileKind.POSITIONAL, y0, x0, DeleteTargets(low = "data/f.parquet", high = "data/f.parquet")))
        assertEquals(DeleteReachVerdict.REACHES, v(DeleteFileKind.DELETION_VECTOR, y0, x0, DeleteTargets(referenced = "data/f.parquet")))
        // A partition this could not decode is not compared.
        assertEquals(DeleteReachVerdict.MAY_REACH, v(DeleteFileKind.EQUALITY, y0, PartitionScope(0, null)))
    }

    @Test
    fun `an equality delete whose bounds cannot meet the file's is ruled out, and a missing figure means it may`() {
        fun stats(id: Int, low: Any?, high: Any?, values: Long? = 3, nulls: Long? = 0) = ColumnStats(
            fieldId = id, columnName = "id", type = IcebergType.IntType,
            lowerBound = low?.let { DecodedValue(it.toString(), it, IcebergType.IntType, ByteArray(0)) },
            upperBound = high?.let { DecodedValue(it.toString(), it, IcebergType.IntType, ByteArray(0)) },
            valueCount = values, nullValueCount = nulls, nanValueCount = null, columnSizeBytes = null,
        )
        val data = listOf(stats(1, 1, 1))
        assertFalse(equalityDeleteMayTouch(data, listOf(stats(1, 4, 4)), listOf(1), emptySet()), "4..4 against 1..1")
        assertTrue(equalityDeleteMayTouch(data, listOf(stats(1, 0, 2)), listOf(1), emptySet()))
        assertTrue(equalityDeleteMayTouch(data, listOf(stats(1, null, null)), listOf(1), emptySet()), "no bound on the delete")
        assertTrue(equalityDeleteMayTouch(listOf(stats(1, null, null)), listOf(stats(1, 4, 4)), listOf(1), emptySet()), "no bound on the data")
        assertTrue(equalityDeleteMayTouch(data, listOf(stats(1, 4, 4)), listOf(2), emptySet()), "a column with no statistics on either side")
        // Nulls: a data file all-null on the column against a delete holding no null, and the reverse.
        assertFalse(equalityDeleteMayTouch(listOf(stats(1, null, null, values = 3, nulls = 3)), listOf(stats(1, 4, 4, nulls = 0)), listOf(1), emptySet()))
        assertFalse(equalityDeleteMayTouch(listOf(stats(1, 1, 1, nulls = 0)), listOf(stats(1, null, null, values = 2, nulls = 2)), listOf(1), emptySet()))
        assertTrue(equalityDeleteMayTouch(listOf(stats(1, 1, 1, nulls = 1)), listOf(stats(1, 4, 4, nulls = 1)), listOf(1), emptySet()), "both hold a null, so the deletes apply")
        // A required column is never null, whatever the counts say.
        assertFalse(equalityDeleteMayTouch(data, listOf(stats(1, 4, 4, nulls = null)), listOf(1), setOf(1)))
        val verdict = reachVerdict(DeleteFileKind.EQUALITY, 5, DeleteTargets(), "data/f.parquet", 4, equalityMayTouch = { false })
        assertEquals(DeleteReachVerdict.RULED_OUT_BY_BOUNDS, verdict)
    }

    /** A path outside the recorded range is ruled out; one inside it is only unsettled. */
    @Test
    fun `bounds that span rule out what falls outside them and prove nothing inside`() {
        val targets = DeleteTargets(low = "data/b.parquet", high = "data/d.parquet")
        fun verdict(path: String) =
            reachVerdict(DeleteFileKind.POSITIONAL, deleteSequence = 9, targets, path, dataSequence = 1)

        assertEquals(DeleteReachVerdict.RULED_OUT_BY_TARGET, verdict("data/a.parquet"))
        assertEquals(DeleteReachVerdict.RULED_OUT_BY_TARGET, verdict("data/e.parquet"))
        assertEquals(DeleteReachVerdict.MAY_REACH, verdict("data/c.parquet"))
    }

    /**
     * Nothing is claimed about a table that has no delete files at all.
     *
     * Worth pinning because an empty result is also what a broken walk returns, and six of the
     * eight fixtures are in exactly this state.
     */
    @Test
    fun `a table with no deletes reports no reach at all`() {
        listOf("test", "parted", "evolved", "respec", "branched").forEach { name ->
            assertEquals(emptyList(), deleteReach(currentSnapshot(name)), name)
        }
    }

    /**
     * The same pairing asked from the data file's end, over the graph rather than a closure.
     *
     * `mor`'s compacted file is the one every count in the panel is about, and two of the three
     * delete files drawn for the table have nothing to do with it. That is the whole reason this
     * section exists: they are drawn under other manifests, and nothing on screen said which of
     * them mattered.
     *
     * The two are excluded for **different reasons**, which was not what this test first expected.
     * One was written before the compaction produced this file, so no path comparison is even
     * reached — the sequence rule settles it. The other is contemporary and is ruled out by its
     * recorded target. One fixture, both exclusions, which is what makes the pair worth asserting
     * as a distribution rather than as a count.
     */
    @Test
    fun `a data file lists the delete files that can reach it, and why the others cannot`() {
        val graph = service.GraphLayoutService.layoutGraph(
            UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/mor").absolutePath)),
            showRows = false,
        )
        val files = graph.nodes.filterIsInstance<GraphNode.FileNode>()
        val compacted = files.single {
            deleteKindOf(it.data) == null &&
                it.data.filePath.orEmpty().endsWith("8bb56de0-bda3-4465-be13-0a3f8fd26149-0-00001.parquet")
        }

        val candidates = deleteCandidatesFor(compacted, files)
        assertEquals(3, candidates.size, "every delete file drawn is weighed")
        assertEquals(
            mapOf(
                DeleteReachVerdict.REACHES to 1,
                DeleteReachVerdict.RULED_OUT_BY_SEQUENCE to 1,
                DeleteReachVerdict.RULED_OUT_BY_TARGET to 1,
            ),
            candidates.groupingBy { it.verdict }.eachCount(),
        )
        val reaching = candidates.single { it.verdict == DeleteReachVerdict.REACHES }
        assertEquals(6L, reaching.delete.sequenceNumber)
        assertEquals(DeleteFileKind.POSITIONAL, reaching.kind)
    }

    /** Asked of a delete file, the answer is nothing: it is not a data file and pairs with none. */
    @Test
    fun `a delete file has no candidates of its own`() {
        val graph = service.GraphLayoutService.layoutGraph(
            UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/mor").absolutePath)),
            showRows = false,
        )
        val files = graph.nodes.filterIsInstance<GraphNode.FileNode>()
        val delete = files.first { deleteKindOf(it.data) != null }
        assertEquals(emptyList(), deleteCandidatesFor(delete, files))
    }

    /** Both directions agree on `mor`: the file the snapshot pairing found is the one this finds. */
    @Test
    fun `the two directions name the same pair`() {
        val model = UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/mor").absolutePath))
        val fromSnapshot = deleteReach(model.metadatas.last().snapshots.last())
            .single { it.reaches.isNotEmpty() }

        val graph = service.GraphLayoutService.layoutGraph(model, showRows = false)
        val files = graph.nodes.filterIsInstance<GraphNode.FileNode>()
        val target = files.single {
            deleteKindOf(it.data) == null &&
                normalizeFilePath(it.data.filePath.orEmpty()) == fromSnapshot.reaches.single()
        }
        val fromFile = deleteCandidatesFor(target, files)
            .single { it.verdict == DeleteReachVerdict.REACHES }
        assertEquals(
            fromSnapshot.deletePath,
            normalizeFilePath(fromFile.delete.data.filePath.orEmpty()),
            "the snapshot walk and the per-file pairing must name one relationship, not two",
        )
    }
}
