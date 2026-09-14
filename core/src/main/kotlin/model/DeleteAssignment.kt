package model

/**
 * Which data files a snapshot's delete files can reach, decided from the metadata alone.
 *
 * A scan does not apply every delete file to every data file: it pairs them by two rules, and both
 * are answerable without opening a single delete file. That is worth surfacing because the pairing
 * is invisible in the tree — a delete file is drawn under the manifest that lists it, beside data
 * files it may have nothing to do with — and because a delete file that reaches **nothing** is the
 * table's cheapest recurring cost: a *dangling* delete, still planned against on every scan of the
 * partition, until somebody rewrites the manifest.
 *
 * ### The two rules
 *
 * **Sequence.** A positional delete (and a v3 deletion vector) applies to a data file whose
 * sequence number is less than *or equal to* its own; an equality delete only to one strictly
 * below. The difference is the whole reason equality deletes are safe to write: they must not
 * delete rows a later commit adds, while a positional delete addresses rows that already exist by
 * position and so may be written in the same commit as the data. The number compared is the
 * *inherited* one — see [effectiveSequenceNumber], without which most entries have none at all.
 *
 * **Target.** A v3 deletion vector names its one data file in `referenced_data_file`. A v2
 * positional delete records no such field, but it does record **bounds on its own `file_path`
 * column** — Iceberg's reserved field 2147483546 — and those bounds are what its planner prunes
 * with. When they meet, the file targets exactly one path and the pairing is settled without
 * reading anything; when they span, the range only rules paths *out*. An equality delete has no
 * target at all: it applies by value.
 *
 * ### The two rules that scope the rest
 *
 * **Partition.** `DeleteFileIndex` keys an equality delete, and a positional delete that names no
 * single file, by the *spec and partition* its manifest records, and looks a data file's deletes
 * up by the data file's own — so a delete under partition `p=x` never applies to a file in `p=y`,
 * nor to a file under another spec, even one whose partition renders the same. The one exception
 * is an equality delete written under an **unpartitioned** spec, which is global. A vector, or a
 * positional delete whose bounds name one file, is keyed by path instead and the partition is not
 * consulted. A partition this could not decode is left undecided rather than compared, which can
 * only leave a pair at [DeleteReachVerdict.MAY_REACH].
 *
 * **Bounds.** An equality delete records bounds and null counts on its equality columns like any
 * file, and `canContainEqDeletesForFile` compares them with the data file's before pairing:
 * ranges that do not overlap on some equality column, a data file all-null where the delete holds
 * no null, or the reverse, rule the pair out. Every step is a one-sided proof over recorded
 * figures — a missing bound or count on either side means "may match" — so this reads more than
 * Iceberg only where a figure is missing, never less.
 *
 * Both were left out until `fup` and `fupp` existed to hold them to Iceberg's own plan: a wrong
 * exclusion here hides a delete, which is the failure worth avoiding, and the only defence
 * against it is the planner's answer on engine-written bytes (`IcebergDeletePairingPlanTest`).
 */
data class DeleteReach(
    val deletePath: String,
    /** The delete file's identity — see [DataFile.ledgerKey]; a vector's is its container with the file it references. */
    val deleteKey: String,
    val kind: DeleteFileKind,
    val sequenceNumber: Long,
    val recordCount: Long?,
    /** The data files this delete file is proved to reach, by path. */
    val reaches: List<String>,
    /** The data files nothing ruled out, which the delete file itself would have to settle. */
    val mayReach: List<String>,
    /** What the metadata says about the paths it can target — the range, or the one file it names. */
    val targets: DeleteTargets,
) {
    /**
     * True when no live data file in this snapshot passes both rules.
     *
     * The definition of a dangling delete, and it is a *proof*: the metadata ruled every candidate
     * out. A delete file with candidates left at [mayReach] is not dangling, it is unsettled.
     */
    val isDangling: Boolean get() = reaches.isEmpty() && mayReach.isEmpty()
}

enum class DeleteFileKind { DELETION_VECTOR, POSITIONAL, EQUALITY }

enum class DeleteReachVerdict { REACHES, MAY_REACH, RULED_OUT_BY_TARGET, RULED_OUT_BY_SEQUENCE, RULED_OUT_BY_PARTITION, RULED_OUT_BY_BOUNDS }

/**
 * What `DeleteFileIndex` keys a file on: the spec its manifest records and its partition tuple,
 * rendered as the path segments (`p=x`) — `""` under an unpartitioned spec, null where the tuple
 * could not be decoded and so must not be compared.
 */
