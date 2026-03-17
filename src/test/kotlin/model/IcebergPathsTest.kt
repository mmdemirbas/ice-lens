package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IcebergPathsTest {

    @Test
    fun `normalizeFilePath strips file scheme`() {
        assertEquals("/data/table/file.parquet", normalizeFilePath("file:///data/table/file.parquet"))
    }

    @Test
    fun `normalizeFilePath replaces backslashes`() {
        assertEquals("C:/data/table/file.parquet", normalizeFilePath("C:\\data\\table\\file.parquet"))
    }

    @Test
    fun `normalizeFilePath trims whitespace`() {
        assertEquals("/data/file.parquet", normalizeFilePath("  /data/file.parquet  "))
    }

    @Test
    fun `normalizeFilePath returns empty for blank`() {
        assertEquals("", normalizeFilePath(""))
        assertEquals("", normalizeFilePath("   "))
    }

    @Test
    fun `normalizeFilePath passes through plain path`() {
        assertEquals("/data/table/file.parquet", normalizeFilePath("/data/table/file.parquet"))
    }

    @Test
    fun `metadataVersionFromFileName parses versioned files`() {
        assertEquals(1, metadataVersionFromFileName("v1.metadata.json"))
        assertEquals(42, metadataVersionFromFileName("v42.metadata.json"))
        assertEquals(100, metadataVersionFromFileName("v100.metadata.json"))
    }

    @Test
    fun `metadataVersionFromFileName returns null for non-versioned`() {
        assertNull(metadataVersionFromFileName("abc-def.metadata.json"))
        assertNull(metadataVersionFromFileName("snapshot-123.avro"))
        assertNull(metadataVersionFromFileName("v.metadata.json"))
    }
}
