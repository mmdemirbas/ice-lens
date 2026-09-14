package model

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * What is under the table root that nothing in the table's metadata names.
 *
 * Every other question this app answers starts from the metadata and reaches down: a snapshot
 * names manifests, a manifest names files. This one starts from the directory and asks the
 * metadata about each thing found there, which is the only direction that can find a file the
 * metadata has forgotten — a write that failed after its files landed, an expire that crashed
 * halfway, or the case the `cl` fixture pins: Paimon writing a changelog file for an overwrite and
 * then declining to commit it. Such files cost storage and are read by nothing, and the log line
 * that explained them was on a driver that is gone.
 *
 * **Referenced means named by any metadata on disk**, not only by the current version. An Iceberg
 * file reachable from `v3.metadata.json` but not from `v10` is reported as referenced here, because
 * a metadata file on disk names it; Iceberg's own `remove_orphan_files` reaches from the current
 * metadata only and can delete more than this reports. Paimon's reaches from every snapshot, as this
 * does, and from tags, which this follows too: a tag is a snapshot copy under `tag/`, and after
 * `expire_snapshots` it can be the only thing naming a data file — the `tg` fixture. Branches are
 * followed too, and have to be: a branch keeps only its snapshot, schema and tag files under
 * `branch/`, and writes its manifests and data files beside main's, so a walk that reads main
 * alone reports every file a branch committed as an orphan — the `br` fixture. A consumer's file
 * under `consumer/` is named by the model that read it, so the whole table directory is walked.
 *
 * Hidden files (a leading `.`) are skipped: Hadoop's local filesystem writes a `.crc` beside every
 * file and macOS writes `.DS_Store`, and neither is the table's. The walk is the whole table
 * directory, so it sits behind a click — on a remote table it is one subtree listing.
 */
/**
 * A file on disk the metadata does not name, with what deciding its removal takes: when it was
 * last modified — the one thing `remove_orphan_files` ages a file by, on both formats — and, when
 * the format's procedure would never list it, why ([unlistedBecause], null when it would).
 */
data class UnreferencedFile(
    val path: Path,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val unlistedBecause: String? = null,
)

data class UnreferencedFilesReport(
    val format: service.TableFormat,
    /** The table root the walk started from, so a listed path can be shown relative to it. */
    val root: Path,
    /** Every regular file the walk saw, hidden ones excluded. */
    val filesOnDisk: Int,
    /** Of those, how many the metadata names. */
    val referencedOnDisk: Int,
    /** The rest, in path order. */
    val unreferenced: List<UnreferencedFile>,
    /**
     * Iceberg only: files the walk counts as referenced that `remove_orphan_files` does not reach
     * — named by an older metadata version the current one's log has dropped, or held by a
     * `DELETED` entry alone — so a bare call deletes them while this report lists nothing. Empty
     * on Paimon, whose procedure names what the walk names.
     */
    val unreachedFromCurrent: List<UnreferencedFile> = emptyList(),
    /** Where the walk could not go, in the filesystem's own words. */
    val problems: List<String> = emptyList(),
) {
    val unreferencedBytes: Long get() = unreferenced.sumOf { it.sizeBytes }

    /** [file]'s path under [root], as the reader knows the table. */
    fun relativePathOf(file: UnreferencedFile): String =
        runCatching { root.toAbsolutePath().normalize().relativize(file.path).toString() }.getOrDefault(file.path.toString())
}

/** Every file the table's metadata names, resolved the way the model resolved it, as absolute paths. */
fun referencedFiles(model: FormatTableModel): Set<Path> = when (model) {
    is UnifiedTableModel -> icebergReferencedFiles(model)
    is PaimonUnifiedTableModel -> paimonReferencedFiles(model)
}.mapTo(mutableSetOf()) { it.toAbsolutePath().normalize() }

