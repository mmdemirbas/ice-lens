package model

import service.DeltaCheckpointFile
import service.DeltaCheckpointRead
import service.DeltaLogListing
import service.DeltaReader
import service.StorageLocation
import service.TableFormat
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** One commit file: its version, where it is, and what it holds. */
data class DeltaCommit(
    val version: Long,
    val path: Path,
    val actions: List<DeltaAction>,
    /** The file's modification time — what a commit without `inCommitTimestamp` is dated by. */
    val fileModifiedMs: Long?,
) {
    val commitInfo: DeltaCommitInfo? get() = actions.firstNotNullOfOrNull { it.commitInfo }

    /**
     * When the commit happened: `inCommitTimestamp` where the feature wrote one, else the commit
     * file's modification time — which is what Delta's own history and time travel read
     * (`DeltaHistoryManager`), and what `commitInfo.timestamp` usually but not always equals.
     */
    val timestampMs: Long? get() = commitInfo?.inCommitTimestamp ?: fileModifiedMs ?: commitInfo?.timestamp

    val adds: List<DeltaAddFile> get() = actions.mapNotNull { it.add }
    val removes: List<DeltaRemoveFile> get() = actions.mapNotNull { it.remove }
    val cdcs: List<DeltaCdcFile> get() = actions.mapNotNull { it.cdc }
    val metadata: DeltaMetadata? get() = actions.firstNotNullOfOrNull { it.metaData }
    val protocol: DeltaProtocol? get() = actions.firstNotNullOfOrNull { it.protocol }
}

/**
 * A table version as the log reconstructs it (`PROTOCOL.md`, "Action Reconciliation"): the latest
 * protocol and metadata, the latest `txn` per application and `domainMetadata` per domain, and of
 * the file actions the newest reference to each logical file — an `add` is a live file, a
 * `remove` a tombstone kept for VACUUM. [files] is in the order the replay met the adds.
 */
data class DeltaState(
    val version: Long,
    val protocol: DeltaProtocol?,
    val metadata: DeltaMetadata?,
    val files: Map<DeltaFileKey, DeltaAddFile>,
    val tombstones: Map<DeltaFileKey, DeltaRemoveFile>,
    val txns: Map<String, DeltaTxn>,
    val domains: Map<String, DeltaDomainMetadata>,
    /** The checkpoint the replay started from; null where it started from version 0. */
    val fromCheckpoint: Long?,
    /** The commits replayed on top of that start, oldest first. */
    val commitsReplayed: List<Long>,
) {
    val recordCount: Long? get() = files.values.map { it.parsedStats?.numRecords }.let { rows -> if (rows.any { it == null }) null else rows.sumOf { it!! } }

    /** Rows a deletion vector marks across the live files — the rows the recorded counts still include. */
    val deletedByVectors: Long get() = files.values.sumOf { it.deletionVector?.cardinality ?: 0L }

    val sizeBytes: Long get() = files.values.sumOf { it.size ?: 0L }
}

/** Why a version cannot be rebuilt — its commit gone and no checkpoint at or below it to start from. */
data class DeltaUnreconstructable(val version: Long, val reason: String)

/** Folds actions onto a state, the protocol's reconciliation rules, in the order given. */
internal class DeltaReplay(start: DeltaState?) {
    var protocol: DeltaProtocol? = start?.protocol
    var metadata: DeltaMetadata? = start?.metadata
    val files = LinkedHashMap(start?.files.orEmpty())
    val tombstones = LinkedHashMap(start?.tombstones.orEmpty())
    val txns = LinkedHashMap(start?.txns.orEmpty())
    val domains = LinkedHashMap(start?.domains.orEmpty())

