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
    /** What `snapshot/` holds, by id. A snapshot a tag retains after expiry is in [tags], not here. */
    val snapshots: List<PaimonUnifiedSnapshot>,
    /** What `tag/` holds — see [PaimonUnifiedTag]. */
    val tags: List<PaimonUnifiedTag> = emptyList(),
    /** What `branch/` holds, by name — see [PaimonUnifiedBranch]. Main's own commits are [snapshots]. */
    val branches: List<PaimonUnifiedBranch> = emptyList(),
    /** What `consumer/` holds, by id — see [PaimonUnifiedConsumer]. */
    val consumers: List<PaimonUnifiedConsumer> = emptyList(),
    override val readErrors: List<UnifiedReadError> = emptyList(),
) : FormatTableModel {
    /** The tags naming a snapshot id, by id — a tag on a live snapshot and a tag on an expired one alike. */
    val tagNamesBySnapshotId: Map<Long, List<String>> by lazy { tagNamesBySnapshotId(tags) }

    /** The tagged snapshots `snapshot/` no longer holds — retained by the tag alone. */
    val tagOnlySnapshots: List<PaimonUnifiedSnapshot> by lazy { tagOnlySnapshots(snapshots, tags) }
    override val format get() = service.TableFormat.PAIMON
}

/**
 * A Paimon branch: `branch/branch-<name>/`, which holds its own `snapshot/`, `schema/` and `tag/`
 * over the table's one `manifest/` and one set of data directories.
 *
 * That split is what the `br` fixture settled and the reason a branch is not a nested table: a
 * commit to the branch writes its manifests into the table's `manifest/` and its data file into
 * the same bucket directory main writes to, and only the snapshot file lands under `branch/`. So a
 * reader of `snapshot/` alone sees the branch's data file as a file nothing names. Snapshot ids
 * are per branch — a branch created from a tag starts with that snapshot copied verbatim, and its
 * next commit takes the next id, which main may have used for a different commit. An empty branch
 * (`create_branch` without a tag) holds a schema and no `snapshot/` at all, which is not an error.
 */
data class PaimonUnifiedBranch(
    val name: String,
    val path: Path,
    val schemas: List<PaimonSchema>,
    val snapshots: List<PaimonUnifiedSnapshot>,
    val tags: List<PaimonUnifiedTag> = emptyList(),
    val readErrors: List<UnifiedReadError> = emptyList(),
) {
    val tagNamesBySnapshotId: Map<Long, List<String>> by lazy { tagNamesBySnapshotId(tags) }
    val tagOnlySnapshots: List<PaimonUnifiedSnapshot> by lazy { tagOnlySnapshots(snapshots, tags) }
}

/**
 * A Paimon consumer: `consumer/consumer-<id>`, a streaming reader's bookmark — see [PaimonConsumer].
 * It is what holds an expiry back: `expire_snapshots` keeps every snapshot from the reader's next
 * one on, so a table whose expiry stops short of what its retention says is usually a table with
 * a consumer standing on an old snapshot.
 */
data class PaimonUnifiedConsumer(
    val name: String,
    val path: Path,
    val metadata: PaimonConsumer,
)

private fun tagNamesBySnapshotId(tags: List<PaimonUnifiedTag>): Map<Long, List<String>> =
    tags.groupBy({ it.snapshot.metadata.id }, { it.name })
        .filterKeys { it != null }.mapKeys { it.key!! }

private fun tagOnlySnapshots(snapshots: List<PaimonUnifiedSnapshot>, tags: List<PaimonUnifiedTag>): List<PaimonUnifiedSnapshot> {
    val live = snapshots.mapNotNull { it.metadata.id }.toSet()
    return tags.filter { it.snapshot.metadata.id !in live }
        .distinctBy { it.snapshot.metadata.id }
        .map { it.snapshot }
}

/**
 * A Paimon tag: a snapshot file copied under `tag/` as `tag-<name>`, which is what keeps the
 * snapshot's files alive after `expire_snapshots` has removed it from `snapshot/`.
 *
 * What a tag retains is the data, not the change stream. The `tg` fixture's tag names a changelog
 * manifest list that expiry deleted, so reading it produces a read error on the tag — that is the
 * format's behaviour, and the error is reported rather than hidden.
 */
data class PaimonUnifiedTag(
    val name: String,
    val path: Path,
    val snapshot: PaimonUnifiedSnapshot,
)

