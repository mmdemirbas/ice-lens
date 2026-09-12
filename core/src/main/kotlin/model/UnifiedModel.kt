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

/**
 * Builds a [UnifiedReadError] from a stage, path, and throwable. Falls back to the exception
 * class name when the message is null (a bare NullPointerException would otherwise read as
 * "Unknown error" with no diagnostic value).
 */
internal fun toError(stage: String, path: Path, throwable: Throwable): UnifiedReadError {
    val msg = throwable.message ?: (throwable::class.simpleName ?: "Unknown error")
    return UnifiedReadError(
        stage = stage,
        path = path.toString(),
        message = msg,
        stackTrace = throwable.stackTraceToString(),
    )
}

/** String overload for callers that don't have a Path handy. */
internal fun toError(stage: String, path: String, throwable: Throwable): UnifiedReadError {
    val msg = throwable.message ?: (throwable::class.simpleName ?: "Unknown error")
    return UnifiedReadError(
        stage = stage,
        path = path,
        message = msg,
        stackTrace = throwable.stackTraceToString(),
    )
}

/**
 * Oldest first: by the version in the file name, then by the timestamp the file records, then by
 * its modification time, then by name — so `v10` follows `v9` and a file with no version sorts last.
 */
private val metadataOrder: Comparator<Pair<Path, TableMetadata>> = compareBy(
    { (path, _) -> metadataVersionFromFileName(path.fileName.toString()) == null },
    { (path, _) -> metadataVersionFromFileName(path.fileName.toString()) ?: Int.MAX_VALUE },
    { (_, metadata) -> metadata.lastUpdatedMs ?: Long.MAX_VALUE },
    { (path, _) -> runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(Long.MAX_VALUE) },
    { (path, _) -> path.fileName.toString() },
)

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

    // The snapshots the current metadata still lists. A snapshot an older version lists whose
    // manifest list is gone was expired, and is read as such rather than as a read error — see
    // [UnifiedSnapshot.expired]. "Current" is decided the way the versions are ordered below.
    val currentSnapshotIds = parsedMetadata
        .maxWithOrNull(metadataOrder)?.second?.snapshots?.mapNotNull { it.snapshotId }?.toSet()
        .orEmpty()

    // Parse snapshots once to avoid duplicate parsing of the same snapshots, and share one
    // manifest cache across all of them — see [ManifestCache].
    val manifestCache = ManifestCache()
    val parsedSnapshots = parsedMetadata
        .map { it.second }
        .flatMap { it.snapshots }
        .filter { it.snapshotId != null }
        .distinctBy { it.snapshotId }
        .associate { snapshot ->
            val (listPath, listResolution) = resolveRecordedOrRelative(metadataDir, snapshot.manifestList)
            val expired = snapshot.snapshotId !in currentSnapshotIds &&
                !runCatching { Files.exists(listPath) }.getOrDefault(false)
            snapshot.snapshotId to if (expired) {
                UnifiedSnapshot(
                    path = listPath,
                    metadata = snapshot,
                    manifests = emptyList(),
                    pathResolution = listResolution,
                    expired = true,
                )
            } else {
                UnifiedSnapshot(
                    listPath,
                    snapshot,
                    manifestCache,
                    listResolution,
                )
            }
        }

    // version-hint.text is written only by HadoopCatalog / HadoopTables. A table managed by a
    // Hive, Glue, REST or Nessie catalog never has one — the current version lives in the
    // catalog instead — so its absence is normal, not a fault. Reporting it as a read error
    // put a red TABLE READ ERROR node on the majority of real tables. Only a file that exists
    // and cannot be read is worth surfacing.
    val versionHintPath = metadataDir.resolve("version-hint.text")
    val versionHint = if (Files.exists(versionHintPath)) {
        runCatching { versionHintPath.readText().trim() }
            .getOrElse { e ->
                tableReadErrors += toError("read-version-hint", versionHintPath, e)
                null
            }
    } else {
        null
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
        }.sortedWith(compareBy(metadataOrder) { it.path to it.metadata })

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

/** Which of the two strategies in [resolveRecordedOrRelative] produced a path. */
enum class PathResolution {
    /** The path the table recorded, used as written because the file is there. */
    RECORDED,

    /** The recorded directory was discarded and the file name resolved against the local dir. */
    FORCED_RELATIVE,

    /**
     * The recorded path sits outside the table's own directory — a `write.data.path` layout, or
     * data registered from elsewhere — so there was no sub-path under the table to rebuild. It
     * was rebuilt under the local counterpart of the directory the table's *own* recorded path
     * sits in: the recorded table directory and the local one agree on their last few segments,
     * and what is above those is the warehouse in each world. See [rebuildBesideTable].
     */
    REBUILT_BESIDE_TABLE,
}

