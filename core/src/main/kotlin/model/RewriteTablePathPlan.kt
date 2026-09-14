package model

/**
 * What `rewrite_table_path(table, source_prefix, target_prefix, [start_version], [end_version],
 * [staging_location])` writes and lists — `RewriteTablePathSparkAction` and
 * `RewriteTablePathUtil` at 1.8.1 — decided from the model alone.
 *
 * The procedure copies nothing into place. It rewrites the metadata into a **staging**
 * directory with every path under the source prefix moved under the target prefix, and writes a
 * `file-list` CSV of `(from, to)` pairs for a copy tool to run: the end version and every
 * `metadata-log` entry back to the start version (exclusive; each must exist), each rewritten
 * as a JSON with its location, its snapshots' manifest lists, its log, its `write.*.path`
 * properties and its statistics paths replaced; the manifest list of every snapshot the end
 * version holds that the start version does not, rewritten with its manifests' paths
 * replaced; every manifest the end version's snapshots list (the `all_manifests` set, and with
 * a start version only those whose `added_snapshot_id` is a snapshot the start lacks),
 * rewritten entry by entry; and, for the **live** entries of those manifests, a data file as a
 * `(source, target)` pair to copy as it is, a positional delete rewritten into staging with its
 * `file_path` column replaced and listed from there, an equality delete copied as it is. A
 * `DELETED` entry keeps its rewritten path in the manifest and is not listed. So the list
 * carries every data file any retained snapshot reads, not the current snapshot's alone.
 *
 * Refused, in the order the action checks: a source prefix equal to the target; a start or end
 * version not in the end version's log (`Cannot find provided version file %s in metadata
 * log.`); a version in the log that is gone from disk (`Version file %s doesn't exist`);
 * partition statistics on the end version (`Partition statistics files are not supported
 * yet.`); any path a rewritten file carries that is not under the source prefix — a manifest
 * list, a log entry, a `write.data.path` outside the table, a manifest, a data file — with
 * `Path %s does not start with %s/`; and a deletion vector, whose entry the delete-manifest
 * rewrite cannot rebuild (`Content offset is required for DV`), so a v3 table with a vector
 * cannot be moved by it. Two things the runs settled beyond the source, in
 * `docs/fixtures/rewrite-table-path.sql`: a statistics file is listed as if staged and never
 * written to staging, so the copy fails on it; and every retained version is rewritten, so a
 * copied table's older versions still name the expired snapshots' lists that were never copied.
 */

enum class RewriteTablePathCopyKind(val label: String) {
    VERSION_FILE("metadata version"),
    STATISTICS("statistics file"),
    MANIFEST_LIST("manifest list"),
    MANIFEST("manifest"),
    DATA_FILE("data file"),
    POSITION_DELETE("positional delete"),
    EQUALITY_DELETE("equality delete"),
}

/** One line of the `file-list` the procedure writes: copy [from] to [to]. */
data class RewriteTablePathCopy(
    val kind: RewriteTablePathCopyKind,
    val from: String,
    val to: String,
    /** True where [from] is a rewritten copy under the staging directory rather than the file as recorded. */
    val staged: Boolean,
)

data class RewriteTablePathOptions(
    val sourcePrefix: String,
    val targetPrefix: String,
    val startVersion: String? = null,
    val endVersion: String? = null,
    /** The staging directory; null for the default, `<metadata dir>/copy-table-staging-<uuid>/`. */
    val stagingLocation: String? = null,
)

