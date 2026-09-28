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
 * **`WHERE`** narrows every mode to the live files of the partitions it matches, before any
 * rule above runs (`OptimizeExecutor.optimize`: `txn.filterFiles(partitionPredicate)`): the
 * predicate may name partition columns only — the resolver ignoring case — and each is read as
 * the partition value cast to the column's type, so it is an exact SQL predicate over each
 * file's partition, a null partition satisfying no comparison ([DeltaOptimizeWhere]). A table
 * with the `clustering` feature refuses it before anything else.
 *
 * **Nothing commits unless a file was written**, and every rewrite is removed and added with
 * `dataChange = false`. **`numDeletionVectorsRemoved` counts the vectors on every file the run
 * considered** — the candidates, or every file under ZORDER BY and clustering, the cubes left out
 * included — not on the files it removed: `dcl`'s v11 recorded 1 and removed no vector
 * ([DeltaOptimizePlan.deletionVectorsCounted]).
 *
 * The oracles are the logs: every `OPTIMIZE` commit of `dvac`, `dzo`, `dcl` and `dow` against this
 * plan at the version before it, and the runs of `dcl` and `dow` that wrote no commit against a
 * plan of nothing. The input order the clustering pack sees is the snapshot's, which the log does
 * not fix; it decides only where a cube is cut, past 150 GiB of files.
 */
enum class DeltaOptimizeMode(val label: String) {
    COMPACTION("compaction"),
    ZORDER("ZORDER BY"),
    CLUSTERING("liquid clustering"),
}

/**
 * An `OPTIMIZE … WHERE` predicate: [text] as it was written — the clustering refusal quotes it —
 * and [filter], what it reads as.
 *
 * Whether a file is in a partition it matches is decided exactly, not bounded: the partition's
 * values are known, so each condition is true or false of them, and a comparison with a null
 * partition value is false — which, once `NOT` is pushed into the operators, is the same answer
 * SQL's three-valued logic gives ([ScanFilter.holds]). `dow`'s `WHERE p <> 'x'` leaves its null
 * partition out for that reason.
 */
