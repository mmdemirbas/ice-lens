package model

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The VACUUM plan held to the one VACUUM the fixtures ran: `dvac` is the table before and `dvaca`
 * the same table after `VACUUM … RETAIN 0 HOURS`, whose `VACUUM START` commit recorded what it
 * was about to delete. A clock of now makes `RETAIN 0` the run's own conditions whatever the
 * checkout's file times are, since every file and tombstone is older than now.
 */
class DeltaVacuumPlanFixtureTest {

    private fun dataFiles(dir: Path): Set<String> =
        Files.list(dir).use { s -> s.filter { Files.isRegularFile(it) && !it.name.startsWith(".") }.map { it.name }.toList().toSet() }

    @Test
    fun `RETAIN 0 on dvac deletes exactly what dvaca lost, and the figures VACUUM START recorded`() {
        val now = System.currentTimeMillis()
        val plan = FixtureCatalog.deltaModel("dvac").planVacuum(now, retainMs = 0).getOrThrow()
        val before = dataFiles(FixtureCatalog.deltaDir("dvac").toPath())
        val after = dataFiles(FixtureCatalog.deltaDir("dvaca").toPath())
        assertEquals(before - after, plan.toDelete.map { it.relativePath }.toSet())

        val start = FixtureCatalog.deltaModel("dvaca").commits.single { it.commitInfo?.operation == "VACUUM START" }.commitInfo!!.operationMetrics!!
        assertEquals(start["numFilesToDelete"]!!.toInt(), plan.numFilesToDelete)
        assertEquals(start["sizeOfDataToDelete"]!!.toLong(), plan.sizeOfDataToDelete)
        val end = FixtureCatalog.deltaModel("dvaca").commits.single { it.commitInfo?.operation == "VACUUM END" }.commitInfo!!.operationMetrics!!
        assertEquals(end["numDeletedFiles"]!!.toInt(), plan.deleted.size)
        assertEquals(end["numVacuumedDirectories"]!!.toInt(), plan.directoriesListed)

        // The checksum beside each deleted file went with it: dvaca holds only the survivor's.
        val dvaca = FixtureCatalog.deltaDir("dvaca").toPath()
        assertTrue(plan.deleted.all { row -> row.companions.size == 1 && !Files.exists(dvaca.resolve(row.companions.single().name)) })
        assertEquals(plan.deleted.size, plan.deleted.flatMap { it.companions }.size)

        // Four compacted by OPTIMIZE and the whole-file DELETE's file are tombstones past the cutoff; the empty file is named by nothing.
        assertEquals(5, plan.deleted.count { "tombstone" in it.reason })
        assertEquals(listOf("part-00000-7a67d95c-74ad-4ec2-90f2-1f9064fd1791-c000.snappy.parquet"), plan.deleted.filter { "named by nothing" in it.reason }.map { it.relativePath })
    }

    @Test
    fun `a RETAIN below the table's retention is refused while the check is on`() {
        val plan = FixtureCatalog.deltaModel("dvac").planVacuum(System.currentTimeMillis(), retainMs = 0).getOrThrow()
        assertNotNull(plan.refusal)
        assertTrue("168 hours" in plan.refusal!!, plan.refusal)
        assertEquals("VACUUM RETAIN 0 HOURS", plan.call)
        assertNull(FixtureCatalog.deltaModel("dvac").planVacuum(System.currentTimeMillis(), retainMs = 7 * 86_400_000L).getOrThrow().refusal)
    }

    @Test
    fun `under the table's retention at the moment VACUUM ran, nothing is old enough`() {
        val model = FixtureCatalog.deltaModel("dvac")
        val ranAt = FixtureCatalog.deltaModel("dvaca").commits.single { it.commitInfo?.operation == "VACUUM START" }.timestampMs!!
        val plan = model.planVacuum(ranAt).getOrThrow()
        assertEquals(0, plan.numFilesToDelete)
        assertEquals(ranAt - 7 * 86_400_000L, plan.deleteBeforeMs)
        assertTrue(plan.rows.count { it.fate == VacuumFate.KEPT } >= 5, "the live file and the tombstones within the week")
    }

    @Test
    fun `after the VACUUM there is nothing left to delete`() {
        val plan = FixtureCatalog.deltaModel("dvaca").planVacuum(System.currentTimeMillis(), retainMs = 0).getOrThrow()
        assertEquals(emptyList(), plan.toDelete.map { it.relativePath })
        assertEquals(1, plan.kept)
    }

    @Test
    fun `a change data file is kept by nothing, and its directory stays while it holds one`() {
        val plan = FixtureCatalog.deltaModel("dcdf").planVacuum(System.currentTimeMillis(), retainMs = 0).getOrThrow()
        val changeFiles = plan.deleted.filter { it.relativePath.startsWith("_change_data/") }
        assertTrue(changeFiles.isNotEmpty())
        assertTrue(changeFiles.all { "change data file" in it.reason }, "${changeFiles.map { it.reason }}")
        val dir = plan.rows.single { it.isDirectory && it.relativePath == "_change_data" }
        assertEquals(VacuumFate.KEPT, dir.fate, dir.reason)
    }

    @Test
    fun `no live file or its vector is ever planned for deletion, on any Delta fixture`() {
        val now = System.currentTimeMillis()
        for (name in FixtureCatalog.delta) {
            val model = FixtureCatalog.deltaModel(name)
            val state = model.latestVersion?.let { model.stateAt(it).getOrNull() } ?: continue
            val live = state.files.values.flatMap { add ->
                listOfNotNull(model.resolve(add.path), add.deletionVector?.filePath(model.path)).map { it.toAbsolutePath().normalize() }
            }.toSet()
            val plan = model.planVacuum(now, retainMs = 0).getOrThrow()
            assertEquals(emptyList(), plan.toDelete.filter { it.path in live }.map { it.relativePath }, name)
            assertTrue(plan.rows.none { it.relativePath.startsWith("_delta_log") }, name)
        }
    }

    @Test
    fun `a retention property reads the way Delta parses it`() {
        assertEquals(604_800_000L, deltaIntervalMs("interval 1 week"))
        assertEquals(604_800_000L, deltaIntervalMs("7 days"))
        assertEquals(216_000_000L, deltaIntervalMs("interval 2 days 12 hours"))
        assertEquals(0L, deltaIntervalMs("0 hours"))
        assertNull(deltaIntervalMs("interval 1 month"), "a month has no fixed length, and Delta refuses it")
        assertNull(deltaIntervalMs("1 fortnight"))
        assertNull(deltaIntervalMs("interval"))
    }
}
