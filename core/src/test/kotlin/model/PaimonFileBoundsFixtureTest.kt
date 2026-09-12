package model

import java.io.File
import java.nio.file.Paths
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a Paimon data file records about its own contents, against the rows the scripts wrote.
 *
 * `_MIN_KEY` / `_MAX_KEY` are `BinaryRow`s over the trimmed primary key, `_VALUE_STATS` a pair of
 * them over every column — the figures a scan prunes files with and a reader asks "which file
 * holds key 42" of. None was read before. The oracle is the fixture scripts: every `INSERT`
 * names its keys and values, so each file's bounds are known before the bytes are decoded, and
 * the partition columns' bounds inside `_VALUE_STATS` must agree with the same partition decoded
 * from `_PARTITION` — two encodings of one fact.
 */
class PaimonFileBoundsFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun model(name: String) = PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/$name").absolutePath))

    private fun liveEntries(model: PaimonUnifiedTableModel): List<PaimonUnifiedDataFile> =
        model.snapshots.last().let { it.baseManifests + it.deltaManifests }.flatMap { it.entries }
            .filter { it.metadata.kind == PaimonEntryKind.ADD }

    private fun PaimonUnifiedDataFile.bound(column: String): PaimonColumnBounds =
        columnBounds!!.single { it.name == column }

    /**
     * On the partitioned table the key is `k` alone — `dt` and `region` are partition keys and
     * are trimmed out of it — and each file's range is the keys its `INSERT` put in that partition.
     */
    @Test
    fun `each file's key range is the keys the script wrote into its partition`() {
        val entries = liveEntries(model("pt"))
        assertEquals(7, entries.size)
        val ranges = entries.map { entry ->
            Triple(entry.partition!!.display, entry.keyMin!!.single().value, entry.keyMax!!.single().value)
        }.toSet()
        assertEquals(
            setOf(
                Triple("dt=2024-03-05, region=eu", 1, 2),
                Triple("dt=2024-03-05, region=north-america", 3, 3),
                Triple("dt=2024-03-05, region=north-america", 10, 10),
                Triple("dt=2024-03-06, region=eu", 4, 4),
                Triple("dt=2024-03-06, region=eu", 7, 8),
                Triple("dt=2024-03-06, region=north-america", 5, 6),
                Triple("dt=2024-03-07, region=eu", 9, 9),
            ),
            ranges,
        )
        entries.forEach { entry ->
            assertEquals(listOf("k"), entry.keyMin!!.map { it.name }, "the trimmed key is k alone")
            assertTrue(entry.keyMin!!.single().decoded)
        }
    }

    /** `_VALUE_STATS` covers every column in schema order, and its partition columns agree with `_PARTITION`. */
    @Test
    fun `column bounds cover the schema and agree with the partition decoded from the entry`() {
        val entries = liveEntries(model("pt"))
        entries.forEach { entry ->
            val bounds = entry.columnBounds
            assertNotNull(bounds, "${entry.metadata.file?.fileName}")
            assertEquals(listOf("k", "dt", "region", "v"), bounds.map { it.name })
            assertTrue(bounds.all { it.decoded && it.nullCount == 0L }, "${entry.metadata.file?.fileName}: $bounds")

            val partition = entry.partition!!.values
            assertEquals(partition[0].value, entry.bound("dt").min, "dt minimum is the partition's date")
            assertEquals(partition[0].value, entry.bound("dt").max)
            assertEquals(partition[1].value, entry.bound("region").min)
            assertEquals(partition[1].value, entry.bound("region").max)
            assertEquals(entry.keyMin!!.single().value, entry.bound("k").min, "the key bound and the column bound are one figure")
            assertEquals(entry.keyMax!!.single().value, entry.bound("k").max)
        }
        // The values, per the script: the first file of (2024-03-05, eu) holds alpha and bravo.
        val first = entries.single { it.partition!!.display == "dt=2024-03-05, region=eu" }
        assertEquals("alpha" to "bravo", first.bound("v").min to first.bound("v").max)
        assertEquals(LocalDate.of(2024, 3, 5), first.bound("dt").min)
    }

    /**
     * On `dv` the bounds are over a thousand rows, and a string maximum is lexicographic: the
     * largest `v` in `v1..v1000` is `v999`, and in the three-row delete file `{v2, v1001, v1500}`
     * the minimum is `v1001`. `_DELETE_ROW_COUNT` counts the delete rows *in* the file — 3 on the
     * level-0 file that holds them, 0 on the files the deletion vector marks.
     */
    @Test
    fun `string bounds are lexicographic and the delete row count is the file's own rows`() {
        val dv = model("dv")
        val all = dv.snapshots.flatMap { it.baseManifests + it.deltaManifests }.flatMap { it.entries }
            .distinctBy { it.metadata.file?.fileName to it.metadata.kind }
        val thousand = all.single { it.metadata.file?.rowCount == 1000L && it.metadata.kind == PaimonEntryKind.ADD && it.metadata.file?.level == 0 }
        assertEquals(1 to 1000, thousand.keyMin!!.single().value to thousand.keyMax!!.single().value)
        assertEquals("v1" to "v999", thousand.bound("v").min to thousand.bound("v").max)
        assertEquals(0L, thousand.metadata.file?.deleteRowCount)

        val deletes = all.single { it.metadata.file?.rowCount == 3L && it.metadata.kind == PaimonEntryKind.ADD }
        assertEquals(2 to 1500, deletes.keyMin!!.single().value to deletes.keyMax!!.single().value)
        assertEquals("v1001" to "v2", deletes.bound("v").min to deletes.bound("v").max)
        assertEquals(3L, deletes.metadata.file?.deleteRowCount, "the three -D rows are inside this file")

        // The two files the vector marks are what the replay leaves live; the base list still
        // names the delete-row file and the pre-upgrade levels, so "ADD entries" is not "live".
        val marked = replayPaimonSnapshot(dv.snapshots.last()).liveFiles.values.filterNotNull()
        assertEquals(setOf(1000L, 500L), marked.map { it.rowCount }.toSet())
        assertEquals(listOf(0L, 0L), marked.map { it.deleteRowCount }, "a vector marks rows without touching the count")
        assertTrue(marked.all { it.fileSource == PaimonFileSource.APPEND }, "an upgraded file keeps its source")
    }

    /** The key statistics say the same as the key bounds where the key is one column, on every fixture. */
    @Test
    fun `key statistics agree with the key bounds on every checked-in table`() {
        var checked = 0
        listOf("test", "dv", "cl", "tg", "pt").forEach { name ->
            val m = model(name)
            val keyNames = m.schemas.single().primaryKeys - m.schemas.single().partitionKeys.toSet()
            assertTrue(keyNames.isNotEmpty(), "$name is a primary-key table; an append table's key is empty and is `ao`'s test")
            val keyFields = keyNames.map { key -> m.schemas.single().fields.single { it.name == key } }
            m.snapshots.flatMap { it.baseManifests + it.deltaManifests }.flatMap { it.entries }.forEach { entry ->
                val stats = entry.metadata.file?.keyStats ?: return@forEach
                val statBounds = decodePaimonColumnBounds(stats, keyFields)
                assertNotNull(statBounds, "$name/${entry.metadata.file?.fileName}")
                assertEquals(entry.keyMin!!.map { it.value }, statBounds.map { it.min }, "$name/${entry.metadata.file?.fileName}")
                assertEquals(entry.keyMax!!.map { it.value }, statBounds.map { it.max }, "$name/${entry.metadata.file?.fileName}")
                checked++
            }
        }
        assertTrue(checked >= 20, "$checked entries checked")
    }
}
