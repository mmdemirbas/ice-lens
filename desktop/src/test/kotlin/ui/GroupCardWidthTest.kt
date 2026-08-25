package ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import model.AggregationKind
import model.GraphNode
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The group card's words are the app's own, so they have to fit the card the app gives them.
 *
 * `CardHeightTest` is the same question on the other axis and cannot answer this one: a line too
 * wide is ellipsised or wrapped, and ellipsis does not change a card's height at all. The one that
 * shipped read `6 more metadata versi…` — the count survived and the noun saying *what* was not
 * drawn did not, which is the half a reader needs.
 *
 * **Only this card is swept, and that is the whole distinction.** Every other card prints something
 * the *table* decides — a path, a file name, a branch — and a long one ellipsising is the design,
 * not a defect. A `GroupNode` prints a count and a noun from [AggregationKind], a closed vocabulary
 * of ten strings this repository chooses. A string we choose that does not fit the box we chose for
 * it is a defect every time, and the eleventh kind somebody adds is the case this is here for.
 *
 * Measured the same way as the height sweep, one axis over: `LocalCardWidthSlack` draws the card
 * far wider than its node so `CardColumn` is measured unconstrained, and `LocalCardContentProbe`
 * reports the width the content and its padding came to. Measuring at the declared width would
 * report the clamp rather than the content.
 *
 * Run against the card as it shipped, it named five of the ten kinds. Since the fix the noun sits
 * in the eyebrow at `TypeScale.micro` and is no longer the widest line on any of them — all ten
 * measure 159.5dp, decided by a line the kind has nothing to do with — so the check was run again
 * with `METADATA`'s plural lengthened to `table metadata json versions`, and reported it at
 * 206.5dp. That is the evidence it still bites for the thing it is named after, rather than only
 * for whatever happens to be widest today.
 */
class GroupCardWidthTest {

    @Test
    fun `every aggregation kind's name fits the card that prints it`() {
        val nodes = AggregationKind.entries.map { kind ->
            kind to GraphNode.GroupNode(
                id = "grp_probe_${kind.key}",
                parentId = "table_root",
                kind = kind,
                // A view rather than a built list: the card reads `memberIds.size` and nothing
                // else, and ten kinds x a million ids is a lot of strings to allocate to print
                // one number.
                memberIds = java.util.Collections.nCopies(WIDE_COUNT, "member"),
                // Both optional lines on, because each is another string that has to fit and the
                // widest of the three is what decides the card. They cost nothing to include and
                // leaving them out would measure two thirds of the card.
                hiddenNodeCount = WIDE_COUNT + 1,
                hiddenErrorCount = WIDE_COUNT,
            )
        }

        val measured = measureContentWidths(nodes)
        val declared = GraphNode.GroupNode(id = "d", parentId = "p", kind = AggregationKind.FILE).width

        // Printed for the same reason the height sweep prints its worst case: 200dp is the width
        // every group card gets, and the margin left over is what an eleventh kind has to fit in.
        println("group card content width, of ${declared}dp declared, at a count of $WIDE_COUNT")
        measured.entries.sortedByDescending { it.value }.forEach { (key, px) ->
            println("  %-15s %6.1fdp".format(key, px / DENSITY))
        }

        val overflowing = nodes.mapNotNull { (kind, _) ->
            val width = measured.getValue(kind.key) / DENSITY
            if (width > declared) "${kind.key} (\"${kind.plural}\") wants ${width}dp of $declared" else null
        }
        assertTrue(
            overflowing.isEmpty(),
            "these group cards truncate the noun that says what they hide: $overflowing",
        )
    }

    /** Every card drawn wide, returning the width in pixels each one's content came to. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun measureContentWidths(
        nodes: List<Pair<AggregationKind, GraphNode.GroupNode>>,
    ): Map<String, Float> {
        val measured = mutableMapOf<String, Float>()
        val scene = ImageComposeScene(
            width = ((200 + SLACK.value + 2 * MARGIN.value) * DENSITY).toInt(),
            height = ((nodes.sumOf { (_, node) -> node.height } +
                nodes.size * SPACING.value + 2 * MARGIN.value) * DENSITY).toInt(),
            density = Density(DENSITY),
        ) {
            // Inside the app's theme for the same reason the height sweep is: the text style a
            // card inherits is where its metrics come from.
            MaterialTheme(colorScheme = IceLensLightColorScheme) {
                CompositionLocalProvider(LocalCardWidthSlack provides SLACK) {
                    Column(Modifier.padding(MARGIN), verticalArrangement = Arrangement.spacedBy(SPACING)) {
                        nodes.forEach { (kind, node) ->
                            CompositionLocalProvider(
                                LocalCardContentProbe provides { size -> measured[kind.key] = size.width.toFloat() },
                            ) { GraphNodeCard(node) }
                        }
                    }
                }
            }
        }
        try {
            scene.render()
        } finally {
            scene.close()
        }

        val missing = AggregationKind.entries.map { it.key }.filterNot { it in measured }
        assertTrue(missing.isEmpty(), "these cards were never measured, so nothing was checked: $missing")
        return measured
    }

    private companion object {
        /**
         * A count wide enough that the digits are not what is being measured.
         *
         * `formatCount` puts separators in, so 987,654 is the widest realistic prefix — six digits
         * and two separators. A single-digit count would let a noun pass here and truncate on the
         * first table with a thousand manifests under one snapshot.
         */
        const val WIDE_COUNT = 987_654

        val SLACK = 300.dp
        val SPACING = 12.dp
        val MARGIN = 16.dp
        const val DENSITY = 2f
    }
}