/** A Paimon snapshot with its resolved manifest trees. */
data class PaimonUnifiedSnapshot(
    val path: Path,
    val metadata: PaimonSnapshot,
    val schema: PaimonSchema?,
    val baseManifests: List<PaimonUnifiedManifest>,
    val deltaManifests: List<PaimonUnifiedManifest>,
    val changelogManifests: List<PaimonUnifiedManifest>,
    /**
     * The index files this snapshot's index manifest lists — a key index per bucket, or, on a
     * table with deletion vectors enabled, the rows deleted from each data file.
     *
     * Read eagerly like the manifest lists rather than deferred: an index manifest is one small
     * Avro file per snapshot, where a data-file read is one per file.
     */
    val indexFiles: List<PaimonIndexManifestEntry> = emptyList(),
    /**
     * The statistics an `ANALYZE` commit wrote, on the snapshot that names them — null on every
     * other kind. One small JSON file per such snapshot, so read eagerly like the index manifest.
     */
    val statistics: PaimonStatistics? = null,
    val readErrors: List<UnifiedReadError> = emptyList(),
)

/** A Paimon manifest file with its resolved data file entries. */
data class PaimonUnifiedManifest(
    val path: Path,
    val metadata: PaimonManifestFileMeta,
    val entries: List<PaimonUnifiedDataFile>,
    val readErrors: List<UnifiedReadError> = emptyList(),
    /** `_PARTITION_STATS` minimums, decoded against the same schema as the entries. Null when not decodable. */
    val partitionMin: DecodedPaimonPartition? = null,
    /** `_PARTITION_STATS` maximums, likewise. */
    val partitionMax: DecodedPaimonPartition? = null,
)

/**
 * How a Paimon data file's local path was arrived at. The format records no path for a file in
 * the table's own layout, so there is nothing to prefer over the layout; a file written to
 * `data-file.external-paths` records where in `_EXTERNAL_PATH`, and that is the one case with
 * something to resolve — the Paimon counterpart of [PathResolution].
 */
enum class PaimonPathResolution {
    /** `<table>/<partition>/bucket-N/<file>`, the layout the format defines. */
    LAYOUT,

    /** The entry's `_EXTERNAL_PATH`, used as written because the file is there. */
    EXTERNAL_RECORDED,

    /**
     * `_EXTERNAL_PATH` is not present here, and the file was found by the recorded path's tail
     * under the local warehouse — see [rerootExternalPath].
     */
    EXTERNAL_REROOTED,

    /** `_EXTERNAL_PATH` is recorded and nothing matching it is on this machine; the layout path stands in, and is missing. */
    EXTERNAL_MISSING,
}

/** A single Paimon data file entry from a manifest. */
data class PaimonUnifiedDataFile(
    val path: Path,
    val metadata: PaimonManifestEntry,
    /** How [path] was arrived at — see [PaimonPathResolution]. */
    val pathResolution: PaimonPathResolution = PaimonPathResolution.LAYOUT,
    /** The entry's `_PARTITION`, decoded against its manifest's schema; null when it could not be. */
    val partition: DecodedPaimonPartition? = null,
    /** `_MIN_KEY` over the trimmed primary key — the primary keys that are not partition keys. Null when not decodable. */
    val keyMin: List<PaimonRowValue>? = null,
    /** `_MAX_KEY`, likewise. */
    val keyMax: List<PaimonRowValue>? = null,
    /**
     * `_KEY_STATS` per trimmed primary key — the bounds a scan prunes a file by on a key
     * predicate, kept in full whatever `stats-mode` says of the value columns. Null on a table
     * without a primary key, or when not decodable.
     */
    val keyBounds: List<PaimonColumnBounds>? = null,
    /** `_VALUE_STATS` per column, over every field of the schema, or `_WRITE_COLS`, or the ones `_VALUE_STATS_COLS` names. */
    val columnBounds: List<PaimonColumnBounds>? = null,
    /** A data-evolution patch file — [PaimonDataFileMeta.isPartialUnder] the schema the file's own `_SCHEMA_ID` names. */
    val partial: Boolean = false,
    private val rowsLoader: () -> List<UnifiedRow> = {
        SampleRowReader.querySampleRows(path.toString()).map(::unifiedRowOf)
    },
) {
    val rows: List<UnifiedRow> by lazy { rowsLoader() }
}

