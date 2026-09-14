package service

import model.DataFileContent
import model.LiveFile
import model.LookupDeleteFile
import model.PositionDeleteRewritePlan
import model.RowLookupInput
import model.UNDECODED_PARTITION
import model.normalizeFilePath
import org.slf4j.LoggerFactory

/**
 * What [model.planPositionDeleteRewrite]'s rewritten files would come out as, read: each delete
 * file's positions grouped by the data file they name ([SampleRowReader.queryPositionalDeleteTargets]),
 * and each target kept when a live data file at that path sits in the group's partition —
 * `SparkBinPackPositionDeletesRewriter.doRewrite`'s left-semi join on `file_path` against the
 * partition's `data_files` — or dropped. The dropped ones are the dangling records, which
 * `record_count` counts and a scan plans against without opening the file; `maint`'s rewrite
 * removed three positions and wrote one, and this is held to that.
 *
 * Behind a click, one read per rewritten delete file, [MAX_FILES] of them and the rest said.
 */
object PositionDeleteRewriteDrops {
    private val logger = LoggerFactory.getLogger(PositionDeleteRewriteDrops::class.java)

    const val MAX_FILES = 64

    data class Target(
        val dataFilePath: String,
        val positions: Long,
        val kept: Boolean,
        val reason: String,
    )

    data class FileDrops(
        val delete: LiveFile,
        val targets: List<Target>,
        val error: String? = null,
    ) {
        val kept: Long get() = targets.filter { it.kept }.sumOf { it.positions }
        val dropped: Long get() = targets.filter { !it.kept }.sumOf { it.positions }
    }

    data class Result(
        val files: List<FileDrops>,
        /** Rewritten delete files [MAX_FILES] left unread. */
        val filesLeft: Int,
    ) {
        val kept: Long get() = files.sumOf { it.kept }
        val dropped: Long get() = files.sumOf { it.dropped }
        val failed: Int get() = files.count { it.error != null }
        val complete: Boolean get() = failed == 0 && filesLeft == 0
    }

    /** The plan's rewritten files read; [live] is the snapshot's live set the plan was made from, [input] where its delete files are opened from. */
    fun read(plan: PositionDeleteRewritePlan, live: List<LiveFile>, input: RowLookupInput): Result {
        val dataPartitions = live.filter { it.content == DataFileContent.DATA }.associate { normalizeFilePath(it.path) to (it.partition ?: UNDECODED_PARTITION) }
        val deletesByPath = input.deleteFiles.associateBy { normalizeFilePath(it.recordedPath) }
        var opened = 0
        var left = 0
        val files = plan.rewrittenGroups.flatMap { group ->
            group.files.map { delete ->
                val lookup: LookupDeleteFile? = deletesByPath[normalizeFilePath(delete.path)]
                when {
                    lookup == null -> FileDrops(delete, emptyList(), "not among the snapshot's delete files")
                    opened >= MAX_FILES -> { left++; FileDrops(delete, emptyList(), "left unread by the cap") }
                    else -> {
                        opened++
                        runCatching {
                            val targets = SampleRowReader.queryPositionalDeleteTargets(lookup.localPath).map { tally ->
                                val partition = dataPartitions[normalizeFilePath(tally.dataFilePath)]
                                when {
                                    partition == null -> Target(tally.dataFilePath, tally.positions, kept = false, reason = "no live data file at this path — dropped as dangling")
                                    partition != group.partition -> Target(tally.dataFilePath, tally.positions, kept = false, reason = "the live data file at this path is in ${partition.ifEmpty { "the unpartitioned partition" }}, not this group's — dropped")
                                    else -> Target(tally.dataFilePath, tally.positions, kept = true, reason = "a live data file of this partition")
                                }
                            }
                            FileDrops(delete, targets)
                        }.onFailure { logger.warn("Could not read {}: {}", lookup.localPath, it.message) }
                            .getOrElse { FileDrops(delete, emptyList(), it.message ?: it.toString()) }
                    }
                }
            }
        }
        return Result(files, left)
    }
}
