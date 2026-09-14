package model

/**
 * Which files a Paimon `expire_snapshots` deletes along with the snapshots [planExpiry] removes —
 * decided the way `ExpireSnapshotsImpl.expireUntil` decides it at release-1.3.1, and checked
 * against one expiry run on a table copied on disk beforehand (`pe`/`pea`, from
 * `docs/fixtures/paimon-pe.sql`).
 *
 * The removed range is `[begin, end)`, `end` the first retained snapshot, and the walk is four
 * passes over it:
 *
 * 1. **Data files**, from the delta lists of `(begin, end]` — `end` included, because a file a
 *    snapshot's delta records as `DELETE` was live in the snapshot before it, which is expired.
 *    Each delta's entries in order: `DELETE` marks the file (and its extra files), an `ADD` of
 *    the same file after it unmarks it, which is what keeps a compaction's upgraded file. Then the
 *    **nearest earlier tag** — the last tag on a snapshot below the expiring one — protects every
 *    file its own merged manifests still hold; a table with no tag protects nothing.
 * 2. **Changelog files** added by the changelog lists of `[begin, end)`. A tag does not reach
 *    these.
 * 3. **Manifests**, against a skipping set: the base and delta lists, data manifests, index
 *    manifest, index files and statistics of snapshot `end` and of every tag in the range (the
 *    last tag at or below `begin` through the last below `end`). Each removed snapshot's base,
 *    delta and changelog lists go with every manifest they name that the set lacks, so do its
 *    index manifest, index files and statistics — and a tag's **changelog** list is not in the
 *    set, which is why a tag keeps data and not changelog.
 * 4. The snapshot files.
 *
 * **A decoupled changelog lifecycle changes what goes.** `CoreOptions.changelogLifecycleDecoupled`
 * is derived, not an option of its own: `changelog.num-retained.max`, `.min` or
 * `changelog.time-retained` above the snapshot setting (each defaulting to it). Then the expiry
 * keeps the change stream for `expire_changelogs` and writes the snapshot again as
 * `changelog/changelog-<id>`: pass 2 frees no changelog file and pass 3 no changelog list, and
 * on a table with no changelog producer — whose delta list *is* its change stream — pass 1 keeps
 * every `APPEND`-sourced data file and pass 3 the base and delta lists too
 * (`SnapshotDeletion.cleanUnusedDataFiles` / `cleanUnusedManifests`, `produceChangelog`). `pcl`
 * is the fixture: its `changelog/` holds what the passes left. [PaimonExpiryFilePlan.decoupled]
 * says which rule the plan ran under.
 */
enum class PaimonExpiryFileKind(val label: String) {
    DATA_FILE("data file"),
    CHANGELOG_FILE("changelog file"),
    INDEX_FILE("index file"),
    STATISTICS("statistics file"),
    MANIFEST("manifest"),
    INDEX_MANIFEST("index manifest"),
    MANIFEST_LIST("manifest list"),
    SNAPSHOT("snapshot file"),
}

enum class PaimonExpiryFileReason(val label: String) {
    REMOVED_BY_LATER("removed by a later commit; the last snapshot that read it expires"),
    CHANGE_STREAM("the change stream of an expired snapshot"),
    UNNAMED("named by no tag in the range and not by the first retained snapshot"),
    EXPIRED_SNAPSHOT("its snapshot expires"),
}

data class PaimonExpiryFile(
    val kind: PaimonExpiryFileKind,
    /** The file name Paimon records — unique, and what the copies of a table share. */
    val name: String,
    /** Where it is under the table, when the model resolved it. */
    val path: String?,
    val sizeBytes: Long?,
    val reason: PaimonExpiryFileReason,
    /** The removed snapshot it goes with — for a data file, the one whose delta removed it. */
    val snapshotId: Long?,
)

/** A file a removed snapshot's delta recorded as removed, kept because a tag still holds it. */
data class PaimonExpiryProtected(val name: String, val tag: String, val tagSnapshotId: Long, val removedBy: Long)

data class PaimonExpiryFilePlan(
    val beginInclusive: Long?,
    val endExclusive: Long?,
    val decoupled: Boolean,
    val files: List<PaimonExpiryFile>,
    val protectedByTag: List<PaimonExpiryProtected>,
) {
    fun ofKind(kind: PaimonExpiryFileKind): List<PaimonExpiryFile> = files.filter { it.kind == kind }
    val names: Set<String> get() = files.map { it.name }.toSet()
    val knownBytes: Long get() = files.sumOf { it.sizeBytes ?: 0L }
    val describe: String get() = when {
        files.isEmpty() -> "nothing"
        else -> PaimonExpiryFileKind.entries.mapNotNull { k -> ofKind(k).size.takeIf { it > 0 }?.let { "$it ${k.label}${if (it == 1) "" else "s"}" } }.joinToString(", ")
    }
}