fun findUnreferencedFiles(model: FormatTableModel): UnreferencedFilesReport {
    val referenced = referencedFiles(model)
    val problems = mutableListOf<String>()
    val onDisk = walkTableFiles(model.path, problems)
    val root = model.path.toAbsolutePath().normalize()
    val unlisted: (Path) -> String? = when (model) {
        is UnifiedTableModel -> { path -> icebergUnlistedBecause(root, path) }
        is PaimonUnifiedTableModel -> {
            val keys = model.schemas.maxByOrNull { it.id ?: -1 }?.partitionKeys?.size ?: 0
            val branchNames = model.branches.map { it.name }
            ({ path -> paimonUnlistedBecause(root, path, keys, branchNames) })
        }
    }
    fun fileOf(path: Path, facts: DiskFile) = UnreferencedFile(path, facts.sizeBytes, facts.modifiedMs, unlisted(path))
    val unreferenced = onDisk
        .filterKeys { it !in referenced }
        .map { (path, facts) -> fileOf(path, facts) }
        .sortedBy { it.path.toString() }
    val reachable = (model as? UnifiedTableModel)?.let { icebergReachableFromCurrent(it) }
    val unreached = if (reachable == null) emptyList() else onDisk
        .filterKeys { it in referenced && it !in reachable }
        .map { (path, facts) -> fileOf(path, facts) }
        .sortedBy { it.path.toString() }
    return UnreferencedFilesReport(
        format = model.format,
        root = model.path,
        filesOnDisk = onDisk.size,
        referencedOnDisk = onDisk.size - unreferenced.size,
        unreferenced = unreferenced,
        unreachedFromCurrent = unreached,
        problems = problems,
    )
}

/**
 * What Iceberg's `remove_orphan_files` reaches, which is less than [icebergReferencedFiles]:
 * `DeleteOrphanFilesSparkAction.validFileIdentDS` at 1.8.1 is the **current** metadata file and
 * the entries of its `metadata-log` (`ReachableFileUtil.metadataFileLocations(table, recursive =
 * false)`), the version hint, the statistics and partition statistics files it names, and, for
 * every snapshot it lists, the manifest list, every manifest, and the **live** entries' files —
 * `ReadManifest` iterates `ManifestReader.iterator()`, which drops `DELETED` entries. So an older
 * `metadata.json` the log has dropped (`write.metadata.previous-versions-max`), or a data file
 * whose only remaining entry is the `DELETED` one a copy-on-write delete wrote, is an orphan to
 * the procedure and a referenced file to the walk. `orph` is the fixture.
 */
private fun icebergReachableFromCurrent(model: UnifiedTableModel): Set<Path> {
    val metadataDir = model.path.resolve("metadata")
    val current = model.metadatas.lastOrNull() ?: return emptySet() // sorted oldest first; the newest is current
    val paths = mutableListOf<Path>()
    paths.add(metadataDir.resolve("version-hint.text"))
    paths.add(current.path)
    val dir = current.path.parent ?: metadataDir
    current.metadata.metadataLog.forEach { entry ->
        entry.metadataFile?.let { paths.add(resolveRecordedOrRelative(dir, it).first) }
    }
    current.metadata.statistics.forEach { file ->
        file.statisticsPath?.let { paths.add(resolveRecordedOrRelative(dir, it).first) }
    }
    current.metadata.partitionStatistics.forEach { file ->
        file.statisticsPath?.let { paths.add(resolveRecordedOrRelative(dir, it).first) }
    }
    current.snapshots.forEach { snapshot ->
        paths.add(snapshot.path)
        snapshot.manifests.forEach { manifest ->
            paths.add(manifest.path)
            manifest.dataFiles.filter { it.metadata.status != ManifestEntryStatus.DELETED }.forEach { paths.add(it.path) }
        }
    }
    return paths.mapTo(mutableSetOf()) { it.toAbsolutePath().normalize() }
}

/**
 * Iceberg's `remove_orphan_files` lists the whole table location through `HiddenPathFilter`: a
 * name starting with `_` or `.` is hidden, and a hidden directory hides everything under it —
 * Hadoop's `_SUCCESS` and `_temporary` are the reason. A partition directory of a field whose
 * name starts so is exempt (`PartitionAwareHiddenPathFilter`); read here as any `name=value`
 * segment. The walk already skips `.`; a `_` file it reports is one the procedure leaves alone.
 */
