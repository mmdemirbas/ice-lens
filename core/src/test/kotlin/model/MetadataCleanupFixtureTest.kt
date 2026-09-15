package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `expire_snapshots(clean_expired_metadata => true)`, held to `docs/fixtures/clean-expired-metadata.sql`
 * — the procedure run on the Iceberg 1.10.0 runtime over copies of `respec`, `evolved` and
 * `promoted`: `respec` rewritten whole under its current spec, written to once more and expired
 * down to that commit kept spec 3 alone of four; `evolved` under `retain_last => 2` kept schemas
 * 4 and 5 of six; `promoted` under `retain_last => 1` kept schema 5 alone.
 */
class MetadataCleanupFixtureTest {

    private fun input(name: String) = FixtureCatalog.icebergModel(name).expiryFileInput()

    private fun removedUnder(input: ExpiryFileInput, retainLast: Int): Set<Long> {
        val now = input.metadata.lastUpdatedMs ?: 0L
        return input.metadata.planExpiry(ExpiryOptions(nowMs = now, olderThanMs = now, retainLast = retainLast)).removed.toSet()
    }

    @Test
    fun `respec keeps the spec its current snapshot's carried manifests record and the default, and drops the two nothing wrote under`() {
        val input = input("respec")
        val plan = input.planMetadataCleanup(removedUnder(input, retainLast = 1))
        assertEquals(listOf(1, 2), plan.removedSpecs, "specs 1 and 2 were replaced before any write: all_manifests lists spec 0 and spec 3 only")
        assertEquals("the default spec", plan.specs.single { it.id == 3 }.keptBy)
        assertTrue(plan.specs.single { it.id == 0 }.keptBy!!.startsWith("reached by 1 retained snapshot"), plan.specs.toString())
        assertEquals(emptyList(), plan.removedSchemas, "one schema, the current")
        assertEquals("drops 2 specs (1, 2)", plan.describe())
    }

    /** The run's end state: after the rewrite and one more insert, the retained snapshot lists spec-3 manifests alone, and v9 kept `[3]`. */
    @Test
    fun `respec with only current-spec manifests retained drops every other spec, as the run did`() {
        val input = input("respec")
        val current = input.metadata.currentSnapshotId!!
        val onlyCurrentSpec = input.copy(manifestLists = input.manifestLists.mapValues { (id, list) -> if (id == current) list.filter { it.partitionSpecId == 3 } else list })
        assertTrue(onlyCurrentSpec.manifestLists.getValue(current).isNotEmpty())
        val plan = onlyCurrentSpec.planMetadataCleanup(removedUnder(input, retainLast = 1))
        assertEquals(listOf(0, 1, 2), plan.removedSpecs, "4 0 0 4 3 0, then partition-specs [3]")
    }

    @Test
    fun `evolved under retain_last 2 keeps schemas 4 and 5, and under retain_last 1 the current alone`() {
        val input = input("evolved")
        val two = input.planMetadataCleanup(removedUnder(input, retainLast = 2))
        assertEquals(listOf(0, 1, 2, 3), two.removedSchemas, "schemas [4, 5] afterwards")
        assertEquals("the current schema", two.schemas.single { it.id == 5 }.keptBy)
        assertTrue(two.schemas.single { it.id == 4 }.keptBy!!.startsWith("reached by 1 retained snapshot"))
        assertEquals(emptyList(), two.removedSpecs)
        val one = input.planMetadataCleanup(removedUnder(input, retainLast = 1))
        assertEquals(listOf(0, 1, 2, 3, 4), one.removedSchemas)
        assertEquals("drops 5 schemas (0, 1, 2, 3, 4)", one.describe())
    }

    @Test
    fun `promoted under retain_last 1 keeps schema 5 alone`() {
        val input = input("promoted")
        val plan = input.planMetadataCleanup(removedUnder(input, retainLast = 1))
        assertEquals(listOf(0, 1, 2, 3, 4), plan.removedSchemas, "schemas [5] afterwards")
        assertEquals(emptyList(), plan.removedSpecs)
    }

    /** Nothing removed under an expiry that removes nothing; the default and current never removed; a removed spec is in no retained list. */
    @Test
    fun `across every fixture the default spec and current schema stay, and a removed spec is recorded by no retained manifest`() {
        FixtureCatalog.iceberg.forEach { name ->
            val input = input(name)
            val none = input.planMetadataCleanup(emptySet())
            assertTrue(none.specs.none { it.removed } || name == "respec", "$name: with every snapshot retained only a spec nothing ever wrote under goes: ${none.removedSpecs}")
            val removed = removedUnder(input, retainLast = 1)
            val plan = input.planMetadataCleanup(removed)
            assertTrue(plan.specs.none { it.removed && it.id == input.metadata.defaultSpecId }, name)
            assertTrue(plan.schemas.none { it.removed && it.id == input.metadata.currentSchemaId }, name)
            val retainedSpecs = input.metadata.snapshots.filter { it.snapshotId !in removed }.flatMap { s -> input.manifestLists[s.snapshotId].orEmpty().mapNotNull { it.partitionSpecId } }.toSet()
            plan.removedSpecs.forEach { assertTrue(it !in retainedSpecs, "$name: spec $it removed but recorded by a retained manifest") }
            val retainedSchemas = input.metadata.snapshots.filter { it.snapshotId !in removed }.mapNotNull { it.schemaId }.toSet()
            plan.removedSchemas.forEach { assertTrue(it !in retainedSchemas, "$name: schema $it removed but a retained snapshot was written under it") }
        }
    }
}
