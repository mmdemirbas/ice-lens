package model

import org.slf4j.LoggerFactory
import service.IcebergReader
import service.SampleRowReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.streams.asSequence

private val logger = LoggerFactory.getLogger("model.UnifiedModel")

data class UnifiedReadError(
    val stage: String,
    val path: String,
    val message: String,
    val stackTrace: String? = null,
)

private fun toError(stage: String, path: Path, throwable: Throwable): UnifiedReadError {
    val msg = throwable.message ?: (throwable::class.simpleName ?: "Unknown error")
    return UnifiedReadError(
        stage = stage,
        path = path.toString(),
        message = msg,
        stackTrace = throwable.stackTraceToString(),
    )
}

fun UnifiedWarehouseModel(warehousePath: Path): UnifiedWarehouseModel {
    return UnifiedWarehouseModel(
        path = warehousePath,
        tables = Files
            .list(warehousePath)
            .filter { Files.isDirectory(it) }
            .toList()
            .associate { tablePath ->
                val table = UnifiedTableModel(tablePath)
                table.name to table
            },
    )
}

fun UnifiedTableModel(tablePath: Path): UnifiedTableModel {
    logger.info("Loading Iceberg table: {}", tablePath)
    val metadataDir = tablePath.resolve("metadata")
    val tableReadErrors = mutableListOf<UnifiedReadError>()

    val metadataFiles = runCatching {
        Files
            .list(metadataDir)
            .asSequence()
            .filter { Files.isRegularFile(it) }
            .filter { it.fileName.toString().endsWith(".metadata.json") }
            .toList()
    }.getOrElse { e ->
        tableReadErrors += toError("list-metadata-files", metadataDir, e)
        emptyList()
    }

    val parsedMetadata = mutableListOf<Pair<Path, TableMetadata>>()
    metadataFiles.forEach { metadataPath ->
        runCatching {
            IcebergReader.readTableMetadata(metadataPath.toString())
        }.onSuccess { metadata ->
            parsedMetadata += metadataPath to metadata
        }.onFailure { e ->
            tableReadErrors += toError("read-metadata-json", metadataPath, e)
        }
    }

    // Parse snapshots once to avoid duplicate parsing of the same snapshots
    val parsedSnapshots = parsedMetadata
        .map { it.second }
        .flatMap { it.snapshots }
        .filter { it.snapshotId != null }
        .distinctBy { it.snapshotId }
        .associate { snapshot ->
            snapshot.snapshotId to UnifiedSnapshot(
                resolveForceRelative(metadataDir, snapshot.manifestList),
                snapshot,
            )
        }

    val versionHintPath = metadataDir.resolve("version-hint.text")
    val versionHint = runCatching { versionHintPath.readText().trim() }
        .getOrElse { e ->
            tableReadErrors += toError("read-version-hint", versionHintPath, e)
            "N/A"
        }

    val orderedMetadatas = parsedMetadata.map { (path, metadata) ->
            UnifiedMetadata(
                path = path,
                metadata = metadata,
                rawJson = runCatching { path.readText() }.getOrNull(),
                snapshots = metadata.snapshots
                    .mapNotNull { parsedSnapshots[it.snapshotId] }
                    .sortedBy { it.metadata.timestampMs },
            )
        }.sortedWith(
            compareBy<UnifiedMetadata>(
                { metadataVersionFromFileName(it.path.fileName.toString()) == null },
                { metadataVersionFromFileName(it.path.fileName.toString()) ?: Int.MAX_VALUE },
                { it.metadata.lastUpdatedMs ?: Long.MAX_VALUE },
                { runCatching { Files.getLastModifiedTime(it.path).toMillis() }.getOrDefault(Long.MAX_VALUE) },
                { it.path.fileName.toString() },
            )
        )

    val totalSnapshots = orderedMetadatas.sumOf { it.snapshots.size }
    logger.info("  Iceberg table loaded: {} metadata files, {} snapshots, {} errors",
        orderedMetadatas.size, totalSnapshots, tableReadErrors.size)
    if (tableReadErrors.isNotEmpty()) {
        tableReadErrors.forEach { err -> logger.warn("  Read error [{}] {}: {}", err.stage, err.path, err.message) }
    }

    return UnifiedTableModel(
        path = tablePath,
        name = tablePath.fileName.toString(),
        versionHint = versionHint,
        metadatas = orderedMetadatas,
        readErrors = tableReadErrors,
    )
}

fun resolveForceRelative(start: Path, pathToTakeOnlyLastPart: String?): Path {
    // Get the last part of the path and resolve it relative to start. Fall back to the start directory.
    val tail = pathToTakeOnlyLastPart
        ?.removeSuffix("/")
        ?.substringAfterLast("/")
        ?.takeIf { it.isNotBlank() }
        ?: return start
    return start.resolve(tail)
}

