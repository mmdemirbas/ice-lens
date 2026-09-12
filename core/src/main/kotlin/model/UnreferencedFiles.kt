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
 * a nested table layout under `branch/` and consumers are bookkeeping under `consumer/`; neither
 * is read, so both directories are left out of the walk rather than reported. Stated on the panel.
 *
 * Hidden files (a leading `.`) are skipped: Hadoop's local filesystem writes a `.crc` beside every
 * file and macOS writes `.DS_Store`, and neither is the table's. The walk is the whole table
 * directory, so it sits behind a click — on a remote table it is one subtree listing.
 */
data class UnreferencedFile(val path: Path, val sizeBytes: Long)

data class UnreferencedFilesReport(
    /** The table root the walk started from, so a listed path can be shown relative to it. */
    val root: Path,
    /** Every regular file the walk saw, hidden ones excluded. */
    val filesOnDisk: Int,
    /** Of those, how many the metadata names. */
    val referencedOnDisk: Int,
    /** The rest, in path order. */
    val unreferenced: List<UnreferencedFile>,
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
    val notWalked = when (model) {
        is UnifiedTableModel -> emptySet()
        is PaimonUnifiedTableModel -> setOf("branch", "consumer")
    }
    val onDisk = walkTableFiles(model.path, notWalked, problems)
    val unreferenced = onDisk
        .filterKeys { it !in referenced }
        .map { (path, size) -> UnreferencedFile(path, size) }
        .sortedBy { it.path.toString() }
    return UnreferencedFilesReport(
        root = model.path,
        filesOnDisk = onDisk.size,
        referencedOnDisk = onDisk.size - unreferenced.size,
        unreferenced = unreferenced,
        problems = problems,
    )
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

private fun paimonReferencedFiles(model: PaimonUnifiedTableModel): List<Path> {
    val root = model.path
    val manifestDir = root.resolve("manifest")
    // add(), not +=: a Path is an Iterable<Path> of its own segments, and += would append those.
    val paths = mutableListOf<Path>()
    paths.add(root.resolve("snapshot").resolve("EARLIEST"))
    paths.add(root.resolve("snapshot").resolve("LATEST"))
    model.schemas.forEach { schema -> schema.id?.let { paths.add(root.resolve("schema").resolve("schema-$it")) } }
    model.tags.forEach { paths.add(it.path) }
    (model.snapshots + model.tags.map { it.snapshot }).forEach { snapshot ->
        paths.add(snapshot.path)
        val md = snapshot.metadata
        listOfNotNull(md.baseManifestList, md.deltaManifestList, md.changelogManifestList, md.indexManifest)
            .forEach { paths.add(manifestDir.resolve(it)) }
        md.statistics?.let { paths.add(root.resolve("statistics").resolve(it)) }
        snapshot.indexFiles.forEach { index -> index.fileName?.let { paths.add(root.resolve("index").resolve(it)) } }
        (snapshot.baseManifests + snapshot.deltaManifests + snapshot.changelogManifests).forEach { manifest ->
            paths.add(manifest.path)
            manifest.entries.forEach { paths.add(it.path) }
        }
    }
    return paths
}

/**
 * Every regular file under [root] that is not hidden, with its size; failures go to [problems].
 * [notWalked] names top-level directories the format keeps and this model does not read.
 */
private fun walkTableFiles(root: Path, notWalked: Set<String>, problems: MutableList<String>): Map<Path, Long> {
    val files = linkedMapOf<Path, Long>()
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
                dir.parent == root && dir.fileName.toString() in notWalked -> FileVisitResult.SKIP_SUBTREE
                else -> FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && !file.isHidden()) {
                    files[file.toAbsolutePath().normalize()] = attrs.size()
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
