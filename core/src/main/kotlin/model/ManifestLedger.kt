package model

/**
 * One entry, reduced to the three things that decide what it contributes.
 *
 * [fileKey] is the identity used for deduplication — the normalised data-file path where there is
 * one. It is supplied by the caller rather than derived here, because the traversal that counts a
 * whole table and the inspector that explains one manifest arrive at it from different objects.
 */
data class LedgerEntry(val fileKey: String, val status: Int, val dataFile: DataFile?)

/** Why an entry did or did not add to the figures folded over a manifest. */
enum class EntryFate {
    /** Added its file, records and bytes to the totals. */
    COUNTED,

    /** `status = DELETED`, under a reading that covers live entries only. */
    REMOVAL,

    /** Its data file had already been counted, here or in a manifest reached earlier. */
    DUPLICATE,
}

/** What one entry added, and which rule stopped it if it added nothing. */
data class EntryContribution(
    val fileKey: String,
    val filePath: String,
    val status: Int,
    val content: Int,
    /**
     * What the entry records, before any rule was applied. Kept beside the delta rather than
     * derived from it because a dropped entry's delta is zero by construction, and "the 1,204
     * records this removal took out" is the reason a reader opened the row.
     */
    val recordCount: Long,
    val sizeBytes: Long,
    val fate: EntryFate,
    val delta: ContentStats,
)

/**
 * One row per entry: what it contributed to a manifest's figures, or which rule dropped it.
 *
 * This is the bottom of the same ledger `StatsDerivation` sits on top of. The table's totals are
 * folded from one [ManifestContribution] per manifest, and each of those is folded from this —
 * so the number a reader sees at the table, the number at the manifest and the rows explaining
 * it are one computation seen at three depths, not three implementations that agree today.
 *
 * [seenFileKeys] carries the deduplication across calls. The traversal passes one set through
 * every manifest it visits, which is what makes a file listed by four manifests count once; the
 * inspector passes a fresh set, which scopes the answer to the manifest on screen and is why it
 * says so.
 *
 * With [liveEntriesOnly] a `DELETED` entry contributes nothing but its place in the entry count —
 * the right reading for "what does my table contain now". Without it every referenced file is
 * counted once, which is the right reading for "what is still on disk".
 */
fun manifestLedger(
    entries: List<LedgerEntry>,
    liveEntriesOnly: Boolean,
    seenFileKeys: MutableSet<String> = mutableSetOf(),
): List<EntryContribution> = entries.map { entry ->
    val isRemoval = entry.status == ManifestEntryStatus.DELETED
    val content = entry.dataFile?.content ?: DataFileContent.DATA
    // Every entry counts toward the entry count whatever happens to it next: that figure measures
    // what a scan has to read, not what the table holds.
    val seat = ContentStats(manifestEntryCount = 1, deletedEntryCount = if (isRemoval) 1 else 0)

    val rows = entry.dataFile?.recordCount ?: 0L
    val bytes = entry.dataFile?.fileSizeInBytes ?: 0L

    fun contribution(fate: EntryFate, added: ContentStats = ContentStats()) = EntryContribution(
        fileKey = entry.fileKey,
        filePath = entry.dataFile?.filePath?.takeIf { it.isNotBlank() } ?: entry.fileKey,
        status = entry.status,
        content = content,
        recordCount = rows,
        sizeBytes = bytes,
        fate = fate,
        delta = seat + added,
    )

    when {
        liveEntriesOnly && isRemoval -> contribution(EntryFate.REMOVAL)
        !seenFileKeys.add(entry.fileKey) -> contribution(EntryFate.DUPLICATE)
        else -> {
            contribution(
                EntryFate.COUNTED,
                when (content) {
                    DataFileContent.POSITION_DELETES -> ContentStats(
                        posDeleteFileCount = 1, deleteRecordCount = rows, deleteSizeBytes = bytes,
                    )
                    DataFileContent.EQUALITY_DELETES -> ContentStats(
                        eqDeleteFileCount = 1, deleteRecordCount = rows, deleteSizeBytes = bytes,
                    )
                    else -> ContentStats(dataFileCount = 1, recordCount = rows, dataSizeBytes = bytes)
                },
            )
        }
    }
}

/** The ledger's own total, which is the figure it explains. */
fun List<EntryContribution>.total(): ContentStats =
    fold(ContentStats()) { running, entry -> running + entry.delta }
