package model

import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `example/paimon/db.db/sm`: statistics switched off for one column of three, from
 * `docs/fixtures/paimon-sm.sql`.
 *
 * With `fields.v.stats-mode = none` the entry's `_VALUE_STATS` rows shrink to the columns that
 * have statistics and `_VALUE_STATS_COLS` names them — `[k, w]`, arity 2 over a three-field
 * schema. A decoder that lays the stats row over the schema in order would put `w`'s bounds on
 * `v`; the expected values are the script's: k 1..3, w 10..30, no nulls, and nothing for v.
 */
class PaimonStatsModeFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val model = PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/sm").absolutePath))
    private val entry = model.snapshots.single().deltaManifests.single().entries.single()

    @Test
    fun `the stats row covers the columns _VALUE_STATS_COLS names and no other`() {
        val file = entry.metadata.file!!
        assertEquals(listOf("k", "w"), file.valueStatsCols)
        assertEquals(listOf("k", "v", "w"), model.schemas.single().fields.map { it.name }, "three fields in the schema")
        assertEquals("none", model.schemas.single().options["fields.v.stats-mode"])
        assertEquals(2, java.nio.ByteBuffer.wrap(file.valueStats!!.minValues!!, 0, 4).int, "the BinaryRow's arity is the subset's, not the schema's")
        assertEquals(listOf(0L, 0L), file.valueStats!!.nullCounts)
    }

    @Test
    fun `each bound lands on the column it belongs to`() {
        val bounds = entry.columnBounds!!
        assertEquals(listOf("k", "w"), bounds.map { it.name })
        assertEquals(listOf(1 to 3, 10 to 30), bounds.map { it.min to it.max })
        assertTrue(bounds.all { it.decoded && it.nullCount == 0L })
        assertNull(bounds.firstOrNull { it.name == "v" }, "v has no statistics, so no bound is attributed to it")
        assertEquals(listOf("INT NOT NULL", "INT"), bounds.map { it.type }, "the key is declared NOT NULL by the primary key")
    }
}
