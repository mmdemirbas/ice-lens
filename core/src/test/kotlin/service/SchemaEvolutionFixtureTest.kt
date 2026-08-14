package service

import model.ColumnStats
import model.UnifiedManifest
import model.UnifiedTableModel
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reads `example/iceberg/default/evolved` and tests the claim this codebase repeats everywhere
 * and had never verified: **bounds decode against the schema the manifest carries in its own
 * Avro file metadata, not against the table's current schema.**
 *
 * Every other fixture has one schema, so the manifest's schema and the table's are the same
 * object and a decoder reading the wrong one passes all of them. This table has three, and the
 * differences are chosen so reading the wrong one cannot be a near miss:
 *
 * | Manifest schema | `id` | field 2 | `amount` | field 4 |
 * |---|---|---|---|---|
 * | 0 | `int` (4-byte bounds) | `name` string | `float` (4-byte) | absent |
 * | 4 | `long` (8-byte bounds) | `label` string | `double` (8-byte) | `note` string |
 * | 5 (current) | `long` | dropped | `double` | `note` string |
 *
 * The `id` values make the failure unambiguous rather than plausible: the first commit's bounds
 * are 1 and 2 in four bytes, and the later commits' are 3000000000 and 5000000000 — values that
 * do not fit in a signed int at all. Decoding either manifest with the other's schema cannot
 * produce the right answer by luck.
 *
 * Regenerate with `docs/fixtures/evolved.sql`.
 */
class SchemaEvolutionFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun evolvedModel(): UnifiedTableModel {
        val tableDir = File(repoRoot, "example/iceberg/default/evolved")
        assertTrue(tableDir.isDirectory, "schema-evolution fixture missing at $tableDir")
        return UnifiedTableModel(Paths.get(tableDir.absolutePath))
    }

    /** Every distinct manifest in the table, keyed by the schema id it carries. */
    private fun manifestsBySchemaId(): Map<Int?, UnifiedManifest> =
        evolvedModel().metadatas.flatMap { it.snapshots }.flatMap { it.manifests }
            .distinctBy { it.path.fileName.toString() }
            .associateBy { it.schema?.schemaId }

    private fun statsFor(manifest: UnifiedManifest): List<ColumnStats> =
        manifest.dataFiles.flatMap { model.columnStatsFor(it.metadata.dataFile!!, manifest.schema) }

    @Test
    fun `the fixture really does carry more than one schema`() {
        val bySchema = manifestsBySchemaId()
        assertEquals(
            setOf(0, 4, 5), bySchema.keys,
            "the point of this fixture is three different manifest schemas, got ${bySchema.keys}",
        )
        assertEquals(
            5, evolvedModel().metadatas.last().metadata.currentSchemaId,
            "the table's current schema should not be the one the first manifest carries",
        )
    }

    /**
     * The load-bearing case. `id` is `int` in the first manifest's schema and `long` in the
     * current one; the bounds in that manifest are four bytes. Reading them as a long is not a
     * subtle error — but reading a later manifest's eight-byte bound as an int would silently
     * truncate 3000000000, which is why both directions are checked.
     */
    @Test
    fun `a widened column decodes at the width the writing manifest recorded`() {
        val bySchema = manifestsBySchemaId()

        val old = statsFor(assertNotNull(bySchema[0])).single { it.fieldId == 1 }
        assertEquals("int", old.type?.typeName, "id was int when the first files were written")
        assertEquals("1", old.lowerBound?.display)
        assertEquals("2", old.upperBound?.display)
        assertEquals(4, old.lowerBound?.raw?.size, "an int bound occupies four bytes")

        val new = statsFor(assertNotNull(bySchema[4])).single { it.fieldId == 1 }
        assertEquals("long", new.type?.typeName, "id was widened to long before these were written")
        assertEquals("3000000000", new.lowerBound?.display, "a value no int could hold")
        assertEquals("4000000000", new.upperBound?.display)
        assertEquals(8, new.lowerBound?.raw?.size, "a long bound occupies eight bytes")
    }

    /** Same trap on the floating-point side: a float bit pattern read as a double is a denormal. */
    @Test
    fun `a float widened to double decodes as float in the manifest that predates the change`() {
        val bySchema = manifestsBySchemaId()

        val old = statsFor(assertNotNull(bySchema[0])).single { it.fieldId == 3 }
        assertEquals("float", old.type?.typeName)
        assertEquals("1.5", old.lowerBound?.display)
        assertEquals("2.5", old.upperBound?.display)
        assertEquals(4, old.lowerBound?.raw?.size)

        val new = statsFor(assertNotNull(bySchema[4])).single { it.fieldId == 3 }
        assertEquals("double", new.type?.typeName)
        assertEquals("3.5", new.lowerBound?.display)
        assertEquals(8, new.lowerBound?.raw?.size)
    }

    /**
     * Names, not just types. Field 2 was `name`, became `label`, then was dropped. Only the
     * manifest's own schema still knows what a file written before the rename called it — the
     * current schema does not contain the field at all, so a display resolved against the table
     * would show a stale name in one manifest and nothing in another.
     */
    @Test
    fun `a renamed and dropped column keeps the name it had when each file was written`() {
        val bySchema = manifestsBySchemaId()

        assertEquals("name", assertNotNull(bySchema[0]).schema?.nameOf(2))
        assertEquals("label", assertNotNull(bySchema[4]).schema?.nameOf(2))
        assertNull(
            assertNotNull(bySchema[5]).schema?.nameOf(2),
            "field 2 was dropped, so the newest manifest's schema should not name it",
        )

        val oldNames = statsFor(assertNotNull(bySchema[0])).map { it.displayName }
        assertTrue("name" in oldNames, "the first manifest's stats should label field 2 as name: $oldNames")
    }

    /**
     * A column added later has no bound in the manifests that predate it. That is an absence in
     * the data, not a decode failure, and the two must not look alike.
     */
    @Test
    fun `a column added after a file was written simply has no statistics there`() {
        val bySchema = manifestsBySchemaId()

        assertTrue(
            statsFor(assertNotNull(bySchema[0])).none { it.fieldId == 4 },
            "field 4 (note) did not exist when the first files were written",
        )
        val note = statsFor(assertNotNull(bySchema[5])).single { it.fieldId == 4 }
        assertEquals("string", note.type?.typeName)
        assertEquals("fifth", note.lowerBound?.display)
    }
}
