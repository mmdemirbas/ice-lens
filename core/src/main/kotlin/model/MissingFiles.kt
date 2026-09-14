package model

import java.nio.file.Files
import java.nio.file.Path

/**
 * What the retained snapshots need that is not there — the converse of [findUnreferencedFiles],
 * and the question a `NoSuchFileException` at query time is asking after the fact.
 *
 * "Needed" is scoped to what a reader can still be asked for: every snapshot the newest
 * Iceberg metadata retains (an expired one is a state, not a missing file — see
 * `UnifiedSnapshot.expired`), and every Paimon snapshot under `snapshot/`, a tag, or a branch.
 * For each: its manifest list, its manifests, the data and delete files **live** in it — an
 * Iceberg `DELETED` entry names a file the commit removed, a Paimon `_KIND = 1` entry one the
 * replay drops, and neither is read — plus, on Paimon, the index manifest and index files, the
 * changelog files, the statistics file and the schema file the snapshot names, and on Iceberg
 * the newest metadata's statistics files. A snapshot only a tag retains needs no changelog: an
 * expiry deletes a tagged snapshot's changelog list and files (`tg`, `pea`), since a tag retains
 * data and not the stream. Not needed: older `metadata.json` versions (a reader
 * opens the newest, and `write.metadata.delete-after-commit.enabled` deletes the rest on
 * purpose), and the files only an expired snapshot listed — `expired` and `swept` are the
 * tables where those are gone by design and this must report nothing.
 *
 * A stat per needed file, so it sits behind a click like the walk it mirrors; on a remote table
 * the stats come off the cached listing.
 */
enum class MissingFileKind(val label: String) {
    MANIFEST_LIST("manifest list"),
    MANIFEST("manifest"),
    DATA_FILE("data file"),
    DELETE_FILE("delete file"),
    INDEX_FILE("index file"),
    CHANGELOG_FILE("changelog file"),
    STATISTICS("statistics file"),
    SCHEMA("schema file"),
}

data class MissingFile(
    val path: Path,
    val kind: MissingFileKind,
    /** The retained snapshots that read it, the way their panels name them. */
    val neededBy: List<String>,
)

data class MissingFilesReport(
    val root: Path,
    /** Distinct files the retained snapshots need. */
    val needed: Int,
    val snapshotsChecked: Int,
    val missing: List<MissingFile>,
) {
    /** [file]'s path under [root], as the reader knows the table; a file outside it as it is. */
    fun relativePathOf(file: MissingFile): String =
        runCatching { root.toAbsolutePath().normalize().relativize(file.path.toAbsolutePath().normalize()).toString() }
            .getOrDefault(file.path.toString())
            .let { if (it.startsWith("..")) file.path.toString() else it }
}

/** One file a snapshot needs, before the stat. */
private data class Needed(val path: Path, val kind: MissingFileKind, val by: String)

private fun report(root: Path, needed: List<Needed>, snapshots: Int): MissingFilesReport {
    val byPath = needed.groupBy { it.path.toAbsolutePath().normalize() }
    val missing = byPath.mapNotNull { (path, uses) ->
        if (runCatching { Files.exists(path) }.getOrDefault(false)) null
        else MissingFile(path, uses.first().kind, uses.map { it.by }.distinct())
    }.sortedWith(compareBy({ it.kind.ordinal }, { it.path.toString() }))
    return MissingFilesReport(root, byPath.size, snapshots, missing)
}