/**
 * Reads a Paimon table directory and builds a [PaimonUnifiedTableModel].
 *
 * Reads all schemas from `schema/`, all snapshots from `snapshot/`, then resolves each
 * snapshot's manifest lists and manifest files; then the same for every branch under `branch/`,
 * whose snapshot, schema and tag directories are its own and whose manifests and data files are
 * the table's.
 */
fun PaimonUnifiedTableModel(tablePath: Path): PaimonUnifiedTableModel {
    logger.info("Loading Paimon table: {}", tablePath)
    val errors = mutableListOf<UnifiedReadError>()

    // One cache for the whole load: a snapshot's base manifest list carries manifests forward
    // from earlier commits, so without it each shared manifest is re-parsed once per snapshot
    // that references it — and a branch's first snapshot is a copy of the one it was created
    // from, so the cache is what keeps a branch from re-reading main's manifests. Same defect as
    // the Iceberg side — see [ManifestCache].
    val manifestCache = PaimonManifestCache()
    val main = readPaimonBranch(MAIN_BRANCH, tablePath, tablePath, manifestCache, mainBranch = true)
    errors += main.readErrors

    val branches = listBranchDirs(tablePath.resolve("branch"), errors).map { branchDir ->
        readPaimonBranch(
            name = branchDir.fileName.toString().removePrefix(BRANCH_DIR_PREFIX),
            metadataRoot = branchDir,
            tablePath = tablePath,
            manifestCache = manifestCache,
            mainBranch = false,
        )
    }.sortedBy { it.name }
    if (branches.isNotEmpty()) logger.info("  Branches: {}", branches.map { it.name })

    val consumers = listConsumerFiles(tablePath.resolve("consumer"), errors).mapNotNull { consumerPath ->
        runCatching { PaimonReader.readConsumer(consumerPath.toString()) }
            .onFailure { e -> errors += toError("read-consumer", consumerPath.toString(), e) }
            .getOrNull()
            ?.let { PaimonUnifiedConsumer(name = consumerPath.fileName.toString().removePrefix("consumer-"), path = consumerPath, metadata = it) }
    }.sortedBy { it.name }
    if (consumers.isNotEmpty()) logger.info("  Consumers: {}", consumers.map { "${it.name} -> ${it.metadata.nextSnapshot}" })

    val snapshots = main.snapshots
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
        schemas = main.schemas,
        snapshots = snapshots,
        tags = main.tags,
        branches = branches,
        consumers = consumers,
        readErrors = errors,
    )
}

/** `consumer/consumer-<id>`, or nothing: most tables have no `consumer/` directory at all, which is not an error. */
private fun listConsumerFiles(consumerDir: Path, errors: MutableList<UnifiedReadError>): List<Path> {
    if (!Files.isDirectory(consumerDir)) return emptyList()
    return runCatching {
        Files.list(consumerDir)
            .asSequence()
            .filter { Files.isRegularFile(it) }
            .filter { it.fileName.toString().startsWith("consumer-") }
            .toList()
    }.getOrElse { e ->
        errors += toError("list-consumer-files", consumerDir.toString(), e)
        emptyList()
    }
}

/** Paimon's name for the table's own line of commits — the one `snapshot/` at the root holds. */
const val MAIN_BRANCH = "main"

/** `branch/branch-<name>/` — the prefix Paimon puts on every branch directory. */
private const val BRANCH_DIR_PREFIX = "branch-"

/**
 * One line of commits — main's, or a branch's — read from [metadataRoot]'s `snapshot/`, `schema/`
 * and `tag/`, with manifests and data resolved under [tablePath]. The two are the same directory
 * for main and differ for a branch, which is the whole of what a branch is.
 *
 * A branch with no `snapshot/` was created empty and has nothing to read, so only main reports
 * the directory missing — the format detector required it there.
 */
