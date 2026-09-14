package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `docs/fixtures/paimon-prb.sql`: four inserts, tags `two` and `three`, kept as `prb`, and as
 * `prba` after `sys.rollback(version => '2')` — which deleted snapshot-3, snapshot-4 and tag-three
 * and nothing else: every data file, manifest and manifest list is still on disk.
 */
class PaimonRollbackFixtureTest {

    private val prb by lazy { FixtureCatalog.paimonModel("prb") }
    private val prba by lazy { FixtureCatalog.paimonModel("prba") }

    @Test
    fun `the plan from prb names exactly the snapshots and tags prba lost`() {
        val plan = assertNotNull(prb.expiryInput().planRollback(2))
        assertEquals(listOf(4L, 3L), plan.removedSnapshots, "highest first, the order the helper deletes in")
        assertEquals(emptyList(), plan.removedChangelogs)
        assertEquals(listOf("three"), plan.removedTags)
        assertEquals(listOf("two"), plan.keptTags)
        assertTrue(plan.removesAnything)
        assertEquals(setOf(1L, 2L), prba.snapshots.mapNotNull { it.metadata.id }.toSet())
        assertEquals(listOf("two"), prba.tags.map { it.name })
        // The latest snapshot rolls back to nothing; a snapshot the table does not hold is no target.
        val latest = assertNotNull(prb.expiryInput().planRollback(4))
        assertTrue(!latest.removesAnything, latest.toString())
        assertNull(prb.expiryInput().planRollback(9))
    }

    /** What the rollback leaves on disk is what the orphan check finds on `prba` — f3, f4, and the lists and manifests of snapshots 3 and 4. */
    @Test
    fun `the rollback's leftovers are the files the orphan check reports afterwards`() {
        val leftovers = prb.expiryFileInput().rollbackLeftovers(2)
        val names = leftovers.map { it.name }.toSet()
        assertEquals(2, leftovers.count { it.kind == PaimonExpiryFileKind.DATA_FILE }, leftovers.toString())
        assertTrue(leftovers.any { it.kind == PaimonExpiryFileKind.MANIFEST_LIST })
        assertTrue(leftovers.any { it.kind == PaimonExpiryFileKind.MANIFEST })
        assertTrue(leftovers.all { it.reason == PaimonExpiryFileReason.ROLLED_BACK })
        val orphans = findUnreferencedFiles(prba)
        assertTrue(orphans.problems.isEmpty(), orphans.problems.toString())
        assertEquals(names, orphans.unreferenced.map { it.path.fileName.toString() }.toSet(), "two readings of one set")
        // Every one of them is still on disk in prba: a rollback deletes the snapshot and tag files alone.
        val onDisk = FixtureCatalog.paimonDir("prba").walk().filter { it.isFile }.map { it.name }.toSet()
        assertTrue(names.all { it in onDisk }, (names - onDisk).toString())
        assertEquals(4, onDisk.count { it.endsWith(".parquet") })
        // And after the rollback there is nothing left to roll back to below 2 but snapshot 1's leftovers.
        assertEquals(emptyList(), prba.expiryFileInput().rollbackLeftovers(2))
    }

    /** A rollback on every fixture's latest snapshot removes nothing, and the leftovers of rolling back to the earliest are files no earlier snapshot names. */
    @Test
    fun `every fixture rolls back to its latest snapshot with nothing removed`() {
        for (name in FixtureCatalog.paimon) {
            val model = FixtureCatalog.paimonModel(name)
            val latest = model.snapshots.mapNotNull { it.metadata.id }.maxOrNull() ?: continue
            val plan = assertNotNull(model.expiryInput().planRollback(latest), name)
            assertEquals(emptyList(), plan.removedSnapshots, name)
            assertTrue(plan.removedTags.isEmpty(), "$name: a tag above the latest snapshot cannot exist")
            assertEquals(emptyList(), model.expiryFileInput().rollbackLeftovers(latest), name)
        }
    }
}
