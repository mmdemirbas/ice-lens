package model

import service.TableFormat
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What `remove_orphan_files` deletes, held to two tables it ran on.
 *
 * `orph` / `orpha` (docs/fixtures/orph.scala) and `po` / `poa` (docs/fixtures/paimon-po.sql) are
 * each one table copied on disk before the procedure ran on the original, so the files missing
 * from the second are the procedure's own answer — the sweep/swept shape. A checkout keeps no
 * modification times, so the age rule is exercised against a clock placed on either side of
 * the files' times rather than against the calendar.
 */
class OrphanRemovalPlanFixtureTest {

    private fun filesUnder(dir: File): Set<String> =
        dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.map { it.relativeTo(dir).path }.toSet()

    private fun missingFrom(before: File, after: File): Set<String> = filesUnder(before) - filesUnder(after)

    private fun newestMtime(report: UnreferencedFilesReport) =
        (report.unreferenced + report.unreachedFromCurrent).maxOf { it.modifiedMs }

    @Test
    fun `an Iceberg cleanup removes what an older metadata version alone names, and the walk's strays but the hidden one`() {
        val report = findUnreferencedFiles(FixtureCatalog.icebergModel("orph"))
        assertEquals(TableFormat.ICEBERG, report.format)
        // The walk's literal reading: three strays, of which the `_` one is hidden to the procedure.
        assertEquals(listOf("data/_stray", "data/stray.txt", "metadata/stray.txt"), report.unreferenced.map { report.relativePathOf(it) })
        assertEquals(
            listOf("data/_stray"),
            report.unreferenced.filter { it.unlistedBecause != null }.map { report.relativePathOf(it) },
        )
        // What the current metadata does not reach: v1..v3, three manifest lists, the first
        // commit's manifest, and its data file — a DELETED entry alone in the delete's manifest.
        val unreached = report.unreachedFromCurrent.map { report.relativePathOf(it) }
        assertEquals(8, unreached.size, unreached.toString())
        assertEquals(listOf("metadata/v1.metadata.json", "metadata/v2.metadata.json", "metadata/v3.metadata.json"), unreached.filter { it.endsWith(".metadata.json") })
        assertEquals(3, unreached.count { it.startsWith("metadata/snap-") })
        assertEquals(1, unreached.count { it.endsWith("-m0.avro") })
        assertEquals(1, unreached.count { it.startsWith("data/") && it.endsWith(".parquet") })

        // Past the cutoff, the plan is exactly what the procedure deleted.
        val plan = planOrphanRemoval(report, nowMs = newestMtime(report) + 1, olderThanMs = Long.MAX_VALUE)
        assertEquals(
            missingFrom(FixtureCatalog.icebergDir("orph"), FixtureCatalog.icebergDir("orpha")),
            plan.removed.map { it.relativePath }.toSet(),
        )
        assertEquals(listOf("data/_stray"), plan.rows.filter { it.fate == OrphanFate.UNLISTED }.map { it.relativePath })
        assertEquals(8, plan.rows.count { it.namedByOlderVersionOnly })
        assertTrue(plan.rows.first { it.namedByOlderVersionOnly }.reason.startsWith("named only by an older metadata version"))
    }

    @Test
    fun `an Iceberg cleanup under the default interval removes nothing younger than three days`() {
        val report = findUnreferencedFiles(FixtureCatalog.icebergModel("orph"))
        val newest = newestMtime(report)
        val young = planOrphanRemoval(report, nowMs = newest + 1)
        assertTrue(young.defaultCutoff)
        assertEquals(newest + 1 - ICEBERG_ORPHAN_INTERVAL_MS, young.cutoffMs)
        assertEquals(emptyList(), young.removed)
        assertEquals(10, young.tooYoung)
        assertEquals(1, young.unlisted)
        val old = planOrphanRemoval(report, nowMs = newest + ICEBERG_ORPHAN_INTERVAL_MS + 1)
        assertEquals(10, old.removed.size)
        assertEquals("3 days", old.defaultIntervalText)
    }

