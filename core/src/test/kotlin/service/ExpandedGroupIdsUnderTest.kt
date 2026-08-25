package service

import model.AggregationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which expanded pages belong to one parent — the lookup a per-parent collapse needs.
 *
 * There is deliberately no string parse here, and these are the cases that say why. A group id is
 * `grp_<parentId>_<kind>_<page>`, and a parent id has underscores of its own, so taking the parent
 * back out means guessing where the kind starts. The kinds a parent id can *contain* are what makes
 * that guess wrong silently rather than loudly.
 */
class ExpandedGroupIdsUnderTest {

    private fun id(parent: String, kind: AggregationKind, page: Int) =
        GraphAggregation.groupId(parent, kind, page)

    @Test
    fun `only this parent's pages come back`() {
        val mine = setOf(
            id("snap_1", AggregationKind.MANIFEST, 1),
            id("snap_1", AggregationKind.MANIFEST, 2),
        )
        val theirs = setOf(
            id("snap_2", AggregationKind.MANIFEST, 1),
            id("table_root", AggregationKind.METADATA, 1),
        )
        assertEquals(mine, GraphAggregation.expandedGroupIdsUnder("snap_1", mine + theirs))
    }

    @Test
    fun `a parent with several kinds of child gets all of them`() {
        val expanded = setOf(
            id("table_root", AggregationKind.METADATA, 1),
            id("table_root", AggregationKind.SNAPSHOT, 1),
            id("table_root", AggregationKind.SNAPSHOT, 2),
        )
        assertEquals(expanded, GraphAggregation.expandedGroupIdsUnder("table_root", expanded))
    }

    /**
     * A parent id ending in something a kind key also spells.
     *
     * `pman_2` ends in the characters of no kind, but `man_3` with kind `manifest` produces
     * `grp_man_3_manifest_1`, and a parse looking for the last `_`-delimited kind has two
     * plausible readings on ids like these. Generating cannot: the id either was built for this
     * parent or was not.
     */
    @Test
    fun `a parent id that overlaps a kind key is not confused for another parent`() {
        val real = id("man_3", AggregationKind.MANIFEST, 1)
        val other = id("man_3_manifest", AggregationKind.FILE, 1)
        val expanded = setOf(real, other)

        assertEquals(setOf(real), GraphAggregation.expandedGroupIdsUnder("man_3", expanded))
        assertEquals(setOf(other), GraphAggregation.expandedGroupIdsUnder("man_3_manifest", expanded))
    }

    @Test
    fun `nothing expanded means nothing to collapse`() {
        assertTrue(GraphAggregation.expandedGroupIdsUnder("snap_1", emptySet()).isEmpty())
        assertTrue(
            GraphAggregation.expandedGroupIdsUnder(
                "snap_9",
                setOf(id("snap_1", AggregationKind.MANIFEST, 1)),
            ).isEmpty(),
            "a parent nobody expanded has nothing of its own in the set",
        )
    }

    /**
     * Page numbers past the set's size cannot be reached, and do not need to be.
     *
     * The candidate range is `1..expanded.size` because a page id is only in the set if something
     * put it there, and nothing adds more ids than there are pages. A set of three cannot contain
     * page 4 of anything.
     */
    @Test
    fun `every expanded page is found however high the page numbers go`() {
        val expanded = (1..40).mapTo(mutableSetOf()) { id("man_7", AggregationKind.FILE, it) }
        assertEquals(expanded, GraphAggregation.expandedGroupIdsUnder("man_7", expanded))
    }
}
