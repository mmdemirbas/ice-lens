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

    // M-8 regression: path containing the outer separator `;` must round-trip.
    @Test
    fun `paths containing semicolons round-trip safely`() {
        val original = WorkspaceItem.Warehouse("/data/ware;house/odd", "odd")
        val serialized = original.serialize()
        // Outer separator is `;` — it must not appear unencoded in serialized form.
        assert(!serialized.removePrefix("W|").contains(';')) {
            "serialized path must not contain a raw `;`: $serialized"
        }
        val restored = WorkspaceItem.deserialize(serialized)
        assertIs<WorkspaceItem.Warehouse>(restored)
        assertEquals("/data/ware;house/odd", restored.path)
    }

    @Test
    fun `paths containing pipes round-trip safely`() {
        val original = WorkspaceItem.SingleTable("/data/odd|table", "odd|table")
        val restored = WorkspaceItem.deserialize(original.serialize())
        assertIs<WorkspaceItem.SingleTable>(restored)
        assertEquals("/data/odd|table", restored.path)
    }

    @Test
    fun `paths containing percent characters round-trip safely`() {
        val original = WorkspaceItem.SingleTable("/data/100%/table", "table")
        val restored = WorkspaceItem.deserialize(original.serialize())
        assertIs<WorkspaceItem.SingleTable>(restored)
        assertEquals("/data/100%/table", restored.path)
    }
}
