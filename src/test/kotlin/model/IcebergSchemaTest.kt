package model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class IcebergSchemaTest {

    private val json = Json { ignoreUnknownKeys = true }

    // H-7 regression: ManifestListEntry sequence numbers must accept Long values.
    // The Iceberg spec defines sequence_number / min_sequence_number as int64.
    @Test
    fun `ManifestListEntry sequence numbers accept values larger than Int MAX_VALUE`() {
        val largeSeq = Int.MAX_VALUE.toLong() + 100L
        val entry = ManifestListEntry(
            manifestPath = "/m.avro",
            sequenceNumber = largeSeq,
            minSequenceNumber = largeSeq,
        )
        assertEquals(largeSeq, entry.sequenceNumber)
        assertEquals(largeSeq, entry.minSequenceNumber)
    }

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

    @Test
    fun `DataFile equals uses structural ByteArray comparison`() {
        val a = DataFile(filePath = "/a.parquet", keyMetadata = byteArrayOf(1, 2, 3))
        val b = DataFile(filePath = "/a.parquet", keyMetadata = byteArrayOf(1, 2, 3))
        val c = DataFile(filePath = "/a.parquet", keyMetadata = byteArrayOf(4, 5, 6))
        assertEquals(a, b, "DataFiles with same keyMetadata content should be equal")
        assertEquals(a.hashCode(), b.hashCode(), "DataFiles with same keyMetadata should have same hashCode")
        assertNotEquals(a, c, "DataFiles with different keyMetadata should not be equal")
    }

    @Test
    fun `DataFile equals handles null keyMetadata`() {
        val a = DataFile(filePath = "/a.parquet", keyMetadata = null)
        val b = DataFile(filePath = "/a.parquet", keyMetadata = null)
        val c = DataFile(filePath = "/a.parquet", keyMetadata = byteArrayOf(1))
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `KeyValuePairBytes equals uses structural ByteArray comparison`() {
        val a = KeyValuePairBytes(1, byteArrayOf(10, 20))
        val b = KeyValuePairBytes(1, byteArrayOf(10, 20))
        val c = KeyValuePairBytes(1, byteArrayOf(30, 40))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun `DataFile can be used in sets without ByteArray identity issues`() {
        val a = DataFile(filePath = "/a.parquet", keyMetadata = byteArrayOf(1, 2))
        val b = DataFile(filePath = "/a.parquet", keyMetadata = byteArrayOf(1, 2))
        val set = setOf(a, b)
        assertEquals(1, set.size, "Identical DataFiles should deduplicate in a Set")
    }
}