data class PartitionScope(val specId: Int?, val partition: String?) {
    val isUnpartitioned: Boolean get() = partition == ""
    val isKnown: Boolean get() = partition != null

    /** The same key, as the index compares it: spec and tuple both. */
    fun sameAs(other: PartitionScope): Boolean = isKnown && other.isKnown && specId == other.specId && partition == other.partition
}

/**
 * `canContainEqDeletesForFile` at 1.8.1: whether an equality delete's recorded figures on its
 * equality columns leave it able to touch the data file. Null where either side lacks the column
 * statistics to say, which is "may match".
 */
fun equalityDeleteMayTouch(data: List<ColumnStats>, delete: List<ColumnStats>, equalityIds: List<Int>, requiredIds: Set<Int>): Boolean {
    for (id in equalityIds) {
        val d = data.firstOrNull { it.fieldId == id }
        val e = delete.firstOrNull { it.fieldId == id }
        val required = id in requiredIds
        // Null bookkeeping, each a fact about counts the writer recorded.
        fun containsNull(s: ColumnStats?) = !required && (s?.nullValueCount == null || s.nullValueCount > 0)
        fun allNull(s: ColumnStats?) = !required && s?.nullValueCount != null && s.valueCount != null && s.nullValueCount == s.valueCount
        fun allNonNull(s: ColumnStats?) = required || (s?.nullValueCount != null && s.nullValueCount <= 0)
        if (containsNull(d) && containsNull(e)) continue
        if (allNull(d) && allNonNull(e)) return false
        if (allNull(e) && allNonNull(d)) return false
        val dl = d?.lowerBound?.value ?: continue
        val du = d.upperBound?.value ?: continue
        val el = e?.lowerBound?.value ?: continue
        val eu = e.upperBound?.value ?: continue
        val lowPastHigh = compareValues(dl, eu) ?: continue
        if (lowPastHigh > 0) return false
        val highBeforeLow = compareValues(el, du) ?: continue
        if (highBeforeLow > 0) return false
    }
    return true
}

/** What one delete file's own metadata says about the data files it can apply to. */
data class DeleteTargets(
    /** The single file a v3 deletion vector names, when it is one. */
    val referenced: String? = null,
    /** The lowest and highest data-file path the delete file's own rows mention, when recorded. */
    val low: String? = null,
    val high: String? = null,
) {
    /** Bounds that meet name one file, which settles the pairing without opening anything. */
    val namesOneFile: Boolean get() = referenced != null || (low != null && low == high)

    /** The one path this file can target, when the metadata pins it to one. */
    val onlyPath: String? get() = referenced ?: low?.takeIf { it == high }
}

/** Iceberg's reserved field id for the `file_path` column of a positional delete file. */
const val DELETE_FILE_PATH_FIELD_ID = 2147483546

/**
 * Every live delete file in [snapshot], and the live data files each can reach.
 *
 * A separate walk from [liveFilesOf] on purpose: that one answers "what does the table hold here",
 * folded through the shared ledger, and this one answers a question about *pairs*. It uses that
 * walk for the live set rather than deciding liveness a second way, so the two cannot disagree
 * about which files are in the snapshot — only about what they mean to each other.
 */
