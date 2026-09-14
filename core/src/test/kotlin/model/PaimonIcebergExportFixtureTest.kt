package model

import service.TableFormat
import service.TableFormatDetector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `pic`: a Paimon primary-key table under `metadata.iceberg.storage = table-location`, which
 * writes Iceberg metadata under `<table>/metadata/` on every commit
 * (`docs/fixtures/paimon-pic.sql`). Three things it settles.
 *
 * The directory carries both formats' markers and is a Paimon table — opened as Iceberg, as the
 * detector did before, its own `snapshot/`, `schema/` and `manifest/` are orphans and its levels
 * and merge engine invisible. The export's files are referenced files, or every one of them is a
 * false orphan in the direction that gets a file deleted.
 *
 * And **which of the table's files the export lists depends on which path the commit callback
 * took**: two appends into one bucket, neither compacted, and the export lists the first commit's
 * level-0 file and not the second's. Snapshot 1 found no base metadata and rebuilt from the
 * snapshot, which lists every file of a raw-convertible split whatever its level; snapshot 2 went
 * through `shouldAddFileToIceberg`, which on a primary-key table takes the highest level only.
 * Neither half is a disagreement, and the check names each with the rule that explains it.
 */
class PaimonIcebergExportFixtureTest {

    private val model = FixtureCatalog.paimonModel("pic")

    @Test
    fun `a Paimon table carrying Iceberg metadata is detected as Paimon, and the export's files are referenced`() {
        assertEquals(TableFormat.PAIMON, TableFormatDetector.detect(model.path))
        assertTrue(TableFormatDetector.isIcebergTable(model.path), "the Iceberg markers are there too")
        assertEquals(emptyList(), model.readErrors)
        val export = assertNotNull(model.icebergExport)
        assertEquals(emptyList(), export.readErrors)
        assertEquals(listOf("v2.metadata.json"), export.metadatas.map { it.path.fileName.toString() }, "metadata.iceberg.previous-versions-max is 0 by default and delete-after-commit is on, so only the current version stays")
        // The export's four files are under the table root and nothing in Paimon's own metadata
        // names them, so each is an orphan unless the export's referenced set covers it.
        val referenced = referencedFiles(model).map { it.fileName.toString() }.toSet()
        assertTrue(
            referenced.containsAll(
                setOf(
                    "v2.metadata.json",
                    "version-hint.text",
                    "snap-1-606af31f-b5f0-4688-aa23-5b4c451a7e5e.avro",
                    "snap-1-a273951c-0970-4778-b0c4-de9ec58285e4.avro",
                    "606af31f-b5f0-4688-aa23-5b4c451a7e5e-m1.avro",
                )
            ),
            "the export's files are referenced: $referenced",
        )
        assertEquals(emptyList(), findUnreferencedFiles(model).unreferenced.map { it.path.fileName.toString() })
    }

    @Test
    fun `the export is current, and its file set is the two commit paths rather than the level rule`() {
        val check = assertNotNull(model.checkIcebergExport())
        assertTrue(check.current, "$check")
        assertEquals(2L, check.currentIcebergSnapshotId)
        assertEquals(2L, check.latestPaimonSnapshotId)
        assertEquals(1, check.versions)
        assertEquals(5, check.exportedLevel, "num-levels defaults to the compaction trigger + 1")
        assertEquals(2, check.paimonFiles.size)
        assertTrue(check.paimonFiles.values.all { it == 0 }, "neither commit compacted: ${check.paimonFiles}")
        assertEquals(
            setOf("data-f8514c24-37d7-4ba9-9002-48c9e04354de-0.parquet"),
            check.icebergFiles,
            "snapshot 1 rebuilt the export from the snapshot and listed its raw-convertible split",
        )
        assertEquals(check.icebergFiles, check.exportedBelowLevel, "listed though below level 5, by the rebuild path")
        assertEquals(
            setOf("data-f933e5dc-3f17-4b14-92a6-0cd173414e48-0.parquet"),
            check.belowExportedLevel,
            "snapshot 2 went incremental, and the level rule left its file out",
        )
        assertEquals(emptySet(), check.missingFromIceberg, "each absence is explained by a rule")
        assertEquals(emptySet(), check.extraInIceberg)
    }

    @Test
    fun `an append table exports every file, and a file the export lacks is then a disagreement`() {
        val check = IcebergExportCheck(1, 2, 2, icebergFiles = setOf("a"), paimonFiles = mapOf("a" to 0, "b" to 0), exportedLevel = null)
        assertEquals(setOf("b"), check.missingFromIceberg)
        assertEquals(emptySet(), check.belowExportedLevel)
        assertEquals(emptySet(), check.exportedBelowLevel, "an append table exports every level")
        val dv = IcebergExportCheck(1, 2, 2, icebergFiles = setOf("a"), paimonFiles = mapOf("a" to 3, "b" to 0, "c" to 1), exportedLevel = 5, aboveLevelZero = true)
        assertEquals(setOf("b"), dv.belowExportedLevel)
        assertEquals(setOf("c"), dv.missingFromIceberg, "above level 0 and not exported")
        assertEquals(emptySet(), dv.exportedBelowLevel)
        val behind = IcebergExportCheck(1, 1, 2, icebergFiles = emptySet(), paimonFiles = emptyMap())
        assertTrue(!behind.current)
    }
}
