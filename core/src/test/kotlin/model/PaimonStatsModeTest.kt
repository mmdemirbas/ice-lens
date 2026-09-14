package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every value column of every engine-written Paimon file records the shape its schema's
 * `metadata.stats-mode` options say; `sm`, `psm`, `psk` and `psl` exercise each rule
 * `StatsCollectorFactories.createStatsFactories` applies; then each disagreement is planted.
 */
class PaimonStatsModeTest {

    private fun checks(name: String): List<Pair<String, PaimonStatsModeCheck>> {
        val model = FixtureCatalog.paimonModel(name)
        val lines = listOf(model.snapshots + model.tagOnlySnapshots) + model.branches.map { it.snapshots + it.tagOnlySnapshots }
        return lines.flatMap { snapshots ->
            snapshots.flatMap { s ->
                val changelog = s.changelogManifests.map { it.path }.toSet()
                (s.baseManifests + s.deltaManifests + s.changelogManifests).distinctBy { it.path }.flatMap { m ->
                    m.entries.flatMap { e ->
                        val file = e.metadata.file ?: return@flatMap emptyList()
                        paimonStatsModeChecks(file, e.schema, e.columnBounds, changelog = m.path in changelog).map { "$name/${file.fileName}" to it }
                    }
                }
            }
        }.distinctBy { it.first to it.second.column }
    }

    @Test
    fun `every engine-written Paimon file records the shape its stats mode says`() {
        val all = FixtureCatalog.paimon.flatMap(::checks)
        val bad = all.filter { it.second.agrees == false }
        assertEquals(emptyList(), bad.map { "${it.first} ${it.second.column}: ${it.second.reason} (${it.second.configured.setBy})" })
        val agreed = all.filter { it.second.agrees == true }
        assertTrue(agreed.size >= 300, "${agreed.size} agreed of ${all.size}")
        assertEquals(setOf("none", "counts", "truncate(4)", "truncate(16)", "full"), agreed.map { it.second.configured.mode.spelled }.toSet())
        val unjudged = all.filter { it.second.agrees == null }.map { it.second.reason }.toSet()
        assertTrue(unjudged.all { it.startsWith("every value is null") || it.startsWith("the file's statistics could not be placed") }, "$unjudged")
    }

    @Test
    fun `psm gives each rule its column, and the second file the option set after the first`() {
        val byFile = checks("psm").groupBy { it.first }.mapValues { (_, v) -> v.map { it.second }.associateBy { c -> c.column } }
        val (first, second) = byFile.keys.sortedBy { f -> byFile.getValue(f).getValue("tag").recorded }.let { it[1] to it[0] } // "nothing" sorts after "counts"
        fun mode(file: String, column: String) = byFile.getValue(file).getValue(column)
        assertEquals("counts", mode(first, "k").configured.mode.spelled)
        assertEquals("metadata.stats-mode = counts", mode(first, "k").configured.setBy)
        assertEquals("counts", mode(first, "k").recorded)
        assertEquals("truncate(4)", mode(first, "name").configured.mode.spelled)
        assertEquals("fields.name.stats-mode = truncate(4)", mode(first, "name").configured.setBy)
        assertEquals("counts and bounds", mode(first, "name").recorded)
        assertEquals("full", mode(first, "note").configured.mode.spelled)
        assertEquals("nothing", mode(first, "tag").recorded)
        assertEquals("none", mode(first, "tag").configured.mode.spelled)
        assertEquals("counts", mode(first, "score").configured.mode.spelled)
        assertEquals("counts", mode(second, "tag").configured.mode.spelled)
        assertEquals("fields.tag.stats-mode = counts", mode(second, "tag").configured.setBy)
        assertEquals("counts", mode(second, "tag").recorded)
        assertTrue(byFile.values.flatMap { it.values }.all { it.agrees == true })
    }

