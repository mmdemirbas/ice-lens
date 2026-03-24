@file:OptIn(com.github.avrokotlin.avro4k.ExperimentalAvro4kApi::class)

package service

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.schema
import model.*
import org.apache.avro.file.DataFileWriter
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericDatumWriter
import java.io.File
import java.nio.file.Path
import kotlin.test.*

/**
 * Tests for error handling across the reading pipeline.
 * Focuses on corrupt data, partial failures, and edge cases
 * that should degrade gracefully rather than crash.
 */
class ErrorScenariosTest {

    private lateinit var tmpDir: File

    @BeforeTest
    fun setUp() {
        tmpDir = kotlin.io.path.createTempDirectory("error-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    // ═══════════════════════════════════════════════════════════════
    //  AvroReader Error Handling
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `AvroReader throws on non-existent file`() {
        assertFailsWith<Exception> {
            AvroReader.readAvro<ManifestListEntry>("/nonexistent/file.avro")
        }
    }

    @Test
    fun `AvroReader throws on truncated file`() {
        val file = File(tmpDir, "truncated.avro")
        file.writeBytes(byteArrayOf(0x4F, 0x62, 0x6A, 0x01)) // Avro magic bytes only
        assertFailsWith<Exception> {
            AvroReader.readAvro<ManifestListEntry>(file.absolutePath)
        }
    }

    @Test
    fun `AvroReader throws on completely invalid file`() {
        val file = File(tmpDir, "garbage.avro")
        file.writeText("this is not an avro file at all")
        assertFailsWith<Exception> {
            AvroReader.readAvro<ManifestListEntry>(file.absolutePath)
        }
    }

    @Test
    fun `AvroReader throws on empty file`() {
        val file = File(tmpDir, "empty.avro")
        file.createNewFile()
        assertFailsWith<Exception> {
            AvroReader.readAvro<ManifestListEntry>(file.absolutePath)
        }
    }

    @Test
    fun `AvroReader reads valid Avro via file URI`() {
        val schema = Avro.schema<ManifestListEntry>()
        val file = File(tmpDir, "via-uri.avro")
        DataFileWriter(GenericDatumWriter<GenericData.Record>(schema)).use { writer ->
            writer.create(schema, file)
            val record = GenericData.Record(schema)
            record.put("manifest_path", "/test/manifest.avro")
            record.put("manifest_length", 1024L)
            record.put("partition_spec_id", 0)
            record.put("content", 0)
            record.put("sequence_number", 1)
            record.put("min_sequence_number", 1)
            record.put("added_snapshot_id", 1L)
            record.put("added_files_count", 1)
            record.put("existing_files_count", 0)
            record.put("deleted_files_count", 0)
            record.put("added_rows_count", 100L)
            record.put("existing_rows_count", 0L)
            record.put("deleted_rows_count", 0L)
            writer.append(record)
        }
        // Read via file: URI
        val result = AvroReader.readAvro<ManifestListEntry>(file.toURI().toString())
        assertEquals(1, result.entries.size)
        assertEquals("/test/manifest.avro", result.entries[0].manifestPath)
        assertEquals(0, result.errors.size)
    }

    @Test
    fun `AvroReader returns empty result for valid Avro with zero records`() {
        val schema = Avro.schema<ManifestListEntry>()
        val file = File(tmpDir, "empty-records.avro")
        DataFileWriter(GenericDatumWriter<GenericData.Record>(schema)).use { writer ->
            writer.create(schema, file)
            // Write no records
        }
        val result = AvroReader.readAvro<ManifestListEntry>(file.absolutePath)
        assertEquals(0, result.entries.size)
        assertEquals(0, result.errors.size)
    }

    @Test
    fun `AvroReader rejects unsupported URI schemes`() {
        assertFailsWith<IllegalArgumentException> {
            AvroReader.readAvro<ManifestListEntry>("s3://bucket/path/file.avro")
        }
        assertFailsWith<IllegalArgumentException> {
            AvroReader.readAvro<ManifestListEntry>("hdfs://namenode/path/file.avro")
        }
        assertFailsWith<IllegalArgumentException> {
            AvroReader.readAvro<ManifestListEntry>("gs://bucket/path/file.avro")
        }
    }

    @Test
    fun `AvroReader handles file URI for non-existent file`() {
        assertFailsWith<Exception> {
            AvroReader.readAvro<ManifestListEntry>("file:///nonexistent/file.avro")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Paimon Pipeline Error Recovery
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `Paimon pipeline recovers from corrupt manifest list Avro`() {
        val schemaDir = File(tmpDir, "schema").apply { mkdirs() }
        val snapshotDir = File(tmpDir, "snapshot").apply { mkdirs() }
        val manifestDir = File(tmpDir, "manifest").apply { mkdirs() }

        File(schemaDir, "schema-0").writeText("""{"id": 0, "fields": []}""")
        File(snapshotDir, "snapshot-1").writeText("""
            {"id": 1, "schemaId": 0, "baseManifestList": "manifest/manifest-list-base-0", "commitKind": "APPEND"}
        """.trimIndent())
        // Write corrupt Avro file
        File(manifestDir, "manifest-list-base-0").writeText("not avro data")

        val model = PaimonUnifiedTableModel(tmpDir.toPath())
        assertEquals(1, model.snapshots.size)
        // Error collected but model still loads
        assertTrue(model.snapshots[0].readErrors.isNotEmpty())
        assertTrue(model.snapshots[0].readErrors.any { it.stage.contains("manifest-list") })
        assertTrue(model.snapshots[0].baseManifests.isEmpty())
    }

    @Test
    fun `Paimon pipeline recovers from corrupt manifest Avro`() {
        val schemaDir = File(tmpDir, "schema").apply { mkdirs() }
        val snapshotDir = File(tmpDir, "snapshot").apply { mkdirs() }
        val manifestDir = File(tmpDir, "manifest").apply { mkdirs() }

        File(schemaDir, "schema-0").writeText("""{"id": 0, "fields": []}""")
        File(snapshotDir, "snapshot-1").writeText("""
            {"id": 1, "schemaId": 0, "baseManifestList": "manifest/manifest-list-base-0", "commitKind": "APPEND"}
        """.trimIndent())

        // Valid manifest list pointing to corrupt manifest
        writeManifestListAvro(
            File(manifestDir, "manifest-list-base-0"),
            listOf(PaimonManifestListData(fileName = "manifest-0", fileSize = 1024))
        )
        // Corrupt manifest file
        File(manifestDir, "manifest-0").writeText("not avro data")

        val model = PaimonUnifiedTableModel(tmpDir.toPath())
        assertEquals(1, model.snapshots.size)
        assertEquals(1, model.snapshots[0].baseManifests.size)
        val manifest = model.snapshots[0].baseManifests[0]
        assertTrue(manifest.readErrors.isNotEmpty())
        assertTrue(manifest.readErrors.any { it.stage == "read-manifest" })
        assertTrue(manifest.entries.isEmpty())
    }

    @Test
    fun `Paimon pipeline handles snapshot with only changelog manifests`() {
        val schemaDir = File(tmpDir, "schema").apply { mkdirs() }
        val snapshotDir = File(tmpDir, "snapshot").apply { mkdirs() }
        val manifestDir = File(tmpDir, "manifest").apply { mkdirs() }

        File(schemaDir, "schema-0").writeText("""{"id": 0, "fields": []}""")
        File(snapshotDir, "snapshot-1").writeText("""
            {"id": 1, "schemaId": 0, "changelogManifestList": "manifest/manifest-list-changelog-0", "commitKind": "APPEND"}
        """.trimIndent())

        writeManifestListAvro(
            File(manifestDir, "manifest-list-changelog-0"),
            listOf(PaimonManifestListData(fileName = "manifest-0", fileSize = 512))
        )
        writePaimonManifestAvro(
            File(manifestDir, "manifest-0"),
            listOf(PaimonManifestEntryData(kind = 0, bucket = 0, fileName = "data-0.parquet", rowCount = 10))
        )

        val model = PaimonUnifiedTableModel(tmpDir.toPath())
        assertEquals(1, model.snapshots.size)
        assertTrue(model.snapshots[0].baseManifests.isEmpty())
        assertTrue(model.snapshots[0].deltaManifests.isEmpty())
        assertEquals(1, model.snapshots[0].changelogManifests.size)
        assertEquals(1, model.snapshots[0].changelogManifests[0].entries.size)
    }

    @Test
    fun `Paimon pipeline handles mix of valid and invalid snapshots`() {
        val schemaDir = File(tmpDir, "schema").apply { mkdirs() }
        val snapshotDir = File(tmpDir, "snapshot").apply { mkdirs() }

        File(schemaDir, "schema-0").writeText("""{"id": 0, "fields": []}""")
        File(snapshotDir, "snapshot-1").writeText("""{"id": 1, "schemaId": 0, "commitKind": "APPEND"}""")
        File(snapshotDir, "snapshot-2").writeText("CORRUPT JSON")
        File(snapshotDir, "snapshot-3").writeText("""{"id": 3, "schemaId": 0, "commitKind": "COMPACT"}""")

        val model = PaimonUnifiedTableModel(tmpDir.toPath())
        assertEquals(2, model.snapshots.size) // snapshot-2 skipped
        assertEquals(1L, model.snapshots[0].metadata.id)
        assertEquals(3L, model.snapshots[1].metadata.id)
        assertTrue(model.readErrors.any { it.stage == "read-snapshot" })
    }

    // ═══════════════════════════════════════════════════════════════
    //  Iceberg Pipeline Error Recovery
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `Iceberg pipeline recovers from corrupt manifest list Avro`() {
        val metadataDir = File(tmpDir, "metadata").apply { mkdirs() }

        File(metadataDir, "v1.metadata.json").writeText(icebergMetadataJson(
            snapshots = listOf(icebergSnapshotJson(1, "snap-1.avro"))
        ))
        // Corrupt Avro file
        File(metadataDir, "snap-1.avro").writeText("not avro data")

        val model = UnifiedTableModel(tmpDir.toPath())
        assertEquals(1, model.metadatas.size)
        assertEquals(1, model.metadatas[0].snapshots.size)
        val snap = model.metadatas[0].snapshots[0]
        assertTrue(snap.readErrors.isNotEmpty())
        assertTrue(snap.readErrors.any { it.stage == "read-manifest-list-file" })
        assertTrue(snap.manifests.isEmpty())
    }

    @Test
    fun `Iceberg pipeline recovers from corrupt manifest Avro`() {
        val metadataDir = File(tmpDir, "metadata").apply { mkdirs() }

        File(metadataDir, "v1.metadata.json").writeText(icebergMetadataJson(
            snapshots = listOf(icebergSnapshotJson(1, "snap-1.avro"))
        ))

        writeIcebergManifestListAvro(
            File(metadataDir, "snap-1.avro"),
            listOf(IcebergManifestListData(manifestPath = "${metadataDir.absolutePath}/manifest-1.avro"))
        )
        // Corrupt manifest file
        File(metadataDir, "manifest-1.avro").writeText("not avro data")

        val model = UnifiedTableModel(tmpDir.toPath())
        val manifest = model.metadatas[0].snapshots[0].manifests[0]
        assertTrue(manifest.readErrors.isNotEmpty())
        assertTrue(manifest.readErrors.any { it.stage == "read-manifest-file" })
        assertTrue(manifest.dataFiles.isEmpty())
    }

    @Test
    fun `Iceberg pipeline handles mix of valid and corrupt metadata JSONs`() {
        val metadataDir = File(tmpDir, "metadata").apply { mkdirs() }

        File(metadataDir, "v1.metadata.json").writeText(icebergMetadataJson(
            snapshots = listOf(icebergSnapshotJson(1, "snap-1.avro"))
        ))
        File(metadataDir, "v2.metadata.json").writeText("CORRUPT")
        File(metadataDir, "v3.metadata.json").writeText(icebergMetadataJson(
            snapshots = listOf(icebergSnapshotJson(3, "snap-3.avro"))
        ))

        val model = UnifiedTableModel(tmpDir.toPath())
        assertEquals(2, model.metadatas.size) // v2 skipped
        assertTrue(model.readErrors.any { it.stage == "read-metadata-json" })
    }

    @Test
    fun `Iceberg pipeline handles snapshot with null manifest-list`() {
        val metadataDir = File(tmpDir, "metadata").apply { mkdirs() }

        File(metadataDir, "v1.metadata.json").writeText("""
            {
              "format-version": 2,
              "snapshots": [{"snapshot-id": 1, "timestamp-ms": 1700000000000}],
              "schemas": [], "partition-specs": [], "sort-orders": []
            }
        """.trimIndent())

        val model = UnifiedTableModel(tmpDir.toPath())
        assertEquals(1, model.metadatas.size)
        // Snapshot with null manifest-list → resolves to metadata dir itself
        // which is not an Avro file → error collected
        assertEquals(1, model.metadatas[0].snapshots.size)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Graph Builder Error Recovery
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `Paimon graph builder creates error nodes for read errors`() {
        val model = PaimonUnifiedTableModel(
            path = tmpDir.toPath(),
            name = "test-table",
            schemas = listOf(PaimonSchema(id = 0)),
            snapshots = listOf(
                PaimonUnifiedSnapshot(
                    path = tmpDir.toPath().resolve("snapshot/snapshot-1"),
                    metadata = PaimonSnapshot(id = 1, schemaId = 0, commitKind = "APPEND"),
                    schema = PaimonSchema(id = 0),
                    baseManifests = emptyList(),
                    deltaManifests = emptyList(),
                    changelogManifests = emptyList(),
                    readErrors = listOf(
                        UnifiedReadError("test-stage", "/test/path", "Test error message")
                    )
                )
            ),
            readErrors = listOf(
                UnifiedReadError("top-level-stage", "/test/path", "Top level error")
            )
        )

        val (nodes, edges) = PaimonGraphBuilder.buildGraph(model, showRows = false)
        val errorNodes = nodes.filterIsInstance<GraphNode.ErrorNode>()
        assertTrue(errorNodes.size >= 2, "Expected at least 2 error nodes, got ${errorNodes.size}")
    }

    @Test
    fun `Iceberg graph builder creates error nodes for read errors`() {
        val model = UnifiedTableModel(
            path = tmpDir.toPath(),
            name = "test-table",
            versionHint = "1",
            metadatas = listOf(
                UnifiedMetadata(
                    path = tmpDir.toPath().resolve("metadata/v1.metadata.json"),
                    metadata = TableMetadata(formatVersion = 2),
                    snapshots = listOf(
                        UnifiedSnapshot(
                            path = tmpDir.toPath().resolve("metadata/snap-1.avro"),
                            metadata = Snapshot(snapshotId = 1, timestampMs = 1700000000000L),
                            manifests = emptyList(),
                            readErrors = listOf(
                                UnifiedReadError("test-stage", "/test/path", "Manifest list read failed")
                            )
                        )
                    )
                )
            ),
            readErrors = listOf(
                UnifiedReadError("top-level", "/test/path", "Top error")
            )
        )

        val result = IcebergGraphBuilder.buildGraph(model, showRows = false)
        val errorNodes = result.nodes.filterIsInstance<GraphNode.ErrorNode>()
        assertTrue(errorNodes.size >= 2, "Expected at least 2 error nodes, got ${errorNodes.size}")
    }

    // ═══════════════════════════════════════════════════════════════
    //  End-to-End Error Propagation
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `Paimon errors propagate from reader through model to graph`() {
        val schemaDir = File(tmpDir, "schema").apply { mkdirs() }
        val snapshotDir = File(tmpDir, "snapshot").apply { mkdirs() }
        val manifestDir = File(tmpDir, "manifest").apply { mkdirs() }

        File(schemaDir, "schema-0").writeText("""{"id": 0, "fields": []}""")
        File(snapshotDir, "snapshot-1").writeText("""
            {"id": 1, "schemaId": 0, "baseManifestList": "manifest/manifest-list-base-0", "commitKind": "APPEND"}
        """.trimIndent())
        // Corrupt manifest list
        File(manifestDir, "manifest-list-base-0").writeText("corrupt")

        val model = PaimonUnifiedTableModel(tmpDir.toPath())
        val (nodes, _) = PaimonGraphBuilder.buildGraph(model, showRows = false)

        // Errors should be visible as error nodes in the graph
        val errorNodes = nodes.filterIsInstance<GraphNode.ErrorNode>()
        assertTrue(errorNodes.isNotEmpty(), "Expected error nodes in graph for corrupt manifest list")
    }

    @Test
    fun `Iceberg errors propagate from reader through model to graph`() {
        val metadataDir = File(tmpDir, "metadata").apply { mkdirs() }

        File(metadataDir, "v1.metadata.json").writeText(icebergMetadataJson(
            snapshots = listOf(icebergSnapshotJson(1, "snap-1.avro"))
        ))
        // Corrupt manifest list
        File(metadataDir, "snap-1.avro").writeText("corrupt")

        val model = UnifiedTableModel(tmpDir.toPath())
        val result = IcebergGraphBuilder.buildGraph(model, showRows = false)

        val errorNodes = result.nodes.filterIsInstance<GraphNode.ErrorNode>()
        assertTrue(errorNodes.isNotEmpty(), "Expected error nodes in graph for corrupt manifest list")
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    // --- Paimon Avro helpers ---

    private data class PaimonManifestListData(val fileName: String, val fileSize: Long = 1024)
    private data class PaimonManifestEntryData(val kind: Int, val bucket: Int, val fileName: String, val rowCount: Long)

    private fun writeManifestListAvro(file: File, entries: List<PaimonManifestListData>) {
        val schema = Avro.schema<PaimonManifestFileMeta>()
        DataFileWriter(GenericDatumWriter<GenericData.Record>(schema)).use { writer ->
            writer.create(schema, file)
            entries.forEach { entry ->
                val record = GenericData.Record(schema)
                record.put("_FILE_NAME", entry.fileName)
                record.put("_FILE_SIZE", entry.fileSize)
                record.put("_NUM_ADDED_FILES", 1L)
                record.put("_NUM_DELETED_FILES", 0L)
                record.put("_SCHEMA_ID", 0L)
                writer.append(record)
            }
        }
    }

    private fun writePaimonManifestAvro(file: File, entries: List<PaimonManifestEntryData>) {
        val schema = Avro.schema<PaimonManifestEntry>()
        DataFileWriter(GenericDatumWriter<GenericData.Record>(schema)).use { writer ->
            writer.create(schema, file)
            entries.forEach { entry ->
                val record = GenericData.Record(schema)
                record.put("_KIND", entry.kind)
                record.put("_BUCKET", entry.bucket)
                record.put("_TOTAL_BUCKETS", 1)
                val fileSchema = schema.getField("_FILE").schema()
                val fileRecordSchema = if (fileSchema.isUnion) {
                    fileSchema.types.first { it.type == org.apache.avro.Schema.Type.RECORD }
                } else fileSchema
                val fileRecord = GenericData.Record(fileRecordSchema)
                fileRecord.put("_FILE_NAME", entry.fileName)
                fileRecord.put("_FILE_SIZE", 4096L)
                fileRecord.put("_ROW_COUNT", entry.rowCount)
                fileRecord.put("_LEVEL", 0)
                fileRecord.put("_SCHEMA_ID", 0L)
                fileRecord.put("_MIN_SEQUENCE_NUMBER", 0L)
                fileRecord.put("_MAX_SEQUENCE_NUMBER", 0L)
                fileRecord.put("_CREATION_TIME", System.currentTimeMillis())
                record.put("_FILE", fileRecord)
                writer.append(record)
            }
        }
    }

    // --- Iceberg JSON helpers ---

    private fun icebergMetadataJson(snapshots: List<String> = emptyList()): String = """
        {
          "format-version": 2,
          "table-uuid": "test-uuid",
          "schemas": [], "partition-specs": [], "sort-orders": [],
          "snapshots": [${snapshots.joinToString(",")}],
          "snapshot-log": [], "metadata-log": []
        }
    """.trimIndent()

    private fun icebergSnapshotJson(snapshotId: Long, manifestList: String): String = """
        {"snapshot-id": $snapshotId, "timestamp-ms": 1700000000000, "manifest-list": "$manifestList", "summary": {}}
    """.trimIndent()

    // --- Iceberg Avro helpers ---

    private data class IcebergManifestListData(
        val manifestPath: String,
        val content: Int = 0,
        val addedSnapshotId: Long = 1,
    )

    private fun writeIcebergManifestListAvro(file: File, entries: List<IcebergManifestListData>) {
        val schema = Avro.schema<ManifestListEntry>()
        DataFileWriter(GenericDatumWriter<GenericData.Record>(schema)).use { writer ->
            writer.create(schema, file)
            entries.forEach { entry ->
                val record = GenericData.Record(schema)
                record.put("manifest_path", entry.manifestPath)
                record.put("manifest_length", 1024L)
                record.put("partition_spec_id", 0)
                record.put("content", entry.content)
                record.put("sequence_number", 1)
                record.put("min_sequence_number", 1)
                record.put("added_snapshot_id", entry.addedSnapshotId)
                record.put("added_files_count", 1)
                record.put("existing_files_count", 0)
                record.put("deleted_files_count", 0)
                record.put("added_rows_count", 100L)
                record.put("existing_rows_count", 0L)
                record.put("deleted_rows_count", 0L)
                writer.append(record)
            }
        }
    }
}
