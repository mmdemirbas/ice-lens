package model

/**
 * The Iceberg metadata a Paimon table writes beside its own under
 * `metadata.iceberg.storage = table-location`, checked against the table it exports: whether the
 * export's current snapshot is the table's latest, and which of the table's live files an Iceberg
 * reader sees.
 *
 * Paimon writes the export from the commit callback (`IcebergCommitCallback` at 1.3.1), so the
 * snapshot should be current after every commit — and **which files it lists depends on which of
 * the callback's two paths ran**, which is the thing a reader cannot get from the level rule
 * alone. `createMetadata` takes the incremental path when the metadata for `snapshotId - 1` is
 * there, and rebuilds from scratch when it is not — the first snapshot, a format-version change,
 * or a base the export's own expiry removed.
 *
 * - **Incremental** (`createMetadataWithBase`): the commit's delta entries through
 *   `shouldAddFileToIceberg` — an append table exports every file; a primary-key table exports a
 *   file only at the highest level (`level == num-levels - 1`), or at any level above 0 under
 *   deletion vectors, whose vectors go out as Iceberg's. An Iceberg reader merges nothing, so a
 *   lower level's records may be shadowed by a higher level's.
 * - **Rebuild** (`createMetadataWithoutBase`): the snapshot read through `newSnapshotReader`, every
 *   file of every **raw-convertible** split — the splits a read returns without merging — whatever
 *   its level.
 *
 * `pic` holds both in one table: two appends into one bucket of a primary-key table, neither
 * compacted. Snapshot 1 took the rebuild path and its level-0 file is in the export because its
 * split is raw convertible; snapshot 2 took the incremental path and its level-0 file is not,
 * being below level 5. So an Iceberg reader sees one of the table's two live files, and neither
 * half of that is a disagreement — [belowExportedLevel] and [exportedBelowLevel] name each,
 * leaving [missingFromIceberg] and [extraInIceberg] for what no rule explains.
 */
/**
 * A deletion vector as one side records it: the file holding the blob, its coordinates, and
 * the cardinality — Paimon's index manifest range, or the export's `POSITION_DELETES` entry with
 * `content_offset` / `content_size_in_bytes` and `record_count`. The two are one vector when
 * every figure agrees, which is what makes the export's vector readable as Iceberg's.
 */
data class ExportedVector(val container: String, val offset: Long, val length: Long, val cardinality: Long?)

/**
 * What the export does with one file — the verdict column of the section's table, ordered so
 * the disagreements lead: a live file the rule says is exported and is not, a listed file the
 * table no longer holds live, then the rule's own absences, then a file the rebuild path listed
 * below the level, then the ordinary case.
 */
enum class ExportFileFate(val label: String) {
    MISSING("NOT EXPORTED"),
    NOT_LIVE("NOT LIVE HERE"),
    BELOW_LEVEL("not exported — level rule"),
    REBUILT("exported — rebuild path"),
    EXPORTED("exported"),
    ;

    /** The two the rule does not explain. */
    val disagrees: Boolean get() = this == MISSING || this == NOT_LIVE
}

/** One of the table's live files with its level, or a file the export lists that is not live (level null), and the export's verdict on it. */
data class ExportedFileVerdict(val file: String, val level: Int?, val fate: ExportFileFate)

