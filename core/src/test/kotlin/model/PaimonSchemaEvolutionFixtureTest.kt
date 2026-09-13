package model

import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `example/paimon/db.db/se`: a primary-key table whose schema gained a column between two writes,
 * then compacted, from `docs/fixtures/paimon-se.sql`.
 *
 * A data file's `_VALUE_STATS` is a row over the schema the *file* was written under, named by
 * its own `_SCHEMA_ID`, and a manifest has a `_SCHEMA_ID` of its own. The two differ in exactly
 * one place here: the compaction's delta manifest, written under schema 1, records the schema-0
 * file it removed. Decoded against the manifest's schema that entry's two-field stats row is read
 * as three, fails the arity check and comes out as no bounds at all — which is what this table
 * showed before the entry was decoded against its own file's schema. The expected values are the
 * script's: file A k 1..2, v a..b; file B k 1..3, v aa..c, w 10..30; the compacted file the same
 * with one null in w, for the row A contributed that has no w.
 */
class PaimonSchemaEvolutionFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val model = PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/se").absolutePath))
    private val compaction = model.snapshots.single { it.metadata.commitKind == "COMPACT" }
    private val delta = compaction.deltaManifests.single()

    @Test
    fun `the compaction's manifest is written under the new schema and lists a file of the old`() {
        assertEquals(listOf(listOf("k", "v"), listOf("k", "v", "w")), model.schemas.sortedBy { it.id }.map { s -> s.fields.map { it.name } })
        assertEquals(listOf(0, 1, 1), model.snapshots.sortedBy { it.metadata.id }.map { it.metadata.schemaId })
        assertEquals(1L, delta.metadata.schemaId)
        val fileSchemaAndKind = delta.entries.map { "${it.metadata.file?.schemaId}:${it.metadata.kind}" }.toSet()
        assertEquals(setOf("0:${PaimonEntryKind.DELETE}", "1:${PaimonEntryKind.DELETE}", "1:${PaimonEntryKind.ADD}"), fileSchemaAndKind)
        assertTrue(model.readErrors.isEmpty() && delta.readErrors.isEmpty(), "${model.readErrors + delta.readErrors}")
    }

    @Test
    fun `an entry's bounds are decoded against its own file's schema, not the manifest's`() {
        val removedOld = delta.entries.single { it.metadata.file?.schemaId == 0L }
        assertEquals(2, java.nio.ByteBuffer.wrap(removedOld.metadata.file!!.valueStats!!.minValues!!, 0, 4).int, "a two-field stats row in a three-field manifest")
        val bounds = assertNotNull(removedOld.columnBounds, "decoded, not dropped for the wrong arity")
        assertEquals(listOf("k", "v"), bounds.map { it.name })
        assertEquals(listOf(1 to 2, "a" to "b"), bounds.map { it.min to it.max })
        assertTrue(bounds.all { it.decoded && it.nullCount == 0L })
        assertEquals(listOf(1), removedOld.keyMin?.map { it.value })
        assertEquals(listOf(2), removedOld.keyMax?.map { it.value })
    }

    @Test
    fun `the compacted file's bounds cover the new column, with one null for the row that predates it`() {
        val added = delta.entries.single { it.metadata.kind == PaimonEntryKind.ADD }
        assertEquals(3L, added.metadata.file?.rowCount, "k = 1 written twice merges to one row")
        val bounds = assertNotNull(added.columnBounds)
        assertEquals(listOf("k", "v", "w"), bounds.map { it.name })
        assertEquals(listOf(1 to 3, "aa" to "c", 10 to 30), bounds.map { it.min to it.max })
        assertEquals(listOf(0L, 0L, 1L), bounds.map { it.nullCount })
        assertEquals(3L, compaction.metadata.totalRecordCount)
    }

    /** Every entry of every manifest in the table decodes — the rule holds where the two schemas agree too. */
    @Test
    fun `every entry in the table has its bounds`() {
        val entries = model.snapshots.flatMap { it.baseManifests + it.deltaManifests }.flatMap { it.entries }
        assertEquals(8, entries.size, "one, then base + delta, then two base + three delta")
        entries.forEach { entry ->
            val bounds = assertNotNull(entry.columnBounds, entry.metadata.file?.fileName)
            assertEquals(model.schemas.single { it.id == entry.metadata.file?.schemaId?.toInt() }.fields.map { it.name }, bounds.map { it.name })
        }
    }
}
