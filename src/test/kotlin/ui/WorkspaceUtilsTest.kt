package ui

import model.WorkspaceItem
import model.WorkspaceTableStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceUtilsTest {

    @Test
    fun `isIcebergTable returns false for non-existent directory`() {
        assertFalse(isIcebergTable(java.io.File("/nonexistent/path")))
    }

    @Test
    fun `isIcebergTable returns true for directory with metadata`() {
        val tmpDir = kotlin.io.path.createTempDirectory("iceberg-test").toFile()
        try {
            val metaDir = java.io.File(tmpDir, "metadata")
            metaDir.mkdirs()
            java.io.File(metaDir, "v1.metadata.json").writeText("{}")
            assertTrue(isIcebergTable(tmpDir))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `isIcebergTable returns false for directory without metadata json`() {
        val tmpDir = kotlin.io.path.createTempDirectory("iceberg-test").toFile()
        try {
            val metaDir = java.io.File(tmpDir, "metadata")
            metaDir.mkdirs()
            java.io.File(metaDir, "somefile.avro").writeText("")
            assertFalse(isIcebergTable(tmpDir))
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
}