data class IcebergExportCheck(
    /** The directory whose `metadata/` holds the export — the table's own, or the catalog-storage directory beside the warehouse. */
    val exportPath: String,
    /** Whether [exportPath] is the table's own directory (`table-location`), or a directory of its own beside the warehouse. */
    val atTable: Boolean,
    /** `metadata.iceberg.storage` as the latest schema records it; null where the option is not set (an export left by an option since removed). */
    val storage: String?,
    /** Metadata versions under `metadata/` — one by default: `metadata.iceberg.previous-versions-max` is 0 and `delete-after-commit.enabled` is on, so each commit deletes the versions before its own. */
    val versions: Int,
    val currentIcebergSnapshotId: Long?,
    val latestPaimonSnapshotId: Long?,
    /** Data file names the export's current snapshot lists live — its delete files are [icebergVectors]. */
    val icebergFiles: Set<String>,
    /** The table's latest snapshot's live files by name, with the level each is at. */
    val paimonFiles: Map<String, Int?>,
    /**
     * The export's live deletion vectors by the data file each references — written only under
     * `deletion-vectors.bitmap64` and format version 3, as `puffin` entries whose container is
     * Paimon's own index file (`pid`).
     */
    val icebergVectors: Map<String, ExportedVector> = emptyMap(),
    /** The table's vectors as its latest index manifest records them, by data file. */
    val paimonVectors: Map<String, ExportedVector> = emptyMap(),
    /** Whether the table's vectors go out as Iceberg's: `deletion-vectors.enabled`, `deletion-vectors.bitmap64` and `metadata.iceberg.format-version = 3` (`needAddDvToIceberg` at 1.3.1). */
    val vectorsExported: Boolean = false,
    /** On a primary-key table, the level a file has to be at to be exported; null on an append table, which exports every file. */
    val exportedLevel: Int? = null,
    /** With deletion vectors, any level above 0 is exported instead of the highest alone. */
    val aboveLevelZero: Boolean = false,
    val readErrors: List<UnifiedReadError> = emptyList(),
) {
    val current: Boolean get() = currentIcebergSnapshotId != null && currentIcebergSnapshotId == latestPaimonSnapshotId

    /**
     * Every live file with the export's verdict on it, and every file the export lists that is
     * not live — the one reading the four sets below are filters of, so a file cannot be in two.
     * Disagreements first, then the rule's absences, then the rebuilt, then the ordinary; within
     * a fate the highest level first, then by name.
     */
    val fileVerdicts: List<ExportedFileVerdict>
        get() {
            val live = paimonFiles.map { (name, level) ->
                val listed = name in icebergFiles
                val fate = when {
                    listed && exportsLevel(level) -> ExportFileFate.EXPORTED
                    listed -> ExportFileFate.REBUILT
                    exportsLevel(level) -> ExportFileFate.MISSING
                    else -> ExportFileFate.BELOW_LEVEL
                }
                ExportedFileVerdict(name, level, fate)
            }
            val extra = (icebergFiles - paimonFiles.keys).map { ExportedFileVerdict(it, null, ExportFileFate.NOT_LIVE) }
            return (live + extra).sortedWith(compareBy<ExportedFileVerdict> { it.fate.ordinal }.thenByDescending { it.level ?: -1 }.thenBy { it.file })
        }

    private fun filesWith(fate: ExportFileFate): Set<String> = fileVerdicts.filter { it.fate == fate }.map { it.file }.toSet()

    /** Live files the export does not list because the level rule leaves them out — expected, and said. */
    val belowExportedLevel: Set<String> get() = filesWith(ExportFileFate.BELOW_LEVEL)

    /** Live files the export does not list and the rule does not explain — the disagreement. */
    val missingFromIceberg: Set<String> get() = filesWith(ExportFileFate.MISSING)

    /**
     * Live files the export lists that the level rule would not have added — the rebuild path's,
     * and the reason a file below the exported level can be in the export all the same.
     */
    val exportedBelowLevel: Set<String> get() = filesWith(ExportFileFate.REBUILT)

    /** Files the export lists that the table no longer holds live. */
    val extraInIceberg: Set<String> get() = filesWith(ExportFileFate.NOT_LIVE)

    /** Data files the table has a vector for and the export does not, where the vectors go out as Iceberg's. */
    val vectorsMissingFromIceberg: Set<String> get() = if (vectorsExported) paimonVectors.keys - icebergVectors.keys else emptySet()

    /** Data files the export has a vector for and the table does not. */
    val vectorsExtraInIceberg: Set<String> get() = icebergVectors.keys - paimonVectors.keys

    /** Data files whose vector the two sides record with different coordinates or cardinality. */
    val vectorsDisagreeing: Set<String>
        get() = icebergVectors.filter { (file, v) -> paimonVectors[file]?.let { it != v } == true }.keys

    /** Whether nothing disagrees — the files, and the vectors where they are exported. */
    val agrees: Boolean
        get() = current && missingFromIceberg.isEmpty() && extraInIceberg.isEmpty() &&
            vectorsMissingFromIceberg.isEmpty() && vectorsExtraInIceberg.isEmpty() && vectorsDisagreeing.isEmpty() && readErrors.isEmpty()

    /** Live files the export lists — what an Iceberg reader sees of the table. */
    val seen: Int get() = paimonFiles.keys.count { it in icebergFiles }

    /**
     * The check in one sentence, shared by both shells so the two cannot spell it differently:
     * whether the export is current, how many versions are on disk, and what an Iceberg reader
     * sees of the table's live files.
     */
    val describe: String
        get() {
            val head = if (current) "current: the export's snapshot $currentIcebergSnapshotId is the table's latest" else
                "behind: the export's snapshot is ${currentIcebergSnapshotId ?: "none"}, the table's latest ${latestPaimonSnapshotId ?: "none"}"
            val where = if (atTable) "under metadata/" else "at $exportPath/metadata/"
            val versionsText = "%,d metadata %s $where".format(versions, if (versions == 1) "version" else "versions")
            val files = "an Iceberg reader sees %,d of the table's %,d live %s".format(seen, paimonFiles.size, if (paimonFiles.size == 1) "file" else "files")
            val vectors = when {
                icebergVectors.isNotEmpty() || paimonVectors.isNotEmpty() ->
                    "; %,d of the table's %,d deletion %s exported as Iceberg's".format(icebergVectors.size, paimonVectors.size, if (paimonVectors.size == 1) "vector" else "vectors")
                else -> ""
            }
            return "$head; $versionsText; $files$vectors"
        }

    private fun exportsLevel(level: Int?): Boolean = when {
        exportedLevel == null -> true
        aboveLevelZero -> (level ?: 0) > 0
        else -> level == exportedLevel
    }
}

