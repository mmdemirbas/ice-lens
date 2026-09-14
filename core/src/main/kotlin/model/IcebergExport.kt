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
    /** File names the export's current snapshot lists live. */
    val icebergFiles: Set<String>,
    /** The table's latest snapshot's live files by name, with the level each is at. */
    val paimonFiles: Map<String, Int?>,
    /** On a primary-key table, the level a file has to be at to be exported; null on an append table, which exports every file. */
    val exportedLevel: Int? = null,
    /** With deletion vectors, any level above 0 is exported instead of the highest alone. */
    val aboveLevelZero: Boolean = false,
    val readErrors: List<UnifiedReadError> = emptyList(),
) {
    val current: Boolean get() = currentIcebergSnapshotId != null && currentIcebergSnapshotId == latestPaimonSnapshotId

    /** Live files the export does not list because the level rule leaves them out — expected, and said. */
    val belowExportedLevel: Set<String>
        get() = paimonFiles.filter { (name, level) -> name !in icebergFiles && !exportsLevel(level) }.keys

    /** Live files the export does not list and the rule does not explain — the disagreement. */
    val missingFromIceberg: Set<String>
        get() = paimonFiles.filter { (name, level) -> name !in icebergFiles && exportsLevel(level) }.keys

    /**
     * Live files the export lists that the level rule would not have added — the rebuild path's,
     * and the reason a file below the exported level can be in the export all the same.
     */
    val exportedBelowLevel: Set<String>
        get() = paimonFiles.filter { (name, level) -> name in icebergFiles && !exportsLevel(level) }.keys

    /** Files the export lists that the table no longer holds live. */
    val extraInIceberg: Set<String> get() = icebergFiles - paimonFiles.keys

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
            return "$head; $versionsText; $files"
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
    val icebergFiles = current?.let { liveFilesOf(it) }.orEmpty().map { it.path.substringAfterLast('/') }.toSet()
    val latest = snapshots.maxByOrNull { it.metadata.id ?: Long.MIN_VALUE }
    val paimonFiles = replayPaimonSnapshot(latest).liveFiles.entries.associate { (name, file) -> name.substringAfterLast('/') to file?.level }
    val schema = latest?.schema ?: schemas.maxByOrNull { it.id ?: -1 }
    val primaryKey = schema?.primaryKeys.orEmpty().isNotEmpty()
    val options = schema?.options.orEmpty()
    return IcebergExportCheck(
        exportPath = export.path.toString(),
        atTable = export.path.toAbsolutePath().normalize() == path.toAbsolutePath().normalize(),
        storage = options[ICEBERG_STORAGE_KEY],
        versions = export.metadatas.size,
        currentIcebergSnapshotId = currentId,
        latestPaimonSnapshotId = latest?.metadata?.id,
        icebergFiles = icebergFiles,
        paimonFiles = paimonFiles,
        exportedLevel = if (primaryKey) PaimonCompactionOptions.from(options).numLevels - 1 else null,
        aboveLevelZero = primaryKey && options["deletion-vectors.enabled"]?.toBoolean() == true,
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
