package model

import service.GraphLayoutService
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Paimon key-value row's `_VALUE_KIND` is the byte that makes a `-D` row a deletion; the row is
 * otherwise stored like any other. `dv`'s level-0 file holds three such rows — the `DELETE` that
 * forced the lookup compaction — beside a thousand `+I` rows in the file it shadows, and the
 * `cl` table's changelog files carry the `+I` and `-D` their input had. An Iceberg row has no
 * such byte, and neither does a Paimon append-table row.
 */
class PaimonRowKindTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun rows(format: String, name: String): List<GraphNode.RowNode> {
        val model: FormatTableModel = if (format == "paimon") {
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/$name").absolutePath))
        } else {
            UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/$name").absolutePath))
        }
        return GraphLayoutService.layoutGraph(model, showRows = true).nodes
            .filterIsInstance<GraphNode.RowNode>().filter { PaimonRowKind.COLUMN in it.resolvedData || "id" in it.resolvedData || "k" in it.resolvedData }
    }

    @Test
    fun `the three -D rows of the delete file are retractions and nothing else is`() {
        val rows = rows("paimon", "dv")
        val byKind = rows.groupBy { it.paimonRowKind }
        assertEquals(setOf(PaimonRowKind.INSERT, PaimonRowKind.DELETE), byKind.keys)
        assertEquals(3, byKind.getValue(PaimonRowKind.DELETE).distinctBy { it.resolvedData["k"] }.size, "k = 2, 1001, 1500 — the script's DELETE")
        assertTrue(byKind.getValue(PaimonRowKind.DELETE).all { it.isRetraction })
        assertTrue(byKind.getValue(PaimonRowKind.INSERT).none { it.isRetraction })
        assertEquals("-D (delete)", PaimonRowKind.describe(PaimonRowKind.DELETE))
    }

    /**
     * `cl`'s changelog is the input as written (`changelog-producer = input`), so its rows are
     * `+I` and the `-D` of its DELETE. The update pair is `lk`'s: a `lookup` producer computes
     * the change at commit time, so the second INSERT of k = 2 is `-U (2, 'b')` then
     * `+U (2, 'B')` beside the `+I (4, 'd')`, and the DELETE is `-D (3, 'c')` — each carried by
     * the COMPACT snapshot the lookup ran in, not by the APPEND before it.
     */
    @Test
    fun `a changelog file's rows carry the kind the input had, or the pair the lookup computed`() {
        val kinds = rows("paimon", "cl").mapNotNull { it.paimonRowKind }.toSet()
        assertEquals(setOf(PaimonRowKind.INSERT, PaimonRowKind.DELETE), kinds)

        val lk = PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/lk").absolutePath))
        assertEquals(listOf("APPEND", "COMPACT", "APPEND", "COMPACT", "APPEND", "COMPACT"), lk.snapshots.map { it.metadata.commitKind })
        assertEquals(listOf(false, true, false, true, false, true), lk.snapshots.map { it.changelogManifests.isNotEmpty() }, "the lookup's COMPACT carries the changelog")
        fun changelogRows(snapshotIndex: Int) = lk.snapshots[snapshotIndex].changelogManifests.single().entries.single().rows
            .map { row -> Triple((row.cells[PaimonRowKind.COLUMN] as Number).toInt(), row.cells["k"], row.cells["v"].toString()) }
        assertEquals(
            listOf(Triple(PaimonRowKind.UPDATE_BEFORE, 2, "b"), Triple(PaimonRowKind.UPDATE_AFTER, 2, "B"), Triple(PaimonRowKind.INSERT, 4, "d")),
            changelogRows(3).sortedWith(compareBy({ it.second.toString() }, { it.first })),
        )
        assertEquals(listOf(Triple(PaimonRowKind.DELETE, 3, "c")), changelogRows(5))
        assertEquals("-U (update, the value before)", PaimonRowKind.describe(PaimonRowKind.UPDATE_BEFORE))
        assertEquals("+U (update, the value after)", PaimonRowKind.describe(PaimonRowKind.UPDATE_AFTER))
        assertTrue(PaimonRowKind.isRetraction(PaimonRowKind.UPDATE_BEFORE) && !PaimonRowKind.isRetraction(PaimonRowKind.UPDATE_AFTER))
    }

    @Test
    fun `an Iceberg row and an append-table row have no kind`() {
        assertTrue(rows("iceberg", "test").isNotEmpty())
        rows("iceberg", "test").forEach { assertNull(it.paimonRowKind); assertTrue(!it.isRetraction) }
        assertTrue(rows("paimon", "ao").isNotEmpty())
        rows("paimon", "ao").forEach { assertNull(it.paimonRowKind) }
    }
}