fun deleteReach(snapshot: UnifiedSnapshot): List<DeleteReach> {
    val live = liveFilesOf(snapshot)
    val liveKeys = live.map { it.key }.toSet()

    class Side(val path: String, val key: String, val sequence: Long, val file: DataFile, val scope: PartitionScope, val manifest: UnifiedManifest) {
        val stats: List<ColumnStats> by lazy { columnStatsFor(file, manifest.schema) }
    }
    val data = mutableListOf<Side>()
    val deletes = mutableListOf<Side>()
    val seen = mutableSetOf<String>()
    snapshot.manifests.forEach { manifest ->
        manifest.dataFiles.forEach { unified ->
            val file = unified.metadata.dataFile ?: return@forEach
            val path = file.filePath?.takeIf { it.isNotBlank() } ?: return@forEach
            val normalized = normalizeFilePath(path)
            val key = file.ledgerKey() ?: return@forEach
            // Live only, and once: a manifest is carried forward by every snapshot that still
            // needs it, so one file is listed by one manifest inside a closure — but a table with
            // two manifests naming one path would otherwise pair it twice. By key, not path: two
            // vectors in one Puffin container are two delete files (`pid`).
            if (key !in liveKeys || !seen.add(key)) return@forEach
            val sequence = effectiveSequenceNumber(unified.metadata, manifest.metadata.sequenceNumber)
            val side = Side(normalized, key, sequence, file, PartitionScope(manifest.metadata.partitionSpecId, unified.partition?.path), manifest)
            if (file.content == DataFileContent.DATA || file.content == null) data += side else deletes += side
        }
    }

    return deletes.map { delete ->
        val file = delete.file
        val sequence = delete.sequence
        val kind = deleteKindOf(file) ?: DeleteFileKind.POSITIONAL
        val targets = deleteTargetsOf(file)
        val requiredIds = delete.manifest.schema?.struct?.fields?.filter { it.required }?.map { it.id }?.toSet().orEmpty()
        val reaches = mutableListOf<String>()
        val mayReach = mutableListOf<String>()
        data.forEach { d ->
            val overlap = if (kind == DeleteFileKind.EQUALITY) {
                { equalityDeleteMayTouch(d.stats, delete.stats, file.equalityIds.orEmpty(), requiredIds) }
            } else null
            when (reachVerdict(kind, sequence, targets, d.path, d.sequence, delete.scope, d.scope, overlap)) {
                DeleteReachVerdict.REACHES -> reaches += d.path
                DeleteReachVerdict.MAY_REACH -> mayReach += d.path
                else -> Unit
            }
        }
        DeleteReach(
            deletePath = normalizeFilePath(file.filePath.orEmpty()),
            deleteKey = delete.key,
            kind = kind,
            sequenceNumber = sequence,
            recordCount = file.recordCount,
            reaches = reaches.sorted(),
            mayReach = mayReach.sorted(),
            targets = targets,
        )
    }.sortedBy { it.deleteKey }
}

/**
 * Which of the three a file is, or null when it is not a delete file at all.
 *
 * `content` alone does not separate the first two: a v3 deletion vector and a v2 positional delete
 * both declare 1, and only `content_offset` — the byte range of the blob inside a Puffin container
 * — is written for a vector. A `.puffin` extension is a naming convention, not a statement.
 */
fun deleteKindOf(file: DataFile): DeleteFileKind? = when {
    file.contentOffset != null -> DeleteFileKind.DELETION_VECTOR
    file.content == DataFileContent.EQUALITY_DELETES -> DeleteFileKind.EQUALITY
    file.content == DataFileContent.POSITION_DELETES -> DeleteFileKind.POSITIONAL
    else -> null
}

/** One delete file weighed against one data file, for the panel that stands on the data file. */
data class DeleteCandidate(
    val delete: GraphNode.FileNode,
    val kind: DeleteFileKind,
    val verdict: DeleteReachVerdict,
)

/**
 * Every delete file among [candidates] weighed against [dataFile], by the same two rules.
 *
 * This is [deleteReach] asked from the other end, and it is deliberately *not* scoped to a
 * snapshot: both operands' sequence numbers and recorded targets are facts about the files
 * themselves, so the pairing needs no closure walked. What that gives up is liveness — a delete
 * file a later commit removed is still drawn, and is still listed here — which is the same scope
 * `evaluateScan` already answers in, and the panel says so rather than implying a snapshot.
 */
fun deleteCandidatesFor(
    dataFile: GraphNode.FileNode,
    candidates: List<GraphNode.FileNode>,
): List<DeleteCandidate> {
    val dataPath = normalizeFilePath(dataFile.data.filePath.orEmpty())
    if (dataPath.isEmpty() || deleteKindOf(dataFile.data) != null) return emptyList()
    val dataScope = PartitionScope(dataFile.specId, dataFile.partition?.path)
    return candidates.mapNotNull { node ->
        val kind = deleteKindOf(node.data) ?: return@mapNotNull null
        val requiredIds = node.schema?.struct?.fields?.filter { it.required }?.map { it.id }?.toSet().orEmpty()
        DeleteCandidate(
            delete = node,
            kind = kind,
            verdict = reachVerdict(
                kind = kind,
                deleteSequence = node.sequenceNumber,
                targets = deleteTargetsOf(node.data),
                dataPath = dataPath,
                dataSequence = dataFile.sequenceNumber,
                deleteScope = PartitionScope(node.specId, node.partition?.path),
                dataScope = dataScope,
                equalityMayTouch = if (kind == DeleteFileKind.EQUALITY) {
                    { equalityDeleteMayTouch(dataFile.columnStats, node.columnStats, node.data.equalityIds.orEmpty(), requiredIds) }
                } else null,
            ),
        )
    }
}