    /**
     * One commit's (or one checkpoint's) actions. Within a commit the protocol forbids two actions
     * of one type for one path, so removes and adds never contend for a key there, and applying
     * them in file order is the same as any order.
     */
    fun apply(actions: List<DeltaAction>) {
        for (a in actions) {
            a.protocol?.let { protocol = it }
            a.metaData?.let { metadata = it }
            a.txn?.let { txns[it.appId] = it }
            a.domainMetadata?.let { if (it.removed) domains.remove(it.domain) else domains[it.domain] = it }
            a.add?.let { add ->
                tombstones.remove(add.key)
                files.remove(add.key) // re-insert, so the map's order is the order last met
                files[add.key] = add
            }
            a.remove?.let { rm ->
                files.remove(rm.key)
                tombstones[rm.key] = rm
            }
        }
    }

    fun state(version: Long, fromCheckpoint: Long?, replayed: List<Long>) =
        DeltaState(version, protocol, metadata, LinkedHashMap(files), LinkedHashMap(tombstones), txns.toMap(), domains.toMap(), fromCheckpoint, replayed)
}

/**
 * A Delta Lake table read from its `_delta_log/`.
 *
 * Every commit file is read, because each is small and each is a node on the graph; checkpoints
 * are read on first use, because a checkpoint is the whole table's file list and most of them are
 * never asked for. A version is rebuilt from the newest complete checkpoint at or below it plus the
 * commits after it; where a version's commit has been cleaned up and no checkpoint covers it, it is
 * [DeltaUnreconstructable] — a state the log's retention defines, not a failure to read.
 */
