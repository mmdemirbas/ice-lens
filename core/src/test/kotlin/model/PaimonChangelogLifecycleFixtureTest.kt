package model

import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import service.GraphLayoutService

/**
 * `pcl`: seven inserts under `snapshot.num-retained.max = 2` and `changelog.num-retained.max = 4`
 * with `changelog-producer = input`, every expiry run at commit time. What the decoupled
 * lifecycle left: `snapshot/` holds 7 and 8; `changelog/` holds 5 and 6 — the latest snapshot's
 * id less the changelog maximum plus one is the floor, and the two live snapshots count toward
 * it — each the JSON of the expired snapshot; 5's changelog manifest list and its changelog
 * file are still on disk and its base and delta lists are not; 6 is a `COMPACT` that wrote no
 * changelog and keeps nothing but the file. Before this every one of those files was an orphan
 * to the referenced-files walk, in the direction that gets a file deleted.
 */
class PaimonChangelogLifecycleFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val model = PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/pcl").absolutePath))

    @Test
    fun `the changelog directory holds the expired snapshots the changelog retention keeps, read with their retired lists named`() {
        assertEquals(listOf(7L, 8L), model.snapshots.map { it.metadata.id })
        assertEquals(listOf(5L, 6L), model.changelogs.map { it.metadata.id })
        val latest = model.snapshots.last().metadata.id!!
        val max = model.latestSchema!!.options.getValue("changelog.num-retained.max").toInt()
        assertEquals(latest - max + 1, model.changelogs.first().metadata.id, "the floor counts the live snapshots")
        model.changelogs.forEach { c ->
            assertTrue(c.longLivedChangelog)
            assertEquals(emptyList(), c.readErrors, "${c.path}: ${c.readErrors}")
            assertEquals(listOfNotNull(c.metadata.baseManifestList, c.metadata.deltaManifestList), c.retiredLists, c.path.toString())
            assertTrue(c.baseManifests.isEmpty() && c.deltaManifests.isEmpty())
        }
        val five = model.changelogs.first()
        assertEquals("APPEND", five.metadata.commitKind)
        assertEquals(1, five.changelogManifests.size)
        assertEquals(1, five.changelogManifests.single().entries.size)
        assertTrue(five.changelogManifests.single().entries.single().metadata.file?.fileName.orEmpty().startsWith("changelog-"))
        val six = model.changelogs.last()
        assertEquals("COMPACT", six.metadata.commitKind)
        assertNull(six.metadata.changelogManifestList)
        assertTrue(six.changelogManifests.isEmpty())
    }

    @Test
    fun `nothing the changelog lifecycle kept is an orphan, and the tallies count only what is left to count`() {
        val report = findUnreferencedFiles(model)
        assertEquals(emptyList(), report.unreferenced.map { report.relativePathOf(it) })
        assertTrue(report.filesOnDisk >= 30, "${report.filesOnDisk} files")
        val five = model.changelogs.first()
        val tallies = paimonRecordTallies(five, 0)
        assertEquals(listOf("Changelog records"), tallies.map { it.label })
        assertEquals(1L to 1L, tallies.single().recorded to tallies.single().counted)
        val integrity = model.integrityReport()
        assertEquals(emptyList(), integrity.findings, integrity.findings.toString())
    }

    /**
     * The expiry that ran is the oracle for the plan: what `changelog/changelog-5` still names on
     * disk is exactly what a decoupled expiry keeps, so a plan expiring snapshot 7 must free its
     * base and delta lists and never its changelog list or changelog file — and the derived rule
     * has to read `pcl`'s options as decoupled and every other fixture's as not.
     */
    @Test
    fun `a decoupled expiry keeps the changelog list and files, frees the base and delta lists, and pcl is the one decoupled fixture`() {
        val options = model.latestSchema!!.options
        assertTrue(paimonChangelogLifecycleDecoupled(options))
        assertTrue(!paimonChangelogLifecycleDecoupled(options - "changelog.num-retained.max" - "changelog.num-retained.min"))
        FixtureCatalog.paimon.filter { it != "pcl" }.forEach { name ->
            val other = PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/$name").absolutePath))
            assertTrue(!paimonChangelogLifecycleDecoupled(other.latestSchema?.options.orEmpty()), name)
        }
        // What the past expiries left of changelog 5 is what the rule says a decoupled expiry keeps.
        val five = model.changelogs.first()
        val manifestDir = model.path.resolve("manifest")
        assertTrue(java.nio.file.Files.exists(manifestDir.resolve(five.metadata.changelogManifestList!!)))
        assertTrue(!java.nio.file.Files.exists(manifestDir.resolve(five.metadata.baseManifestList!!)))
        assertTrue(!java.nio.file.Files.exists(manifestDir.resolve(five.metadata.deltaManifestList!!)))
        five.changelogManifests.flatMap { it.entries }.forEach { assertTrue(java.nio.file.Files.exists(it.path), it.path.toString()) }
        // The plan for the next expiry: snapshot 7 goes, its changelog list and file stay.
        val plan = model.expiryFileInput().planExpiryFiles(setOf(7L))
        assertTrue(plan.decoupled)
        val seven = model.snapshots.first { it.metadata.id == 7L }
        assertTrue(seven.metadata.baseManifestList in plan.names, plan.names.toString())
        assertTrue(seven.metadata.deltaManifestList in plan.names, plan.names.toString())
        assertTrue(seven.metadata.changelogManifestList !in plan.names, plan.names.toString())
        assertTrue(plan.files.none { it.kind == PaimonExpiryFileKind.CHANGELOG_FILE }, plan.files.toString())
        assertTrue("snapshot-7" in plan.names)
    }

    @Test
    fun `the graph draws a long-lived changelog as a changelog-only snapshot with its changelog list and no base or delta`() {
        val graph = GraphLayoutService.layoutGraph(model, showRows = false)
        val five = assertNotNull(graph.nodeById["psnap_5"] as? GraphNode.PaimonSnapshotNode)
        assertTrue(five.retainedByChangelogOnly)
        assertTrue(!five.retainedByTagOnly)
        assertEquals(2, five.retiredLists.size)
        assertNotNull(graph.nodeById["pml_5_changelog"])
        assertNull(graph.nodeById["pml_5_base"])
        assertNull(graph.nodeById["pml_5_delta"])
        val eight = assertNotNull(graph.nodeById["psnap_8"] as? GraphNode.PaimonSnapshotNode)
        assertTrue(!eight.retainedByChangelogOnly)
        assertNotNull(graph.nodeById["pml_8_base"])
        assertEquals(listOf("psnap_5", "psnap_6", "psnap_7", "psnap_8"), graph.nodes.filterIsInstance<GraphNode.PaimonSnapshotNode>().map { it.id }.sorted())
    }
}
