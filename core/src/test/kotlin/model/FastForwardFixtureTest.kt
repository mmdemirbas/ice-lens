package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `fast_forward` on both formats, held to the runs recorded in `docs/fixtures/fast-forward.sql`.
 *
 * Iceberg: on `sweepb`, `dev` sits on snapshot 1 and `main` four commits past it, so
 * `fast_forward(dev → main)` moved `dev` to main's tip (`dev 8337608489946395297 1069833806646654058`),
 * `fast_forward(main → dev)` was refused (`Cannot fast-forward: main is not an ancestor of dev`),
 * a second `dev → main` reported the same snapshot twice, and `feature → main` created the branch
 * (`feature NULL 1069833806646654058`). On `branched`, whose `audit` forked from snapshot 1 while
 * main moved on, every direction was refused and `main → prod` (a tag on main's own tip) was
 * nothing to do.
 *
 * Paimon: `brf` is `br` after `sys.fast_forward(table, branch => 'dev')` — main's snapshot-3 gone,
 * its snapshot-2 overwritten by dev's, `tag-base` rewritten from the branch's copy, `SELECT *`
 * reading k 1, 2, 3, 5 — and the same call for `empty` refused with `Cannot fast forward branch
 * empty, because it does not have snapshot`.
 */
class FastForwardFixtureTest {

    private val sweepb by lazy { FixtureCatalog.icebergModel("sweepb").metadatas.last().metadata }
    private val branched by lazy { FixtureCatalog.icebergModel("branched").metadatas.last().metadata }

    @Test
    fun `iceberg moves a branch whose tip is an ancestor of the target, and refuses the other direction`() {
        val refused = sweepb.planFastForward("main", "dev")
        assertEquals(IcebergFastForwardVerdict.NOT_AN_ANCESTOR, refused.verdict)
        assertTrue(refused.reason.startsWith("Cannot fast-forward: main is not an ancestor of dev"), refused.reason)
        val moved = sweepb.planFastForward("dev", "main")
        assertEquals(IcebergFastForwardVerdict.MOVES, moved.verdict)
        assertEquals(8337608489946395297L, moved.from)
        assertEquals(1069833806646654058L, moved.target)
        assertEquals(3, moved.gained.size, "the three commits between dev's tip and main's")
        assertEquals(moved.target, moved.gained.first())
        // A second call: dev is where main is.
        val after = sweepb.copy(refs = sweepb.refs + ("dev" to sweepb.refs.getValue("main")))
        assertEquals(IcebergFastForwardVerdict.NOTHING_TO_DO, after.planFastForward("dev", "main").verdict)
        val created = sweepb.planFastForward("feature", "main")
        assertEquals(IcebergFastForwardVerdict.CREATES, created.verdict)
        assertNull(created.from)
        assertEquals(1069833806646654058L, created.target)
        assertEquals(IcebergFastForwardVerdict.NO_SUCH_REF, sweepb.planFastForward("dev", "nope").verdict)
    }

    @Test
    fun `two lines that both moved on cannot be fast-forwarded either way`() {
        assertEquals(IcebergFastForwardVerdict.NOT_AN_ANCESTOR, branched.planFastForward("main", "audit").verdict)
        assertEquals(IcebergFastForwardVerdict.NOT_AN_ANCESTOR, branched.planFastForward("audit", "main").verdict)
        assertEquals(IcebergFastForwardVerdict.NOT_AN_ANCESTOR, branched.planFastForward("audit", "v1").verdict)
        assertEquals(IcebergFastForwardVerdict.NOTHING_TO_DO, branched.planFastForward("main", "prod").verdict)
        assertEquals(IcebergFastForwardVerdict.NOT_A_BRANCH, branched.planFastForward("prod", "main").verdict)
        val matrix = branched.fastForwardPlans()
        assertEquals(8, matrix.size, "two branches against four other refs")
        assertEquals(listOf("main", "main", "main", "main", "audit", "audit", "audit", "audit"), matrix.map { it.branch })
        assertTrue(matrix.none { it.moves }, matrix.joinToString { "${it.branch}→${it.to} ${it.verdict}" })
    }

    @Test
    fun `every planned move on every iceberg fixture is along the target's ancestry`() {
        var moves = 0
        FixtureCatalog.iceberg.forEach { name ->
            val m = FixtureCatalog.icebergModel(name).metadatas.last().metadata
            val byId = m.snapshots.associateBy { it.snapshotId }
            m.fastForwardPlans().forEach { plan ->
                if (plan.verdict == IcebergFastForwardVerdict.MOVES) {
                    moves++
                    val ancestry = generateSequence(byId[plan.target]) { byId[it.parentSnapshotId] }.map { it.snapshotId }.toList()
                    assertTrue(plan.from in ancestry, "$name: ${plan.branch} → ${plan.to}")
                    assertEquals(ancestry.takeWhile { it != plan.from }, plan.gained, "$name: ${plan.branch} → ${plan.to}")
                }
            }
        }
        assertEquals(1, moves, "sweepb's dev → main is the corpus's one fast-forwardable pair")
    }

    private val br by lazy { FixtureCatalog.paimonModel("br") }
    private val brf by lazy { FixtureCatalog.paimonModel("brf") }

    @Test
    fun `paimon replaces main from the branch's earliest snapshot on, and brf is what that left`() {
        val plan = br.expiryFileInput().planFastForward("dev")!!
        assertNull(plan.refused)
        assertEquals(1L, plan.earliestId)
        assertEquals(listOf(3L, 2L, 1L), plan.removedSnapshots)
        assertEquals(listOf(0), plan.removedSchemas)
        assertEquals(listOf("base"), plan.removedTags)
        assertEquals(listOf(1L, 2L), plan.arrivingSnapshots)
        assertEquals(listOf(0), plan.arrivingSchemas)
        assertEquals(listOf("base"), plan.arrivingTags)
        assertEquals(listOf(1L, 2L), plan.overwrittenSnapshots)
        assertEquals(listOf(1L), plan.identicalSnapshots, "dev's snapshot 1 is main's, copied at the branch's creation")
        assertEquals(2, plan.droppedCommits)
        // brf: main is dev's line now, and the branch directory is still there.
        assertEquals(listOf(1L, 2L), brf.snapshots.mapNotNull { it.metadata.id }.sorted())
        val dev = br.branches.single { it.name == "dev" }
        assertEquals(dev.snapshots.map { it.metadata.deltaManifestList }, brf.snapshots.sortedBy { it.metadata.id }.map { it.metadata.deltaManifestList })
        assertEquals(listOf("base"), brf.tags.map { it.name })
        assertEquals(setOf("dev", "empty"), brf.branches.map { it.name }.toSet())
    }

    /** What the fast-forward leaves on disk is what the orphan check finds on `brf`: main's k = 4 and k = 6 files with their manifests and lists. */
    @Test
    fun `the leftovers are what the orphan check reports on brf, and brf itself has none`() {
        val leftovers = br.expiryFileInput().planFastForward("dev")!!.leftovers
        assertEquals(
            mapOf(PaimonExpiryFileKind.DATA_FILE to 2, PaimonExpiryFileKind.MANIFEST to 2, PaimonExpiryFileKind.MANIFEST_LIST to 4),
            leftovers.groupingBy { it.kind }.eachCount(),
        )
        assertTrue(leftovers.all { it.reason == PaimonExpiryFileReason.FAST_FORWARDED })
        val names = leftovers.map { it.name }.toSet()
        val orphans = findUnreferencedFiles(brf)
        assertEquals(names, orphans.unreferenced.map { it.path.fileName.toString() }.toSet(), "two readings of one set")
        val onDisk = FixtureCatalog.paimonDir("brf").walk().filter { it.isFile }.map { it.name }.toSet()
        assertTrue(names.all { it in onDisk }, "a fast-forward deletes snapshot, schema and tag files alone")
        // On brf the branch and main hold the same files, so a second call would leave nothing.
        val again = brf.expiryFileInput().planFastForward("dev")!!
        assertEquals(listOf(2L, 1L), again.removedSnapshots)
        assertEquals(listOf(1L, 2L), again.identicalSnapshots)
        assertEquals(0, again.droppedCommits)
        assertEquals(emptyList(), again.leftovers)
    }

    @Test
    fun `main, an empty branch and an unknown branch are refused or unknown`() {
        val input = br.expiryFileInput()
        assertEquals("Branch name 'main' do not use in fast-forward.", input.planFastForward("main")?.refused)
        val empty = input.planFastForward("empty")!!
        assertEquals("Cannot fast forward branch empty, because it does not have snapshot.", empty.refused)
        assertEquals(emptyList(), empty.removedSnapshots)
        assertNull(input.planFastForward("nope"))
    }
}
