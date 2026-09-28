package model

/**
 * What a streaming reader of a Paimon table receives for each commit — the changelog files the
 * snapshot's changelog manifest list names — with what reading them for a filter takes. The
 * row lookup and its history answer what a *batch* read returns at each snapshot; this answers
 * what was *published*, which is the other side of a `changelog-producer` and the one a
 * downstream consumer sees: an update of key 2 is a `-U (2, b)` and a `+U (2, B)` under
 * `lookup` and a bare `+I (2, B)` under `input`, and the two are read from the same statements.
 */
data class ChangelogSnapshot(
    val snapshotId: Long,
    val timestampMs: Long?,
    val commitKind: String?,
    /** `changelogRecordCount`, the writer's figure — what the rows read are held to. */
    val recordCount: Long?,
    /** The changelog files the snapshot names, every one an `ADD` — the stream, not the table. */
    val files: List<PaimonLookupFile>,
    /** Read from `changelog/changelog-<id>`: the snapshot has expired and its changelog outlived it. */
    val longLived: Boolean = false,
)

/**
 * The commits of one line — [branch], `main` or a branch — that name a changelog, oldest first,
 * capped at [MAX_HISTORY_SNAPSHOTS] newest, and the schema the files are read under — the line's
 * latest, the lookup's rule. The commits are the line's retained snapshots **and its long-lived
 * changelogs** (`changelog/changelog-<id>`), since a streaming reader is handed the second where
 * the first is gone (`NextSnapshotFetcher.getNextSnapshot` at release-1.3.1, under a decoupled
 * lifecycle): `pcl` publishes at 5 from `changelog/` and at 7 and 8 from `snapshot/`. A
 * branch publishes a stream of its own (`pbc`), and a branch created from a tag starts with the
 * tag's snapshot and so with its changelog. The table node carries every line's as a
 * `DeferredRead`, since building one walks every commit's changelog manifests.
 */
data class PaimonChangelogInputs(
    /** `changelog-producer` as the latest schema records it — `none` when unset. */
    val producer: String,
    val schema: PaimonSchema,
    val snapshots: List<ChangelogSnapshot>,
    /** How many of the line's commits name a changelog, so a capped trace can say so. */
    val withChangelog: Int,
    val branch: String = "main",
) {
    val capped: Boolean get() = snapshots.size < withChangelog
    val readSchema: IcebergSchemaModel by lazy { paimonSchemaAsIceberg(schema, systemColumns = true) }

    /**
     * What the producer publishes for a change, in a sentence — read off `ChangelogProducer`
     * at release-1.3.1: `input` writes the input rows as they arrived, `lookup` and
     * `full-compaction` compute the before image at compaction and publish in the COMPACT
     * commit after the append, `none` publishes nothing a consumer can take a change from.
     */
    val producerRule: String get() = when (producer.lowercase()) {
        "input" -> "changelog-producer = input: each APPEND publishes its input rows as they arrived — an update of an existing key is a +I with no before image, a delete a -D — so the changelog is the writes, not the changes"
        "lookup" -> "changelog-producer = lookup: the change is computed against the table at commit time and published by the COMPACT commit after the APPEND that made it — an update of a key is a -U with the value before and a +U with the value after"
        "full-compaction" -> "changelog-producer = full-compaction: the change is computed by the next full compaction and published by that COMPACT commit, so it lags the write by the compaction interval"
        else -> "changelog-producer = $producer: no changelog is written, and a streaming reader is handed the rows of the delta files, which a merge has not been applied to"
    }
}

