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
 * ### What this is not
 *
 * The partition is not compared. Iceberg only ever applies a delete file within its own partition,
 * so including it would rule *more* out — and a wrong exclusion here hides a delete, which is the
 * failure worth avoiding. Leaving it out can only leave a pair at [DeleteReachVerdict.MAY_REACH],
 * which is the absence of a proof and is what the panel says. In practice the paths carry the
 * partition directory, so the bounds usually settle it anyway.
 */
data class DeleteReach(
    val deletePath: String,
    val kind: DeleteFileKind,
    val sequenceNumber: Long?,
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

enum class DeleteReachVerdict { REACHES, MAY_REACH, RULED_OUT_BY_TARGET, RULED_OUT_BY_SEQUENCE }

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
    val livePaths = live.map { normalizeFilePath(it.path) }.toSet()

    val data = mutableListOf<Pair<String, Long?>>()
    val deletes = mutableListOf<Triple<ManifestEntry, Long?, DataFile>>()
    val seen = mutableSetOf<String>()
    snapshot.manifests.forEach { manifest ->
        manifest.dataFiles.forEach { unified ->
            val file = unified.metadata.dataFile ?: return@forEach
            val path = file.filePath?.takeIf { it.isNotBlank() } ?: return@forEach
            val normalized = normalizeFilePath(path)
            // Live only, and once: a manifest is carried forward by every snapshot that still
            // needs it, so one file is listed by one manifest inside a closure — but a table with
            // two manifests naming one path would otherwise pair it twice.
            if (normalized !in livePaths || !seen.add(normalized)) return@forEach
            val sequence = effectiveSequenceNumber(unified.metadata, manifest.metadata.sequenceNumber)
            if (file.content == DataFileContent.DATA || file.content == null) {
                data += normalized to sequence
            } else {
                deletes += Triple(unified.metadata, sequence, file)
            }
        }
    }

    return deletes.map { (entry, sequence, file) ->
        val kind = deleteKindOf(file) ?: DeleteFileKind.POSITIONAL
        val targets = deleteTargetsOf(file)
        val reaches = mutableListOf<String>()
        val mayReach = mutableListOf<String>()
        data.forEach { (dataPath, dataSequence) ->
            when (reachVerdict(kind, sequence, targets, dataPath, dataSequence)) {
                DeleteReachVerdict.REACHES -> reaches += dataPath
                DeleteReachVerdict.MAY_REACH -> mayReach += dataPath
                else -> Unit
            }
        }
        DeleteReach(
            deletePath = normalizeFilePath(file.filePath.orEmpty()),
            kind = kind,
            sequenceNumber = sequence,
            recordCount = file.recordCount,
            reaches = reaches.sorted(),
            mayReach = mayReach.sorted(),
            targets = targets,
        )
    }.sortedBy { it.deletePath }
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
    return candidates.mapNotNull { node ->
        val kind = deleteKindOf(node.data) ?: return@mapNotNull null
        DeleteCandidate(
            delete = node,
            kind = kind,
            verdict = reachVerdict(
                kind = kind,
                deleteSequence = node.sequenceNumber,
                targets = deleteTargetsOf(node.data),
                dataPath = dataPath,
                dataSequence = dataFile.sequenceNumber,
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
    deleteSequence: Long?,
    targets: DeleteTargets,
    dataPath: String,
    dataSequence: Long?,
): DeleteReachVerdict {
    if (deleteSequence != null && dataSequence != null) {
        val ordered = when (kind) {
            // An equality delete must not touch rows a later commit adds, so it applies strictly
            // below its own number. A positional delete addresses rows by position in a file that
            // already exists, so it may be written in the same commit as the data it deletes from.
            DeleteFileKind.EQUALITY -> deleteSequence > dataSequence
            else -> deleteSequence >= dataSequence
        }
        if (!ordered) return DeleteReachVerdict.RULED_OUT_BY_SEQUENCE
    }

    targets.onlyPath?.let { only ->
        return if (normalizeFilePath(only) == dataPath) DeleteReachVerdict.REACHES
        else DeleteReachVerdict.RULED_OUT_BY_TARGET
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
