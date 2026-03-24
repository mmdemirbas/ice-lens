package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertIs

class WorkspaceTypesTest {

    @Test
    fun `Warehouse serialization round-trip`() {
        val item = WorkspaceItem.Warehouse("/tmp/warehouse", "warehouse")
        val serialized = item.serialize()
        assertEquals("W|/tmp/warehouse", serialized)
    }

    @Test
    fun `SingleTable serialization round-trip`() {
        val item = WorkspaceItem.SingleTable("/tmp/table", "table")
        val serialized = item.serialize()
        assertEquals("T|/tmp/table", serialized)
    }

    @Test
    fun `deserialize returns null for invalid format`() {
        assertNull(WorkspaceItem.deserialize("invalid"))
        assertNull(WorkspaceItem.deserialize("X|/tmp/foo"))
        assertNull(WorkspaceItem.deserialize(""))
    }

    @Test
    fun `deserialize preserves items with non-existent paths`() {
        val result = WorkspaceItem.deserialize("W|/nonexistent/path/that/does/not/exist")
        assertIs<WorkspaceItem.Warehouse>(result)
        assertEquals("/nonexistent/path/that/does/not/exist", result.path)
        assertEquals("exist", result.name)
    }

    @Test
    fun `deserialize Warehouse from existing path`() {
        val tmpDir = kotlin.io.path.createTempDirectory("ws-test").toFile()
        try {
            val result = WorkspaceItem.deserialize("W|${tmpDir.absolutePath}")
            assertIs<WorkspaceItem.Warehouse>(result)
            assertEquals(tmpDir.absolutePath, result.path)
            assertEquals(tmpDir.name, result.name)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `deserialize SingleTable from existing path`() {
        val tmpDir = kotlin.io.path.createTempDirectory("ws-test").toFile()
        try {
            val result = WorkspaceItem.deserialize("T|${tmpDir.absolutePath}")
            assertIs<WorkspaceItem.SingleTable>(result)
            assertEquals(tmpDir.absolutePath, result.path)
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}