/** One record of the stream: which commit published it (a Paimon snapshot or a Delta version, [snapshotId]), its kind, its sequence number and its cells under the schema's names. */
data class ChangelogRecord(
    val snapshotId: Long,
    val commitKind: String?,
    /** `_VALUE_KIND` — see [PaimonRowKind]; null where the file did not carry one. */
    val kind: Int?,
    val sequenceNumber: Long?,
    val fileName: String,
    val cells: Map<String, Any?>,
    /** The format's own word for the kind where it has one — Delta's `_change_type` — printed in place of [PaimonRowKind.describe]. */
    val label: String? = null,
) {
    val isRetraction: Boolean get() = kind != null && PaimonRowKind.isRetraction(kind)
    val kindText: String get() = label ?: kind?.let(PaimonRowKind::describe) ?: "—"
}

/** One changelog file as read: how many of its records matched, or why it was not read. */
data class ChangelogFileRead(val snapshotId: Long, val fileName: String, val matched: Int, val error: String? = null)

/**
 * What a stream reader receives for the rows a filter matches, commit by commit — Paimon's
 * changelog files ([PaimonChangelogTrace]) or Delta's change data feed (`DeltaChangeFeedTrace`),
 * one shape so the lookup draws either. [unit] is what the format calls a commit, `snapshot` or
 * `version`; [publishing] how many of them published something, of which the last are read when
 * [capped]; [rule] is the sentence saying what the format publishes for a change; [line] the
 * Paimon branch the stream is of, null where the format has one line; [longLived] the commits
 * read from `changelog/`, whose snapshots have expired.
 */
data class Changelog(
    val records: List<ChangelogRecord>,
    val filesRead: List<ChangelogFileRead>,
    val capped: Boolean,
    val publishing: Int,
    val rule: String,
    val unit: String = "snapshot",
    val line: String? = null,
    val longLived: List<Long> = emptyList(),
) {
    val snapshotsRead: Int get() = filesRead.map { it.snapshotId }.distinct().size
    val unreadable: Int get() = filesRead.count { it.error != null }
    /** The snapshots at which something was published for the rows, oldest first. */
    val publishedAt: List<Long> get() = records.map { it.snapshotId }.distinct()
}

/** Every line's changelog inputs, `main` first and then the branches by name; the lines that name none are left out. */
fun PaimonUnifiedTableModel.paimonChangelogLines(): List<PaimonChangelogInputs> =
    listOfNotNull(paimonChangelogInputs()) + branches.sortedBy { it.name }.mapNotNull { paimonChangelogInputs(it.name) }

/** Whether any line's commit names a changelog — [paimonChangelogLines] without reading one. */
fun PaimonUnifiedTableModel.hasChangelog(): Boolean =
    (snapshots + changelogs + branches.flatMap { it.snapshots + it.changelogs }).any { it.changelogManifests.isNotEmpty() }

/** [branch]'s changelog inputs — `main` unless named — or null where none of its commits names one. */
fun PaimonUnifiedTableModel.paimonChangelogInputs(branch: String = "main"): PaimonChangelogInputs? {
    val line = if (branch == "main") null else branches.firstOrNull { it.name == branch } ?: return null
    val schema = (if (line == null) latestSchema else line.schemas.maxByOrNull { it.id ?: -1 }) ?: return null
    val commits = if (line == null) snapshots + changelogs else line.snapshots + line.changelogs
    val named = commits.filter { it.changelogManifests.isNotEmpty() }
        .distinctBy { it.metadata.id }
        .sortedBy { it.metadata.id ?: Long.MIN_VALUE }
    if (named.isEmpty()) return null
    val traced = named.takeLast(MAX_HISTORY_SNAPSHOTS).mapNotNull { snapshot ->
        val id = snapshot.metadata.id ?: return@mapNotNull null
        val files = snapshot.changelogManifests.flatMap { manifest -> manifest.entries }
            .filter { it.metadata.kind == PaimonEntryKind.ADD }
            .mapNotNull { it.asLookupFile() }
        ChangelogSnapshot(id, snapshot.metadata.timeMillis, snapshot.metadata.commitKind, snapshot.metadata.changelogRecordCount, files, snapshot.longLivedChangelog)
    }
    return PaimonChangelogInputs(schema.options["changelog-producer"] ?: "none", schema, traced, named.size, branch)
}