class DeltaUnifiedTableModel(
    override val path: Path,
    val listing: DeltaLogListing,
    val commits: List<DeltaCommit>,
    val lastCheckpoint: DeltaLastCheckpoint?,
    private val errors: MutableList<UnifiedReadError>,
) : FormatTableModel {
    override val name: String get() = path.fileName?.toString() ?: path.toString()
    override val format: TableFormat get() = TableFormat.DELTA
    override val readErrors: List<UnifiedReadError> get() = errors.toList()

    val commitByVersion: Map<Long, DeltaCommit> = commits.associateBy { it.version }

    /** Every checkpoint, oldest first, a version's several in listing order. */
    val checkpoints: List<DeltaCheckpointFile> = listing.checkpoints.values.flatten()

    /** Every version the log names — by a commit or a checkpoint — oldest first. */
    val versions: List<Long> = (listing.commits.keys + listing.checkpoints.keys).distinct().sorted()

    val latestVersion: Long? get() = versions.lastOrNull()

    private val checkpointReads = ConcurrentHashMap<DeltaCheckpointFile, Result<DeltaCheckpointRead>>()

    /** A checkpoint's actions, read once; a failure is recorded as a read error and answered as null. */
    fun checkpointRead(checkpoint: DeltaCheckpointFile): DeltaCheckpointRead? =
        checkpointReads.computeIfAbsent(checkpoint) { cp ->
            runCatching { DeltaReader.readCheckpoint(cp, listing.logDir) }.onFailure { e ->
                synchronized(errors) { errors += toError("read-delta-checkpoint", cp.parts.first(), e) }
            }
        }.getOrNull()

    /** The checkpoint a replay to [version] would start from: the newest complete, readable one at or below it. */
    fun startingCheckpoint(version: Long): DeltaCheckpointFile? =
        listing.checkpoints.filterKeys { it <= version }.toSortedMap(compareByDescending { it }).values.asSequence()
            .flatMap { it.asSequence() }
            .filter { it.complete }
            .firstOrNull { checkpointRead(it) != null }

    private val states = ConcurrentHashMap<Long, Result<DeltaState>>()

    /** [version] rebuilt, or why it cannot be. */
    fun stateAt(version: Long): Result<DeltaState> = states.computeIfAbsent(version) { v ->
        val start = startingCheckpoint(v)
        val from = start?.version?.plus(1) ?: 0L
        val missing = (from..v).filter { it !in commitByVersion }
        if (missing.isNotEmpty()) {
            return@computeIfAbsent Result.failure(
                DeltaVersionUnavailable(
                    DeltaUnreconstructable(
                        v,
                        if (start == null) "no checkpoint at or below $v, and commit ${missing.first()} is not in the log"
                        else "the replay from checkpoint ${start.version} needs commit ${missing.first()}, which is not in the log",
                    ),
                ),
            )
        }
        val replay = DeltaReplay(null)
        start?.let { cp -> replay.apply(checkpointRead(cp)!!.allActions) }
        val replayed = (from..v).toList()
        replayed.forEach { replay.apply(commitByVersion.getValue(it).actions) }
        Result.success(replay.state(v, start?.version, replayed))
    }

    /**
     * [version] rebuilt from the commits alone, ignoring every checkpoint — null where a commit at or
     * below it is gone. It is what a checkpoint is checked against: a checkpoint is the replay of
     * the commits up to its version, so the two are two readings of one set.
     */
    fun stateFromCommits(version: Long): DeltaState? {
        if ((0L..version).any { it !in commitByVersion }) return null
        val replay = DeltaReplay(null)
        (0L..version).forEach { replay.apply(commitByVersion.getValue(it).actions) }
        return replay.state(version, null, (0L..version).toList())
    }

    /** The table as it stands; null where even the latest version cannot be rebuilt. */
    val current: DeltaState? by lazy { latestVersion?.let { stateAt(it).getOrNull() } }

    /** A data file's path as the log records it, resolved against the table — see [resolveDeltaPath]. */
    fun resolve(recorded: String): Path = resolveDeltaPath(path, recorded)

    companion object {
        /** Reads the log at [path]; a table whose `_delta_log/` cannot be listed has no versions and one error. */
        operator fun invoke(path: Path): DeltaUnifiedTableModel {
            val errors = mutableListOf<UnifiedReadError>()
            val listing = runCatching { DeltaReader.list(path) }.getOrElse { e ->
                errors += toError("list-delta-log", path.resolve(DeltaReader.LOG_DIR), e)
                DeltaLogListing(path.resolve(DeltaReader.LOG_DIR), sortedMapOf(), sortedMapOf(), emptyList(), null, emptyList())
            }
            val commits = listing.commits.mapNotNull { (v, p) ->
                runCatching {
                    DeltaCommit(v, p, DeltaReader.readCommit(p), runCatching { Files.getLastModifiedTime(p).toMillis() }.getOrNull())
                }.getOrElse { e -> errors += toError("read-delta-commit", p, e); null }
            }
            val last = listing.lastCheckpoint?.let { p ->
                runCatching { DeltaReader.readLastCheckpoint(p) }.getOrElse { e -> errors += toError("read-delta-last-checkpoint", p, e); null }
            }
            return DeltaUnifiedTableModel(path, listing, commits, last, errors)
        }
    }
}

/** The failure [DeltaUnifiedTableModel.stateAt] answers with where the log cannot rebuild a version. */
class DeltaVersionUnavailable(val detail: DeltaUnreconstructable) : RuntimeException(detail.reason)

/**
 * A path the log records, as a path to open. An `add` path is a URI (RFC 2396): relative to the
 * table root unless it carries a scheme, and percent-encoded — a partition value with a space is
 * written `region=north%20america` in the log and `region=north america` on disk. `+` is a
 * literal in a URI path, so it is protected before decoding.
 */
fun resolveDeltaPath(tableRoot: Path, recorded: String): Path {
    val decoded = java.net.URLDecoder.decode(recorded.replace("+", "%2B"), Charsets.UTF_8)
    val hasScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(recorded)
    if (!hasScheme) return tableRoot.resolve(decoded)
    val normalised = normalizeFilePath(decoded)
    return StorageLocation.pathOf(normalised)
}

/**
 * Where a deletion vector's bytes are (`PROTOCOL.md`, "Deletion Vector Descriptor Schema"):
 * `u` a file under the table named from the UUID the last 20 characters encode, after an optional
 * random prefix that is also a directory; `p` an absolute path; `i` inline, which has no file.
 */
