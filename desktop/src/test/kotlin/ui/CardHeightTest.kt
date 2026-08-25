package ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import model.GraphModel
import model.GraphNode
import model.PaimonUnifiedTableModel
import model.UnifiedTableModel
import service.AggregationPolicy
import service.GraphLayoutService
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every card has to fit inside the height its node declares, and this is the assertion for it.
 *
 * `GraphNode` declares the height ELK reserves; the card is drawn at exactly that; a `Column`
 * out of room simply does not place its last child, and each `Text` clips itself to the size it
 * was measured at. So a card that outgrows its node loses a line with nothing failing — the
 * composition succeeds and the PNG is valid either way. A pixel probe looking for ink below the
 * card was written and run against a deliberately reverted fix, and reported nothing, because
 * nothing is ever painted outside the box.
 *
 * What works is measuring the content with room to spare. `LocalCardHeightSlack` draws each card
 * in a box far taller than its node, so `CardColumn` is measured unconstrained, and
 * `LocalCardContentProbe` reports the height its content and padding actually came to. That
 * number against the declared height is the check, and it is exact.
 *
 * **It sweeps every node of every fixture, not one of each kind.** A card's line count varies with
 * what the artifact carries — a manifest list with a longer name wraps, a snapshot with three refs
 * draws a second chip row, a file whose label gains a verdict needs another line — so one sample
 * per kind measures whichever instance the fixture happened to list first. That is enough to catch
 * a font-size change and not enough to say what a kind's height should be, which is what the
 * printed worst-case table below is for.
 */
class CardHeightTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun icebergGraph(fixture: String, pageSize: Int = AggregationPolicy.DEFAULT_PAGE_SIZE): GraphModel {
        val tableDir = File(repoRoot, "example/iceberg/default/$fixture")
        assertTrue(tableDir.isDirectory, "fixture missing at $tableDir")
        return GraphLayoutService.layoutGraph(
            UnifiedTableModel(Paths.get(tableDir.absolutePath)),
            showRows = true,
            policy = AggregationPolicy(pageSize = pageSize),
        )
    }

    private fun paimonGraph(): GraphModel {
        val tableDir = File(repoRoot, "example/paimon/db.db/test")
        assertTrue(tableDir.isDirectory, "the Paimon fixture should be checked in")
        return GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(tableDir.absolutePath)), showRows = true,
        )
    }

    /**
     * Every card the app can draw, from every checked-in table, in both of the states a filter
     * puts them in.
     *
     * The page size of 1 is not a duplicate run: it is the only way a `GroupNode` exists at all,
     * and a group's card sizes itself from the lines it will draw.
     */
    @Test
    fun `every card of every node in every fixture fits the height its node declares`() {
        val cards = buildList {
            ICEBERG_FIXTURES.forEach { fixture ->
                icebergGraph(fixture).nodes.forEach { node -> addAll(cardsFor(fixture, node)) }
            }
            icebergGraph("branched", pageSize = 1).nodes
                .filterIsInstance<GraphNode.GroupNode>()
                .forEach { node -> addAll(cardsFor("branched@1", node)) }
            paimonGraph().nodes.forEach { node -> addAll(cardsFor("paimon", node)) }
            // No fixture is broken, so nothing produces an ErrorNode — and its card was therefore
            // the one kind never measured. Built by hand rather than left uncovered, with the
            // longest strings the node can hold: a read error names a path, and a path is long.
            val error = GraphNode.ErrorNode(
                id = "err_probe",
                title = "MANIFEST READ ERROR",
                stage = "manifest",
                path = "/Users/someone/warehouse/default/orders/metadata/" +
                    "6f6bfefc-d312-49da-9d9b-03070876548a-m0.avro",
                message = "java.io.FileNotFoundException: the manifest this snapshot lists is not " +
                    "present at the path the manifest list recorded for it",
            )
            addAll(cardsFor("synthetic", error))
        }

        assertEveryNodeKindWasDrawn(cards)
        assertFits(cards)
    }

    /**
     * One card per state a node can be drawn in.
     *
     * The pruned state is not cosmetic here: it lengthens the title line, which is what costs a
     * card its last line. Measuring only the unfiltered state means the height is right until
     * somebody types a predicate.
     */
    private fun cardsFor(fixture: String, node: GraphNode): List<Pair<String, Card>> {
        val kind = node::class.simpleName ?: "?"
        val base = "$kind [$fixture ${node.id}]"
        val states = buildList {
            add("" to false)
            if (node is GraphNode.ManifestNode || node is GraphNode.FileNode) add(" pruned" to true)
        }
        return states.map { (suffix, pruned) ->
            "$base$suffix" to Card(node) { GraphNodeCard(node, isPruned = pruned) }
        }
    }

    /**
     * Every subtype of [GraphNode] has to appear, checked against the sealed hierarchy itself.
     *
     * A count would have said "12 cards" and needed bumping by hand every time a node type was
     * added — which is a check on the person editing the test, not on the code. Asking the sealed
     * class what its subtypes are makes a new node kind that nothing draws a failure here.
     */
    private fun assertEveryNodeKindWasDrawn(cards: List<Pair<String, Card>>) {
        val drawn = cards.mapNotNull { (_, card) -> card.node::class.simpleName }.toSet()
        val declared = GraphNode::class.sealedSubclasses.mapNotNull { it.simpleName }.toSet()
        assertTrue(
            declared.isNotEmpty(),
            "the sealed hierarchy reported no subclasses, so this check is measuring nothing",
        )
        assertTrue(
            (declared - drawn).isEmpty(),
            "no fixture produced a node of these kinds, so their cards are unmeasured: " +
                "${(declared - drawn).sorted()}",
        )
    }

    private class Card(val node: GraphNode, val draw: @Composable () -> Unit)

    private companion object {
        /** Every checked-in Iceberg table. A kind's worst case is not in any one of them. */
        val ICEBERG_FIXTURES = listOf(
            "test", "parted", "mor", "eqdel", "v3", "evolved", "respec", "branched",
        )

        /**
         * Room beyond the declared height, so the content is measured rather than clamped.
         *
         * Smaller than a screen on purpose: it multiplies by the number of cards in a batch, and
         * an `ImageComposeScene` allocates its whole surface. An overflow is a line or two.
         */
        val SLACK = 160.dp
        val SPACING = 12.dp
        val MARGIN = 16.dp

        /** Cards per scene. The surface is `800 x (batch x (height + slack)) x density` pixels. */
        const val BATCH = 24
    }

    /**
     * Draws every card with slack and requires none of them to have needed it.
     *
     * Batched rather than one scene: standing an `ImageComposeScene` up costs about a second, so
     * one per card turns this into something nobody runs — and one scene for all of them would
     * allocate a surface tens of thousands of pixels tall.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun assertFits(cards: List<Pair<String, Card>>) {
        val density = 2f
        val measured = mutableMapOf<String, Int>()

        cards.chunked(BATCH).forEach { batch ->
            // The scene has to hold every card in the batch at its full slack height. A `Column`
            // that runs out of room measures what is left with a maximum of zero and the probe
            // reports 0dp — which looks like a card that fits rather than like a card that was
            // never drawn, so a scene one card too short turns this green by leaving work undone.
            val sceneHeight = ((batch.sumOf { (_, card) -> (card.node.height + SLACK.value).toDouble() } +
                batch.size * SPACING.value + 2 * MARGIN.value) * density).toInt()
            val scene = ImageComposeScene(width = 800, height = sceneHeight, density = Density(density)) {
                // Inside the app's theme, because Material3's body style is where the 24sp line
                // height comes from and overriding it is `CardColumn`'s whole job. Measuring
                // outside the theme would measure a text style the running app never uses, and it
                // is the more permissive of the two — a card could pass here and still lose a line.
                MaterialTheme(colorScheme = IceLensLightColorScheme) {
                    CompositionLocalProvider(LocalCardHeightSlack provides SLACK) {
                        Column(Modifier.padding(MARGIN), verticalArrangement = Arrangement.spacedBy(SPACING)) {
                            batch.forEach { (name, card) ->
                                CompositionLocalProvider(
                                    LocalCardContentProbe provides { px -> measured[name] = px },
                                ) { card.draw() }
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
        }

        val unmeasured = cards.filter { (name, _) -> (measured[name] ?: 0) == 0 }
        assertTrue(
            unmeasured.isEmpty(),
            "these cards came to zero height, so the scene did not have room to draw them and " +
                "nothing here was measured: ${unmeasured.take(10).map { it.first }}",
        )

        reportWorstPerKind(cards, measured, density)

        val overflowing = cards.mapNotNull { (name, card) ->
            val wanted = measured.getValue(name) / density
            val declared = card.node.height
            if (wanted > declared + 0.5) {
                "$name wants ${"%.1f".format(wanted)}dp and its node declares $declared"
            } else {
                null
            }
        }
        assertTrue(
            overflowing.isEmpty(),
            "these cards draw more than their node reserves, so each loses its last line " +
                "silently:\n" + overflowing.joinToString("\n"),
        )
    }

    /**
     * Prints the tallest instance of each kind against what that kind reserves.
     *
     * This is the number a height can be tightened against, and printing it is the point: the
     * declared heights are generous, the graph is that much taller than it needs to be, and
     * shrinking one off a single sample is how a line goes missing on a table nobody rendered.
     */
    private fun reportWorstPerKind(
        cards: List<Pair<String, Card>>,
        measured: Map<String, Int>,
        density: Float,
    ) {
        val worst = cards
            .groupBy { (_, card) -> card.node::class.simpleName ?: "?" }
            .mapValues { (_, group) ->
                group.maxBy { (name, _) -> measured[name] ?: 0 }
                    .let { (name, card) -> Triple((measured[name] ?: 0) / density, card.node.height, name) }
            }
        val lines = worst.entries.sortedByDescending { (_, v) -> v.second - v.first }.joinToString("\n") {
            val (wanted, declared, which) = it.value
            "  %-24s worst %6.1fdp of %6.1fdp declared   (%s)".format(it.key, wanted, declared, which)
        }
        println("card heights, tallest instance of each kind across ${cards.size} cards:\n$lines")
    }
}
