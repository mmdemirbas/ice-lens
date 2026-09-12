package model

import service.GraphLayoutService
import service.PaimonGraphBuilder
import service.snapshotColumns
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `example/paimon/db.db/br`: a primary-key table with a branch created from a tag and written to,
 * and a branch created empty, written by Spark from `docs/fixtures/paimon-br.sql`.
 *
 * What the fixture settled about the layout is the reason a branch is modelled as another line of
 * commits and not as a nested table: `branch/branch-dev/` holds `snapshot/`, `schema/` and `tag/`
 * and **no `manifest/`** — the branch's commit wrote its manifests into the table's `manifest/` and
 * its data file into `bucket-0` beside main's three. The expected values below are read off the
 * script and the directory listing it produced: main's snapshots 1..3, dev's 1 and 2 where 1 is
 * main's 1 byte for byte, dev's 2 holding four rows to main's 2 holding four different ones, and
 * one data file under `bucket-0` that only dev names.
 */
class PaimonBranchFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val tableDir = File(repoRoot, "example/paimon/db.db/br")
    private val model = PaimonUnifiedTableModel(Paths.get(tableDir.absolutePath))

    private val dev get() = model.branches.single { it.name == "dev" }
    private val empty get() = model.branches.single { it.name == "empty" }

    @Test
    fun `the table has three commits on main and two branches under branch slash`() {
        assertEquals(listOf(1L, 2L, 3L), model.snapshots.map { it.metadata.id })
        assertEquals(listOf("dev", "empty"), model.branches.map { it.name }, "in name order")
        assertEquals(listOf("base"), model.tags.map { it.name })
        assertTrue(model.readErrors.isEmpty(), "table-level: ${model.readErrors}")
        assertEquals(tableDir.toPath().resolve("branch").resolve("branch-dev"), dev.path)
    }

    /**
     * `create_branch` from a tag copies the tag's snapshot — and the tag — into the branch, and
     * the branch's next commit takes the next id. So dev's snapshot 1 names the same manifest
     * lists main's does, and dev's snapshot 2 is a commit main's snapshot 2 is not.
     */
    @Test
    fun `a branch created from a tag starts with that snapshot copied and numbers on from it`() {
        assertEquals(listOf(1L, 2L), dev.snapshots.map { it.metadata.id })
        assertEquals(listOf("base"), dev.tags.map { it.name }, "the tag was copied with the snapshot")
        assertEquals(mapOf(1L to listOf("base")), dev.tagNamesBySnapshotId)
        assertTrue(dev.tagOnlySnapshots.isEmpty())
        assertTrue(dev.readErrors.isEmpty(), "branch-level: ${dev.readErrors}")

        val mainOne = model.snapshots.first { it.metadata.id == 1L }.metadata
        val devOne = dev.snapshots.first { it.metadata.id == 1L }.metadata
        assertEquals(mainOne, devOne, "the branch's first snapshot is main's, verbatim")

        val mainTwo = model.snapshots.first { it.metadata.id == 2L }.metadata
        val devTwo = dev.snapshots.first { it.metadata.id == 2L }.metadata
        assertNotEquals(mainTwo.deltaManifestList, devTwo.deltaManifestList, "same id, different commit")
        assertEquals(4L, devTwo.totalRecordCount, "3 rows from snapshot 1, plus k = 5")
        assertEquals(1L, devTwo.deltaRecordCount)
        assertEquals("APPEND", devTwo.commitKind)
        assertEquals(5L, model.snapshots.last().metadata.totalRecordCount, "main went on to k = 6 without it")
    }

    /** A branch's manifests and data file live in the table's directories, not the branch's. */
    @Test
    fun `a branch commit writes its manifests and data beside main's`() {
        val devTwo = dev.snapshots.first { it.metadata.id == 2L }
        val manifest = devTwo.deltaManifests.single()
        assertEquals(tableDir.toPath().resolve("manifest"), manifest.path.parent, "the table's manifest/")
        assertTrue(Files.isRegularFile(manifest.path))
        assertTrue(!Files.exists(dev.path.resolve("manifest")), "the branch has no manifest/ of its own")

        val file = manifest.entries.single()
        assertEquals(tableDir.toPath().resolve("bucket-0"), file.path.parent, "the table's bucket-0")
        assertTrue(Files.isRegularFile(file.path))
        val mainFiles = model.snapshots.flatMap { s -> (s.baseManifests + s.deltaManifests).flatMap { it.entries } }.map { it.path }.toSet()
        assertTrue(file.path !in mainFiles, "and no snapshot of main names it")
        assertEquals(4, Files.list(tableDir.toPath().resolve("bucket-0")).filter { !it.fileName.toString().startsWith(".") }.count().toInt())
    }

    /** `create_branch` with no tag writes a schema and nothing else, which is a branch and not an error. */
    @Test
    fun `an empty branch has a schema, no snapshot directory and no read error`() {
        assertEquals(1, empty.schemas.size)
        assertTrue(empty.snapshots.isEmpty())
        assertTrue(empty.tags.isEmpty())
        assertTrue(!Files.exists(empty.path.resolve("snapshot")), "created without a snapshot/ at all")
        assertTrue(empty.readErrors.isEmpty(), "an absent snapshot/ on a branch is its normal state: ${empty.readErrors}")
    }

    /**
     * The file only the branch names is referenced. Before branches were read, `branch/` was
     * skipped and this file was the table's one "orphan" — a data file a commit wrote and a
     * reader could time-travel to.
     */
    @Test
    fun `a data file only a branch names is referenced, and the branch's own files are too`() {
        val report = findUnreferencedFiles(model)
        assertTrue(report.problems.isEmpty(), "${report.problems}")
        assertEquals(emptyList(), report.unreferenced.map { report.relativePathOf(it) })
        assertEquals(report.filesOnDisk, report.referencedOnDisk)

        // The branch's files are in the walk: main only would be 3 snapshots + EARLIEST + LATEST
        // + a schema + a tag + 8 manifest-list/manifest files + 4 data files.
        val branchFiles = referencedFiles(model).filter { it.startsWith(tableDir.toPath().resolve("branch")) }
        assertEquals(
            listOf(
                "branch-dev/schema/schema-0",
                "branch-dev/snapshot/EARLIEST",
                "branch-dev/snapshot/LATEST",
                "branch-dev/snapshot/snapshot-1",
                "branch-dev/snapshot/snapshot-2",
                "branch-dev/tag/tag-base",
                "branch-empty/schema/schema-0",
                "branch-empty/snapshot/EARLIEST",
                "branch-empty/snapshot/LATEST",
            ),
            branchFiles.map { tableDir.toPath().resolve("branch").relativize(it).toString() }.sorted(),
        )
    }

    /**
     * Every snapshot of every line is drawn under the table root, a branch's with the branch in
     * its id and on its node, and a manifest two lines share is one node under both.
     */
    @Test
    fun `branch snapshots are drawn with their branch and share main's manifests where they are main's`() {
        val graph = GraphLayoutService.layoutGraph(model, showRows = false)
        val snapshots = graph.nodes.filterIsInstance<GraphNode.PaimonSnapshotNode>()
        assertEquals(
            mapOf("psnap_1" to null, "psnap_2" to null, "psnap_3" to null, "psnap_dev_1" to "dev", "psnap_dev_2" to "dev"),
            snapshots.associate { it.id to it.branch },
        )
        val devOne = graph.nodeById.getValue("psnap_dev_1") as GraphNode.PaimonSnapshotNode
        assertEquals(listOf("base"), devOne.tags)
        assertEquals(83.0, devOne.height, "a line taller for the branch and tag chips")
        assertEquals(66.0, graph.nodeById.getValue("psnap_2").height, "main's carry no chip")
        assertEquals(83.0, graph.nodeById.getValue("psnap_dev_2").height, "the branch chip alone is a line")
        assertTrue(graph.edges.any { it.fromId == "table_root" && it.toId == "psnap_dev_2" })

        fun manifestsUnder(snapshotId: String) = graph.edges.filter { it.fromId == snapshotId && it.toId.startsWith("pml_") }
            .flatMap { ml -> graph.edges.filter { it.fromId == ml.toId }.map { it.toId } }.toSet()
        assertEquals(manifestsUnder("psnap_1"), manifestsUnder("psnap_dev_1"), "one manifest node under both lines")
        assertNotEquals(manifestsUnder("psnap_2"), manifestsUnder("psnap_dev_2"))
        assertTrue(graph.edges.any { it.fromId == "psnap_dev_1" && it.toId == "pml_dev_1_delta" }, "the branch's manifest list ids carry the branch")
        assertTrue(graph.nodes.none { it is GraphNode.ErrorNode }, "no read error anywhere")
    }

    /** Main in one column, the branch in the next, and each column named — the same drawing `branched` gets. */
    @Test
    fun `a branch draws in its own column and the columns are named`() {
        val graph = GraphLayoutService.layoutGraph(model, showRows = false)
        val snapshots = graph.nodes.filterIsInstance<GraphNode.PaimonSnapshotNode>()
        val xOf = { node: GraphNode -> graph.layoutPositions.getValue(node.id).x }
        val mainX = snapshots.filter { it.branch == null }.map(xOf).distinct()
        val devX = snapshots.filter { it.branch == "dev" }.map(xOf).distinct()
        assertEquals(1, mainX.size, "main's snapshots share one x")
        assertEquals(1, devX.size, "dev's share another")
        assertTrue(devX.single() > mainX.single() + 200.0, "dev to the right of main: $devX vs $mainX")

        val columns = snapshotColumns(snapshots) { id -> graph.layoutPositions.getValue(id) }
        assertEquals(listOf(listOf("main"), listOf("dev")), columns.map { c -> c.labels.map { it.name } })
        assertTrue(columns.all { c -> c.labels.all { it.isBranch } })

        // Unbranched tables draw exactly where they drew: no column, no label.
        val tg = PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/tg").absolutePath))
        val tgGraph = GraphLayoutService.layoutGraph(tg, showRows = false)
        val tgSnapshots = tgGraph.nodes.filterIsInstance<GraphNode.PaimonSnapshotNode>()
        assertEquals(1, tgSnapshots.map { tgGraph.layoutPositions.getValue(it.id).x }.distinct().size)
        assertTrue(snapshotColumns(tgSnapshots) { id -> tgGraph.layoutPositions.getValue(id) }.isEmpty())
    }

    /**
     * History reaches the branch's file; current is main's and is not touched by it. The branch's
     * copied snapshot 1 reaches main's manifests and adds nothing, the same rule as a tag's.
     */
    @Test
    fun `history counts the branch's file and current does not`() {
        val summary = PaimonGraphBuilder.buildTableSummary(model)
        assertEquals(3, summary.current.dataFileCount, "main's three files")
        assertEquals(5L, summary.current.recordCount)
        assertEquals(4, summary.history.dataFileCount, "plus the one only dev names")
        assertEquals(6L, summary.history.recordCount)
        assertEquals(
            listOf(BranchSummary("dev", "branch/branch-dev", 2, 2L, 1, 1, 0), BranchSummary("empty", "branch/branch-empty", 0, null, 1, 0, 0)),
            summary.branches,
        )
        val ao = PaimonGraphBuilder.buildTableSummary(PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/ao").absolutePath)))
        assertEquals(emptyList(), ao.branches, "a Paimon table with no branch/ has an empty list, not null")
    }
}
