package model

import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The manifest list's counts against the entries they summarise.
 *
 * The first test is an oracle in both directions, which is why it is worth more than its size
 * suggests. Every checked-in table was written by real Spark/Iceberg, so its `manifest_file`
 * counts are Iceberg's own arithmetic over the entries it had just written — and this code
 * re-derives them from the Avro it decoded independently. A disagreement means either that the
 * decode is wrong (a status misread, an entry dropped, a `record_count` read at the wrong width)
 * or that the writer's summary is. Neither is visible from either number on its own, and this is
 * the only assertion in the suite that compares what we read against what Iceberg recorded about
 * the same bytes.
 *
 * It covers every fixture rather than one, because the shapes that break a count are the ones the
 * minimal table does not have: deletes with a status of DELETED, a compaction that leaves entries
 * EXISTING, two partition specs, ten metadata versions.
 */
class ManifestTallyTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun `every checked-in table's manifest list agrees with its own entries`() {
        val fixtures = File(repoRoot, "example/iceberg/default").listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            .orEmpty()
        assertTrue(fixtures.size >= 8, "expected the checked-in Iceberg fixtures, found $fixtures")

        val disagreements = mutableListOf<String>()
        var compared = 0
        fixtures.forEach { tableDir ->
            val model = UnifiedTableModel(Paths.get(tableDir.absolutePath))
            model.metadatas.asSequence()
                .flatMap { it.snapshots.asSequence() }
                .flatMap { it.manifests.asSequence() }
                .distinctBy { it.metadata.manifestPath }
                .forEach { manifest ->
                    val entries = manifest.dataFiles.map { it.metadata }
                    // The seventh figure is the file's own length; every manifest here is on disk.
                    assertTrue(manifest.sizeOnDisk != null, "${tableDir.name} ${manifest.path} not stat-ed")
                    manifestTallies(manifest.metadata, entries, manifest.sizeOnDisk).forEach { tally ->
                        if (tally.agrees == null) return@forEach
                        compared++
                        if (tally.agrees == false) {
                            disagreements += "${tableDir.name} " +
                                "${manifest.metadata.manifestPath?.substringAfterLast('/')}: " +
                                "${tally.label} recorded ${tally.recorded}, counted ${tally.counted}"
                        }
                    }
                }
        }

