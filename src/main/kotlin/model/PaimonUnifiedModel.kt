package model

import org.slf4j.LoggerFactory
import service.AvroReader
import service.PaimonReader
import service.SampleRowReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

private val logger = LoggerFactory.getLogger("model.PaimonUnifiedModel")

/** Aggregated Paimon table model linking snapshots, schemas, manifests, and data files. */
data class PaimonUnifiedTableModel(
    override val path: Path,
    override val name: String,
    val schemas: List<PaimonSchema>,
    val snapshots: List<PaimonUnifiedSnapshot>,
    override val readErrors: List<UnifiedReadError> = emptyList(),
) : FormatTableModel {
    override val format get() = service.TableFormat.PAIMON
}

/** A Paimon snapshot with its resolved manifest trees. */
data class PaimonUnifiedSnapshot(
    val path: Path,
    val metadata: PaimonSnapshot,
    val schema: PaimonSchema?,
    val baseManifests: List<PaimonUnifiedManifest>,
    val deltaManifests: List<PaimonUnifiedManifest>,
    val changelogManifests: List<PaimonUnifiedManifest>,
    val readErrors: List<UnifiedReadError> = emptyList(),
)

/** A Paimon manifest file with its resolved data file entries. */
data class PaimonUnifiedManifest(
    val path: Path,
    val metadata: PaimonManifestFileMeta,
    val entries: List<PaimonUnifiedDataFile>,
    val readErrors: List<UnifiedReadError> = emptyList(),
)

/** A single Paimon data file entry from a manifest. */
data class PaimonUnifiedDataFile(
    val path: Path,
    val metadata: PaimonManifestEntry,
    private val rowsLoader: () -> List<UnifiedRow> = {
        SampleRowReader.querySampleRows(path.toString()).map { UnifiedRow(it) }
    },
) {
    val rows: List<UnifiedRow> by lazy { rowsLoader() }
}

/**
 * Reads a Paimon table directory and builds a [PaimonUnifiedTableModel].
 *
 * Reads all schemas from `schema/`, all snapshots from `snapshot/`,
 * then resolves each snapshot's manifest lists and manifest files.
 */
fun PaimonUnifiedTableModel(tablePath: Path): PaimonUnifiedTableModel {
    logger.info("Loading Paimon table: {}", tablePath)
    val errors = mutableListOf<UnifiedReadError>()

    // 1. Read all schemas
    val schemaDir = tablePath.resolve("schema")
    val schemas = readSchemas(schemaDir, errors)
    val schemasById = schemas.associateBy { it.id }
    logger.info("  Schemas loaded: {}", schemas.size)

    // 2. Read all snapshots
    val snapshotDir = tablePath.resolve("snapshot")
    val snapshotFiles = listSnapshotFiles(snapshotDir, errors)
    logger.info("  Snapshot files found: {}", snapshotFiles.size)

    val snapshots = snapshotFiles.mapNotNull { snapshotPath ->
        readPaimonSnapshot(tablePath, snapshotPath, schemasById, errors)
    }.sortedBy { it.metadata.id ?: Long.MAX_VALUE }

    val totalManifests = snapshots.sumOf { it.baseManifests.size + it.deltaManifests.size + it.changelogManifests.size }
    val totalDataFiles = snapshots.sumOf { s -> (s.baseManifests + s.deltaManifests + s.changelogManifests).sumOf { it.entries.size } }
    val allSnapshotErrors = snapshots.flatMap { it.readErrors }
    val allManifestErrors = snapshots.flatMap { s -> (s.baseManifests + s.deltaManifests + s.changelogManifests).flatMap { it.readErrors } }
    val totalErrors = errors.size + allSnapshotErrors.size + allManifestErrors.size
    logger.info("  Paimon table loaded: {} snapshots, {} manifests, {} data files, {} errors",
        snapshots.size, totalManifests, totalDataFiles, totalErrors)
    if (errors.isNotEmpty()) {
        errors.forEach { err -> logger.warn("  Read error [{}] {}: {}", err.stage, err.path, err.message) }
    }
    allSnapshotErrors.forEach { err -> logger.warn("  Snapshot read error [{}] {}: {}", err.stage, err.path, err.message) }
    allManifestErrors.forEach { err -> logger.warn("  Manifest read error [{}] {}: {}", err.stage, err.path, err.message) }

    return PaimonUnifiedTableModel(
        path = tablePath,
        name = tablePath.fileName.toString(),
        schemas = schemas,
        snapshots = snapshots,
        readErrors = errors,
    )
}

private fun readSchemas(schemaDir: Path, errors: MutableList<UnifiedReadError>): List<PaimonSchema> {
    val files = runCatching {
        Files.list(schemaDir)
            .asSequence()
            .filter { Files.isRegularFile(it) }
            .filter { it.fileName.toString().startsWith("schema-") }
            .toList()
    }.getOrElse { e ->
        errors += toError("list-schema-files", schemaDir.toString(), e)
        emptyList()
    }

    return files.mapNotNull { path ->
        runCatching { PaimonReader.readSchema(path.toString()) }
            .onFailure { e ->
                errors += toError("read-schema", path.toString(), e)
            }
            .getOrNull()
    }.sortedBy { it.id ?: Int.MAX_VALUE }
}

private fun listSnapshotFiles(snapshotDir: Path, errors: MutableList<UnifiedReadError>): List<Path> {
    return runCatching {
        Files.list(snapshotDir)
            .asSequence()
            .filter { Files.isRegularFile(it) }
            .filter { it.fileName.toString().startsWith("snapshot-") }
            .toList()
    }.getOrElse { e ->
        errors += toError("list-snapshot-files", snapshotDir.toString(), e)
        emptyList()
    }
}

