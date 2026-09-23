package model

import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The log cleanup plan held to the cleanup the fixtures ran: `dlcb` is `dlc` before the two
 * commits whose checkpoint at 6 cleaned the log, and `dlc` is what was left. A file's time does
 * not survive the copy into the repository, so the test gives each file the time the script gave
 * it — `touch -d '2020-01-01 00:00:00'` on versions 0 to 4 in the container, which runs in UTC —
 * and the rest their commit's time.
 */
class DeltaLogCleanupPlanFixtureTest {

    private val aged = Instant.parse("2020-01-01T00:00:00Z").toEpochMilli()

    @Test
    fun `the cleanup after the checkpoint at 6 deletes from dlcb exactly what dlc lacks`() {
        val dlcb = FixtureCatalog.deltaModel("dlcb")
        val dlc = FixtureCatalog.deltaModel("dlc")
        val ranAt = dlc.commitByVersion.getValue(6).timestampMs!!
        val times: (Path) -> Long? = { p -> if (logVersion(p) <= 4) aged else ranAt }
        val plan = dlcb.planLogCleanup(ranAt, checkpointVersion = 6, modifiedMs = times).getOrThrow()

        val before = dlcb.listing.logDir.toFile().list()!!.filter { it.first().isDigit() }.toSet()
        val after = dlc.listing.logDir.toFile().list()!!.filter { it.first().isDigit() }.toSet()
        assertEquals(before - after, plan.deleted.map { it.path.name }.toSet())
        assertEquals(7, plan.deleted.size, "commits 0 to 4 and the checkpoints at 2 and 4")
        // Seven files at one time: each read a millisecond after the one before, except that a
        // checkpoint and the commit of its version are never compared, so a run ends between them —
        // [0, 1, 2.checkpoint], [2, 3, 4.checkpoint], [4], and four files read later than written.
        assertEquals(4, plan.deleted.count { it.adjustedMs != null })
        assertEquals(6L, plan.earliestReadableAfter)
    }

    @Test
    fun `the cutoff is the retention before now, truncated to UTC midnight`() {
        val now = Instant.parse("2026-09-23T17:30:00Z").toEpochMilli()
        val plan = FixtureCatalog.deltaModel("dlcb").planLogCleanup(now, modifiedMs = { aged }).getOrThrow()
        assertEquals(Instant.parse("2026-08-24T00:00:00Z").toEpochMilli(), plan.cutoffMs)
        assertEquals(30L * 86_400_000, plan.retentionMs)
    }

    @Test
    fun `under its own checkpoint at 4, a run ending at the checkpoint keeps the commits before it`() {
        val plan = FixtureCatalog.deltaModel("dlcb").planLogCleanup(System.currentTimeMillis(), modifiedMs = { aged }).getOrThrow()
        assertEquals(4L, plan.checkpointVersion)
        // [0, 1, 2.checkpoint] ends old and below 4 and goes; [2, 3, 4.checkpoint] ends at version 4 and stays whole.
        assertEquals(listOf("00000000000000000000.json", "00000000000000000001.json", "00000000000000000002.checkpoint.parquet"), plan.deleted.map { it.path.name })
        assertEquals(setOf(2L, 3L), plan.rows.filter { it.fate == LogCleanupFate.KEPT_WITH_ITS_RUN }.map { logVersion(it.path) }.toSet())
        assertEquals(setOf(4L), plan.rows.filter { it.fate == LogCleanupFate.KEPT_AT_CHECKPOINT }.map { logVersion(it.path) }.toSet())
    }

    @Test
    fun `a run is decided by its last file, whichever way that goes`() {
        val dlcb = FixtureCatalog.deltaModel("dlcb")
        val now = Instant.parse("2026-09-23T00:00:00Z").toEpochMilli()
        // 0 and 1 dated 2020 and ten milliseconds, 2 dated before them, 3 on dated now.
        val times: (Path) -> Long? = { p ->
            when (logVersion(p)) {
                0L, 1L -> aged + 10
                2L -> aged
                else -> now
            }
        }
        // [0, 1, 2.checkpoint] read as increasing times and old; [2] old; 3 young and ends nothing it follows.
        assertEquals(
            listOf("00000000000000000000.json", "00000000000000000001.json", "00000000000000000002.checkpoint.parquet", "00000000000000000002.json"),
            dlcb.planLogCleanup(now, checkpointVersion = 4, modifiedMs = times).getOrThrow().deleted.map { it.path.name },
        )
        // 3 dated 2020 too: 2 and 3 are one run, ending at 3 — old and below 4, so both go.
        val later: (Path) -> Long? = { p -> if (logVersion(p) == 3L) aged else times(p) }
        assertTrue(dlcb.planLogCleanup(now, checkpointVersion = 4, modifiedMs = later).getOrThrow().deleted.any { logVersion(it.path) == 3L })
        // Under a checkpoint at 3 that run ends at a version the cleanup keeps, and 2.json stays with it.
        val at3 = dlcb.planLogCleanup(now, checkpointVersion = 3, modifiedMs = later).getOrThrow()
        assertEquals(listOf("00000000000000000000.json", "00000000000000000001.json", "00000000000000000002.checkpoint.parquet"), at3.deleted.map { it.path.name })
        assertEquals(LogCleanupFate.KEPT_WITH_ITS_RUN, at3.rows.single { it.path.name == "00000000000000000002.json" }.fate)
    }

    @Test
    fun `the next checkpoint is at the next multiple of the interval`() {
        assertEquals(8L, FixtureCatalog.deltaModel("dlc").nextCheckpointVersion(), "interval 2, latest 6")
        assertEquals(10L, FixtureCatalog.deltaModel("ddv").nextCheckpointVersion(), "the default interval")
    }

    private fun logVersion(p: Path): Long = p.name.take(20).toLong()
}
