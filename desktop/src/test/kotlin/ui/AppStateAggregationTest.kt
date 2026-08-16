package ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import model.GraphNode
import java.io.File
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Expanding a group node, through the shell, at the page size the app actually ships.
 *
 * The table is built with more metadata versions than one page holds rather than by tightening
 * the policy for the test, because the number that matters is the shipped one — a test that
 * passes at a page size nobody runs proves the mechanism and not the product.
 */
class AppStateAggregationTest {

    private lateinit var prefs: Preferences
    private lateinit var tmpDir: File

    @BeforeTest
    fun setUp() {
        prefs = Preferences.userRoot().node("icelens-test-agg-${System.nanoTime()}")
        tmpDir = kotlin.io.path.createTempDirectory("appstate-agg-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        prefs.removeNode()
        tmpDir.deleteRecursively()
    }

    private fun newState() = AppState(prefs, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

    /** A table with [versions] metadata files and no commits — enough to fan out past a page. */
    private fun tableWithMetadataVersions(versions: Int): String {
        val metadataDir = File(tmpDir, "metadata").apply { mkdirs() }
        (1..versions).forEach { version ->
            File(metadataDir, "v$version.metadata.json").writeText(
                """
                {
                  "format-version": 2,
                  "table-uuid": "aggregation-test",
                  "schemas": [{"schema-id": 0, "type": "struct", "fields": []}],
                  "partition-specs": [{"spec-id": 0, "fields": []}],
                  "sort-orders": [{"order-id": 0, "fields": []}],
                  "snapshots": [],
                  "snapshot-log": [], "metadata-log": []
                }
                """.trimIndent()
            )
        }
        File(metadataDir, "version-hint.text").writeText("$versions")
        return tmpDir.canonicalPath
    }

    private fun metadataCount(state: AppState) =
        state.graphModel!!.nodes.filterIsInstance<GraphNode.MetadataNode>().size

    @Test
    fun `a table with more versions than a page draws a group for the rest`() {
        val state = newState()
        state.loadTable(tableWithMetadataVersions(40))

        val graph = assertNotNull(state.graphModel)
        val group = graph.groups.singleOrNull()
        assertNotNull(group, "40 metadata versions should not all be drawn")
        assertEquals(16, group.memberCount)
        assertEquals(24, metadataCount(state))
        assertEquals(16, graph.hiddenNodeCount, "and the graph should say how many are missing")
    }

    @Test
    fun `expanding a group reveals the next page and keeps the rest reachable`() {
        val state = newState()
        state.loadTable(tableWithMetadataVersions(60))

        val first = assertNotNull(state.graphModel!!.groups.singleOrNull())
        assertEquals(24, metadataCount(state))

        state.expandGroup(first.id)

        assertEquals(48, metadataCount(state), "one expansion reveals one more page")
        assertEquals(setOf(first.id), state.expandedGroupIds)
        val second = assertNotNull(state.graphModel!!.groups.singleOrNull())
        assertEquals(12, second.memberCount)
        assertTrue(second.id != first.id, "the remainder gets its own group")

        state.expandGroup(second.id)
        assertEquals(60, metadataCount(state))
        assertEquals(emptyList(), state.graphModel!!.groups, "nothing is left to open")
    }

    @Test
    fun `collapsing goes back to one page`() {
        val state = newState()
        state.loadTable(tableWithMetadataVersions(40))
        state.expandGroup(state.graphModel!!.groups.single().id)
        assertEquals(40, metadataCount(state))

        state.collapseAllGroups()

        assertEquals(24, metadataCount(state))
        assertEquals(emptySet(), state.expandedGroupIds)
    }

    /**
     * The cached graph and the expansion it was built under travel together. Restoring one
     * without the other gives a panel that reports a state the drawing does not have.
     */
    @Test
    fun `switching away and back restores the expansion the graph was built with`() {
        val state = newState()
        val expanded = tableWithMetadataVersions(40)
        state.loadTable(expanded)
        val groupId = state.graphModel!!.groups.single().id
        state.expandGroup(groupId)
        assertEquals(40, metadataCount(state))

        val other = File(tmpDir.parentFile, "other-${System.nanoTime()}").apply { mkdirs() }
        try {
            state.loadTable(other.canonicalPath)
            assertEquals(emptySet(), state.expandedGroupIds, "a different table starts closed")

            state.loadTable(expanded)
            assertEquals(setOf(groupId), state.expandedGroupIds)
            assertEquals(40, metadataCount(state))
        } finally {
            other.deleteRecursively()
        }
    }

    @Test
    fun `expanding a group fully draws every sibling in one rebuild`() {
        val state = newState()
        state.loadTable(tableWithMetadataVersions(100))
        val group = state.graphModel!!.groups.single()
        val revision = state.graphRevision

        state.expandGroupFully(group)

        assertEquals(100, metadataCount(state))
        assertEquals(emptyList(), state.graphModel!!.groups)
        assertEquals(revision + 1, state.graphRevision, "one rebuild, not four")
    }

    // --- Page size ---

    @Test
    fun `a larger page size draws what a smaller one folded away`() {
        val state = newState()
        state.loadTable(tableWithMetadataVersions(40))
        assertEquals(24, metadataCount(state))

        state.updateGraphPageSize(48)

        assertEquals(40, metadataCount(state))
        assertEquals(emptyList(), state.graphModel!!.groups)
        assertEquals(48, state.graphPageSize)
    }

    /**
     * A group id names a page *at a size*. Kept across a change of size it would name a different
     * set of siblings, so the panel would report an expansion the drawing does not have.
     */
    @Test
    fun `changing the page size closes what was open`() {
        val state = newState()
        state.loadTable(tableWithMetadataVersions(60))
        state.expandGroup(state.graphModel!!.groups.single().id)
        assertEquals(48, metadataCount(state))

        state.updateGraphPageSize(16)

        assertEquals(emptySet(), state.expandedGroupIds)
        assertEquals(16, metadataCount(state), "back to one page, at the new size")
    }

    /**
     * The cache holds graphs, and a graph carries the page size it was drawn under. Restoring one
     * built at 24 while the app is set to 48 puts a drawing on screen that disagrees with the
     * badge above it.
     */
    @Test
    fun `a table cached at the old page size is not restored at the new one`() {
        val state = newState()
        val paged = tableWithMetadataVersions(40)
        state.loadTable(paged)
        assertEquals(24, metadataCount(state))

        val other = File(tmpDir.parentFile, "other-${System.nanoTime()}").apply { mkdirs() }
        try {
            state.loadTable(other.canonicalPath)
            state.updateGraphPageSize(48)

            state.loadTable(paged)
            assertEquals(40, metadataCount(state), "re-read under the size in force now")
        } finally {
            other.deleteRecursively()
        }
    }

    @Test
    fun `the page size outlives the session`() {
        newState().updateGraphPageSize(96)
        assertEquals(96, newState().graphPageSize)
    }

    @Test
    fun `expanding a group that is already open changes nothing`() {
        val state = newState()
        state.loadTable(tableWithMetadataVersions(40))
        val groupId = state.graphModel!!.groups.single().id

        state.expandGroup(groupId)
        val revision = state.graphRevision
        state.expandGroup(groupId)

        assertEquals(revision, state.graphRevision, "no rebuild for a no-op")
    }
}
