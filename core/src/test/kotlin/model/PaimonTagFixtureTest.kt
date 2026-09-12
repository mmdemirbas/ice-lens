package model

import service.GraphLayoutService
import service.PaimonGraphBuilder
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `example/paimon/db.db/tg`: a tag on a snapshot that `expire_snapshots` has since removed,
 * written by Spark from `docs/fixtures/paimon-tg.sql`.
 *
 * A Paimon tag is a copy of the snapshot file under `tag/`, and it is what keeps the snapshot's
 * files on disk after the snapshot itself is gone from `snapshot/`. Two things follow that a reader
 * of `snapshot/` alone gets wrong, and this table is the oracle for both: the tagged snapshot's data
 * file is on disk and referenced, so it is **not** an orphan; and the tagged snapshot is part of the
 * table's retained history, so it is drawn. The fixture also settled something the script did not
 * predict — **a tag retains data, not changelog**: expiry deleted the changelog manifest list the tag
 * still names, so reading the tag reports exactly that as a read error.
 */
class PaimonTagFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val tableDir = File(repoRoot, "example/paimon/db.db/tg")
    private val model = PaimonUnifiedTableModel(Paths.get(tableDir.absolutePath))

    @Test
    fun `snapshot dir holds the survivor, and the tag holds an expired snapshot`() {
        assertEquals(listOf(4L), model.snapshots.map { it.metadata.id }, "retain_max = 1 left one snapshot")
        assertEquals(listOf("first"), model.tags.map { it.name })
        assertEquals(1L, model.tags.single().snapshot.metadata.id)
        assertEquals("APPEND", model.tags.single().snapshot.metadata.commitKind)
        assertEquals(mapOf(1L to listOf("first")), model.tagNamesBySnapshotId)
        assertEquals(listOf(1L), model.tagOnlySnapshots.map { it.metadata.id })
        assertTrue(model.readErrors.isEmpty(), "table-level: ${model.readErrors}")
    }

    /**
     * The tag keeps snapshot 1's data file and manifests; it did not keep its changelog.
     *
     * Snapshot 1's data and delta manifest resolve to files on disk. Its changelog manifest list —
     * which the tag file still names — is gone, and that surfaces as the tag's one read error.
     * Snapshots 2 and 3, tagged by nothing, lost their data files entirely; snapshot 4's base
     * manifests still list them, so their paths resolve to the fallback and nothing is there.
     */
    @Test
    fun `a tag retains the snapshot's data and not its changelog`() {
        val tagged = model.tags.single().snapshot
        val data = tagged.deltaManifests.flatMap { it.entries }
        assertEquals(1, data.size)
        assertTrue(Files.isRegularFile(data.single().path), "the tagged snapshot's data file is on disk: ${data.single().path}")
        assertTrue(tagged.deltaManifests.all { Files.isRegularFile(it.path) })

        assertTrue(tagged.metadata.changelogManifestList != null, "the tag file still names a changelog list")
        assertTrue(tagged.changelogManifests.isEmpty(), "and nothing could be read from it")
        assertEquals(listOf("changelog-manifest-list"), tagged.readErrors.map { it.stage })

        val survivor = model.snapshots.single()
        val missing = survivor.baseManifests.flatMap { it.entries }
            .filter { it.metadata.kind == PaimonEntryKind.ADD }
            .filterNot { Files.isRegularFile(it.path) }
        assertEquals(2, missing.size, "snapshots 2 and 3's data files, expired with them")
    }

    /**
     * The tagged snapshot's data file is not an orphan, and the overwrite's changelog file is.
     *
     * Without the tag in the referenced set this reports two orphans and one of them is a file the
     * table can still read — the wrong answer in the direction that gets a file deleted.
     */
    @Test
    fun `a file only the tag reaches is referenced, and the uncommitted changelog is not`() {
        val report = findUnreferencedFiles(model)
        assertTrue(report.problems.isEmpty(), "${report.problems}")
        val orphan = report.unreferenced.single()
        assertTrue(orphan.path.fileName.toString().startsWith("changelog-"), "got ${orphan.path}")
        assertEquals(1164L, orphan.sizeBytes)

        val tagFile = model.tags.single().path.toAbsolutePath().normalize()
        val taggedData = model.tags.single().snapshot.deltaManifests.flatMap { it.entries }.single().path.toAbsolutePath().normalize()
        val referenced = referencedFiles(model)
        assertTrue(tagFile in referenced, "the tag file itself")
        assertTrue(taggedData in referenced, "the data file only the tag names")
    }

    /** The tagged snapshot is drawn, says why it is there, and hangs its manifests like any other. */
    @Test
    fun `the expired snapshot a tag retains is drawn as a snapshot with its tag`() {
        val graph = GraphLayoutService.layoutGraph(model, showRows = false)
        val byId = graph.nodes.filterIsInstance<GraphNode.PaimonSnapshotNode>().associateBy { it.data.id }
        assertEquals(setOf(1L, 4L), byId.keys)

        val tagged = byId.getValue(1L)
        assertEquals(listOf("first"), tagged.tags)
        assertTrue(tagged.retainedByTagOnly)
        assertEquals(83.0, tagged.height, "a line taller for the chip")
        assertTrue(graph.edges.any { it.fromId == tagged.id && it.toId.startsWith("pml_1_") }, "its manifest lists hang under it")

        val survivor = byId.getValue(4L)
        assertTrue(survivor.tags.isEmpty())
        assertTrue(!survivor.retainedByTagOnly)
        assertEquals(66.0, survivor.height)

        // The changelog list the tag names and expiry deleted is on the graph as a read error
        // under the tagged snapshot, the way every snapshot-level read error is.
        val errors = graph.nodes.filterIsInstance<GraphNode.ErrorNode>()
        assertEquals(1, errors.size, "one error, for the missing changelog manifest list: ${errors.map { it.message }}")
        assertTrue(graph.edges.any { it.fromId == tagged.id && it.toId == errors.single().id }, "hung under the tagged snapshot")
    }

    /**
     * History walks the tagged snapshot too, and on this table that adds nothing: the survivor's
     * base manifests still list every data file ever added — history counts manifest entries, not
     * files on disk, so the two expired snapshots' deleted files are still in the figure — and the
     * tag's own manifest is one of them. The walk would matter on a table whose compaction had
     * rewritten the tagged files out of the base; it is pinned here so the figure is a decision.
     */
    @Test
    fun `history is what the survivor's base still lists, and the tag adds nothing to it here`() {
        val summary = PaimonGraphBuilder.buildTableSummary(model)
        assertEquals(1, summary.current.dataFileCount)
        assertEquals(2L, summary.current.recordCount)
        assertEquals(4, summary.history.dataFileCount)
        assertEquals(3L + 2L + 1L + 2L, summary.history.recordCount)
        val tagManifest = model.tags.single().snapshot.deltaManifests.single()
        val contributions = summary.historyDerivation.contributions.filter { it.manifestPath == tagManifest.path.toString() }
        assertEquals(
            listOf(null, "snapshot 4"),
            contributions.map { it.firstCountedIn },
            "counted once through the survivor's base list, then met again under the tag",
        )
    }
}
