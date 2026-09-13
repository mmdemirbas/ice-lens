package model

/**
 * What `expire_snapshots` would keep and remove, decided the way Iceberg's `RemoveSnapshots`
 * decides it — read from that class rather than from the docs, because the docs describe
 * `older_than` as the cutoff and the code makes it only the *default* cutoff:
 *
 * 1. A ref survives if it is `main`, or if its snapshot is no older than the ref's own
 *    `max-ref-age-ms` (else the table's `history.expire.max-ref-age-ms`, else forever).
 * 2. Every surviving ref keeps its snapshot. A surviving branch also keeps its ancestors from the
 *    tip down while fewer than `min-snapshots-to-keep` are kept **or** the ancestor is newer than
 *    the branch's cutoff — the branch's own `max-snapshot-age-ms` if set, else [olderThanMs] — and
 *    stops at the first ancestor that is neither. The branch's age therefore *replaces*
 *    `older_than` for everything the branch reaches, which is why `retained`'s first run expired
 *    nothing.
 * 3. A snapshot reached by no surviving ref is kept only while it is newer than [olderThanMs].
 * 4. Everything else is removed.
 */
data class ExpiryOptions(
    /** The moment the ages are measured from. */
    val nowMs: Long,
    /** The procedure's `older_than`, or null for the table's `history.expire.max-snapshot-age-ms` (5 days) before [nowMs]. */
    val olderThanMs: Long? = null,
    /** The procedure's `retain_last`, or null for the table's `history.expire.min-snapshots-to-keep` (1). */
    val retainLast: Int? = null,
)

/** The rule under which a ref keeps a snapshot. */
enum class KeepRule(val label: String) {
    /** The ref points at it — a branch's tip or a tag. */
    REF("referenced by"),
    /** Within the branch's `min-snapshots-to-keep` most recent ancestors. */
    WITHIN_MIN("within the minimum kept by"),
    /** Newer than the branch's cutoff — its own `max-snapshot-age-ms`, else `older_than`. */
    NEWER_THAN_CUTOFF("newer than the cutoff of"),
    /** On no surviving ref, but newer than the default cutoff. */
    UNREFERENCED_YOUNG("on no ref, newer than the cutoff"),
}

/** One reason a snapshot survives: the rule, and the ref it applies through (none for [KeepRule.UNREFERENCED_YOUNG]). */
data class Keep(val rule: KeepRule, val ref: String? = null)

/** Why a snapshot survives an expiry, or that it does not. */
data class SnapshotExpiryVerdict(
    val snapshotId: Long,
    val retained: Boolean,
    /** The rules that kept it — empty when it is removed. */
    val keptBy: List<Keep>,
) {
    /** `referenced by main; newer than the cutoff of audit` — one clause per rule, refs grouped under it. */
    fun describeKeptBy(): String = keptBy.groupBy { it.rule }.entries.joinToString("; ") { (rule, keeps) ->
        val refs = keeps.mapNotNull { it.ref }
        if (refs.isEmpty()) rule.label else "${rule.label} ${refs.joinToString(", ")}"
    }
}

data class RefExpiryVerdict(val name: String, val retained: Boolean, val reason: String)

data class ExpiryPlan(
    val options: ExpiryOptions,
    /** The cutoff refs without an age of their own fall back to. */
    val defaultCutoffMs: Long,
    val defaultMinSnapshotsToKeep: Int,
    val refs: List<RefExpiryVerdict>,
    val snapshots: List<SnapshotExpiryVerdict>,
) {
    val removed: List<Long> get() = snapshots.filter { !it.retained }.map { it.snapshotId }
}

private const val MAX_SNAPSHOT_AGE_MS_DEFAULT = 5L * 24 * 60 * 60 * 1000
private const val MIN_SNAPSHOTS_TO_KEEP_DEFAULT = 1

