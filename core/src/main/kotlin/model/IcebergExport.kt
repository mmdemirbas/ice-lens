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
    /** Metadata versions under `metadata/` — `metadata.iceberg.previous-versions-max` keeps one by default. */
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
