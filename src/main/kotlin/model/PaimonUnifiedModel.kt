package model

import service.AvroReader
import service.PaimonReader
import service.SampleRowReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

/** Aggregated Paimon table model linking snapshots, schemas, manifests, and data files. */
data class PaimonUnifiedTableModel(
    val path: Path,
    val name: String,
    val schemas: List<PaimonSchema>,
    val snapshots: List<PaimonUnifiedSnapshot>,
    val readErrors: List<UnifiedReadError> = emptyList(),
)

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
    val errors = mutableListOf<UnifiedReadError>()

    // 1. Read all schemas
    val schemaDir = tablePath.resolve("schema")
    val schemas = readSchemas(schemaDir, errors)
    val schemasById = schemas.associateBy { it.id }

    // 2. Read all snapshots
    val snapshotDir = tablePath.resolve("snapshot")
    val snapshotFiles = listSnapshotFiles(snapshotDir, errors)

    val snapshots = snapshotFiles.mapNotNull { snapshotPath ->
        readPaimonSnapshot(tablePath, snapshotPath, schemasById, errors)
    }.sortedBy { it.metadata.id ?: Long.MAX_VALUE }

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
        errors += UnifiedReadError("list-schema-files", schemaDir.toString(), e.message ?: "Unknown error", e.stackTraceToString())
        emptyList()
    }

    return files.mapNotNull { path ->
        runCatching { PaimonReader.readSchema(path.toString()) }
            .onFailure { e ->
                errors += UnifiedReadError("read-schema", path.toString(), e.message ?: "Unknown error", e.stackTraceToString())
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
        errors += UnifiedReadError("list-snapshot-files", snapshotDir.toString(), e.message ?: "Unknown error", e.stackTraceToString())
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
            errors += UnifiedReadError("read-snapshot", snapshotPath.toString(), e.message ?: "Unknown error", e.stackTraceToString())
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

    val resolvedPath = tablePath.resolve(manifestListPath)
    val result = runCatching { PaimonReader.readManifestList(resolvedPath.toString()) }
        .getOrElse { e ->
            errors += UnifiedReadError(stage, resolvedPath.toString(), e.message ?: "Unknown error", e.stackTraceToString())
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
                manifestErrors += UnifiedReadError("read-manifest", manifestPath.toString(), e.message ?: "Unknown error", e.stackTraceToString())
                AvroReader.ReadResult<PaimonManifestEntry>(entries = emptyList())
            }

        manifestErrors += result.errors.map { error ->
            UnifiedReadError("decode-manifest-entry", manifestPath.toString(), error.message, error.stackTrace)
        }

        result.entries.map { entry ->
            val dataFilePath = resolveDataFilePath(tablePath, entry)
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
    // Fall back: search common locations or just resolve from table root
    return tablePath.resolve(fileName)
}
