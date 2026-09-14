package model

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `sys.remove_unexisting_files`, held to `docs/fixtures/paimon-pru.sql`: `pru` is the table with
 * the files of snapshots 2 and 3 deleted from disk, `prua` the same table after the procedure
 * committed — one APPEND, a DELETE entry per file, `deltaRecordCount` -2. The level rule is held
 * to the two `fr` copies the script ran on: a missing level-0 file of a first-row table is not
 * listed, a missing level-4 file is.
 */
class PaimonUnexistingFilesFixtureTest {

    private fun planOf(model: PaimonUnifiedTableModel) = planUnexistingFiles(model.findMissingFiles(), model.paimonRowLookupInput()!!)

    @Test
    fun `the plan on pru names the two files the run listed, and prua is the commit it describes`() {
        val pru = FixtureCatalog.paimonModel("pru")
        val plan = planOf(pru)
        assertEquals(3L, plan.snapshotId)
        assertTrue(plan.commits)
        assertEquals(listOf(UnexistingFileVerdict.REMOVED, UnexistingFileVerdict.REMOVED), plan.rows.map { it.verdict })
        assertEquals(
            setOf("p=x/bucket-0/data-c9f662ef-59a9-4d80-b020-624f03395256-0.parquet", "p=y/bucket-0/data-e4c400b8-7c3d-4716-b390-33fdaa40a03e-0.parquet"),
            plan.removed.map { pru.findMissingFiles().relativePathOf(it.file) }.toSet(),
        )
        assertEquals(-2L, plan.deltaRecordCount)
        assertEquals(2, plan.partitions)
        assertEquals(2, plan.buckets)
        assertEquals(listOf(0, 0), plan.removed.map { it.level })
        // prua: snapshot 4 is the commit — APPEND, two DELETE entries and nothing added, delta -2, total 2 —
        // and its live set is pru's less the two files, so the plan on it removes nothing.
        val prua = FixtureCatalog.paimonModel("prua")
        val fix = prua.snapshots.last()
        assertEquals(4L, fix.metadata.id)
        assertEquals("APPEND", fix.metadata.commitKind)
        assertEquals(-2L, fix.metadata.deltaRecordCount)
        assertEquals(2L, fix.metadata.totalRecordCount)
        assertEquals(Long.MAX_VALUE, fix.metadata.commitIdentifier)
        val delta = fix.deltaManifests.flatMap { it.entries }
        assertEquals(listOf(PaimonEntryKind.DELETE, PaimonEntryKind.DELETE), delta.map { it.metadata.kind })
        assertEquals(plan.removed.map { it.file.path.name }.toSet(), delta.map { it.metadata.file?.fileName }.toSet())
        val before = replayPaimonSnapshot(pru.snapshots.last()).liveEntries.keys
        val after = replayPaimonSnapshot(fix).liveEntries.keys
        assertEquals(plan.removed.map { it.file.path.name }.toSet(), (before - after).map { Path.of(it).name }.toSet())
        val afterPlan = planOf(prua)
        assertEquals(emptyList(), afterPlan.removed)
        assertTrue(!afterPlan.commits)
        // The older snapshots still name the two files: they are missing still, and the procedure does not reach them.
        assertEquals(listOf(UnexistingFileVerdict.NOT_REACHED, UnexistingFileVerdict.NOT_REACHED), afterPlan.rows.map { it.verdict })
        assertTrue(afterPlan.rows.all { it.reason.startsWith("not live in snapshot 4: only snapshot ") }, afterPlan.rows.map { it.reason }.toString())
        assertTrue(afterPlan.rows.first { "c9f662ef" in it.file.path.name }.file.neededBy == listOf("snapshot 2 (APPEND)", "snapshot 3 (APPEND)"))
    }

    @Test
    fun `a first-row table's missing level-0 file is not scanned, and its missing level-4 file is`(@TempDir tmp: Path) {
        val level0 = "data-d0ebee7f-ccea-4b06-a4fe-a2cdd4f66b93-0.parquet"
        val level4 = "data-3dd96d5b-6c54-4e2d-a679-98a1eb8ed4d2-0.parquet"
        fun copyWithout(name: String, file: String): PaimonUnifiedTableModel {
            val root = tmp.resolve(name)
            FixtureCatalog.paimonDir("fr").copyRecursively(root.toFile())
            Files.delete(root.resolve("bucket-0").resolve(file))
            return PaimonUnifiedTableModel(root)
        }
        val l0 = planOf(copyWithout("frl0", level0))
        assertEquals(listOf(UnexistingFileVerdict.UNREAD), l0.rows.map { it.verdict })
        assertEquals(0, l0.rows.single().level)
        assertTrue(!l0.commits)
        assertTrue("first-row table never opens" in l0.rows.single().reason, l0.rows.single().reason)
        val l4 = planOf(copyWithout("frl5", level4))
        assertEquals(listOf(UnexistingFileVerdict.REMOVED), l4.rows.map { it.verdict })
        assertEquals(4, l4.rows.single().level)
        assertEquals(-1L, l4.deltaRecordCount)
    }

    @Test
    fun `a missing manifest is not reached, and every other fixture plans nothing`(@TempDir tmp: Path) {
        val root = tmp.resolve("pc")
        FixtureCatalog.paimonDir("pc").copyRecursively(root.toFile())
        val model = PaimonUnifiedTableModel(root)
        val manifest = model.snapshots.last().deltaManifests.first().path
        Files.delete(manifest)
        val plan = planOf(PaimonUnifiedTableModel(root))
        val row = plan.rows.single { it.file.kind == MissingFileKind.MANIFEST }
        assertEquals(UnexistingFileVerdict.NOT_REACHED, row.verdict)
        assertTrue(row.reason.startsWith("a manifest: the procedure stats the data files"), row.reason)
        assertTrue(!plan.commits)
        for (fixture in FixtureCatalog.paimon - setOf("pru", "prua")) {
            val m = FixtureCatalog.paimonModel(fixture)
            val read = m.paimonRowLookupInput() ?: continue
            assertEquals(emptyList(), planUnexistingFiles(m.findMissingFiles(), read).rows, fixture)
        }
    }
}