data class DeltaOptimizeWhere(val text: String, val filter: ScanFilter)

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
    /** The `WHERE` the call carries; [files] are then the live files of the partitions it matches. */
    val where: DeltaOptimizeWhere? = null,
    /** The live files [where] leaves out. */
    val outsideWhere: Int = 0,
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
        get() = whereText.orEmpty() + when (mode) {
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

    /** What the `WHERE` keeps, as the sentence the rule starts with; null without one. */
    val whereText: String?
        get() = where?.let {
            "WHERE ${it.text} keeps ${formatCounted(files.size, "live file")} of ${files.size + outsideWhere}, those in the partitions it " +
                "matches — a comparison with a null partition value is false — and the rule runs over those alone. "
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
            files.isEmpty() && where != null -> "no live file is in a partition WHERE ${where.text} matches"
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

/** The call's clauses as Spark SQL spells them: `WHERE p = 'x' ZORDER BY a, b`, either one alone. */
fun deltaOptimizeCall(where: DeltaOptimizeWhere?, zOrderBy: List<String>): String =
    listOfNotNull(where?.let { "WHERE ${it.text}" }, zOrderBy.takeIf { it.isNotEmpty() }?.let { "ZORDER BY ${it.joinToString(", ")}" }).joinToString(" ")

/**
 * Why `OPTIMIZE … WHERE` fails on a table under [metadata] — a column the predicate names that is
 * not a partition column, the first in the order written — or null. `verifyPartitionPredicates`
 * at 3.2.1, the resolver ignoring case.
 */
fun deltaPartitionPredicateRefusal(metadata: DeltaMetadata, where: DeltaOptimizeWhere): String? {
    val partition = metadata.partitionColumns
    val outside = where.filter.predicates().map { it.column }.firstOrNull { c -> partition.none { it.equals(c, ignoreCase = true) } }
        ?: return null
    return "Predicate references non-partition column '$outside'. Only the partition columns may be referenced: [${partition.joinToString(", ")}]"
}

/**
 * The live files of [files] in a partition [where] matches: each partition value read as its
 * column's type (the text for a type the read schema has no counterpart for), a null one
 * satisfying no comparison, and a `LIKE` matched against the text as SQL matches it.
 */
fun deltaFilesMatching(files: Collection<DeltaAddFile>, metadata: DeltaMetadata, where: DeltaOptimizeWhere): List<DeltaAddFile> {
    val struct = metadata.schema ?: return emptyList()
    val schema = deltaReadSchema(struct).schema
    val fields = metadata.partitionColumns.mapNotNull { name -> struct.fields.firstOrNull { it.name.equals(name, ignoreCase = true) } }
    fun leaf(add: DeltaAddFile, p: ScanPredicate): Boolean {
        val field = fields.firstOrNull { it.name.equals(p.column, ignoreCase = true) } ?: return false
        val text = if (field.physicalName in add.partitionValues) add.partitionValues[field.physicalName] else add.partitionValues[field.name]
        val type = schema.idOfPath(field.name)?.let(schema::typeOf)
        fun typed(t: String): Any? = type?.let { parseLiteral(t, it) } ?: t.takeIf { type == null }
        return when (p.op) {
            PredicateOp.IS_NULL -> text == null
            PredicateOp.IS_NOT_NULL -> text != null
            PredicateOp.LIKE -> text != null && sqlLikeMatches(text, p.literal)
            PredicateOp.NOT_LIKE -> text != null && !sqlLikeMatches(text, p.literal)
            else -> {
                // A literal the column's type cannot read is a cast to null in Spark, and so is a
                // partition value that does not parse: either way the comparison is null, and false.
                val order = text?.let(::typed)?.let { v -> typed(p.literal)?.let { compareValues(v, it) } } ?: return false
                when (p.op) {
                    PredicateOp.EQ -> order == 0
                    PredicateOp.NOT_EQ -> order != 0
                    PredicateOp.LT -> order < 0
                    PredicateOp.LTE -> order <= 0
                    PredicateOp.GT -> order > 0
                    else -> order >= 0
                }
            }
        }
    }
    return files.filter { add -> where.filter.holds { leaf(add, it) } }
}

/**
 * Whether [value] matches the SQL `LIKE` [pattern]: `%` any run, `_` one character, a backslash
 * taking the next character as it is — Spark's `StringUtils.escapeLikeRegex` with its default
 * escape.
 */
internal fun sqlLikeMatches(value: String, pattern: String): Boolean {
    val regex = StringBuilder()
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        when {
            c == '\\' && i + 1 < pattern.length -> { regex.append(Regex.escape(pattern[i + 1].toString())); i++ }
            c == '%' -> regex.append("(?s).*")
            c == '_' -> regex.append("(?s).")
            else -> regex.append(Regex.escape(c.toString()))
        }
        i++
    }
    return Regex(regex.toString()).matches(value)
}

/**
 * The plan at [version] — the latest unless given — for a bare `OPTIMIZE`, or `ZORDER BY`
 * [zOrderBy] where given, over the partitions [where] matches where given, under the default
 * options unless given. Refused in the command's order: a `WHERE` on a clustered table, a
 * `ZORDER BY` on one, a `WHERE` naming a column that is not a partition column, then the
 * `ZORDER BY` columns.
 */
fun DeltaUnifiedTableModel.planOptimize(
    zOrderBy: List<String> = emptyList(),
    where: DeltaOptimizeWhere? = null,
    options: DeltaOptimizeOptions = DeltaOptimizeOptions(),
    version: Long? = null,
): Result<DeltaOptimizePlan> = runCatching {
    val at = requireNotNull(version ?: latestVersion) { "the log holds no version" }
    val state = stateAt(at).getOrThrow()
    val metadata = requireNotNull(state.metadata) { "version $at has no metadata" }
    val clusteringFeature = state.protocol?.writerFeatures.orEmpty().contains("clustering")
    val clustering = deltaClusteringColumns(state).takeIf { clusteringFeature }.orEmpty()
    val mode = when {
        zOrderBy.isNotEmpty() -> DeltaOptimizeMode.ZORDER
        clustering.isNotEmpty() -> DeltaOptimizeMode.CLUSTERING
        else -> DeltaOptimizeMode.COMPACTION
    }
    val refusal = when {
        where != null && clusteringFeature ->
            "OPTIMIZE command for Delta table with clustering doesn't support partition predicates. Please remove the predicates: ${where.text}."
        zOrderBy.isNotEmpty() && clusteringFeature -> deltaZOrderRefusal(metadata, state.protocol, zOrderBy)
        else -> where?.let { deltaPartitionPredicateRefusal(metadata, it) }
            ?: zOrderBy.takeIf { it.isNotEmpty() }?.let { deltaZOrderRefusal(metadata, state.protocol, it) }
    }
    if (refusal != null) return@runCatching DeltaOptimizePlan(at, options, emptyList(), emptyList(), mode, zOrderBy.ifEmpty { clustering }, refusal, where)
    val files = if (where == null) state.files.values.toList() else deltaFilesMatching(state.files.values, metadata, where)
    val plan = when (mode) {
        DeltaOptimizeMode.ZORDER -> planDeltaZOrder(at, files, zOrderBy, options)
        DeltaOptimizeMode.CLUSTERING -> planDeltaClustering(at, files, clustering, options)
        DeltaOptimizeMode.COMPACTION -> planDeltaOptimize(at, files, options)
    }
    plan.copy(where = where, outsideWhere = state.files.size - files.size)
}

/**
 * The plan an `OPTIMIZE` commit ran — at the version before it, under the `zOrderBy` and the
 * `predicate` it records — or null for another operation, a predicate [parseCatalystPredicate]
 * cannot read back, or a version the log cannot rebuild. The options a session set are not
 * recorded, so the plan is under the defaults; a caller comparing it with the commit checks the
 * removed files agree first.
 */
fun DeltaUnifiedTableModel.planOptimizeBefore(commit: DeltaCommit): DeltaOptimizePlan? {
    val info = commit.commitInfo ?: return null
    if (info.operation != "OPTIMIZE") return null
    fun list(key: String): List<String>? = (info.operationParameters?.get(key) as? JsonPrimitive)?.contentOrNull
        ?.let { runCatching { (deltaJson.parseToJsonElement(it) as JsonArray).map { e -> e.jsonPrimitive.content } }.getOrNull() }
    val predicates = list("predicate") ?: return null
    val where = if (predicates.isEmpty()) null else {
        val filters = predicates.map { parseCatalystPredicate(it) ?: return null }
        DeltaOptimizeWhere(predicates.joinToString(" AND "), filters.singleOrNull() ?: ScanFilter.And(filters))
    }
    val zOrderBy = list("zOrderBy") ?: return null
    return planOptimize(zOrderBy, where, version = commit.version - 1).getOrNull()
}

/**
 * An `OPTIMIZE` commit's recorded `predicate` read back into a filter, or null where it cannot be.
 *
 * delta-spark 3.2.1 records each predicate as Catalyst's `toString` of the parsed, unresolved
 * expression, not as SQL — what `dow`'s log holds: `p = 'x'` is `('p = x)`, `p <> 'x'` is
 * `NOT ('p = x)`, `p IS NULL` is `isnull('p)`, and a `LIKE` or an `IN` prints no parentheses of
 * its own (`(NOT 'p LIKE %y OR isnull('p))`). A column is `'name` and a literal loses its quotes,
 * so a value holding a space, a parenthesis or a comma cannot be told from the text around it:
 * a text that does not read back as exactly one expression of these shapes is null, never a guess.
 */
internal fun parseCatalystPredicate(text: String): ScanFilter? {
    val tokens = Regex("""[(),]|[^\s(),]+""").findAll(text).map { it.value }.toList()
    var pos = 0
    fun next(): String? = tokens.getOrNull(pos)?.also { pos++ }

    // An operand is a column, a literal (null for Catalyst's `null`), or a condition.
    abstract class Operand
    class Column(val name: String) : Operand()
    class Literal(val text: String?) : Operand()
    class Condition(val filter: ScanFilter) : Operand()

    val comparisons = mapOf("=" to PredicateOp.EQ, "<" to PredicateOp.LT, "<=" to PredicateOp.LTE, ">" to PredicateOp.GT, ">=" to PredicateOp.GTE)
    val flipped = mapOf(PredicateOp.EQ to PredicateOp.EQ, PredicateOp.LT to PredicateOp.GT, PredicateOp.LTE to PredicateOp.GTE, PredicateOp.GT to PredicateOp.LT, PredicateOp.GTE to PredicateOp.LTE)
    fun condition(o: Operand?): ScanFilter? = (o as? Condition)?.filter
    fun term(column: String, op: PredicateOp, literal: String = "") = Condition(ScanFilter.Term(ScanPredicate(column, op, literal)))
    fun like(column: String, pattern: String) = term(column, if (pattern.none { it == '%' || it == '_' }) PredicateOp.EQ else PredicateOp.LIKE, pattern)

    fun operand(): Operand? {
        val token = next() ?: return null
        return when {
            token == "(" -> {
                val left = operand() ?: return null
                val op = next() ?: return null
                val right = operand() ?: return null
                if (next() != ")") return null
                when {
                    op == "AND" -> Condition(ScanFilter.And(listOf(condition(left) ?: return null, condition(right) ?: return null)))
                    op == "OR" -> Condition(ScanFilter.Or(listOf(condition(left) ?: return null, condition(right) ?: return null)))
                    op == "<=>" && left is Column && right is Literal -> right.text?.let { term(left.name, PredicateOp.EQ, it) } ?: term(left.name, PredicateOp.IS_NULL)
                    op in comparisons && left is Column && right is Literal -> term(left.name, comparisons.getValue(op), right.text ?: return null)
                    op in comparisons && left is Literal && right is Column -> term(right.name, flipped.getValue(comparisons.getValue(op)), left.text ?: return null)
                    else -> null
                }
            }
            token == "NOT" -> Condition(ScanFilter.Not(condition(operand()) ?: return null))
            (token == "isnull" || token == "isnotnull") && next() == "(" -> {
                val column = operand() as? Column ?: return null
                if (next() != ")") return null
                term(column.name, if (token == "isnull") PredicateOp.IS_NULL else PredicateOp.IS_NOT_NULL)
            }
            token.startsWith("'") && token.length > 1 -> {
                val name = token.drop(1)
                when (tokens.getOrNull(pos)) {
                    "LIKE" -> { pos++; val pattern = next()?.takeIf { it !in setOf("(", ")", ",") } ?: return null; like(name, pattern) }
                    "IN" -> {
                        pos++
                        if (next() != "(") return null
                        val values = mutableListOf<String>()
                        while (true) {
                            values += next()?.takeIf { it !in setOf("(", ")", ",", "null") } ?: return null
                            when (next()) { ")" -> break; "," -> continue; else -> return null }
                        }
                        Condition(values.map { ScanFilter.Term(ScanPredicate(name, PredicateOp.EQ, it)) }.let { it.singleOrNull() ?: ScanFilter.Or(it) })
                    }
                    else -> Column(name)
                }
            }
            token in setOf(")", ",") -> null
            else -> Literal(token.takeUnless { it == "null" })
        }
    }
    val filter = condition(operand()) ?: return null
    return filter.takeIf { pos == tokens.size }
}
