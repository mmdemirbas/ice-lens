package service

import org.eclipse.elk.core.options.Direction

/**
 * Which shape the graph is laid out in.
 *
 * The default is what this app was built around and is the right one for the content: a table's
 * metadata is a containment hierarchy — table, versions, commits, manifests, files — and a layered
 * left-to-right layout is that hierarchy drawn as depth across the page. The alternatives exist
 * because a shape that suits the data does not always suit the *screen* or the question: a portrait
 * window fits a downward layout, a reader following one branch to its files wants a tree, and a
 * reader asking "what is clustered with what" wants forces.
 *
 * ### The rule that matters here
 *
 * **`refinesLayers` decides whether the post-processing runs, and only the left-to-right layered
 * layout gets it.** Every one of those passes is defined against that shape and no other:
 * `enforceChronologicalVerticalOrder` orders commits down a column, `alignParentsWithChildren`
 * moves a parent to its children's vertical centre, `spreadSnapshotBranches` gives a branch its own
 * *column*. Under a downward layout each of those is about the wrong axis; under a tree or a force
 * layout there are no layers for them to be about at all. Half-transposing them would produce a
 * drawing worse than ELK's own, so the other layouts are ELK's own output and this says so rather
 * than pretending the refinements carry over. Transposing them properly is a later change, and the
 * flag is where it would attach.
 */
enum class GraphLayoutAlgorithm(
    val label: String,
    val description: String,
    /** The ELK algorithm id. Each is a separate artifact that registers itself at startup. */
    val elkId: String,
    /** Null where the algorithm has no direction of its own. */
    val direction: Direction?,
    /** Whether the layered post-processing applies. True for exactly one entry — see above. */
    val refinesLayers: Boolean,
) {
    LAYERED_RIGHT(
        label = "Layered, left to right",
        description = "containment as depth across the page — the default",
        elkId = "org.eclipse.elk.layered",
        direction = Direction.RIGHT,
        refinesLayers = true,
    ),
    LAYERED_DOWN(
        label = "Layered, top to bottom",
        description = "the same layering, turned for a tall window",
        elkId = "org.eclipse.elk.layered",
        direction = Direction.DOWN,
        refinesLayers = false,
    ),
    TREE(
        label = "Tree",
        description = "one branch under its parent, compact",
        elkId = "org.eclipse.elk.mrtree",
        direction = Direction.DOWN,
        refinesLayers = false,
    ),
    FORCE(
        label = "Force-directed",
        description = "clusters what is connected, ignores hierarchy",
        elkId = "org.eclipse.elk.force",
        direction = null,
        refinesLayers = false,
    );

    companion object {
        val DEFAULT = LAYERED_RIGHT

        /** By [name], falling back to the default — a persisted value can outlive its enum entry. */
        fun byNameOrDefault(name: String?): GraphLayoutAlgorithm =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