/**
 * What a delete file records about the paths it can apply to.
 *
 * The bounds are read as UTF-8 rather than through [SingleValueDecoder], because the reserved
 * field is not in the table's schema — a manifest carries the *table's* schema, and `file_path`
 * belongs to the delete file's own. There is nothing to look the type up in, and the spec fixes it
 * as a string.
 */
fun deleteTargetsOf(file: DataFile): DeleteTargets = DeleteTargets(
    referenced = file.referencedDataFile?.takeIf { it.isNotBlank() },
    low = file.lowerBounds?.firstOrNull { it.key == DELETE_FILE_PATH_FIELD_ID }
        ?.value?.toString(Charsets.UTF_8),
    high = file.upperBounds?.firstOrNull { it.key == DELETE_FILE_PATH_FIELD_ID }
        ?.value?.toString(Charsets.UTF_8),
)

/**
 * Whether one delete file reaches one data file, or which rule ruled it out.
 *
 * The order matters for the reason a reader is given, not for the answer: sequence is checked
 * first because it is the rule that surprises people — a delete file sitting in the same snapshot
 * as a data file written after it applies to nothing in it, and no path comparison would say so.
 */
fun reachVerdict(
    kind: DeleteFileKind,
    deleteSequence: Long,
    targets: DeleteTargets,
    dataPath: String,
    dataSequence: Long,
    /** The delete file's spec and partition, and the data file's — see [PartitionScope]; either null leaves the partition out. */
    deleteScope: PartitionScope? = null,
    dataScope: PartitionScope? = null,
    /** For an equality delete, whether its bounds leave it able to touch the file — see [equalityDeleteMayTouch]; null leaves the bounds out. */
    equalityMayTouch: (() -> Boolean)? = null,
): DeleteReachVerdict {
    // Both numbers always exist: an entry inherits its manifest's, and a v1 manifest's is 0 by
    // the spec — see [effectiveSequenceNumber]. A delete written after a v1 table's upgrade
    // therefore reaches every file the table had, which is the rule and not a gap in it.
    val ordered = when (kind) {
        // An equality delete must not touch rows a later commit adds, so it applies strictly
        // below its own number. A positional delete addresses rows by position in a file that
        // already exists, so it may be written in the same commit as the data it deletes from.
        DeleteFileKind.EQUALITY -> deleteSequence > dataSequence
        else -> deleteSequence >= dataSequence
    }
    if (!ordered) return DeleteReachVerdict.RULED_OUT_BY_SEQUENCE

    // A vector, and a positional delete whose bounds name one file, are keyed by path; the
    // partition is never consulted for them.
    targets.onlyPath?.let { only ->
        return if (normalizeFilePath(only) == dataPath) DeleteReachVerdict.REACHES
        else DeleteReachVerdict.RULED_OUT_BY_TARGET
    }

    // Everything else is keyed by spec and partition — except an equality delete under an
    // unpartitioned spec, which is global. Two scopes this could not decode are not compared.
    if (deleteScope != null && dataScope != null && deleteScope.isKnown && dataScope.isKnown) {
        val global = kind == DeleteFileKind.EQUALITY && deleteScope.isUnpartitioned
        if (!global && !deleteScope.sameAs(dataScope)) return DeleteReachVerdict.RULED_OUT_BY_PARTITION
    }

    if (kind == DeleteFileKind.EQUALITY && equalityMayTouch != null && !equalityMayTouch()) {
        return DeleteReachVerdict.RULED_OUT_BY_BOUNDS
    }

    val low = targets.low
    val high = targets.high
    if (low != null && high != null) {
        // Both sides go through the same normalisation before being ordered. The bounds are the
        // minimum and maximum of the delete file's own `file_path` strings, so comparing a
        // normalised path against a raw bound would order two different spellings of one path
        // against each other — which is how a range test starts answering about an order nothing
        // produced. Within one table the writer spells them the same way, so normalising both is
        // order-preserving.
        if (dataPath < normalizeFilePath(low) || dataPath > normalizeFilePath(high)) {
            return DeleteReachVerdict.RULED_OUT_BY_TARGET
        }
    }
    return DeleteReachVerdict.MAY_REACH
}
