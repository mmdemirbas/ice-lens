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

    /**
     * `pih` is `pic` under `metadata.iceberg.storage = hadoop-catalog`, whose export goes to
     * catalog storage — `<warehouse>/iceberg/<db>/<table>/metadata/` — so the table directory
     * carries no Iceberg marker and the export is a directory of its own beside the warehouse's
     * databases (`docs/fixtures/paimon-pih.sql`). The model finds it by the rule
     * `catalogTableMetadataPath` writes it by, and the check reads the same as `pic`'s. Opened
     * as the Iceberg table it also is, its data files are the Paimon table's, under the
     * `location` its metadata records rather than under its own directory — which the resolver
     * already re-roots beside the table, since the two directories share their trailing
     * `iceberg/db/pih` with the warehouse above.
     */
    @Test
    fun `an export in catalog storage is found beside the warehouse, and opens as an Iceberg table over the Paimon table's files`() {
        val pih = FixtureCatalog.paimonModel("pih")
        assertEquals(TableFormat.PAIMON, TableFormatDetector.detect(pih.path))
        assertTrue(!TableFormatDetector.isIcebergTable(pih.path), "no Iceberg marker under the table itself")
        val exportPath = assertNotNull(pih.icebergExportPath)
        assertEquals(pih.path.parent.parent.resolve("iceberg").resolve("db").resolve("pih").normalize(), exportPath.normalize())
        val check = assertNotNull(pih.checkIcebergExport())
        assertTrue(!check.atTable)
        assertEquals("hadoop-catalog", check.storage)
        assertTrue(check.current && check.missingFromIceberg.isEmpty() && check.extraInIceberg.isEmpty(), "$check")
        assertEquals(1, check.icebergFiles.size, "the rebuild path's file, as on pic")
        assertEquals(1, check.belowExportedLevel.size)
        assertTrue(check.describe.startsWith("current: the export's snapshot 2 is the table's latest; 1 metadata version at ") && check.describe.endsWith("/iceberg/db/pih/metadata/; an Iceberg reader sees 1 of the table's 2 live files"), check.describe)
        assertEquals(emptyList(), findUnreferencedFiles(pih).unreferenced, "the export is outside the table root, and nothing under the root is an orphan")

        val export = UnifiedTableModel(exportPath)
        assertEquals(emptyList(), export.readErrors)
        val files = export.metadatas.last().snapshots.flatMap { s -> s.manifests.flatMap { it.dataFiles } }
        assertTrue(files.isNotEmpty())
        files.forEach { file ->
            assertEquals(PathResolution.REBUILT_BESIDE_TABLE, file.pathResolution, file.toString())
            assertTrue(java.nio.file.Files.isRegularFile(file.path), "resolved to the Paimon table's file: ${file.path}")
            assertTrue(file.path.normalize().startsWith(pih.path.normalize()))
        }
        assertEquals("/wh/db.db/pih", export.metadatas.last().metadata.location)
        // The table panel's row for it: the metadata says it is under /wh/iceberg/db/pih and the table is at /wh/db.db/pih.
        val summary = service.IcebergGraphBuilder.buildGraph(export).nodes.filterIsInstance<GraphNode.TableNode>().single().summary
        assertEquals("/wh/iceberg/db/pih", summary.metadataKeptApartAt)
        assertEquals(pih.path.toAbsolutePath().normalize(), summary.locationIsPaimonTable?.let { java.nio.file.Paths.get(it).toAbsolutePath().normalize() }, "the location, re-rooted beside this directory, is the Paimon table")
        assertEquals(null, FixtureCatalog.icebergModel("mor").let { service.IcebergGraphBuilder.buildGraph(it).nodes.filterIsInstance<GraphNode.TableNode>().single().summary.metadataKeptApartAt }, "a table whose metadata sits under its location lists no such row")
    }

    /**
     * `pid` is `dv` with its vectors exported as Iceberg v3 deletion vectors —
     * `deletion-vectors.bitmap64` and `metadata.iceberg.format-version = 3`, which is
     * `needAddDvToIceberg` at 1.3.1 (`docs/fixtures/paimon-pid.sql`). Three things it settles.
     * The export's DELETES manifest holds one `puffin` entry per vector whose container is
     * Paimon's own index file at the range the index manifest records, and Iceberg's own blob
     * reader decodes it — cardinality, positions and CRC — so the coordinates Paimon records
     * for a bitmap64 vector are Iceberg's. With the vectors as Iceberg's, the level rule is
     * `level > 0`: the two files the compactions moved to levels 5 and 4 are both exported,
     * and the level-0 file of `-D` rows the last compaction removed is in neither table. And
     * **two vectors in one container are two delete files**: keyed by path alone the ledger
     * counted the second as a duplicate of the first — one delete file and one position
     * delete where the export has two and three — which every Iceberg fixture had hidden by
     * holding one vector per Puffin file.
     */
    @Test
    fun `a bitmap64 table's vectors are exported as Iceberg v3 deletion vectors that Iceberg's reader decodes, two to one container`() {
        val pid = FixtureCatalog.paimonModel("pid")
        val check = assertNotNull(pid.checkIcebergExport())
        assertTrue(check.agrees, "$check")
        assertTrue(check.vectorsExported && check.aboveLevelZero)
        assertEquals(6L, check.currentIcebergSnapshotId)
        assertEquals(setOf(5, 4), check.paimonFiles.values.toSet(), "both live files above level 0, the -D file compacted away: ${check.paimonFiles}")
        assertEquals(check.paimonFiles.keys, check.icebergFiles, "under deletion vectors every file above level 0 is exported")
        assertEquals(emptySet(), check.belowExportedLevel + check.exportedBelowLevel)
        assertEquals(2, check.paimonVectors.size)
        assertEquals(check.paimonVectors, check.icebergVectors, "the export records each vector at the index manifest's range, with its cardinality")
        assertEquals(setOf(1L, 2L), check.icebergVectors.values.map { it.cardinality }.toSet(), "k = 2 in the first file; 1001 and 1500 in the second")
        assertTrue(check.icebergVectors.values.all { it.container.startsWith("index-") })
        assertTrue(check.describe.endsWith("an Iceberg reader sees 2 of the table's 2 live files; 2 of the table's 2 deletion vectors exported as Iceberg's"), check.describe)

        // Iceberg's own reading of the export: format version 3, a DELETES manifest of two
        // puffin entries, each decoding through the Puffin blob reader to the positions Paimon marked.
        val export = assertNotNull(pid.icebergExport)
        val newest = export.metadatas.last()
        assertEquals(3, newest.metadata.formatVersion)
        val current = newest.snapshots.single { it.metadata.snapshotId == 6L }
        val vectors = current.manifests.filter { it.metadata.content == ManifestContent.DELETES }.flatMap { it.dataFiles }
        assertEquals(2, vectors.size)
        val decoded = vectors.associate { entry ->
            val df = assertNotNull(entry.metadata.dataFile)
            assertEquals("puffin", df.fileFormat)
            val vector = service.PuffinReader.readDeletionVector(entry.path, assertNotNull(df.contentOffset), assertNotNull(df.contentSizeInBytes), df.referencedDataFile, df.recordCount)
            assertTrue(vector.checksumMatches && vector.cardinalityAgrees, vector.toString())
            df.recordCount to vector.positions
        }
        assertEquals(mapOf<Long?, List<Long>>(1L to listOf(1L), 2L to listOf(0L, 499L)), decoded, "position 1 of the first file is k = 2; positions 0 and 499 of the second are 1001 and 1500")

        val live = liveFilesOf(current)
        assertEquals(2, live.count { it.content == DataFileContent.POSITION_DELETES }, "two vectors in one container are two delete files")
        assertEquals(3L, live.filter { it.content == DataFileContent.POSITION_DELETES }.sumOf { it.recordCount })
        val summary = service.IcebergGraphBuilder.buildGraph(export).nodes.filterIsInstance<GraphNode.TableNode>().single().summary
        assertEquals(2, summary.current.deleteFileCount)
        assertEquals(3L, summary.current.deleteRecordCount)

        // And paired, looked up and counted as two: the pairing keys a delete by container and
        // referenced file, each vector reaches exactly its own data file, the lookup's cache of
        // decoded vectors is keyed the same way, and the live count is the export's 1,497 rows.
        val reach = deleteReach(current)
        assertEquals(2, reach.size)
        assertEquals(check.icebergVectors.keys, reach.map { it.reaches.single().substringAfterLast('/') }.toSet())
        assertTrue(reach.all { it.mayReach.isEmpty() && it.deleteKey.contains('#') })
        val input = assertNotNull(export.rowLookupInput())
        assertEquals(2, input.deleteFiles.size)
        // One lookup across both files, so the vector decoded for the first file's hit is in the
        // cache when the second file's hit is decided — keyed by container alone it answered
        // the first vector's positions for the second, and 1001 at position 0 read as live.
        val filter = (parseScanFilter("k IN (2, 1001, 1499, 1500)") as ScanFilterParse.Parsed).filter
        val fates = service.RowLookup.lookup(input, filter, emptySet()).hits.associate { (it.cells["k"] as Number).toInt() to it.fate }
        assertEquals(mapOf(2 to RowFate.VECTOR_DELETED, 1001 to RowFate.VECTOR_DELETED, 1499 to RowFate.LIVE, 1500 to RowFate.VECTOR_DELETED), fates)
        val count = service.LiveRowCount.count(input)
        assertEquals(1497L, count.live, count.toString())
    }

    /**
     * `pil` is `pic` compacted, and no vectors (`docs/fixtures/paimon-pil.sql`): the writer's
     * compactions leave a 1,004-row file at level 5 and a four-row file at level 4, and the
     * level rule — `level == num-levels - 1` — lists the first and leaves the second out. `pic`
     * reaches the rule with level-0 files alone, which `level > 0` (the rule under exported
     * vectors) leaves out just the same, so this is the table that tells the two apart. The
     * script printed both readers' answers: Paimon reads 1,006 rows with k 1 and 2 at their
     * updated values, and Iceberg's own reader reads 1,004 with the old ones — the level-4
     * file's two new keys absent and its two updates unseen — which the app's read of the
     * export reproduces. The rebuild path's level-0 file of snapshot 1 is gone from the export
     * too: the compaction's `DELETE` entries went through the incremental path and removed it.
     */
    @Test
    fun `a compacted table without vectors exports its level-5 file and not its level-4 one, and Iceberg reads the rows accordingly`() {
        val pil = FixtureCatalog.paimonModel("pil")
        val check = assertNotNull(pil.checkIcebergExport())
        assertTrue(check.current && check.atTable && !check.vectorsExported && !check.aboveLevelZero, "$check")
        assertEquals(11L, check.currentIcebergSnapshotId)
        assertEquals(1, check.versions)
        assertEquals(5, check.exportedLevel)
        val top = "data-7f316ab1-ff1c-4cdd-819f-7a0239731ee2-0.parquet"
        val below = "data-00bb8e9e-e2b1-4230-9fda-bfba4899abc1-0.parquet"
        assertEquals(mapOf(top to 5, below to 4), check.paimonFiles, "the two compactions' outputs, and nothing at level 0")
        assertEquals(setOf(top), check.icebergFiles, "the level-5 file alone; the rebuilt level-0 file of snapshot 1 was removed by the first compaction")
        assertEquals(setOf(below), check.belowExportedLevel)
        assertEquals(emptySet(), check.exportedBelowLevel)
        assertEquals(emptySet(), check.missingFromIceberg + check.extraInIceberg, "each absence is explained by the level rule")
        assertTrue(check.describe.endsWith("an Iceberg reader sees 1 of the table's 2 live files"), check.describe)

        // The export read as Iceberg reads it: 1,004 rows, k 1 and 2 at the values the level-5
        // file holds, 1005 and 1006 nowhere — the script's own Iceberg read printed the same.
        val export = assertNotNull(pil.icebergExport)
        val input = assertNotNull(export.rowLookupInput())
        assertEquals(listOf(top), input.dataFiles.map { it.recordedPath.substringAfterLast('/') })
        assertEquals(1004L, service.LiveRowCount.count(input).live)
        val filter = (parseScanFilter("k IN (1, 2, 1005, 1006)") as ScanFilterParse.Parsed).filter
        val hits = service.RowLookup.lookup(input, filter, emptySet()).hits
        assertEquals(mapOf(1 to "v1", 2 to "v2"), hits.associate { (it.cells["k"] as Number).toInt() to it.cells["v"] })
        assertTrue(hits.all { it.fate == RowFate.LIVE })
    }

    /** The rule alone, on paths: every storage type but `table-location` goes to catalog storage unless told otherwise, and only from under a `<db>.db` directory. */
    @Test
    fun `the export's directory follows the storage type, the storage-location override, and the db suffix`() {
        val table = java.nio.file.Paths.get("/wh/db.db/t")
        assertEquals(null, icebergExportPathOf(table, emptyMap()))
        assertEquals(null, icebergExportPathOf(table, mapOf(ICEBERG_STORAGE_KEY to "disabled")))
        assertEquals(table, icebergExportPathOf(table, mapOf(ICEBERG_STORAGE_KEY to "table-location")))
        val catalog = java.nio.file.Paths.get("/wh/iceberg/db/t")
        assertEquals(catalog, icebergExportPathOf(table, mapOf(ICEBERG_STORAGE_KEY to "hadoop-catalog")))
        assertEquals(catalog, icebergExportPathOf(table, mapOf(ICEBERG_STORAGE_KEY to "hive-catalog")))
        assertEquals(catalog, icebergExportPathOf(table, mapOf(ICEBERG_STORAGE_KEY to "rest-catalog")))
        assertEquals(table, icebergExportPathOf(table, mapOf(ICEBERG_STORAGE_KEY to "hive-catalog", ICEBERG_STORAGE_LOCATION_KEY to "table-location")))
        assertEquals(catalog, icebergExportPathOf(table, mapOf(ICEBERG_STORAGE_KEY to "table-location", ICEBERG_STORAGE_LOCATION_KEY to "catalog-storage")))
        assertEquals(null, icebergExportPathOf(java.nio.file.Paths.get("/wh/plain/t"), mapOf(ICEBERG_STORAGE_KEY to "hadoop-catalog")), "the callback refuses a parent that is not <db>.db")
    }

    @Test
    fun `an append table exports every file, and a file the export lacks is then a disagreement`() {
        val check = IcebergExportCheck("/t", true, "table-location", 1, 2, 2, icebergFiles = setOf("a"), paimonFiles = mapOf("a" to 0, "b" to 0), exportedLevel = null)
        assertEquals(setOf("b"), check.missingFromIceberg)
        assertEquals(emptySet(), check.belowExportedLevel)
        assertEquals(emptySet(), check.exportedBelowLevel, "an append table exports every level")
        val dv = IcebergExportCheck("/t", true, "table-location", 1, 2, 2, icebergFiles = setOf("a"), paimonFiles = mapOf("a" to 3, "b" to 0, "c" to 1), exportedLevel = 5, aboveLevelZero = true)
        assertEquals(setOf("b"), dv.belowExportedLevel)
        assertEquals(setOf("c"), dv.missingFromIceberg, "above level 0 and not exported")
        assertEquals(emptySet(), dv.exportedBelowLevel)
        val behind = IcebergExportCheck("/t", true, "table-location", 1, 1, 2, icebergFiles = emptySet(), paimonFiles = emptyMap())
        assertTrue(!behind.current)
    }
}
