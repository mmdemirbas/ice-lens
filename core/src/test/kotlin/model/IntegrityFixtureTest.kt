package model

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The report runs the checks the panels run, so the corpus decides what it has to say: every
 * engine-written table agrees with itself, except the one disagreement the format wrote — `tg`'s
 * tag records a changelog count against a changelog list the expiry deleted. That the report
 * reaches each check is shown the other way, by lying in a copy: two summary figures changed in
 * the newest `metadata.json` are exactly the two findings.
 */
class IntegrityFixtureTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun icebergFixtures() = File(repoRoot, "example/iceberg/default").listFiles()!!.filter { it.isDirectory }.map { it.name }.sorted()
    private fun paimonFixtures() = File(repoRoot, "example/paimon/db.db").listFiles()!!.filter { it.isDirectory }.map { it.name }.sorted()

    @Test
    fun `every Iceberg fixture agrees with itself`() {
        var checked = 0
        for (fixture in icebergFixtures()) {
            val report = UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/$fixture").absolutePath)).integrityReport()
            assertEquals(emptyList(), report.findings, fixture)
            assertEquals(report.snapshotCount, report.closuresChecked, "$fixture: every closure fits under the cap")
            assertTrue(report.checked > 0, fixture)
            checked += report.checked
        }
        assertTrue(checked >= 1_000, "checked only $checked comparisons")
    }

    /** `tg` and `pea` each keep a tag on a snapshot whose changelog list the expiry deleted — a tag retains data, not changelog. */
    @Test
    fun `every Paimon fixture agrees with itself, but for the changelog count a tag records against a list expiry deleted`() {
        var checked = 0
        for (fixture in paimonFixtures()) {
            val report = PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/$fixture").absolutePath)).integrityReport()
            if (fixture == "tg" || fixture == "pea") {
                val finding = report.findings.single()
                assertEquals(IntegrityCheck.RECORD_COUNTS, finding.check)
                assertEquals("Changelog records", finding.figure)
                assertEquals(if (fixture == "tg") "3" else "1", finding.recorded)
                assertEquals("0", finding.counted)
                assertTrue(finding.where.endsWith(", retained by a tag only"), finding.where)
                assertTrue(report.readErrors > 0, "the missing list is a read error beside the finding")
            } else {
                assertEquals(emptyList(), report.findings, fixture)
            }
            assertEquals(report.snapshotCount, report.closuresChecked, fixture)
            checked += report.checked
        }
        assertTrue(checked >= 300, "checked only $checked comparisons")
    }

    @Test
    fun `a summary figure changed in the newest metadata is found by the check that reads it`() {
        val copy = Files.createTempDirectory("integrity").toFile()
        try {
            File(repoRoot, "example/iceberg/default/test").copyRecursively(copy)
            val newest = File(copy, "metadata").listFiles()!!.filter { it.name.endsWith(".metadata.json") }.maxBy { metadataVersionFromFileName(it.name) ?: -1 }
            val lied = newest.readText()
                .replace("\"added-records\" : \"2\"", "\"added-records\" : \"3\"")
                .replace("\"total-records\" : \"2\"", "\"total-records\" : \"5\"")
            assertTrue(lied != newest.readText(), "the fixture's summary should hold the two figures being changed")
            newest.writeText(lied)

            val report = UnifiedTableModel(Paths.get(copy.absolutePath)).integrityReport()
            assertEquals(
                setOf(
                    IntegrityCheck.COMMIT_SUMMARY to Triple("Records added", "3", "2"),
                    IntegrityCheck.SNAPSHOT_TOTALS to Triple("Records", "5", "2"),
                ),
                report.findings.map { it.check to Triple(it.figure, it.recorded, it.counted) }.toSet(),
            )
            assertTrue(report.findings.all { it.where.startsWith("snapshot 859671389683896769 (append)") })
            assertTrue(report.describe.startsWith("2 of "))
        } finally {
            copy.deleteRecursively()
        }
    }

    @Test
    fun `the closure cap is honoured and said`() {
        val m = UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/branched").absolutePath))
        val report = m.integrityReport(maxClosureChecks = 2)
        assertEquals(2, report.closuresChecked)
        assertTrue(report.snapshotCount > 2)
        assertTrue(report.checked < m.integrityReport().checked)
    }
}
