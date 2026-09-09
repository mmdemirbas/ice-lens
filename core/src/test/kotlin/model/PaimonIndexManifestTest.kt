package model

import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The index manifest a Paimon snapshot names, which was parsed and dropped until now.
 *
 * `PaimonSnapshot.indexManifest` had a field and no reader — the same shape of gap Iceberg's
 * `statistics` had, and invisible for the same reason: nothing rendered it, so nothing noticed.
 * It is where Paimon records the per-bucket key index, and on a table with deletion vectors
 * enabled it is the **only** place that says which data files have deleted rows.
 *
 * The oracle is the fixture's own bytes. The Flink-written table records one `HASH` index for
 * bucket 0 covering two rows, which is the row count its snapshot also reports, and the file it
 * names is eight bytes on disk — which is what the manifest says its size is. That last pair is
 * `manifestTallies` in miniature: a figure a reader trusts without opening the file, put beside
 * the file.
 */
class PaimonIndexManifestTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val tableDir = File(repoRoot, "example/paimon/db.db/test")

    private val model = PaimonUnifiedTableModel(Paths.get(tableDir.absolutePath))

    @Test
    fun `the snapshot's index manifest is read, not dropped`() {
        val snapshot = model.snapshots.last()
        assertEquals(1, snapshot.indexFiles.size, "one index file for the single bucket")

        val index = snapshot.indexFiles.single()
        assertEquals("HASH", index.indexType)
        assertEquals(0, index.bucket)
        assertEquals(2L, index.rowCount, "the same two rows the snapshot reports")
        assertTrue(index.fileName.orEmpty().startsWith("index-"), "got ${index.fileName}")
        assertEquals(null, index.deletionVectorRanges, "a key index marks no deleted rows")
        assertTrue(!index.isDeletionVectorIndex)
    }

    /**
     * The recorded size against the file itself, which nothing on the read path checks.
     *
     * Paimon writes the index file's length into the manifest so a reader can plan against it
     * without a stat; the two can only be compared by doing what a reader never does.
     */
    @Test
    fun `the recorded index file size is the size of the file on disk`() {
        val index = model.snapshots.last().indexFiles.single()
        val onDisk = File(tableDir, "index/${index.fileName}")
        assertTrue(onDisk.isFile, "the fixture should carry the index file itself: $onDisk")
        assertEquals(onDisk.length(), index.fileSize)
    }

    /** Every snapshot of this table names an index manifest, and every one of them reads. */
    @Test
    fun `no snapshot reports an error reading its index manifest`() {
        assertTrue(model.snapshots.isNotEmpty())
        val failures = model.snapshots.flatMap { it.readErrors }
            .filter { it.stage.contains("index-manifest") }
        assertTrue(failures.isEmpty(), "index manifest read errors: $failures")
    }

    /** Paimon's own row-lineage counter, which the snapshot records from version 3. */
    @Test
    fun `the snapshot's next row id is read`() {
        assertEquals(0L, model.snapshots.first().metadata.nextRowId)
    }
}
