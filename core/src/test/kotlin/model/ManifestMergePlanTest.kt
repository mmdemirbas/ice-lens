package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `ManifestMergeManager` one rule at a time, on lists made up for the purpose — the branches a
 * kilobyte-manifest fixture never reaches: bins closing at the target size, a full bin merging
 * without the first manifest whatever the count, and the packing running from the oldest end.
 */
class ManifestMergePlanTest {

    private fun manifest(path: String, length: Long, spec: Int = 0, content: Int = ManifestContent.DATA, added: Int = 1, existing: Int = 0) =
        ManifestListEntry(manifestPath = path, manifestLength = length, partitionSpecId = spec, content = content, addedFilesCount = added, existingFilesCount = existing, deletedFilesCount = 0)

    private val mb = 1L shl 20
    private val defaults = ManifestMergeOptions()

    @Test
    fun `defaults are the ones TableProperties ships, read off the table`() {
        assertEquals(ManifestMergeOptions(8 * mb, 100, true), defaults)
        val tuned = ManifestMergeOptions.forTable(mapOf("commit.manifest.target-size-bytes" to "1048576", "commit.manifest.min-count-to-merge" to "2", "commit.manifest-merge.enabled" to "false"))
        assertEquals(ManifestMergeOptions(mb, 2, false), tuned)
    }

    @Test
    fun `packing runs from the oldest end, so the under-filled bin is the newest one`() {
        // Newest first, as a manifest list is: three of 5 MB under an 8 MB target, plus a new 1 MB.
        val listed = listOf(manifest("m3", 5 * mb), manifest("m2", 5 * mb), manifest("m1", 5 * mb))
        val plan = planManifestMerge(listed, ManifestContent.DATA, mb, 0, defaults.copy(minCountToMerge = 2))
        assertEquals(listOf(listOf(null, "m3"), listOf("m2"), listOf("m1")), plan.bins.map { b -> b.manifests.map { it.entry?.manifestPath } })
        assertEquals(listOf(ManifestMergeVerdict.MERGED, ManifestMergeVerdict.ALONE, ManifestMergeVerdict.ALONE), plan.bins.map { it.verdict })
        assertEquals(3, plan.outputCount)
        // Under the default count the newest bin is kept and the lone ones stay lone: nothing merges.
        val bare = planManifestMerge(listed, ManifestContent.DATA, mb, 0, defaults)
        assertEquals(listOf(ManifestMergeVerdict.UNDER_MIN_COUNT, ManifestMergeVerdict.ALONE, ManifestMergeVerdict.ALONE), bare.bins.map { it.verdict })
        assertEquals(4, bare.outputCount)
        assertEquals("nothing merges", bare.describe)
    }

    @Test
    fun `a bin that fills the target without the first manifest merges whatever the count`() {
        // Ten of 1 MB: from the oldest end, eight fill a bin; the two newest and the new one are the other.
        val listed = (10 downTo 1).map { manifest("m$it", mb) }
        val plan = planManifestMerge(listed, ManifestContent.DATA, mb, 0, defaults)
        assertEquals(listOf(3, 8), plan.bins.map { it.manifests.size })
        assertEquals(listOf(ManifestMergeVerdict.UNDER_MIN_COUNT, ManifestMergeVerdict.MERGED), plan.bins.map { it.verdict })
        assertEquals(listOf(true, false), plan.bins.map { it.holdsFirst })
        assertEquals(4, plan.outputCount)
        assertEquals("8 of 11 merge into 1", plan.describe)
    }

    @Test
    fun `the new manifest joins its own spec's group, and the groups come in descending spec order`() {
        val listed = listOf(manifest("b2", mb, spec = 1), manifest("a2", mb, spec = 0), manifest("b1", mb, spec = 1), manifest("a1", mb, spec = 0))
        val plan = planManifestMerge(listed, ManifestContent.DATA, mb, 2, defaults)
        assertEquals(listOf(2, 1, 0), plan.bins.map { it.specId })
        assertEquals(listOf(ManifestMergeVerdict.ALONE, ManifestMergeVerdict.MERGED, ManifestMergeVerdict.MERGED), plan.bins.map { it.verdict })
        assertEquals(3, plan.outputCount)
    }

    @Test
    fun `with no new manifest the newest existing one is the first, and an all-deleted manifest is dropped first`() {
        val listed = listOf(manifest("m2", mb), manifest("dead", mb, added = 0, existing = 0), manifest("m1", mb))
        val plan = planManifestMerge(listed, ManifestContent.DATA, null, 0, defaults.copy(minCountToMerge = 2))
        assertEquals(listOf("m2", "m1"), plan.input.map { it.entry?.manifestPath })
        assertEquals(listOf(ManifestMergeVerdict.MERGED), plan.bins.map { it.verdict })
        assertEquals(true, plan.bins.single().holdsFirst)
        // A null count reads as "has some", which is ManifestFile.hasAddedFiles' own rule.
        val unknown = listOf(ManifestListEntry(manifestPath = "v1", manifestLength = mb))
        assertEquals(1, planManifestMerge(unknown, ManifestContent.DATA, null, 0, defaults).input.size)
    }

    @Test
    fun `the other content is left out, and disabled merging returns the list as it is`() {
        val listed = listOf(manifest("d1", mb, content = ManifestContent.DELETES), manifest("m1", mb))
        val data = planManifestMerge(listed, ManifestContent.DATA, mb, 0, defaults.copy(minCountToMerge = 2))
        assertEquals(listOf(null, "m1"), data.input.map { it.entry?.manifestPath })
        val off = planManifestMerge(listed, ManifestContent.DATA, mb, 0, defaults.copy(enabled = false))
        assertEquals(emptyList(), off.bins)
        assertEquals(2, off.outputCount)
        assertNull(planManifestMerge(emptyList(), ManifestContent.DELETES, null, 0, defaults).bins.firstOrNull())
        assertEquals(mb, assumedManifestBytes(listed, ManifestContent.DELETES))
        assertEquals(0L, assumedManifestBytes(emptyList(), ManifestContent.DATA))
    }
}
