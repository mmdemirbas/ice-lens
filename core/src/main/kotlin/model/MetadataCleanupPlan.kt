package model

/**
 * What `expire_snapshots(clean_expired_metadata => true)` drops from `metadata.json` besides the
 * snapshots — `RemoveSnapshots.apply` under `cleanExpiredMetadata` (1.10.0, lines 222–252): the
 * partition specs no retained snapshot's manifest records and the schemas no retained snapshot
 * was written under, the default spec and the current schema always kept. Every manifest of a
 * retained snapshot counts, data and delete alike, whatever its entries' statuses — so a spec
 * stays reachable through a filtered manifest holding nothing but `DELETED` entries until the
 * next commit drops that manifest from the list (`respec`: rewritten whole under its current
 * spec, the old specs went only after one more insert and an expiry down to that snapshot). At
 * 1.8.1 the core API cleans specs alone (`// TODO: Support cleaning expired schema as well`) and
 * the Spark procedure has no `clean_expired_metadata` parameter; 1.9 added both.
 *
 * Read from the manifest lists the expiry plan already holds, so it costs nothing beyond them;
 * `docs/fixtures/clean-expired-metadata.sql` records the runs on the 1.10.0 runtime.
 */
data class MetadataCleanupItem(
    val id: Int,
    val removed: Boolean,
    /** Why it stays — the default or current, or the retained snapshots that reach it (at most three named). */
    val keptBy: String?,
)

data class MetadataCleanupPlan(
    val specs: List<MetadataCleanupItem>,
    val schemas: List<MetadataCleanupItem>,
) {
    val removedSpecs: List<Int> get() = specs.filter { it.removed }.map { it.id }
    val removedSchemas: List<Int> get() = schemas.filter { it.removed }.map { it.id }
    val removesAnything: Boolean get() = removedSpecs.isNotEmpty() || removedSchemas.isNotEmpty()

    fun describe(): String = when {
        !removesAnything -> "keeps every spec and schema"
        else -> listOfNotNull(
            removedSpecs.takeIf { it.isNotEmpty() }?.let { "drops ${it.size} ${if (it.size == 1) "spec" else "specs"} (${it.joinToString(", ")})" },
            removedSchemas.takeIf { it.isNotEmpty() }?.let { "drops ${it.size} ${if (it.size == 1) "schema" else "schemas"} (${it.joinToString(", ")})" },
        ).joinToString(" and ")
    }
}

/** The specs and schemas an expiry removing [removed] leaves reachable, and the rest. */
fun ExpiryFileInput.planMetadataCleanup(removed: Set<Long>): MetadataCleanupPlan {
    val retained = metadata.snapshots.filter { it.snapshotId != null && it.snapshotId !in removed }
    val specUsers = mutableMapOf<Int, MutableList<Long>>()
    val schemaUsers = mutableMapOf<Int, MutableList<Long>>()
    retained.forEach { s ->
        val id = s.snapshotId!!
        manifestLists[id]?.forEach { m -> m.partitionSpecId?.let { specUsers.getOrPut(it) { mutableListOf() }.add(id) } }
        s.schemaId?.let { schemaUsers.getOrPut(it) { mutableListOf() }.add(id) }
    }
    fun keptBy(users: List<Long>?, pinned: String?): String? = when {
        pinned != null -> pinned
        users.isNullOrEmpty() -> null
        else -> "reached by ${users.distinct().size} retained ${if (users.distinct().size == 1) "snapshot" else "snapshots"}: " +
            users.distinct().take(3).joinToString(", ") + (if (users.distinct().size > 3) ", …" else "")
    }
    val specs = metadata.partitionSpecs.mapNotNull { spec ->
        val id = spec.specId ?: return@mapNotNull null
        val kept = keptBy(specUsers[id], if (id == metadata.defaultSpecId) "the default spec" else null)
        MetadataCleanupItem(id, removed = kept == null, keptBy = kept)
    }
    val schemas = metadata.schemas.mapNotNull { schema ->
        val id = schema.schemaId ?: return@mapNotNull null
        val kept = keptBy(schemaUsers[id], if (id == metadata.currentSchemaId) "the current schema" else null)
        MetadataCleanupItem(id, removed = kept == null, keptBy = kept)
    }
    return MetadataCleanupPlan(specs, schemas)
}
