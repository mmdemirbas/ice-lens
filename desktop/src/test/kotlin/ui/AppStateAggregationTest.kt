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
