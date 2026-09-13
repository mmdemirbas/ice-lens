package model

import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `example/paimon/db.db/ad`: an append table with `deletion-vectors.enabled`, from
 * `docs/fixtures/paimon-ad.sql`. `ao` showed a DELETE on an append table rewriting the file it
 * touched; here the two files stay and the DELETE commits as a **COMPACT** snapshot whose only
 * change is an index manifest — one `DELETION_VECTORS` file with a vector per touched data file,
 * cardinality 1 each, `deltaRecordCount` 0 and `totalRecordCount` still 7 for a table that reads
 * five rows. The script's SELECT is the oracle for those five: 1, 3, 4, 5, 7.
 */
class PaimonAppendDeletionVectorFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val model = PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/ad").absolutePath))
    private val delete = model.snapshots.last()

    @Test
    fun `the DELETE commits as a COMPACT that adds an index manifest and touches no data file`() {
        assertEquals(listOf("APPEND", "APPEND", "COMPACT"), model.snapshots.map { it.metadata.commitKind })
        assertEquals(listOf(false, false, true), model.snapshots.map { it.indexFiles.isNotEmpty() })
        assertEquals(7L, delete.metadata.totalRecordCount, "file rows summed — the marked rows still count")
        assertEquals(0L, delete.metadata.deltaRecordCount, "no file added or removed")
        assertTrue(delete.deltaManifests.flatMap { it.entries }.isEmpty(), "the delta list carries no entry")
        val live = paimonLiveFilesOf(delete)
        assertEquals(listOf(5L, 2L), live.map { it.recordCount }.sortedDescending(), "both files as written")
        assertTrue(model.readErrors.isEmpty() && delete.readErrors.isEmpty(), "${model.readErrors}")
    }

    @Test
    fun `one vector per touched file, one row each, in the unaware bucket`() {
        val index = delete.indexFiles.single()
        assertEquals("DELETION_VECTORS", index.indexType)
        assertEquals(0, index.bucket, "bucket = -1 tables write everything to bucket-0")
        val ranges = index.deletionVectorRanges.orEmpty().filterNotNull()
        val rowsByFile = paimonLiveFilesOf(delete).associate { it.path to it.recordCount }
        assertEquals(mapOf(5L to 1L, 2L to 1L), ranges.associate { rowsByFile.getValue(it.dataFileName!!) to it.cardinality })
        assertEquals(5L, (delete.metadata.totalRecordCount ?: 0L) - ranges.sumOf { it.cardinality ?: 0L }, "the rows the script's SELECT printed")
    }
}
