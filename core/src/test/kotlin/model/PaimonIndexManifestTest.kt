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
 * Two fixtures, one per index kind. The Flink-written `test` table records one `HASH` index for
 * bucket 0 covering two rows, which is the row count its snapshot also reports, and the file it
 * names is eight bytes on disk — which is what the manifest says its size is. That pair is
 * `manifestTallies` in miniature: a figure a reader trusts without opening the file, put beside
 * the file. The Spark-written `dv` table carries a `DELETION_VECTORS` index whose cardinalities
 * were predicted in the script that wrote it.
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

    // ---- example/paimon/db.db/dv: the deletion-vector branch, against bytes Spark wrote ----

    private val dvDir = File(repoRoot, "example/paimon/db.db/dv")
    private val dv = PaimonUnifiedTableModel(Paths.get(dvDir.absolutePath))

    /**
     * The `DELETION_VECTORS` branch against a table that has one, which until this fixture existed
     * was modelled from an Avro schema and had never met real bytes.
     *
     * The expected figures were written into `docs/fixtures/paimon-dv.sql` before it was run: a
     * thousand-row file loses one row (k = 2) and a five-hundred-row file loses two (k = 1001 and
     * 1500). The data file each range names is matched by its row count, so what is asserted is the
     * relationship the DELETE statement created rather than a file name that changes on every
     * regeneration.
     */
    @Test
    fun `a deletion-vector index names each data file and how many rows it marks`() {
        val snapshot = dv.snapshots.last()
        val index = snapshot.indexFiles.single()
        assertEquals("DELETION_VECTORS", index.indexType)
        assertTrue(index.isDeletionVectorIndex)
        assertEquals(0, index.bucket)

        val ranges = index.deletionVectorRanges.orEmpty().filterNotNull()
        assertEquals(2, ranges.size, "one vector per data file the DELETE touched")

        val rowsByFile = (snapshot.baseManifests + snapshot.deltaManifests)
            .flatMap { it.entries }
            .filter { it.metadata.kind == PaimonEntryKind.ADD }
            .associate { it.metadata.file?.fileName to it.metadata.file?.rowCount }
        val deletedByRows: Map<Long?, Long?> = ranges.associate { rowsByFile[it.dataFileName] to it.cardinality }
        val expected: Map<Long?, Long?> = mapOf(1000L to 1L, 500L to 2L)
        assertEquals(expected, deletedByRows)
        assertEquals(3L, ranges.sumOf { it.cardinality ?: 0L }, "three keys were deleted")
    }

    /**
     * The ranges are byte slices of one file, and the file is what the manifest says it is.
     *
     * Same check as the HASH case — recorded size against the bytes on disk — plus the structural
     * half a container has to satisfy: every slice inside the file, and no two overlapping.
     */
    @Test
    fun `the vector ranges fit inside the index file the manifest describes`() {
        val index = dv.snapshots.last().indexFiles.single()
        val onDisk = File(dvDir, "index/${index.fileName}")
        assertTrue(onDisk.isFile, "the fixture should carry the index file: $onDisk")
        assertEquals(onDisk.length(), index.fileSize)

        val ranges = index.deletionVectorRanges.orEmpty().filterNotNull()
            .sortedBy { it.offset ?: 0 }
        ranges.forEach { range ->
            val end = (range.offset ?: 0) + (range.length ?: 0)
            assertTrue(end <= index.fileSize!!, "range $range runs past the ${index.fileSize}-byte file")
        }
        ranges.zipWithNext().forEach { (a, b) ->
            assertTrue((a.offset ?: 0) + (a.length ?: 0) <= (b.offset ?: 0), "ranges overlap: $a, $b")
        }
    }

    /**
     * A vector marks rows without rewriting the file, and the manifests show exactly that.
     *
     * The commit that wrote the vector kept both large data files in place — the same names, the
     * same row counts — and removed only the level-0 file carrying the three delete rows. That is
     * the whole difference between a deletion vector and a compaction, and it is why the
     * snapshot's `totalRecordCount` stays at 1500 while three of those rows are gone.
     */
    @Test
    fun `the vector's commit removes the delete rows and leaves the marked files untouched`() {
        val snapshot = dv.snapshots.last()
        assertEquals("COMPACT", snapshot.metadata.commitKind)
        assertEquals(1500L, snapshot.metadata.totalRecordCount)
        assertEquals(-3L, snapshot.metadata.deltaRecordCount, "the three -D rows compacted away")

        val removed = snapshot.deltaManifests.flatMap { it.entries }
            .filter { it.metadata.kind == PaimonEntryKind.DELETE }
        assertEquals(listOf(3L), removed.map { it.metadata.file?.rowCount }, "only the level-0 delete file goes")

        val marked = snapshot.indexFiles.single().deletionVectorRanges.orEmpty().filterNotNull()
            .map { it.dataFileName }.toSet()
        val stillListed = (snapshot.baseManifests + snapshot.deltaManifests).flatMap { it.entries }
            .filter { it.metadata.kind == PaimonEntryKind.ADD }
            .map { it.metadata.file?.fileName }.toSet()
        assertTrue(stillListed.containsAll(marked), "every file a vector marks is still a live file")
    }

    /** Five of the six snapshots predate the vector and name no index manifest at all. */
    @Test
    fun `snapshots before the vector name no index manifest`() {
        assertEquals(6, dv.snapshots.size)
        assertEquals(
            listOf(false, false, false, false, false, true),
            dv.snapshots.map { it.indexFiles.isNotEmpty() },
        )
        assertTrue(dv.snapshots.flatMap { it.readErrors }.none { it.stage.contains("index") })
    }
}
