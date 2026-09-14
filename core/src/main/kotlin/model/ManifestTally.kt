package model

/**
 * One figure the manifest list records about a manifest, beside the same figure counted from the
 * manifest's own entries.
 *
 * The six counts in `manifest_file` are what a scan reads to plan without opening the manifest —
 * how many files it adds, how many rows those hold, how much of it is already deleted. They are a
 * *claim* made by whoever wrote the manifest list, in a different file from the entries they
 * describe, and nothing on the read path checks them. A reader who is told "added files: 3" has
 * no way to know whether the manifest holds three added entries or three hundred.
 *
 * [counted] is folded from the entries, so the two sit side by side and the reader can see the
 * claim being kept. When they disagree the manifest list is wrong, or this tool read the manifest
 * wrong — either is worth knowing, and neither is visible from one number alone.
 */
data class ManifestTally(
    val label: String,
    /** From `manifest_file`. Null when the writer omitted it — every count is optional in v1. */
    val recorded: Long?,
    /** Folded from the manifest's own entries. */
    val counted: Long,
) {
    /** Null when there is nothing recorded to agree with. */
    val agrees: Boolean? = recorded?.let { it == counted }
}

/**
 * The six counts `manifest_file` carries, each against the entries it summarises, then the
 * lowest data sequence number among its live entries, then the file's length.
 *
 * Rows come from `data_file.record_count`, which for a delete manifest is the number of delete
 * records rather than table rows — that is the spec's own definition of `added_rows_count`, so
 * the comparison holds for both kinds of manifest.
 */
fun manifestTallies(recorded: ManifestListEntry, entries: List<ManifestEntry>, sizeOnDisk: Long? = null): List<ManifestTally> {
    fun files(status: Int): Long = entries.count { it.status == status }.toLong()
    fun rows(status: Int): Long =
        entries.filter { it.status == status }.sumOf { it.dataFile?.recordCount ?: 0L }

    return listOf(
        ManifestTally("Added files", recorded.addedFilesCount?.toLong(), files(ManifestEntryStatus.ADDED)),
        ManifestTally("Existing files", recorded.existingFilesCount?.toLong(), files(ManifestEntryStatus.EXISTING)),
        ManifestTally("Deleted files", recorded.deletedFilesCount?.toLong(), files(ManifestEntryStatus.DELETED)),
        ManifestTally("Added rows", recorded.addedRowsCount, rows(ManifestEntryStatus.ADDED)),
        ManifestTally("Existing rows", recorded.existingRowsCount, rows(ManifestEntryStatus.EXISTING)),
        ManifestTally("Deleted rows", recorded.deletedRowsCount, rows(ManifestEntryStatus.DELETED)),
        // `min_sequence_number` is folded by `ManifestWriter.addEntry` from the live entries'
        // data sequence numbers — an added entry recording none takes the commit's, which is
        // what `V2Metadata` assigns the figure itself where no live entry recorded one (1.8.1)
        // — and it is what the next commit prunes delete files by: `MergingSnapshotProducer.apply`
        // takes the lowest over the data manifests it keeps and `dropDeleteFilesOlderThan`
        // removes every delete file whose number is below it, as one that "cannot match any
        // existing rows". A figure above the oldest live file's number lets a delete that still
        // reaches that file be dropped, and its rows come back. Null in a v1 list: not compared.
        ManifestTally(
            "Min sequence number", recorded.minSequenceNumber,
            entries.filter { it.status != ManifestEntryStatus.DELETED }
                .minOfOrNull { effectiveSequenceNumber(it, recorded.sequenceNumber) } ?: recorded.effectiveSequenceNumber,
        ),
    ) + listOfNotNull(
        // `manifest_length` is the length `FileIO.newInputFile(ManifestFile)` opens the manifest
        // at (1.8.1), never stat-ed, so it is a seventh figure a reader takes on trust — against
        // the file rather than the entries, and only where the file could be measured.
        sizeOnDisk?.let { ManifestTally("Manifest length", recorded.manifestLength, it) },
    )
}
