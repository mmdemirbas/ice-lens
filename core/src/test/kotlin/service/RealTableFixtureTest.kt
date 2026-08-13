package service

import model.UnifiedTableModel
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reads the checked-in `example/` tables — written by real Spark/Iceberg and Flink/Paimon, not
 * by this codebase — and asserts they decode without error.
 *
 * This is the suite's only oracle. Every other Avro fixture here is written with
 * `Avro.schema<T>()`, the schema derived from our own Kotlin class, so the writer schema and
 * the reader schema are the same object: those tests can prove our reader agrees with our
 * writer, and nothing more. A field whose Kotlin type disagrees with the Iceberg spec — an
 * `Int` where the spec says `long`, or the reverse — would decode our fixtures perfectly and
 * fail on every real table, with a green suite the whole way.
 *
 * Note what this does NOT establish. The tables *this class* reads are minimal: one snapshot,
 * one manifest, one data file, no partitioning, no delete files, no schema evolution, format
 * version 2. Passing here means our schema is compatible with *that* shape.
 *
 * The partitioned case is covered separately by [PartitionDecodingTest], against
 * `example/iceberg/default/parted`. Still missing: a merge-on-read table with positional and
 * equality deletes, several commits including a compaction, and a v3 table. Tracked in TODO.md.
 */
class RealTableFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun `the example Iceberg table decodes with no read errors`() {
        val tableDir = File(repoRoot, "example/iceberg/default/test")
        assertTrue(tableDir.isDirectory, "example Iceberg table missing at $tableDir")

        val model = UnifiedTableModel(Paths.get(tableDir.absolutePath))

        assertEquals(
            emptyList(), model.readErrors,
            "table-level read errors decoding a real Spark-written Iceberg table",
        )

        val snapshots = model.metadatas.flatMap { it.snapshots }
        assertTrue(snapshots.isNotEmpty(), "fixture should contain at least one snapshot")
        snapshots.forEach { snapshot ->
            assertEquals(
                emptyList(), snapshot.readErrors,
                "manifest list ${snapshot.path.fileName} failed to decode",
            )
        }

        val manifests = snapshots.flatMap { it.manifests }
        assertTrue(manifests.isNotEmpty(), "fixture should contain at least one manifest")
        manifests.forEach { manifest ->
            assertEquals(
                emptyList(), manifest.readErrors,
                "manifest ${manifest.path.fileName} failed to decode",
            )
        }

        // A manifest that decodes to zero entries is the failure mode a per-record catch hides:
        // every record errors, the errors are collected, and the graph renders an empty manifest.
        val entries = manifests.flatMap { it.dataFiles }
        assertTrue(entries.isNotEmpty(), "real manifests decoded to zero entries")
        entries.forEach { entry ->
            assertTrue(
                entry.metadata.dataFile?.filePath?.isNotBlank() == true,
                "entry decoded without a file path: ${entry.metadata}",
            )
        }
    }

    @Test
    fun `the example Paimon table decodes with no read errors`() {
        val tableDir = File(repoRoot, "example/paimon/db.db/test")
        assertTrue(tableDir.isDirectory, "example Paimon table missing at $tableDir")

        val model = model.PaimonUnifiedTableModel(Paths.get(tableDir.absolutePath))

        assertEquals(
            emptyList(), model.readErrors,
            "table-level read errors decoding a real Paimon table",
        )

        val manifests = model.snapshots.flatMap { it.baseManifests + it.deltaManifests + it.changelogManifests }
        assertTrue(manifests.isNotEmpty(), "fixture should contain at least one manifest")
        manifests.forEach { manifest ->
            assertEquals(
                emptyList(), manifest.readErrors,
                "manifest ${manifest.path.fileName} failed to decode",
            )
        }

        val entries = manifests.flatMap { it.entries }
        assertTrue(entries.isNotEmpty(), "real Paimon manifests decoded to zero entries")
    }
}