private fun icebergUnlistedBecause(root: Path, path: Path): String? {
    val hidden = root.relativize(path).firstOrNull { seg ->
        val n = seg.toString()
        (n.startsWith("_") || n.startsWith(".")) && '=' !in n
    } ?: return null
    return "hidden to remove_orphan_files — `$hidden` starts with `_` or `.`"
}

private const val PAIMON_UNLISTED = "not in a directory remove_orphan_files lists (manifest/, index/, statistics/, " +
    "a bucket directory, snapshot/, changelog/)"

/**
 * Paimon's `remove_orphan_files` lists exactly these (`OrphanFilesClean.listPaimonFileDirs` and
 * `cleanBranchSnapshotDir` at release-1.3.1, shared by the local and the Spark cleaner): the files
 * directly inside `manifest/`, `index/` and `statistics/`; every `bucket-*` directory found by
 * descending exactly [partitionKeys] levels of `name=value` directories from the table root (and
 * the external data paths, which lie outside the walk); and, per branch, the files in `snapshot/`
 * not named `snapshot-*`, `EARLIEST` or `LATEST`, and in `changelog/` not `changelog-*`. A file
 * anywhere else — the table root, `schema/`, `tag/`, `consumer/`, a partition directory above
 * the buckets, a bucket directory at the wrong depth — is never a candidate, and a snapshot or
 * changelog file is kept whatever names it. `po` is the fixture, one stray per rule.
 */
private fun paimonUnlistedBecause(root: Path, path: Path, partitionKeys: Int, branches: List<String>): String? {
    val segs = root.relativize(path).map { it.toString() }
    val name = segs.last()
    val dirs = segs.dropLast(1)
    val lineRoots = listOf(emptyList<String>()) + branches.map { listOf("branch", "branch-$it") }
    for (line in lineRoots) {
        if (dirs == line + "snapshot") {
            return if (name.startsWith("snapshot-") || name == "EARLIEST" || name == "LATEST") {
                "in snapshot/ under a snapshot file's name, which remove_orphan_files never deletes"
            } else {
                null
            }
        }
        if (dirs == line + "changelog") {
            return if (name.startsWith("changelog-") || name == "EARLIEST" || name == "LATEST") {
                "in changelog/ under a changelog file's name, which remove_orphan_files never deletes"
            } else {
                null
            }
        }
    }
    if (dirs.size == 1 && dirs[0] in setOf("manifest", "index", "statistics")) return null
    val bucketDir = dirs.size == partitionKeys + 1 &&
        dirs.dropLast(1).all { '=' in it } &&
        dirs.last().startsWith("bucket-")
    return if (bucketDir) null else PAIMON_UNLISTED
}

private fun icebergReferencedFiles(model: UnifiedTableModel): List<Path> {
    val metadataDir = model.path.resolve("metadata")
    // add(), not +=: a Path is an Iterable<Path> of its own segments, and += would append those.
    val paths = mutableListOf<Path>()
    paths.add(metadataDir.resolve("version-hint.text"))
    model.metadatas.forEach { metadata ->
        paths.add(metadata.path)
        // A previous version the current one lists is referenced whether or not this model read
        // it, and a statistics file resolves the same way a manifest list does — recorded path
        // first, its file name under the metadata dir otherwise (see IcebergGraphBuilder).
        val dir = metadata.path.parent ?: metadataDir
        metadata.metadata.metadataLog.forEach { entry ->
            entry.metadataFile?.let { paths.add(resolveRecordedOrRelative(dir, it).first) }
        }
        metadata.metadata.statistics.forEach { file ->
            file.statisticsPath?.let { paths.add(resolveRecordedOrRelative(dir, it).first) }
        }
        metadata.metadata.partitionStatistics.forEach { file ->
            file.statisticsPath?.let { paths.add(resolveRecordedOrRelative(dir, it).first) }
        }
        metadata.snapshots.forEach { snapshot ->
            paths.add(snapshot.path)
            snapshot.manifests.forEach { manifest ->
                paths.add(manifest.path)
                manifest.dataFiles.forEach { paths.add(it.path) }
            }
        }
    }
    return paths
}