fun UnifiedSnapshot(snapshotPath: Path, snapshot: Snapshot): UnifiedSnapshot {
    val manifestListResult = runCatching { IcebergReader.readManifestList(snapshotPath.toString()) }
    val snapshotReadErrors = mutableListOf<UnifiedReadError>()
    val manifestList = manifestListResult.getOrElse { e ->
        snapshotReadErrors += toError("read-manifest-list-file", snapshotPath, e)
        IcebergReader.ReadResult(entries = emptyList())
    }
    snapshotReadErrors += manifestList.errors.map { error ->
        UnifiedReadError(
            stage = "decode-manifest-list-entry",
            path = snapshotPath.toString(),
            message = error.message,
            stackTrace = error.stackTrace,
        )
    }
    return UnifiedSnapshot(
        path = snapshotPath,
        metadata = snapshot,
        manifests = manifestList.entries.map { manifest ->
            val metadataDir = snapshotPath.parent
            UnifiedManifest(
                resolveForceRelative(metadataDir, manifest.manifestPath),
                manifest,
            )
        },
        readErrors = snapshotReadErrors,
    )
}

fun UnifiedManifest(manifestPath: Path, manifest: ManifestListEntry): UnifiedManifest {
    val manifestFileResult = runCatching { IcebergReader.readManifestFile(manifestPath.toString()) }
    val manifestReadErrors = mutableListOf<UnifiedReadError>()
    val dataFiles = manifestFileResult.getOrElse { e ->
        manifestReadErrors += toError("read-manifest-file", manifestPath, e)
        IcebergReader.ReadResult(entries = emptyList())
    }
    manifestReadErrors += dataFiles.errors.map { error ->
        UnifiedReadError(
            stage = "decode-manifest-entry",
            path = manifestPath.toString(),
            message = error.message,
            stackTrace = error.stackTrace,
        )
    }
    val dataRoot = manifestPath.parent?.parent ?: manifestPath.parent ?: manifestPath
    val normalizedDataRoot = runCatching { dataRoot.toAbsolutePath().normalize() }.getOrElse { dataRoot.normalize() }

    return UnifiedManifest(
        path = manifestPath,
        metadata = manifest,
        dataFiles = dataFiles.entries.map { dataFile ->
            val metadataDirPrefix = manifest.manifestPath.orEmpty().substringBeforeLast('/')
            val tableDirPrefix = metadataDirPrefix.substringBeforeLast('/')
            val dataFilePathInFile = dataFile.dataFile?.filePath.orEmpty()
            val dataFilePathRelative =
                dataFilePathInFile.removePrefix(tableDirPrefix).removePrefix("/")
            val dataFilePathResolved = dataRoot.resolve(dataFilePathRelative)

            // Validate resolved path stays within the table directory tree
            val normalizedResolved = runCatching { dataFilePathResolved.toAbsolutePath().normalize() }
                .getOrElse { dataFilePathResolved.normalize() }
            if (!normalizedResolved.startsWith(normalizedDataRoot)) {
                manifestReadErrors += UnifiedReadError(
                    stage = "path-traversal-check",
                    path = dataFilePathInFile,
                    message = "Data file path resolves outside the table directory: $normalizedResolved",
                )
            }

            UnifiedDataFile(path = dataFilePathResolved, metadata = dataFile)
        },
        readErrors = manifestReadErrors,
    )
}

data class UnifiedWarehouseModel(
    val path: Path,
    val tables: Map<String, UnifiedTableModel>,
)

data class UnifiedTableModel(
    override val path: Path,
    override val name: String,
    val versionHint: String,
    val metadatas: List<UnifiedMetadata>,
    override val readErrors: List<UnifiedReadError> = emptyList(),
) : FormatTableModel {
    override val format get() = service.TableFormat.ICEBERG
}

data class UnifiedMetadata(
    val path: Path,
    val metadata: TableMetadata,
    val rawJson: String? = null,
    val snapshots: List<UnifiedSnapshot>,
)

data class UnifiedSnapshot(
    val path: Path,
    val metadata: Snapshot,
    val manifests: List<UnifiedManifest>,
    val readErrors: List<UnifiedReadError> = emptyList(),
)

data class UnifiedManifest(
    val path: Path,
    val metadata: ManifestListEntry,
    val dataFiles: List<UnifiedDataFile>,
    val readErrors: List<UnifiedReadError> = emptyList(),
)

data class UnifiedDataFile(
    val path: Path,
    val metadata: ManifestEntry,
    private val rowsLoader: () -> List<UnifiedRow> = {
        SampleRowReader.querySampleRows(path.toString()).map { UnifiedRow(it) }
    },
) {
    val rows: List<UnifiedRow> by lazy { rowsLoader() }
}

data class UnifiedRow(
    val cells: Map<String, Any>,
)
