package model

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `sys.purge_files`, held to `docs/fixtures/paimon-purge.sql`: `brp` is `br` purged — two
 * branches dropped, the tag deleted, one truncating OVERWRITE as snapshot 4, snapshots 1..3
 * expired, every data file and every old list gone, main's three manifests carried into the
 * truncate's base list. The plan on `br` must name exactly the files `brp` lacks, and `brp`
 * must hold exactly the four files the truncate wrote beyond what it kept.
 */
class PaimonPurgeFixtureTest {

    private fun files(name: String): Set<String> = FixtureCatalog.paimonDir(name).walkTopDown()
        .filter { it.isFile && !it.name.startsWith(".") }.map { it.relativeTo(FixtureCatalog.paimonDir(name)).path }.toSet()

    @Test
    fun `the plan on br names what brp lacks, and brp holds the truncate's four new files beyond what was kept`() {
        val br = FixtureCatalog.paimonModel("br")
        val plan = br.expiryFileInput().planPurge()
        assertEquals(listOf("dev", "empty"), plan.branches)
        assertEquals(listOf("base"), plan.tags)
        assertEquals(emptyList(), plan.consumers)
        assertEquals(listOf(1L, 2L, 3L), plan.snapshotIds)
        assertEquals(4L, plan.truncateSnapshotId)
        assertEquals(-5L, plan.deltaRecordCount, "k 1, 2, 3, 4, 6 on main")
        assertEquals(4, plan.newFiles)
        val before = files("br")
        val after = files("brp")
        // Every file br has that brp lacks is in the plan by name — the branch directories
        // apart, which the plan names as branches, and the two hint files, rewritten under the
        // same names.
        val branchPaths = (before - after).filter { it.startsWith("branch/") }
        assertTrue(branchPaths.isNotEmpty())
        val gone = (before - after - branchPaths.toSet()).map { File(it).name }.filterNot { it == "EARLIEST" || it == "LATEST" }.toSet()
        assertEquals(gone, plan.removed.map { it.name }.toSet())
        assertEquals(4, plan.ofKind(PaimonExpiryFileKind.DATA_FILE).size, "main's three files and the branch's one")
        assertEquals(8, plan.ofKind(PaimonExpiryFileKind.MANIFEST_LIST).size, "main's three snapshots' six and dev's own snapshot's two; dev's copied snapshot 1 names main's")
        assertEquals(1, plan.ofKind(PaimonExpiryFileKind.MANIFEST).size, "the branch's own manifest; main's three are kept")
        assertEquals(3, plan.kept.size)
        assertTrue(plan.kept.all { it.kind == PaimonExpiryFileKind.MANIFEST && "manifest/${it.name}" in after })
        // What brp gained: the truncate's snapshot file, two lists and one delete manifest.
        val gained = after - before
        assertEquals(plan.newFiles, gained.size, gained.toString())
        assertEquals(setOf("snapshot/snapshot-4"), gained.filter { it.startsWith("snapshot/") }.toSet())
        val truncate = FixtureCatalog.paimonModel("brp").snapshots.single()
        assertEquals(4L, truncate.metadata.id)
        assertEquals("OVERWRITE", truncate.metadata.commitKind)
        assertEquals(-5L, truncate.metadata.deltaRecordCount)
        assertEquals(0L, truncate.metadata.totalRecordCount)
        assertEquals(plan.kept.map { it.name }.toSet(), truncate.baseManifests.map { paimonManifestKey(it) }.toSet(), "the base list carries main's manifests forward")
        assertEquals(3, truncate.deltaManifests.single().entries.count { it.metadata.kind == PaimonEntryKind.DELETE })
        // And brp itself plans a purge that removes nothing but its one snapshot's own files.
        val again = FixtureCatalog.paimonModel("brp").expiryFileInput().planPurge()
        assertEquals(emptyList(), again.branches + again.tags)
        assertEquals(0L, again.deltaRecordCount)
        assertTrue(again.ofKind(PaimonExpiryFileKind.DATA_FILE).isEmpty())
    }

    /** The metadata directories, an Iceberg export's included; what is left under the table is data — bucket files, their `.index` sidecars, changelog files. */
    private val metadataDirs = setOf("snapshot", "schema", "manifest", "index", "statistics", "tag", "consumer", "branch", "changelog", "metadata")

    @Test
    fun `every fixture's purge names every data file on disk and nothing that is not there, and keeps the latest snapshot's manifests`() {
        // A plan is read off the metadata, so two tables are held one way only: `pru` and `prua`
        // name files deleted from disk by design, and the tables written with an orphan on purpose
        // (`UnreferencedFilesTest`) hold files no metadata names, which the purge's orphan clean
        // takes without this being able to name them.
        val missingByDesign = setOf("pru", "prua")
        val orphansByDesign = setOf("cl", "tg", "prba", "po", "poa", "brf")
        for (name in FixtureCatalog.paimon) {
            val model = FixtureCatalog.paimonModel(name)
            val input = model.expiryFileInput()
            val plan = input.planPurge()
            val latest = model.snapshots.lastOrNull() ?: continue
            val live = replayPaimonSnapshot(latest).liveEntries.values.map { paimonEntryFileName(it) }.toSet()
            val removed = plan.ofKind(PaimonExpiryFileKind.DATA_FILE).map { it.name }.toSet()
            assertTrue(live.all { it in removed }, "$name: every live file of the latest snapshot goes")
            val onDisk = files(name).filter { it.substringBefore('/') !in metadataDirs && '/' in it }.map { File(it).name }.toSet()
            val planned = (plan.ofKind(PaimonExpiryFileKind.DATA_FILE) + plan.ofKind(PaimonExpiryFileKind.CHANGELOG_FILE))
            if (name !in missingByDesign) planned.forEach { f -> assertTrue(File(f.path!!).isFile, "$name: ${f.name} is planned and not on disk") }
            if (name !in orphansByDesign) assertEquals(emptySet(), onDisk - planned.map { it.name }.toSet(), "$name: every data file under the table is planned")
            assertEquals((latest.baseManifests + latest.deltaManifests).map { paimonManifestKey(it) }.toSet(), plan.kept.filter { it.kind == PaimonExpiryFileKind.MANIFEST }.map { it.name }.toSet(), name)
            assertEquals(-(latest.metadata.totalRecordCount ?: 0L), plan.deltaRecordCount, name)
            assertEquals(model.snapshots.mapNotNull { it.metadata.id }, plan.snapshotIds, name)
            assertEquals(model.consumers.map { it.name }.sorted(), plan.consumers, name)
            assertEquals(model.branches.map { it.name }.sorted(), plan.branches, name)
        }
    }
}