private fun readPaimonBranch(
    name: String,
    metadataRoot: Path,
    tablePath: Path,
    manifestCache: PaimonManifestCache,
    mainBranch: Boolean,
): PaimonUnifiedBranch {
    val errors = mutableListOf<UnifiedReadError>()

    val schemas = readSchemas(metadataRoot.resolve("schema"), errors)
    val schemasById = schemas.associateBy { it.id }
    logger.info("  [{}] Schemas loaded: {}", name, schemas.size)

    val snapshotDir = metadataRoot.resolve("snapshot")
    val snapshotFiles = if (mainBranch || Files.isDirectory(snapshotDir)) listSnapshotFiles(snapshotDir, errors) else emptyList()
    logger.info("  [{}] Snapshot files found: {}", name, snapshotFiles.size)

    val snapshots = snapshotFiles.mapNotNull { snapshotPath ->
        readPaimonSnapshot(tablePath, snapshotPath, schemasById, errors, manifestCache)
    }.sortedBy { it.metadata.id ?: Long.MAX_VALUE }

    // Tags: the same file shape as a snapshot, read the same way, through the same cache.
    val tags = listTagFiles(metadataRoot.resolve("tag"), errors).mapNotNull { tagPath ->
        readPaimonSnapshot(tablePath, tagPath, schemasById, errors, manifestCache)?.let { snapshot ->
            PaimonUnifiedTag(name = tagPath.fileName.toString().removePrefix("tag-"), path = tagPath, snapshot = snapshot)
        }
    }.sortedBy { it.name }
    if (tags.isNotEmpty()) logger.info("  [{}] Tags: {}", name, tags.map { it.name })

    return PaimonUnifiedBranch(
        name = name,
        path = metadataRoot,
        schemas = schemas,
        snapshots = snapshots,
        tags = tags,
        readErrors = errors,
    )
}

/** `branch/branch-<name>/`, or nothing: most tables have no `branch/` directory at all, which is not an error. */
private fun listBranchDirs(branchDir: Path, errors: MutableList<UnifiedReadError>): List<Path> {
    if (!Files.isDirectory(branchDir)) return emptyList()
    return runCatching {
        Files.list(branchDir)
            .asSequence()
            .filter { Files.isDirectory(it) }
            .filter { it.fileName.toString().startsWith(BRANCH_DIR_PREFIX) }
            .toList()
    }.getOrElse { e ->
        errors += toError("list-branch-dirs", branchDir.toString(), e)
        emptyList()
    }
}