private fun readPaimonSnapshot(
    tablePath: Path,
    snapshotPath: Path,
    schemasById: Map<Int?, PaimonSchema>,
    errors: MutableList<UnifiedReadError>,
): PaimonUnifiedSnapshot? {
    val snapshot = runCatching { PaimonReader.readSnapshot(snapshotPath.toString()) }
        .onFailure { e ->
            errors += toError("read-snapshot", snapshotPath.toString(), e)
        }
        .getOrNull() ?: return null

    val schema = schemasById[snapshot.schemaId]
    val snapshotErrors = mutableListOf<UnifiedReadError>()

    val baseManifests = readManifestList(tablePath, snapshot.baseManifestList, "base-manifest-list", snapshotErrors)
    val deltaManifests = readManifestList(tablePath, snapshot.deltaManifestList, "delta-manifest-list", snapshotErrors)
    val changelogManifests = readManifestList(tablePath, snapshot.changelogManifestList, "changelog-manifest-list", snapshotErrors)

    return PaimonUnifiedSnapshot(
        path = snapshotPath,
        metadata = snapshot,
        schema = schema,
        baseManifests = baseManifests,
        deltaManifests = deltaManifests,
        changelogManifests = changelogManifests,
        readErrors = snapshotErrors,
    )
}

private fun readManifestList(
    tablePath: Path,
    manifestListPath: String?,
    stage: String,
    errors: MutableList<UnifiedReadError>,
): List<PaimonUnifiedManifest> {
    if (manifestListPath.isNullOrBlank()) return emptyList()

    // Paimon stores manifest lists in the manifest/ subdirectory.
    // Snapshot JSON contains just the filename — resolve under manifest/.
    val manifestDir = tablePath.resolve("manifest")
    val resolvedPath = if (manifestDir.resolve(manifestListPath).toFile().exists()) {
        manifestDir.resolve(manifestListPath)
    } else {
        // Fallback: resolve directly from table root (for non-standard layouts)
        logger.debug("Manifest list not in manifest/ subdir, falling back to table root: {}", manifestListPath)
        tablePath.resolve(manifestListPath)
    }
    val result = runCatching { PaimonReader.readManifestList(resolvedPath.toString()) }
        .getOrElse { e ->
            errors += toError(stage, resolvedPath.toString(), e)
            return emptyList()
        }

    errors += result.errors.map { error ->
        UnifiedReadError("decode-$stage-entry", resolvedPath.toString(), error.message, error.stackTrace)
    }

    return result.entries.map { meta ->
        readPaimonManifest(tablePath, meta, errors)
    }
}

private fun readPaimonManifest(
    tablePath: Path,
    meta: PaimonManifestFileMeta,
    errors: MutableList<UnifiedReadError>,
): PaimonUnifiedManifest {
    val manifestPath = tablePath.resolve("manifest").resolve(meta.fileName ?: "unknown")
    val manifestErrors = mutableListOf<UnifiedReadError>()

    val entries = if (meta.fileName != null) {
        val result = runCatching { PaimonReader.readManifest(manifestPath.toString()) }
            .getOrElse { e ->
                manifestErrors += toError("read-manifest", manifestPath.toString(), e)
                AvroReader.ReadResult<PaimonManifestEntry>(entries = emptyList())
            }

        manifestErrors += result.errors.map { error ->
            UnifiedReadError("decode-manifest-entry", manifestPath.toString(), error.message, error.stackTrace)
        }

        val normalizedTableRoot = runCatching { tablePath.toAbsolutePath().normalize() }
            .getOrElse { tablePath.normalize() }
        result.entries.map { entry ->
            val dataFilePath = resolveDataFilePath(tablePath, entry)
            val normalizedResolved = runCatching { dataFilePath.toAbsolutePath().normalize() }
                .getOrElse { dataFilePath.normalize() }
            if (!normalizedResolved.startsWith(normalizedTableRoot)) {
                manifestErrors += UnifiedReadError(
                    stage = "path-traversal-check",
                    path = entry.file?.fileName.orEmpty(),
                    message = "Data file path resolves outside the table directory: $normalizedResolved",
                )
            }
            PaimonUnifiedDataFile(path = dataFilePath, metadata = entry)
        }
    } else {
        emptyList()
    }

    return PaimonUnifiedManifest(
        path = manifestPath,
        metadata = meta,
        entries = entries,
        readErrors = manifestErrors,
    )
}

/**
 * Resolves the full path for a Paimon data file.
 * Paimon manifest entries contain only the file name; the full path is
 * `tablePath / [partition=value] / bucket-N / fileName`.
 * Since we don't decode partition bytes, we search for the file or fall back to tablePath/fileName.
 */
private fun resolveDataFilePath(tablePath: Path, entry: PaimonManifestEntry): Path {
    val fileName = entry.file?.fileName ?: return tablePath
    val bucket = entry.bucket
    // If we have a bucket number, try bucket-N directory
    if (bucket != null) {
        val bucketDir = tablePath.resolve("bucket-$bucket")
        val candidate = bucketDir.resolve(fileName)
        if (Files.exists(candidate)) return candidate
    }
    // Fall back: resolve from table root
    logger.debug("Data file not found in bucket-{}, falling back to table root: {}", bucket, fileName)
    return tablePath.resolve(fileName)
}
