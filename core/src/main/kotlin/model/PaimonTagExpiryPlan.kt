package model

import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Which tags `expire_tags` removes, and what removing one frees — `TagTimeExpire.expire` and
 * `TagManager.deleteTag` at release-1.3.1.
 *
 * A tag records `tagCreateTime` and `tagTimeRetained` only when it was created with a retention
 * (`time_retained`, or the table's `tag.default-time-retained`); without one it is the snapshot's
 * JSON verbatim. A bare call removes a tag whose create time plus retention is before now, and
 * never a tag recording neither. `older_than` removes any tag created before it, a tag recording
 * no create time going by its file's modification time. Both are `LocalDateTime` comparisons in
 * the writer's zone. The same expiry runs at commit time (`TableCommitImpl`, through
 * `TagAutoManager`) whether or not tags are created automatically.
 *
 * Removing a tag whose snapshot is still in `snapshot/` deletes the tag file alone. One whose
 * snapshot has expired — and that no other tag names — also frees what the tag alone held: its
 * merged data files not in the skip set, and its lists, manifests, index manifest and files and
 * statistics not named by the skip set, where the skip set is the tag before it and the nearer of
 * the earliest snapshot and the tag after it (`doClean`). `ptt`/`ptta` is the oracle for the time
 * rule and a run on a copy of `tg` for the files.
 */
data class PaimonTagInput(
    val name: String,
    val snapshotId: Long?,
    val createTime: LocalDateTime?,
    val timeRetainedMs: Long?,
    /** The tag file's modification time — what `older_than` reads for a tag recording no create time. */
    val fileModifiedMs: Long?,
    /** Whether `snapshot/` still holds the tagged snapshot; removing the tag then frees nothing. */
    val snapshotRetained: Boolean,
)

data class PaimonTagExpiryVerdict(
    val tag: PaimonTagInput,
    /** The create time the rule read, as epoch milliseconds — recorded, or the file's; null where neither. */
    val createdMs: Long?,
    /** When the retention runs out; null without one. */
    val expiresAtMs: Long?,
    val expired: Boolean,
    val reason: String,
)

data class PaimonTagExpiryPlan(
    val nowMs: Long,
    val olderThanMs: Long?,
    val tags: List<PaimonTagExpiryVerdict>,
) {
    val removed: List<PaimonTagExpiryVerdict> get() = tags.filter { it.expired }
}

/** The plan at [nowMs], a bare call with [olderThanMs] null; [zone] is the writer's, where `LocalDateTime.now()` is taken. */
fun PaimonExpiryInput.planTagExpiry(nowMs: Long, olderThanMs: Long? = null, zone: ZoneId = ZoneId.systemDefault()): PaimonTagExpiryPlan {
    val verdicts = tags.map { tag ->
        val recorded = tag.createTime?.atZone(zone)?.toInstant()?.toEpochMilli()
        val retained = tag.timeRetainedMs
        if (recorded != null && retained != null) {
            val expiresAt = recorded + retained
            val reachedRetention = nowMs > expiresAt
            val olderThan = olderThanMs != null && olderThanMs > recorded
            PaimonTagExpiryVerdict(
                tag, recorded, expiresAt, reachedRetention || olderThan,
                when {
                    reachedRetention -> "its retention of ${formatPaimonDurationMs(retained)} ran out"
                    olderThan -> "created before older_than"
                    olderThanMs != null -> "created after older_than, with ${formatPaimonDurationMs(expiresAt - nowMs)} of its retention left"
                    else -> "${formatPaimonDurationMs(expiresAt - nowMs)} of its retention left"
                },
            )
        } else if (olderThanMs == null) {
            PaimonTagExpiryVerdict(tag, null, null, false, "records no retention, and a bare call reads nothing else")
        } else {
            val modified = tag.fileModifiedMs
            when {
                modified == null -> PaimonTagExpiryVerdict(tag, null, null, false, "records no create time and its file could not be dated, so it is skipped")
                olderThanMs > modified -> PaimonTagExpiryVerdict(tag, modified, null, true, "its file was written before older_than, recording no create time")
                else -> PaimonTagExpiryVerdict(tag, modified, null, false, "its file was written after older_than, recording no create time")
            }
        }
    }
    return PaimonTagExpiryPlan(nowMs, olderThanMs, verdicts)
}

/** `1 d`, `2 h`, `30 s` — the spelling the tag's `$tags` row and the docs use, for a millisecond count. */
fun formatPaimonDurationMs(ms: Long): String {
    val s = ms / 1000
    return when {
        ms < 0 -> "-" + formatPaimonDurationMs(-ms)
        s >= 86_400 && s % 86_400 == 0L -> "${s / 86_400} d"
        s >= 3_600 && s % 3_600 == 0L -> "${s / 3_600} h"
        s >= 3_600 -> "${s / 3_600} h ${s % 3_600 / 60} min"
        s >= 60 && s % 60 == 0L -> "${s / 60} min"
        s >= 60 -> "${s / 60} min ${s % 60} s"
        else -> "$s s"
    }
}