/** One manifest as the plan reads it: the name a list records, its size, and its entries in order. */
data class PaimonExpiryManifestView(val name: String, val sizeBytes: Long?, val entries: List<PaimonUnifiedDataFile>)

data class PaimonExpirySnapshotView(
    val metadata: PaimonSnapshot,
    val base: List<PaimonExpiryManifestView>,
    val delta: List<PaimonExpiryManifestView>,
    val changelog: List<PaimonExpiryManifestView>,
    val indexFiles: List<PaimonIndexManifestEntry>,
) {
    val id: Long? get() = metadata.id
}

data class PaimonExpiryTagView(val name: String, val snapshot: PaimonExpirySnapshotView)

/** What the plan reads: `snapshot/` and `tag/` before the expiry, every manifest still on disk. */
data class PaimonExpiryFileInput(
    /** By id — `snapshot/` only, the branch the expiry runs on. */
    val snapshots: Map<Long, PaimonExpirySnapshotView>,
    /** In snapshot-id order, as `TagManager.taggedSnapshots()` lists them. */
    val tags: List<PaimonExpiryTagView>,
    val tableOptions: Map<String, String>,
)

private fun PaimonUnifiedSnapshot.view(): PaimonExpirySnapshotView {
    fun views(ms: List<PaimonUnifiedManifest>) = ms.map { m -> PaimonExpiryManifestView(paimonManifestKey(m), m.metadata.fileSize, m.entries) }
    return PaimonExpirySnapshotView(metadata, views(baseManifests), views(deltaManifests), views(changelogManifests), indexFiles)
}

fun PaimonUnifiedTableModel.expiryFileInput(): PaimonExpiryFileInput = PaimonExpiryFileInput(
    snapshots = snapshots.mapNotNull { s -> s.metadata.id?.let { it to s.view() } }.toMap(),
    tags = tags.sortedBy { it.snapshot.metadata.id ?: Long.MAX_VALUE }.map { PaimonExpiryTagView(it.name, it.snapshot.view()) },
    tableOptions = schemas.lastOrNull()?.options.orEmpty(),
)