        // Printed rather than pinned: this is how much the oracle actually covers, and a new
        // fixture should raise it without failing anything.
        println("Compared $compared recorded figures across ${fixtures.size} tables")
        assertTrue(compared > 100, "only $compared figures were recorded across the fixtures")
        assertTrue(disagreements.isEmpty(), "the manifest list and the entries disagree:\n" +
            disagreements.joinToString("\n"))
    }

    @Test
    fun `the manifest's length is a tally against the file rather than the entries`() {
        val manifest = UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/mor").absolutePath))
            .metadatas.last().snapshots.last().manifests.first()
        val recorded = manifest.metadata.manifestLength!!
        assertEquals(recorded, manifest.sizeOnDisk)

        val length = manifestTallies(manifest.metadata, manifest.dataFiles.map { it.metadata }, manifest.sizeOnDisk).single { it.label == "Manifest length" }
        assertEquals(true, length.agrees)
        // Off by one either way is a length the reader would open the file at and fail — a disagreement, not a rounding.
        val short = manifestTallies(manifest.metadata, manifest.dataFiles.map { it.metadata }, recorded - 1).single { it.label == "Manifest length" }
        assertEquals(false, short.agrees)
        assertEquals(recorded - 1, short.counted)
        // A file that could not be measured adds no tally: there is no length figure to be wrong about.
        assertEquals(7, manifestTallies(manifest.metadata, manifest.dataFiles.map { it.metadata }).size)
    }

    /**
     * `min_sequence_number` is the figure the next commit prunes delete files by, so it is the
     * lowest number among the *live* entries: a rewritten manifest carries older files under a
     * newer number, a DELETED-only manifest has no live entry and takes the commit's own, and a
     * figure above the oldest live file's would let a delete that still reaches it be dropped.
     */
    @Test
    fun `min_sequence_number is the lowest live entry's, the manifest's own where none is live, and a raised one is named`() {
        val maint = UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/maint").absolutePath)).metadatas.last().snapshots.last()
        val rewritten = maint.manifests.first { m -> m.dataFiles.any { it.metadata.status == ManifestEntryStatus.EXISTING && it.metadata.sequenceNumber != null } }
        val liveMin = rewritten.dataFiles.filter { it.metadata.status != ManifestEntryStatus.DELETED }.minOf { effectiveSequenceNumber(it.metadata, rewritten.metadata.sequenceNumber) }
        assertTrue(liveMin < rewritten.metadata.effectiveSequenceNumber, "a rewritten manifest lists files older than itself")
        val tally = manifestTallies(rewritten.metadata, rewritten.dataFiles.map { it.metadata }).single { it.label == "Min sequence number" }
        assertEquals(liveMin, tally.counted)
        assertEquals(true, tally.agrees)

        val raised = manifestTallies(rewritten.metadata.copy(minSequenceNumber = liveMin + 1), rewritten.dataFiles.map { it.metadata }).single { it.label == "Min sequence number" }
        assertEquals(false, raised.agrees)

        // `lineage`'s DELETED-only manifests: the writer assigns the commit's number where no live entry recorded one.
        val deletedOnly = UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/lineage").absolutePath)).metadatas.last().snapshots
            .flatMap { it.manifests }.first { m -> m.dataFiles.isNotEmpty() && m.dataFiles.all { it.metadata.status == ManifestEntryStatus.DELETED } }
        val own = manifestTallies(deletedOnly.metadata, deletedOnly.dataFiles.map { it.metadata }).single { it.label == "Min sequence number" }
        assertEquals(deletedOnly.metadata.effectiveSequenceNumber, own.counted)
        assertEquals(true, own.agrees)

        // A v1 list records none, and the v1 rule reads every number as 0: nothing to compare.
        assertNull(manifestTallies(ManifestListEntry(manifestPath = "m.avro"), listOf(added(rows = 1))).single { it.label == "Min sequence number" }.agrees)
    }

    @Test
    fun `a count the writer did not record is not a disagreement`() {
        val tallies = manifestTallies(ManifestListEntry(manifestPath = "m.avro"), listOf(added(rows = 7)))

        assertTrue(tallies.all { it.recorded == null }, "v1 leaves every count optional")
        tallies.forEach { assertNull(it.agrees, "${it.label} has nothing to agree with") }
        assertEquals(1L, tallies.first { it.label == "Added files" }.counted)
        assertEquals(7L, tallies.first { it.label == "Added rows" }.counted)
    }

    @Test
    fun `a recorded count that the entries contradict does not agree`() {
        val recorded = ManifestListEntry(
            manifestPath = "m.avro",
            addedFilesCount = 3,
            addedRowsCount = 300,
            existingFilesCount = 0,
        )
        val tallies = manifestTallies(recorded, listOf(added(rows = 100), added(rows = 100)))

        assertEquals(false, tallies.first { it.label == "Added files" }.agrees)
        assertEquals(2L, tallies.first { it.label == "Added files" }.counted)
        assertEquals(false, tallies.first { it.label == "Added rows" }.agrees)
        assertEquals(true, tallies.first { it.label == "Existing files" }.agrees)
    }

    /** Rows are counted per status, so an entry of another status must not reach the total. */
    @Test
    fun `rows are attributed to the status of their own entry`() {
        val entries = listOf(
            added(rows = 10),
            ManifestEntry(status = ManifestEntryStatus.EXISTING, dataFile = DataFile(recordCount = 20)),
            ManifestEntry(status = ManifestEntryStatus.DELETED, dataFile = DataFile(recordCount = 40)),
        )
        val tallies = manifestTallies(ManifestListEntry(), entries)

        assertEquals(10L, tallies.first { it.label == "Added rows" }.counted)
        assertEquals(20L, tallies.first { it.label == "Existing rows" }.counted)
        assertEquals(40L, tallies.first { it.label == "Deleted rows" }.counted)
    }

    private fun added(rows: Long) =
        ManifestEntry(status = ManifestEntryStatus.ADDED, dataFile = DataFile(recordCount = rows))
}
