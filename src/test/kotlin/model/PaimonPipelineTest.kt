@file:OptIn(com.github.avrokotlin.avro4k.ExperimentalAvro4kApi::class)

package model

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.schema
import org.apache.avro.file.DataFileWriter
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericDatumWriter
import service.PaimonGraphBuilder
import service.PaimonReader
import java.io.File
import java.nio.file.Path
import kotlin.test.*

/**
 * Integration tests for the Paimon reading pipeline:
 * Filesystem → PaimonReader → PaimonUnifiedTableModel → PaimonGraphBuilder
 */
class PaimonPipelineTest {

    private lateinit var tmpDir: File

    @BeforeTest
    fun setUp() {
        tmpDir = kotlin.io.path.createTempDirectory("paimon-pipeline-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    // ═══════════════════════════════════════════════════════════════
    //  PaimonReader Unit Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `PaimonReader reads snapshot from fixture`() {
        val fixturePath = fixtureFile("snapshot/snapshot-1")
        val snapshot = PaimonReader.readSnapshot(fixturePath)
        assertEquals(1L, snapshot.id)
        assertEquals(0, snapshot.schemaId)
        assertEquals("APPEND", snapshot.commitKind)
        assertEquals(100L, snapshot.totalRecordCount)
        assertEquals("manifest/manifest-list-base-0", snapshot.baseManifestList)
        assertEquals("manifest/manifest-list-delta-0", snapshot.deltaManifestList)
        assertNull(snapshot.changelogManifestList)
    }

    @Test
    fun `PaimonReader reads schema from fixture`() {
        val fixturePath = fixtureFile("schema/schema-0")
        val schema = PaimonReader.readSchema(fixturePath)
        assertEquals(0, schema.id)
        assertEquals(2, schema.fields.size)
        assertEquals("k", schema.fields[0].name)
        assertEquals("INT NOT NULL", schema.fields[0].type)
        assertEquals("v", schema.fields[1].name)
        assertEquals(listOf("k"), schema.primaryKeys)
    }

    @Test
    fun `PaimonReader throws on non-existent snapshot file`() {
        assertFailsWith<IllegalArgumentException> {
            PaimonReader.readSnapshot("/nonexistent/snapshot-1")
        }
    }

    @Test
    fun `PaimonReader throws on non-existent schema file`() {
        assertFailsWith<IllegalArgumentException> {
            PaimonReader.readSchema("/nonexistent/schema-0")
        }
    }

    @Test
    fun `PaimonReader reads snapshot with unknown fields gracefully`() {
        val snapshotFile = File(tmpDir, "snapshot-test")
        snapshotFile.writeText("""
            {
              "id": 99,
              "schemaId": 0,
              "unknownField": "should be ignored",
              "commitKind": "OVERWRITE"
            }
        """.trimIndent())
        val snapshot = PaimonReader.readSnapshot(snapshotFile.absolutePath)
        assertEquals(99L, snapshot.id)
        assertEquals("OVERWRITE", snapshot.commitKind)
    }

    @Test
    fun `PaimonReader reads minimal snapshot with all defaults`() {
        val snapshotFile = File(tmpDir, "snapshot-test")
        snapshotFile.writeText("{}")
        val snapshot = PaimonReader.readSnapshot(snapshotFile.absolutePath)
        assertNull(snapshot.id)
        assertNull(snapshot.schemaId)
        assertNull(snapshot.commitKind)
        assertNull(snapshot.totalRecordCount)
    }

    // ═══════════════════════════════════════════════════════════════
    //  PaimonUnifiedTableModel — JSON-Only Pipeline
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `PaimonUnifiedTableModel reads schemas from directory`() {
        val table = createTableDir(
            schemas = listOf(
                0 to """{"id": 0, "fields": [{"id": 0, "name": "k", "type": "INT"}], "primaryKeys": ["k"]}""",
                1 to """{"id": 1, "fields": [{"id": 0, "name": "k", "type": "INT"}, {"id": 1, "name": "v", "type": "STRING"}], "primaryKeys": ["k"]}"""
            ),
            snapshots = emptyList()
        )
        val model = PaimonUnifiedTableModel(table)
        assertEquals(2, model.schemas.size)
        assertEquals(0, model.schemas[0].id)
        assertEquals(1, model.schemas[1].id)
        assertEquals(1, model.schemas[0].fields.size)
        assertEquals(2, model.schemas[1].fields.size)
    }

    @Test
    fun `PaimonUnifiedTableModel reads and links snapshots to schemas`() {
        val table = createTableDir(
            schemas = listOf(
                0 to """{"id": 0, "fields": [{"id": 0, "name": "k", "type": "INT"}]}"""
            ),
            snapshots = listOf(
                1 to """{"id": 1, "schemaId": 0, "commitKind": "APPEND", "timeMillis": 1700000000000, "totalRecordCount": 100}""",
                2 to """{"id": 2, "schemaId": 0, "commitKind": "COMPACT", "timeMillis": 1700000060000, "totalRecordCount": 200}"""
            )
        )
        val model = PaimonUnifiedTableModel(table)
        assertEquals(1, model.schemas.size)
        assertEquals(2, model.snapshots.size)
        // Sorted by id
        assertEquals(1L, model.snapshots[0].metadata.id)
        assertEquals(2L, model.snapshots[1].metadata.id)
        // Schema linked
        assertNotNull(model.snapshots[0].schema)
        assertEquals(0, model.snapshots[0].schema?.id)
    }

    @Test
    fun `PaimonUnifiedTableModel handles snapshot with non-existent schema id`() {
        val table = createTableDir(
            schemas = listOf(
                0 to """{"id": 0, "fields": []}"""
            ),
            snapshots = listOf(
                1 to """{"id": 1, "schemaId": 99, "commitKind": "APPEND"}"""
            )
        )
        val model = PaimonUnifiedTableModel(table)
        assertEquals(1, model.snapshots.size)
        assertNull(model.snapshots[0].schema) // Schema 99 doesn't exist
    }

    @Test
    fun `PaimonUnifiedTableModel collects errors for missing manifest lists`() {
        val table = createTableDir(
            schemas = listOf(0 to """{"id": 0}"""),
            snapshots = listOf(
                1 to """{"id": 1, "schemaId": 0, "baseManifestList": "manifest/manifest-list-base-0", "deltaManifestList": "manifest/manifest-list-delta-0"}"""
            )
        )
        val model = PaimonUnifiedTableModel(table)
        assertEquals(1, model.snapshots.size)
        // Manifest files don't exist → errors collected
        val snapshotErrors = model.snapshots[0].readErrors
        assertTrue(snapshotErrors.isNotEmpty(), "Expected errors for missing manifest list files")
        assertTrue(snapshotErrors.any { it.stage.contains("manifest-list") })
        // But model still loads — not a hard failure
        assertTrue(model.snapshots[0].baseManifests.isEmpty())
        assertTrue(model.snapshots[0].deltaManifests.isEmpty())
    }

    @Test
    fun `PaimonUnifiedTableModel handles missing schema directory`() {
        // Only create snapshot dir, no schema dir
        File(tmpDir, "snapshot").mkdirs()
        File(tmpDir, "snapshot/snapshot-1").writeText("""{"id": 1}""")

        val model = PaimonUnifiedTableModel(tmpDir.toPath())
        assertEquals(0, model.schemas.size)
        assertTrue(model.readErrors.isNotEmpty())
        assertTrue(model.readErrors.any { it.stage == "list-schema-files" })
        // Snapshots still load
        assertEquals(1, model.snapshots.size)
    }

    @Test
    fun `PaimonUnifiedTableModel handles missing snapshot directory`() {
        File(tmpDir, "schema").mkdirs()
        File(tmpDir, "schema/schema-0").writeText("""{"id": 0}""")

        val model = PaimonUnifiedTableModel(tmpDir.toPath())
        assertEquals(1, model.schemas.size)
        assertEquals(0, model.snapshots.size)
        assertTrue(model.readErrors.isNotEmpty())
        assertTrue(model.readErrors.any { it.stage == "list-snapshot-files" })
    }

    @Test
    fun `PaimonUnifiedTableModel handles malformed snapshot JSON`() {
        val table = createTableDir(
            schemas = listOf(0 to """{"id": 0}"""),
            snapshots = listOf(
                1 to """{"id": 1, "schemaId": 0, "commitKind": "APPEND"}""",
                2 to """NOT VALID JSON""",
                3 to """{"id": 3, "schemaId": 0, "commitKind": "COMPACT"}"""
            )
        )
        val model = PaimonUnifiedTableModel(table)
        // Malformed snapshot skipped, others still load
        assertEquals(2, model.snapshots.size)
        assertEquals(1L, model.snapshots[0].metadata.id)
        assertEquals(3L, model.snapshots[1].metadata.id)
        // Error collected for the bad snapshot
        assertTrue(model.readErrors.any { it.stage == "read-snapshot" })
    }

    @Test
    fun `PaimonUnifiedTableModel handles empty table`() {
        val table = createTableDir(schemas = emptyList(), snapshots = emptyList())
        val model = PaimonUnifiedTableModel(table)
        assertEquals(0, model.schemas.size)
        assertEquals(0, model.snapshots.size)
        assertEquals(tmpDir.name, model.name)
    }

    @Test
    fun `PaimonUnifiedTableModel handles null manifest list paths`() {
        val table = createTableDir(
            schemas = listOf(0 to """{"id": 0}"""),
            snapshots = listOf(
                1 to """{"id": 1, "schemaId": 0, "baseManifestList": null, "deltaManifestList": null, "changelogManifestList": null}"""
            )
        )
        val model = PaimonUnifiedTableModel(table)
        assertEquals(1, model.snapshots.size)
        assertTrue(model.snapshots[0].baseManifests.isEmpty())
        assertTrue(model.snapshots[0].deltaManifests.isEmpty())
        assertTrue(model.snapshots[0].changelogManifests.isEmpty())
        assertTrue(model.snapshots[0].readErrors.isEmpty())
    }

    @Test
    fun `PaimonUnifiedTableModel handles malformed schema JSON`() {
        val table = createTableDir(
            schemas = listOf(
                0 to """{"id": 0, "fields": []}""",
                1 to """INVALID JSON"""
            ),
            snapshots = listOf(1 to """{"id": 1, "schemaId": 0}""")
        )
        val model = PaimonUnifiedTableModel(table)
        assertEquals(1, model.schemas.size) // Only valid one
        assertEquals(0, model.schemas[0].id)
        assertTrue(model.readErrors.any { it.stage == "read-schema" })
    }

    // ═══════════════════════════════════════════════════════════════
    //  Full Pipeline with Avro Fixtures
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `full pipeline reads manifest list and manifest Avro files`() {
        val table = createCompleteTable()
        val model = PaimonUnifiedTableModel(table)

        // Verify schemas
        assertEquals(1, model.schemas.size)
        assertEquals(0, model.schemas[0].id)

        // Verify snapshots
        assertEquals(1, model.snapshots.size)
        val snap = model.snapshots[0]
        assertEquals(1L, snap.metadata.id)
        assertEquals("APPEND", snap.metadata.commitKind)
        assertNotNull(snap.schema)

        // Verify base manifests (from Avro manifest list)
        assertEquals(1, snap.baseManifests.size)
        val manifest = snap.baseManifests[0]
        assertEquals("manifest-0", manifest.metadata.fileName)

        // Verify data files (from Avro manifest)
        assertEquals(2, manifest.entries.size)
        assertEquals("data-0.parquet", manifest.entries[0].metadata.file?.fileName)
        assertEquals("data-1.parquet", manifest.entries[1].metadata.file?.fileName)
        assertEquals(0, manifest.entries[0].metadata.kind) // ADD
        assertEquals(100L, manifest.entries[0].metadata.file?.rowCount)

        // No read errors in the pipeline
        assertEquals(0, model.readErrors.size, "Top-level errors: ${model.readErrors}")
        assertEquals(0, snap.readErrors.size, "Snapshot errors: ${snap.readErrors}")
        assertEquals(0, manifest.readErrors.size, "Manifest errors: ${manifest.readErrors}")
    }

    @Test
    fun `full pipeline produces correct graph structure`() {
        val table = createCompleteTable()
        val model = PaimonUnifiedTableModel(table)
        val (nodes, edges) = PaimonGraphBuilder.buildGraph(model, showRows = false)

        // Expected nodes: table_root + schema + snapshot + manifest_list + manifest + 2 data files = 7
        val nodeTypes = nodes.groupBy { it::class.simpleName }
        assertEquals(1, nodeTypes["TableNode"]?.size, "Expected 1 table node")
        assertEquals(1, nodeTypes["PaimonSchemaNode"]?.size, "Expected 1 schema node")
        assertEquals(1, nodeTypes["PaimonSnapshotNode"]?.size, "Expected 1 snapshot node")
        assertTrue((nodeTypes["PaimonManifestListNode"]?.size ?: 0) >= 1, "Expected at least 1 manifest list node")
        assertTrue((nodeTypes["PaimonManifestNode"]?.size ?: 0) >= 1, "Expected at least 1 manifest node")
        assertEquals(2, nodeTypes["PaimonDataFileNode"]?.size, "Expected 2 data file nodes")

        // Verify edges connect properly
        assertTrue(edges.isNotEmpty())
        // Table → Snapshot edge
        assertTrue(edges.any { it.fromId == "table_root" && it.toId.startsWith("psnap_") })
        // Snapshot → ManifestList edge
        assertTrue(edges.any { it.fromId.startsWith("psnap_") && it.toId.startsWith("pml_") })
    }

    @Test
    fun `full pipeline with multiple snapshots and schema evolution`() {
        val table = createMultiSnapshotTable()
        val model = PaimonUnifiedTableModel(table)

        assertEquals(2, model.schemas.size)
        assertEquals(2, model.snapshots.size)

        // Snapshot 1 → schema 0
        assertEquals(0, model.snapshots[0].schema?.id)
        // Snapshot 2 → schema 1
        assertEquals(1, model.snapshots[1].schema?.id)

        // Both snapshots have manifests
        assertEquals(1, model.snapshots[0].baseManifests.size)
        assertEquals(1, model.snapshots[1].baseManifests.size)

        // No errors
        assertEquals(0, model.readErrors.size, "Errors: ${model.readErrors}")
        model.snapshots.forEach { snap ->
            assertEquals(0, snap.readErrors.size, "Snapshot ${snap.metadata.id} errors: ${snap.readErrors}")
        }
    }

    @Test
    fun `full pipeline handles DELETE kind entries in manifest`() {
        val table = createTableWithDeleteEntries()
        val model = PaimonUnifiedTableModel(table)

        assertEquals(1, model.snapshots.size)
        val manifest = model.snapshots[0].deltaManifests[0]
        // One ADD (kind=0) and one DELETE (kind=1)
        assertEquals(2, manifest.entries.size)
        assertEquals(0, manifest.entries[0].metadata.kind)
        assertEquals(1, manifest.entries[1].metadata.kind)
    }

    @Test
    fun `full pipeline graph builder preserves data file metadata`() {
        val table = createCompleteTable()
        val model = PaimonUnifiedTableModel(table)
        val (nodes, _) = PaimonGraphBuilder.buildGraph(model, showRows = false)

        val dataFileNodes = nodes.filterIsInstance<GraphNode.PaimonDataFileNode>()
        assertEquals(2, dataFileNodes.size)
        // Verify metadata is preserved through the pipeline
        assertTrue(dataFileNodes.any { it.entry.file?.fileName == "data-0.parquet" })
        assertTrue(dataFileNodes.any { it.entry.file?.fileName == "data-1.parquet" })
        // Verify row count propagated
        val node0 = dataFileNodes.first { it.entry.file?.fileName == "data-0.parquet" }
        assertEquals(100L, node0.entry.file?.rowCount)
    }

    // M-9 regression: Paimon data file path traversal attempt must be flagged.
    @Test
    fun `paimon data file with path traversal attempt is flagged`() {
        val schemaDir = File(tmpDir, "schema").apply { mkdirs() }
        val snapshotDir = File(tmpDir, "snapshot").apply { mkdirs() }
        val manifestDir = File(tmpDir, "manifest").apply { mkdirs() }

        File(schemaDir, "schema-0").writeText("""{"id": 0, "fields": [{"id": 0, "name": "k", "type": "INT"}]}""")
        File(snapshotDir, "snapshot-1").writeText("""
            {"id": 1, "schemaId": 0, "baseManifestList": "manifest-list-base-0", "commitKind": "APPEND", "timeMillis": 1700000000000}
        """.trimIndent())

        writeManifestListAvro(
            File(manifestDir, "manifest-list-base-0"),
            listOf(ManifestListEntry(fileName = "manifest-traversal", fileSize = 1024, numAddedFiles = 1, numDeletedFiles = 0, schemaId = 0))
        )
        // Malicious filename with traversal sequence
        writeManifestAvro(
            File(manifestDir, "manifest-traversal"),
            listOf(ManifestEntryData(kind = 0, bucket = 0, fileName = "../../../etc/passwd", fileSize = 0, rowCount = 0, level = 0))
        )

        val model = PaimonUnifiedTableModel(tmpDir.toPath())
        val manifest = model.snapshots[0].baseManifests[0]
        val traversalErrors = manifest.readErrors.filter { it.stage == "path-traversal-check" }
        assertTrue(traversalErrors.isNotEmpty(),
            "Path traversal attempt must be flagged. errors=${manifest.readErrors}")
    }

    @Test
    fun `manifest list in manifest subdirectory is resolved correctly`() {
        // Regression test: Paimon stores manifest lists in manifest/ subdir,
        // but snapshot JSON references them by filename only (no manifest/ prefix).
        val schemaDir = File(tmpDir, "schema").apply { mkdirs() }
        val snapshotDir = File(tmpDir, "snapshot").apply { mkdirs() }
        val manifestDir = File(tmpDir, "manifest").apply { mkdirs() }

        File(schemaDir, "schema-0").writeText("""{"id": 0, "fields": [{"id": 0, "name": "k", "type": "INT"}], "primaryKeys": ["k"]}""")
        // Snapshot references manifest list by filename only (no "manifest/" prefix)
        File(snapshotDir, "snapshot-1").writeText("""
            {"id": 1, "schemaId": 0, "baseManifestList": "manifest-list-base-0", "commitKind": "APPEND", "timeMillis": 1700000000000, "totalRecordCount": 50}
        """.trimIndent())

        // Manifest list lives in manifest/ subdirectory
        writeManifestListAvro(
            File(manifestDir, "manifest-list-base-0"),
            listOf(ManifestListEntry(fileName = "manifest-0", fileSize = 1024, numAddedFiles = 1, numDeletedFiles = 0, schemaId = 0))
        )
        writeManifestAvro(
            File(manifestDir, "manifest-0"),
            listOf(ManifestEntryData(kind = 0, bucket = 0, fileName = "data-0.parquet", fileSize = 4096, rowCount = 50, level = 0))
        )

        val model = PaimonUnifiedTableModel(tmpDir.toPath())

        assertEquals(1, model.snapshots.size)
        assertEquals(0, model.readErrors.size, "Top-level errors: ${model.readErrors}")
        assertEquals(0, model.snapshots[0].readErrors.size, "Snapshot errors: ${model.snapshots[0].readErrors}")
        assertEquals(1, model.snapshots[0].baseManifests.size, "Should find manifest via manifest/ subdir")
        assertEquals(1, model.snapshots[0].baseManifests[0].entries.size, "Should find data file entries")
    }

    // ═══════════════════════════════════════════════════════════════
    //  Fixture Helpers
    // ═══════════════════════════════════════════════════════════════

    private fun fixtureFile(relativePath: String): String {
        return javaClass.classLoader.getResource("paimon-fixtures/$relativePath")?.path
            ?: error("Fixture not found: paimon-fixtures/$relativePath")
    }

    private fun createTableDir(
        schemas: List<Pair<Int, String>>,
        snapshots: List<Pair<Int, String>>
    ): Path {
        val schemaDir = File(tmpDir, "schema").apply { mkdirs() }
        val snapshotDir = File(tmpDir, "snapshot").apply { mkdirs() }
        schemas.forEach { (id, json) ->
            File(schemaDir, "schema-$id").writeText(json)
        }
        snapshots.forEach { (id, json) ->
            File(snapshotDir, "snapshot-$id").writeText(json)
        }
        return tmpDir.toPath()
    }

    /**
     * Creates a complete Paimon table with:
     * - 1 schema (id=0)
     * - 1 snapshot (id=1) with base manifest list
     * - 1 manifest list pointing to 1 manifest
     * - 1 manifest with 2 data file entries
     */
    private fun createCompleteTable(): Path {
        val schemaDir = File(tmpDir, "schema").apply { mkdirs() }
        val snapshotDir = File(tmpDir, "snapshot").apply { mkdirs() }
        val manifestDir = File(tmpDir, "manifest").apply { mkdirs() }

        // Schema
        File(schemaDir, "schema-0").writeText("""
            {"id": 0, "fields": [{"id": 0, "name": "k", "type": "INT NOT NULL"}, {"id": 1, "name": "v", "type": "STRING"}], "primaryKeys": ["k"]}
        """.trimIndent())

        // Snapshot
        File(snapshotDir, "snapshot-1").writeText("""
            {"id": 1, "schemaId": 0, "baseManifestList": "manifest/manifest-list-base-0", "commitKind": "APPEND", "timeMillis": 1700000000000, "totalRecordCount": 100}
        """.trimIndent())

        // Avro manifest list → points to manifest-0
        writeManifestListAvro(
            File(manifestDir, "manifest-list-base-0"),
            listOf(
                ManifestListEntry(fileName = "manifest-0", fileSize = 2048, numAddedFiles = 2, numDeletedFiles = 0, schemaId = 0)
            )
        )

        // Avro manifest → 2 data files
        writeManifestAvro(
            File(manifestDir, "manifest-0"),
            listOf(
                ManifestEntryData(kind = 0, bucket = 0, fileName = "data-0.parquet", fileSize = 4096, rowCount = 100, level = 0),
                ManifestEntryData(kind = 0, bucket = 0, fileName = "data-1.parquet", fileSize = 8192, rowCount = 200, level = 0),
            )
        )

        return tmpDir.toPath()
    }

    private fun createMultiSnapshotTable(): Path {
        val schemaDir = File(tmpDir, "schema").apply { mkdirs() }
        val snapshotDir = File(tmpDir, "snapshot").apply { mkdirs() }
        val manifestDir = File(tmpDir, "manifest").apply { mkdirs() }

        // Two schemas (schema evolution)
        File(schemaDir, "schema-0").writeText("""{"id": 0, "fields": [{"id": 0, "name": "k", "type": "INT"}]}""")
        File(schemaDir, "schema-1").writeText("""{"id": 1, "fields": [{"id": 0, "name": "k", "type": "INT"}, {"id": 1, "name": "v", "type": "STRING"}]}""")

        // Two snapshots
        File(snapshotDir, "snapshot-1").writeText("""
            {"id": 1, "schemaId": 0, "baseManifestList": "manifest/manifest-list-base-0", "commitKind": "APPEND", "timeMillis": 1700000000000, "totalRecordCount": 50}
        """.trimIndent())
        File(snapshotDir, "snapshot-2").writeText("""
            {"id": 2, "schemaId": 1, "baseManifestList": "manifest/manifest-list-base-1", "commitKind": "COMPACT", "timeMillis": 1700000060000, "totalRecordCount": 100}
        """.trimIndent())

        // Manifest lists
        writeManifestListAvro(
            File(manifestDir, "manifest-list-base-0"),
            listOf(ManifestListEntry(fileName = "manifest-0", fileSize = 1024, numAddedFiles = 1, numDeletedFiles = 0, schemaId = 0))
        )
        writeManifestListAvro(
            File(manifestDir, "manifest-list-base-1"),
            listOf(ManifestListEntry(fileName = "manifest-1", fileSize = 1024, numAddedFiles = 1, numDeletedFiles = 0, schemaId = 1))
        )

        // Manifests
        writeManifestAvro(
            File(manifestDir, "manifest-0"),
            listOf(ManifestEntryData(kind = 0, bucket = 0, fileName = "data-0.parquet", fileSize = 4096, rowCount = 50, level = 0))
        )
        writeManifestAvro(
            File(manifestDir, "manifest-1"),
            listOf(ManifestEntryData(kind = 0, bucket = 0, fileName = "data-1.parquet", fileSize = 8192, rowCount = 100, level = 0))
        )

        return tmpDir.toPath()
    }

    private fun createTableWithDeleteEntries(): Path {
        val schemaDir = File(tmpDir, "schema").apply { mkdirs() }
        val snapshotDir = File(tmpDir, "snapshot").apply { mkdirs() }
        val manifestDir = File(tmpDir, "manifest").apply { mkdirs() }

        File(schemaDir, "schema-0").writeText("""{"id": 0, "fields": [{"id": 0, "name": "k", "type": "INT"}]}""")
        File(snapshotDir, "snapshot-1").writeText("""
            {"id": 1, "schemaId": 0, "deltaManifestList": "manifest/manifest-list-delta-0", "commitKind": "COMPACT", "timeMillis": 1700000000000}
        """.trimIndent())

        writeManifestListAvro(
            File(manifestDir, "manifest-list-delta-0"),
            listOf(ManifestListEntry(fileName = "manifest-0", fileSize = 1024, numAddedFiles = 1, numDeletedFiles = 1, schemaId = 0))
        )
        writeManifestAvro(
            File(manifestDir, "manifest-0"),
            listOf(
                ManifestEntryData(kind = 0, bucket = 0, fileName = "added.parquet", fileSize = 4096, rowCount = 50, level = 1),
                ManifestEntryData(kind = 1, bucket = 0, fileName = "deleted.parquet", fileSize = 2048, rowCount = 30, level = 0),
            )
        )

        return tmpDir.toPath()
    }

    // ═══════════════════════════════════════════════════════════════
    //  Avro File Writers
    // ═══════════════════════════════════════════════════════════════

    private data class ManifestListEntry(
        val fileName: String,
        val fileSize: Long,
        val numAddedFiles: Long,
        val numDeletedFiles: Long,
        val schemaId: Long,
    )

    private data class ManifestEntryData(
        val kind: Int,
        val bucket: Int,
        val fileName: String,
        val fileSize: Long,
        val rowCount: Long,
        val level: Int,
    )

    private fun writeManifestListAvro(file: File, entries: List<ManifestListEntry>) {
        val schema = Avro.schema<PaimonManifestFileMeta>()
        DataFileWriter(GenericDatumWriter<GenericData.Record>(schema)).use { writer ->
            writer.create(schema, file)
            entries.forEach { entry ->
                val record = GenericData.Record(schema)
                record.put("_FILE_NAME", entry.fileName)
                record.put("_FILE_SIZE", entry.fileSize)
                record.put("_NUM_ADDED_FILES", entry.numAddedFiles)
                record.put("_NUM_DELETED_FILES", entry.numDeletedFiles)
                record.put("_SCHEMA_ID", entry.schemaId)
                writer.append(record)
            }
        }
    }

    private fun writeManifestAvro(file: File, entries: List<ManifestEntryData>) {
        val schema = Avro.schema<PaimonManifestEntry>()
        DataFileWriter(GenericDatumWriter<GenericData.Record>(schema)).use { writer ->
            writer.create(schema, file)
            entries.forEach { entry ->
                val record = GenericData.Record(schema)
                record.put("_KIND", entry.kind)
                record.put("_BUCKET", entry.bucket)
                record.put("_TOTAL_BUCKETS", 1)

                // Create nested _FILE record
                val fileSchema = schema.getField("_FILE").schema()
                // Handle union type: ["null", record]
                val fileRecordSchema = if (fileSchema.isUnion) {
                    fileSchema.types.first { it.type == org.apache.avro.Schema.Type.RECORD }
                } else fileSchema
                val fileRecord = GenericData.Record(fileRecordSchema)
                fileRecord.put("_FILE_NAME", entry.fileName)
                fileRecord.put("_FILE_SIZE", entry.fileSize)
                fileRecord.put("_ROW_COUNT", entry.rowCount)
                fileRecord.put("_LEVEL", entry.level)
                fileRecord.put("_SCHEMA_ID", 0L)
                fileRecord.put("_MIN_SEQUENCE_NUMBER", 0L)
                fileRecord.put("_MAX_SEQUENCE_NUMBER", 0L)
                fileRecord.put("_CREATION_TIME", System.currentTimeMillis())
                record.put("_FILE", fileRecord)

                writer.append(record)
            }
        }
    }
}