fun UnifiedTableModel.findMissingFiles(): MissingFilesReport {
    val newest = metadatas.lastOrNull() ?: return MissingFilesReport(path, 0, 0, emptyList())
    val needed = mutableListOf<Needed>()
    val metadataDir = newest.path.parent ?: path.resolve("metadata")
    newest.metadata.statistics.mapNotNull { it.statisticsPath }.forEach { needed += Needed(resolveRecordedOrRelative(metadataDir, it).first, MissingFileKind.STATISTICS, "the newest metadata") }
    newest.metadata.partitionStatistics.mapNotNull { it.statisticsPath }.forEach { needed += Needed(resolveRecordedOrRelative(metadataDir, it).first, MissingFileKind.STATISTICS, "the newest metadata") }
    val retained = newest.snapshots.filter { !it.expired }
    retained.forEach { s ->
        val by = "snapshot ${s.metadata.snapshotId}" + (s.metadata.summary["operation"]?.let { " ($it)" } ?: "")
        needed += Needed(s.path, MissingFileKind.MANIFEST_LIST, by)
        s.manifests.forEach { m ->
            needed += Needed(m.path, MissingFileKind.MANIFEST, by)
            m.dataFiles.forEach { f ->
                if (f.metadata.status == ManifestEntryStatus.DELETED) return@forEach
                val kind = if ((f.metadata.dataFile?.content ?: DataFileContent.DATA) == DataFileContent.DATA) MissingFileKind.DATA_FILE else MissingFileKind.DELETE_FILE
                needed += Needed(f.path, kind, by)
            }
        }
    }
    return report(path, needed, retained.size)
}

fun PaimonUnifiedTableModel.findMissingFiles(): MissingFilesReport {
    val needed = mutableListOf<Needed>()
    data class Line(val branch: String?, val metadataRoot: Path, val snapshots: List<PaimonUnifiedSnapshot>, val tagOnlyIds: Set<Long?>)
    val lines = listOf(Line(null, path, snapshots + tagOnlySnapshots, tagOnlySnapshots.map { it.metadata.id }.toSet())) +
        branches.map { Line(it.name, it.path, it.snapshots + it.tagOnlySnapshots, it.tagOnlySnapshots.map { t -> t.metadata.id }.toSet()) }
    val manifestDir = path.resolve("manifest")
    var count = 0
    lines.forEach { line ->
        line.snapshots.distinctBy { it.metadata.id }.forEach { s ->
            count++
            val md = s.metadata
            val by = "snapshot ${md.id}" + (md.commitKind?.let { " ($it)" } ?: "") +
                (if (md.id in line.tagOnlyIds) ", retained by a tag only" else "") + (line.branch?.let { " on $it" } ?: "")
            // A tag retains data, not changelog: an expiry deletes the changelog list and files of a
            // snapshot a tag still names (`tg`, `pea`), so a tag-only snapshot's are gone by design.
            val tagOnly = md.id in line.tagOnlyIds
            md.schemaId?.let { needed += Needed(line.metadataRoot.resolve("schema").resolve("schema-$it"), MissingFileKind.SCHEMA, by) }
            listOfNotNull(md.baseManifestList, md.deltaManifestList, md.changelogManifestList.takeUnless { tagOnly }).forEach { needed += Needed(manifestDir.resolve(it), MissingFileKind.MANIFEST_LIST, by) }
            md.indexManifest?.let { needed += Needed(manifestDir.resolve(it), MissingFileKind.MANIFEST, by) }
            md.statistics?.let { needed += Needed(path.resolve("statistics").resolve(it), MissingFileKind.STATISTICS, by) }
            s.indexFiles.forEach { index -> index.fileName?.let { needed += Needed(path.resolve("index").resolve(it), MissingFileKind.INDEX_FILE, by) } }
            (s.baseManifests + s.deltaManifests + (if (tagOnly) emptyList() else s.changelogManifests)).forEach { needed += Needed(it.path, MissingFileKind.MANIFEST, by) }
            // The files a read of the snapshot opens: what the delta replayed over the base leaves live.
            replayPaimonSnapshot(s).liveEntries.values.forEach { entry ->
                needed += Needed(entry.path, MissingFileKind.DATA_FILE, by)
                entry.metadata.file?.extraFiles?.forEach { name -> needed += Needed(entry.path.resolveSibling(name), MissingFileKind.INDEX_FILE, by) }
            }
            if (!tagOnly) s.changelogManifests.forEach { m -> m.entries.forEach { e -> if (e.metadata.kind == PaimonEntryKind.ADD) needed += Needed(e.path, MissingFileKind.CHANGELOG_FILE, by) } }
        }
    }
    return report(path, needed, count)
}
