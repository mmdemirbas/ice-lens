package ui

import java.io.File
import model.WorkspaceItem
import model.WorkspaceTableStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceUtilsTest {

    @Test
    fun `isTableDirectory returns false for non-existent directory`() {
        assertFalse(isTableDirectory(java.io.File("/nonexistent/path")))
    }

    @Test
    fun `isTableDirectory returns true for Iceberg directory`() {
        val tmpDir = kotlin.io.path.createTempDirectory("iceberg-test").toFile()
        try {
            val metaDir = java.io.File(tmpDir, "metadata")
            metaDir.mkdirs()
            java.io.File(metaDir, "v1.metadata.json").writeText("{}")
            assertTrue(isTableDirectory(tmpDir))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `isTableDirectory returns true for Paimon directory`() {
        val tmpDir = kotlin.io.path.createTempDirectory("paimon-test").toFile()
        try {
            java.io.File(tmpDir, "snapshot").mkdirs()
            java.io.File(tmpDir, "schema").mkdirs()
            assertTrue(isTableDirectory(tmpDir))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `isTableDirectory returns false for plain directory`() {
        val tmpDir = kotlin.io.path.createTempDirectory("plain-test").toFile()
        try {
            assertFalse(isTableDirectory(tmpDir))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `deduplicateWorkspaceItems removes duplicates`() {
        val items = listOf(
            WorkspaceItem.SingleTable("/tmp/a", "a"),
            WorkspaceItem.SingleTable("/tmp/b", "b"),
            WorkspaceItem.SingleTable("/tmp/a", "a"),
        )
        val result = deduplicateWorkspaceItems(items)
        assertEquals(2, result.size)
        assertEquals("/tmp/a", result[0].path)
        assertEquals("/tmp/b", result[1].path)
    }

    @Test
    fun `initialWarehouseTableStatuses creates EXISTING entries`() {
        val items = listOf(
            WorkspaceItem.Warehouse("/tmp/wh", "wh", tables = listOf("t1", "t2")),
            WorkspaceItem.SingleTable("/tmp/single", "single"),
        )
        val result = initialWarehouseTableStatuses(items)
        assertEquals(1, result.size)
        val statuses = result["/tmp/wh"]!!
        assertEquals(WorkspaceTableStatus.EXISTING, statuses["t1"])
        assertEquals(WorkspaceTableStatus.EXISTING, statuses["t2"])
    }

    @Test
    fun `scanForTables finds iceberg tables`() {
        val tmpDir = kotlin.io.path.createTempDirectory("warehouse-test").toFile()
        try {
            // Create a valid iceberg table
            val table1 = java.io.File(tmpDir, "table1")
            val meta1 = java.io.File(table1, "metadata")
            meta1.mkdirs()
            java.io.File(meta1, "v1.metadata.json").writeText("{}")

            // Create a non-iceberg directory
            val notTable = java.io.File(tmpDir, "not_a_table")
            notTable.mkdirs()

            val tables = scanForTables(tmpDir)
            assertEquals(listOf("table1"), tables)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `scanForTables finds nested tables recursively`() {
        val tmpDir = kotlin.io.path.createTempDirectory("warehouse-test").toFile()
        try {
            // Create nested iceberg table at depth 2
            val db = java.io.File(tmpDir, "db")
            val table1 = java.io.File(db, "table1")
            val meta1 = java.io.File(table1, "metadata")
            meta1.mkdirs()
            java.io.File(meta1, "v1.metadata.json").writeText("{}")

            // Create nested paimon table at depth 3
            val ns = java.io.File(tmpDir, "ns")
            val schema = java.io.File(ns, "schema1")
            val table2 = java.io.File(schema, "table2")
            java.io.File(table2, "snapshot").mkdirs()
            java.io.File(table2, "schema").mkdirs()

            // Create a non-table directory
            val notTable = java.io.File(tmpDir, "empty")
            notTable.mkdirs()

            val tables = scanForTables(tmpDir)
            assertEquals(listOf("db/table1", "ns/schema1/table2"), tables)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `scanForTables skips hidden directories`() {
        val tmpDir = kotlin.io.path.createTempDirectory("warehouse-test").toFile()
        try {
            // Create a table inside a hidden directory — should be skipped
            val hidden = java.io.File(tmpDir, ".hidden")
            val table1 = java.io.File(hidden, "table1")
            val meta1 = java.io.File(table1, "metadata")
            meta1.mkdirs()
            java.io.File(meta1, "v1.metadata.json").writeText("{}")

            // Create a visible table
            val table2 = java.io.File(tmpDir, "table2")
            val meta2 = java.io.File(table2, "metadata")
            meta2.mkdirs()
            java.io.File(meta2, "v1.metadata.json").writeText("{}")

            val tables = scanForTables(tmpDir)
            assertEquals(listOf("table2"), tables)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `scanForTables returns empty for non-existent directory`() {
        val tables = scanForTables(java.io.File("/nonexistent/path"))
        assertEquals(emptyList(), tables)
    }

    /**
     * A remote root can be left out of a sweep, and leaving it out is not the same as finding it
     * empty.
     *
     * A local warehouse is a directory walk against a warm page cache. A remote one is two
     * recursive globs against object storage — on the three-second timer that is a request every
     * three seconds for as long as the window is open, against a store that bills per request. So
     * remote roots are swept on a slower cadence, and this is the mechanism: they are **omitted**
     * from both maps, which the fold already reads as "not covered" and leaves alone.
     */
    @Test
    fun `a sweep can skip remote roots, and omits them rather than reporting them empty`() {
        val local = kotlin.io.path.createTempDirectory("ws-local").toFile()
        try {
            File(local, "t/metadata").mkdirs()
            File(local, "t/metadata/v1.metadata.json").writeText("{}")
            val items = listOf(
                WorkspaceItem.Warehouse(local.absolutePath, local.name),
                WorkspaceItem.Warehouse("s3://warehouse/db", "db"),
                WorkspaceItem.SingleTable("s3://warehouse/db/orders", "orders"),
            )

            val skipped = scanWorkspace(items, includeRemote = false)
            assertTrue(
                "s3://warehouse/db" !in skipped.warehouseTables,
                "a skipped remote root must be absent, not present-and-empty — present-and-empty " +
                    "is what would blank a warehouse full of tables",
            )
            assertTrue("s3://warehouse/db/orders" !in skipped.singleTableExists)
            assertTrue(
                local.absolutePath in skipped.warehouseTables,
                "the local root is on the fast cadence and must still be swept",
            )
            assertEquals(listOf("t"), skipped.warehouseTables.getValue(local.absolutePath))
        } finally {
            local.deleteRecursively()
        }
    }

    /**
     * The default still covers everything, so nothing had to change at the other call sites.
     *
     * `refreshWarehouseTables()` and the tests that predate the cadence both call the one-argument
     * form, and a default that quietly skipped remote roots would make an explicit refresh not
     * refresh the roots the reader most likely pressed it for.
     */
    @Test
    fun `the default sweep covers remote roots`() {
        val items = listOf(WorkspaceItem.Warehouse("s3://warehouse/db", "db"))
        val scan = scanWorkspace(items)
        // No credentials are configured here, so the listing cannot be done at all — which is a
        // third outcome, not a second. What this pins is only that the root was *looked at*:
        // covered means it appears in one of the three maps, and not covered means none of them.
        assertTrue(
            "s3://warehouse/db" in scan.warehouseTables || "s3://warehouse/db" in scan.unreachable,
            "the default must sweep a remote root, and reach one of the two conclusions",
        )
    }

    /**
     * A store that cannot be listed is a third answer, and it must not be spelled as the second.
     *
     * Reported as an empty warehouse, a refused key blanks a list of tables that are all still
     * there — and the reader has nothing left to click that might have said why, because the
     * message only ever existed inside the sweep.
     */
    @Test
    fun `a warehouse that cannot be listed is reported as unreachable, not as empty`() {
        val scan = scanWorkspace(listOf(WorkspaceItem.Warehouse("s3://warehouse/db", "db")))
        assertTrue(
            "s3://warehouse/db" !in scan.warehouseTables,
            "an unlistable warehouse must be absent, so the 'not covered' rule keeps its tables",
        )
        val reason = scan.unreachable["s3://warehouse/db"]
        assertTrue(!reason.isNullOrBlank(), "the store's own words are the only diagnosis there is")
    }

    /** A local root is unaffected: its answers are still the two it always had. */
    @Test
    fun `a local root is never reported as unreachable`() {
        val warehouse = kotlin.io.path.createTempDirectory("unreachable-local").toFile()
        try {
            val items = listOf(
                WorkspaceItem.Warehouse(warehouse.absolutePath, "w"),
                WorkspaceItem.SingleTable(File(warehouse, "gone").absolutePath, "gone"),
            )
            val scan = scanWorkspace(items)
            assertTrue(scan.unreachable.isEmpty(), "got ${scan.unreachable}")
            assertEquals(emptyList(), scan.warehouseTables.getValue(warehouse.absolutePath))
            assertEquals(false, scan.singleTableExists.getValue(File(warehouse, "gone").absolutePath))
        } finally {
            warehouse.deleteRecursively()
        }
    }

    /**
     * A location in object storage is not a file on this machine, and must not be judged as one.
     *
     * `File("s3://warehouse/db").exists()` is false for every bucket that has ever existed, so the
     * row drew in error red with "(deleted)" beside it from the moment the reader added it — while
     * the tables underneath it listed and opened perfectly well.
     */
    @Test
    fun `a remote warehouse is not called deleted because it is not a local file`() {
        val remote = WorkspaceItem.Warehouse("s3://warehouse/db", "db")
        assertEquals(null, workspaceRootStatus(remote, emptyMap()))
    }

    @Test
    fun `a local warehouse that is gone is still called deleted`() {
        val missing = WorkspaceItem.Warehouse("/nonexistent/warehouse", "warehouse")
        assertEquals(WorkspaceTableStatus.DELETED, workspaceRootStatus(missing, emptyMap()))
    }

    @Test
    fun `a local warehouse that is there claims nothing`() {
        val dir = kotlin.io.path.createTempDirectory("warehouse-status").toFile()
        try {
            assertEquals(null, workspaceRootStatus(WorkspaceItem.Warehouse(dir.absolutePath, "w"), emptyMap()))
        } finally {
            dir.deleteRecursively()
        }
    }

    /** A single table's status is the sweep's, and defaults to present when it has not run. */
    @Test
    fun `a single table takes the status the sweep gave it`() {
        val table = WorkspaceItem.SingleTable("s3://warehouse/db/orders", "orders")
        assertEquals(WorkspaceTableStatus.EXISTING, workspaceRootStatus(table, emptyMap()))
        assertEquals(
            WorkspaceTableStatus.DELETED,
            workspaceRootStatus(table, mapOf(table.path to WorkspaceTableStatus.DELETED)),
        )
    }
}
