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
     * `+I` and the `-D` of its DELETE; the update pair `-U` / `+U` is what a `lookup` or
     * `full-compaction` producer would write and no fixture here has one — the two are read by
     * the same byte and named here without an oracle.
     */
    @Test
    fun `a changelog file's rows carry the kind the input had`() {
        val kinds = rows("paimon", "cl").mapNotNull { it.paimonRowKind }.toSet()
        assertEquals(setOf(PaimonRowKind.INSERT, PaimonRowKind.DELETE), kinds)
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
