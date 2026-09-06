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
        return AppState(prefs, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
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

    /**
     * A root removed while a sweep was running does not come back.
     *
     * The sweep is a filesystem walk on another thread now, so between starting it and folding it
     * in the reader can edit the list. A result carrying finished *items* would put back whatever
     * the list held when it started; [WorkspaceScan] is keyed by path and folded over the list as
     * it is at that moment, which is what makes the removal stick.
     */
    @Test
    fun `a scan started before a root was removed does not bring it back`() {
        val kept = kotlin.io.path.createTempDirectory("ws-kept").toFile()
        val removed = kotlin.io.path.createTempDirectory("ws-removed").toFile()
        try {
            listOf(kept, removed).forEach { root ->
                File(root, "table1/metadata").mkdirs()
                File(root, "table1/metadata/v1.metadata.json").writeText("{}")
                state.addWorkspaceRoot(root.absolutePath)
            }
            assertEquals(2, state.workspaceItems.size)

            val inFlight = scanWorkspace(state.workspaceItems)
            state.removeWorkspaceRoot(state.workspaceItems.first { it.path.contains("ws-removed") })
            state.applyWorkspaceScan(inFlight)

            assertEquals(1, state.workspaceItems.size, "the removed root should stay removed")
            assertTrue(state.workspaceItems.single().path.contains("ws-kept"))
        } finally {
            kept.deleteRecursively()
            removed.deleteRecursively()
        }
    }

    /**
     * A root added while a sweep was running is left alone, not reported empty.
     *
     * The sweep never looked at it, and "no tables found" and "not looked at" are the same absence
     * in a map — so the fold has to tell them apart by the key being missing rather than by the
     * value being empty, or a warehouse full of tables blinks empty for one polling interval.
     */
    @Test
    fun `a root added after a scan started keeps its tables`() {
        val first = kotlin.io.path.createTempDirectory("ws-first").toFile()
        val late = kotlin.io.path.createTempDirectory("ws-late").toFile()
        try {
            File(first, "table1/metadata").mkdirs()
            File(first, "table1/metadata/v1.metadata.json").writeText("{}")
            state.addWorkspaceRoot(first.absolutePath)

            val inFlight = scanWorkspace(state.workspaceItems)

            File(late, "table2/metadata").mkdirs()
            File(late, "table2/metadata/v1.metadata.json").writeText("{}")
            state.addWorkspaceRoot(late.absolutePath)
            state.applyWorkspaceScan(inFlight)

            val lateItem = state.workspaceItems.first { it.path.contains("ws-late") } as WorkspaceItem.Warehouse
            assertEquals(listOf("table2"), lateItem.tables, "the sweep never saw this root, so it must not empty it")
        } finally {
            first.deleteRecursively()
            late.deleteRecursively()
        }
    }

    /**
     * A store that could not be read keeps the tables it last had, and says why.
     *
     * This is the shape a refused key arrives in, and both halves matter. The tables are still
     * there — nothing was deleted, the key stopped working — so emptying the list would report a
     * data loss that did not happen. And the store's own message is the only diagnosis anyone will
     * ever get, because the sweep runs on a timer and the warehouse has nothing to open.
     */
    @Test
    fun `a root the store refused keeps its tables and carries the reason`() {
        val warehouse = kotlin.io.path.createTempDirectory("ws-refused").toFile()
        try {
            File(warehouse, "orders/metadata").mkdirs()
            File(warehouse, "orders/metadata/v1.metadata.json").writeText("{}")
            state.addWorkspaceRoot(warehouse.absolutePath)
            state.applyWorkspaceScan(scanWorkspace(state.workspaceItems))
            // From the workspace, not from the temp dir: a root is stored canonicalised, and on
            // macOS /var canonicalises to /private/var — a message filed under the other spelling
            // would never be cleared by a sweep, and the test would be asserting about a key
            // nothing else uses.
            val path = state.workspaceItems.single().path
            assertEquals(listOf("orders"), (state.workspaceItems.single() as WorkspaceItem.Warehouse).tables)

            val refused = WorkspaceScan(
                warehouseTables = emptyMap(),
                singleTableExists = emptyMap(),
                unreachable = mapOf(path to "Access denied — check the key for this bucket"),
            )
            state.applyWorkspaceScan(refused)

            assertEquals(
                listOf("orders"),
                (state.workspaceItems.single() as WorkspaceItem.Warehouse).tables,
                "the tables are still in the bucket; only the key stopped working",
            )
            assertEquals("Access denied — check the key for this bucket", state.unreachableRoots[path])
        } finally {
            warehouse.deleteRecursively()
        }
    }

    /** And the message goes as soon as a sweep reads the root, so it never outlives the failure. */
    @Test
    fun `a root that comes back is no longer reported unreachable`() {
        val warehouse = kotlin.io.path.createTempDirectory("ws-recovered").toFile()
        try {
            File(warehouse, "orders/metadata").mkdirs()
            File(warehouse, "orders/metadata/v1.metadata.json").writeText("{}")
            state.addWorkspaceRoot(warehouse.absolutePath)
            val path = state.workspaceItems.single().path
            state.applyWorkspaceScan(
                WorkspaceScan(emptyMap(), emptyMap(), mapOf(path to "Access denied"))
            )
            assertTrue(path in state.unreachableRoots)

            state.applyWorkspaceScan(scanWorkspace(state.workspaceItems))
            assertTrue(path !in state.unreachableRoots, "a sweep that read the root answers the question")
        } finally {
            warehouse.deleteRecursively()
        }
    }

    /** A root the sweep skipped keeps whatever message it had — absence is not an answer. */
    @Test
    fun `a sweep that skipped a root leaves its message alone`() {
        val warehouse = kotlin.io.path.createTempDirectory("ws-skipped").toFile()
        try {
            state.addWorkspaceRoot(warehouse.absolutePath)
            val path = state.workspaceItems.single().path
            state.applyWorkspaceScan(WorkspaceScan(emptyMap(), emptyMap(), mapOf(path to "Access denied")))
            state.applyWorkspaceScan(WorkspaceScan(emptyMap(), emptyMap(), emptyMap()))
            assertEquals("Access denied", state.unreachableRoots[path])
        } finally {
            warehouse.deleteRecursively()
        }
    }

    /**
     * A sweep that skipped the remote roots does not drop the badges of the tables under them.
     *
     * The badges are keyed by *table* while "covered" is a set of *roots*, so the rule that keeps
     * a skipped root's state has to be applied through the prefix rather than by key equality —
     * otherwise every table's badge disappears on each of the nine local sweeps between two remote
     * ones, and the chips blink.
     */
    @Test
    fun `a sweep that skipped a remote root keeps the badges under it`() {
        val local = kotlin.io.path.createTempDirectory("ws-badges").toFile()
        try {
            state.addWorkspaceRoot(local.absolutePath)
            state.applyWorkspaceScan(
                WorkspaceScan(
                    warehouseTables = mapOf("s3://warehouse/db" to listOf("mor")),
                    singleTableExists = emptyMap(),
                    tableFormats = mapOf("s3://warehouse/db/mor" to "ICE"),
                )
            )
            assertEquals("ICE", state.remoteTableFormats["s3://warehouse/db/mor"])

            // The next sweep is a local-only one: the remote root is not in any of its maps.
            state.applyWorkspaceScan(scanWorkspace(state.workspaceItems, includeRemote = false))
            assertEquals(
                "ICE",
                state.remoteTableFormats["s3://warehouse/db/mor"],
                "the sweep never looked at that root, so it said nothing about its tables",
            )
        } finally {
            local.deleteRecursively()
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

    // C-2 regression: Paimon fingerprint must reflect snapshot/schema files,
    // not always be "missing". Without this, cache invalidation never fires.
    @Test
    fun `computeTableFingerprint detects Paimon snapshot files`() {
        val tmpDir = kotlin.io.path.createTempDirectory("fp-paimon").toFile()
        try {
            val snapshotDir = File(tmpDir, "snapshot").apply { mkdirs() }
            val schemaDir = File(tmpDir, "schema").apply { mkdirs() }
            File(snapshotDir, "snapshot-1").writeText("{}")
            File(schemaDir, "schema-0").writeText("{}")

            val fp1 = state.computeTableFingerprint(tmpDir.absolutePath)
            assertNotEquals("missing", fp1)
            assertNotEquals("empty", fp1)

            // New snapshot file -> fingerprint changes
            Thread.sleep(10)
            File(snapshotDir, "snapshot-2").writeText("{}")
            val fp2 = state.computeTableFingerprint(tmpDir.absolutePath)
            assertNotEquals(fp1, fp2)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `computeTableFingerprint changes when Paimon schema file changes`() {
        val tmpDir = kotlin.io.path.createTempDirectory("fp-paimon-schema").toFile()
        try {
            val snapshotDir = File(tmpDir, "snapshot").apply { mkdirs() }
            val schemaDir = File(tmpDir, "schema").apply { mkdirs() }
            File(snapshotDir, "snapshot-1").writeText("{}")
            val schemaFile = File(schemaDir, "schema-0")
            schemaFile.writeText("{\"id\": 0}")

            val fp1 = state.computeTableFingerprint(tmpDir.absolutePath)
            Thread.sleep(50)
            schemaFile.writeText("{\"id\": 0, \"comment\": \"updated\"}")
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

    // M-7 regression: session cache must be bounded so unbounded table-switching doesn't OOM.
    @Test
    fun `session cache evicts oldest entry past max size`() {
        // Pre-load synthetic sessions to test bounding without going through loadTable.
        val cache = state.sessionCache
        val graph = model.GraphModel(emptyList(), emptyList(), 1.0, 1.0)
        // The implementation caps at 5; insert 7 distinct keys.
        for (i in 1..7) {
            cache["key-$i"] = TableSession(graph = graph, fingerprint = "fp-$i")
        }
        assertTrue(cache.size <= 5, "session cache must not grow unbounded; size=${cache.size}")
        // The two oldest keys (1, 2) should have been evicted.
        assertNull(cache["key-1"])
        assertNull(cache["key-2"])
        assertNotNull(cache["key-7"])
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
