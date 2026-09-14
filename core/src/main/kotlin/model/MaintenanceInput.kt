package model

/**
 * What the table panel's maintenance summary — and the snapshot panel's `Rewrite` and
 * `Manifest Merge` sections — plan from: the newest metadata, whose properties are the options a
 * bare call runs under, and the current snapshot's node, whose deferred walks are the live
 * files, the delete pairing, the manifest list or the bucket trees the planners take.
 *
 * Built by the graph builder off its **full** node set, never read off the drawn graph.
 * Aggregation folds the siblings past the page size out of `graph.nodes`, and metadata versions
 * and snapshots are both drawn oldest first — so at a small page size, or under a snapshot
 * filter, the newest metadata and the current snapshot are exactly what is not drawn. A section
 * that looked for them there planned under an older version's options, which is a wrong answer
 * with nothing failing, or found no snapshot and drew no section at all. The node is held rather
 * than its walks so the snapshot's deferred reads stay one per snapshot: a reader who opens the
 * snapshot's own panel and then the table's walks once.
 */
sealed interface MaintenanceInput

data class IcebergMaintenanceInput(
    /** The newest `metadata.json` — its `properties` and `default-spec-id` are what a bare call reads. */
    val metadata: TableMetadata,
    /** Its file name — `v12.metadata.json` — which is how the panel names the metadata node. */
    val metadataFileName: String,
    /** The current snapshot's node; null when the newest metadata names none. */
    val current: GraphNode.SnapshotNode?,
) : MaintenanceInput

/** The newest `metadata.json` the table node carries — what a read of the table is decided under — or null off an Iceberg table. */
fun GraphModel.newestIcebergMetadata(): TableMetadata? =
    (nodeById["table_root"] as? GraphNode.TableNode)?.let { (it.maintenance.value as? IcebergMaintenanceInput)?.metadata }

data class PaimonMaintenanceInput(
    /** The latest snapshot on main; null on a table that has none. */
    val current: GraphNode.PaimonSnapshotNode?,
) : MaintenanceInput