/** The check, or null where the table carries no Iceberg export. */
fun PaimonUnifiedTableModel.checkIcebergExport(): IcebergExportCheck? {
    val export = icebergExport ?: return null
    val newest = export.metadatas.lastOrNull()
    val currentId = newest?.metadata?.currentSnapshotId
    val current = newest?.snapshots?.firstOrNull { it.metadata.snapshotId == currentId }
    val live = current?.let { liveFilesOf(it) }.orEmpty()
    val icebergFiles = live.filter { it.content == DataFileContent.DATA }.map { it.path.substringAfterLast('/') }.toSet()
    // The export's vectors, off the entries the live set kept: a vector's live key is its
    // container with the data file it references, and its coordinates are on the entry.
    val liveKeys = live.map { normalizeFilePath(it.path) }.toSet()
    val icebergVectors = current?.manifests.orEmpty().flatMap { it.dataFiles }
        .filter { it.metadata.status != ManifestEntryStatus.DELETED && it.metadata.dataFile?.contentOffset != null }
        .filter { normalizeFilePath(it.metadata.dataFile?.filePath.orEmpty()) in liveKeys }
        .mapNotNull { entry ->
            val df = entry.metadata.dataFile ?: return@mapNotNull null
            val referenced = df.referencedDataFile?.substringAfterLast('/') ?: return@mapNotNull null
            referenced to ExportedVector(df.filePath.orEmpty().substringAfterLast('/'), df.contentOffset ?: return@mapNotNull null, df.contentSizeInBytes ?: return@mapNotNull null, df.recordCount)
        }.toMap()
    val latest = snapshots.maxByOrNull { it.metadata.id ?: Long.MIN_VALUE }
    val paimonFiles = replayPaimonSnapshot(latest).liveFiles.entries.associate { (name, file) -> name.substringAfterLast('/') to file?.level }
    val paimonVectors = latest?.let { vectorRangesOf(path, listOf(it)) }.orEmpty()
        .filterKeys { it in paimonFiles }
        .mapValues { (_, r) -> ExportedVector(r.indexFileName, r.offset, r.length, r.cardinality) }
    val schema = latest?.schema ?: schemas.maxByOrNull { it.id ?: -1 }
    val primaryKey = schema?.primaryKeys.orEmpty().isNotEmpty()
    val options = schema?.options.orEmpty()
    val vectorsOn = options["deletion-vectors.enabled"]?.toBoolean() == true
    val vectorsExported = vectorsOn && options["deletion-vectors.bitmap64"]?.toBoolean() == true && options["metadata.iceberg.format-version"] == "3"
    return IcebergExportCheck(
        exportPath = export.path.toString(),
        atTable = export.path.toAbsolutePath().normalize() == path.toAbsolutePath().normalize(),
        storage = options[ICEBERG_STORAGE_KEY],
        versions = export.metadatas.size,
        currentIcebergSnapshotId = currentId,
        latestPaimonSnapshotId = latest?.metadata?.id,
        icebergFiles = icebergFiles,
        paimonFiles = paimonFiles,
        icebergVectors = icebergVectors,
        paimonVectors = paimonVectors,
        vectorsExported = vectorsExported,
        exportedLevel = if (primaryKey) PaimonCompactionOptions.from(options).numLevels - 1 else null,
        // `shouldAddFileToIceberg` takes `level > 0` under `needAddDvToIceberg`, which is the
        // vectors going out as Iceberg's — not deletion vectors alone.
        aboveLevelZero = primaryKey && vectorsExported,
        readErrors = export.readErrors,
    )
}