data class PaimonTagDeletionPlan(
    val tag: String,
    val files: List<PaimonExpiryFile>,
    /** Why the data files go, or do not. */
    val note: String,
) {
    val dataFiles: List<PaimonExpiryFile> get() = files.filter { it.kind == PaimonExpiryFileKind.DATA_FILE }
    val describe: String get() = PaimonExpiryFileKind.entries.mapNotNull { k -> files.count { it.kind == k }.takeIf { it > 0 }?.let { "$it ${k.label}${if (it == 1) "" else "s"}" } }.joinToString(", ")
}

/** What `deleteTag` frees for one tag — `TagManager.deleteTag` and `doClean`. Null for a tag the input does not hold. */
fun PaimonExpiryFileInput.planTagDeletion(tagName: String): PaimonTagDeletionPlan? {
    val index = tags.indexOfFirst { it.name == tagName }.takeIf { it >= 0 } ?: return null
    val tag = tags[index]
    val files = mutableListOf(PaimonExpiryFile(PaimonExpiryFileKind.TAG, "tag-$tagName", "tag/tag-$tagName", null, PaimonExpiryFileReason.TAG_FILE, tag.snapshot.id))
    val id = tag.snapshot.id
    if (id != null && id in snapshots) return PaimonTagDeletionPlan(tagName, files, "snapshot $id is still retained, so only the tag file goes")
    if (tags.count { it.snapshot.id == id } > 1) return PaimonTagDeletionPlan(tagName, files, "another tag names snapshot $id, so only the tag file goes")
    // The skip set: the tag before, and the nearer of the earliest snapshot and the tag after.
    val skipped = mutableListOf<PaimonExpirySnapshotView>()
    if (index > 0) skipped += tags[index - 1].snapshot
    val earliest = snapshots.keys.minOrNull()?.let { snapshots[it] }
    val rightTag = tags.getOrNull(index + 1)?.snapshot
    val right = when {
        earliest == null -> rightTag
        rightTag == null -> earliest
        (earliest.id ?: Long.MAX_VALUE) < (rightTag.id ?: Long.MAX_VALUE) -> earliest
        else -> rightTag
    }
    right?.let { skipped += it }
    val skippedFiles = skipped.flatMap { it.mergedFiles().keys }.toSet()
    tag.snapshot.mergedFiles().forEach { (name, e) ->
        if (name in skippedFiles) return@forEach
        files += PaimonExpiryFile(PaimonExpiryFileKind.DATA_FILE, name, e.path.toString(), e.metadata.file?.fileSize, PaimonExpiryFileReason.TAG_ALONE, id)
        e.metadata.file?.extraFiles.orEmpty().forEach { extra -> files += PaimonExpiryFile(PaimonExpiryFileKind.DATA_FILE, extra, e.path.resolveSibling(extra).toString(), null, PaimonExpiryFileReason.TAG_ALONE, id) }
    }
    val skippedNames = skipped.flatMap { it.metadataNames() }.toSet()
    val s = tag.snapshot
    (s.base + s.delta).forEach { m -> if (m.name !in skippedNames) files += PaimonExpiryFile(PaimonExpiryFileKind.MANIFEST, m.name, "manifest/${m.name}", m.sizeBytes, PaimonExpiryFileReason.TAG_ALONE, id) }
    listOfNotNull(s.metadata.baseManifestList, s.metadata.deltaManifestList).forEach { if (it !in skippedNames) files += PaimonExpiryFile(PaimonExpiryFileKind.MANIFEST_LIST, it, "manifest/$it", null, PaimonExpiryFileReason.TAG_ALONE, id) }
    s.metadata.indexManifest?.let { im ->
        s.indexFiles.forEach { f -> f.fileName?.takeIf { it !in skippedNames }?.let { files += PaimonExpiryFile(PaimonExpiryFileKind.INDEX_FILE, it, "index/$it", f.fileSize, PaimonExpiryFileReason.TAG_ALONE, id) } }
        if (im !in skippedNames) files += PaimonExpiryFile(PaimonExpiryFileKind.INDEX_MANIFEST, im, "index/$im", null, PaimonExpiryFileReason.TAG_ALONE, id)
    }
    s.metadata.statistics?.takeIf { it !in skippedNames }?.let { files += PaimonExpiryFile(PaimonExpiryFileKind.STATISTICS, it, "statistics/$it", null, PaimonExpiryFileReason.TAG_ALONE, id) }
    val dataCount = files.count { it.kind == PaimonExpiryFileKind.DATA_FILE }
    val neighbours = skipped.mapNotNull { it.id }.joinToString(" and ") { "snapshot $it" }.ifEmpty { "nothing" }
    return PaimonTagDeletionPlan(tagName, files, "snapshot $id has expired: ${if (dataCount == 0) "no data file" else "$dataCount data file${if (dataCount == 1) "" else "s"}"} of its ${tag.snapshot.mergedFiles().size} ${if (dataCount == 0) "is" else "are"} held by the tag alone, against $neighbours")
}
