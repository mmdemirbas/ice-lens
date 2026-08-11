@file:OptIn(com.github.avrokotlin.avro4k.ExperimentalAvro4kApi::class)

package service

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.schema
import model.ManifestEntry
import model.ManifestListEntry
import model.UnifiedTableModel
import org.apache.avro.file.DataFileWriter
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericDatumWriter
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Accuracy tests for [IcebergGraphBuilder.buildTableSummary].
 *
 * The summary numbers are the headline figures a user reads first, so they must describe
 * the table — not the shape of the traversal that produced them. Iceberg deliberately
 * shares structures: one manifest is referenced by every snapshot that carries its files
 * forward, and one snapshot is listed in every metadata.json written after it. A summary
 * that counts per-visit therefore multiplies with commit history rather than with data.
 */
class TableSummaryAccuracyTest {

    private lateinit var tmpDir: File

    @BeforeTest
    fun setUp() {
        tmpDir = kotlin.io.path.createTempDirectory("summary-accuracy-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    /**
     * Two commits laid out the way Iceberg actually writes them:
     *
     * - `v1.metadata.json` lists snapshot 1.
     * - `v2.metadata.json` lists snapshots 1 **and** 2 (metadata.json is cumulative).
     * - Snapshot 1's manifest list references `manifest-1.avro` (file-1, 100 rows).
     * - Snapshot 2's manifest list references `manifest-1.avro` **again** (files carried
     *   forward, status=EXISTING) plus `manifest-2.avro` (file-2, 50 rows).
     *
     * The table holds two data files and 150 rows. Nothing about the commit history
     * changes that.
     */
    @Test
    fun `summary counts files and records once, not once per snapshot that carries them`() {
        val metadataDir = File(tmpDir, "metadata").apply { mkdirs() }

        writeManifestAvro(
            File(metadataDir, "manifest-1.avro"),
            listOf(EntryData(status = 1, snapshotId = 1, filePath = "data/file-1.parquet", recordCount = 100)),
        )
        writeManifestAvro(
            File(metadataDir, "manifest-2.avro"),
            listOf(EntryData(status = 1, snapshotId = 2, filePath = "data/file-2.parquet", recordCount = 50)),
        )

        writeManifestListAvro(
            File(metadataDir, "snap-1.avro"),
            listOf(ManifestListData("$metadataDir/manifest-1.avro", addedSnapshotId = 1, sequenceNumber = 1)),
        )
        writeManifestListAvro(
            File(metadataDir, "snap-2.avro"),
            listOf(
                // Carried forward from snapshot 1 — the same physical manifest file.
                ManifestListData("$metadataDir/manifest-1.avro", addedSnapshotId = 1, sequenceNumber = 1),
                ManifestListData("$metadataDir/manifest-2.avro", addedSnapshotId = 2, sequenceNumber = 2),
            ),
        )

        File(metadataDir, "v1.metadata.json").writeText(
            metadataJson(currentSnapshotId = 1, snapshots = listOf(snapshotJson(1, "snap-1.avro", 1700000000000L))),
        )
        File(metadataDir, "v2.metadata.json").writeText(
            metadataJson(
                currentSnapshotId = 2,
                snapshots = listOf(
                    snapshotJson(1, "snap-1.avro", 1700000000000L),
                    snapshotJson(2, "snap-2.avro", 1700000001000L),
                ),
            ),
        )

        val summary = IcebergGraphBuilder.buildTableSummary(UnifiedTableModel(tmpDir.toPath()))

        assertEquals(2, summary.snapshotCount, "two snapshots exist")

        // The table right now, reached through snapshot 2's manifest list.
        assertEquals(2, summary.current.manifestCount, "snapshot 2 references two manifests")
        assertEquals(2, summary.current.manifestEntryCount, "two manifest entries, not four visits")
        assertEquals(2, summary.current.dataFileCount, "two data files exist")
        assertEquals(150L, summary.current.recordCount, "the table holds 150 rows")
        assertEquals(8192L, summary.current.dataSizeBytes, "two 4 KiB files")

        // The same two manifests and files, counted once across all retained snapshots.
        assertEquals(2, summary.history.manifestCount, "two distinct manifest files exist")
        assertEquals(2, summary.history.manifestEntryCount, "two distinct manifest entries exist")
        assertEquals(2, summary.history.dataFileCount, "two distinct data files exist")
        assertEquals(150L, summary.history.recordCount)
    }

    /**
     * A DELETED manifest entry records a file's removal; the file is not part of the table
     * any more. Counting it as a live data file overstates both the file count and the row
     * count, and it is exactly the case a user opens this tool to understand.
     */
    @Test
    fun `summary excludes DELETED manifest entries from live file and record counts`() {
        val metadataDir = File(tmpDir, "metadata").apply { mkdirs() }

        writeManifestAvro(
            File(metadataDir, "manifest-1.avro"),
            listOf(
                EntryData(status = 1, snapshotId = 2, filePath = "data/kept.parquet", recordCount = 80),
                EntryData(status = 2, snapshotId = 2, filePath = "data/removed.parquet", recordCount = 40),
            ),
        )
        writeManifestListAvro(
            File(metadataDir, "snap-1.avro"),
            listOf(ManifestListData("$metadataDir/manifest-1.avro", addedSnapshotId = 2, sequenceNumber = 1)),
        )
        File(metadataDir, "v1.metadata.json").writeText(
            metadataJson(currentSnapshotId = 2, snapshots = listOf(snapshotJson(2, "snap-1.avro", 1700000000000L))),
        )

        val summary = IcebergGraphBuilder.buildTableSummary(UnifiedTableModel(tmpDir.toPath()))

        assertEquals(1, summary.current.dataFileCount, "only the kept file is live")
        assertEquals(80L, summary.current.recordCount, "only the kept file's rows count")
        assertEquals(2, summary.current.manifestEntryCount, "both entries are still entries in the manifest")
        assertEquals(1, summary.current.deletedEntryCount, "one entry records a removal")

        // The removed file still occupies storage until the snapshot referencing it expires,
        // so the history view keeps counting it.
        assertEquals(2, summary.history.dataFileCount, "both files are still on disk")
    }

    /**
     * Delete-file record counts are counts of *delete records*, not table rows. Adding them
     * to the row total inflates it by the size of the delete backlog.
     */
    @Test
    fun `summary keeps delete-file records out of the table record count`() {
        val metadataDir = File(tmpDir, "metadata").apply { mkdirs() }

        writeManifestAvro(
            File(metadataDir, "data-manifest.avro"),
            listOf(EntryData(status = 1, snapshotId = 1, filePath = "data/file-1.parquet", recordCount = 100)),
        )
        writeManifestAvro(
            File(metadataDir, "delete-manifest.avro"),
            listOf(EntryData(status = 1, snapshotId = 1, filePath = "data/pos-del.parquet", recordCount = 7, content = 1)),
        )
        writeManifestListAvro(
            File(metadataDir, "snap-1.avro"),
            listOf(
                ManifestListData("$metadataDir/data-manifest.avro", addedSnapshotId = 1, sequenceNumber = 1),
                ManifestListData("$metadataDir/delete-manifest.avro", addedSnapshotId = 1, sequenceNumber = 1, content = 1),
            ),
        )
        File(metadataDir, "v1.metadata.json").writeText(
            metadataJson(currentSnapshotId = 1, snapshots = listOf(snapshotJson(1, "snap-1.avro", 1700000000000L))),
        )

        val summary = IcebergGraphBuilder.buildTableSummary(UnifiedTableModel(tmpDir.toPath()))

        assertEquals(1, summary.current.dataFileCount)
        assertEquals(1, summary.current.posDeleteFileCount)
        assertEquals(100L, summary.current.recordCount, "the 7 delete records are not table rows")
        assertEquals(7L, summary.current.deleteRecordCount, "they are reported on their own")
        assertEquals(1, summary.current.dataManifestCount)
        assertEquals(1, summary.current.deleteManifestCount)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Fixture writers
    // ═══════════════════════════════════════════════════════════════

    private data class ManifestListData(
        val manifestPath: String,
        val addedSnapshotId: Long,
        val sequenceNumber: Long,
        val content: Int = 0,
    )

    private data class EntryData(
        val status: Int,
        val snapshotId: Long,
        val filePath: String,
        val recordCount: Long,
        val content: Int = 0,
    )

    private fun metadataJson(currentSnapshotId: Long, snapshots: List<String>): String = """
        {
          "format-version": 2,
          "table-uuid": "6f4d8b1a-2c3e-4f5a-9b8c-7d6e5f4a3b2c",
          "last-updated-ms": 1700000001000,
          "schemas": [{"schema-id": 0, "type": "struct", "fields": [{"id": 1, "name": "id", "required": true, "type": "long"}]}],
          "current-schema-id": 0,
          "partition-specs": [{"spec-id": 0, "fields": []}],
          "default-spec-id": 0,
          "sort-orders": [{"order-id": 0, "fields": []}],
          "default-sort-order-id": 0,
          "current-snapshot-id": $currentSnapshotId,
          "snapshots": [${snapshots.joinToString(",")}],
          "snapshot-log": [],
          "metadata-log": []
        }
    """.trimIndent()

    private fun snapshotJson(snapshotId: Long, manifestList: String, timestampMs: Long): String = """
        {
          "snapshot-id": $snapshotId,
          "sequence-number": $snapshotId,
          "timestamp-ms": $timestampMs,
          "manifest-list": "$manifestList",
          "summary": {"operation": "append"},
          "schema-id": 0
        }
    """.trimIndent()

    private fun writeManifestListAvro(file: File, entries: List<ManifestListData>) {
        val schema = Avro.schema<ManifestListEntry>()
        DataFileWriter(GenericDatumWriter<GenericData.Record>(schema)).use { writer ->
            writer.create(schema, file)
            entries.forEach { entry ->
                val record = GenericData.Record(schema)
                record.put("manifest_path", entry.manifestPath)
                record.put("manifest_length", 1024L)
                record.put("partition_spec_id", 0)
                record.put("content", entry.content)
                record.put("sequence_number", entry.sequenceNumber)
                record.put("min_sequence_number", entry.sequenceNumber)
                record.put("added_snapshot_id", entry.addedSnapshotId)
                record.put("added_files_count", 1)
                record.put("existing_files_count", 0)
                record.put("deleted_files_count", 0)
                record.put("added_rows_count", 0L)
                record.put("existing_rows_count", 0L)
                record.put("deleted_rows_count", 0L)
                writer.append(record)
            }
        }
    }

    private fun writeManifestAvro(file: File, entries: List<EntryData>) {
        val schema = Avro.schema<ManifestEntry>()
        DataFileWriter(GenericDatumWriter<GenericData.Record>(schema)).use { writer ->
            writer.create(schema, file)
            entries.forEach { entry ->
                val record = GenericData.Record(schema)
                record.put("status", entry.status)
                record.put("snapshot_id", entry.snapshotId)
                record.put("sequence_number", 1L)
                record.put("file_sequence_number", 1L)

                val dataFileField = schema.getField("data_file").schema()
                val dataFileSchema = if (dataFileField.isUnion) {
                    dataFileField.types.first { it.type == org.apache.avro.Schema.Type.RECORD }
                } else dataFileField
                val dataFileRecord = GenericData.Record(dataFileSchema)
                dataFileRecord.put("file_path", entry.filePath)
                dataFileRecord.put("file_format", "PARQUET")
                dataFileRecord.put("record_count", entry.recordCount)
                dataFileRecord.put("file_size_in_bytes", 4096L)
                dataFileRecord.put("content", entry.content)
                record.put("data_file", dataFileRecord)

                writer.append(record)
            }
        }
    }
}
