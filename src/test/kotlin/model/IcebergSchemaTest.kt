package model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IcebergSchemaTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parse minimal TableMetadata`() {
        val input = """{"format-version": 2, "table-uuid": "abc-123"}"""
        val meta = json.decodeFromString(TableMetadata.serializer(), input)
        assertEquals(2, meta.formatVersion)
        assertEquals("abc-123", meta.tableUuid)
        assertNull(meta.location)
        assertEquals(emptyList(), meta.snapshots)
    }

    @Test
    fun `parse TableMetadata with snapshots`() {
        val input = """{
            "format-version": 2,
            "snapshots": [
                {"snapshot-id": 100, "timestamp-ms": 1700000000000, "summary": {"operation": "append"}},
                {"snapshot-id": 200, "parent-snapshot-id": 100, "timestamp-ms": 1700001000000}
            ]
        }"""
        val meta = json.decodeFromString(TableMetadata.serializer(), input)
        assertEquals(2, meta.snapshots.size)
        assertEquals(100L, meta.snapshots[0].snapshotId)
        assertEquals("append", meta.snapshots[0].summary["operation"])
        assertEquals(200L, meta.snapshots[1].snapshotId)
        assertEquals(100L, meta.snapshots[1].parentSnapshotId)
    }

    @Test
    fun `parse TableMetadata with schemas and identifier fields`() {
        val input = """{
            "format-version": 2,
            "schemas": [
                {
                    "type": "struct",
                    "schema-id": 0,
                    "identifier-field-ids": [1, 2],
                    "fields": [
                        {"id": 1, "name": "id", "required": true, "type": "long"},
                        {"id": 2, "name": "name", "required": false, "type": "string"},
                        {"id": 3, "name": "value", "required": false, "type": "double"}
                    ]
                }
            ]
        }"""
        val meta = json.decodeFromString(TableMetadata.serializer(), input)
        assertEquals(1, meta.schemas.size)
        val schema = meta.schemas[0]
        assertEquals(listOf(1, 2), schema.identifierFieldIds)
        assertEquals(3, schema.fields.size)
        assertEquals("id", schema.fields[0].name)
    }

    @Test
    fun `parse TableMetadata ignores unknown keys`() {
        val input = """{"format-version": 1, "some-future-field": "value", "another": 42}"""
        val meta = json.decodeFromString(TableMetadata.serializer(), input)
        assertEquals(1, meta.formatVersion)
    }

    @Test
    fun `parse DataFile with content types`() {
        val input = """{"file_path": "/data/file.parquet", "content": 1, "record_count": 500}"""
        val file = json.decodeFromString(DataFile.serializer(), input)
        assertEquals("/data/file.parquet", file.filePath)
        assertEquals(1, file.content)
        assertEquals(500L, file.recordCount)
    }
}
