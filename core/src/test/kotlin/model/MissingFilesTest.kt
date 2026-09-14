package model

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every engine-written table has every file its retained snapshots need — including `expired`
 * and `swept`, whose older metadata versions name files an expiry removed, which is the scoping
 * this check exists for — and a copy with a data file, a manifest and a manifest list removed
 * names each with the snapshots that read it.
 */
class MissingFilesTest {

    @Test
    fun `every checked-in table has every file its retained snapshots need`() {
        for (fixture in FixtureCatalog.iceberg) {
            val report = FixtureCatalog.icebergModel(fixture).findMissingFiles()
            assertEquals(emptyList(), report.missing.map { "$fixture/${report.relativePathOf(it)} ${it.neededBy}" })
            assertTrue(report.needed > 0 && report.snapshotsChecked > 0, "$fixture: ${report.needed} needed, ${report.snapshotsChecked} snapshots")
        }
        // pru and prua are the two tables with a data file deleted by design, for remove_unexisting_files;
        // PaimonUnexistingFilesFixtureTest holds what each is missing.
        for (fixture in FixtureCatalog.paimon - setOf("pru", "prua")) {
            val report = FixtureCatalog.paimonModel(fixture).findMissingFiles()
            assertEquals(emptyList(), report.missing.map { "$fixture/${report.relativePathOf(it)} ${it.neededBy}" })
            assertTrue(report.needed > 0 && report.snapshotsChecked > 0, "$fixture: ${report.needed} needed, ${report.snapshotsChecked} snapshots")
        }
    }

    @Test
    fun `an expired snapshot's files are not needed, and a retained one's are`() {
        // expired: older versions still name the removed snapshots' manifest lists, which are gone by design.
        val expired = FixtureCatalog.icebergModel("expired")
        assertTrue(expired.metadatas.dropLast(1).flatMap { it.snapshots }.any { it.expired }, "the fixture holds expired snapshots")
        assertEquals(emptyList(), expired.findMissingFiles().missing)
        // swept: a data file the incremental cleanup freed, listed by older versions only.
        assertEquals(emptyList(), FixtureCatalog.icebergModel("swept").findMissingFiles().missing)
        // A retained snapshot's needs are counted once per file, whichever snapshots share it.
        val mor = FixtureCatalog.icebergModel("mor").findMissingFiles()
        val distinct = FixtureCatalog.icebergModel("mor").metadatas.last().snapshots.flatMap { s -> s.manifests.flatMap { m -> m.dataFiles.filter { it.metadata.status != ManifestEntryStatus.DELETED }.map { it.path } } + s.manifests.map { it.path } + listOf(s.path) }.map { it.toAbsolutePath().normalize() }.toSet()
        assertEquals(distinct.size, mor.needed)
    }

    @Test
    fun `a data file, a manifest and a manifest list removed from a copy are each named with the snapshots that read them`(@TempDir tmp: Path) {
        val copy = tmp.resolve("mor")
        FixtureCatalog.icebergDir("mor").copyRecursively(copy.toFile())
        val model = UnifiedTableModel(copy)
        val newest = model.metadatas.last()
        val liveData = newest.snapshots.last().manifests.flatMap { m -> m.dataFiles.filter { it.metadata.status == ManifestEntryStatus.ADDED && it.metadata.dataFile?.content == DataFileContent.DATA } }.first()
        val oldestList = newest.snapshots.first().path
        val aManifest = newest.snapshots.first().manifests.first().path
        Files.delete(liveData.path)
        Files.delete(oldestList)
        Files.delete(aManifest)

        val report = UnifiedTableModel(copy).findMissingFiles()
        assertEquals(listOf(MissingFileKind.MANIFEST_LIST, MissingFileKind.MANIFEST, MissingFileKind.DATA_FILE), report.missing.map { it.kind })
        val data = report.missing.single { it.kind == MissingFileKind.DATA_FILE }
        assertEquals("data/${liveData.path.name}", report.relativePathOf(data))
        assertTrue(data.neededBy.all { it.startsWith("snapshot ") }, "${data.neededBy}")
        assertEquals(newest.snapshots.count { s -> s.manifests.any { m -> m.dataFiles.any { it.path == liveData.path && it.metadata.status != ManifestEntryStatus.DELETED } } }, data.neededBy.size)
        assertEquals(listOf("snapshot ${newest.snapshots.first().metadata.snapshotId} (append)"), report.missing.single { it.kind == MissingFileKind.MANIFEST_LIST }.neededBy)
        // The manifest is carried forward: every snapshot still listing it reads it.
        assertTrue(report.missing.single { it.kind == MissingFileKind.MANIFEST }.neededBy.size >= 2)
    }

    @Test
    fun `a Paimon data file only a tag needs, and a schema file, are named`(@TempDir tmp: Path) {
        val copy = tmp.resolve("tg")
        FixtureCatalog.paimonDir("tg").copyRecursively(copy.toFile())
        val model = PaimonUnifiedTableModel(copy)
        val tagOnly = model.tagOnlySnapshots.single()
        val tagOnlyFile = replayPaimonSnapshot(tagOnly).liveEntries.values.first().path
        Files.delete(tagOnlyFile)
        Files.delete(copy.resolve("schema").resolve("schema-0"))

        val report = PaimonUnifiedTableModel(copy).findMissingFiles()
        assertEquals(setOf(MissingFileKind.DATA_FILE, MissingFileKind.SCHEMA), report.missing.map { it.kind }.toSet())
        val data = report.missing.single { it.kind == MissingFileKind.DATA_FILE }
        assertTrue(data.neededBy.any { it.contains("retained by a tag only") }, "${data.neededBy}")
        assertTrue(report.missing.single { it.kind == MissingFileKind.SCHEMA }.neededBy.isNotEmpty())
    }
}