/** The plan for removing every snapshot in [removed] — the ids [planExpiry] would drop, a contiguous range from the earliest. */
fun PaimonExpiryFileInput.planExpiryFiles(removed: Set<Long>): PaimonExpiryFilePlan {
    val decoupled = paimonChangelogLifecycleDecoupled(tableOptions)
    val producesChangelog = (tableOptions["changelog-producer"] ?: "none").lowercase() != "none"
    val ids = snapshots.keys.sorted()
    val removedIds = ids.filter { it in removed }
    if (removedIds.isEmpty()) return PaimonExpiryFilePlan(null, null, decoupled, emptyList(), emptyList())
    // The range is contiguous from the earliest: `expireUntil` walks [begin, end) and stops at the first kept.
    val begin = removedIds.first()
    val end = ids.firstOrNull { it > begin && it !in removed } ?: (removedIds.last() + 1)
    val range = ids.filter { it in begin until end }
    val files = mutableListOf<PaimonExpiryFile>()
    val seen = mutableSetOf<String>()
    fun add(kind: PaimonExpiryFileKind, name: String, path: String?, size: Long?, reason: PaimonExpiryFileReason, snapshotId: Long?) {
        if (seen.add(name)) files.add(PaimonExpiryFile(kind, name, path, size, reason, snapshotId))
    }
    fun nameOf(f: PaimonUnifiedDataFile) = f.metadata.file?.fileName ?: f.path.fileName.toString()

    // 1. Data files: the deltas of (begin, end], each against the nearest earlier tag.
    val protectedByTag = mutableListOf<PaimonExpiryProtected>()
    val tagFiles = mutableMapOf<Long, Set<String>>()
    fun mergedNames(s: PaimonExpirySnapshotView): Set<String> {
        // `readMergedDataFiles`: the base and delta manifests folded, ADD minus DELETE — one replay.
        val live = mutableMapOf<String, PaimonUnifiedDataFile>()
        (s.base + s.delta).forEach { m -> m.entries.forEach { e -> if (e.metadata.kind == PaimonEntryKind.DELETE) live.remove(nameOf(e)) else live[nameOf(e)] = e } }
        return live.keys
    }
    for (id in ids.filter { it in (begin + 1)..end }) {
        val s = snapshots[id] ?: continue
        val toDelete = linkedMapOf<String, PaimonUnifiedDataFile>()
        s.delta.forEach { m ->
            m.entries.forEach { e ->
                val name = nameOf(e)
                if (e.metadata.kind == PaimonEntryKind.DELETE) toDelete[name] = e else toDelete.remove(name)
            }
        }
        if (toDelete.isEmpty()) continue
        val previousTag = tags.lastOrNull { (it.snapshot.id ?: Long.MAX_VALUE) < id }
        val held = previousTag?.let { t -> tagFiles.getOrPut(t.snapshot.id!!) { mergedNames(t.snapshot) } }.orEmpty()
        toDelete.forEach { (name, e) ->
            // Decoupled with no changelog producer: an APPEND-sourced file is the change stream itself, left to expire_changelogs.
            val keptForChangelog = decoupled && !producesChangelog && (e.metadata.file?.fileSource ?: PaimonFileSource.APPEND) == PaimonFileSource.APPEND
            if (name in held) protectedByTag.add(PaimonExpiryProtected(name, previousTag!!.name, previousTag.snapshot.id!!, id))
            else if (keptForChangelog) Unit
            else {
                add(PaimonExpiryFileKind.DATA_FILE, name, e.path.toString(), e.metadata.file?.fileSize, PaimonExpiryFileReason.REMOVED_BY_LATER, id)
                e.metadata.file?.extraFiles.orEmpty().forEach { extra -> add(PaimonExpiryFileKind.DATA_FILE, extra, e.path.resolveSibling(extra).toString(), null, PaimonExpiryFileReason.REMOVED_BY_LATER, id) }
            }
        }
    }

    // 2. Changelog files added by [begin, end) — kept for the changelog when its lifecycle is decoupled.
    if (!decoupled) {
        range.forEach { id ->
            snapshots[id]?.changelog?.forEach { m ->
                m.entries.filter { it.metadata.kind == PaimonEntryKind.ADD }.forEach { e ->
                    add(PaimonExpiryFileKind.CHANGELOG_FILE, nameOf(e), e.path.toString(), e.metadata.file?.fileSize, PaimonExpiryFileReason.CHANGE_STREAM, id)
                }
            }
        }
    }

    // 3. Manifests, against what the tags in the range and snapshot `end` still name.
    val skipping = mutableSetOf<String>()
    fun skip(s: PaimonExpirySnapshotView) {
        s.metadata.baseManifestList?.let(skipping::add)
        s.metadata.deltaManifestList?.let(skipping::add)
        (s.base + s.delta).forEach { skipping.add(it.name) }
        s.metadata.indexManifest?.let { skipping.add(it); s.indexFiles.mapNotNull { f -> f.fileName }.forEach(skipping::add) }
        s.metadata.statistics?.let(skipping::add)
    }
    // `findSkippingTags`: from the last tag at or below `begin` (else the first) to the last below `end`.
    val right = tags.indexOfLast { (it.snapshot.id ?: Long.MAX_VALUE) < end }
    if (right >= 0) {
        val left = maxOf(tags.indexOfLast { (it.snapshot.id ?: Long.MAX_VALUE) <= begin }, 0)
        for (i in left..right) skip(tags[i].snapshot)
    }
    snapshots[end]?.let(::skip)
    range.forEach { id ->
        val s = snapshots[id] ?: return@forEach
        fun list(name: String?, manifests: List<PaimonExpiryManifestView>) {
            name ?: return
            manifests.forEach { m ->
                if (skipping.add(m.name)) add(PaimonExpiryFileKind.MANIFEST, m.name, "manifest/${m.name}", m.sizeBytes, PaimonExpiryFileReason.UNNAMED, id)
            }
            if (name !in skipping) add(PaimonExpiryFileKind.MANIFEST_LIST, name, "manifest/$name", null, PaimonExpiryFileReason.EXPIRED_SNAPSHOT, id)
        }
        // Decoupled: the changelog list stays for expire_changelogs; the base and delta lists go
        // unless the table produces no changelog, in which case the delta list is the change stream.
        if (!decoupled || producesChangelog) {
            list(s.metadata.baseManifestList, s.base)
            list(s.metadata.deltaManifestList, s.delta)
        }
        if (!decoupled) list(s.metadata.changelogManifestList, s.changelog)
        s.metadata.indexManifest?.let { im ->
            s.indexFiles.forEach { f -> f.fileName?.takeIf { it !in skipping }?.let { add(PaimonExpiryFileKind.INDEX_FILE, it, "index/$it", f.fileSize, PaimonExpiryFileReason.UNNAMED, id) } }
            if (im !in skipping) add(PaimonExpiryFileKind.INDEX_MANIFEST, im, "index/$im", null, PaimonExpiryFileReason.UNNAMED, id)
        }
        s.metadata.statistics?.takeIf { it !in skipping }?.let { add(PaimonExpiryFileKind.STATISTICS, it, "statistics/$it", null, PaimonExpiryFileReason.UNNAMED, id) }
    }

    // 4. The snapshot files.
    range.forEach { id -> add(PaimonExpiryFileKind.SNAPSHOT, "snapshot-$id", "snapshot/snapshot-$id", null, PaimonExpiryFileReason.EXPIRED_SNAPSHOT, id) }
    return PaimonExpiryFilePlan(begin, end, decoupled, files, protectedByTag)
}