/** `tag/tag-<name>`, or nothing: most tables have no `tag/` directory at all, which is not an error. */
private fun listTagFiles(tagDir: Path, errors: MutableList<UnifiedReadError>): List<Path> {
    if (!Files.isDirectory(tagDir)) return emptyList()
    return runCatching {
        Files.list(tagDir)
            .asSequence()
            .filter { Files.isRegularFile(it) }
            .filter { it.fileName.toString().startsWith("tag-") }
            .toList()
    }.getOrElse { e ->
        errors += toError("list-tag-files", tagDir.toString(), e)
        emptyList()
    }
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

/**
 * Reads each Paimon manifest file at most once per table load, keyed by the file name the
 * manifest list records. The Paimon counterpart of [ManifestCache]; the same carry-forward
 * shape and the same cost without it.
 *
 * Not thread-safe: one instance belongs to one table load.
 */
class PaimonManifestCache {
    private val byName = mutableMapOf<String, PaimonUnifiedManifest>()

    fun manifestFor(meta: PaimonManifestFileMeta, read: () -> PaimonUnifiedManifest): PaimonUnifiedManifest {
        val key = meta.fileName ?: return read()
        return byName.getOrPut(key) { read() }
    }
}

private fun readPaimonSnapshot(
    tablePath: Path,
    snapshotPath: Path,
    schemasById: Map<Int?, PaimonSchema>,
    errors: MutableList<UnifiedReadError>,
    manifestCache: PaimonManifestCache,
): PaimonUnifiedSnapshot? {
    val snapshot = runCatching { PaimonReader.readSnapshot(snapshotPath.toString()) }
        .onFailure { e ->
            errors += toError("read-snapshot", snapshotPath.toString(), e)
        }
        .getOrNull() ?: return null

    val schema = schemasById[snapshot.schemaId]
    val snapshotErrors = mutableListOf<UnifiedReadError>()

    val baseManifests = readManifestList(tablePath, snapshot.baseManifestList, "base-manifest-list", snapshotErrors, manifestCache, schemasById)
    val deltaManifests = readManifestList(tablePath, snapshot.deltaManifestList, "delta-manifest-list", snapshotErrors, manifestCache, schemasById)
    val changelogManifests = readManifestList(tablePath, snapshot.changelogManifestList, "changelog-manifest-list", snapshotErrors, manifestCache, schemasById)

    return PaimonUnifiedSnapshot(
        path = snapshotPath,
        metadata = snapshot,
        schema = schema,
        baseManifests = baseManifests,
        deltaManifests = deltaManifests,
        changelogManifests = changelogManifests,
        indexFiles = readIndexManifest(tablePath, snapshot.indexManifest, snapshotErrors),
        statistics = readStatistics(tablePath, snapshot.statistics, snapshotErrors),
        readErrors = snapshotErrors,
    )
}

/** The statistics file a snapshot names under `statistics/`, or null when it names none. */
private fun readStatistics(
    tablePath: Path,
    statisticsName: String?,
    errors: MutableList<UnifiedReadError>,
): PaimonStatistics? {
    if (statisticsName.isNullOrBlank()) return null
    val resolved = tablePath.resolve("statistics").resolve(statisticsName)
    return runCatching { PaimonReader.readStatistics(resolved.toString()) }
        .getOrElse { e ->
            errors += toError("statistics", resolved.toString(), e)
            null
        }
}

/**
 * The index manifest a snapshot names, or nothing when it names none.
 *
 * Resolved the same way a manifest list is — `manifest/` first, the table root as a fallback for a
 * layout that does not use it — because the snapshot records a bare file name for both.
 */
private fun readIndexManifest(
    tablePath: Path,
    indexManifestName: String?,
    errors: MutableList<UnifiedReadError>,
): List<PaimonIndexManifestEntry> {
    if (indexManifestName.isNullOrBlank()) return emptyList()
    val manifestDir = tablePath.resolve("manifest")
    val resolved = if (Files.exists(manifestDir.resolve(indexManifestName))) {
        manifestDir.resolve(indexManifestName)
    } else {
        tablePath.resolve(indexManifestName)
    }
    val result = runCatching { PaimonReader.readIndexManifest(resolved.toString()) }
        .getOrElse { e ->
            errors += toError("index-manifest", resolved.toString(), e)
            return emptyList()
        }
    errors += result.errors.map { error ->
        UnifiedReadError("decode-index-manifest-entry", resolved.toString(), error.message, error.stackTrace)
    }
    return result.entries
}

private fun readManifestList(
    tablePath: Path,
    manifestListPath: String?,
    stage: String,
    errors: MutableList<UnifiedReadError>,
    manifestCache: PaimonManifestCache,
    schemasById: Map<Int?, PaimonSchema>,
): List<PaimonUnifiedManifest> {
    if (manifestListPath.isNullOrBlank()) return emptyList()

    // Paimon stores manifest lists in the manifest/ subdirectory.
    // Snapshot JSON contains just the filename — resolve under manifest/.
    val manifestDir = tablePath.resolve("manifest")
    val resolvedPath = if (Files.exists(manifestDir.resolve(manifestListPath))) {
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
        manifestCache.manifestFor(meta) { readPaimonManifest(tablePath, meta, errors, schemasById) }
    }
}

private fun readPaimonManifest(
    tablePath: Path,
    meta: PaimonManifestFileMeta,
    errors: MutableList<UnifiedReadError>,
    schemasById: Map<Int?, PaimonSchema>,
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
        // The partition is decoded against the schema the manifest was written under — its
        // `_SCHEMA_ID` — the same rule that decodes an Iceberg manifest against its own spec.
        // Partition keys cannot change across a Paimon table's schemas, but their types name
        // which decoding each slot gets, and a schema this table never had is not a guess worth
        // making: the newest one stands in only when the manifest names none.
        val schema = schemasById[meta.schemaId?.toInt()] ?: schemasById.values.maxByOrNull { it.id ?: -1 }
        val partitionKeys = schema?.partitionKeys.orEmpty()
        val partitionFields = partitionKeys.mapNotNull { key -> schema?.fields?.firstOrNull { it.name == key } }
        result.entries.map { entry ->
            val partition = entry.partition
                ?.takeIf { partitionFields.size == partitionKeys.size }
                ?.let { decodePaimonPartition(it, partitionFields, schema?.options.orEmpty()) }
            val (dataFilePath, pathResolution) = resolveDataFilePath(tablePath, entry, partition)
            val file = entry.file
            // A file's key and value statistics are rows over the schema the FILE was written
            // under, which its own `_SCHEMA_ID` names and which is not always the manifest's: a
            // compaction's delta manifest, written under the new schema, records the old-schema
            // files it removed. Decoded against the manifest's schema, a two-field row read as
            // three has the wrong arity and decodes to nothing — the `se` fixture.
            val fileSchema = file?.schemaId?.let { schemasById[it.toInt()] } ?: schema
            // The key bounds are over the trimmed primary key — every primary key that is not a
            // partition key, in primary-key order — which is what Paimon's key type is once the
            // partition has been taken out of it.
            val keyNames = fileSchema?.primaryKeys.orEmpty().filterNot { it in partitionKeys }
            val keyFields = keyNames.mapNotNull { key -> fileSchema?.fields?.firstOrNull { it.name == key } }
            val keysResolved = keyFields.size == keyNames.size && keyFields.isNotEmpty()
            val keyMin = file?.minKey?.takeIf { keysResolved }?.let { decodePaimonRow(it, keyFields) }
            val keyMax = file?.maxKey?.takeIf { keysResolved }?.let { decodePaimonRow(it, keyFields) }
            val keyBounds = file?.keyStats?.takeIf { keysResolved }?.let { decodePaimonColumnBounds(it, keyFields) }
            // The value statistics cover the schema's fields in order — or _WRITE_COLS, for a file
            // that holds only the columns a MERGE INTO set — or the subset _VALUE_STATS_COLS
            // names; every name has to resolve, or a bound lands on the wrong column. The `de`
            // fixture's patch file has a one-field stats row and a three-field schema.
            val statsNames = file?.valueStatsCols ?: file?.writeCols ?: fileSchema?.fields?.mapNotNull { it.name }.orEmpty()
            val statsFields = statsNames.mapNotNull { name -> fileSchema?.fields?.firstOrNull { it.name == name } }
            val columnBounds = file?.valueStats
                ?.takeIf { statsFields.size == statsNames.size && statsFields.isNotEmpty() }
                ?.let { decodePaimonColumnBounds(it, statsFields) }
            val normalizedResolved = runCatching { dataFilePath.toAbsolutePath().normalize() }
                .getOrElse { dataFilePath.normalize() }
            // A path the table recorded outside itself is the table's own statement and is not
            // checked; the rebuilt ones are ours and must land under the table.
            val recordedElsewhere = pathResolution == PaimonPathResolution.EXTERNAL_RECORDED ||
                pathResolution == PaimonPathResolution.EXTERNAL_REROOTED
            if (!recordedElsewhere && !normalizedResolved.startsWith(normalizedTableRoot)) {
                manifestErrors += UnifiedReadError(
                    stage = "path-traversal-check",
                    path = entry.file?.fileName.orEmpty(),
                    message = "Data file path resolves outside the table directory: $normalizedResolved",
                )
            }
            PaimonUnifiedDataFile(
                path = dataFilePath,
                metadata = entry,
                pathResolution = pathResolution,
                partition = partition,
                keyMin = keyMin,
                keyMax = keyMax,
                keyBounds = keyBounds,
                columnBounds = columnBounds,
                partial = file?.isPartialUnder(fileSchema) ?: false,
            )
        }
    } else {
        emptyList()
    }

    // Decoded here rather than in the entries loop because the schema is the manifest's, not an
    // entry's; a schema whose partition keys do not all resolve to fields decodes nothing.
    val statsSchema = schemasById[meta.schemaId?.toInt()] ?: schemasById.values.maxByOrNull { it.id ?: -1 }
    val statsKeys = statsSchema?.partitionKeys.orEmpty()
    val statsFields = statsKeys.mapNotNull { key -> statsSchema?.fields?.firstOrNull { it.name == key } }
    val decodeStats: (ByteArray?) -> DecodedPaimonPartition? = { bytes ->
        bytes?.takeIf { statsFields.size == statsKeys.size }
            ?.let { decodePaimonPartition(it, statsFields, statsSchema?.options.orEmpty()) }
    }
    return PaimonUnifiedManifest(
        path = manifestPath,
        metadata = meta,
        entries = entries,
        readErrors = manifestErrors,
        partitionMin = decodeStats(meta.partitionStats?.minValues),
        partitionMax = decodeStats(meta.partitionStats?.maxValues),
    )
}