fun DeltaDeletionVector.filePath(tableRoot: Path): Path? {
    val value = pathOrInlineDv ?: return null
    return when (storageType) {
        "u" -> {
            if (value.length < 20) return null
            val prefix = value.dropLast(20)
            val uuid = runCatching { Z85.decodeUuid(value.takeLast(20)) }.getOrNull() ?: return null
            val name = "deletion_vector_$uuid.bin"
            if (prefix.isEmpty()) tableRoot.resolve(name) else tableRoot.resolve(prefix).resolve(name)
        }
        "p" -> resolveDeltaPath(tableRoot, value)
        else -> null
    }
}

/** The three file actions a commit writes. */
enum class DeltaFileAction(val label: String) { ADD("Add"), REMOVE("Remove"), CDC("Change data") }

/**
 * A file's partition as the path Hive layout would give it — `region=eu/dt=2024-03-05` — in the
 * table's partition column order. A null value reads `null`: the log stores it as JSON null, and
 * the directory the writer used for it (`__HIVE_DEFAULT_PARTITION__`) is the writer's choice.
 */
fun deltaPartitionText(partitionColumns: List<String>, values: Map<String, String?>): String? =
    partitionColumns.takeIf { it.isNotEmpty() }?.joinToString("/") { "$it=${values[it] ?: "null"}" }

/**
 * The live set as the comparison and the partition breakdown read it — one [LiveFile] per logical
 * file, keyed by [DeltaFileKey] so two vectors on one path are two entries as they are to the log.
 * A file with a vector carries its record count less the vector's cardinality: `numRecords`
 * counts the rows written, and a read skips the rows the vector marks.
 */
fun DeltaState.liveFiles(): List<LiveFile> {
    val partitionColumns = metadata?.partitionColumns.orEmpty()
    return files.map { (key, add) ->
        LiveFile(
            path = add.path,
            content = 0,
            recordCount = (add.parsedStats?.numRecords ?: 0L) - (add.deletionVector?.cardinality ?: 0L),
            sizeBytes = add.size ?: 0L,
            partition = deltaPartitionText(partitionColumns, add.partitionValues),
            sequenceNumber = add.defaultRowCommitVersion,
            format = "parquet",
            key = key.toString(),
        )
    }
}

/** The table-wide facts a Delta log states, for the table panel and the strip. */
data class DeltaTableFacts(
    val minReaderVersion: Int?,
    val minWriterVersion: Int?,
    val features: List<String>,
    val partitionColumns: List<String>,
    val columnMappingMode: String,
    val checkpointVersions: List<Long>,
    val lastCheckpointVersion: Long?,
    /** The earliest version whose commit is still in the log — older ones were cleaned up after a checkpoint. */
    val earliestCommit: Long?,
    val configuration: Map<String, String?>,
    /** The current schema as the Iceberg read path takes it — see [deltaReadSchema]; what a filter binds against. */
    val readSchema: IcebergSchemaModel? = null,
) {
    val describeProtocol: String
        get() = "reader $minReaderVersion, writer $minWriterVersion" + (features.takeIf { it.isNotEmpty() }?.let { " — " + it.joinToString(", ") } ?: "")
}

fun DeltaUnifiedTableModel.tableFacts(): DeltaTableFacts {
    val state = current
    return DeltaTableFacts(
        minReaderVersion = state?.protocol?.minReaderVersion,
        minWriterVersion = state?.protocol?.minWriterVersion,
        features = state?.protocol?.features.orEmpty(),
        partitionColumns = state?.metadata?.partitionColumns.orEmpty(),
        columnMappingMode = state?.metadata?.columnMappingMode ?: "none",
        checkpointVersions = listing.checkpoints.keys.toList(),
        lastCheckpointVersion = lastCheckpoint?.version,
        earliestCommit = commits.firstOrNull()?.version,
        configuration = state?.metadata?.configuration.orEmpty(),
        readSchema = state?.metadata?.schema?.let { deltaReadSchema(it).schema },
    )
}