/**
 * Resolves a recorded path, preferring what the table actually says.
 *
 * [resolveForceRelative] discards the recorded directory entirely, which is the behaviour that
 * lets this tool open a table copied down from object storage: the paths inside point at
 * `s3://…` or at some container's `/wh`, and none of them exist on this machine. That is worth
 * keeping — PyIceberg cannot open those tables at all.
 *
 * It is wrong, though, whenever the recorded path *is* valid and points somewhere other than the
 * local metadata directory: a table with `write.metadata.path` set, or any layout where metadata
 * does not sit beside the data. Those resolve to a file that is not there and report it missing.
 *
 * So: use the recorded path when it is absolute and the file exists, and fall back otherwise.
 * A table whose paths are foreign is unaffected — the existence check fails and the fallback runs
 * exactly as before. Only absolute paths are considered, because a relative one would resolve
 * against the process's working directory and could match an unrelated file by coincidence.
 */
fun resolveRecordedOrRelative(start: Path, recorded: String?): Pair<Path, PathResolution> =
    resolveRecordedOr(recorded) { resolveForceRelative(start, recorded) }

/**
 * The recorded-first half of the rule, with the fallback left to the caller.
 *
 * Metadata files and data files agree on when the recorded path wins and disagree on what to do
 * when it does not — a manifest's file name is resolved against the local metadata dir, a data
 * file's sub-path is rebuilt under the table root. Two callers, one rule about the recorded path,
 * and the fallback is the only thing that differs, so the fallback is the parameter.
 */
fun resolveRecordedOr(recorded: String?, fallback: () -> Path): Pair<Path, PathResolution> =
    resolveRecordedOrRebuilt(recorded) { fallback() to PathResolution.FORCED_RELATIVE }

/** [resolveRecordedOr] for a fallback that has more than one way of rebuilding, and says which it took. */
fun resolveRecordedOrRebuilt(recorded: String?, fallback: () -> Pair<Path, PathResolution>): Pair<Path, PathResolution> {
    val asRecorded = recorded
        ?.takeIf { it.isNotBlank() }
        ?.let(::normalizeFilePath)
        ?.let { runCatching { service.StorageLocation.pathOf(it) }.getOrNull() }
        ?.takeIf { it.isAbsolute && runCatching { Files.isRegularFile(it) }.getOrDefault(false) }
    return if (asRecorded != null) {
        asRecorded to PathResolution.RECORDED
    } else {
        fallback()
    }
}

/**
 * Where a recorded path that sits *outside* the table lands on this machine, or null when nothing
 * can be said.
 *
 * A data file under the table rebuilds by its sub-path — `data/name=alpha/…` under the local
 * table root — and a `write.data.path` file has no such sub-path: `/wh/extdata-files/x.parquet`
 * shares nothing with `/wh/default/extdata` below the warehouse. What it does share is the
 * warehouse, and the warehouse has a local counterpart whenever the table was copied down with
 * its surroundings: [recordedTableDir] and [localTableDir] agree on their last segments
 * (`default/extdata`), and the directory above those is `/wh` in one world and
 * `…/example/iceberg` in the other. The recorded path is then re-rooted from the one to the
 * other. Null when the two table directories share no trailing segment, or the recorded path
 * is not under the recorded warehouse either, or the re-rooted path escapes the local warehouse
 * — each of which is "nothing can be said", and the caller's older rule runs instead.
 */
