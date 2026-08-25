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
    /** File key to its metadata, in the order the replay last wrote each one. */
    val liveFiles: Map<String, PaimonDataFileMeta?>,
)

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
fun replayPaimonSnapshot(snapshot: PaimonUnifiedSnapshot?): PaimonReplay {
    if (snapshot == null) return PaimonReplay(emptyList(), emptyMap())

    val liveFiles = LinkedHashMap<String, PaimonDataFileMeta?>()
    val countedManifests = mutableMapOf<String, String>()
    val contributions = mutableListOf<ManifestContribution>()
    var liveRecords = 0L
    var liveBytes = 0L

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
            var entries = 0
            var deletedEntries = 0
            var suppressed = 0

            manifest.entries.forEach { entry ->
                entries++
                val fileKey = paimonDataFileKey(entry)
                val wasLive = liveFiles.containsKey(fileKey)
                // containsKey rather than the result of remove/put: the map's value type is itself
                // nullable, so a null return cannot tell "absent" from "present, no metadata" and
                // the running totals would drift on files with no meta block.
                if (wasLive) {
                    val previous = liveFiles[fileKey]
                    liveRecords -= previous?.rowCount ?: 0L
                    liveBytes -= previous?.fileSize ?: 0L
                }
                if ((entry.metadata.kind ?: PaimonEntryKind.ADD) == PaimonEntryKind.DELETE) {
                    deletedEntries++
                    liveFiles.remove(fileKey)
                } else {
                    if (wasLive) suppressed++
                    liveFiles[fileKey] = entry.metadata.file
                    liveRecords += entry.metadata.file?.rowCount ?: 0L
                    liveBytes += entry.metadata.file?.fileSize ?: 0L
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
                ),
                entriesSuppressedAsDuplicate = suppressed,
            )
        }
    }

    consume(snapshot.baseManifests, "base manifest list")
    consume(snapshot.deltaManifests, "delta manifest list")
    return PaimonReplay(contributions.toList(), liveFiles)
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
fun paimonLiveFilesOf(snapshot: PaimonUnifiedSnapshot?): List<LiveFile> =
    replayPaimonSnapshot(snapshot).liveFiles.map { (key, meta) ->
        LiveFile(
            path = meta?.fileName?.takeIf { it.isNotBlank() } ?: key,
            content = DataFileContent.DATA,
            recordCount = meta?.rowCount ?: 0L,
            sizeBytes = meta?.fileSize ?: 0L,
        )
    }
