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
 * Result of building graph nodes and edges from any table model.
 * Shared by all format-specific graph builders.
 */
data class GraphBuildResult(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val summary: TableSummary,
)