private fun paimonReferencedFiles(model: PaimonUnifiedTableModel): List<Path> =
    paimonLineReferencedFiles(model.path, model.path, model.schemas, model.snapshots, model.tags, model.changelogs) +
        model.branches.flatMap { paimonLineReferencedFiles(model.path, it.path, it.schemas, it.snapshots, it.tags, it.changelogs) } +
        model.consumers.map { it.path } +
        // The Iceberg export under `metadata/` names its own manifest lists and manifests, and
        // the data files it lists are the table's; without this every file of the export is a
        // false orphan in the direction that gets a file deleted (`pic`).
        model.icebergExport?.let(::icebergReferencedFiles).orEmpty()

/**
 * What one line of commits names: its own snapshot, schema and tag files under [metadataRoot],
 * and the manifests, index, statistics and data files under the table's [root] — the same
 * directory for main, a `branch/branch-<name>` for a branch.
 */
private fun paimonLineReferencedFiles(
    root: Path,
    metadataRoot: Path,
    schemas: List<PaimonSchema>,
    snapshots: List<PaimonUnifiedSnapshot>,
    tags: List<PaimonUnifiedTag>,
    /** The line's long-lived changelogs, whose changelog lists and files an expiry kept — `pcl`'s, false orphans without this. */
    changelogs: List<PaimonUnifiedSnapshot> = emptyList(),
): List<Path> {
    val manifestDir = root.resolve("manifest")
    // add(), not +=: a Path is an Iterable<Path> of its own segments, and += would append those.
    val paths = mutableListOf<Path>()
    paths.add(metadataRoot.resolve("snapshot").resolve("EARLIEST"))
    paths.add(metadataRoot.resolve("snapshot").resolve("LATEST"))
    // `changelog/` keeps its own hints; only a table whose changelog outlives its snapshots has the directory.
    metadataRoot.resolve("changelog").takeIf { Files.isDirectory(it) }?.let { dir ->
        paths.add(dir.resolve("EARLIEST"))
        paths.add(dir.resolve("LATEST"))
    }
    schemas.forEach { schema -> schema.id?.let { paths.add(metadataRoot.resolve("schema").resolve("schema-$it")) } }
    tags.forEach { paths.add(it.path) }
    (snapshots + tags.map { it.snapshot } + changelogs).forEach { snapshot ->
        paths.add(snapshot.path)
        val md = snapshot.metadata
        listOfNotNull(md.baseManifestList, md.deltaManifestList, md.changelogManifestList, md.indexManifest)
            .forEach { paths.add(manifestDir.resolve(it)) }
        md.statistics?.let { paths.add(root.resolve("statistics").resolve(it)) }
        snapshot.indexFiles.forEach { index -> index.fileName?.let { paths.add(root.resolve("index").resolve(it)) } }
        (snapshot.baseManifests + snapshot.deltaManifests + snapshot.changelogManifests).forEach { manifest ->
            paths.add(manifest.path)
            manifest.entries.forEach { entry ->
                paths.add(entry.path)
                // A file index too large to embed is `<file>.index` beside the data file, named
                // in the entry — the `fi` fixture's, at 599 KB, read as an orphan until this.
                entry.metadata.file?.extraFiles?.forEach { name -> paths.add(entry.path.resolveSibling(name)) }
            }
        }
    }
    return paths
}

/** What the walk records per file. */
private data class DiskFile(val sizeBytes: Long, val modifiedMs: Long)

/** Every regular file under [root] that is not hidden, with its size and mtime; failures go to [problems]. */
private fun walkTableFiles(root: Path, problems: MutableList<String>): Map<Path, DiskFile> {
    val files = linkedMapOf<Path, DiskFile>()
    if (!Files.isDirectory(root)) {
        problems += "$root is not a directory"
        return files
    }
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult = when {
                dir == root -> FileVisitResult.CONTINUE
                dir.isHidden() -> FileVisitResult.SKIP_SUBTREE
                else -> FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && !file.isHidden()) {
                    files[file.toAbsolutePath().normalize()] = DiskFile(attrs.size(), attrs.lastModifiedTime().toMillis())
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                problems += "$file: ${exc.message ?: exc::class.simpleName}"
                return FileVisitResult.CONTINUE
            }
        },
    )
    return files
}

private fun Path.isHidden(): Boolean = fileName?.toString()?.startsWith(".") == true
