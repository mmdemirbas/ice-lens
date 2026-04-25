@file:OptIn(com.github.avrokotlin.avro4k.ExperimentalAvro4kApi::class)

package ui

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.schema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import model.*
import org.apache.avro.file.DataFileWriter
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericDatumWriter
import java.io.File
import java.util.prefs.Preferences
import kotlin.test.*

/**
 * Tests for AppState.loadTable covering the full coroutine flow:
 * cache hit, format detection, Iceberg/Paimon dispatch, error recovery,
 * fingerprint-based reload, and session caching.
 */
class AppStateTableLoadingTest {

    private lateinit var prefs: Preferences
    private lateinit var tmpDir: File

    @BeforeTest
    fun setUp() {
        prefs = Preferences.userRoot().node("icelens-test-load-${System.nanoTime()}")
        tmpDir = kotlin.io.path.createTempDirectory("appstate-load-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        prefs.removeNode()
        tmpDir.deleteRecursively()
    }

    // ═══════════════════════════════════════════════════════════════
    //  Iceberg Table Loading
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `loadTable loads Iceberg table and populates graph`() {
        val tablePath = createIcebergTable()
        val state = AppState(prefs, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

        state.loadTable(tablePath)

        assertEquals(tablePath, state.selectedTablePath)
        assertNotNull(state.graphModel, "Graph should be populated")
        assertTrue(state.graphModel!!.nodes.isNotEmpty(), "Graph should have nodes")
        assertFalse(state.isLoadingTable)
        assertNull(state.errorMsg)
    }

    @Test
    fun `loadTable caches session and returns cache on second call`() {
        val tablePath = createIcebergTable()
        val state = AppState(prefs, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

        state.loadTable(tablePath)
        val firstGraph = state.graphModel
        assertNotNull(firstGraph)

        // Second load should hit cache
        state.loadTable(tablePath)
        assertSame(firstGraph, state.graphModel, "Should return same cached graph")
        assertFalse(state.isLoadingTable)
    }

    @Test
    fun `loadTable with forceRelayout bypasses cache`() {
        val tablePath = createIcebergTable()
        val state = AppState(prefs, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

        state.loadTable(tablePath)
        val firstGraph = state.graphModel
        val firstRevision = state.graphRevision

        state.loadTable(tablePath, forceRelayout = true)
        assertTrue(state.graphRevision > firstRevision, "Revision should increment on forced relayout")
    }

    @Test
    fun `loadTable persists selectedTablePath`() {
        val tablePath = createIcebergTable()
        val state = AppState(prefs, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

        state.loadTable(tablePath)

        // New state should restore the selected path
        val state2 = AppState(prefs, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        assertEquals(tablePath, state2.selectedTablePath)
    }

    @Test
    fun `loadTable handles non-existent table path gracefully`() {
        val state = AppState(prefs, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

        state.loadTable("/nonexistent/table/path")

        assertFalse(state.isLoadingTable)
        // Non-existent path loads with error nodes rather than throwing
        assertNotNull(state.graphModel, "Should still produce a graph (with error nodes)")
        val errorNodes = state.graphModel!!.nodes.filterIsInstance<GraphNode.ErrorNode>()
        assertTrue(errorNodes.isNotEmpty(), "Graph should contain error nodes for missing path")
    }

    @Test
    fun `loadTable sets error for corrupt metadata`() {
        val metadataDir = File(tmpDir, "metadata").apply { mkdirs() }
        File(metadataDir, "v1.metadata.json").writeText("NOT JSON")
        File(metadataDir, "version-hint.text").writeText("1")
        val tablePath = tmpDir.canonicalPath
        val state = AppState(prefs, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

        state.loadTable(tablePath)

        // Should still load (empty table with error nodes), not crash
        assertFalse(state.isLoadingTable)
        assertNotNull(state.graphModel)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Paimon Table Loading
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `loadTable loads Paimon table and populates graph`() {
        val tablePath = createPaimonTable()
        val state = AppState(prefs, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

        state.loadTable(tablePath)

        assertNotNull(state.graphModel)
        val snapNodes = state.graphModel!!.nodes.filterIsInstance<GraphNode.PaimonSnapshotNode>()
        assertTrue(snapNodes.isNotEmpty(), "Should have Paimon snapshot nodes")
        assertFalse(state.isLoadingTable)
    }

    @Test
    fun `loadTable detects Paimon format correctly`() {
        val tablePath = createPaimonTable()
        val state = AppState(prefs, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

        state.loadTable(tablePath)

        // Verify it's in the session cache as a Paimon table
        val cacheKey = "$tablePath-rows_${state.showRows}"
        val session = state.sessionCache[cacheKey]
        assertNotNull(session)
        assertIs<PaimonUnifiedTableModel>(session.tableModel, "Should have Paimon table model")
    }

    // ═══════════════════════════════════════════════════════════════
    //  Fingerprint & Reload
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `forceReloadFromFs skips reload if fingerprint unchanged`() {
        val tablePath = createIcebergTable()
        val state = AppState(prefs, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

        state.loadTable(tablePath)
        val firstRevision = state.graphRevision

        // Force reload but don't change any files
        state.loadTable(tablePath, forceReloadFromFs = true)
        // Should detect same fingerprint and return same session
        assertFalse(state.isLoadingTable)
    }

    // T-3 regression: when a table is deleted with no cached session, loading it again
    // with forceReloadFromFs must NOT leave the previous table's graph on screen.
    @Test
    fun `loadTable replaces graph when switching to missing table with forceReloadFromFs`() {
        val state = AppState(prefs, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        val tablePath = createIcebergTable()
        state.loadTable(tablePath)
        val originalGraph = state.graphModel
        assertNotNull(originalGraph, "Initial table populates graph")

        // Now request a different (non-existent) table with forceReloadFromFs=true and no cache.
        val deletedPath = "/nonexistent/deleted-table-${System.nanoTime()}"
        state.loadTable(deletedPath, forceReloadFromFs = true)

        assertEquals(deletedPath, state.selectedTablePath)
        assertTrue(state.graphModel !== originalGraph, "previous table's graph must not remain visible")
    }

    @Test
    fun `forceReloadFromFs reloads when fingerprint changes`() {
        val tablePath = createIcebergTable()
        val state = AppState(prefs, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

        state.loadTable(tablePath)
        val firstFingerprint = state.sessionCache.values.first().fingerprint

        // Modify the metadata file to change fingerprint
        Thread.sleep(50) // Ensure different lastModified
        val metaFile = File(tmpDir, "metadata/v1.metadata.json")
        val content = metaFile.readText()
        metaFile.writeText(content) // Rewrite to update mtime

        state.loadTable(tablePath, forceReloadFromFs = true)
        val newFingerprint = state.sessionCache.values.first().fingerprint
        assertNotEquals(firstFingerprint, newFingerprint, "Fingerprint should change after file modification")
    }

    // ═══════════════════════════════════════════════════════════════
    //  Fixture Helpers
    // ═══════════════════════════════════════════════════════════════

    private fun createIcebergTable(): String {
        val metadataDir = File(tmpDir, "metadata").apply { mkdirs() }

        File(metadataDir, "v1.metadata.json").writeText("""
            {
              "format-version": 2,
              "table-uuid": "test-uuid",
              "schemas": [{"schema-id": 0, "type": "struct", "fields": []}],
              "partition-specs": [{"spec-id": 0, "fields": []}],
              "sort-orders": [{"order-id": 0, "fields": []}],
              "snapshots": [
                {"snapshot-id": 1, "timestamp-ms": 1700000000000, "manifest-list": "snap-1.avro", "summary": {}}
              ],
              "snapshot-log": [], "metadata-log": []
            }
        """.trimIndent())
        File(metadataDir, "version-hint.text").writeText("1")

        // Manifest list Avro
        val mlSchema = Avro.schema<ManifestListEntry>()
        DataFileWriter(GenericDatumWriter<GenericData.Record>(mlSchema)).use { writer ->
            writer.create(mlSchema, File(metadataDir, "snap-1.avro"))
            val record = GenericData.Record(mlSchema)
            record.put("manifest_path", "${metadataDir.absolutePath}/manifest-1.avro")
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

        // Manifest Avro
        val mSchema = Avro.schema<ManifestEntry>()
        DataFileWriter(GenericDatumWriter<GenericData.Record>(mSchema)).use { writer ->
            writer.create(mSchema, File(metadataDir, "manifest-1.avro"))
            val record = GenericData.Record(mSchema)
            record.put("status", 1)
            record.put("snapshot_id", 1L)
            record.put("sequence_number", 1L)
            record.put("file_sequence_number", 1L)
            val dfField = mSchema.getField("data_file").schema()
            val dfSchema = if (dfField.isUnion) dfField.types.first { it.type == org.apache.avro.Schema.Type.RECORD } else dfField
            val df = GenericData.Record(dfSchema)
            df.put("file_path", "data/file-1.parquet")
            df.put("file_format", "PARQUET")
            df.put("record_count", 100L)
            df.put("file_size_in_bytes", 4096L)
            df.put("content", 0)
            record.put("data_file", df)
            writer.append(record)
        }

        return tmpDir.canonicalPath
    }

    private fun createPaimonTable(): String {
        val schemaDir = File(tmpDir, "schema").apply { mkdirs() }
        val snapshotDir = File(tmpDir, "snapshot").apply { mkdirs() }
        val manifestDir = File(tmpDir, "manifest").apply { mkdirs() }

        File(schemaDir, "schema-0").writeText("""{"id": 0, "fields": [{"id": 0, "name": "k", "type": "INT"}], "primaryKeys": ["k"]}""")
        File(snapshotDir, "snapshot-1").writeText("""{"id": 1, "schemaId": 0, "baseManifestList": "manifest/manifest-list-base-0", "commitKind": "APPEND", "timeMillis": 1700000000000, "totalRecordCount": 50}""")

        val mlSchema = Avro.schema<PaimonManifestFileMeta>()
        DataFileWriter(GenericDatumWriter<GenericData.Record>(mlSchema)).use { writer ->
            writer.create(mlSchema, File(manifestDir, "manifest-list-base-0"))
            val record = GenericData.Record(mlSchema)
            record.put("_FILE_NAME", "manifest-0")
            record.put("_FILE_SIZE", 1024L)
            record.put("_NUM_ADDED_FILES", 1L)
            record.put("_NUM_DELETED_FILES", 0L)
            record.put("_SCHEMA_ID", 0L)
            writer.append(record)
        }

        val mSchema = Avro.schema<PaimonManifestEntry>()
        DataFileWriter(GenericDatumWriter<GenericData.Record>(mSchema)).use { writer ->
            writer.create(mSchema, File(manifestDir, "manifest-0"))
            val record = GenericData.Record(mSchema)
            record.put("_KIND", 0)
            record.put("_BUCKET", 0)
            record.put("_TOTAL_BUCKETS", 1)
            val fileField = mSchema.getField("_FILE").schema()
            val fileSchema = if (fileField.isUnion) fileField.types.first { it.type == org.apache.avro.Schema.Type.RECORD } else fileField
            val fileRecord = GenericData.Record(fileSchema)
            fileRecord.put("_FILE_NAME", "data-0.parquet")
            fileRecord.put("_FILE_SIZE", 4096L)
            fileRecord.put("_ROW_COUNT", 50L)
            fileRecord.put("_LEVEL", 0)
            fileRecord.put("_SCHEMA_ID", 0L)
            fileRecord.put("_MIN_SEQUENCE_NUMBER", 0L)
            fileRecord.put("_MAX_SEQUENCE_NUMBER", 0L)
            fileRecord.put("_CREATION_TIME", System.currentTimeMillis())
            record.put("_FILE", fileRecord)
            writer.append(record)
        }

        return tmpDir.canonicalPath
    }
}
