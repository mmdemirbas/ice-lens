package model

/**
 * What replaying a Paimon snapshot's manifests produced: the per-manifest ledger, and the file
 * set it ends on.
 *
 * Both come out of one walk on purpose. The figures and the contents are two readings of the same
 * replay, and computing them separately would be a second implementation of the same rule — the
 * project's standing objection to a total that lives beside its explanation rather than being
 * folded from it.
 */
data class PaimonReplay(
    val contributions: List<ManifestContribution>,
    /** The entries the replay ends holding, by file key, in the order it last wrote each one. */
    val liveEntries: Map<String, PaimonUnifiedDataFile>,
    /**
     * Per-entry detail for the one manifest that was asked for, in apply order. Empty otherwise.
     *
     * See [replayPaimonSnapshot]'s `traceFor` for why this is one manifest rather than all of them.
     */
    val trace: List<PaimonEntryTrace> = emptyList(),
) {
    /** File key to its metadata — [liveEntries] without the entry around each. */
    val liveFiles: Map<String, PaimonDataFileMeta?> by lazy { liveEntries.mapValues { it.value.metadata.file } }
}

/**
 * What one Paimon manifest entry did to the live set when the replay reached it.
 *
 * The Iceberg twin is `manifestLedger`, and the difference between them is the whole point.
 * `manifestLedger` decides an entry **on its own** - a `DELETED` status contributes nothing, a
 * repeated path is a duplicate - so it can be re-run for any manifest in isolation. A Paimon entry
 * has no such reading: `_KIND=1` means "remove what is there", and whether anything *is* there was
 * decided by the entries before it. So this records the state the entry met rather than a verdict
 * about the entry, and [effect] is the outcome of the two together.
 */
data class PaimonEntryTrace(
    val fileKey: String,
    val fileName: String?,
    /** `PaimonEntryKind.ADD` or `.DELETE`, as recorded. */
    val kind: Int,
    /** Whether the live set already held this file when the entry was applied. */
    val wasLive: Boolean,
    /** What the live set held for this file before, when it held anything. */
    val previousRecordCount: Long?,
    val previousSizeBytes: Long?,
    val effect: PaimonEntryEffect,
    /** What this entry moved the running totals by. Negative for a removal. */
    val liveFileDelta: Int,
    val recordDelta: Long,
    val byteDelta: Long,
)

/**
 * The four things a Paimon entry can turn out to have done.
 *
 * [REMOVED_ABSENT] is the one worth having a name for: a delta that removes a file the base never
 * listed changes nothing at all, and in the aggregate it is indistinguishable from an entry that
 * was never written. It is also the shape a rolled-back or re-applied commit leaves behind.
 */
enum class PaimonEntryEffect(val label: String) {
    ADDED("added"),
    REPLACED("replaced"),
    REMOVED("removed"),
    REMOVED_ABSENT("removed (absent)"),
}

/** Identity of a Paimon data file, for deduplicating it across manifests. */
internal fun paimonDataFileKey(entry: PaimonUnifiedDataFile): String =
    entry.metadata.file?.fileName?.takeIf { it.isNotBlank() } ?: "path:${entry.path}"

internal fun paimonManifestKey(manifest: PaimonUnifiedManifest): String =
    manifest.metadata.fileName?.takeIf { it.isNotBlank() } ?: "path:${manifest.path}"

/**
 * The files a Paimon snapshot actually exposes, and what each manifest contributed to reaching them.
 *
 * **This is a replay, not a filter, and that is the difference from Iceberg.** `manifestLedger`
 * decides each entry on its own — a `DELETED` status contributes nothing, a repeated path is a
 * duplicate — so it can fold entries in any order. Paimon splits a snapshot's manifest lists into
 * a *base*, the accumulated state carried forward, and a *delta*, this commit's changes, and the
 * delta is applied **over** the base: a `_KIND=1` entry removes a file the base still lists. An
 * entry's meaning therefore depends on what the entries before it did, which is why this cannot be
 * expressed as the shared per-entry ledger and why a contribution here may be negative.
 *
 * Counting ADD entries alone would report every file the table has ever held. The changelog
 * manifest list is excluded on purpose: it carries the change stream, not the table's contents.
 *
 * Running totals rather than a sum over the live map per manifest — re-summing for each one would
 * be quadratic in the number of files.
 */
