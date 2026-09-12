package model

import service.PaimonGraphBuilder
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `example/paimon/db.db/cl`: a changelog, an overwrite and an `ANALYZE` commit, written by Spark
 * from `docs/fixtures/paimon-cl.sql`, whose header carries the expected figures.
 *
 * Three things the model drew had never met real bytes before this table: the changelog manifest
 * list (a third list beside base and delta, excluded from `current` on purpose), the OVERWRITE and
 * ANALYZE commit kinds, and the `statistics` file an ANALYZE commit names — which `PaimonSnapshot`
 * parsed and dropped, the same shape of gap `indexManifest` had. The expected values are the
 * writer's own: each snapshot's `changelogRecordCount` and `deltaRecordCount`, the statistics
 * file's figures, and the sizes recorded for the manifest lists, all against what this code reads.
 */
class PaimonChangelogFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val tableDir = File(repoRoot, "example/paimon/db.db/cl")
    private val model = PaimonUnifiedTableModel(Paths.get(tableDir.absolutePath))

    private fun snapshot(id: Long) = model.snapshots.single { it.metadata.id == id }

    @Test
    fun `five commits of three kinds, and nothing fails to read`() {
        assertEquals(
            listOf("APPEND", "APPEND", "APPEND", "OVERWRITE", "ANALYZE"),
            model.snapshots.map { it.metadata.commitKind },
        )
        assertTrue(model.readErrors.isEmpty(), "table: ${model.readErrors}")
        assertTrue(model.snapshots.flatMap { it.readErrors }.isEmpty(), "snapshots: ${model.snapshots.flatMap { it.readErrors }}")
    }

    /**
     * The changelog manifest list exists on exactly the commits that produced a change stream, and
     * the rows its manifests list add up to the count the snapshot recorded.
     *
     * The overwrite has none: Paimon wrote a changelog file for it and then logged "Overwrite
     * mode currently does not commit any changelog" and left the file unlisted — see the orphan
     * test below. ANALYZE writes nothing at all.
     */
    @Test
    fun `the changelog manifest list appears on the commits that produced a change stream`() {
        assertEquals(
            listOf(true, true, true, false, false),
            model.snapshots.map { it.changelogManifests.isNotEmpty() },
        )
        assertEquals(listOf(3L, 2L, 1L, 0L, 0L), model.snapshots.map { it.metadata.changelogRecordCount })

        model.snapshots.filter { it.changelogManifests.isNotEmpty() }.forEach { snapshot ->
            val entries = snapshot.changelogManifests.flatMap { it.entries }
            assertTrue(entries.all { it.metadata.kind == PaimonEntryKind.ADD }, "a change stream only adds")
            assertTrue(
                entries.all { it.metadata.file?.fileName.orEmpty().startsWith("changelog-") },
                "changelog files, not data files: ${entries.map { it.metadata.file?.fileName }}",
            )
            assertEquals(
                snapshot.metadata.changelogRecordCount,
                entries.sumOf { it.metadata.file?.rowCount ?: 0L },
                "the writer's changelogRecordCount at snapshot ${snapshot.metadata.id}",
            )
        }
    }

    /**
     * The change stream is not the table's contents, and the two figures say so differently.
     *
     * `current` replays base and delta and never opens the changelog list, so after the overwrite
     * it holds one file of two rows. `history` counts everything reachable from any retained
     * snapshot — and the changelog files *are* reachable and retained until the snapshot expires,
     * so it holds seven files: four data files ever written and the three changelog files. That
     * is the stated definition rather than a leak, and it is pinned here so a change to it is a
     * decision.
     */
    @Test
    fun `current excludes the change stream and history counts it as retained`() {
        val summary = PaimonGraphBuilder.buildTableSummary(model)
        assertEquals(1, summary.current.dataFileCount)
        assertEquals(2L, summary.current.recordCount)
        assertEquals(snapshot(5).metadata.totalRecordCount, summary.current.recordCount)

        val dataFiles = model.snapshots.flatMap { it.baseManifests + it.deltaManifests }.flatMap { it.entries }
            .filter { it.metadata.kind == PaimonEntryKind.ADD }.map { it.metadata.file?.fileName }.toSet()
        val changelogFiles = model.snapshots.flatMap { it.changelogManifests }.flatMap { it.entries }
            .map { it.metadata.file?.fileName }.toSet()
        assertEquals(4, dataFiles.size)
        assertEquals(3, changelogFiles.size)
        assertEquals(dataFiles.size + changelogFiles.size, summary.history.dataFileCount)
        assertEquals(8L + 6L, summary.history.recordCount, "3+2+1+2 data rows and 3+2+1 change rows")
    }

    /**
     * An overwrite removes every file the table held and adds what it wrote, and the manifest's
     * contribution is the figure the writer recorded for the commit.
     */
    @Test
    fun `an overwrite removes every file the table held, and its contribution is the writer's delta`() {
        val overwrite = snapshot(4)
        val delta = overwrite.deltaManifests.single()
        val replay = replayPaimonSnapshot(overwrite, traceFor = paimonManifestKey(delta))

        assertEquals(
            listOf(PaimonEntryEffect.REMOVED, PaimonEntryEffect.REMOVED, PaimonEntryEffect.REMOVED, PaimonEntryEffect.ADDED),
            replay.trace.map { it.effect },
        )
        val contribution = replay.contributions.single { it.manifestPath == delta.path.toString() }
        assertEquals(-2, contribution.delta.dataFileCount, "three out, one in")
        assertEquals(-4L, contribution.delta.recordCount, "six rows out, two in")
        assertEquals(overwrite.metadata.deltaRecordCount, contribution.delta.recordCount)
        assertEquals(1, replay.liveFiles.size)
    }

    /**
     * Paimon wrote a changelog file for the overwrite and then did not list it.
     *
     * The container log says it in as many words — "Overwrite mode currently does not commit any
     * changelog … Ignored changelog files are: …" — and the table shows it: four `changelog-`
     * files in the bucket, three named by any manifest, and the fourth sharing its UUID with the
     * data file the overwrite added. An orphan file is exactly what an inspector exists to find,
     * so the fact is pinned against the fixture rather than left in a log nobody keeps.
     */
    @Test
    fun `the overwrite's changelog file is on disk and in no manifest`() {
        val onDisk = File(tableDir, "bucket-0").listFiles().orEmpty()
            .map { it.name }.filter { it.startsWith("changelog-") }.toSet()
        val listed = model.snapshots.flatMap { it.changelogManifests }.flatMap { it.entries }
            .mapNotNull { it.metadata.file?.fileName }.toSet()
        assertEquals(4, onDisk.size)
        assertEquals(3, listed.size)
        assertTrue(onDisk.containsAll(listed))

        val orphan = (onDisk - listed).single()
        val overwriteAdded = snapshot(4).deltaManifests.single().entries
            .single { it.metadata.kind == PaimonEntryKind.ADD }.metadata.file?.fileName
        assertNotNull(overwriteAdded)
        assertEquals(
            overwriteAdded.removePrefix("data-").substringBeforeLast('-'),
            orphan.removePrefix("changelog-").substringBeforeLast('-'),
            "the orphan was written by the same writer as the overwrite's data file",
        )
    }

    /**
     * An ANALYZE commit names a statistics file, and the file describes the snapshot before it.
     *
     * `mergedRecordCount` is the row count after the merge engine — the figure a scan returns and
     * the one the format records nowhere else — and here it equals the snapshot's file-row total
     * only because the overwrite left one file with no duplicated key. `mergedRecordSize` is the
     * bytes of that file, which is what `current` counts. The column figures were predicted in
     * the script: `k` is 10 and 11, and `v` gets lengths and a distinct count but no bounds,
     * because Paimon writes none for a string column.
     */
    @Test
    fun `an ANALYZE commit's statistics are read, and describe the snapshot it measured`() {
        assertEquals(listOf(false, false, false, false, true), model.snapshots.map { it.statistics != null })
        assertEquals(listOf(null, null, null, null, snapshot(5).metadata.statistics), model.snapshots.map { it.metadata.statistics })

        val stats = assertNotNull(snapshot(5).statistics)
        assertEquals(4L, stats.snapshotId, "measured at the snapshot before the ANALYZE commit")
        assertEquals(0L, stats.schemaId)
        assertEquals(2L, stats.mergedRecordCount)
        assertEquals(snapshot(5).metadata.totalRecordCount, stats.mergedRecordCount)
        assertEquals(PaimonGraphBuilder.buildTableSummary(model).current.dataSizeBytes, stats.mergedRecordSize)

        assertEquals(setOf("k", "v"), stats.colStats.keys)
        val k = stats.colStats.getValue("k")
        assertEquals(PaimonColStats(colId = 0, distinctCount = 2, min = "10", max = "11", nullCount = 0, avgLen = 4, maxLen = 4), k)
        val v = stats.colStats.getValue("v")
        assertEquals(PaimonColStats(colId = 1, distinctCount = 2, min = null, max = null, nullCount = 0, avgLen = 1, maxLen = 1), v)
        assertNull(v.min, "no bounds for a string column")
    }

    /**
     * The sizes a snapshot records for its manifest lists against the files on disk — the same
     * check as `manifestTallies`, on figures a planner budgets with without a stat.
     */
    @Test
    fun `the recorded manifest list sizes are the sizes on disk`() {
        val manifestDir = File(tableDir, "manifest")
        var checked = 0
        model.snapshots.forEach { snapshot ->
            val md = snapshot.metadata
            listOf(
                md.baseManifestList to md.baseManifestListSize,
                md.deltaManifestList to md.deltaManifestListSize,
                md.changelogManifestList to md.changelogManifestListSize,
            ).forEach { (name, recorded) ->
                if (name == null) {
                    assertNull(recorded, "no size without a list at snapshot ${md.id}")
                    return@forEach
                }
                checked++
                assertEquals(File(manifestDir, name).length(), recorded, "$name at snapshot ${md.id}")
            }
        }
        assertEquals(5 + 5 + 3, checked, "a base and a delta on every snapshot, a changelog on three")
    }
}
