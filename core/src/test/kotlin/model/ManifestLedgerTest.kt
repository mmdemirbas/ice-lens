package model

import service.GraphLayoutService
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The per-entry ledger, and the claim that it is the same computation the table's totals are
 * folded from rather than a second one that agrees today.
 *
 * The last test here is the one that matters: it replays a real table's traversal through the
 * ledger and requires every manifest's delta to come out identical. A drill-down computed
 * separately would pass a unit test and drift from the number it is supposed to explain.
 */
class ManifestLedgerTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun entry(path: String, status: Int, rows: Long, bytes: Long, content: Int = DataFileContent.DATA) =
        LedgerEntry(
            fileKey = path,
            status = status,
            dataFile = DataFile(filePath = path, recordCount = rows, fileSizeInBytes = bytes, content = content),
        )

    @Test
    fun `a removal keeps its place in the entry count and adds nothing else`() {
        val ledger = manifestLedger(
            listOf(
                entry("/wh/a.parquet", ManifestEntryStatus.ADDED, rows = 10, bytes = 1_000),
                entry("/wh/b.parquet", ManifestEntryStatus.DELETED, rows = 7, bytes = 700),
            ),
            liveEntriesOnly = true,
        )
        val total = ledger.total()

        assertEquals(listOf(EntryFate.COUNTED, EntryFate.REMOVAL), ledger.map { it.fate })
        assertEquals(2, total.manifestEntryCount, "both entries cost a scan a read")
        assertEquals(1, total.deletedEntryCount)
        assertEquals(1, total.dataFileCount, "the removed file is not in the table")
        assertEquals(10L, total.recordCount)
        assertEquals(1_000L, total.dataSizeBytes)
        assertEquals(
            7L, ledger.last().recordCount,
            "the removal's own record count survives on the row, so the drill-down can say what left",
        )
    }

    @Test
    fun `without the live-entries rule a removal still counts the file it names`() {
        val entries = listOf(
            entry("/wh/a.parquet", ManifestEntryStatus.ADDED, rows = 10, bytes = 1_000),
            entry("/wh/b.parquet", ManifestEntryStatus.DELETED, rows = 7, bytes = 700),
        )
        val total = manifestLedger(entries, liveEntriesOnly = false).total()

        assertEquals(2, total.dataFileCount, "'what is still on disk' counts both")
        assertEquals(17L, total.recordCount)
        assertEquals(1, total.deletedEntryCount, "and still records that one of them is a removal")
    }

    @Test
    fun `a data file listed by two manifests is counted once`() {
        val shared = mutableSetOf<String>()
        val first = manifestLedger(
            listOf(entry("/wh/a.parquet", ManifestEntryStatus.ADDED, rows = 10, bytes = 1_000)),
            liveEntriesOnly = true, seenFileKeys = shared,
        )
        val second = manifestLedger(
            listOf(entry("/wh/a.parquet", ManifestEntryStatus.EXISTING, rows = 10, bytes = 1_000)),
            liveEntriesOnly = true, seenFileKeys = shared,
        )

        assertEquals(EntryFate.COUNTED, first.single().fate)
        assertEquals(EntryFate.DUPLICATE, second.single().fate)
        assertEquals(0, second.total().dataFileCount)
        assertEquals(
            1, second.total().manifestEntryCount,
            "a duplicate still has to be read, so it still costs an entry",
        )
    }

    @Test
    fun `delete-file rows never land in the table's record count`() {
        val total = manifestLedger(
            listOf(
                entry("/wh/a.parquet", ManifestEntryStatus.ADDED, rows = 10, bytes = 1_000),
                entry("/wh/d.parquet", ManifestEntryStatus.ADDED, rows = 3, bytes = 300, content = DataFileContent.POSITION_DELETES),
                entry("/wh/e.parquet", ManifestEntryStatus.ADDED, rows = 2, bytes = 200, content = DataFileContent.EQUALITY_DELETES),
            ),
            liveEntriesOnly = true,
        ).total()

        assertEquals(10L, total.recordCount, "a delete file's record_count is deletes, not table rows")
        assertEquals(5L, total.deleteRecordCount)
        assertEquals(1, total.dataFileCount)
        assertEquals(1, total.posDeleteFileCount)
        assertEquals(1, total.eqDeleteFileCount)
        assertEquals(1_000L, total.dataSizeBytes)
        assertEquals(500L, total.deleteSizeBytes)
    }

    /**
     * Replays `mor`'s history traversal through the ledger and requires the same numbers.
     *
     * `mor` is the fixture with something to get wrong here: six commits, three positional
     * delete files and a compaction, so its manifests re-list each other's files and the
     * deduplication is doing real work rather than passing through.
     */
    @Test
    fun `the ledger reproduces every manifest contribution the table folded`() {
        val tableDir = File(repoRoot, "example/iceberg/default/mor")
        assertTrue(tableDir.isDirectory, "mor fixture missing at $tableDir")
        val graph = GraphLayoutService.layoutGraph(
            UnifiedTableModel(Paths.get(tableDir.absolutePath)), showRows = false,
        )
        val summary = graph.nodes.filterIsInstance<GraphNode.TableNode>().single().summary
        // Keyed on where the file was actually opened, not on what the manifest list recorded:
        // the derivation names the resolved path, and the two differ for a table copied down
        // from object storage — which is the layout every checked-in fixture has.
        val manifestsByPath = graph.nodes.filterIsInstance<GraphNode.ManifestNode>()
            .associateBy { normalizeFilePath(it.localPath ?: it.data.manifestPath ?: "") }

        val seen = mutableSetOf<String>()
        var replayed = 0
        summary.historyDerivation.contributions.forEach { contribution ->
            if (contribution.isRepeat) return@forEach
            val node = manifestsByPath[normalizeFilePath(contribution.manifestPath)]
                ?: error("no manifest node for ${contribution.manifestPath}")
            val isDeleteManifest = node.data.content == ManifestContent.DELETES
            val seed = ContentStats(
                dataManifestCount = if (isDeleteManifest) 0 else 1,
                deleteManifestCount = if (isDeleteManifest) 1 else 0,
            )
            val ledger = manifestLedger(
                entries = node.entries.map { view ->
                    LedgerEntry(
                        fileKey = view.entry.dataFile?.filePath?.takeIf { it.isNotBlank() }
                            ?.let(::normalizeFilePath) ?: "path:${view.localPath}",
                        status = view.entry.status,
                        dataFile = view.entry.dataFile,
                    )
                },
                // history is every referenced file, not only the live ones.
                liveEntriesOnly = false,
                seenFileKeys = seen,
            )
            assertEquals(
                contribution.delta,
                ledger.fold(seed) { running, e -> running + e.delta },
                "the ledger disagrees with the contribution for ${contribution.manifestPath}",
            )
            assertEquals(
                contribution.entriesSuppressedAsDuplicate,
                ledger.count { it.fate == EntryFate.DUPLICATE },
                "the duplicate count disagrees for ${contribution.manifestPath}",
            )
            replayed++
        }
        assertTrue(replayed > 2, "only $replayed manifests replayed — the fixture is not exercising this")
        assertTrue(
            summary.historyDerivation.entriesSuppressedAsDuplicate > 0,
            "mor should re-list files across manifests, or deduplication is untested here",
        )
    }
}