/**
 * Resolves the full path for a Paimon data file: `tablePath / <key>=<value>/… / bucket-N / fileName`.
 *
 * The manifest entry names the file by `_FILE_NAME` only; the partition directories come from
 * its `_PARTITION`, decoded, and the bucket from `_BUCKET`. That path is *the* path — it is
 * returned whether or not the file is there, because a file that is missing should be reported
 * at the place it was supposed to be. Only an entry whose partition could not be decoded falls
 * back to looking under `bucket-N` at the table root, which is where an unpartitioned table's
 * files are anyway, and then to the table root itself.
 */
/**
 * Where a data file is: by the layout — partition directories, then `bucket-N`, then the file
 * name — unless the entry records an `_EXTERNAL_PATH`, which is where `data-file.external-paths`
 * put it. The recorded path wins when the file is there; a copy of the table on another machine
 * finds it by the recorded path's tail under the local warehouse; and a file that is neither
 * place is reported at the layout path it is not at, with a resolution that says so. The `ep`
 * fixture is the external case: its table directory holds no bucket at all.
 */
private fun resolveDataFilePath(tablePath: Path, entry: PaimonManifestEntry, partition: DecodedPaimonPartition?): Pair<Path, PaimonPathResolution> {
    val layout = layoutDataFilePath(tablePath, entry, partition)
    val external = entry.file?.externalPath?.takeIf { it.isNotBlank() } ?: return layout to PaimonPathResolution.LAYOUT
    val recorded = runCatching { service.StorageLocation.pathOf(normalizeFilePath(external)) }.getOrNull()
        ?.takeIf { it.isAbsolute && runCatching { Files.isRegularFile(it) }.getOrDefault(false) }
    if (recorded != null) return recorded to PaimonPathResolution.EXTERNAL_RECORDED
    rerootExternalPath(external, tablePath)?.let { return it to PaimonPathResolution.EXTERNAL_REROOTED }
    return layout to PaimonPathResolution.EXTERNAL_MISSING
}