data class RewriteTablePathPlan(
    val options: RewriteTablePathOptions,
    /** The message the action fails with, or null where it runs. */
    val refusal: String?,
    /** The file name the procedure returns as `latest_version`. */
    val endVersion: String?,
    /** The versions rewritten, newest first — the end version and its log back to the start. */
    val versions: List<String>,
    /** The snapshots whose manifest lists are rewritten. */
    val snapshotIds: List<Long>,
    /** The manifests rewritten, by recorded path. */
    val manifests: List<String>,
    /** The `file-list`, in the order the plan found the files. */
    val copies: List<RewriteTablePathCopy>,
    /** Copies whose `from` is under staging but which the action never writes there — the statistics files. */
    val notStaged: List<RewriteTablePathCopy>,
    /** Where the rewritten files land: the option, or the default under the table's metadata directory. */
    val stagingDir: String,
) {
    val runs: Boolean get() = refusal == null
    fun copiesOf(kind: RewriteTablePathCopyKind): List<RewriteTablePathCopy> = copies.filter { it.kind == kind }
    /** The files a copy tool would write under the target — the list's length. */
    val fileCount: Int get() = copies.size
}

/** What the plan reads off the table: every version on disk with its snapshots' lists and entries — filled by the builder, planned on the panel. */
data class RewriteTablePathInput(val versions: List<RewriteTablePathVersion>)

data class RewriteTablePathVersion(val fileName: String, val metadata: TableMetadata, val snapshots: List<RewriteTablePathSnapshot>)

data class RewriteTablePathSnapshot(val snapshotId: Long?, val manifestList: String?, val manifests: List<RewriteTablePathManifest>)

data class RewriteTablePathManifest(val path: String?, val addedSnapshotId: Long?, val entries: List<RewriteTablePathEntry>)

data class RewriteTablePathEntry(val live: Boolean, val content: Int, val path: String?, val format: String?, val contentOffset: Long?)

fun UnifiedTableModel.rewriteTablePathInput(): RewriteTablePathInput = RewriteTablePathInput(
    metadatas.map { v ->
        RewriteTablePathVersion(
            v.path.fileName.toString(), v.metadata,
            v.snapshots.map { s ->
                RewriteTablePathSnapshot(
                    s.metadata.snapshotId, s.metadata.manifestList,
                    s.manifests.map { m ->
                        RewriteTablePathManifest(
                            m.metadata.manifestPath, m.metadata.addedSnapshotId,
                            m.dataFiles.map { e ->
                                val df = e.metadata.dataFile
                                RewriteTablePathEntry(e.metadata.status != ManifestEntryStatus.DELETED, df?.content ?: DataFileContent.DATA, df?.filePath, df?.fileFormat, df?.contentOffset)
                            },
                        )
                    },
                )
            },
        )
    },
)

fun UnifiedTableModel.planRewriteTablePath(options: RewriteTablePathOptions): RewriteTablePathPlan = rewriteTablePathInput().planRewriteTablePath(options)

private const val SEP = "/"
private fun withSep(path: String) = if (path.endsWith(SEP)) path else path + SEP
private fun fileNameOf(path: String) = path.substringAfterLast(SEP)

/** `RewriteTablePathUtil.newPath`: the path under the target prefix, or null where it is not under the source. */
private fun newPathOrNull(path: String, source: String, target: String): String? {
    val toRemove = withSep(source)
    if (!path.startsWith(toRemove)) return null
    return withSep(target) + path.substring(toRemove.length)
}

/** The action's `relativize` message. */
private fun notUnder(path: String, source: String) = "Path $path does not start with ${withSep(source)}"

