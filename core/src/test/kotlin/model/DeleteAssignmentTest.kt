package model

import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
