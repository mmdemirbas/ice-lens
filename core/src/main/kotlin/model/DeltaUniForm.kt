package model

import java.nio.file.Files
import java.nio.file.Path

/**
 * UniForm: a Delta table that also writes Iceberg metadata, read the way delta-spark 3.2.1 with
 * `delta-iceberg` writes it (`duni`). Under `delta.universalFormat.enabledFormats = iceberg` — with
 * `delta.enableIcebergCompatV2`, which needs name-mode column mapping and refuses deletion
 * vectors — every commit is converted into an Iceberg commit under the table's own `metadata/`:
 * `<n>-<uuid>.metadata.json` versions, no version hint, format version 1, a manifest list and
 * manifests beside them, and the **data files the Delta log names, not copies** — the two formats
 * read the same Parquet files. Each metadata version records which Delta version it converted in
 * its properties (`delta-version`, `delta-timestamp`); no snapshot summary does.
 *
 * Three questions follow, and [DeltaUniFormCheck] answers them: is the export there at all where
 * the table says it is enabled; is it **behind** — the newest metadata's `delta-version` below the
 * latest Delta version, which a conversion that failed or ran asynchronously and was cut short
 * leaves, and which an Iceberg reader does not see; and does its current snapshot list **the same
 * files** as the Delta state at the version it converted — a file on one side only is a row one
 * reader returns and the other does not.
 */
val DeltaMetadata.icebergUniFormEnabled: Boolean
    get() = configuration["delta.universalFormat.enabledFormats"]?.split(',')?.any { it.trim().equals("iceberg", ignoreCase = true) } == true

data class DeltaUniFormCheck(
    /** `delta.universalFormat.enabledFormats` names `iceberg` in the latest metadata. */
    val enabled: Boolean,
    /** The newest Iceberg metadata file under `metadata/`; null where there is none. */
    val metadataFile: String?,
    /** Its `delta-version` property: the Delta version it converted. */
    val exportedVersion: Long?,
    val latestVersion: Long,
    val icebergSnapshotId: Long?,
    /** The Delta state's live files at [exportedVersion], as local paths. */
    val deltaFiles: Set<Path>,
    /** The Iceberg current snapshot's live data files, as local paths. */
    val icebergFiles: Set<Path>,
    /** Why a side could not be read; null where both were. */
    val readError: String? = null,
) {
    val exportPresent: Boolean get() = metadataFile != null
    val behind: Boolean get() = exportedVersion != null && exportedVersion < latestVersion
    val missingFromIceberg: List<Path> get() = (deltaFiles - icebergFiles).sorted()
    val extraInIceberg: List<Path> get() = (icebergFiles - deltaFiles).sorted()
    val filesAgree: Boolean get() = readError == null && exportedVersion != null && missingFromIceberg.isEmpty() && extraInIceberg.isEmpty()

    /** One sentence, the one both shells print. */
    fun describe(): String = when {
        !exportPresent && enabled -> "UniForm is enabled and there is no Iceberg metadata under metadata/ — an Iceberg reader finds no table"
        !exportPresent -> "no Iceberg metadata"
        readError != null -> "Iceberg metadata not read: $readError"
        exportedVersion == null -> "Iceberg metadata $metadataFile records no delta-version"
        !filesAgree -> "Iceberg snapshot $icebergSnapshotId and Delta version $exportedVersion DIFFER: " +
            "${missingFromIceberg.size} only in Delta, ${extraInIceberg.size} only in Iceberg"
        behind -> "an Iceberg reader sees version $exportedVersion of $latestVersion: the export is behind, its ${formatCounted(icebergFiles.size, "file")} those of version $exportedVersion"
        else -> "an Iceberg reader sees version $exportedVersion, the latest — the same ${formatCounted(icebergFiles.size, "file")}"
    }
}

/** The Iceberg metadata UniForm keeps under [tableRoot]'s `metadata/`, read as an Iceberg table; null where there is none. */
internal fun readDeltaIcebergExport(tableRoot: Path): UnifiedTableModel? {
    val dir = tableRoot.resolve("metadata")
    val hasMetadata = runCatching { Files.list(dir).use { s -> s.anyMatch { isMetadataFileName(it.fileName.toString()) } } }.getOrDefault(false)
    return if (hasMetadata) UnifiedTableModel(tableRoot) else null
}

/** The check of [icebergExport] against the log — see [DeltaUniFormCheck]. Null on a table with no version, or with neither UniForm nor metadata. */
fun DeltaUnifiedTableModel.uniFormCheck(export: UnifiedTableModel? = icebergExport): DeltaUniFormCheck? {
    val latest = latestVersion ?: return null
    val enabled = current?.metadata?.icebergUniFormEnabled == true
    if (export == null) return if (enabled) DeltaUniFormCheck(true, null, null, latest, null, emptySet(), emptySet()) else null
    val newest = export.metadatas.lastOrNull()
        ?: return DeltaUniFormCheck(enabled, null, null, latest, null, emptySet(), emptySet())
    val exported = newest.metadata.properties["delta-version"]?.toLongOrNull()
    val snapshotId = newest.metadata.currentSnapshotId
    fun local(p: Path) = p.toAbsolutePath().normalize()
    val snapshot = newest.snapshots.firstOrNull { it.metadata.snapshotId == snapshotId }
    val icebergFiles = snapshot?.manifests.orEmpty()
        .flatMap { it.dataFiles }
        .filter { it.metadata.status != ManifestEntryStatus.DELETED && (it.metadata.dataFile?.content ?: DataFileContent.DATA) == DataFileContent.DATA }
        .mapTo(mutableSetOf()) { local(it.path) }
    val deltaState = exported?.let { stateAt(it) }
    val deltaFiles = deltaState?.getOrNull()?.files?.values.orEmpty().mapNotNull { runCatching { local(resolve(it.path)) }.getOrNull() }.toSet()
    val error = when {
        snapshotId != null && snapshot == null -> "the current snapshot $snapshotId is not in ${newest.path.fileName}"
        snapshot != null && (snapshot.readErrors.isNotEmpty() || snapshot.manifests.any { it.readErrors.isNotEmpty() }) -> "the current snapshot's manifests could not all be read"
        deltaState?.isFailure == true -> "Delta version $exported cannot be rebuilt: ${deltaState.exceptionOrNull()?.message}"
        else -> null
    }
    return DeltaUniFormCheck(enabled, newest.path.fileName.toString(), exported, latest, snapshotId, deltaFiles, icebergFiles, error)
}