fun replayPaimonSnapshot(
    snapshot: PaimonUnifiedSnapshot?,
    /**
     * The manifest to record a per-entry [PaimonEntryTrace] for, by [paimonManifestKey], or null.
     *
     * **One manifest, not all of them, and it still costs the whole replay.** An entry's effect is
     * decided by the state the entries before it left, so the trace for a manifest halfway through
     * the delta list cannot be produced without replaying everything ahead of it - which is why
     * this is a parameter on the walk rather than a function that takes a manifest. Recording every
     * entry of every manifest would hold the whole table's entry list in memory for a panel that
     * shows one, so the caller names the one it is about to draw. The Iceberg side scopes its
     * drill-down instead of replaying it, and that difference is not an inconsistency: it is what
     * "filter" and "replay" mean.
     */
    traceFor: String? = null,
): PaimonReplay {
    if (snapshot == null) return PaimonReplay(emptyList(), emptyMap())

    val liveFiles = LinkedHashMap<String, PaimonUnifiedDataFile>()
    val countedManifests = mutableMapOf<String, String>()
    val contributions = mutableListOf<ManifestContribution>()
    val trace = mutableListOf<PaimonEntryTrace>()
    var liveRecords = 0L
    var liveBytes = 0L
    var livePartialRecords = 0L

    fun consume(manifests: List<PaimonUnifiedManifest>, listLabel: String) {
        manifests.forEach { manifest ->
            val key = paimonManifestKey(manifest)
            countedManifests[key]?.let { firstCountedIn ->
                contributions += ManifestContribution(
                    manifestPath = manifest.path.toString(),
                    delta = ContentStats(),
                    firstCountedIn = firstCountedIn,
                )
                return@forEach
            }
            countedManifests[key] = listLabel

            val filesBefore = liveFiles.size
            val recordsBefore = liveRecords
            val bytesBefore = liveBytes
            val partialBefore = livePartialRecords
            var entries = 0
            var deletedEntries = 0
            var suppressed = 0

            val tracing = traceFor != null && traceFor == key
            manifest.entries.forEach { entry ->
                entries++
                val fileKey = paimonDataFileKey(entry)
                val wasLive = liveFiles.containsKey(fileKey)
                // containsKey rather than the result of remove/put: the map's value type is itself
                // nullable, so a null return cannot tell "absent" from "present, no metadata" and
                // the running totals would drift on files with no meta block.
                val previousEntry = if (wasLive) liveFiles[fileKey] else null
                val previous = previousEntry?.metadata?.file
                val filesAtEntry = liveFiles.size
                val recordsAtEntry = liveRecords
                val bytesAtEntry = liveBytes

                if (wasLive) {
                    liveRecords -= previous?.rowCount ?: 0L
                    liveBytes -= previous?.fileSize ?: 0L
                    if (previousEntry?.partial == true) livePartialRecords -= previous?.rowCount ?: 0L
                }
                val kind = entry.metadata.kind ?: PaimonEntryKind.ADD
                if (kind == PaimonEntryKind.DELETE) {
                    deletedEntries++
                    liveFiles.remove(fileKey)
                } else {
                    if (wasLive) suppressed++
                    liveFiles[fileKey] = entry
                    liveRecords += entry.metadata.file?.rowCount ?: 0L
                    liveBytes += entry.metadata.file?.fileSize ?: 0L
                    if (entry.partial) livePartialRecords += entry.metadata.file?.rowCount ?: 0L
                }

                if (tracing) {
                    trace += PaimonEntryTrace(
                        fileKey = fileKey,
                        fileName = entry.metadata.file?.fileName,
                        kind = kind,
                        wasLive = wasLive,
                        previousRecordCount = previous?.rowCount.takeIf { wasLive },
                        previousSizeBytes = previous?.fileSize.takeIf { wasLive },
                        effect = when {
                            kind == PaimonEntryKind.DELETE && wasLive -> PaimonEntryEffect.REMOVED
                            kind == PaimonEntryKind.DELETE -> PaimonEntryEffect.REMOVED_ABSENT
                            wasLive -> PaimonEntryEffect.REPLACED
                            else -> PaimonEntryEffect.ADDED
                        },
                        // Read off the running totals rather than computed from the entry, so the
                        // trace and the manifest's own figures cannot disagree: they are the same
                        // subtraction at two scales.
                        liveFileDelta = liveFiles.size - filesAtEntry,
                        recordDelta = liveRecords - recordsAtEntry,
                        byteDelta = liveBytes - bytesAtEntry,
                    )
                }
            }

            contributions += ManifestContribution(
                manifestPath = manifest.path.toString(),
                delta = ContentStats(
                    // Paimon has no data/delete manifest split — every manifest carries both kinds
                    // of entry — so all manifests are data manifests and removals show as
                    // deletedEntryCount.
                    dataManifestCount = 1,
                    manifestEntryCount = entries,
                    deletedEntryCount = deletedEntries,
                    dataFileCount = liveFiles.size - filesBefore,
                    recordCount = liveRecords - recordsBefore,
                    dataSizeBytes = liveBytes - bytesBefore,
                    partialRecordCount = livePartialRecords - partialBefore,
                ),
                entriesSuppressedAsDuplicate = suppressed,
            )
        }
    }

    consume(snapshot.baseManifests, "base manifest list")
    consume(snapshot.deltaManifests, "delta manifest list")
    return PaimonReplay(contributions.toList(), liveFiles, trace.toList())
}

