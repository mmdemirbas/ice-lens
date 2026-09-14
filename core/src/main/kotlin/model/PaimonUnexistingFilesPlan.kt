package model

/**
 * What `sys.remove_unexisting_files(table, dry_run)` would do with what [findMissingFiles]
 * found — `ListUnexistingFiles` and the Spark `RemoveUnexistingFilesProcedure` at release-1.3.1.
 *
 * The procedure stats the data files of every split the latest snapshot's **batch scan** plans,
 * partition by partition, and commits the absent ones as one `CommitMessage` per bucket with
 * them as `deletedFiles`: an `APPEND` whose delta manifest holds a `DELETE` entry per file and
 * whose `deltaRecordCount` is minus their rows. So it reaches exactly the missing files live in
 * the latest snapshot that a batch read opens — on a primary-key table under deletion vectors
 * or `first-row` the scan skips level 0 (`batchScanSkipLevel0`), and a missing level-0 file is
 * neither read nor listed — and nothing else: a manifest, a manifest list, an index or a
 * changelog file is not a data file, and a data file only an older snapshot, a tag or a branch
 * names is not in the plan, so a time travel to that snapshot fails after the fix as before.
 * `dry_run` lists the same files and commits nothing; nothing listed commits nothing.
 * `docs/fixtures/paimon-pru.sql` records the runs, and `prua` is the commit.
 */
enum class UnexistingFileVerdict(val label: String) {
    /** Live in the latest snapshot and read by its batch scan: a `DELETE` entry in the commit. */
    REMOVED("REMOVED"),
    /** Live in the latest snapshot at level 0 of a table whose batch scan skips it: not listed, and no read fails on it. */
    UNREAD("not scanned"),
    /** Not a data file of the latest snapshot's scan: the procedure never stats it. */
    NOT_REACHED("not reached"),
}

data class UnexistingFileRow(
    val file: MissingFile,
    val verdict: UnexistingFileVerdict,
    val reason: String,
    /** The live entry's coordinates, for a file the latest snapshot lists; null for one it does not. */
    val partition: String?,
    val bucket: Int?,
    val level: Int?,
    val recordCount: Long?,
)

data class PaimonUnexistingFilesPlan(
    /** The latest snapshot on main, whose scan the procedure plans. */
    val snapshotId: Long,
    /** One row per missing file, removed first, then unread, then not reached. */
    val rows: List<UnexistingFileRow>,
) {
    val removed: List<UnexistingFileRow> get() = rows.filter { it.verdict == UnexistingFileVerdict.REMOVED }
    val unread: List<UnexistingFileRow> get() = rows.filter { it.verdict == UnexistingFileVerdict.UNREAD }
    val notReached: List<UnexistingFileRow> get() = rows.filter { it.verdict == UnexistingFileVerdict.NOT_REACHED }
    /** True where the procedure commits: a snapshot is written only for a file it lists. */
    val commits: Boolean get() = removed.isNotEmpty()
    /** What the commit's `deltaRecordCount` would be — minus the removed files' rows. */
    val deltaRecordCount: Long get() = -removed.sumOf { it.recordCount ?: 0L }
    /** The partitions the removals fall in — the procedure works partition by partition. */
    val partitions: Int get() = removed.map { it.partition }.distinct().size
    val buckets: Int get() = removed.map { it.partition to it.bucket }.distinct().size
}

/**
 * The plan over [report], the missing files, against [read], the latest snapshot's live files
 * with the level rule its batch scan applies — [PaimonReadInput.readFiles] and [PaimonReadInput.skippedFiles].
 */
fun planUnexistingFiles(report: MissingFilesReport, read: PaimonReadInput): PaimonUnexistingFilesPlan {
    val readByPath = read.readFiles.associateBy { it.localPath }
    val skippedByPath = read.skippedFiles.associateBy { it.localPath }
    val rows = report.missing.map { missing ->
        val key = missing.path.toString()
        val live = readByPath[key]
        val skipped = skippedByPath[key]
        when {
            live != null -> UnexistingFileRow(
                missing, UnexistingFileVerdict.REMOVED,
                "live in snapshot ${read.snapshotId} at level ${live.level ?: 0}: a DELETE entry in the commit, ${live.recordCount?.let { "$it row${if (it == 1L) "" else "s"}" } ?: "its rows"} off the count",
                live.partition, live.bucket, live.level, live.recordCount,
            )
            skipped != null -> UnexistingFileRow(
                missing, UnexistingFileVerdict.UNREAD,
                "live in snapshot ${read.snapshotId} at level 0, which a batch scan of this ${read.mergeEngine} table never opens — not listed, and no batch read fails on it",
                skipped.partition, skipped.bucket, skipped.level, skipped.recordCount,
            )
            missing.kind == MissingFileKind.DATA_FILE -> UnexistingFileRow(
                missing, UnexistingFileVerdict.NOT_REACHED,
                "not live in snapshot ${read.snapshotId}: only ${missing.neededBy.joinToString(", ")} names it, and the procedure plans the latest snapshot alone",
                null, null, null, null,
            )
            else -> UnexistingFileRow(
                missing, UnexistingFileVerdict.NOT_REACHED,
                "a ${missing.kind.label}: the procedure stats the data files of a scan and nothing else",
                null, null, null, null,
            )
        }
    }
    return PaimonUnexistingFilesPlan(read.snapshotId, rows.sortedBy { it.verdict.ordinal })
}