private fun layoutDataFilePath(tablePath: Path, entry: PaimonManifestEntry, partition: DecodedPaimonPartition?): Path {
    val fileName = entry.file?.fileName ?: return tablePath
    val bucket = entry.bucket
    if (partition != null && bucket != null) {
        val partitionDir = partition.values.fold(tablePath) { dir, value -> dir.resolve("${value.name}=${value.pathText}") }
        return partitionDir.resolve("bucket-$bucket").resolve(fileName)
    }
    if (bucket != null) {
        val candidate = tablePath.resolve("bucket-$bucket").resolve(fileName)
        if (Files.exists(candidate)) return candidate
    }
    logger.debug("Data file not found in bucket-{}, falling back to table root: {}", bucket, fileName)
    return tablePath.resolve(fileName)
}

/**
 * Where a recorded external path lands on this machine, or null when nothing under the local
 * warehouse matches it.
 *
 * A Paimon snapshot records no table location to re-root against, the way an Iceberg manifest's
 * own path lets [rebuildBesideTable] find the warehouse. What the format does fix is the layout
 * above the table: `<warehouse>/<db>.db/<table>`, so the local warehouse is the table's
 * grandparent when its parent is a `.db` directory, and its parent otherwise. The recorded path
 * `/wh/ep-files/bucket-0/x.parquet` is then looked for by every tail of itself under that
 * warehouse, longest first — `wh/ep-files/bucket-0/x.parquet`, `ep-files/bucket-0/x.parquet`,
 * `bucket-0/x.parquet` — and the first that is a file wins. Existence-gated because it is a
 * search rather than a derivation; a tail that escapes the warehouse is skipped.
 */
internal fun rerootExternalPath(external: String, tablePath: Path): Path? {
    val normalized = normalizeFilePath(external)
    val withoutScheme = normalized.substringAfter("://", normalized)
    val segments = withoutScheme.split('/').filter { it.isNotEmpty() }
    if (segments.size < 2) return null
    val tableRoot = runCatching { tablePath.toAbsolutePath().normalize() }.getOrElse { tablePath.normalize() }
    val parent = tableRoot.parent ?: return null
    val warehouse = if (parent.fileName?.toString()?.endsWith(".db") == true) parent.parent ?: return null else parent
    for (drop in 1 until segments.size) {
        val candidate = warehouse.resolve(segments.drop(drop).joinToString("/")).normalize()
        if (!candidate.startsWith(warehouse)) continue
        if (runCatching { Files.isRegularFile(candidate) }.getOrDefault(false)) return candidate
    }
    return null
}
