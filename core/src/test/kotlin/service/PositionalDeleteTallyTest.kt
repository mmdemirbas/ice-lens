package service

import model.DataFileContent
import model.GraphNode
import model.UnifiedTableModel
import model.normalizeFilePath
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a positional delete file actually removes, read from the file and checked against the
 * figure the manifest recorded about it.
 *
 * This is the same shape as `manifestTallies` and the deletion vector's two recorded figures: a
 * number a scan trusts without opening the file, put beside the same number counted from the
 * bytes. Here the recorded figure is the manifest entry's `record_count`, which the spec defines
 * as the number of deleted positions the file holds, and the counted figure is what DuckDB reports
 * grouping the file's own rows. A scan plans against the recorded one and never opens the file, so
 * nothing on the read path would notice the two disagreeing.
 *
 * `mor` is the fixture with real positional deletes — written by Spark 3.5.5 / Iceberg 1.8.1, and
 * pinned to `--master local[1]` because under `local[2]` a `DELETE` matching every row of a file
 * removes the file outright and writes no delete at all.
 */
class PositionalDeleteTallyTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun morGraph() = GraphLayoutService.layoutGraph(
        UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/mor").absolutePath)),
        showRows = false,
    )

    /**
     * Every positional delete file drawn for `mor`, paired with the path that resolved on disk.
     *
     * Paired rather than returning the node alone because `localPath` is nullable — a node exists
     * for every file the metadata names, whether or not the file is here — and the whole point of
     * this test is reading bytes.
     */
    private fun positionalDeleteNodes(): List<Pair<GraphNode.FileNode, String>> =
        morGraph().nodes.filterIsInstance<GraphNode.FileNode>()
            .filter { it.data.content == DataFileContent.POSITION_DELETES && !it.isDeletionVector }
            .mapNotNull { node -> node.localPath?.takeIf { File(it).isFile }?.let { node to it } }

    @Test
    fun `a delete file's positions add up to the record count its manifest recorded`() {
        val nodes = positionalDeleteNodes()
        assertTrue(
            nodes.size >= 3,
            "the mor fixture should draw at least the three positional delete files Iceberg " +
                "reports for its current snapshot; got ${nodes.size}",
        )

        nodes.forEach { (node, path) ->
            val counted = SampleRowReader.queryPositionalDeleteTargets(path)
                .sumOf { it.positions }
            assertEquals(
                node.data.recordCount,
                counted,
                "${File(path).name}: the manifest records ${node.data.recordCount} " +
                    "deleted positions and the file holds $counted",
            )
        }
    }

    /**
     * Every position names a data file that exists in this table.
     *
     * A delete file pointing at nothing is the failure that makes a delete "dangling", and `mor`
     * has dangling deletes on purpose — but dangling means the *data file* left the table's
     * current snapshot, not that the path is nonsense. So the check is that the name matches a
     * data file the table has written at some point, which is what makes the tally readable as
     * "removes N rows from that file" rather than as an opaque string.
     */
    @Test
    fun `every targeted path names a data file the table wrote`() {
        val graph = morGraph()
        val known = graph.nodes.filterIsInstance<GraphNode.FileNode>()
            .mapNotNull { it.data.filePath?.let(::normalizeFilePath) }
            .toSet()
        assertTrue(known.isNotEmpty(), "the fixture should have data files to match against")

        val targets = positionalDeleteNodes()
            .flatMap { (_, path) -> SampleRowReader.queryPositionalDeleteTargets(path) }
        assertTrue(targets.isNotEmpty(), "three delete files should name at least one target")

        val unknown = targets.map { normalizeFilePath(it.dataFilePath) }.filterNot { it in known }
        assertEquals(emptyList(), unknown, "these delete targets name no file this table wrote")
    }

    /**
     * The bounds are the file's own, not the group's.
     *
     * Grouping by `file_path` and taking `min`/`max` of `pos` is only meaningful per group; a
     * single `min(pos)` over the whole file would be a different number that reads the same on a
     * fixture where every delete file targets exactly one data file — which is every delete file
     * in `mor`. So the invariant is asserted rather than inferred from the fixture's shape.
     */
    @Test
    fun `a tally's bounds contain its own positions and no more`() {
        positionalDeleteNodes().forEach { (_, path) ->
            SampleRowReader.queryPositionalDeleteTargets(path).forEach { tally ->
                assertTrue(
                    tally.lowestPosition <= tally.highestPosition,
                    "${tally.dataFilePath}: bounds ${tally.lowestPosition}..${tally.highestPosition} " +
                        "are inverted",
                )
                assertTrue(
                    tally.positions <= tally.highestPosition - tally.lowestPosition + 1,
                    "${tally.dataFilePath}: ${tally.positions} distinct positions cannot fit in " +
                        "${tally.lowestPosition}..${tally.highestPosition}",
                )
            }
        }
    }

    /** A data file is not a positional delete file, and asking says so rather than returning zero. */
    @Test
    fun `a plain data file has no file_path column to group by`() {
        val dataFile = morGraph().nodes.filterIsInstance<GraphNode.FileNode>()
            .filter { it.data.content == DataFileContent.DATA }
            .firstNotNullOf { node -> node.localPath?.takeIf { File(it).isFile } }

        val failure = runCatching { SampleRowReader.queryPositionalDeleteTargets(dataFile) }
            .exceptionOrNull()
        assertTrue(
            failure != null,
            "a data file has no file_path/pos columns, so this has to fail rather than report " +
                "that the file deletes nothing — an empty result would read as a true answer",
        )
    }

    /**
     * The live row count, which nothing in this app could produce before.
     *
     * `mor`'s compacted data file records 6 rows and the table holds 5 — `MergeOnReadFixtureTest`
     * pins that the obvious subtraction, `record_count - deleteRecordCount`, gives 3 instead,
     * because two of the three delete files are dangling and delete rows no live file has.
     * Counting the positions that actually land in *this* file gives 1, and 6 - 1 is the 5 the
     * table has. That equality is the oracle: two numbers from different sources meeting on a
     * figure neither of them computed.
     */
    @Test
    fun `deleted positions in one data file give the live row count the subtraction cannot`() {
        val graph = morGraph()
        val files = graph.nodes.filterIsInstance<GraphNode.FileNode>()
        val compacted = files.single { node ->
            model.deleteKindOf(node.data) == null &&
                node.data.filePath.orEmpty().contains("8bb56de0")
        }
        val candidates = model.deleteCandidatesFor(compacted, files)
            .filter { it.verdict != model.DeleteReachVerdict.RULED_OUT_BY_TARGET }
            .filter { it.verdict != model.DeleteReachVerdict.RULED_OUT_BY_SEQUENCE }
            .mapNotNull { it.delete.localPath }
        assertEquals(1, candidates.size, "one delete file reaches the compacted file")

        val deleted = SampleRowReader.queryDeletedRowCount(candidates, compacted.data.filePath.orEmpty())
        assertEquals(1L, deleted, "one position lands in this file")
        assertEquals(6L, compacted.data.recordCount, "the file records six rows before deletes")
        assertEquals(5L, (compacted.data.recordCount ?: 0L) - deleted, "which is the table's live row count")
    }

    /**
     * The count is over the union of the candidates, not their sum.
     *
     * Passing the same delete file twice is the cheapest way to exercise that: a sum would report
     * two positions where the file marks one, and a row counted twice is a number that can exceed
     * the row count it is subtracted from.
     */
    @Test
    fun `the same position counted through two delete files is one deleted row`() {
        val graph = morGraph()
        val files = graph.nodes.filterIsInstance<GraphNode.FileNode>()
        val compacted = files.single { node ->
            model.deleteKindOf(node.data) == null &&
                node.data.filePath.orEmpty().contains("8bb56de0")
        }
        val one = model.deleteCandidatesFor(compacted, files)
            .single { it.verdict == model.DeleteReachVerdict.REACHES }
            .delete.localPath
        requireNotNull(one)
        assertEquals(
            1L,
            SampleRowReader.queryDeletedRowCount(listOf(one, one), compacted.data.filePath.orEmpty()),
        )
    }

    /** No candidates is zero rows deleted, without opening anything. */
    @Test
    fun `no delete files is no deleted rows`() {
        assertEquals(0L, SampleRowReader.queryDeletedRowCount(emptyList(), "anything"))
    }
}