internal fun rebuildBesideTable(recorded: String, recordedTableDir: String, localTableDir: Path): Path? {
    val recordedSegments = recordedTableDir.trimEnd('/').split('/')
    val localRoot = runCatching { localTableDir.toAbsolutePath().normalize() }.getOrElse { localTableDir.normalize() }
    val localSegments = localRoot.map { it.toString() }
    var shared = 0
    while (shared < recordedSegments.size && shared < localSegments.size &&
        recordedSegments[recordedSegments.size - 1 - shared] == localSegments[localSegments.size - 1 - shared]
    ) shared++
    if (shared == 0) return null

    val recordedWarehouse = recordedSegments.dropLast(shared).joinToString("/")
    if (recordedWarehouse.isEmpty()) return null
    val relative = recorded.removePrefix("$recordedWarehouse/")
    if (relative == recorded || relative.isEmpty()) return null

    var localWarehouse: Path = localRoot
    repeat(shared) { localWarehouse = localWarehouse.parent ?: return null }
    val rebuilt = localWarehouse.resolve(relative).normalize()
    return rebuilt.takeIf { it.startsWith(localWarehouse) }
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

/**
 * Reads each manifest file at most once per table load.
 *
 * A commit rewrites the manifest list but carries most manifests forward unchanged, so after
 * N commits the same manifest file is referenced by N snapshots. Constructing a
 * [UnifiedManifest] per (snapshot, manifest) pair therefore re-parses the same Avro file once
 * per referencing snapshot, and load time scales with commit history instead of with the
 * table. Measured at 7.3x for an 8x increase in snapshot count over identical contents
 * (`LoadPathScalabilityTest`).
 *
 * Sharing the parsed result is sound because a manifest file is immutable, and the
 * `manifest_file` entry describing it — sequence numbers, added-snapshot id, counts — is
 * written when the manifest is added and carried forward verbatim.
 *
 * Not thread-safe: one instance belongs to one table load, which happens on a single
 * background coroutine.
 */
class ManifestCache {
    private val byPath = mutableMapOf<String, UnifiedManifest>()

    fun manifestAt(
        path: Path,
        entry: ManifestListEntry,
        resolution: PathResolution = PathResolution.FORCED_RELATIVE,
    ): UnifiedManifest = byPath.getOrPut(path.toString()) { UnifiedManifest(path, entry, resolution) }
}

fun UnifiedSnapshot(
    snapshotPath: Path,
    snapshot: Snapshot,
    manifestCache: ManifestCache = ManifestCache(),
    pathResolution: PathResolution = PathResolution.FORCED_RELATIVE,
): UnifiedSnapshot {
    val manifestListResult = runCatching { IcebergReader.readManifestList(snapshotPath.toString()) }
    val snapshotReadErrors = mutableListOf<UnifiedReadError>()
    val manifestList = manifestListResult.getOrElse { e ->
        snapshotReadErrors += toError("read-manifest-list-file", snapshotPath, e)
        service.AvroReader.ReadResult(entries = emptyList())
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
        pathResolution = pathResolution,
        manifests = manifestList.entries.map { manifest ->
            val metadataDir = snapshotPath.parent
            val (manifestPath, manifestResolution) = resolveRecordedOrRelative(metadataDir, manifest.manifestPath)
            manifestCache.manifestAt(manifestPath, manifest, manifestResolution)
        },
        readErrors = snapshotReadErrors,
    )
}

fun UnifiedManifest(
    manifestPath: Path,
    manifest: ManifestListEntry,
    pathResolution: PathResolution = PathResolution.FORCED_RELATIVE,
): UnifiedManifest {
    val manifestFileResult = runCatching { IcebergReader.readManifestFile(manifestPath.toString()) }
    val manifestReadErrors = mutableListOf<UnifiedReadError>()
    val dataFiles = manifestFileResult.getOrElse { e ->
        manifestReadErrors += toError("read-manifest-file", manifestPath, e)
        service.AvroReader.ReadResult(entries = emptyList())
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

    val manifestSchema = dataFiles.fileMetadata[service.AvroReader.MetaKeys.SCHEMA]
        ?.let(::parseIcebergSchema)
    val manifestSpec = dataFiles.fileMetadata[service.AvroReader.MetaKeys.PARTITION_SPEC]
        ?.let { spec ->
            parsePartitionSpec(
                spec,
                specId = dataFiles.fileMetadata[service.AvroReader.MetaKeys.PARTITION_SPEC_ID]?.toIntOrNull()
                    ?: manifest.partitionSpecId,
            )
        }

    return UnifiedManifest(
        path = manifestPath,
        metadata = manifest,
        pathResolution = pathResolution,
        schema = manifestSchema,
        partitionSpec = manifestSpec,
        // The summaries come from the manifest list, the spec from the manifest itself. Both
        // describe the same manifest and `partition_spec_id` ties them together, so there is no
        // ambiguity here of the kind the bounds have.
        partitionSummaries = decodePartitionSummaries(manifest.partitions, manifestSpec, manifestSchema),
        dataFiles = dataFiles.entries.map { record ->
            val dataFile = record.entry
            val dataFilePathInFile = dataFile.dataFile?.filePath.orEmpty()

            // Same rule as the manifests above: the path the table recorded, when the file is
            // actually there. Otherwise a file under the table is rebuilt by its sub-path under
            // the local table root, and a file outside it — a `write.data.path` layout, or one
            // registered against data written elsewhere — beside the table, under the local
            // counterpart of the warehouse (`rebuildBesideTable`). The `extdata` fixture is the
            // second: before that rule it was rebuilt under the table root, where nothing is,
            // and every file reported missing.
            val (dataFilePathResolved, resolution) = resolveRecordedOrRebuilt(dataFilePathInFile) {
                val metadataDirPrefix = manifest.manifestPath.orEmpty().substringBeforeLast('/')
                val tableDirPrefix = metadataDirPrefix.substringBeforeLast('/')
                if (tableDirPrefix.isNotEmpty() && !dataFilePathInFile.startsWith("$tableDirPrefix/")) {
                    rebuildBesideTable(dataFilePathInFile, tableDirPrefix, dataRoot)
                        ?.let { return@resolveRecordedOrRebuilt it to PathResolution.REBUILT_BESIDE_TABLE }
                }
                val dataFilePathRelative =
                    dataFilePathInFile.removePrefix(tableDirPrefix).removePrefix("/")
                val rebuilt = dataRoot.resolve(dataFilePathRelative)

                // The rebuilt path is ours, so it must land under the table. A recorded path that
                // points elsewhere is the table's own statement and is not checked here — that is
                // the case above, and it is reported as RECORDED rather than as an error.
                val normalizedResolved = runCatching { rebuilt.toAbsolutePath().normalize() }
                    .getOrElse { rebuilt.normalize() }
                if (!normalizedResolved.startsWith(normalizedDataRoot)) {
                    manifestReadErrors += UnifiedReadError(
                        stage = "path-traversal-check",
                        path = dataFilePathInFile,
                        message = "Data file path resolves outside the table directory: $normalizedResolved",
                    )
                }
                rebuilt to PathResolution.FORCED_RELATIVE
            }

            UnifiedDataFile(
                path = dataFilePathResolved,
                metadata = dataFile,
                partition = decodePartition(record.partition, manifestSpec, manifestSchema),
                pathResolution = resolution,
            )
        },
        readErrors = manifestReadErrors,
    )
}

data class UnifiedTableModel(
    override val path: Path,
    override val name: String,
    /** Contents of `metadata/version-hint.text`, or null when the file is absent (the norm
     *  for catalog-managed tables). */
    val versionHint: String?,
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
    /**
     * Whether [path] came from what the table recorded, or from discarding the recorded
     * directory. Worth showing: a reader who sees a file reported missing needs to know which
     * path was actually looked at.
     */
    val pathResolution: PathResolution = PathResolution.FORCED_RELATIVE,
    /**
     * True when this snapshot's manifest list is gone **and** the current metadata no longer
     * lists the snapshot — the state `expire_snapshots` leaves behind, which an older metadata
     * version kept on disk by `write.metadata.previous-versions-max` still describes. Not a read
     * error: only a file that exists and cannot be read is one. The two conditions are both
     * required, because a listed snapshot whose manifest list is missing is a broken table.
     */
    val expired: Boolean = false,
)

data class UnifiedManifest(
    val path: Path,
    val metadata: ManifestListEntry,
    val dataFiles: List<UnifiedDataFile>,
    val readErrors: List<UnifiedReadError> = emptyList(),
    /**
     * Whether [path] came from what the table recorded, or from discarding the recorded
     * directory. Worth showing: a reader who sees a file reported missing needs to know which
     * path was actually looked at.
     */
    val pathResolution: PathResolution = PathResolution.FORCED_RELATIVE,

    /**
     * The schema this manifest was written against, read from its own Avro file metadata.
     *
     * This — not the table's current schema — is the only correct source for decoding the
     * entries' bounds and partition values. A file written before a type change is described by
     * the schema in force when it was written, and using the current one instead mis-decodes it
     * silently rather than failing.
     */
    val schema: IcebergSchemaModel? = null,
    /**
     * The partition spec this manifest was written against, from its own Avro file metadata.
     *
     * Same rule as [schema]: a table can be repartitioned, and every manifest keeps the spec in
     * force when it was written. Null means the spec could not be read, not "unpartitioned".
     */
    val partitionSpec: PartitionSpec? = null,
    /** Per-partition-field bounds over this manifest, from `manifest_file.partitions`. */
    val partitionSummaries: List<PartitionSummary> = emptyList(),
)

data class UnifiedDataFile(
    val path: Path,
    val metadata: ManifestEntry,
    /** This file's partition tuple, decoded against the manifest's own spec and schema. */
    val partition: DecodedPartition? = null,
    /** How [path] was arrived at. Worth showing: a file reported missing means something
     *  different depending on whether the table named that path or this tool rebuilt it. */
    val pathResolution: PathResolution = PathResolution.FORCED_RELATIVE,
    private val rowsLoader: () -> List<UnifiedRow> = {
        SampleRowReader.querySampleRows(path.toString()).map(::unifiedRowOf)
    },
) {
    val rows: List<UnifiedRow> by lazy { rowsLoader() }
}

/**
 * One sampled row.
 *
 * [position] is the row's physical position in its file — the coordinate an Iceberg positional
 * delete and a v3 deletion vector both address — and is deliberately not a cell: it is DuckDB's
 * answer about the file rather than a column the table declares, and drawing it beside the real
 * columns would say the table has one it does not.
 */
data class UnifiedRow(
    val cells: Map<String, Any>,
    val position: Long? = null,
)

/** Splits DuckDB's generated position column off the row's real cells. */
private fun unifiedRowOf(row: Map<String, Any>): UnifiedRow = UnifiedRow(
    cells = row - SampleRowReader.FILE_ROW_NUMBER,
    position = (row[SampleRowReader.FILE_ROW_NUMBER] as? Number)?.toLong(),
)
