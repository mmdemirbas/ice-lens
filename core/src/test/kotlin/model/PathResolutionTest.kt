@file:OptIn(com.github.avrokotlin.avro4k.ExperimentalAvro4kApi::class)

package model

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.schema
import org.apache.avro.file.DataFileWriter
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericDatumWriter
import java.io.File
import java.nio.file.Path
import kotlin.test.*

/**
 * Tests for Iceberg data file path resolution in UnifiedManifest.
 * This is the code path most likely to produce "read error" nodes
 * when file paths in manifest entries don't resolve correctly.
 */
class PathResolutionTest {

    private lateinit var tmpDir: File

    @BeforeTest
    fun setUp() {
        tmpDir = kotlin.io.path.createTempDirectory("path-resolve-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    // ═══════════════════════════════════════════════════════════════
    //  resolveForceRelative
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `resolveForceRelative extracts last component from absolute path`() {
        val start = Path.of("/table/metadata")
        val result = resolveForceRelative(start, "/some/deep/path/snap-1.avro")
        assertEquals(Path.of("/table/metadata/snap-1.avro"), result)
    }

    @Test
    fun `resolveForceRelative extracts last component from s3 path`() {
        val start = Path.of("/table/metadata")
        val result = resolveForceRelative(start, "s3://bucket/warehouse/table/metadata/snap-1.avro")
        assertEquals(Path.of("/table/metadata/snap-1.avro"), result)
    }

    @Test
    fun `resolveForceRelative handles simple filename`() {
        val start = Path.of("/table/metadata")
        val result = resolveForceRelative(start, "snap-1.avro")
        assertEquals(Path.of("/table/metadata/snap-1.avro"), result)
    }

    @Test
    fun `resolveForceRelative handles null path`() {
        val start = Path.of("/table/metadata")
        val result = resolveForceRelative(start, null)
        assertEquals(start, result)
    }

    @Test
    fun `resolveForceRelative handles empty path`() {
        val start = Path.of("/table/metadata")
        val result = resolveForceRelative(start, "")
        assertEquals(start, result)
    }

    @Test
    fun `resolveForceRelative handles blank path`() {
        val start = Path.of("/table/metadata")
        val result = resolveForceRelative(start, "   ")
        assertEquals(start, result)
    }

    @Test
    fun `resolveForceRelative strips trailing slash`() {
        val start = Path.of("/table/metadata")
        val result = resolveForceRelative(start, "/some/path/file.avro/")
        assertEquals(Path.of("/table/metadata/file.avro"), result)
    }

    @Test
    fun `resolveForceRelative handles path with only slashes`() {
        val start = Path.of("/table/metadata")
        val result = resolveForceRelative(start, "///")
        assertEquals(start, result)
    }

    // ═══════════════════════════════════════════════════════════════
    //  UnifiedManifest Data File Path Resolution (end-to-end)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `data file with relative path resolves from table root`() {
        val table = createIcebergTableWithDataFilePath("data/file-1.parquet")
        val model = UnifiedTableModel(table)
        val dataFile = firstDataFile(model)
        assertNotNull(dataFile, "Should have a data file")
        assertTrue(dataFile.path.toString().endsWith("data/file-1.parquet") ||
            dataFile.path.toString().contains("file-1.parquet"),
            "Data file path should contain the file name: ${dataFile.path}")
    }

    @Test
    fun `data file with absolute path matching table prefix resolves correctly`() {
        val tableAbsPath = tmpDir.canonicalPath
        val filePath = "$tableAbsPath/data/file-1.parquet"
        val table = createIcebergTableWithDataFilePath(filePath)
        val model = UnifiedTableModel(table)
        val dataFile = firstDataFile(model)
        assertNotNull(dataFile)
        // Should strip the table prefix and resolve relative
        assertTrue(dataFile.path.toString().contains("file-1.parquet"),
            "Should resolve to include filename: ${dataFile.path}")
    }

    @Test
    fun `data file with cloud URI path resolves last component`() {
        val table = createIcebergTableWithDataFilePath("s3://bucket/warehouse/table/data/file-1.parquet")
        val model = UnifiedTableModel(table)
        val dataFile = firstDataFile(model)
        assertNotNull(dataFile)
        // The path in manifest is stripped of the table prefix portion
        assertTrue(dataFile.path.toString().contains("file-1.parquet") ||
            dataFile.path.toString().contains("data"),
            "Should resolve cloud path: ${dataFile.path}")
    }

    @Test
    fun `data file with empty filePath produces no path traversal error`() {
        val table = createIcebergTableWithDataFilePath("")
        val model = UnifiedTableModel(table)
        val snapshot = model.metadatas.firstOrNull()?.snapshots?.firstOrNull()
        assertNotNull(snapshot)
        val manifest = snapshot.manifests.firstOrNull()
        assertNotNull(manifest)
        // Empty path should not cause path traversal error
        val traversalErrors = manifest.readErrors.filter { it.stage == "path-traversal-check" }
        assertTrue(traversalErrors.isEmpty(),
            "Empty path should not trigger traversal error: $traversalErrors")
    }

    @Test
    fun `data file with path traversal attempt is flagged`() {
        // Attempt to escape the table directory
        val table = createIcebergTableWithDataFilePath("../../etc/passwd")
        val model = UnifiedTableModel(table)
        val snapshot = model.metadatas.firstOrNull()?.snapshots?.firstOrNull()
        assertNotNull(snapshot)
        val manifest = snapshot.manifests.firstOrNull()
        assertNotNull(manifest)
        // Path traversal should be flagged
        val traversalErrors = manifest.readErrors.filter { it.stage == "path-traversal-check" }
        assertTrue(traversalErrors.isNotEmpty(),
            "Path traversal attempt should be flagged")
    }

    @Test
    fun `multiple data files with different paths all resolve`() {
        val table = createIcebergTableWithMultipleDataFiles(listOf(
            "data/part-00000.parquet",
            "data/part-00001.parquet",
            "data/nested/part-00002.parquet",
        ))
        val model = UnifiedTableModel(table)
        val dataFiles = model.metadatas.firstOrNull()?.snapshots?.firstOrNull()
            ?.manifests?.firstOrNull()?.dataFiles.orEmpty()
        assertEquals(3, dataFiles.size, "Should have 3 data files")
        assertTrue(dataFiles.all { it.path.toString().contains("part-") },
            "All paths should contain 'part-': ${dataFiles.map { it.path }}")
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    private fun firstDataFile(model: UnifiedTableModel): UnifiedDataFile? =
        model.metadatas.firstOrNull()?.snapshots?.firstOrNull()
            ?.manifests?.firstOrNull()?.dataFiles?.firstOrNull()

    private fun createIcebergTableWithDataFilePath(dataFilePath: String): Path {
        val metadataDir = File(tmpDir, "metadata").apply { mkdirs() }

        File(metadataDir, "v1.metadata.json").writeText("""
            {
              "format-version": 2, "table-uuid": "test",
              "schemas": [], "partition-specs": [], "sort-orders": [],
              "snapshots": [{"snapshot-id": 1, "timestamp-ms": 1700000000000, "manifest-list": "snap-1.avro", "summary": {}}],
              "snapshot-log": [], "metadata-log": []
            }
        """.trimIndent())

        writeManifestListAvro(File(metadataDir, "snap-1.avro"),
            "${metadataDir.absolutePath}/manifest-1.avro")

        writeManifestAvro(File(metadataDir, "manifest-1.avro"), listOf(dataFilePath))

        return tmpDir.toPath()
    }

    private fun createIcebergTableWithMultipleDataFiles(dataFilePaths: List<String>): Path {
        val metadataDir = File(tmpDir, "metadata").apply { mkdirs() }

        File(metadataDir, "v1.metadata.json").writeText("""
            {
              "format-version": 2, "table-uuid": "test",
              "schemas": [], "partition-specs": [], "sort-orders": [],
              "snapshots": [{"snapshot-id": 1, "timestamp-ms": 1700000000000, "manifest-list": "snap-1.avro", "summary": {}}],
              "snapshot-log": [], "metadata-log": []
            }
        """.trimIndent())

        writeManifestListAvro(File(metadataDir, "snap-1.avro"),
            "${metadataDir.absolutePath}/manifest-1.avro")

        writeManifestAvro(File(metadataDir, "manifest-1.avro"), dataFilePaths)

        return tmpDir.toPath()
    }

    private fun writeManifestListAvro(file: File, manifestPath: String) {
        val schema = Avro.schema<ManifestListEntry>()
        DataFileWriter(GenericDatumWriter<GenericData.Record>(schema)).use { writer ->
            writer.create(schema, file)
            val record = GenericData.Record(schema)
            record.put("manifest_path", manifestPath)
            record.put("manifest_length", 1024L)
            record.put("partition_spec_id", 0)
            record.put("content", 0)
            record.put("sequence_number", 1L)
            record.put("min_sequence_number", 1L)
            record.put("added_snapshot_id", 1L)
            record.put("added_files_count", 1)
            record.put("existing_files_count", 0)
            record.put("deleted_files_count", 0)
            record.put("added_rows_count", 100L)
            record.put("existing_rows_count", 0L)
            record.put("deleted_rows_count", 0L)
            writer.append(record)
        }
    }

    private fun writeManifestAvro(file: File, dataFilePaths: List<String>) {
        val schema = Avro.schema<ManifestEntry>()
        DataFileWriter(GenericDatumWriter<GenericData.Record>(schema)).use { writer ->
            writer.create(schema, file)
            dataFilePaths.forEach { filePath ->
                val record = GenericData.Record(schema)
                record.put("status", 1)
                record.put("snapshot_id", 1L)
                record.put("sequence_number", 1L)
                record.put("file_sequence_number", 1L)
                val dfField = schema.getField("data_file").schema()
                val dfSchema = if (dfField.isUnion) dfField.types.first { it.type == org.apache.avro.Schema.Type.RECORD } else dfField
                val df = GenericData.Record(dfSchema)
                df.put("file_path", filePath)
                df.put("file_format", "PARQUET")
                df.put("record_count", 100L)
                df.put("file_size_in_bytes", 4096L)
                df.put("content", 0)
                record.put("data_file", df)
                writer.append(record)
            }
        }
    }
}