fun RewriteTablePathInput.planRewriteTablePath(options: RewriteTablePathOptions): RewriteTablePathPlan {
    val source = options.sourcePrefix
    val target = options.targetPrefix
    val newest = versions.lastOrNull()
    val stagingDir = options.stagingLocation?.let { withSep(it) }
        ?: (newest?.metadata?.location?.let { withSep(it) + "metadata/" } ?: "metadata/") + "copy-table-staging-<uuid>/"
    fun refuse(message: String, endVersion: String? = null) = RewriteTablePathPlan(options, message, endVersion, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), stagingDir)
    if (source.isEmpty()) return refuse("Source prefix('$source') cannot be empty.")
    if (target.isEmpty()) return refuse("Target prefix('$target') cannot be empty.")
    if (source == target) return refuse("Source prefix cannot be the same as target prefix ($source)")
    newest ?: return refuse("no metadata version")

    // The current metadata's own location as it records it — the log names every earlier version
    // by that path, and the end version is looked up in the log by file name.
    val currentLocation = newest.metadata.metadataLog.lastOrNull()?.metadataFile?.let { last ->
        last.substringBeforeLast(SEP) + SEP + newest.fileName
    } ?: (newest.metadata.location?.let { withSep(it) + "metadata/" } ?: "") + newest.fileName
    val byName = versions.associateBy { it.fileName }
    // validateVersion: the current file or any log entry whose file name is the version's, and it must exist.
    fun resolveVersion(name: String): Pair<String, RewriteTablePathVersion>? {
        val recorded = when {
            fileNameOf(currentLocation) == name -> currentLocation
            else -> newest.metadata.metadataLog.lastOrNull { it.metadataFile?.let { f -> fileNameOf(f) == name } == true }?.metadataFile
        } ?: return null
        return byName[name]?.let { recorded to it }
    }
    val endName = options.endVersion?.let { fileNameOf(it) }
    val end: Pair<String, RewriteTablePathVersion> = if (endName == null) currentLocation to newest else {
        val inLog = fileNameOf(currentLocation) == endName || newest.metadata.metadataLog.any { it.metadataFile?.let { f -> fileNameOf(f) == endName } == true }
        if (!inLog) return refuse("Cannot find provided version file $endName in metadata log.")
        resolveVersion(endName) ?: return refuse("Version file $endName does not exist.")
    }
    val startName = options.startVersion?.let { fileNameOf(it) }
    val start: Pair<String, RewriteTablePathVersion>? = if (startName == null) null else {
        val inLog = fileNameOf(currentLocation) == startName || newest.metadata.metadataLog.any { it.metadataFile?.let { f -> fileNameOf(f) == startName } == true }
        if (!inLog) return refuse("Cannot find provided version file $startName in metadata log.")
        resolveVersion(startName) ?: return refuse("Version file $startName does not exist.")
    }
    val (endLocation, endVersion) = end
    val endMeta = endVersion.metadata
    if (endMeta.partitionStatistics.isNotEmpty()) return refuse("Partition statistics files are not supported yet.", fileNameOf(endLocation))

    val rewritten = mutableListOf<String>()
    val copies = mutableListOf<RewriteTablePathCopy>()
    val notStaged = mutableListOf<RewriteTablePathCopy>()

    // replacePaths on one version: every path it carries must be under the source prefix, in the
    // order the rewrite touches them — the snapshots' lists, the log, the three properties, the statistics.
    fun rewriteVersion(location: String, version: RewriteTablePathVersion): String? {
        val m = version.metadata
        for (s in m.snapshots) s.manifestList?.let { if (newPathOrNull(it, source, target) == null) return notUnder(it, source) }
        for (e in m.metadataLog) e.metadataFile?.let { if (newPathOrNull(it, source, target) == null) return notUnder(it, source) }
        for (key in listOf("write.folder-storage.path", "write.data.path", "write.metadata.path")) {
            m.properties[key]?.let { if (newPathOrNull(it, source, target) == null) return notUnder(it, source) }
        }
        for (st in m.statistics) st.statisticsPath?.let { if (newPathOrNull(it, source, target) == null) return notUnder(it, source) }
        rewritten += fileNameOf(location)
        copies += RewriteTablePathCopy(RewriteTablePathCopyKind.VERSION_FILE, stagingDir + fileNameOf(location), newPathOrNull(location, source, target) ?: return notUnder(location, source), staged = true)
        for (st in m.statistics) st.statisticsPath?.let { p ->
            val copy = RewriteTablePathCopy(RewriteTablePathCopyKind.STATISTICS, stagingDir + fileNameOf(p), newPathOrNull(p, source, target)!!, staged = true)
            copies += copy
            notStaged += copy
        }
        return null
    }
    rewriteVersion(endLocation, endVersion)?.let { return refuse(it, fileNameOf(endLocation)) }
    for (entry in endMeta.metadataLog.asReversed()) {
        val file = entry.metadataFile ?: continue
        if (start != null && file == start.first) break
        val version = byName[fileNameOf(file)] ?: return refuse("Version file $file doesn't exist", fileNameOf(endLocation))
        rewriteVersion(file, version)?.let { return refuse(it, fileNameOf(endLocation)) }
    }

    val startIds = start?.second?.metadata?.snapshots?.mapNotNull { it.snapshotId }?.toSet().orEmpty()
    val valid = endVersion.snapshots.filter { it.snapshotId != null && it.snapshotId !in startIds }
    // all_manifests of the end version: every manifest of every snapshot it lists; with a start, those added by a snapshot the start lacks.
    val deltaIds = if (start == null) null else valid.mapNotNull { it.snapshotId }.toSet()
    val toRewrite = endVersion.snapshots.flatMap { it.manifests }
        .filter { m -> deltaIds == null || m.addedSnapshotId in deltaIds }
        .mapNotNull { it.path }.toSet()

    val manifests = LinkedHashMap<String, RewriteTablePathManifest>()
    for (s in valid) {
        val list = s.manifestList ?: continue
        for (m in s.manifests) {
            val p = m.path ?: continue
            if (newPathOrNull(p, source, target) == null) return refuse("Encountered manifest file $p not under the source prefix $source", fileNameOf(endLocation))
        }
        copies += RewriteTablePathCopy(RewriteTablePathCopyKind.MANIFEST_LIST, stagingDir + fileNameOf(list), newPathOrNull(list, source, target)!!, staged = true)
        for (m in s.manifests) {
            val p = m.path ?: continue
            if (p in toRewrite && p !in manifests) {
                manifests[p] = m
                copies += RewriteTablePathCopy(RewriteTablePathCopyKind.MANIFEST, stagingDir + fileNameOf(p), newPathOrNull(p, source, target)!!, staged = true)
            }
        }
    }
    val seen = HashSet<Pair<String, String>>()
    for ((_, m) in manifests) {
        for (entry in m.entries) {
            val p = entry.path ?: continue
            val content = entry.content
            val live = entry.live
            when (content) {
                DataFileContent.DATA -> {
                    val to = newPathOrNull(p, source, target) ?: return refuse("Encountered data file $p not under the source prefix $source", fileNameOf(endLocation))
                    if (live && seen.add(p to to)) copies += RewriteTablePathCopy(RewriteTablePathCopyKind.DATA_FILE, p, to, staged = false)
                }
                DataFileContent.POSITION_DELETES -> {
                    if (entry.format.equals("puffin", ignoreCase = true) || entry.contentOffset != null) return refuse("Content offset is required for DV", fileNameOf(endLocation))
                    val to = newPathOrNull(p, source, target) ?: return refuse(notUnder(p, source), fileNameOf(endLocation))
                    val from = stagingDir + fileNameOf(p)
                    if (live && seen.add(from to to)) copies += RewriteTablePathCopy(RewriteTablePathCopyKind.POSITION_DELETE, from, to, staged = true)
                }
                DataFileContent.EQUALITY_DELETES -> {
                    val to = newPathOrNull(p, source, target) ?: return refuse(notUnder(p, source), fileNameOf(endLocation))
                    if (live && seen.add(p to to)) copies += RewriteTablePathCopy(RewriteTablePathCopyKind.EQUALITY_DELETE, p, to, staged = false)
                }
                else -> return refuse("Unsupported delete file type: $content", fileNameOf(endLocation))
            }
        }
    }
    return RewriteTablePathPlan(
        options, null, fileNameOf(endLocation), rewritten, valid.mapNotNull { it.snapshotId },
        manifests.keys.toList(), copies, notStaged, stagingDir,
    )
}
