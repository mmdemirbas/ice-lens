package model

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    // --- Cloud URIs ---

    @Test
    fun `normalizeFilePath passes through s3 URIs`() {
        assertEquals("s3://bucket/warehouse/table/data/file.parquet",
            normalizeFilePath("s3://bucket/warehouse/table/data/file.parquet"))
    }

    @Test
    fun `normalizeFilePath passes through hdfs URIs`() {
        assertEquals("hdfs://namenode:8020/warehouse/table/data/file.parquet",
            normalizeFilePath("hdfs://namenode:8020/warehouse/table/data/file.parquet"))
    }

    @Test
    fun `normalizeFilePath passes through gs URIs`() {
        assertEquals("gs://bucket/path/file.parquet",
            normalizeFilePath("gs://bucket/path/file.parquet"))
    }

    @Test
    fun `normalizeFilePath passes through abfs URIs`() {
        assertEquals("abfs://container@account.dfs.core.windows.net/path/file.parquet",
            normalizeFilePath("abfs://container@account.dfs.core.windows.net/path/file.parquet"))
    }

    // --- UNC paths ---

    @Test
    fun `normalizeFilePath converts UNC backslashes to forward slashes`() {
        assertEquals("//server/share/data/file.parquet",
            normalizeFilePath("\\\\server\\share\\data\\file.parquet"))
    }

    @Test
    fun `normalizeFilePath preserves UNC double-slash prefix`() {
        val result = normalizeFilePath("\\\\server\\share\\file.parquet")
        assertTrue(result.startsWith("//"), "UNC path should start with // but got: $result")
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

    // --- resolveForceRelative edge cases ---

    @Test
    fun `resolveForceRelative with null path returns start`() {
        val start = Path.of("/metadata")
        assertEquals(start, resolveForceRelative(start, null))
    }

    @Test
    fun `resolveForceRelative with empty path returns start`() {
        val start = Path.of("/metadata")
        assertEquals(start, resolveForceRelative(start, ""))
    }

    @Test
    fun `resolveForceRelative with blank path returns start`() {
        val start = Path.of("/metadata")
        assertEquals(start, resolveForceRelative(start, "   "))
    }

    @Test
    fun `resolveForceRelative with only slashes returns start`() {
        val start = Path.of("/metadata")
        assertEquals(start, resolveForceRelative(start, "///"))
    }

    @Test
    fun `resolveForceRelative with trailing slash strips it`() {
        val start = Path.of("/metadata")
        assertEquals(Path.of("/metadata/snap.avro"), resolveForceRelative(start, "/some/path/snap.avro/"))
    }

    @Test
    fun `resolveForceRelative extracts last segment from deep path`() {
        val start = Path.of("/metadata")
        assertEquals(
            Path.of("/metadata/manifest.avro"),
            resolveForceRelative(start, "s3://bucket/warehouse/table/metadata/manifest.avro")
        )
    }

    @Test
    fun `resolveForceRelative with simple filename`() {
        val start = Path.of("/metadata")
        assertEquals(Path.of("/metadata/file.avro"), resolveForceRelative(start, "file.avro"))
    }

    // --- metadataVersionFromFileName ordering ---

    @Test
    fun `non-versioned metadata files sort after versioned files`() {
        val files = listOf("abc.metadata.json", "v2.metadata.json", "v1.metadata.json", "custom.metadata.json")
        val sorted = files.sortedWith(
            compareBy<String>(
                { metadataVersionFromFileName(it) == null },
                { metadataVersionFromFileName(it) ?: Int.MAX_VALUE },
            )
        )
        assertEquals(listOf("v1.metadata.json", "v2.metadata.json", "abc.metadata.json", "custom.metadata.json"), sorted)
    }

    @Test
    fun `metadataVersionFromFileName handles large version numbers`() {
        assertEquals(99999, metadataVersionFromFileName("v99999.metadata.json"))
    }
}