    @Test
    fun `psk records the first two columns and nothing past them, and psl's upgraded file keeps level 0's nothing`() {
        val psk = checks("psk").map { it.second }.associateBy { it.column }
        listOf("c1", "c2").forEach { assertEquals("the default, nothing configured", psk.getValue(it).configured.setBy, it); assertEquals("counts and bounds", psk.getValue(it).recorded, it) }
        listOf("c3", "c4", "c5").forEach { assertEquals("past the first 2 columns (metadata.stats-keep-first-n-columns = 2)", psk.getValue(it).configured.setBy, it); assertEquals("nothing", psk.getValue(it).recorded, it) }

        val psl = FixtureCatalog.paimonModel("psl")
        // The compaction's delta removes the file at level 0 and adds the same file at level 5: an upgrade, not a rewrite.
        val delta = psl.snapshots.last().deltaManifests.flatMap { it.entries }
        assertEquals(listOf(PaimonEntryKind.DELETE to 0, PaimonEntryKind.ADD to 5), delta.map { it.metadata.kind to it.metadata.file?.level })
        assertEquals(1, delta.map { it.metadata.file?.fileName }.toSet().size)
        val upgraded = delta.last().metadata.file!!
        assertEquals(PaimonFileSource.APPEND, upgraded.fileSource)
        assertEquals(0, paimonStatsWriteLevel(upgraded))
        val v = delta.last().let { e -> paimonStatsModeChecks(upgraded, e.schema, e.columnBounds) }.single { it.column == "v" }
        assertEquals("metadata.stats-mode.per.level = 0:none, level 0", v.configured.setBy)
        assertEquals("nothing", v.recorded)
        assertEquals(true, v.agrees)
        assertEquals("as configured (written at level 0, upgraded to 5)", v.reason)
        // Had the compaction rewritten it, level 5 would take the table's default.
        assertEquals("the default, nothing configured", paimonStatsModes(psl.schemas.last(), 5).getValue("v").setBy)
    }

    @Test
    fun `bounds under counts, a bound past a truncation, nothing under the default, and unplaced statistics are each named`() {
        val schema = FixtureCatalog.paimonModel("psm").schemas.last()
        fun bounds(name: String, min: String?, max: String?, nulls: Long?) = PaimonColumnBounds(name, "STRING", min, max, nulls, decoded = true)
        val file = PaimonDataFileMeta(fileName = "f.parquet", rowCount = 3, level = 0, fileSource = PaimonFileSource.APPEND)
        val checks = paimonStatsModeChecks(
            file, schema,
            listOf(bounds("k", "1", "3", 0), bounds("name", "alpha", "bravo", 0), bounds("tag", "x", "y", 0), bounds("score", null, null, null)),
        ).associateBy { it.column }
        assertEquals("bounds recorded under counts", checks.getValue("k").reason)
        assertEquals("a bound of 5 characters under truncate(4)", checks.getValue("name").reason)
        assertEquals("bounds recorded under counts", checks.getValue("tag").reason)
        assertEquals("no counts recorded under counts", checks.getValue("score").reason)
        assertEquals("no counts recorded under full", checks.getValue("note").reason)

        val allNull = paimonStatsModeChecks(file, schema, listOf(bounds("note", null, null, 3))).single { it.column == "note" }
        assertNull(allNull.agrees)
        val unplaced = paimonStatsModeChecks(file, schema, null).single { it.column == "note" }
        assertNull(unplaced.agrees)
        assertEquals("not placed", unplaced.recorded)
        assertEquals(emptyList(), paimonStatsModeChecks(file, schema, emptyList(), changelog = true))
    }

    @Test
    fun `the integrity report holds every Paimon fixture to its stats modes`() {
        FixtureCatalog.paimon.forEach { fixture ->
            val report = FixtureCatalog.paimonModel(fixture).integrityReport()
            assertTrue(report.findings.none { it.check == IntegrityCheck.METRICS_MODES }, "$fixture: ${report.findings.filter { it.check == IntegrityCheck.METRICS_MODES }}")
        }
    }
}
