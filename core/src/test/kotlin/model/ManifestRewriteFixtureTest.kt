package model

import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `rewrite_manifests` planned the way the action plans it, held to the one such rewrite the
 * fixtures ran: `maint`'s, whose summary records created 2, kept 0, replaced 4 — two data and
 * two delete manifests into one of each. The plan on the snapshot before it has to say the
 * same; the earlier run the script's header records (one kind of two, one of one: created 1,
 * kept 1) is planted, as is a manifest past the target and a spec the output spec is not.
 */
class ManifestRewriteFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun model(fixture: String) = UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/$fixture").absolutePath))

    private fun snapshots(m: UnifiedTableModel) =
        m.metadatas.flatMap { it.snapshots }.filter { !it.expired }.distinctBy { it.metadata.snapshotId }.sortedBy { it.metadata.sequenceNumber }

    private fun options(m: UnifiedTableModel): ManifestRewriteOptions {
        val latest = m.metadatas.last().metadata
        return ManifestRewriteOptions.forTable(latest.properties, latest.defaultSpecId)
    }

    @Test
    fun `the plan before maint's manifest rewrite says created 2, kept 0, replaced 4, as the rewrite's summary does`() {
        val m = model("maint")
        val all = snapshots(m)
        val rewrite = all.first { it.metadata.summary?.get("manifests-created") != null }
        val before = all.last { (it.metadata.sequenceNumber ?: 0) < (rewrite.metadata.sequenceNumber ?: 0) }
        val plan = planManifestRewrite(before.manifests.map { it.metadata }, options(m))
        assertEquals(rewrite.metadata.summary?.get("manifests-created")?.toInt(), plan.created, plan.toString())
        assertEquals(rewrite.metadata.summary?.get("manifests-kept")?.toInt(), plan.kept, plan.toString())
        assertEquals(rewrite.metadata.summary?.get("manifests-replaced")?.toInt(), plan.replaced, plan.toString())
        assertTrue(plan.kinds.all { it.rewritten && it.matching.size == 2 && it.targetNumManifests == 1 }, plan.kinds.toString())
        // After it, each kind is one manifest within the target: nothing to do.
        val after = planManifestRewrite(rewrite.manifests.map { it.metadata }, options(m))
        assertTrue(!after.rewrites, after.toString())
        assertEquals(listOf("one manifest, within the target", "one manifest, within the target"), after.kinds.map { it.leftAlone })
        assertEquals(2, after.kept)
    }

    @Test
    fun `a kind of two folds and a kind of one is kept, a manifest past the target splits, and another spec's manifest is never matched`() {
        fun manifest(name: String, content: Int, bytes: Long, spec: Int = 0) = ManifestListEntry(
            manifestPath = "/wh/t/metadata/$name.avro", manifestLength = bytes, partitionSpecId = spec, content = content,
            addedFilesCount = 1, existingFilesCount = 0, deletedFilesCount = 0,
        )
        val options = ManifestRewriteOptions(targetManifestSizeBytes = 1000, specId = 1)
        // The script header's first run: two data manifests, one delete manifest — created 1, kept 1, replaced 2.
        val first = planManifestRewrite(listOf(manifest("d1", ManifestContent.DATA, 100, 1), manifest("d2", ManifestContent.DATA, 100, 1), manifest("x1", ManifestContent.DELETES, 100, 1)), options)
        assertEquals(Triple(1, 1, 2), Triple(first.created, first.kept, first.replaced), first.toString())
        // One data manifest of 2,500 bytes against a 1,000-byte target: split into three.
        val split = planManifestRewrite(listOf(manifest("big", ManifestContent.DATA, 2500, 1)), options)
        assertEquals(3, split.kinds.first().targetNumManifests)
        assertTrue(split.kinds.first().rewritten)
        assertEquals(Triple(3, 0, 1), Triple(split.created, split.kept, split.replaced))
        // A spec-0 manifest under an output spec of 1 is kept whatever its size; a v1 manifest without counts refuses the kind.
        val other = planManifestRewrite(listOf(manifest("old", ManifestContent.DATA, 5000, 0), manifest("d1", ManifestContent.DATA, 100, 1)), options)
        assertEquals(1, other.unmatched.size)
        assertEquals(Triple(0, 2, 0), Triple(other.created, other.kept, other.replaced))
        val v1 = planManifestRewrite(listOf(manifest("d1", ManifestContent.DATA, 100, 1).copy(addedFilesCount = null), manifest("d2", ManifestContent.DATA, 100, 1)), options)
        assertEquals(1, v1.refused.size, v1.toString())
        assertNull(v1.kinds.first().takeIf { it.rewritten })
    }

    @Test
    fun `every fixture's current snapshot plans, and a kind of one manifest within the target is always left alone`() {
        var kinds = 0
        FixtureCatalog.iceberg.forEach { fixture ->
            val m = model(fixture)
            val current = m.metadatas.last().metadata.currentSnapshotId ?: return@forEach
            val s = snapshots(m).firstOrNull { it.metadata.snapshotId == current } ?: return@forEach
            val plan = planManifestRewrite(s.manifests.map { it.metadata }, options(m))
            plan.kinds.forEach { k ->
                kinds++
                if (k.matching.size == 1 && k.inputBytes <= plan.options.targetManifestSizeBytes) assertEquals(false, k.rewritten, "$fixture ${k.label}: $k")
                if (k.matching.size >= 2 && k.leftAlone?.startsWith(ManifestRewritePlan.REFUSED_PREFIX) != true) assertEquals(true, k.rewritten, "$fixture ${k.label}: $k")
            }
            assertEquals(s.manifests.size, plan.created.let { plan.kept + plan.replaced }, "$fixture: kept and replaced cover the list")
        }
        assertTrue(kinds >= 60, "$kinds kinds planned")
        assertNotNull(model("maint"))
    }
}
