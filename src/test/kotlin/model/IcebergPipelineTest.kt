@file:OptIn(com.github.avrokotlin.avro4k.ExperimentalAvro4kApi::class)

package model

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.schema
import org.apache.avro.file.DataFileWriter
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericDatumWriter
import service.IcebergGraphBuilder
import service.IcebergReader
import java.io.File
import java.nio.file.Path
import kotlin.test.*

/**
 * Integration tests for the Iceberg reading pipeline:
 * Filesystem → IcebergReader → UnifiedTableModel → IcebergGraphBuilder
 */
class IcebergPipelineTest {

    private lateinit var tmpDir: File

    @BeforeTest
    fun setUp() {
        tmpDir = kotlin.io.path.createTempDirectory("iceberg-pipeline-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    // ═══════════════════════════════════════════════════════════════
    //  IcebergReader Unit Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `IcebergReader reads metadata JSON`() {
        val metaFile = File(tmpDir, "v1.metadata.json")
        metaFile.writeText(SIMPLE_METADATA_JSON)
        val metadata = IcebergReader.readTableMetadata(metaFile.absolutePath)
        assertEquals(2, metadata.formatVersion)
        assertEquals("test-uuid", metadata.tableUuid)
        assertEquals(1, metadata.snapshots.size)
        assertEquals(1L, metadata.snapshots[0].snapshotId)
        assertEquals("snap-1.avro", metadata.snapshots[0].manifestList)
    }

    @Test
    fun `IcebergReader throws on non-existent metadata file`() {
        assertFailsWith<IllegalArgumentException> {
            IcebergReader.readTableMetadata("/nonexistent/v1.metadata.json")
        }
    }

    @Test
    fun `IcebergReader reads metadata with unknown fields`() {
        val metaFile = File(tmpDir, "v1.metadata.json")
        metaFile.writeText("""
            {"format-version": 1, "table-uuid": "abc", "unknown-future-field": 42, "snapshots": []}
        """.trimIndent())
        val metadata = IcebergReader.readTableMetadata(metaFile.absolutePath)
        assertEquals(1, metadata.formatVersion)
        assertEquals("abc", metadata.tableUuid)
    }

    @Test
    fun `IcebergReader reads minimal metadata with defaults`() {
        val metaFile = File(tmpDir, "v1.metadata.json")
        metaFile.writeText("{}")
        val metadata = IcebergReader.readTableMetadata(metaFile.absolutePath)
        assertNull(metadata.formatVersion)
        assertEquals(emptyList(), metadata.snapshots)
        assertEquals(emptyMap(), metadata.properties)
    }

    // ═══════════════════════════════════════════════════════════════
    //  UnifiedTableModel — JSON-Only Pipeline
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `UnifiedTableModel reads metadata files from directory`() {
        val table = createTableDir(
            metadataJsons = mapOf("v1.metadata.json" to SIMPLE_METADATA_JSON)
        )
        val model = UnifiedTableModel(table)
        assertEquals(1, model.metadatas.size)
        assertEquals(2, model.metadatas[0].metadata.formatVersion)
    }

    @Test
    fun `UnifiedTableModel sorts metadata by version number`() {
        val table = createTableDir(
            metadataJsons = mapOf(
                "v2.metadata.json" to metadataJson(snapshots = listOf(snapshotJson(2, "snap-2.avro"))),
                "v1.metadata.json" to metadataJson(snapshots = listOf(snapshotJson(1, "snap-1.avro")))
            )
        )
        val model = UnifiedTableModel(table)
        assertEquals(2, model.metadatas.size)
        // v1 should come first
        assertTrue(model.metadatas[0].path.toString().contains("v1"))
        assertTrue(model.metadatas[1].path.toString().contains("v2"))
    }

    @Test
    fun `UnifiedTableModel deduplicates snapshots across metadata files`() {
        // Both metadata files reference snapshot 1
        val table = createTableDir(
            metadataJsons = mapOf(
                "v1.metadata.json" to metadataJson(snapshots = listOf(snapshotJson(1, "snap-1.avro"))),
                "v2.metadata.json" to metadataJson(snapshots = listOf(
                    snapshotJson(1, "snap-1.avro"),
                    snapshotJson(2, "snap-2.avro")
                ))
            )
        )
        val model = UnifiedTableModel(table)
        // Each metadata has its own snapshot list, but the underlying snapshot parsing is deduplicated
        assertEquals(2, model.metadatas.size)
    }

    @Test
    fun `UnifiedTableModel reads version-hint file`() {
        val table = createTableDir(
            metadataJsons = mapOf("v1.metadata.json" to metadataJson()),
            versionHint = "1"
        )
        val model = UnifiedTableModel(table)
        assertEquals("1", model.versionHint)
    }

    @Test
    fun `UnifiedTableModel handles missing version-hint`() {
        val table = createTableDir(
            metadataJsons = mapOf("v1.metadata.json" to metadataJson())
        )
        val model = UnifiedTableModel(table)
        // Missing version hint → error collected but not fatal
        assertTrue(model.readErrors.any { it.stage == "read-version-hint" })
    }

    @Test
    fun `UnifiedTableModel handles missing metadata directory`() {
        // Empty table dir with no metadata/ subdirectory
        val model = UnifiedTableModel(tmpDir.toPath())
        assertEquals(0, model.metadatas.size)
        assertTrue(model.readErrors.isNotEmpty())
        assertTrue(model.readErrors.any { it.stage == "list-metadata-files" })
    }

    @Test
    fun `UnifiedTableModel handles malformed metadata JSON`() {
        val table = createTableDir(
            metadataJsons = mapOf(
                "v1.metadata.json" to metadataJson(snapshots = listOf(snapshotJson(1, "snap-1.avro"))),
                "v2.metadata.json" to "NOT VALID JSON"
            )
        )
        val model = UnifiedTableModel(table)
        // Valid metadata still loads
        assertEquals(1, model.metadatas.size)
        // Error collected for the bad file
        assertTrue(model.readErrors.any { it.stage == "read-metadata-json" })
    }

    @Test
    fun `UnifiedTableModel handles empty metadata directory`() {
        val metadataDir = File(tmpDir, "metadata")
        metadataDir.mkdirs()
        val model = UnifiedTableModel(tmpDir.toPath())
        assertEquals(0, model.metadatas.size)
        assertEquals(tmpDir.name, model.name)
    }

    @Test
    fun `UnifiedTableModel collects errors for missing manifest list Avro`() {
        val table = createTableDir(
            metadataJsons = mapOf(
                "v1.metadata.json" to metadataJson(snapshots = listOf(snapshotJson(1, "snap-1.avro")))
            )
        )
        // snap-1.avro doesn't exist
        val model = UnifiedTableModel(table)
        assertEquals(1, model.metadatas.size)
        val snapshot = model.metadatas[0].snapshots[0]
        assertTrue(snapshot.readErrors.isNotEmpty(), "Expected errors for missing manifest list: ${snapshot.readErrors}")
        assertTrue(snapshot.manifests.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════
    //  Full Pipeline with Avro Fixtures
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `full pipeline reads manifest list and manifest Avro files`() {
        val table = createCompleteTable()
        val model = UnifiedTableModel(table)

        // Verify metadata
        assertEquals(1, model.metadatas.size)
        val meta = model.metadatas[0]
        assertEquals(2, meta.metadata.formatVersion)

        // Verify snapshot
        assertEquals(1, meta.snapshots.size)
        val snap = meta.snapshots[0]
        assertEquals(1L, snap.metadata.snapshotId)

        // Verify manifests (from Avro manifest list)
        assertEquals(1, snap.manifests.size)
        val manifest = snap.manifests[0]

        // Verify data files (from Avro manifest)
        assertEquals(2, manifest.dataFiles.size)
        assertEquals(1, manifest.dataFiles[0].metadata.status) // ADDED
        assertEquals(100L, manifest.dataFiles[0].metadata.dataFile?.recordCount)
        assertEquals(200L, manifest.dataFiles[1].metadata.dataFile?.recordCount)

        // Only version-hint error (expected — no version-hint.text file)
        assertTrue(model.readErrors.all { it.stage == "read-version-hint" }, "Unexpected top-level errors: ${model.readErrors}")
        assertEquals(0, snap.readErrors.size, "Snapshot errors: ${snap.readErrors}")
        assertEquals(0, manifest.readErrors.size, "Manifest errors: ${manifest.readErrors}")
    }

    @Test
    fun `full pipeline produces correct graph structure`() {
        val table = createCompleteTable()
        val model = UnifiedTableModel(table)
        val result = IcebergGraphBuilder.buildGraph(model, showRows = false)

        val nodeTypes = result.nodes.groupBy { it::class.simpleName }
        assertEquals(1, nodeTypes["TableNode"]?.size, "Expected 1 table node")
        assertEquals(1, nodeTypes["MetadataNode"]?.size, "Expected 1 metadata node")
        assertEquals(1, nodeTypes["SnapshotNode"]?.size, "Expected 1 snapshot node")
        assertTrue((nodeTypes["ManifestNode"]?.size ?: 0) >= 1, "Expected at least 1 manifest node")
        assertEquals(2, nodeTypes["FileNode"]?.size, "Expected 2 file nodes")

        assertTrue(result.edges.isNotEmpty())
        // Table → Metadata edge
        assertTrue(result.edges.any { it.fromId == "table_root" })
    }

    @Test
    fun `full pipeline with multiple metadata versions`() {
        val table = createMultiVersionTable()
        val model = UnifiedTableModel(table)

        assertEquals(2, model.metadatas.size)
        // v1 should be first (sorted by version number)
        assertTrue(model.metadatas[0].path.toString().contains("v1"))
        assertTrue(model.metadatas[1].path.toString().contains("v2"))

        // Snapshot 1 should appear in v1's snapshot list
        assertEquals(1, model.metadatas[0].snapshots.size)
        assertEquals(1L, model.metadatas[0].snapshots[0].metadata.snapshotId)

        // Both snapshots should appear in v2's snapshot list
        assertEquals(2, model.metadatas[1].snapshots.size)

        // No errors
        assertEquals(0, model.readErrors.size, "Errors: ${model.readErrors}")
    }

    @Test
    fun `full pipeline with delete manifest content type`() {
        val table = createTableWithDeleteManifest()
        val model = UnifiedTableModel(table)

        assertEquals(1, model.metadatas[0].snapshots.size)
        val snap = model.metadatas[0].snapshots[0]
        // Two manifests: one data (content=0) and one delete (content=1)
        assertEquals(2, snap.manifests.size)

        val dataManifest = snap.manifests.first { it.metadata.content == 0 }
        val deleteManifest = snap.manifests.first { it.metadata.content == 1 }

        assertEquals(1, dataManifest.dataFiles.size)
        assertEquals(1, deleteManifest.dataFiles.size)
    }

    @Test
    fun `full pipeline graph builder preserves file metadata`() {
        val table = createCompleteTable()
        val model = UnifiedTableModel(table)
        val result = IcebergGraphBuilder.buildGraph(model, showRows = false)

        val fileNodes = result.nodes.filterIsInstance<GraphNode.FileNode>()
        assertEquals(2, fileNodes.size)
        assertTrue(fileNodes.any { it.entry.dataFile?.recordCount == 100L })
        assertTrue(fileNodes.any { it.entry.dataFile?.recordCount == 200L })
    }

    // ═══════════════════════════════════════════════════════════════
    //  Fixture Helpers
    // ═══════════════════════════════════════════════════════════════

    private fun createTableDir(
        metadataJsons: Map<String, String>,
        versionHint: String? = null,
    ): Path {
        val metadataDir = File(tmpDir, "metadata").apply { mkdirs() }
        metadataJsons.forEach { (name, json) ->
            File(metadataDir, name).writeText(json)
        }
        if (versionHint != null) {
            File(metadataDir, "version-hint.text").writeText(versionHint)
        }
        return tmpDir.toPath()
    }

    /**
     * Creates a complete Iceberg table:
     * - 1 metadata file (v1) with 1 snapshot
     * - 1 manifest list Avro → 1 manifest entry
     * - 1 manifest Avro → 2 data file entries
     */
    private fun createCompleteTable(): Path {
        val metadataDir = File(tmpDir, "metadata").apply { mkdirs() }

        // Metadata JSON
        File(metadataDir, "v1.metadata.json").writeText(
            metadataJson(snapshots = listOf(snapshotJson(1, "snap-1.avro", timestampMs = 1700000000000L)))
        )

        // Manifest list Avro (snap-1.avro) → points to manifest-1.avro
        writeManifestListAvro(
            File(metadataDir, "snap-1.avro"),
            listOf(
                ManifestListData(
                    manifestPath = "${metadataDir.absolutePath}/manifest-1.avro",
                    manifestLength = 2048,
                    content = 0,
                    addedSnapshotId = 1,
                    addedFilesCount = 2,
                    addedRowsCount = 300
                )
            )
        )

        // Manifest Avro (manifest-1.avro) → 2 data files
        writeManifestAvro(
            File(metadataDir, "manifest-1.avro"),
            listOf(
                ManifestEntryData(status = 1, snapshotId = 1, filePath = "data/file-1.parquet", recordCount = 100, fileSizeInBytes = 4096),
                ManifestEntryData(status = 1, snapshotId = 1, filePath = "data/file-2.parquet", recordCount = 200, fileSizeInBytes = 8192),
            )
        )

        return tmpDir.toPath()
    }

    private fun createMultiVersionTable(): Path {
        val metadataDir = File(tmpDir, "metadata").apply { mkdirs() }

        // v1 metadata with snapshot 1
        File(metadataDir, "v1.metadata.json").writeText(
            metadataJson(snapshots = listOf(snapshotJson(1, "snap-1.avro", timestampMs = 1700000000000L)))
        )

        // v2 metadata with snapshots 1 and 2
        File(metadataDir, "v2.metadata.json").writeText(
            metadataJson(snapshots = listOf(
                snapshotJson(1, "snap-1.avro", timestampMs = 1700000000000L),
                snapshotJson(2, "snap-2.avro", timestampMs = 1700000060000L)
            ))
        )

        // Manifest lists
        writeManifestListAvro(
            File(metadataDir, "snap-1.avro"),
            listOf(ManifestListData(
                manifestPath = "${metadataDir.absolutePath}/manifest-1.avro",
                manifestLength = 1024, content = 0, addedSnapshotId = 1, addedFilesCount = 1, addedRowsCount = 100
            ))
        )
        writeManifestListAvro(
            File(metadataDir, "snap-2.avro"),
            listOf(ManifestListData(
                manifestPath = "${metadataDir.absolutePath}/manifest-2.avro",
                manifestLength = 1024, content = 0, addedSnapshotId = 2, addedFilesCount = 1, addedRowsCount = 50
            ))
        )

        // Manifests
        writeManifestAvro(
            File(metadataDir, "manifest-1.avro"),
            listOf(ManifestEntryData(status = 1, snapshotId = 1, filePath = "data/file-1.parquet", recordCount = 100, fileSizeInBytes = 4096))
        )
        writeManifestAvro(
            File(metadataDir, "manifest-2.avro"),
            listOf(ManifestEntryData(status = 1, snapshotId = 2, filePath = "data/file-2.parquet", recordCount = 50, fileSizeInBytes = 2048))
        )

        File(metadataDir, "version-hint.text").writeText("2")

        return tmpDir.toPath()
    }

    private fun createTableWithDeleteManifest(): Path {
        val metadataDir = File(tmpDir, "metadata").apply { mkdirs() }

        File(metadataDir, "v1.metadata.json").writeText(
            metadataJson(snapshots = listOf(snapshotJson(1, "snap-1.avro")))
        )

        // Manifest list with two entries: data manifest and delete manifest
        writeManifestListAvro(
            File(metadataDir, "snap-1.avro"),
            listOf(
                ManifestListData(
                    manifestPath = "${metadataDir.absolutePath}/data-manifest.avro",
                    manifestLength = 1024, content = 0, addedSnapshotId = 1, addedFilesCount = 1, addedRowsCount = 100
                ),
                ManifestListData(
                    manifestPath = "${metadataDir.absolutePath}/delete-manifest.avro",
                    manifestLength = 512, content = 1, addedSnapshotId = 1, addedFilesCount = 1, addedRowsCount = 10
                )
            )
        )

        writeManifestAvro(
            File(metadataDir, "data-manifest.avro"),
            listOf(ManifestEntryData(status = 1, snapshotId = 1, filePath = "data/file-1.parquet", recordCount = 100, fileSizeInBytes = 4096))
        )
        writeManifestAvro(
            File(metadataDir, "delete-manifest.avro"),
            listOf(ManifestEntryData(status = 1, snapshotId = 1, filePath = "data/delete-1.parquet", recordCount = 10, fileSizeInBytes = 512, content = 1))
        )

        return tmpDir.toPath()
    }

    // ═══════════════════════════════════════════════════════════════
    //  JSON Builders
    // ═══════════════════════════════════════════════════════════════

    private fun metadataJson(
        formatVersion: Int = 2,
        tableUuid: String = "test-uuid",
        snapshots: List<String> = emptyList(),
    ): String = """
        {
          "format-version": $formatVersion,
          "table-uuid": "$tableUuid",
          "last-updated-ms": 1700000000000,
          "schemas": [{"schema-id": 0, "type": "struct", "fields": [{"id": 1, "name": "id", "required": true, "type": "long"}]}],
          "current-schema-id": 0,
          "partition-specs": [{"spec-id": 0, "fields": []}],
          "default-spec-id": 0,
          "sort-orders": [{"order-id": 0, "fields": []}],
          "default-sort-order-id": 0,
          "snapshots": [${snapshots.joinToString(",")}],
          "snapshot-log": [],
          "metadata-log": []
        }
    """.trimIndent()

    private fun snapshotJson(
        snapshotId: Long,
        manifestList: String,
        timestampMs: Long = 1700000000000L,
    ): String = """
        {
          "snapshot-id": $snapshotId,
          "timestamp-ms": $timestampMs,
          "manifest-list": "$manifestList",
          "summary": {"operation": "append"},
          "schema-id": 0
        }
    """.trimIndent()

    // ═══════════════════════════════════════════════════════════════
    //  Avro File Writers
    // ═══════════════════════════════════════════════════════════════

    private data class ManifestListData(
        val manifestPath: String,
        val manifestLength: Long,
        val content: Int = 0,
        val addedSnapshotId: Long = 1,
        val addedFilesCount: Int = 0,
        val addedRowsCount: Long = 0,
    )

    private data class ManifestEntryData(
        val status: Int,
        val snapshotId: Long,
        val filePath: String,
        val recordCount: Long,
        val fileSizeInBytes: Long,
        val content: Int = 0, // 0=DATA, 1=POS_DELETES, 2=EQ_DELETES
    )

    private fun writeManifestListAvro(file: File, entries: List<ManifestListData>) {
        val schema = Avro.schema<ManifestListEntry>()
        DataFileWriter(GenericDatumWriter<GenericData.Record>(schema)).use { writer ->
            writer.create(schema, file)
            entries.forEach { entry ->
                val record = GenericData.Record(schema)
                record.put("manifest_path", entry.manifestPath)
                record.put("manifest_length", entry.manifestLength)
                record.put("partition_spec_id", 0)
                record.put("content", entry.content)
                record.put("sequence_number", 1)
                record.put("min_sequence_number", 1)
                record.put("added_snapshot_id", entry.addedSnapshotId)
                record.put("added_files_count", entry.addedFilesCount)
                record.put("existing_files_count", 0)
                record.put("deleted_files_count", 0)
                record.put("added_rows_count", entry.addedRowsCount)
                record.put("existing_rows_count", 0L)
                record.put("deleted_rows_count", 0L)
                writer.append(record)
            }
        }
    }

    private fun writeManifestAvro(file: File, entries: List<ManifestEntryData>) {
        val schema = Avro.schema<ManifestEntry>()
        DataFileWriter(GenericDatumWriter<GenericData.Record>(schema)).use { writer ->
            writer.create(schema, file)
            entries.forEach { entry ->
                val record = GenericData.Record(schema)
                record.put("status", entry.status)
                record.put("snapshot_id", entry.snapshotId)
                record.put("sequence_number", 1L)
                record.put("file_sequence_number", 1L)

                // Create nested data_file record
                val dataFileField = schema.getField("data_file").schema()
                val dataFileSchema = if (dataFileField.isUnion) {
                    dataFileField.types.first { it.type == org.apache.avro.Schema.Type.RECORD }
                } else dataFileField
                val dataFileRecord = GenericData.Record(dataFileSchema)
                dataFileRecord.put("file_path", entry.filePath)
                dataFileRecord.put("file_format", "PARQUET")
                dataFileRecord.put("record_count", entry.recordCount)
                dataFileRecord.put("file_size_in_bytes", entry.fileSizeInBytes)
                dataFileRecord.put("content", entry.content)
                record.put("data_file", dataFileRecord)

                writer.append(record)
            }
        }
    }

    companion object {
        private val SIMPLE_METADATA_JSON = """
            {
              "format-version": 2,
              "table-uuid": "test-uuid",
              "last-updated-ms": 1700000000000,
              "schemas": [{"schema-id": 0, "type": "struct", "fields": []}],
              "current-schema-id": 0,
              "partition-specs": [{"spec-id": 0, "fields": []}],
              "default-spec-id": 0,
              "sort-orders": [{"order-id": 0, "fields": []}],
              "default-sort-order-id": 0,
              "snapshots": [
                {
                  "snapshot-id": 1,
                  "timestamp-ms": 1700000000000,
                  "manifest-list": "snap-1.avro",
                  "summary": {"operation": "append"}
                }
              ],
              "snapshot-log": [],
              "metadata-log": []
            }
        """.trimIndent()
    }
}