/** The plan for this metadata under [options] — see [ExpiryPlan]. */
fun TableMetadata.planExpiry(options: ExpiryOptions): ExpiryPlan {
    val now = options.nowMs
    val defaultCutoff = options.olderThanMs
        ?: (now - (properties["history.expire.max-snapshot-age-ms"]?.toLongOrNull() ?: MAX_SNAPSHOT_AGE_MS_DEFAULT))
    val defaultMin = options.retainLast
        ?: properties["history.expire.min-snapshots-to-keep"]?.toIntOrNull() ?: MIN_SNAPSHOTS_TO_KEEP_DEFAULT
    val defaultMaxRefAge = properties["history.expire.max-ref-age-ms"]?.toLongOrNull() ?: Long.MAX_VALUE
    val byId = snapshots.associateBy { it.snapshotId }

    fun ancestors(tip: Long?): Sequence<Snapshot> = generateSequence(byId[tip]) { byId[it.parentSnapshotId] }

    val refVerdicts = refs.map { (name, ref) ->
        val snapshot = byId[ref.snapshotId]
        val maxRefAge = ref.maxRefAgeMs ?: defaultMaxRefAge
        when {
            name == "main" -> RefExpiryVerdict(name, true, "main is never expired")
            snapshot == null -> RefExpiryVerdict(name, false, "its snapshot ${ref.snapshotId} is not in the table")
            now - (snapshot.timestampMs ?: now) <= maxRefAge ->
                RefExpiryVerdict(name, true, if (ref.maxRefAgeMs != null) "younger than its max-ref-age" else "no ref age applies")
            else -> RefExpiryVerdict(name, false, "older than its max-ref-age")
        }
    }
    val survivingRefs = refVerdicts.filter { it.retained }.mapNotNull { v -> refs[v.name]?.let { v.name to it } }

    val keptBy = LinkedHashMap<Long, MutableList<Keep>>()
    fun keep(id: Long?, why: Keep) { if (id != null) keptBy.getOrPut(id) { mutableListOf() } += why }

    survivingRefs.forEach { (name, ref) ->
        val isBranch = ref.type == "branch"
        keep(ref.snapshotId, Keep(KeepRule.REF, name))
        if (isBranch) {
            val cutoff = ref.maxSnapshotAgeMs?.let { now - it } ?: defaultCutoff
            val minToKeep = ref.minSnapshotsToKeep ?: defaultMin
            var kept = 0
            for (ancestor in ancestors(ref.snapshotId)) {
                val id = ancestor.snapshotId ?: break
                val young = (ancestor.timestampMs ?: Long.MIN_VALUE) >= cutoff
                if (kept < minToKeep) {
                    if (id != ref.snapshotId) keep(id, Keep(KeepRule.WITHIN_MIN, name))
                    kept++
                } else if (young) {
                    if (id != ref.snapshotId) keep(id, Keep(KeepRule.NEWER_THAN_CUTOFF, name))
                    kept++
                } else {
                    break
                }
            }
        }
    }
    val referenced = survivingRefs.flatMap { (_, ref) ->
        if (ref.type == "branch") ancestors(ref.snapshotId).mapNotNull { it.snapshotId }.toList() else listOfNotNull(ref.snapshotId)
    }.toSet()
    snapshots.forEach { snapshot ->
        val id = snapshot.snapshotId ?: return@forEach
        if (id !in referenced && (snapshot.timestampMs ?: Long.MIN_VALUE) >= defaultCutoff) keep(id, Keep(KeepRule.UNREFERENCED_YOUNG))
    }

    return ExpiryPlan(
        options = options,
        defaultCutoffMs = defaultCutoff,
        defaultMinSnapshotsToKeep = defaultMin,
        refs = refVerdicts,
        snapshots = snapshots.mapNotNull { s ->
            s.snapshotId?.let { SnapshotExpiryVerdict(it, keptBy.containsKey(it), keptBy[it].orEmpty()) }
        },
    )
}
