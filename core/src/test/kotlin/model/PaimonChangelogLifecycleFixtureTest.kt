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
        FixtureCatalog.paimon.filter { it !in setOf("pcl", "pcn") }.forEach { name ->
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

    /**
     * `expire_changelogs` planned the way `ExpireChangelogImpl.expire()` runs it, against the run
     * that already happened: at commit time, with every changelog younger than the hour, the floor
     * alone removed 1–4, so a plan from the last commit's moment removes nothing more; with
     * `older_than = now` and `retain_min = 1` the run goes to the latest changelog and removes 5.
     * Then the bounds are planted: a consumer, a maximum below the floor, and the limit.
     */
    @Test
    fun `the changelog expiry counts against the latest snapshot, and a bare call at commit time removes nothing more`() {
        val input = model.expiryInput()
        assertEquals(listOf(5L, 6L), input.changelogTimes.keys.sorted())
        val lastCommit = model.snapshots.last().metadata.timeMillis!!
        val bare = input.planChangelogExpiry(PaimonExpiryOptions(nowMs = lastCommit + 1000))
        assertEquals(4, bare.retainMax)
        assertEquals(1, bare.retainMin)
        assertEquals(5L, bare.floor)
        assertEquals(emptyList(), bare.removed.map { it.snapshotId }, bare.toString())
        assertEquals(listOf(PaimonKeepRule.YOUNGER_THAN_CUTOFF), bare.changelogs.first().keptBy.map { it.rule })
        val byAge = input.planChangelogExpiry(PaimonExpiryOptions(nowMs = lastCommit + 1000, retainMin = 1, olderThanMs = lastCommit + 1000))
        assertEquals(listOf(5L), byAge.removed.map { it.snapshotId }, byAge.toString())
        assertTrue(byAge.changelogs.last().retained, "the latest changelog bounds the run")
        // A consumer at 6 keeps 6 and everything after; a maximum of 2 puts the floor at 7, past every changelog.
        val consumer = input.copy(consumerNext = mapOf("reader" to 6L)).planChangelogExpiry(PaimonExpiryOptions(nowMs = lastCommit + 1000, retainMin = 1, olderThanMs = lastCommit + 1000))
        assertEquals(listOf(5L), consumer.removed.map { it.snapshotId })
        assertTrue(consumer.changelogs.last().keptBy.any { it.rule == PaimonKeepRule.CONSUMER && it.consumers == listOf("reader") })
        val tight = input.planChangelogExpiry(PaimonExpiryOptions(nowMs = lastCommit + 1000, retainMax = 2, retainMin = 1))
        assertEquals(7L, tight.floor)
        assertEquals(listOf(5L), tight.removed.map { it.snapshotId }, "everything below the floor goes whatever its age, up to the latest changelog")
        val limited = input.copy(tableOptions = input.tableOptions + ("snapshot.expire.limit" to "1")).planChangelogExpiry(PaimonExpiryOptions(nowMs = lastCommit + 1000, retainMin = 1, olderThanMs = lastCommit + 1000))
        assertEquals(listOf(5L), limited.removed.map { it.snapshotId })
        assertTrue(limited.changelogs.last().keptBy.any { it.rule == PaimonKeepRule.EXPIRE_LIMIT })
        // No changelog directory: nothing to plan, and no other fixture has one.
        FixtureCatalog.paimon.filter { it !in setOf("pcl", "pcn") }.forEach { name ->
            val other = PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/$name").absolutePath)).expiryInput()
            assertTrue(other.changelogTimes.isEmpty(), name)
            assertEquals(emptyList(), other.planChangelogExpiry(PaimonExpiryOptions(nowMs = lastCommit)).removed, name)
        }
    }

    /**
     * `pcn` is `pcl` without a changelog producer, where the delta list is the change stream: the
     * decoupled expiry then keeps the base and delta lists too, and every `APPEND`-sourced data
     * file — the five files the compaction at 6 removed are all still on disk — so the long-lived
     * changelog replays whole and a plan expiring snapshot 7 frees none of its lists.
     */
    @Test
    fun `without a changelog producer the decoupled expiry keeps the base and delta lists and the APPEND files, and the changelog replays`() {
        val pcn = PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/pcn").absolutePath))
        assertEquals(listOf(7L, 8L), pcn.snapshots.map { it.metadata.id })
        assertEquals(listOf(5L, 6L), pcn.changelogs.map { it.metadata.id })
        assertTrue((pcn.latestSchema!!.options["changelog-producer"] ?: "none") == "none")
        pcn.changelogs.forEach { c ->
            assertEquals(emptyList(), c.retiredLists, c.path.toString())
            assertEquals(emptyList(), c.readErrors, c.readErrors.toString())
            assertTrue(c.baseManifests.isNotEmpty() || c.deltaManifests.isNotEmpty(), c.path.toString())
            (c.baseManifests + c.deltaManifests).flatMap { it.entries }.forEach { assertTrue(java.nio.file.Files.exists(it.path), "${c.metadata.id}: ${it.path}") }
            assertEquals(3, paimonRecordTallies(c, replayPaimonSnapshot(c).liveEntries.values.sumOf { it.metadata.file?.rowCount ?: 0L }).size)
        }
        // The compaction at 6 removed the five earlier files; every one is still there for the changelog.
        val six = pcn.changelogs.last()
        val removedAtSix = six.deltaManifests.flatMap { it.entries }.filter { it.metadata.kind == PaimonEntryKind.DELETE }
        assertEquals(5, removedAtSix.size, removedAtSix.map { it.path.fileName }.toString())
        removedAtSix.forEach { assertTrue(java.nio.file.Files.exists(it.path), it.path.toString()) }
        assertEquals(emptyList(), findUnreferencedFiles(pcn).unreferenced.map { it.path })
        // A removed file's history: listed live by changelog 5, removed by changelog 6, both long-lived.
        val history = pcn.fileHistoryOf(paimonDataFileKey(removedAtSix.first()))
        assertEquals(6L, history.removedBy?.snapshotId, history.toString())
        assertTrue(history.removedBy?.changelogOnly == true)
        assertTrue(history.liveIn.any { it.snapshotId == 5L && it.changelogOnly }, history.toString())
        assertEquals(4, history.retainedSnapshotCount)
        assertTrue(history.describe.contains("a long-lived changelog"), history.describe)
        val plan = pcn.expiryFileInput().planExpiryFiles(setOf(7L))
        assertTrue(plan.decoupled)
        val seven = pcn.snapshots.first { it.metadata.id == 7L }
        assertTrue(seven.metadata.baseManifestList !in plan.names && seven.metadata.deltaManifestList !in plan.names, plan.names.toString())
        assertEquals(setOf(PaimonExpiryFileKind.SNAPSHOT), plan.files.map { it.kind }.toSet(), plan.files.toString())
        // A tag-free table with a producer frees the lists; the same statement's plan on `pcl` does.
        assertTrue(model.expiryFileInput().planExpiryFiles(setOf(7L)).files.any { it.kind == PaimonExpiryFileKind.MANIFEST_LIST })
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
