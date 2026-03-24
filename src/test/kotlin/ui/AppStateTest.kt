package ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import model.WorkspaceItem
import model.WorkspaceTableStatus
import java.io.File
import java.util.prefs.Preferences
import kotlin.test.*

class AppStateTest {

    private lateinit var prefs: Preferences
    private lateinit var state: AppState

    @BeforeTest
    fun setUp() {
        prefs = Preferences.userRoot().node("icelens-test-${System.nanoTime()}")
        state = createState()
    }

    @AfterTest
    fun tearDown() {
        prefs.removeNode()
    }

    private fun createState(prefs: Preferences = this.prefs): AppState {
        return AppState(prefs, CoroutineScope(Dispatchers.Unconfined))
    }

    // ═══════════════════════════════════════════════════════════════
    //  Workspace Management
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `initial state has empty workspace`() {
        assertEquals(emptyList(), state.workspaceItems)
        assertNull(state.selectedTablePath)
        assertNull(state.graphModel)
        assertFalse(state.isLoadingTable)
    }

    @Test
    fun `addWorkspaceRoot adds a warehouse for non-table directory`() {
        val tmpDir = kotlin.io.path.createTempDirectory("ws-test").toFile()
        try {
            state.addWorkspaceRoot(tmpDir.absolutePath)
            assertEquals(1, state.workspaceItems.size)
            assertIs<WorkspaceItem.Warehouse>(state.workspaceItems[0])
            assertEquals(tmpDir.canonicalPath, state.workspaceItems[0].path)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `addWorkspaceRoot adds a SingleTable for Iceberg table directory`() {
        val tmpDir = kotlin.io.path.createTempDirectory("ws-test").toFile()
        try {
            val metaDir = File(tmpDir, "metadata")
            metaDir.mkdirs()
            File(metaDir, "v1.metadata.json").writeText("{}")

            state.addWorkspaceRoot(tmpDir.absolutePath)
            assertEquals(1, state.workspaceItems.size)
            assertIs<WorkspaceItem.SingleTable>(state.workspaceItems[0])
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `addWorkspaceRoot adds a SingleTable for Paimon table directory`() {
        val tmpDir = kotlin.io.path.createTempDirectory("ws-test").toFile()
        try {
            File(tmpDir, "snapshot").mkdirs()
            File(tmpDir, "schema").mkdirs()

            state.addWorkspaceRoot(tmpDir.absolutePath)
            assertEquals(1, state.workspaceItems.size)
            assertIs<WorkspaceItem.SingleTable>(state.workspaceItems[0])
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `addWorkspaceRoot ignores duplicate paths`() {
        val tmpDir = kotlin.io.path.createTempDirectory("ws-test").toFile()
        try {
            state.addWorkspaceRoot(tmpDir.absolutePath)
            state.addWorkspaceRoot(tmpDir.absolutePath)
            assertEquals(1, state.workspaceItems.size)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `addWorkspaceRoot ignores non-existent path`() {
        state.addWorkspaceRoot("/nonexistent/path/that/does/not/exist")
        assertEquals(0, state.workspaceItems.size)
    }

    @Test
    fun `addWorkspaceRoot scans warehouse for nested tables`() {
        val tmpDir = kotlin.io.path.createTempDirectory("ws-test").toFile()
        try {
            // Create nested Iceberg table
            val table1 = File(tmpDir, "db/table1/metadata")
            table1.mkdirs()
            File(table1, "v1.metadata.json").writeText("{}")

            // Create nested Paimon table
            val table2Dir = File(tmpDir, "db/table2")
            File(table2Dir, "snapshot").mkdirs()
            File(table2Dir, "schema").mkdirs()

            state.addWorkspaceRoot(tmpDir.absolutePath)
            assertEquals(1, state.workspaceItems.size)
            val warehouse = state.workspaceItems[0] as WorkspaceItem.Warehouse
            assertEquals(listOf("db/table1", "db/table2"), warehouse.tables)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `addWorkspaceRoot auto-expands warehouse`() {
        val tmpDir = kotlin.io.path.createTempDirectory("ws-test").toFile()
        try {
            state.addWorkspaceRoot(tmpDir.absolutePath)
            val warehouse = state.workspaceItems[0]
            assertTrue(state.workspaceExpandedPaths.contains(warehouse.path))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `removeWorkspaceRoot removes the item`() {
        val tmpDir = kotlin.io.path.createTempDirectory("ws-test").toFile()
        try {
            state.addWorkspaceRoot(tmpDir.absolutePath)
            assertEquals(1, state.workspaceItems.size)

            state.removeWorkspaceRoot(state.workspaceItems[0])
            assertEquals(0, state.workspaceItems.size)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `moveWorkspaceRoot reorders items`() {
        val dir1 = kotlin.io.path.createTempDirectory("ws-test-1").toFile()
        val dir2 = kotlin.io.path.createTempDirectory("ws-test-2").toFile()
        try {
            state.addWorkspaceRoot(dir1.absolutePath)
            state.addWorkspaceRoot(dir2.absolutePath)
            assertEquals(2, state.workspaceItems.size)
            assertEquals(dir1.canonicalPath, state.workspaceItems[0].path)
            assertEquals(dir2.canonicalPath, state.workspaceItems[1].path)

            // Move first item down
            state.moveWorkspaceRoot(state.workspaceItems[0], 1)
            assertEquals(dir2.canonicalPath, state.workspaceItems[0].path)
            assertEquals(dir1.canonicalPath, state.workspaceItems[1].path)
        } finally {
            dir1.deleteRecursively()
            dir2.deleteRecursively()
        }
    }

    @Test
    fun `moveWorkspaceRoot clamps to bounds`() {
        val dir1 = kotlin.io.path.createTempDirectory("ws-test-1").toFile()
        try {
            state.addWorkspaceRoot(dir1.absolutePath)
            // Move beyond bounds - should be no-op
            state.moveWorkspaceRoot(state.workspaceItems[0], -1)
            assertEquals(1, state.workspaceItems.size)
            state.moveWorkspaceRoot(state.workspaceItems[0], 1)
            assertEquals(1, state.workspaceItems.size)
        } finally {
            dir1.deleteRecursively()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Workspace Persistence
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `workspace items are persisted and restored`() {
        val tmpDir = kotlin.io.path.createTempDirectory("ws-test").toFile()
        try {
            state.addWorkspaceRoot(tmpDir.absolutePath)
            assertEquals(1, state.workspaceItems.size)

            // Create a new state with the same prefs - should restore
            val state2 = createState()
            assertEquals(1, state2.workspaceItems.size)
            assertEquals(state.workspaceItems[0].path, state2.workspaceItems[0].path)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `workspace items with missing paths are preserved on restore`() {
        val tmpDir = kotlin.io.path.createTempDirectory("ws-test").toFile()
        try {
            state.addWorkspaceRoot(tmpDir.absolutePath)
            val savedPath = state.workspaceItems[0].path
        } finally {
            tmpDir.deleteRecursively() // Delete before restore
        }
        // Create new state - path no longer exists but item should be preserved
        val state2 = createState()
        assertEquals(1, state2.workspaceItems.size)
    }

    @Test
    fun `expanded paths are persisted and restored`() {
        val tmpDir = kotlin.io.path.createTempDirectory("ws-test").toFile()
        try {
            state.addWorkspaceRoot(tmpDir.absolutePath)
            val path = state.workspaceItems[0].path
            assertTrue(state.workspaceExpandedPaths.contains(path))

            val state2 = createState()
            assertTrue(state2.workspaceExpandedPaths.contains(path))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `last browse directory is persisted and restored`() {
        assertNull(state.lastBrowseDirectory)
        state.updateLastBrowseDirectory("/some/dir")
        assertEquals("/some/dir", state.lastBrowseDirectory)

        val state2 = createState()
        assertEquals("/some/dir", state2.lastBrowseDirectory)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Workspace Status Tracking
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `single table status is EXISTING for valid path`() {
        val tmpDir = kotlin.io.path.createTempDirectory("ws-test").toFile()
        try {
            val metaDir = File(tmpDir, "metadata")
            metaDir.mkdirs()
            File(metaDir, "v1.metadata.json").writeText("{}")

            state.addWorkspaceRoot(tmpDir.absolutePath)
            val path = state.workspaceItems[0].path
            assertEquals(WorkspaceTableStatus.EXISTING, state.singleTableStatuses[path])
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `single table status is DELETED for missing path on restore`() {
        val tmpDir = kotlin.io.path.createTempDirectory("ws-test").toFile()
        val metaDir = File(tmpDir, "metadata")
        metaDir.mkdirs()
        File(metaDir, "v1.metadata.json").writeText("{}")
        state.addWorkspaceRoot(tmpDir.absolutePath)
        tmpDir.deleteRecursively() // Delete before restore

        val state2 = createState()
        assertEquals(1, state2.workspaceItems.size)
        val path = state2.workspaceItems[0].path
        assertEquals(WorkspaceTableStatus.DELETED, state2.singleTableStatuses[path])
    }

    @Test
    fun `warehouse table statuses initialized as EXISTING`() {
        val tmpDir = kotlin.io.path.createTempDirectory("ws-test").toFile()
        try {
            val table1 = File(tmpDir, "table1/metadata")
            table1.mkdirs()
            File(table1, "v1.metadata.json").writeText("{}")

            state.addWorkspaceRoot(tmpDir.absolutePath)
            val warehouse = state.workspaceItems[0] as WorkspaceItem.Warehouse
            val statuses = state.warehouseTableStatuses[warehouse.path]!!
            assertEquals(WorkspaceTableStatus.EXISTING, statuses["table1"])
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `refreshWarehouseTables detects new tables`() {
        val tmpDir = kotlin.io.path.createTempDirectory("ws-test").toFile()
        try {
            state.addWorkspaceRoot(tmpDir.absolutePath)
            val warehouse = state.workspaceItems[0] as WorkspaceItem.Warehouse
            assertEquals(0, warehouse.tables.size)

            // Add a table after adding the warehouse
            val table1 = File(tmpDir, "table1/metadata")
            table1.mkdirs()
            File(table1, "v1.metadata.json").writeText("{}")

            state.refreshWarehouseTables()
            val updatedWarehouse = state.workspaceItems[0] as WorkspaceItem.Warehouse
            assertEquals(listOf("table1"), updatedWarehouse.tables)
            assertEquals(WorkspaceTableStatus.NEW, state.warehouseTableStatuses[warehouse.path]!!["table1"])
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `refreshWarehouseTables detects deleted tables`() {
        val tmpDir = kotlin.io.path.createTempDirectory("ws-test").toFile()
        try {
            // Create a table first
            val tableDir = File(tmpDir, "table1/metadata")
            tableDir.mkdirs()
            File(tableDir, "v1.metadata.json").writeText("{}")

            state.addWorkspaceRoot(tmpDir.absolutePath)
            assertEquals(WorkspaceTableStatus.EXISTING, state.warehouseTableStatuses[state.workspaceItems[0].path]!!["table1"])

            // Delete the table
            File(tmpDir, "table1").deleteRecursively()

            state.refreshWarehouseTables()
            assertEquals(WorkspaceTableStatus.DELETED, state.warehouseTableStatuses[state.workspaceItems[0].path]!!["table1"])
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Table Fingerprinting
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `computeTableFingerprint returns missing for non-existent dir`() {
        assertEquals("missing", state.computeTableFingerprint("/nonexistent/path"))
    }

    @Test
    fun `computeTableFingerprint returns empty for dir without metadata files`() {
        val tmpDir = kotlin.io.path.createTempDirectory("fp-test").toFile()
        try {
            File(tmpDir, "metadata").mkdirs()
            assertEquals("empty", state.computeTableFingerprint(tmpDir.absolutePath))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `computeTableFingerprint changes when metadata file changes`() {
        val tmpDir = kotlin.io.path.createTempDirectory("fp-test").toFile()
        try {
            val metaDir = File(tmpDir, "metadata")
            metaDir.mkdirs()
            val metaFile = File(metaDir, "v1.metadata.json")
            metaFile.writeText("{}")
            val fp1 = state.computeTableFingerprint(tmpDir.absolutePath)

            // Change the file content (and ensure different mtime)
            Thread.sleep(50) // Ensure different lastModified
            metaFile.writeText("{\"changed\": true}")
            val fp2 = state.computeTableFingerprint(tmpDir.absolutePath)

            assertNotEquals(fp1, fp2)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `computeTableFingerprint changes when new metadata file added`() {
        val tmpDir = kotlin.io.path.createTempDirectory("fp-test").toFile()
        try {
            val metaDir = File(tmpDir, "metadata")
            metaDir.mkdirs()
            File(metaDir, "v1.metadata.json").writeText("{}")
            val fp1 = state.computeTableFingerprint(tmpDir.absolutePath)

            File(metaDir, "v2.metadata.json").writeText("{}")
            val fp2 = state.computeTableFingerprint(tmpDir.absolutePath)

            assertNotEquals(fp1, fp2)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Show Rows Toggle
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `updateShowRows persists value`() {
        assertTrue(state.showRows) // default
        state.updateShowRows(false)
        assertFalse(state.showRows)

        val state2 = createState()
        assertFalse(state2.showRows)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Snapshot Filter
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `initial snapshot filter state is empty`() {
        assertTrue(state.selectedSnapshotFilterNodeIds.isEmpty())
        assertTrue(state.snapshotFilterOptions.isEmpty())
        assertTrue(state.allSnapshotFilterNodeIds.isEmpty())
    }

    @Test
    fun `updateSnapshotFilterSelection constrains to available nodes`() {
        // No graph loaded - allSnapshotFilterNodeIds is empty
        state.updateSnapshotFilterSelection(setOf("nonexistent_node"))
        assertTrue(state.selectedSnapshotFilterNodeIds.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════
    //  Graph Model Management
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `setGraphModelAndBump increments revision`() {
        val initialRevision = state.graphRevision
        state.setGraphModelAndBump(null)
        assertEquals(initialRevision + 1, state.graphRevision)
    }

    @Test
    fun `setGraphModelAndBump with null clears graph`() {
        state.setGraphModelAndBump(null)
        assertNull(state.graphModel)
        assertNull(state.visibleGraphModel)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Session Cache
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `session cache is initially empty`() {
        assertTrue(state.sessionCache.isEmpty())
    }

    @Test
    fun `saveCurrentSessionSelection does nothing without selected table`() {
        state.saveCurrentSessionSelection()
        // Should not throw
    }

    // ═══════════════════════════════════════════════════════════════
    //  Error State
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `error message can be set and cleared`() {
        assertNull(state.errorMsg)
        state.errorMsg = "test error"
        assertEquals("test error", state.errorMsg)
        state.errorMsg = null
        assertNull(state.errorMsg)
    }
}
