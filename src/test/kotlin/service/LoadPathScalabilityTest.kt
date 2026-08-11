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
import kotlin.system.measureTimeMillis
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Measures the *real* load path — `UnifiedTableModel(path)` against files on disk.
 *
 * [PerformanceTest] builds its fixtures by calling the data-class constructors directly, so it
 * benchmarks graph building and layout while never opening a file. The cost that decides
 * whether a table opens in a second or a minute is the one it skips: reading every manifest
 * Avro file before anything is drawn.
 *
 * The shape under test is the one Iceberg actually produces. A commit rewrites the manifest
 * list but carries most manifests forward unchanged, so after N commits the same manifest file
 * is referenced by N snapshots. Whether the loader reads it once or N times is the difference
 * between scaling with the table and scaling with its history.
 */
class LoadPathScalabilityTest {

    private lateinit var tmpDir: File

    @BeforeTest
    fun setUp() {
        tmpDir = kotlin.io.path.createTempDirectory("load-scalability").toFile()
    }

    @AfterTest
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    /**
     * Ten manifests, each carried forward by every snapshot — ten physical manifest files no
     * matter how many commits happened. Load time is measured at 4 snapshots and at 32.
     *
     * If the loader deduplicates, the two are within noise of each other: the same ten files
     * are read either way. If it reads per (snapshot, manifest) pair, the 32-snapshot table
     * costs roughly 8x the 4-snapshot one while containing exactly the same data.
     */
    @Test
    fun `load time tracks distinct manifests, not the number of snapshots carrying them`() {
        val small = buildTable(File(tmpDir, "small"), snapshotCount = 4, manifestCount = 10, entriesPerManifest = 200)
        val large = buildTable(File(tmpDir, "large"), snapshotCount = 32, manifestCount = 10, entriesPerManifest = 200)

        // Warm the JIT and the page cache so the comparison is about read count, not first touch.
        repeat(2) { UnifiedTableModel(small.toPath()); UnifiedTableModel(large.toPath()) }

        val smallMs = medianOf(3) { measureTimeMillis { UnifiedTableModel(small.toPath()) } }
        val largeMs = medianOf(3) { measureTimeMillis { UnifiedTableModel(large.toPath()) } }

        val ratio = largeMs.toDouble() / smallMs.coerceAtLeast(1).toDouble()
        println(
            "load-path scalability: 4 snapshots = ${smallMs}ms, 32 snapshots = ${largeMs}ms, " +
                "ratio = ${"%.1f".format(ratio)}x for identical table contents (10 manifests, 2000 entries)"
        )

        // 8x the snapshots over the same ten manifests. A deduplicating loader stays near 1x;
        // the bound is generous so this fails on the scaling law, not on machine noise.
        assertTrue(
            ratio < 3.0,
            "Load time grew ${"%.1f".format(ratio)}x when snapshot count grew 8x over identical " +
                "manifests — the loader is re-reading shared manifests once per referencing snapshot.",
        )
    }

    /**
     * Paimon carries manifests forward through a snapshot's *base* manifest list, so it has the
     * same shape and the same exposure. Asserted on read count rather than wall time: the
     * Paimon fixture writer is slower per manifest, so a timing ratio here would be noisier
     * than the effect it measures.
     */
    @Test
    fun `paimon reads each manifest once regardless of how many snapshots carry it`() {
        val root = File(tmpDir, "paimon").apply { mkdirs() }
        val manifestDir = File(root, "manifest").apply { mkdirs() }
        File(root, "schema").apply { mkdirs() }
            .also { File(it, "schema-0").writeText("""{"id":0,"fields":[{"id":0,"name":"k","type":"INT"}],"partitionKeys":[],"primaryKeys":[],"options":{}}""") }
        val snapshotDir = File(root, "snapshot").apply { mkdirs() }

        // One manifest file, referenced by every snapshot's base manifest list.
        writePaimonManifest(File(manifestDir, "manifest-shared"), entryCount = 50)
        writePaimonManifestList(File(manifestDir, "manifest-list-base"), listOf("manifest-shared"))

        val snapshotCount = 24
        repeat(snapshotCount) { i ->
            File(snapshotDir, "snapshot-${i + 1}").writeText(
                """{"id":${i + 1},"schemaId":0,"baseManifestList":"manifest-list-base","commitKind":"APPEND","timeMillis":${1700000000000L + i * 1000L}}"""
            )
        }

        val model = model.PaimonUnifiedTableModel(root.toPath())
        assertTrue(model.snapshots.size == snapshotCount, "expected $snapshotCount snapshots, got ${model.snapshots.size}")

        // Every snapshot resolves to the SAME PaimonUnifiedManifest instance when the cache
        // works. Identity is the direct observation of "read once"; without the cache each
        // snapshot holds a separately-parsed object.
        val distinctInstances = model.snapshots
            .flatMap { it.baseManifests }
            .distinctBy { System.identityHashCode(it) }
            .size
        println("paimon manifest reuse: $snapshotCount snapshots -> $distinctInstances parsed manifest instance(s)")
        assertTrue(
            distinctInstances == 1,
            "$snapshotCount snapshots referencing one manifest produced $distinctInstances parsed " +
                "instances — the manifest is being re-read once per referencing snapshot.",
        )
    }

    private fun writePaimonManifestList(file: File, manifestNames: List<String>) {
        val schema = Avro.schema<model.PaimonManifestFileMeta>()
        DataFileWriter(GenericDatumWriter<GenericData.Record>(schema)).use { writer ->
            writer.create(schema, file)
            manifestNames.forEach { name ->
                val r = GenericData.Record(schema)
                r.put("_FILE_NAME", name)
                r.put("_FILE_SIZE", 2048L)
                r.put("_NUM_ADDED_FILES", 50L)
                r.put("_NUM_DELETED_FILES", 0L)
                r.put("_SCHEMA_ID", 0L)
                writer.append(r)
            }
        }
    }