/**
 * Every file a Paimon snapshot holds, in the shape [snapshotDiff] compares.
 *
 * The Iceberg twin is [liveFilesOf]. Both return the same type from the same reading — "what does
 * the table contain at this point" — which is what lets one comparison serve both formats; how
 * each arrives there is entirely different, and neither borrows the other's rule.
 *
 * Paimon has no positional or equality delete files, so every live file is `DataFileContent.DATA`
 * by definition rather than by inspection — a removal is an entry kind, not a file.
 */
fun paimonLiveFilesOf(snapshot: PaimonUnifiedSnapshot?): List<LiveFile> = paimonLiveFilesOf(replayPaimonSnapshot(snapshot))

/** The same, from a replay already run — the builder shares one replay between the file set and the LSM trees. */
fun paimonLiveFilesOf(replay: PaimonReplay): List<LiveFile> =
    replay.liveEntries.map { (key, entry) ->
        val meta = entry.metadata.file
        LiveFile(
            path = meta?.fileName?.takeIf { it.isNotBlank() } ?: key,
            content = DataFileContent.DATA,
            recordCount = meta?.rowCount ?: 0L,
            sizeBytes = meta?.fileSize ?: 0L,
            partial = entry.partial,
            // The decoded form, not the directory's: a DATE partitions as its epoch day on disk.
            partition = entry.partition?.display,
        )
    }

/** The rows of a snapshot's partial-column files — counted in `totalRecordCount`, read as no rows of their own. */
fun List<LiveFile>.partialRows(): Long = filter { it.partial }.sumOf { it.recordCount }

/**
 * The three record counts a Paimon snapshot file records, each beside the same figure read from
 * the manifests it names — the Paimon counterpart of [snapshotTotals] and [SnapshotChange.tallies]
 * in one list, because a Paimon snapshot carries both kinds of figure in three fields.
 *
 * `totalRecordCount` is what the table holds *at* the snapshot and is checked against the
 * replay's live set, [liveRecords] — the walk `current` and the comparison already run, handed in
 * so a panel that has walked it once does not walk it again. `deltaRecordCount` is what the
 * commit did and is checked the way the writer computes it: rows of the delta list's `ADD`
 * entries minus rows of its `DELETE` entries, whether or not a removed file was there to remove
 * — the writer subtracts by the entry, and a replay that finds nothing to remove contributes
 * nothing, so the two readings are kept apart on purpose and the raw one is the writer's.
 * `changelogRecordCount` is the rows of the changelog list's entries. A snapshot's total is the
 * previous total plus its delta by arithmetic, so a total that disagrees with the replay is one
 * that went wrong at an earlier commit and was carried forward — the same shape of fault the
 * Iceberg totals check catches.
 */
fun paimonRecordTallies(snapshot: PaimonUnifiedSnapshot, liveRecords: Long): List<CommitTally> {
    fun rows(manifests: List<PaimonUnifiedManifest>, signed: Boolean) = manifests.flatMap { it.entries }.sumOf { entry ->
        val rows = entry.metadata.file?.rowCount ?: 0L
        if (signed && (entry.metadata.kind ?: PaimonEntryKind.ADD) == PaimonEntryKind.DELETE) -rows else rows
    }
    val recorded = snapshot.metadata
    // A long-lived changelog whose base or delta list the expiry deleted has nothing to count
    // those two against: the replay is over what is left, which is not what was recorded.
    val baseRetired = snapshot.metadata.baseManifestList in snapshot.retiredLists
    val deltaRetired = snapshot.metadata.deltaManifestList in snapshot.retiredLists
    return listOfNotNull(
        if (baseRetired || deltaRetired) null else CommitTally("Total records", recorded.totalRecordCount, liveRecords),
        if (deltaRetired) null else CommitTally("Delta records", recorded.deltaRecordCount, rows(snapshot.deltaManifests, signed = true)),
        CommitTally("Changelog records", recorded.changelogRecordCount, rows(snapshot.changelogManifests, signed = false)),
    )
}