    @Test
    fun `a Paimon cleanup removes the rollback's leftovers and the strays in the directories it lists`() {
        val report = findUnreferencedFiles(FixtureCatalog.paimonModel("po"))
        assertEquals(TableFormat.PAIMON, report.format)
        assertEquals(emptyList(), report.unreachedFromCurrent)
        assertEquals(14, report.unreferenced.size, report.unreferenced.map { report.relativePathOf(it) }.toString())
        val plan = planOrphanRemoval(report, nowMs = newestMtime(report) + 1, olderThanMs = Long.MAX_VALUE)
        assertEquals(
            missingFrom(FixtureCatalog.paimonDir("po"), FixtureCatalog.paimonDir("poa")),
            plan.removed.map { it.relativePath }.toSet(),
        )
        assertEquals(11, plan.removed.size)
        assertEquals(
            listOf("junk-at-root", "p=x/stray-in-partition", "schema/junk"),
            plan.rows.filter { it.fate == OrphanFate.UNLISTED }.map { it.relativePath },
        )
        // Under the default interval every listed file is too young, and the unlisted three stay unlisted.
        val young = planOrphanRemoval(report, nowMs = newestMtime(report) + 1)
        assertEquals(newestMtime(report) + 1 - PAIMON_ORPHAN_INTERVAL_MS, young.cutoffMs)
        assertEquals(0, young.removed.size)
        assertEquals(11, young.tooYoung)
        assertEquals(3, young.unlisted)
        assertEquals("1 day", young.defaultIntervalText)
    }

    /**
     * The snapshot-directory rule both ways, on a copy: a stray under `snapshot/` is listed, a
     * stray named like a snapshot file is not, and a branch's `snapshot/` follows the same rule.
     */
    @Test
    fun `a Paimon snapshot directory is cleaned of files not named as snapshots, on main and on a branch`() {
        val copy = Files.createTempDirectory("orphan-plan").toFile()
        try {
            FixtureCatalog.paimonDir("br").copyRecursively(File(copy, "br"))
            val root = File(copy, "br")
            val stray = listOf(
                "snapshot/junk", "snapshot/snapshot-99", "snapshot/LATEST.bak",
                "branch/branch-dev/snapshot/junk", "branch/branch-dev/snapshot/snapshot-77",
                "bucket-0/data-stray.parquet", "manifest/manifest-stray", "index/stray", "tag/junk", "consumer/junk", "stray-at-root",
            )
            stray.forEach { File(root, it).apply { parentFile.mkdirs() }.writeText("stray") }
            val report = findUnreferencedFiles(PaimonUnifiedTableModel(root.toPath()))
            val byPath = report.unreferenced.associateBy { report.relativePathOf(it) }
            assertEquals(stray.toSet(), byPath.keys)
            listOf("snapshot/junk", "snapshot/LATEST.bak", "branch/branch-dev/snapshot/junk", "bucket-0/data-stray.parquet", "manifest/manifest-stray", "index/stray")
                .forEach { assertNull(byPath.getValue(it).unlistedBecause, it) }
            listOf("snapshot/snapshot-99", "branch/branch-dev/snapshot/snapshot-77")
                .forEach { assertTrue(byPath.getValue(it).unlistedBecause!!.startsWith("in snapshot/ under a snapshot file's name"), it) }
            listOf("tag/junk", "consumer/junk", "stray-at-root")
                .forEach { assertTrue(byPath.getValue(it).unlistedBecause!!.startsWith("not in a directory remove_orphan_files lists"), it) }

            // The age rule, with the clock: one file set a day and a second older than the rest.
            val old = File(root, "manifest/manifest-stray")
            val newest = report.unreferenced.maxOf { it.modifiedMs }
            Files.setLastModifiedTime(old.toPath(), FileTime.fromMillis(newest - PAIMON_ORPHAN_INTERVAL_MS - 1_000))
            val plan = planOrphanRemoval(findUnreferencedFiles(PaimonUnifiedTableModel(root.toPath())), nowMs = newest)
            assertEquals(listOf("manifest/manifest-stray"), plan.removed.map { it.relativePath })
            assertEquals(5, plan.tooYoung)
            assertEquals(5, plan.unlisted)
        } finally {
            copy.deleteRecursively()
        }
    }

    @Test
    fun `an age prints in its largest whole unit`() {
        assertEquals("40 s", formatOrphanAge(40_000))
        assertEquals("12 min", formatOrphanAge(12 * 60_000L + 5_000))
        assertEquals("5 h", formatOrphanAge(5 * 3_600_000L + 59 * 60_000L))
        assertEquals("3 d", formatOrphanAge(ICEBERG_ORPHAN_INTERVAL_MS + 7_200_000L))
        assertEquals("0 s", formatOrphanAge(-5))
    }
}