    private fun writePaimonManifest(file: File, entryCount: Int) {
        val schema = Avro.schema<model.PaimonManifestEntry>()
        val fileField = schema.getField("_FILE").schema()
        val fileSchema = if (fileField.isUnion) {
            fileField.types.first { it.type == org.apache.avro.Schema.Type.RECORD }
        } else fileField
        DataFileWriter(GenericDatumWriter<GenericData.Record>(schema)).use { writer ->
            writer.create(schema, file)
            repeat(entryCount) { i ->
                val r = GenericData.Record(schema)
                r.put("_KIND", 0)
                r.put("_BUCKET", 0)
                r.put("_TOTAL_BUCKETS", 1)
                val f = GenericData.Record(fileSchema)
                f.put("_FILE_NAME", "data-$i.orc")
                f.put("_FILE_SIZE", 65536L)
                f.put("_ROW_COUNT", 1000L)
                f.put("_LEVEL", 0)
                f.put("_SCHEMA_ID", 0L)
                f.put("_MIN_SEQUENCE_NUMBER", 1L)
                f.put("_MAX_SEQUENCE_NUMBER", 2L)
                f.put("_CREATION_TIME", 1700000000000L)
                r.put("_FILE", f)
                writer.append(r)
            }
        }
    }

    private fun medianOf(runs: Int, block: () -> Long): Long =
        (1..runs).map { block() }.sorted()[runs / 2]

    /**
     * Writes a table where every snapshot's manifest list references the same [manifestCount]
     * manifest files — the carry-forward shape a real commit produces.
     */
    private fun buildTable(root: File, snapshotCount: Int, manifestCount: Int, entriesPerManifest: Int): File {
        val metadataDir = File(root, "metadata").apply { mkdirs() }

        val manifestPaths = (1..manifestCount).map { m ->
            val f = File(metadataDir, "manifest-$m.avro")
            writeManifest(f, entriesPerManifest, manifestIndex = m)
            f.absolutePath
        }

        val snapshotJson = (1..snapshotCount).map { s ->
            writeManifestList(File(metadataDir, "snap-$s.avro"), manifestPaths, sequenceNumber = s.toLong())
            """
            {"snapshot-id": $s, "sequence-number": $s, "timestamp-ms": ${1700000000000L + s * 1000L},
             "manifest-list": "snap-$s.avro", "summary": {"operation": "append"}, "schema-id": 0}
            """.trimIndent()
        }

        File(metadataDir, "v1.metadata.json").writeText(
            """
            {
              "format-version": 2,
              "table-uuid": "8c1d2f3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
              "last-updated-ms": 1700000000000,
              "schemas": [{"schema-id": 0, "type": "struct", "fields": [{"id": 1, "name": "id", "required": true, "type": "long"}]}],
              "current-schema-id": 0,
              "partition-specs": [{"spec-id": 0, "fields": []}],
              "default-spec-id": 0,
              "sort-orders": [{"order-id": 0, "fields": []}],
              "default-sort-order-id": 0,
              "current-snapshot-id": $snapshotCount,
              "snapshots": [${snapshotJson.joinToString(",")}],
              "snapshot-log": [],
              "metadata-log": []
            }
            """.trimIndent()
        )
        return root
    }

    private fun writeManifestList(file: File, manifestPaths: List<String>, sequenceNumber: Long) {
        val schema = Avro.schema<ManifestListEntry>()
        DataFileWriter(GenericDatumWriter<GenericData.Record>(schema)).use { writer ->
            writer.create(schema, file)
            manifestPaths.forEach { path ->
                val r = GenericData.Record(schema)
                r.put("manifest_path", path)
                r.put("manifest_length", 4096L)
                r.put("partition_spec_id", 0)
                r.put("content", 0)
                r.put("sequence_number", sequenceNumber)
                r.put("min_sequence_number", 1L)
                r.put("added_snapshot_id", sequenceNumber)
                r.put("added_files_count", 0)
                r.put("existing_files_count", 0)
                r.put("deleted_files_count", 0)
                r.put("added_rows_count", 0L)
                r.put("existing_rows_count", 0L)
                r.put("deleted_rows_count", 0L)
                writer.append(r)
            }
        }
    }

    private fun writeManifest(file: File, entryCount: Int, manifestIndex: Int) {
        val schema = Avro.schema<ManifestEntry>()
        val dataFileField = schema.getField("data_file").schema()
        val dataFileSchema = if (dataFileField.isUnion) {
            dataFileField.types.first { it.type == org.apache.avro.Schema.Type.RECORD }
        } else dataFileField

        DataFileWriter(GenericDatumWriter<GenericData.Record>(schema)).use { writer ->
            writer.create(schema, file)
            repeat(entryCount) { i ->
                val r = GenericData.Record(schema)
                r.put("status", 1)
                r.put("snapshot_id", 1L)
                r.put("sequence_number", 1L)
                r.put("file_sequence_number", 1L)
                val df = GenericData.Record(dataFileSchema)
                df.put("file_path", "data/part-$manifestIndex-$i.parquet")
                df.put("file_format", "PARQUET")
                df.put("record_count", 1000L)
                df.put("file_size_in_bytes", 1048576L)
                df.put("content", 0)
                r.put("data_file", df)
                writer.append(r)
            }
        }
    }
}
