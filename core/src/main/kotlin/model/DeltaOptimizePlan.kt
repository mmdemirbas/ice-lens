package model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * What `OPTIMIZE` would rewrite, planned the way `OptimizeTableCommand` and `OptimizeExecutor`
 * do it at delta-spark 3.2.1 — in each of the three modes `OptimizeTableStrategy.getMode` picks:
 * [DeltaOptimizeMode.CLUSTERING] where the protocol carries the `clustering` feature and the
 * `delta.clustering` domain names a column, else [DeltaOptimizeMode.ZORDER] where the call names
 * `ZORDER BY` columns, else [DeltaOptimizeMode.COMPACTION].
 *
 * **Compaction.** A candidate is a live file smaller than `spark.databricks.delta.optimize.minFileSize`
 * (1 GiB), or one whose deletion vector marks more than `optimize.maxDeletedRowsRatio` (5%) of its
 * rows — the cardinality over `numRecords`, which counts the marked rows too — or one with a
 * vector and no `numRecords`, which is always compacted so the output gets statistics.
 * Candidates are grouped by partition values, sorted by size ascending, and packed greedily into
 * bins of at most `optimize.maxFileSize` (1 GiB): a file that would take the bin past it closes
 * the bin and opens the next. **Only a bin of two or more files is rewritten**, so a lone
 * candidate stays as it is, its vector with it. Each rewritten bin becomes one file.
 *
 * **ZORDER BY.** Every live file is taken, whatever its size, and each partition is one bin
 * (`maxBinSize` is `Long.MaxValue`) — **rewritten even when it holds one file**, so a second
 * `ZORDER BY` rewrites everything the first wrote (`dzo`'s v7). The bin is range-partitioned on the
 * interleaved columns into `max(1, bytes / optimize.maxFileSize)` partitions, each written as a
 * file if it holds a row — one file under the default size, and **at most** that many above it,
 * which the metadata cannot narrow (`dzo`'s v10 under 300 bytes asked for three and two, and
 * wrote three and one). Refused, in the command's order: on a table with the `clustering`
 * feature at all; per column, a partition column or one not in the data schema; and after them,
 * the columns no statistics are collected on ([deltaStatsColumnPaths]).
 *
 * **Liquid clustering.** A bare `OPTIMIZE` on a clustered table takes the live files sorted by
 * their `ZCUBE_ID` tag, the unclustered first (a stable sort), and leaves out
 * (`ClusteringStrategy.applyMinZCube`): a file another clustering provider wrote; a file clustered
 * by other columns — its `ZCUBE_ZORDER_BY` against the domain's columns by logical name, so after
 * `ALTER TABLE … CLUSTER BY` the old cubes stay as they are (`dcl`'s v11); a cube whose files'
 * logical sizes — the size scaled by the rows a vector leaves — reach
 * `optimize.clustering.mergeStrategy.minCubeSize.threshold` (100 GiB); and, where no unclustered
 * file is left, a lone remaining cube, which has nothing to merge with. What is left is packed in
 * that order into cubes of `max(targetCubeSize, threshold)` (150 GiB) — every one rewritten, a lone
 * file included — each a new `ZCUBE_ID`, its files carrying `clusteringProvider` `liquid`.
 *
 * **Nothing commits unless a file was written**, and every rewrite is removed and added with
 * `dataChange = false`. **`numDeletionVectorsRemoved` counts the vectors on every file the run
 * considered** — the candidates, or every file under ZORDER BY and clustering, the cubes left out
 * included — not on the files it removed: `dcl`'s v11 recorded 1 and removed no vector
 * ([DeltaOptimizePlan.deletionVectorsCounted]).
 *
 * The oracles are the logs: every `OPTIMIZE` commit of `dvac`, `dzo` and `dcl` against this plan at
 * the version before it, and `dcl`'s two runs that wrote no commit against a plan of nothing. The
 * input order the clustering pack sees is the snapshot's, which the log does not fix; it decides
 * only where a cube is cut, past 150 GiB of files.
 */
enum class DeltaOptimizeMode(val label: String) {
    COMPACTION("compaction"),
    ZORDER("ZORDER BY"),
    CLUSTERING("liquid clustering"),
}

data class DeltaOptimizeOptions(
    val minFileSize: Long = 1L shl 30,
    val maxFileSize: Long = 1L shl 30,
    val maxDeletedRowsRatio: Double = 0.05,
    /** `optimize.clustering.mergeStrategy.minCubeSize.threshold`: a cube this large is not merged again. */
    val minCubeSize: Long = 100L * (1L shl 30),
    /** `optimize.clustering.mergeStrategy.minCubeSize.targetCubeSize`: what a new cube is packed to, 1.5 times the threshold. */
    val targetCubeSize: Long = 150L * (1L shl 30),
)

data class DeltaOptimizeFile(
    val add: DeltaAddFile,
    /** Why it is a candidate; null for one that is not. */
    val candidateBecause: String?,
    /** The marked rows over the recorded ones, where both are known. */
    val deletedRatio: Double?,
    /** Why it is not, where the mode says more than compaction's size and ratio. */
    val leftBecause: String? = null,
) {
    val whyText: String get() = candidateBecause ?: leftBecause ?: "at or over optimize.minFileSize, and under the deleted-rows ratio"
}

data class DeltaOptimizeBin(
    val partitionValues: Map<String, String?>,
    val files: List<DeltaOptimizeFile>,
    /** A compaction bin of one is left; a ZORDER BY bin or a cube is rewritten whatever its size. */
    val rewritten: Boolean = files.size > 1,
    /** How many files the rewrite may write: one for a compaction bin, `max(1, bytes / maxFileSize)` at most otherwise. */
    val outputFiles: Int = 1,
) {
    val bytes: Long get() = files.sumOf { it.add.size ?: 0L }

    val verdictText: String
        get() = when {
            !rewritten -> "left — one file"
            outputFiles == 1 -> "rewritten into one"
            else -> "rewritten into up to $outputFiles"
        }
    val partitionText: String get() = partitionValues.entries.joinToString(", ") { "${it.key}=${it.value ?: "null"}" }.ifEmpty { "unpartitioned" }
}

data class DeltaOptimizePlan(
    val version: Long,
    val options: DeltaOptimizeOptions,
    /** Every live file, candidates first. */
    val files: List<DeltaOptimizeFile>,
    /** Every bin, per partition in the order packed, the ones left alone included. */
    val bins: List<DeltaOptimizeBin>,
    val mode: DeltaOptimizeMode = DeltaOptimizeMode.COMPACTION,
    /** The `ZORDER BY` columns, or the clustering columns by logical name; empty for compaction. */
    val columns: List<String> = emptyList(),
    /** The error the command fails with; nothing is planned under it. */
    val refusal: String? = null,
) {
    val rewrittenBins: List<DeltaOptimizeBin> get() = bins.filter { it.rewritten }
    val removed: List<DeltaOptimizeFile> get() = rewrittenBins.flatMap { it.files }
    val removedBytes: Long get() = removed.sumOf { it.add.size ?: 0L }

    /** One output file per rewritten bin at least, and the bins' [DeltaOptimizeBin.outputFiles] at most. */
    val filesAdded: IntRange get() = rewrittenBins.size..rewrittenBins.sumOf { it.outputFiles }
    val filesAddedText: String get() = filesAdded.let { if (it.first == it.last) "${it.first}" else "${it.first} to ${it.last}" }

    /** Candidates in a compaction bin of one: small or marked, and left alone. */
    val leftAlone: List<DeltaOptimizeFile> get() = bins.filter { !it.rewritten }.flatMap { it.files }

    /** Files clustering leaves out: another provider's, other columns', a full cube's, a lone cube's. */
    val skipped: List<DeltaOptimizeFile> get() = if (mode == DeltaOptimizeMode.CLUSTERING) files.filter { it.candidateBecause == null } else emptyList()

    /** `OPTIMIZE` writes no commit when it rewrites nothing. */
    val commits: Boolean get() = refusal == null && filesAdded.last > 0

    /**
     * What the commit records as `numDeletionVectorsRemoved`: the vectors on the files the run
     * considered — the compaction candidates, or every live file — not on the files it removed.
     */
    val deletionVectorsCounted: Int
        get() = (if (mode == DeltaOptimizeMode.COMPACTION) files.filter { it.candidateBecause != null } else files).count { it.add.deletionVector != null }

    /** How the mode chooses and packs, under the options in force — one paragraph, both shells. */
    val ruleText: String
        get() = when (mode) {
            DeltaOptimizeMode.COMPACTION -> "Without ZORDER BY: a live file under optimize.minFileSize (${formatBytes(options.minFileSize)}) or " +
                "whose vector marks over optimize.maxDeletedRowsRatio (${(options.maxDeletedRowsRatio * 100).toInt()}%) of its rows is a candidate; " +
                "candidates are grouped by partition, sorted smallest first and packed into bins of at most optimize.maxFileSize " +
                "(${formatBytes(options.maxFileSize)}), and only a bin of two or more is rewritten — into one file, with dataChange false."
            DeltaOptimizeMode.ZORDER -> "ZORDER BY ${columns.joinToString(", ")}: every live file, one bin per partition, and every bin rewritten — " +
                "a lone file too — range-partitioned on the interleaved columns into max(1, bytes / optimize.maxFileSize " +
                "(${formatBytes(options.maxFileSize)})) partitions, a file for each that holds a row, with dataChange false."
            DeltaOptimizeMode.CLUSTERING -> "Clustered by ${columns.joinToString(", ")}: the unclustered files and the cubes under " +
                "${formatBytes(options.minCubeSize)} clustered by these columns, packed into new cubes of up to " +
                "${formatBytes(maxOf(options.targetCubeSize, options.minCubeSize))} and every cube rewritten, a lone file too; files " +
                "clustered by other columns or in a larger cube are left, and so is a lone cube with no unclustered file to merge. " +
                "Each output file is tagged with its new cube and clusteringProvider liquid, with dataChange false."
        }

    /** What the call would do, in a sentence. */
    val headline: String
        get() = when {
            refusal != null -> "Refused: $refusal"
            commits -> "Would rewrite ${formatCounted(removed.size, "file")} (${formatBytes(removedBytes)}) into $filesAddedText " +
                (if (filesAdded.last == 1) "file" else "files") +
                (if (mode == DeltaOptimizeMode.CLUSTERING) " in ${formatCounted(rewrittenBins.size, "new cube")}" else "") +
                when {
                    leftAlone.isNotEmpty() -> "; ${formatCounted(leftAlone.size, "candidate")} alone in a bin stays."
                    skipped.isNotEmpty() -> "; ${formatCounted(skipped.size, "file")} left as clustered."
                    else -> "."
                }
            else -> "Writes nothing: $nothingBecause."
        }

    /** Why nothing is written, where nothing is. */
    val nothingBecause: String
        get() = when {
            files.isEmpty() -> "the table has no live file"
            mode == DeltaOptimizeMode.COMPACTION && leftAlone.isNotEmpty() -> "${formatCounted(leftAlone.size, "candidate")}, each alone in its bin"
            mode == DeltaOptimizeMode.COMPACTION -> "no live file under optimize.minFileSize (${formatBytes(options.minFileSize)}) or past the deleted-rows ratio"
            else -> "every file is left: " + skipped.groupBy { it.leftBecause }.entries.joinToString("; ") { (why, fs) -> "${formatCounted(fs.size, "file")} $why" }
        }
}

/** An add's cube: its `ZCUBE_ID` with the columns in `ZCUBE_ZORDER_BY`, both needed, as `ZCubeInfo.getForFile` reads them. */
data class DeltaZCube(val id: String, val columns: List<String>)

val DeltaAddFile.zCube: DeltaZCube?
    get() {
        val id = tags?.get("ZCUBE_ID") ?: return null
        val columns = tags["ZCUBE_ZORDER_BY"]?.let { runCatching { (deltaJson.parseToJsonElement(it) as JsonArray).map { e -> e.jsonPrimitive.content } }.getOrNull() } ?: return null
        return DeltaZCube(id, columns)
    }

/** The compaction plan over [files], the live adds of [version]. */
fun planDeltaOptimize(
    version: Long,
    files: Collection<DeltaAddFile>,
    options: DeltaOptimizeOptions = DeltaOptimizeOptions(),
): DeltaOptimizePlan {
    val judged = files.map { add ->
        val records = add.parsedStats?.numRecords
        val marked = add.deletionVector?.cardinality ?: 0L
        val ratio = if (records != null && records > 0) marked.toDouble() / records else null
        val because = when {
            (add.size ?: 0L) < options.minFileSize -> "smaller than optimize.minFileSize (${formatBytes(options.minFileSize)})"
            add.deletionVector != null && records == null -> "a deletion vector and no numRecords — always compacted, so the output gets statistics"
            ratio != null && ratio > options.maxDeletedRowsRatio -> "its vector marks %.1f%% of its rows, over optimize.maxDeletedRowsRatio (%.0f%%)".format(ratio * 100, options.maxDeletedRowsRatio * 100)
            else -> null
        }
        DeltaOptimizeFile(add, because, ratio)
    }
    val bins = mutableListOf<DeltaOptimizeBin>()
    judged.filter { it.candidateBecause != null }.groupBy { it.add.partitionValues }.forEach { (partition, candidates) ->
        var bin = mutableListOf<DeltaOptimizeFile>()
        var binBytes = 0L
        for (file in candidates.sortedBy { it.add.size ?: 0L }) {
            val size = file.add.size ?: 0L
            if (size + binBytes > options.maxFileSize) {
                if (bin.isNotEmpty()) bins += DeltaOptimizeBin(partition, bin)
                bin = mutableListOf(file)
                binBytes = size
            } else {
                bin += file
                binBytes += size
            }
        }
        if (bin.isNotEmpty()) bins += DeltaOptimizeBin(partition, bin)
    }
    val ordered = judged.sortedWith(compareBy<DeltaOptimizeFile> { it.candidateBecause == null }.thenBy { it.add.path })
    return DeltaOptimizePlan(version, options, ordered, bins)
}

/** The ZORDER BY plan: every file of [files], one bin per partition, each rewritten. */
fun planDeltaZOrder(
    version: Long,
    files: Collection<DeltaAddFile>,
    columns: List<String>,
    options: DeltaOptimizeOptions = DeltaOptimizeOptions(),
): DeltaOptimizePlan {
    val judged = files.map { DeltaOptimizeFile(it, "ZORDER BY takes every file of the partition", null) }
    val bins = judged.groupBy { it.add.partitionValues }.map { (partition, inBin) ->
        val bytes = inBin.sumOf { it.add.size ?: 0L }
        DeltaOptimizeBin(partition, inBin, rewritten = true, outputFiles = maxOf(1L, bytes / options.maxFileSize).toInt())
    }
    return DeltaOptimizePlan(version, options, judged.sortedBy { it.add.path }, bins, DeltaOptimizeMode.ZORDER, columns)
}

/**
 * The clustering plan over [files] in the snapshot's order, clustered by [columns] — logical names,
 * as `ZCUBE_ZORDER_BY` records them.
 */
fun planDeltaClustering(
    version: Long,
    files: Collection<DeltaAddFile>,
    columns: List<String>,
    options: DeltaOptimizeOptions = DeltaOptimizeOptions(),
): DeltaOptimizePlan {
    val left = linkedMapOf<DeltaFileKey, String>()
    val sorted = files.sortedWith(compareBy(nullsFirst()) { it.tags?.get("ZCUBE_ID") })
    val kept = sorted.filter { add ->
        val cube = add.zCube
        when {
            add.clusteringProvider != null && add.clusteringProvider != "liquid" -> left[add.key] = "clustered by another provider (${add.clusteringProvider})"
            cube != null && cube.columns != columns -> left[add.key] = "in a cube clustered by ${cube.columns.joinToString(", ")}, not by ${columns.joinToString(", ")}"
        }
        add.key !in left
    }
    // A cube's size is its files' logical sizes: the bytes scaled by the rows a vector leaves.
    fun logicalSize(add: DeltaAddFile): Long {
        val records = add.parsedStats?.numRecords ?: return add.size ?: 0L
        val logical = records - (add.deletionVector?.cardinality ?: 0L)
        return (logical.toDouble() / records * (add.size ?: 0L)).toLong()
    }
    val cubeBytes = kept.filter { it.zCube != null }.groupBy { it.zCube!!.id }.mapValues { (_, f) -> f.sumOf(::logicalSize) }
    val small = kept.filter { add ->
        val id = add.zCube?.id ?: return@filter true
        val bytes = cubeBytes.getValue(id)
        if (bytes >= options.minCubeSize) left[add.key] = "in cube ${id.take(8)} of ${formatBytes(bytes)}, at or over the ${formatBytes(options.minCubeSize)} threshold"
        add.key !in left
    }
    val smallCubes = small.mapNotNull { it.zCube?.id }.distinct()
    val taken = if (small.none { it.zCube == null } && smallCubes.size == 1) {
        small.forEach { left[it.key] = "in the only cube under the threshold, with no unclustered file to merge it with" }
        emptyList()
    } else small
    val maxBin = maxOf(options.targetCubeSize, options.minCubeSize)
    val bins = mutableListOf<DeltaOptimizeBin>()
    var bin = mutableListOf<DeltaOptimizeFile>()
    var binBytes = 0L
    fun close() {
        if (bin.isNotEmpty()) bins += DeltaOptimizeBin(emptyMap(), bin, rewritten = true, outputFiles = maxOf(1L, binBytes / options.maxFileSize).toInt())
    }
    for (add in taken) {
        val because = add.zCube?.let { "in cube ${it.id.take(8)} of ${formatBytes(cubeBytes.getValue(it.id))}, under the ${formatBytes(options.minCubeSize)} threshold" } ?: "not clustered yet"
        val file = DeltaOptimizeFile(add, because, null)
        val size = add.size ?: 0L
        if (size + binBytes > maxBin) {
            close()
            bin = mutableListOf(file)
            binBytes = size
        } else {
            bin += file
            binBytes += size
        }
    }
    close()
    val takenFiles = bins.flatMap { it.files }
    val leftFiles = files.filter { it.key in left }.map { DeltaOptimizeFile(it, null, null, left.getValue(it.key)) }
    return DeltaOptimizePlan(version, options, takenFiles + leftFiles.sortedBy { it.add.path }, bins, DeltaOptimizeMode.CLUSTERING, columns)
}

/**
 * The lower-cased paths — every prefix of each included — of the columns Delta collects statistics
 * on: `StatisticsCollection.statCollectionLogicalSchema` at 3.2.1. The data schema without the
 * partition columns, narrowed to `delta.dataSkippingStatsColumns` where set — a struct named
 * whole keeps every field under it — else to its first `delta.dataSkippingNumIndexedCols` (32)
 * leaves, a struct's own fields counted and an array or a map one; a negative count keeps all.
 */
fun deltaStatsColumnPaths(metadata: DeltaMetadata): Set<List<String>> {
    val struct = metadata.schema ?: return emptySet()
    val partition = metadata.partitionColumns.map { it.lowercase() }.toSet()
    val data = struct.fields.filter { it.name.lowercase() !in partition }
    val out = linkedSetOf<List<String>>()
    fun all(fields: List<DeltaField>, parent: List<String>) {
        for (f in fields) {
            val path = parent + f.name.lowercase()
            out += path
            (f.type as? DeltaStructType)?.let { all(it.fields, path) }
        }
    }
    val named = metadata.configuration["delta.dataSkippingStatsColumns"]?.takeIf { it.isNotBlank() }
    if (named != null) {
        val wanted = named.split(',').map { c -> c.trim().split('.').map { it.trim().removeSurrounding("`").lowercase() } }
        fun keep(fields: List<DeltaField>, parent: List<String>, paths: List<List<String>>) {
            val byHead = paths.groupBy({ it.first() }, { it.drop(1) })
            for (f in fields) {
                val rest = byHead[f.name.lowercase()] ?: continue
                val path = parent + f.name.lowercase()
                val type = f.type
                when {
                    rest.all { it.isEmpty() } -> { out += path; if (type is DeltaStructType) all(type.fields, path) }
                    type is DeltaStructType -> { out += path; keep(type.fields, path, rest.filter { it.isNotEmpty() }) }
                }
            }
        }
        keep(data, emptyList(), wanted)
        return out
    }
    val indexed = metadata.configuration["delta.dataSkippingNumIndexedCols"]?.toIntOrNull() ?: 32
    if (indexed < 0) {
        all(data, emptyList())
        return out
    }
    fun truncate(fields: List<DeltaField>, parent: List<String>, budget: Int): Int {
        var used = 0
        for (f in fields) {
            if (used >= budget) break
            val path = parent + f.name.lowercase()
            out += path
            val type = f.type
            used += if (type is DeltaStructType) truncate(type.fields, path, budget - used) else 1
        }
        return used
    }
    truncate(data, emptyList(), indexed)
    return out
}

/** A column as a reader types it — `a`, `addr.city`, `` `a.b` `` — as name parts. */
internal fun deltaColumnNameParts(column: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var i = 0
    while (i < column.length) {
        val c = column[i]
        when {
            c == '`' && quoted && i + 1 < column.length && column[i + 1] == '`' -> { current.append('`'); i++ }
            c == '`' -> quoted = !quoted
            c == '.' && !quoted -> { parts += current.toString().trim(); current.clear() }
            else -> current.append(c)
        }
        i++
    }
    parts += current.toString().trim()
    return parts
}

/**
 * Why `OPTIMIZE … ZORDER BY [columns]` fails on a table under [metadata] and [protocol], in the
 * command's order; null where it runs. The messages are delta-spark 3.2.1's error classes.
 */
fun deltaZOrderRefusal(metadata: DeltaMetadata, protocol: DeltaProtocol?, columns: List<String>): String? {
    if (protocol?.writerFeatures.orEmpty().contains("clustering")) {
        return "OPTIMIZE command for Delta table with clustering cannot specify ZORDER BY. Please remove ZORDER BY (${columns.joinToString(", ")})."
    }
    val partition = metadata.partitionColumns.map { it.lowercase() }.toSet()
    val statsPaths = deltaStatsColumnPaths(metadata)
    val data = metadata.schema?.fields.orEmpty().filter { it.name.lowercase() !in partition }
    fun exists(parts: List<String>): Boolean {
        var fields = data
        for ((i, part) in parts.withIndex()) {
            val f = fields.firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return false
            if (i < parts.lastIndex) fields = (f.type as? DeltaStructType)?.fields ?: return false
        }
        return true
    }
    val withoutStats = mutableListOf<String>()
    for (column in columns) {
        val parts = deltaColumnNameParts(column)
        if (parts.map { it.lowercase() } !in statsPaths) withoutStats += column
        if (parts.size == 1 && parts.single().lowercase() in partition) return "$column is a partition column. Z-Ordering can only be performed on data columns"
        if (!exists(parts)) return "Z-Ordering column $column does not exist in data schema."
    }
    if (withoutStats.isNotEmpty()) {
        return "Z-Ordering on [${withoutStats.joinToString(", ")}] will be ineffective, because we currently do not collect stats for these columns. " +
            "You can disable this check by setting 'set spark.databricks.delta.optimize.zorder.checkStatsCollection.enabled = false'"
    }
    return null
}

/**
 * The clustering columns by logical name — the `delta.clustering` domain's physical name parts
 * walked through the schema, each joined as `FieldReference` prints it — or empty where the table
 * is not clustered, or clustered by `CLUSTER BY NONE`.
 */
fun deltaClusteringColumns(state: DeltaState): List<String> {
    val configuration = state.domains["delta.clustering"]?.configuration ?: return emptyList()
    val physical = runCatching {
        ((deltaJson.parseToJsonElement(configuration) as JsonObject)["clusteringColumns"] as JsonArray).map { column ->
            (column as JsonArray).map { (it as JsonPrimitive).contentOrNull.orEmpty() }
        }
    }.getOrNull() ?: return emptyList()
    val schema = state.metadata?.schema ?: return emptyList()
    return physical.mapNotNull { parts ->
        var fields = schema.fields
        val logical = mutableListOf<String>()
        for (part in parts) {
            val f = fields.firstOrNull { it.physicalName.equals(part, ignoreCase = true) } ?: return@mapNotNull null
            logical += f.name
            fields = (f.type as? DeltaStructType)?.fields.orEmpty()
        }
        logical.joinToString(".") { if (it.matches(Regex("[A-Za-z0-9_]+"))) it else "`${it.replace("`", "``")}`" }
    }
}

/**
 * The plan at [version] — the latest unless given — for a bare `OPTIMIZE`, or `ZORDER BY`
 * [zOrderBy] where given, under the default options unless given.
 */
fun DeltaUnifiedTableModel.planOptimize(
    zOrderBy: List<String> = emptyList(),
    options: DeltaOptimizeOptions = DeltaOptimizeOptions(),
    version: Long? = null,
): Result<DeltaOptimizePlan> = runCatching {
    val at = requireNotNull(version ?: latestVersion) { "the log holds no version" }
    val state = stateAt(at).getOrThrow()
    val metadata = requireNotNull(state.metadata) { "version $at has no metadata" }
    val clustering = deltaClusteringColumns(state).takeIf { state.protocol?.writerFeatures.orEmpty().contains("clustering") }.orEmpty()
    when {
        zOrderBy.isNotEmpty() -> deltaZOrderRefusal(metadata, state.protocol, zOrderBy)
            ?.let { DeltaOptimizePlan(at, options, emptyList(), emptyList(), DeltaOptimizeMode.ZORDER, zOrderBy, refusal = it) }
            ?: planDeltaZOrder(at, state.files.values, zOrderBy, options)
        clustering.isNotEmpty() -> planDeltaClustering(at, state.files.values, clustering, options)
        else -> planDeltaOptimize(at, state.files.values, options)
    }
}

/**
 * The plan an `OPTIMIZE` commit ran — at the version before it, under the `zOrderBy` it records —
 * or null for another operation, a commit under a `predicate` this cannot apply, or a version the
 * log cannot rebuild. The options a session set are not recorded, so the plan is under the
 * defaults; a caller comparing it with the commit checks the removed files agree first.
 */
fun DeltaUnifiedTableModel.planOptimizeBefore(commit: DeltaCommit): DeltaOptimizePlan? {
    val info = commit.commitInfo ?: return null
    if (info.operation != "OPTIMIZE") return null
    fun list(key: String): List<String>? = (info.operationParameters?.get(key) as? JsonPrimitive)?.contentOrNull
        ?.let { runCatching { (deltaJson.parseToJsonElement(it) as JsonArray).map { e -> e.jsonPrimitive.content } }.getOrNull() }
    if (list("predicate")?.isEmpty() != true) return null
    val zOrderBy = list("zOrderBy") ?: return null
    return planOptimize(zOrderBy, version = commit.version - 1).getOrNull()
}
