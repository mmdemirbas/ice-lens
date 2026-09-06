package model

import service.TableFormat
import java.nio.file.Path

/**
 * Common interface for all table format models.
 *
 * Sealed to ensure compile-time exhaustiveness in `when` expressions —
 * adding a new format implementation will produce compile errors at
 * every dispatch point that needs updating.
 */
sealed interface FormatTableModel {
    val path: Path
    val name: String
    val format: TableFormat
    val readErrors: List<UnifiedReadError>
}

/**
 * The table at [path], read as whichever format is there.
 *
 * Core rather than shell because it is knowledge about the artifacts, not about a screen — and
 * because there are two shells now. `GraphLayoutService.layoutGraph` already dispatches on the
 * model's type, so this is the other half of that single dispatch point: a format added to
 * `TableFormat` fails to compile here until it is read, instead of silently falling through to
 * Iceberg in whichever shell forgot to be updated.
 */
fun readTableModel(path: Path): FormatTableModel = when (service.TableFormatDetector.detect(path)) {
    TableFormat.PAIMON -> PaimonUnifiedTableModel(path)
    TableFormat.ICEBERG, TableFormat.UNKNOWN -> UnifiedTableModel(path)
}

/**
 * Result of building graph nodes and edges from any table model.
 * Shared by all format-specific graph builders.
 */
data class GraphBuildResult(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val summary: TableSummary,
    /**
     * Sample rows for a data-file node, keyed by that node's id, produced on demand.
     *
     * Deferred rather than built with the rest of the graph because a builder cannot know which
     * data files will be drawn — aggregation decides that, and it runs afterwards. Building them
     * eagerly costs a filesystem stat and five nodes per data file in the table, most of which
     * are then thrown away; a table with 100,000 files pays half a million nodes for the handful
     * a reader is looking at.
     */
    val sampleRows: Map<String, () -> List<GraphNode.RowNode>> = emptyMap(),
)
