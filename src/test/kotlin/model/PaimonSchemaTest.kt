package model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PaimonSchemaTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserialize snapshot JSON`() {
        val input = """
        {
          "version": 3,
          "id": 1,
          "schemaId": 0,
          "baseManifestList": "manifest/manifest-list-base-0",
          "deltaManifestList": "manifest/manifest-list-delta-0",
          "changelogManifestList": null,
          "indexManifest": null,
          "commitUser": "user-uuid-123",
          "commitIdentifier": 12345,
          "commitKind": "APPEND",
          "timeMillis": 1700000000000,
          "logOffsets": {},
          "totalRecordCount": 100,
          "deltaRecordCount": 10,
          "changelogRecordCount": 0,
          "watermark": -9223372036854775808
        }
        """.trimIndent()

        val snapshot = json.decodeFromString(PaimonSnapshot.serializer(), input)
        assertEquals(3, snapshot.version)
        assertEquals(1L, snapshot.id)
        assertEquals(0, snapshot.schemaId)
        assertEquals("manifest/manifest-list-base-0", snapshot.baseManifestList)
        assertEquals("manifest/manifest-list-delta-0", snapshot.deltaManifestList)
        assertNull(snapshot.changelogManifestList)
        assertNull(snapshot.indexManifest)
        assertEquals("user-uuid-123", snapshot.commitUser)
        assertEquals(12345L, snapshot.commitIdentifier)
        assertEquals("APPEND", snapshot.commitKind)
        assertEquals(1700000000000L, snapshot.timeMillis)
        assertEquals(100L, snapshot.totalRecordCount)
        assertEquals(10L, snapshot.deltaRecordCount)
        assertEquals(0L, snapshot.changelogRecordCount)
        assertEquals(Long.MIN_VALUE, snapshot.watermark)
    }

    @Test
    fun `deserialize snapshot with unknown fields`() {
        val input = """{"id": 5, "unknownField": "test", "commitKind": "COMPACT"}"""
        val snapshot = json.decodeFromString(PaimonSnapshot.serializer(), input)
        assertEquals(5L, snapshot.id)
        assertEquals("COMPACT", snapshot.commitKind)
    }

    @Test
    fun `deserialize schema JSON`() {
        val input = """
        {
          "id": 0,
          "fields": [
            {"id": 0, "name": "k", "type": "INT NOT NULL"},
            {"id": 1, "name": "v", "type": "STRING"}
          ],
          "highestFieldId": 1,
          "partitionKeys": [],
          "primaryKeys": ["k"],
          "options": {
            "merge-engine": "deduplicate",
            "bucket": "1"
          }
        }
        """.trimIndent()

        val schema = json.decodeFromString(PaimonSchema.serializer(), input)
        assertEquals(0, schema.id)
        assertEquals(2, schema.fields.size)
        assertEquals("k", schema.fields[0].name)
        assertEquals("INT NOT NULL", schema.fields[0].type)
        assertEquals("v", schema.fields[1].name)
        assertEquals("STRING", schema.fields[1].type)
        assertEquals(1, schema.highestFieldId)
        assertEquals(emptyList(), schema.partitionKeys)
        assertEquals(listOf("k"), schema.primaryKeys)
        assertEquals("deduplicate", schema.options["merge-engine"])
        assertEquals("1", schema.options["bucket"])
    }

    @Test
    fun `deserialize schema with missing optional fields`() {
        val input = """{"id": 1, "fields": []}"""
        val schema = json.decodeFromString(PaimonSchema.serializer(), input)
        assertEquals(1, schema.id)
        assertEquals(emptyList(), schema.fields)
        assertNull(schema.highestFieldId)
        assertEquals(emptyList(), schema.partitionKeys)
        assertEquals(emptyList(), schema.primaryKeys)
        assertEquals(emptyMap(), schema.options)
        assertNull(schema.comment)
    }

    @Test
    fun `deserialize field`() {
        val input = """{"id": 0, "name": "column1", "type": "BIGINT NOT NULL"}"""
        val field = json.decodeFromString(PaimonField.serializer(), input)
        assertEquals(0, field.id)
        assertEquals("column1", field.name)
        assertEquals("BIGINT NOT NULL", field.type)
    }

    @Test
    fun `deserialize snapshot from fixture file`() {
        val path = this::class.java.getResource("/paimon-fixtures/snapshot/snapshot-1")?.readText()
        assertNotNull(path, "Fixture file not found")
        val snapshot = json.decodeFromString(PaimonSnapshot.serializer(), path)
        assertEquals(1L, snapshot.id)
        assertEquals("APPEND", snapshot.commitKind)
        assertEquals(100L, snapshot.totalRecordCount)
    }

    @Test
    fun `deserialize schema from fixture file`() {
        val content = this::class.java.getResource("/paimon-fixtures/schema/schema-0")?.readText()
        assertNotNull(content, "Fixture file not found")
        val schema = json.decodeFromString(PaimonSchema.serializer(), content)
        assertEquals(0, schema.id)
        assertEquals(2, schema.fields.size)
        assertEquals(listOf("k"), schema.primaryKeys)
    }

    @Test
    fun `snapshot defaults for all-null input`() {
        val snapshot = json.decodeFromString(PaimonSnapshot.serializer(), "{}")
        assertNull(snapshot.version)
        assertNull(snapshot.id)
        assertNull(snapshot.schemaId)
        assertNull(snapshot.baseManifestList)
        assertNull(snapshot.deltaManifestList)
        assertNull(snapshot.commitKind)
        assertNull(snapshot.timeMillis)
        assertEquals(emptyMap(), snapshot.logOffsets)
    }

    @Test
    fun `all commitKind values deserialize`() {
        listOf("APPEND", "COMPACT", "OVERWRITE", "ANALYZE").forEach { kind ->
            val input = """{"commitKind": "$kind"}"""
            val snapshot = json.decodeFromString(PaimonSnapshot.serializer(), input)
            assertEquals(kind, snapshot.commitKind)
        }
    }
}
