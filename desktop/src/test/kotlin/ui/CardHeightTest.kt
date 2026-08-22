package ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.ImageComposeScene
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
 * This is the test the type scale needed before it could be changed: raising a font size raises
 * every card that uses it, and eyes on a PNG are not a way to know whether a five-line card is
 * still five lines.
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

    @Test
    fun `every iceberg card fits inside the height its node declares`() {
        // parted for the ordinary cards, branched for the two snapshot shapes — it is the fixture
        // with five refs, so it has one snapshot carrying chips and plenty without — and a page
        // size of one on the same table so a group node is produced to measure too.
        val parted = icebergGraph("parted")
        val branched = icebergGraph("branched")
        val paged = icebergGraph("branched", pageSize = 1)
        val cards = buildList {
            parted.nodes.filterIsInstance<GraphNode.TableNode>().firstOrNull()
                ?.let { add("TableCard" to Card(it) { TableCard(it) }) }
            parted.nodes.filterIsInstance<GraphNode.MetadataNode>().firstOrNull()
                ?.let { add("MetadataCard" to Card(it) { MetadataCard(it) }) }
            parted.nodes.filterIsInstance<GraphNode.ManifestNode>().firstOrNull()
                ?.let { add("ManifestCard" to Card(it) { ManifestCard(it) }) }
            parted.nodes.filterIsInstance<GraphNode.FileNode>().firstOrNull()
                ?.let { add("FileCard" to Card(it) { FileCard(it) }) }
            parted.nodes.filterIsInstance<GraphNode.RowNode>().firstOrNull()
                ?.let { add("RowCard" to Card(it) { RowCard(it) }) }
            // Both snapshot shapes: the card declares 112dp instead of 84dp when it has ref
            // chips to draw, so the one without them is the tighter of the two.
            branched.nodes.filterIsInstance<GraphNode.SnapshotNode>().firstOrNull { it.refs.isNotEmpty() }
                ?.let { add("SnapshotCard with refs" to Card(it) { SnapshotCard(it) }) }
            branched.nodes.filterIsInstance<GraphNode.SnapshotNode>().firstOrNull { it.refs.isEmpty() }
                ?.let { add("SnapshotCard" to Card(it) { SnapshotCard(it) }) }
            paged.nodes.filterIsInstance<GraphNode.GroupNode>().firstOrNull()
                ?.let { add("GroupCard" to Card(it) { GroupCard(it) }) }
        }
        assertTrue(cards.size == 8, "only measured ${cards.map { it.first }}")
        assertFits(cards)
    }

    @Test
    fun `every paimon card fits inside the height its node declares`() {
        val tableDir = File(repoRoot, "example/paimon/db.db/test")
        assertTrue(tableDir.isDirectory, "the Paimon fixture should be checked in")
        val graph = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(tableDir.absolutePath)), showRows = true,
        )
        val kinds = listOf(
            "PaimonSnapshotNode" to graph.nodes.filterIsInstance<GraphNode.PaimonSnapshotNode>().firstOrNull(),
            "PaimonSchemaNode" to graph.nodes.filterIsInstance<GraphNode.PaimonSchemaNode>().firstOrNull(),
            "PaimonManifestListNode" to graph.nodes.filterIsInstance<GraphNode.PaimonManifestListNode>().firstOrNull(),
            "PaimonManifestNode" to graph.nodes.filterIsInstance<GraphNode.PaimonManifestNode>().firstOrNull(),
            "PaimonDataFileNode" to graph.nodes.filterIsInstance<GraphNode.PaimonDataFileNode>().firstOrNull(),
        )
        val cards = kinds.mapNotNull { (name, node) ->
            node?.let { name to Card(it) { PaimonNodeCard(it) } }
        }
        assertTrue(cards.size == kinds.size, "only measured ${cards.map { it.first }}")
        assertFits(cards)
    }

    private class Card(val node: GraphNode, val draw: @Composable () -> Unit)

    private companion object {
        /** Room beyond the declared height, so the content is measured rather than clamped. */
        val SLACK = 400.dp
        val SPACING = 24.dp
        val MARGIN = 16.dp
    }

    /**
     * Draws every card once, with 400dp of slack, and requires none of them to have needed it.
     *
     * The cards go in one scene rather than one each: an `ImageComposeScene` costs about a
     * second to stand up, and nine of them turns a check into something nobody runs.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun assertFits(cards: List<Pair<String, Card>>) {
        val density = 2f
        val measured = mutableMapOf<String, Int>()
        // The scene has to hold every card at its full slack height. A `Column` that runs out of
        // room measures what is left with a maximum of zero and the probe reports 0dp — which
        // looks like a card that fits rather than like a card that was never drawn, so a scene
        // too short by one card turns this test green by leaving the work undone.
        val sceneHeight = ((cards.sumOf { (_, card) -> (card.node.height + SLACK.value).toDouble() } +
            cards.size * SPACING.value + 2 * MARGIN.value) * density).toInt()
        val scene = ImageComposeScene(
            width = 800,
            height = sceneHeight,
            density = Density(density),
        ) {
            // Inside the app's theme, because Material3's body style is where the 24sp line height
            // comes from and overriding it is `CardColumn`'s whole job. Measuring outside the theme
            // would measure a text style the running app never uses, and it is the more permissive
            // of the two — a card could pass here and still lose a line on screen.
            MaterialTheme(colorScheme = IceLensLightColorScheme) {
                CompositionLocalProvider(LocalCardHeightSlack provides SLACK) {
                    Column(Modifier.padding(MARGIN), verticalArrangement = Arrangement.spacedBy(SPACING)) {
                        cards.forEach { (name, card) ->
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

        val unmeasured = cards.filter { (name, _) -> (measured[name] ?: 0) == 0 }
        assertTrue(
            unmeasured.isEmpty(),
            "these cards came to zero height, so the scene did not have room to draw them and " +
                "nothing here was measured: ${unmeasured.map { it.first }}",
        )
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
}