const val ICEBERG_STORAGE_KEY = "metadata.iceberg.storage"
const val ICEBERG_STORAGE_LOCATION_KEY = "metadata.iceberg.storage-location"

/**
 * Where `metadata.iceberg.storage` puts the export, from the table's options, the way
 * `IcebergCommitCallback.catalogTableMetadataPath` decides it at 1.3.1 — or null where the
 * option is off or the layout rules the export out.
 *
 * `table-location` writes under the table's own `metadata/`. Every other storage type —
 * `hadoop-catalog`, `hive-catalog`, `rest-catalog` — infers *catalog storage* unless
 * `metadata.iceberg.storage-location` says otherwise, and catalog storage is
 * `<warehouse>/iceberg/<db>/<table>`, where the table's parent has to be `<db>.db` (the callback
 * refuses any other parent): a directory beside the warehouse's databases that an Iceberg
 * HadoopCatalog at `<warehouse>/iceberg` lists as `<db>.<table>`. The returned path is the
 * directory whose `metadata/` holds the export, which is what [PaimonUnifiedTableModel.icebergExportPath]
 * opens as an Iceberg table.
 */
fun icebergExportPathOf(tablePath: java.nio.file.Path, options: Map<String, String>): java.nio.file.Path? {
    val storage = options[ICEBERG_STORAGE_KEY]?.lowercase()?.takeIf { it != "disabled" } ?: return null
    val location = options[ICEBERG_STORAGE_LOCATION_KEY]?.lowercase()
        ?: if (storage == "table-location") "table-location" else "catalog-storage"
    if (location == "table-location") return tablePath
    val dbDir = tablePath.parent ?: return null
    val dbName = dbDir.fileName?.toString()?.removeSuffix(".db")?.takeIf { it != dbDir.fileName.toString() } ?: return null
    val warehouse = dbDir.parent ?: return null
    return warehouse.resolve("iceberg").resolve(dbName).resolve(tablePath.fileName.toString())
}
