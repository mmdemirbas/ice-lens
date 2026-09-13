package model

import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `docs/fixtures/merged.sql` wrote three tables with `commit.manifest.min-count-to-merge` at two
 * (`merged`, `mergedel`) and at its default (`mergespec`), and every commit's manifest list is
 * what `ManifestMergeManager` left — so the plan on the parent's list, with the one manifest the
 * commit writes assumed, has to land on the child's list. The sweep at the end runs the same
 * check over every append and row-level delete in every Iceberg fixture: none of those has a
 * hundred manifests, so their oracle is that nothing merged, which is the branch a reader
 * asking "why do I have forty manifests" is standing in.
 */
class ManifestMergeFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun model(fixture: String) =
        UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/$fixture").absolutePath))

    private fun snapshotsInOrder(m: UnifiedTableModel) =
        m.metadatas.flatMap { it.snapshots }.filter { !it.expired }.distinctBy { it.metadata.snapshotId }
            .sortedBy { it.metadata.sequenceNumber }

    private fun options(m: UnifiedTableModel, at: UnifiedSnapshot): ManifestMergeOptions {
        // The properties in force when the commit ran: the newest metadata that lists it as current.
        val meta = m.metadatas.filter { md -> md.metadata.currentSnapshotId == at.metadata.snapshotId }
            .minByOrNull { metadataVersionFromFileName(it.path.fileName.toString()) ?: Int.MAX_VALUE }?.metadata
            ?: m.metadatas.last().metadata
        return ManifestMergeOptions.forTable(meta.properties)
    }

    private fun entries(s: UnifiedSnapshot) = s.manifests.map { it.metadata }
    private fun count(s: UnifiedSnapshot, content: Int) = entries(s).count { (it.content ?: ManifestContent.DATA) == content }

    /** The plan from [parent] for a commit writing one manifest of [content], or none. */
    private fun plan(parent: UnifiedSnapshot, content: Int, writesOne: Boolean, options: ManifestMergeOptions): ManifestMergePlan {
        val listed = entries(parent)
        val bytes = if (writesOne) assumedManifestBytes(listed, content) else null
        return planManifestMerge(listed, content, bytes, listed.firstOrNull()?.partitionSpecId, options)
    }

    @Test
    fun `at min-count-to-merge two, every append after the first merges the list into one manifest`() {
        val m = model("merged")
        val snaps = snapshotsInOrder(m)
        assertEquals(listOf("append", "append", "append", "delete", "append", "append"), snaps.map { it.metadata.summary["operation"] })
        assertEquals(listOf(1, 1, 1, 1, 1, 2), snaps.map { count(it, ManifestContent.DATA) })

        // Snapshot 2: [new, m1] holds the first and is not under two → merged.
        val second = plan(snaps[0], ManifestContent.DATA, writesOne = true, options(m, snaps[1]))
        assertEquals(listOf(ManifestMergeVerdict.MERGED), second.bins.map { it.verdict })
        assertEquals(1, second.outputCount)
        assertEquals("2 of 2 merge into 1", second.describe)
        // and the manifest it left carries the earlier ADDED as EXISTING.
        val merged2 = entries(snaps[1]).single()
        assertEquals(Triple(1, 1, 0), Triple(merged2.addedFilesCount, merged2.existingFilesCount, merged2.deletedFilesCount))

        // Snapshot 4 is a copy-on-write delete that writes no data manifest: the first is the
        // existing one, alone in its bin, and the filter's rewrite of it is what the list holds.
        val fourth = plan(snaps[2], ManifestContent.DATA, writesOne = false, options(m, snaps[3]))
        assertEquals(listOf(ManifestMergeVerdict.ALONE), fourth.bins.map { it.verdict })
        val filtered = entries(snaps[3]).single()
        assertEquals(snaps[3].metadata.snapshotId, filtered.addedSnapshotId)
        assertEquals(Triple(0, 2, 1), Triple(filtered.addedFilesCount, filtered.existingFilesCount, filtered.deletedFilesCount))

        // Snapshot 5 merges again, and the DELETED entry — an earlier snapshot's — is dropped.
        val fifth = plan(snaps[3], ManifestContent.DATA, writesOne = true, options(m, snaps[4]))
        assertEquals(1, fifth.outputCount)
        val merged5 = entries(snaps[4]).single()
        assertEquals(Triple(1, 2, 0), Triple(merged5.addedFilesCount, merged5.existingFilesCount, merged5.deletedFilesCount))

        // Snapshot 6 ran with merging disabled: no bins, two manifests.
        val sixth = plan(snaps[4], ManifestContent.DATA, writesOne = true, options(m, snaps[5]))
        assertTrue(!sixth.options.enabled)
        assertEquals(emptyList(), sixth.bins)
        assertEquals(2, sixth.outputCount)
        assertEquals("off", sixth.describe)
    }

    @Test
    fun `delete manifests merge by the same rule, and a row-level delete leaves the data side alone`() {
        val m = model("mergedel")
        val snaps = snapshotsInOrder(m)
        assertEquals(listOf("append", "delete", "delete"), snaps.map { it.metadata.summary["operation"] })
        assertEquals(listOf(1, 1, 1), snaps.map { count(it, ManifestContent.DATA) })
        assertEquals(listOf(0, 1, 1), snaps.map { count(it, ManifestContent.DELETES) })

        val third = options(m, snaps[2])
        // Data side: nothing new, the existing manifest is the first and alone.
        assertEquals(1, plan(snaps[1], ManifestContent.DATA, writesOne = false, third).outputCount)
        // Delete side: [new, the earlier delete manifest] → merged.
        val deletes = plan(snaps[1], ManifestContent.DELETES, writesOne = true, third)
        assertEquals(listOf(ManifestMergeVerdict.MERGED), deletes.bins.map { it.verdict })
        assertEquals(1, deletes.outputCount)

        // What the merged delete manifest holds is 1 ADDED + 1 DELETED, not 1 ADDED + 1 EXISTING:
        // Spark 3.5 on 1.8.1 deletes at file granularity, so the second DELETE rewrote the first
        // delete file — the new one holds both positions and the old one is removed by this
        // snapshot, which is the one DELETED a merge keeps.
        val mergedDeletes = snaps[2].manifests.single { (it.metadata.content ?: 0) == ManifestContent.DELETES }
        assertEquals(snaps[2].metadata.snapshotId, mergedDeletes.metadata.addedSnapshotId)
        val byStatus = mergedDeletes.dataFiles.groupBy { it.metadata.status }
        assertEquals(setOf(ManifestEntryStatus.ADDED, ManifestEntryStatus.DELETED), byStatus.keys)
        assertEquals(2L, byStatus.getValue(ManifestEntryStatus.ADDED).single().metadata.dataFile?.recordCount, "both positions in the new delete file")
        assertEquals(1L, byStatus.getValue(ManifestEntryStatus.DELETED).single().metadata.dataFile?.recordCount, "the first delete file, removed")
    }

    @Test
    fun `a spec change merges the old spec's manifests at the next commit, under the default count of a hundred`() {
        val m = model("mergespec")
        val snaps = snapshotsInOrder(m)
        assertEquals(listOf(1, 2, 2), snaps.map { count(it, ManifestContent.DATA) })
        val third = options(m, snaps[2])
        assertEquals(ManifestMergeOptions.DEFAULT_MIN_COUNT_TO_MERGE, third.minCountToMerge)

        // Snapshot 2: [new, m1] under spec 0, holding the first, 2 < 100 → kept.
        val second = plan(snaps[0], ManifestContent.DATA, writesOne = true, options(m, snaps[1]))
        assertEquals(listOf(ManifestMergeVerdict.UNDER_MIN_COUNT), second.bins.map { it.verdict })
        assertEquals(2, second.outputCount)

        // Snapshot 3: the new manifest is under spec 1, so spec 0's [m2, m1] holds no first and merges.
        val latestSpec = m.metadatas.last().metadata.defaultSpecId
        val listed = entries(snaps[1])
        val plan = planManifestMerge(listed, ManifestContent.DATA, assumedManifestBytes(listed, ManifestContent.DATA), latestSpec, third)
        assertEquals(listOf(1, 0), plan.bins.map { it.specId }, "specs in descending order, the new manifest's first")
        assertEquals(listOf(ManifestMergeVerdict.ALONE, ManifestMergeVerdict.MERGED), plan.bins.map { it.verdict })
        assertEquals(listOf(true, false), plan.bins.map { it.holdsFirst })
        assertEquals(2, plan.outputCount)
        assertEquals("2 of 3 merge into 1", plan.describe)

        val merged = entries(snaps[2]).single { it.partitionSpecId == 0 }
        assertEquals(snaps[2].metadata.snapshotId, merged.addedSnapshotId)
        assertEquals(Triple(0, 2, 0), Triple(merged.addedFilesCount, merged.existingFilesCount, merged.deletedFilesCount))
    }

    /**
     * Every append and delete with a retained parent, in every Iceberg fixture: the plan from the
     * parent lands on the child's manifest count, for both contents. Whether the commit wrote a
     * manifest of a content is read off its own summary — an append adds data files and no delete
     * files, a merge-on-read delete the reverse, and a copy-on-write delete that took a whole file
     * out adds neither, so both of its sides run with the newest existing manifest as the first.
     */
    @Test
    fun `across every fixture, the plan from the parent lands on the child's manifest list`() {
        val fixtures = File(repoRoot, "example/iceberg/default").listFiles()!!.filter { it.isDirectory }.map { it.name }.sorted()
        var checked = 0
        for (fixture in fixtures) {
            val m = model(fixture)
            val byId = m.metadatas.flatMap { it.snapshots }.filter { !it.expired }.associateBy { it.metadata.snapshotId }
            for (child in byId.values) {
                val op = child.metadata.summary["operation"]
                if (op != "append" && op != "delete") continue
                val parent = byId[child.metadata.parentSnapshotId] ?: continue
                val options = options(m, child)
                val specId = m.metadatas.filter { md -> md.metadata.currentSnapshotId == child.metadata.snapshotId }
                    .firstOrNull()?.metadata?.defaultSpecId ?: entries(parent).firstOrNull()?.partitionSpecId
                for (content in listOf(ManifestContent.DATA, ManifestContent.DELETES)) {
                    val addedKey = if (content == ManifestContent.DATA) "added-data-files" else "added-delete-files"
                    val writesOne = (child.metadata.summary[addedKey]?.toIntOrNull() ?: 0) > 0
                    val listed = entries(parent)
                    val plan = planManifestMerge(listed, content, if (writesOne) assumedManifestBytes(listed, content) else null, specId, options)
                    assertEquals(count(child, content), plan.outputCount, "$fixture: $op ${child.metadata.snapshotId}, content $content")
                    checked++
                }
            }
        }
        assertTrue(checked >= 60, "checked only $checked snapshot sides")
        assertNotNull(fixtures.find { it == "merged" })
    }
}
