package model

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `example/paimon/db.db/fi`: a bloom-filter file index written both ways, from
 * `docs/fixtures/paimon-fi.sql`.
 *
 * Where a file index lives is decided by its size against `file-index.in-manifest-threshold`
 * (500 bytes): the first commit's filter over a million items is a 599 KB `.index` file beside
 * the data file, named in the entry's `_EXTRA_FILES`; after `ALTER TABLE` set the items to a
 * hundred, the second commit's filter travels in the entry as `_EMBEDDED_FILE_INDEX`. The
 * expected values are read off the script and the directory listing it produced.
 */
class PaimonFileIndexFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val tableDir = File(repoRoot, "example/paimon/db.db/fi")
    private val model = PaimonUnifiedTableModel(Paths.get(tableDir.absolutePath))

    private fun entryAt(snapshotId: Long) = model.snapshots.first { it.metadata.id == snapshotId }.deltaManifests.single().entries.single()

    @Test
    fun `a large index is a file beside the data file, named in the entry`() {
        val first = entryAt(1L).metadata.file!!
        assertEquals(listOf("${first.fileName}.index"), first.extraFiles)
        assertNull(first.embeddedFileIndex)
        val indexPath = entryAt(1L).path.resolveSibling(first.extraFiles!!.single())
        assertTrue(Files.isRegularFile(indexPath), "on disk beside the data file: $indexPath")
        assertTrue(Files.size(indexPath) > 500_000L, "a million-item bloom filter is hundreds of KB: ${Files.size(indexPath)}")
        assertEquals(0L, first.schemaId, "written under the schema before the ALTER")
    }

    @Test
    fun `a small index is embedded in the entry, and the entry names no extra file`() {
        val second = entryAt(2L).metadata.file!!
        assertEquals(emptyList(), second.extraFiles)
        val embedded = second.embeddedFileIndex
        assertTrue(embedded != null && embedded.size in 1..500, "under the in-manifest threshold: ${embedded?.size}")
        assertEquals(1L, second.schemaId, "written under the schema the ALTER produced")
        assertEquals(listOf(0, 1), model.schemas.map { it.id })
    }

    /** The index file is the table's — an orphan scan that reads data files alone deletes a scan's index. */
    @Test
    fun `the index file beside the data file is referenced, not an orphan`() {
        val report = findUnreferencedFiles(model)
        assertTrue(report.problems.isEmpty(), "${report.problems}")
        assertEquals(emptyList(), report.unreferenced.map { report.relativePathOf(it) })
        assertEquals(3, Files.list(tableDir.toPath().resolve("bucket-0")).filter { !it.fileName.toString().startsWith(".") }.count().toInt(), "two data files and one index file")
        assertTrue(referencedFiles(model).any { it.fileName.toString().endsWith(".parquet.index") })
    }
}
