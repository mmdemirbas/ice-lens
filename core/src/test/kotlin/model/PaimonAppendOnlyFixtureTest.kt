package model

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Paimon append-only table — no primary key, `bucket = -1` — which every other Paimon fixture
 * is not, and which is the other common shape of Paimon table.
 *
 * Three things differ where this tool reads: there is no key, so `_MIN_KEY` / `_MAX_KEY` are
 * rows over nothing and the file panel has no key range to show; every file lands in `bucket-0`
 * under its partition whatever the bucket setting says; and a `DELETE` has no merge engine to
 * write a `-D` row into, so it rewrites the file it touches — an `APPEND` commit whose delta is
 * negative and whose replay removes one file and adds another. The expected figures are read off
 * `docs/fixtures/paimon-ao.sql`.
 */
class PaimonAppendOnlyFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val tableDir = File(repoRoot, "example/paimon/db.db/ao")
    private val model = PaimonUnifiedTableModel(Paths.get(tableDir.absolutePath))

    @Test
    fun `the table has no key, and its files carry no key range but do carry column bounds`() {
        assertEquals(emptyList(), model.readErrors + model.snapshots.flatMap { it.readErrors })
        val schema = model.schemas.single()
        assertEquals(emptyList(), schema.primaryKeys)
        assertEquals(listOf("dt"), schema.partitionKeys)
        assertEquals("-1", schema.options["bucket"])

        val entries = model.snapshots.flatMap { it.baseManifests + it.deltaManifests }.flatMap { it.entries }
        assertTrue(entries.isNotEmpty())
        entries.forEach { entry ->
            assertNull(entry.keyMin, "no trimmed key, so no key range: ${entry.metadata.file?.fileName}")
            val minKey = entry.metadata.file?.minKey
            assertEquals(emptyList(), decodePaimonRow(minKey!!, emptyList()), "a row over nothing decodes to nothing")
            assertEquals(listOf("id", "dt", "msg"), entry.columnBounds!!.map { it.name })
            assertEquals(0, entry.metadata.bucket, "unaware-bucket mode writes everything to bucket 0")
            assertEquals(0L, entry.metadata.file?.deleteRowCount)
        }
    }

    /** Files are under `dt=<epoch day>/bucket-0/` and every one the manifests name is there. */
    @Test
    fun `every file resolves under its partition's bucket-0 and exists`() {
        val entries = model.snapshots.flatMap { it.baseManifests + it.deltaManifests }.flatMap { it.entries }
        entries.forEach { entry ->
            val relative = tableDir.toPath().relativize(entry.path).toString()
            assertTrue(relative.startsWith("dt=1978") && relative.contains("/bucket-0/"), relative)
            assertTrue(Files.isRegularFile(entry.path), "${entry.path}")
        }
        assertEquals(emptyList(), findUnreferencedFiles(model).unreferenced)
    }

    /**
     * The DELETE: an `APPEND` commit with `deltaRecordCount = -1`, whose delta manifest removes
     * the three-row file and adds a two-row one without the deleted row — the shape a
     * copy-on-write delete has on a table with no merge engine.
     */
    @Test
    fun `a delete on an append table rewrites the file it touches`() {
        val delete = model.snapshots.single { it.metadata.id == 3L }
        assertEquals("APPEND", delete.metadata.commitKind)
        assertEquals(-1L, delete.metadata.deltaRecordCount)
        assertEquals(6L, delete.metadata.totalRecordCount)

        val deltaEntries = delete.deltaManifests.flatMap { it.entries }
        val removed = deltaEntries.filter { it.metadata.kind == PaimonEntryKind.DELETE }
        val added = deltaEntries.filter { it.metadata.kind == PaimonEntryKind.ADD }
        assertEquals(listOf(3L), removed.map { it.metadata.file?.rowCount })
        assertEquals(listOf(2L), added.map { it.metadata.file?.rowCount })
        assertEquals(1 to 3, added.single().let { it.columnBounds!![0].min to it.columnBounds!![0].max }, "ids 1 and 3 remain")
        assertEquals("alpha" to "charlie", added.single().let { it.columnBounds!![2].min to it.columnBounds!![2].max })

        val live = replayPaimonSnapshot(delete).liveFiles.values.filterNotNull()
        assertEquals(listOf(2L, 2L, 2L), live.map { it.rowCount }, "three live files of two rows: 1+3, 4+5, 6+7")
        assertEquals(6L, live.sumOf { it.rowCount ?: 0L })
        // The removed file is still on disk and still named by an older snapshot's list.
        assertEquals(4, tableDir.walk().count { it.isFile && it.name.endsWith(".parquet") })
    }
}
