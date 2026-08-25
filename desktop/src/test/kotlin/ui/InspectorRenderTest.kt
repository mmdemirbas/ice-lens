@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import model.GraphModel
import model.GraphNode
import model.ManifestEntryStatus
import model.PaimonUnifiedTableModel
import model.PredicateOp
import model.ScanPredicate
import model.SnapshotRefLabel
import model.UnifiedTableModel
import service.AggregationPolicy
import service.GraphLayoutService
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Paths
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Renders the inspector panel off-screen and writes a PNG per case.
 *
 * Two things this is for. The first is a regression check no data-level test can make: the
 * inspector is the only consumer of the decoded column statistics and partition tuples, and a
 * composable that throws while measuring — a null in a table cell, a row built with the wrong
 * column count — fails here and nowhere else. The whole composition runs, so the failure is a
 * test failure rather than a blank panel someone notices weeks later.
 *
 * The second is that the rendering can actually be looked at. Screen capture on this machine
 * returns bare wallpaper (the Screen Recording permission is missing), so a window screenshot is
 * not available; an off-screen scene needs no window and no permission. The PNGs land in
 * `desktop/build/reports/inspector/` — open them to check the drawing, not just the data.
 *
 * What this does NOT cover: window chrome, the graph canvas, scrolling, and anything below the
 * rendered viewport. It is the panel's first screen, drawn at a fixed size.
 */
class InspectorRenderTest {

    private companion object {
        /** Device pixels per written strip. See [writeBands]. */
        const val BAND_HEIGHT = 1300
    }

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val outputDir = File(repoRoot, "desktop/build/reports/inspector")

    private fun graphFor(fixture: String): GraphModel {
        val tableDir = File(repoRoot, "example/iceberg/default/$fixture")
        assertTrue(tableDir.isDirectory, "fixture missing at $tableDir")
        val model = UnifiedTableModel(Paths.get(tableDir.absolutePath))
        return GraphLayoutService.layoutGraph(model, showRows = false)
    }

    private fun partedGraph(): GraphModel = graphFor("parted")

    @Test
    fun `the table inspector renders`() {
        val graph = partedGraph()
        val table = graph.nodes.filterIsInstance<GraphNode.TableNode>().firstOrNull()
        assertNotNull(table, "graph should contain a table root")
        renderInspector(graph, table.id, "table-node", height = 10400)
    }

    /**
     * The same panel with every section folded, which is the state a click produces and a render
     * otherwise never reaches.
     *
     * It is worth a capture of its own because folding is where a titled section stops being a
     * heading and becomes a control: the carets have to line up down the panel, the titles have
     * to be readable as a list of what the node holds, and the whole table has to fit in a
     * screen's worth of height or the feature has not paid for its chrome. The panel is 10,400dp
     * expanded; this is the number that says whether folding it was worth doing.
     */
    @Test
    fun `the table inspector renders with every section folded`() {
        val graph = partedGraph()
        val table = graph.nodes.filterIsInstance<GraphNode.TableNode>().single()
        val folded = SectionCollapseState().apply { setAll(true) }
        renderScene("table-node-folded", width = 1400, height = 2600) {
            NodeDetailsContent(graph, setOf(table.id), sectionCollapse = folded)
        }
    }

    @Test
    fun `the file inspector renders its column statistics and partition sections`() {
        val graph = partedGraph()
        val file = graph.nodes.filterIsInstance<GraphNode.FileNode>()
            .firstOrNull { it.columnStats.isNotEmpty() && it.partition?.isUnpartitioned == false }
        assertNotNull(file, "the partitioned fixture should yield a file node with stats and a partition")
        renderInspector(graph, file.id, "file-node", height = 6000)
    }

    /**
     * The snapshot carrying `main`, drawn as its card and then as its inspector.
     *
     * The card is where the ref chips live, and chips are exactly the thing a screenshot catches
     * and a test does not: they are laid out in a `FlowRow` because a `Row` would place the later
     * ones past the card's edge, unclipped and invisible, with nothing failing.
     */
    @Test
    fun `the snapshot card and inspector render refs and lineage`() {
        val graph = graphFor("mor")
        val withMain = graph.nodes.filterIsInstance<GraphNode.SnapshotNode>()
            .firstOrNull { node -> node.refs.any { it.name == "main" } }
        assertNotNull(withMain, "the merge-on-read fixture should have a snapshot carrying main")

        renderScene("snapshot-card", width = 700, height = 800) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SnapshotCard(withMain)
                SnapshotCard(withMain.copy(refs = withMain.refs + SnapshotRefLabel("v1.0-release", isBranch = false)))
            }
        }
        renderInspector(graph, withMain.id, "snapshot-node", height = 2600)

        // A commit that takes files out, which the one above does not. "What this commit did"
        // draws a removal in the error colour against additions in body colour, and a verdict
        // column with one kind of row in it cannot be judged for whether the exception is
        // findable — the same rule that put a pruned manifest next to an ordinary one.
        val compaction = graph.nodes.filterIsInstance<GraphNode.SnapshotNode>()
            .firstOrNull { it.change?.removed?.isNotEmpty() == true }
        assertNotNull(compaction, "the merge-on-read fixture should carry a commit that removes files")
        renderInspector(graph, compaction.id, "snapshot-node-compaction", height = 2600)
    }

    /**
     * The two delete-file answers that are not the same answer. A v3 deletion vector names the
     * data file it applies to; an equality delete cannot, and has to say so.
     */
    @Test
    fun `delete file inspectors explain what they delete from`() {
        val v3 = graphFor("v3")
        val vector = v3.nodes.filterIsInstance<GraphNode.FileNode>()
            .firstOrNull { it.data.referencedDataFile != null }
        assertNotNull(vector, "the v3 fixture should have a deletion vector naming its data file")
        renderInspector(v3, vector.id, "delete-vector-node", height = 2500)

        val eqdel = graphFor("eqdel")
        val equality = eqdel.nodes.filterIsInstance<GraphNode.FileNode>()
            .firstOrNull { it.data.content == model.DataFileContent.EQUALITY_DELETES }
        assertNotNull(equality, "the eqdel fixture should have an equality delete")
        renderInspector(eqdel, equality.id, "delete-equality-node", height = 2000)
    }

    @Test
    fun `the manifest inspector renders the entry table`() {
        val graph = partedGraph()
        val manifest = graph.nodes.filterIsInstance<GraphNode.ManifestNode>().firstOrNull()
        assertNotNull(manifest, "graph should contain a manifest")
        renderInspector(graph, manifest.id, "manifest-node", height = 5600)

        // parted's manifests are all additions, so the ledger's "entries that added nothing"
        // table is empty there and the case it exists for goes unseen. mor's compaction leaves
        // a manifest whose entries record removals.
        val mor = graphFor("mor")
        val withRemovals = mor.nodes.filterIsInstance<GraphNode.ManifestNode>()
            .firstOrNull { node -> node.entries.any { it.entry.status == ManifestEntryStatus.DELETED } }
        assertNotNull(withRemovals, "mor should have a manifest recording a removal")
        renderInspector(mor, withRemovals.id, "manifest-node-removals", height = 5600)
    }

    /**
     * The card and the inspector for a run of siblings the graph is not drawing.
     *
     * Rendered at a deliberately tight page size, because none of the checked-in fixtures is big
     * enough to trip the default — and a card nobody has looked at is how the last round's
     * clipped ref chips got shipped.
     *
     * Four states worth seeing side by side: a group standing for exactly its members, one that
     * also stands for a subtree, and one carrying read errors. The third is the one that must not
     * read as decoration; a failure folded into "and 40 more" is a failure nobody investigates.
     *
     * The first is here because it was missing. Every group this fixture produces stands for a
     * subtree, so all three captured states drew the "nodes in total" line and declared the taller
     * height — and the plainest shape, which declares the base, was never in front of anybody. It
     * had been losing its "Double-click to open" line, the one line saying the card is a control.
     * `CardHeightTest`'s sweep found it; this is the capture that would have.
     */
    @Test
    fun `the group card and inspector render what is not drawn`() {
        val tableDir = File(repoRoot, "example/iceberg/default/mor")
        val model = UnifiedTableModel(Paths.get(tableDir.absolutePath))
        val graph = GraphLayoutService.layoutGraph(
            model, showRows = false, policy = AggregationPolicy(pageSize = 1),
        )
        val group = graph.groups.firstOrNull()
        assertNotNull(group, "a page size of one should collapse something in the merge-on-read fixture")

        renderScene("group-card", width = 700, height = 900) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                GroupCard(group.copy(hiddenNodeCount = group.memberCount))
                GroupCard(group)
                GroupCard(group.copy(hiddenNodeCount = group.hiddenNodeCount + 4_812))
                GroupCard(group.copy(hiddenNodeCount = group.hiddenNodeCount + 4_812, hiddenErrorCount = 3))
            }
        }
        renderInspector(graph, group.id, "group-node", height = 1600)
    }

    /**
     * The badge that states what the canvas is not drawing, in each state it has.
     *
     * Its whole job is to be read at a glance from the corner of a graph, and the wording changes
     * with the state — "drawing all" against "drawing 431 of 6,180". A test can assert the numbers
     * it is handed; whether the sentence reads at 11sp against the canvas is the picture's job.
     */
    @Test
    fun `the graph status badge renders every state it has`() {
        renderScene("graph-status-badge", width = 700, height = 900) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                GraphStatusBadge(
                    drawnNodeCount = 118, hiddenByAggregation = 0, groupCount = 0,
                    hiddenByFilter = 0, pageSize = 24,
                    pageSizeChoices = AppState.GRAPH_PAGE_SIZE_CHOICES,
                    hasExpandedGroups = false, drawEverything = false,
                    onPageSizeChange = {}, onDrawEverythingChange = {}, onCollapseAllGroups = {},
                )
                GraphStatusBadge(
                    drawnNodeCount = 431, hiddenByAggregation = 5_749, groupCount = 1,
                    hiddenByFilter = 0, pageSize = 24,
                    pageSizeChoices = AppState.GRAPH_PAGE_SIZE_CHOICES,
                    hasExpandedGroups = false, drawEverything = false,
                    onPageSizeChange = {}, onDrawEverythingChange = {}, onCollapseAllGroups = {},
                )
                GraphStatusBadge(
                    drawnNodeCount = 96, hiddenByAggregation = 0, groupCount = 0,
                    hiddenByFilter = 335, pageSize = 24,
                    pageSizeChoices = AppState.GRAPH_PAGE_SIZE_CHOICES,
                    hasExpandedGroups = false, drawEverything = false,
                    onPageSizeChange = {}, onDrawEverythingChange = {}, onCollapseAllGroups = {},
                )
                GraphStatusBadge(
                    drawnNodeCount = 96, hiddenByAggregation = 5_749, groupCount = 37,
                    hiddenByFilter = 335, pageSize = 8,
                    pageSizeChoices = AppState.GRAPH_PAGE_SIZE_CHOICES,
                    hasExpandedGroups = true, drawEverything = false,
                    onPageSizeChange = {}, onDrawEverythingChange = {}, onCollapseAllGroups = {},
                )
                // Paging off: the badge says "all", and the reason it says so is a decision the
                // reader took rather than a table that happened to be small. The two read the
                // same from the outside, which is why the menu carries the check mark.
                GraphStatusBadge(
                    drawnNodeCount = 6_180, hiddenByAggregation = 0, groupCount = 0,
                    hiddenByFilter = 0, pageSize = 24,
                    pageSizeChoices = AppState.GRAPH_PAGE_SIZE_CHOICES,
                    hasExpandedGroups = false, drawEverything = true,
                    onPageSizeChange = {}, onDrawEverythingChange = {}, onCollapseAllGroups = {},
                )
            }
        }
    }

    /**
     * The scan-pruning section, with a filter that skips one manifest and leaves the other.
     *
     * Two things only a render can check here. The section carries the app's only text fields and
     * dropdowns, and they sit inside the inspector's `SelectionContainer` — a text field there
     * fights the selection gesture, which is why the controls opt out of it. And the verdict table
     * is three columns of prose in a panel far narrower than it, so whether the reason survives
     * the column widths is a question about pixels.
     */
    @Test
    fun `the scan pruning section renders a filter and its verdicts`() {
        val graph = partedGraph()
        val table = graph.nodes.filterIsInstance<GraphNode.TableNode>().first()
        // Chosen so all three verdicts appear and both stages visibly do different work — a
        // capture where every row says the same thing cannot show whether the column is scannable,
        // and one where the file table only ever repeats the manifest table's reason would not
        // show why there are two tables.
        //
        // `d >= 2024-03-06` skips the single-day manifest, so the file under it reads `not
        // reached` rather than a verdict of its own. Under the manifest that survives, the same
        // term skips two files outright and the third passes both terms and is read.
        //
        // `id <= 7` is the second half of the point: `parted` buckets `id`, which is not
        // order-preserving, so the manifest stage declines the term and says so — and the file
        // bounds answer it anyway, because they are the plain source values.
        val predicates = listOf(
            ScanPredicate("d", PredicateOp.GTE, "2024-03-06"),
            ScanPredicate("id", PredicateOp.LTE, "7"),
        )
        renderScene("scan-pruning-table", width = 1400, height = 3200) {
            NodeDetailsContent(graph, setOf(table.id), scanPredicates = predicates)
        }

        val manifest = graph.nodes.filterIsInstance<GraphNode.ManifestNode>().first()
        renderScene("scan-pruning-manifest", width = 1400, height = 1600) {
            NodeDetailsContent(graph, setOf(manifest.id), scanPredicates = predicates)
        }
    }

    /**
     * The per-parent collapse, which appears only on a parent whose pages were opened.
     *
     * Another state a click produces: `expandedGroupIds` is passed in for the same reason
     * `sectionCollapse` is. Worth a capture rather than only an assertion because the control's
     * whole job is to be findable on a node that no longer has a group beside it on the canvas —
     * whether the sentence explains which pages it means, and whether the button reads as the
     * inverse of "Show all", are questions about words on a screen.
     */
    @Test
    fun `a parent whose pages were opened offers to collapse them`() {
        val graph = GraphLayoutService.layoutGraph(
            UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/branched").absolutePath)),
            showRows = false,
            policy = AggregationPolicy(pageSize = 2),
        )
        val table = graph.nodes.filterIsInstance<GraphNode.TableNode>().single()
        val expanded = setOf(
            service.GraphAggregation.groupId(table.id, model.AggregationKind.METADATA, 1),
            service.GraphAggregation.groupId(table.id, model.AggregationKind.METADATA, 2),
        )
        renderScene("collapse-pages", width = 1400, height = 1400) {
            NodeDetailsContent(graph, setOf(table.id), expandedGroupIds = expanded)
        }
        // And at the width the pane actually opens at. `App.kt` starts the inspector at 300dp and
        // lets it be dragged to 200dp, so that — not the wide capture above — is where the header's
        // action row has to survive. A `Row` would have placed the third button past this edge,
        // painted and unreachable, with the wide capture still looking correct.
        renderScene("collapse-pages-narrow", width = 300, height = 900, density = 1f) {
            NodeDetailsContent(graph, setOf(table.id), expandedGroupIds = expanded)
        }
    }

    /**
     * Equality on a bucketed column, which is the one shape no other capture reaches.
     *
     * `id <= 7` above deliberately shows a bucket **declining** — a hash orders nothing. Equality
     * is the case that now reaches a verdict, and until this capture existed it appeared in no
     * render at all: the feature would have been asserted by two test files and looked at by
     * nobody. The literal is one the table holds, so the manifest carrying it must read as kept
     * and any manifest whose bucket range excludes it as skipped — a verdict column with one kind
     * of row in it cannot be judged for whether the exception is findable.
     *
     * The literal is chosen so that its bucket is **not** the literal. `id = 3` buckets to 3 under
     * `bucket[4]`, and the reason then reads `bucket[4](3) = 3` — a picture in which a reader
     * cannot see that a transform happened at all. A capture has to show the thing its caption
     * claims.
     */
    @Test
    fun `a bucket field renders a verdict for equality`() {
        val graph = partedGraph()
        val table = graph.nodes.filterIsInstance<GraphNode.TableNode>().first()
        val literal = (1..64).first { model.BucketTransform.bucketOf(it, 4) != it }
        val predicates = listOf(ScanPredicate("id", PredicateOp.EQ, "$literal"))
        renderScene("scan-pruning-bucket", width = 1400, height = 3200) {
            NodeDetailsContent(graph, setOf(table.id), scanPredicates = predicates)
        }
    }

    /**
     * The canvas itself. Everything else here renders one component in isolation, which cannot
     * see where a control ends up on the surface it belongs to — the badge sits opposite the
     * mini-map, and "opposite" is a claim about a screen, not about a composable.
     *
     * **Rendered at `Density(1f)` for framing.** A scene is a fixed number of device pixels, so
     * every step up in density is a step down in how much of the graph is inside it; at 2 these
     * two files would show a quarter of what they are for. Whether the drawing survives a scaled
     * display is a different question and has its own test above.
     *
     * Two graphs, because they answer different questions. `graph-canvas-partial` is a graph the
     * page size has cut down, which is the state the badge exists for. `graph-canvas-whole` is
     * one drawn entire, which is the only way to see the deletion-vector edges — they run from a
     * `.puffin` delete file to the data file it names, and both ends have to be on screen.
     */
    @Test
    fun `the canvas renders its badge, its groups, and its deletion-vector edges`() {
        val mor = UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/mor").absolutePath))
        val partial = GraphLayoutService.layoutGraph(
            mor, showRows = false, policy = AggregationPolicy(pageSize = 2),
        )
        assertTrue(partial.groups.isNotEmpty(), "a page size of two should collapse something")
        renderCanvas("graph-canvas-partial", partial, pageSize = 2)

        val v3 = UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/v3").absolutePath))
        val whole = GraphLayoutService.layoutGraph(v3, showRows = false)
        assertTrue(
            whole.edges.any { it.id.startsWith("e_dv_") },
            "the v3 fixture should give the canvas deletion-vector edges to draw",
        )
        renderCanvas("graph-canvas-whole", whole, pageSize = AggregationPolicy.DEFAULT_PAGE_SIZE)
    }

    /**
     * The fork, on the surface, which is the only place the branch columns can be judged.
     *
     * `SnapshotTracksTest` pins the assignment and the arithmetic; neither can say whether a
     * reader looking at the canvas sees two chains diverge. What to look for: the `audit` commit
     * one column right of `main`'s, its lineage edge running diagonally back to the commit it
     * forked from, and the manifest layer clear of the column that opened.
     */
    @Test
    fun `the canvas draws a fork as two columns`() {
        val graph = graphFor("branched")
        assertTrue(
            graph.nodes.filterIsInstance<GraphNode.SnapshotNode>().map { it.x }.distinct().size > 1,
            "the branched fixture should have put its branch in a column of its own",
        )
        renderCanvas("graph-canvas-branched", graph, pageSize = AggregationPolicy.DEFAULT_PAGE_SIZE)
    }

    /**
     * Two snapshots compared, which is a panel only a two-node selection reaches.
     *
     * Rendered on `mor` rather than `branched`: the interesting shape is a compaction, where files
     * left and arrived and the net record count barely moved, and a capture where every row says
     * "added" would check the same nothing a one-verdict pruning table would.
     */
    @Test
    fun `two selected snapshots render as a comparison`() {
        val graph = graphFor("mor")
        val snapshots = graph.nodes.filterIsInstance<GraphNode.SnapshotNode>()
            .sortedBy { it.data.sequenceNumber ?: Long.MAX_VALUE }
        assertTrue(snapshots.size >= 2, "mor should draw several snapshots")
        val pair = setOf(snapshots.first().id, snapshots.last().id)

        renderInspector(graph, pair, "snapshot-compare", height = 2600)
    }

    /**
     * What a positional delete file removes, after the button that reads it has been pressed.
     *
     * The result is a state a click produces, so the composable takes `startRequested` for the
     * same reason `NodeDetailsContent` takes `sectionCollapse` — a render never reaches it
     * otherwise, and a capture of the button alone says nothing about the table underneath it,
     * which is the whole feature. Rendered against `mor`, whose delete files are real ones written
     * by Spark.
     *
     * More frames than the usual two because the read is genuinely asynchronous: `produceState`
     * hands the query to `Dispatchers.IO` and the result arrives back on the scene's own
     * dispatcher, which only advances when the scene is rendered.
     */
    @Test
    fun `a positional delete file names what it deletes from`() {
        val graph = graphFor("mor")
        val delete = graph.nodes.filterIsInstance<GraphNode.FileNode>()
            .filter { it.data.content == model.DataFileContent.POSITION_DELETES && !it.isDeletionVector }
            .firstOrNull { node -> node.localPath?.let { File(it).isFile } == true }
        assertNotNull(delete, "the mor fixture should draw a positional delete file that is on disk")

        // The loading line is a state the running app shows and so is captured too. Two frames is
        // enough to be sure of catching it: the read cannot have completed, because the scene has
        // not been rendered enough times to deliver its result.
        renderScene("delete-targets-loading", width = 1400, height = 200) {
            Column(Modifier.padding(16.dp)) {
                PositionalDeleteTargets(delete, startRequested = true)
            }
        }

        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("delete-targets", width = 1400, height = 320, ready = settled::get) {
            Column(Modifier.padding(16.dp)) {
                PositionalDeleteTargets(delete, startRequested = true) { settled.set(true) }
            }
        }
    }

    /**
     * The same graph at two display scales, and the assertion that it is the same drawing.
     *
     * A scene twice as wide, twice as tall and at twice the density is the same window on a
     * display scaled to 200%: every dp is two pixels instead of one, and nothing else changes. So
     * everything drawn has to land at exactly twice the coordinate — that is what makes this an
     * assertion rather than a comparison of two pictures.
     *
     * It is the check the canvas did not have. Node positions were fed to
     * `Modifier.offset { IntOffset(...) }`, which is specified in pixels, while the card inside is
     * `Modifier.size(...dp)` — so at density 2 every card grew and none of them moved, and each
     * column was drawn over its neighbour. Nothing failed: the composition succeeded, the PNG was
     * valid, and the machine this was built on reports density 1, where the two spaces agree.
     *
     * Measured on the ink, not on the model, because the model was never wrong — a pairwise
     * rectangle check over the layout's own coordinates found no overlapping pair at either
     * density. The defect only exists once the coordinates are drawn.
     *
     * Run against the reverted fix, which is the only thing that makes it a check: it reported the
     * drawing at `(994, 847)-(2461, 959)` where twice the density-1 drawing is
     * `(998, 826)-(2602, 972)`. The right edge is the loud one, 141px short, because that is where
     * the columns that should have spread out piled up instead.
     */
    @Test
    fun `the canvas draws the same graph at every display scale`() {
        val graph = graphFor("test")
        val atOne = canvasInk(graph, width = 1800, height = 900, density = 1f)
        val atTwo = canvasInk(graph, width = 3600, height = 1800, density = 2f)

        // Three pixels of slack for the rounding and the antialiased fringe each edge picks up
        // independently.
        val expected = Ink(atOne.left * 2, atOne.top * 2, atOne.right * 2, atOne.bottom * 2)
        val drift = listOf(
            atTwo.left - expected.left,
            atTwo.top - expected.top,
            atTwo.right - expected.right,
            atTwo.bottom - expected.bottom,
        )
        assertTrue(
            drift.all { kotlin.math.abs(it) <= 3 },
            "at density 2 the drawing should be $expected — twice $atOne — and it is $atTwo",
        )
    }

    /** The rectangle the canvas actually drew into, in device pixels. */
    private data class Ink(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun canvasInk(graph: GraphModel, width: Int, height: Int, density: Float): Ink {
        val name = "canvas-density-${density.toInt()}"
        val png = renderPng(name, width, height, density) {
            GraphCanvas(
                graph = graph,
                positions = NodePositions(graph),
                selectedNodeIds = emptySet(),
                isSelectMode = false,
                zoom = 0.35f,
                onZoomChange = {},
                onSelectionChange = {},
            )
        }
        outputDir.mkdirs()
        writeBands(png, name)
        val image = ImageIO.read(ByteArrayInputStream(png))

        // The mini-map is anchored to the bottom-right corner and sized in dp, so it scales
        // exactly with the scene whatever the nodes do — leaving it in would satisfy the
        // assertion on its own. Its corner is masked out; the graph is nowhere near it at this
        // scene size, which the emptiness check below is enough to notice if it stops being true.
        val gutterLeft = image.width - ((MINI_MAP_MARGIN + MINI_MAP_WIDTH).value * density).toInt()
        val gutterTop = image.height - ((MINI_MAP_MARGIN + MINI_MAP_HEIGHT).value * density).toInt()

        val background = image.getRGB(1, 1)
        var left = image.width
        var top = image.height
        var right = -1
        var bottom = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (x >= gutterLeft && y >= gutterTop) continue
                if (image.getRGB(x, y) == background) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        assertTrue(right > left && bottom > top, "the canvas drew nothing at density $density")
        // A drawing clipped by the scene reports the scene's own edge, which scales exactly and
        // would pass the comparison however the nodes were placed.
        assertTrue(
            right < image.width - 1 && bottom < image.height - 1,
            "the drawing ($left,$top)-($right,$bottom) reaches the edge of the " +
                "${image.width}x${image.height} scene at density $density, so the comparison " +
                "would be between two clipped rectangles",
        )
        return Ink(left, top, right, bottom)
    }

    private fun renderCanvas(name: String, graph: GraphModel, pageSize: Int) {
        renderScene(name, width = 2000, height = 1200, density = 1f) {
            GraphCanvas(
                graph = graph,
                positions = NodePositions(graph),
                selectedNodeIds = emptySet(),
                isSelectMode = false,
                zoom = 0.6f,
                onZoomChange = {},
                onSelectionChange = {},
                statusOverlay = {
                    GraphStatusBadge(
                        drawnNodeCount = graph.nodes.count { it !is GraphNode.GroupNode },
                        hiddenByAggregation = graph.hiddenNodeCount,
                        groupCount = graph.groups.size,
                        hiddenByFilter = 0,
                        pageSize = pageSize,
                        pageSizeChoices = AppState.GRAPH_PAGE_SIZE_CHOICES,
                        hasExpandedGroups = false,
                        drawEverything = false,
                        onPageSizeChange = {},
                        onDrawEverythingChange = {},
                        onCollapseAllGroups = {},
                    )
                },
            )
        }
    }

    /**
     * Every card the Iceberg graph draws, at the size its node declares.
     *
     * The node's declared height is what ELK reserves and what the card is sized to, and Compose
     * clips nothing — so a card that draws more than it declares loses the overflow under its own
     * border with nothing failing. There is no assertion that can see it; the file is the check.
     */
    @Test
    fun `the graph cards render inside the size their nodes declare`() {
        val graph = partedGraph()
        val table = graph.nodes.filterIsInstance<GraphNode.TableNode>().first()
        val metadata = graph.nodes.filterIsInstance<GraphNode.MetadataNode>().first()
        val snapshot = graph.nodes.filterIsInstance<GraphNode.SnapshotNode>().first()
        val manifest = graph.nodes.filterIsInstance<GraphNode.ManifestNode>().first()
        val file = graph.nodes.filterIsInstance<GraphNode.FileNode>().first()
        val v3 = GraphLayoutService.layoutGraph(
            UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/v3").absolutePath)),
            showRows = true,
        )
        val vector = v3.nodes.filterIsInstance<GraphNode.FileNode>().first { it.isDeletionVector }
        val deletedRow = v3.nodes.filterIsInstance<GraphNode.RowNode>().first { it.isDeletedByVector }
        val liveRow = v3.nodes.filterIsInstance<GraphNode.RowNode>()
            .first { !it.isDeletedByVector && it.filePosition != null }

        renderScene("graph-cards", width = 700, height = 2400) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TableCard(table)
                MetadataCard(metadata)
                SnapshotCard(snapshot)
                ManifestCard(manifest)
                // The pruned state is drawn beside the ordinary one on purpose. It fades the card
                // and lengthens its title line, which is exactly the pair of changes that costs a
                // card its last line — and the two only read as distinguishable side by side.
                ManifestCard(manifest, isPruned = true)
                FileCard(file)
                // The same pair for a file, since file-level pruning fades these too. A file card
                // is half a manifest card's height and carries three lines rather than two, so
                // "the fade is still legible and the extra word still fits" is a separate
                // question from the one the manifest pair answers.
                FileCard(file, isPruned = true)
                // A v3 deletion vector, which declares `content = 1` exactly as the positional
                // delete file above it does. Whether the two are distinguishable in the drawing is
                // the whole question, and it is only askable with both of them on screen. Pruned,
                // because `FILE n: DELETE VECTOR — NOT READ` is the longest first line any file
                // card draws and is what the node's 68dp is reserved for.
                FileCard(vector)
                FileCard(vector, isPruned = true)
                RowCard(liveRow)
                // The same row shape, in the file a deletion vector covers. The strike and the
                // word only read as a state next to a row that does not carry them.
                RowCard(deletedRow)
            }
        }
    }

    /** The Paimon set, which shares the card sizing rule and none of the card code above. */
    @Test
    fun `the paimon cards render inside the size their nodes declare`() {
        val tableDir = File(repoRoot, "example/paimon/db.db/test")
        assertTrue(tableDir.isDirectory, "the Paimon fixture should be checked in")
        val graph = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(tableDir.absolutePath)), showRows = false,
        )
        val snapshot = graph.nodes.filterIsInstance<GraphNode.PaimonSnapshotNode>().first()
        val schema = graph.nodes.filterIsInstance<GraphNode.PaimonSchemaNode>().first()
        val manifestList = graph.nodes.filterIsInstance<GraphNode.PaimonManifestListNode>().first()
        val manifest = graph.nodes.filterIsInstance<GraphNode.PaimonManifestNode>().first()
        val file = graph.nodes.filterIsInstance<GraphNode.PaimonDataFileNode>().first()

        renderScene("paimon-cards", width = 700, height = 1000) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(snapshot, schema, manifestList, manifest, file).forEach { PaimonNodeCard(it) }
            }
        }
    }

    /**
     * Renders one selection and asserts the result is not a blank panel.
     *
     * The emptiness check matters more than it looks: a composable that lays out to zero height,
     * or one whose content sits entirely below the viewport, produces a valid PNG of the
     * background colour and would otherwise pass. Counting pixels that differ from the corner
     * colour is a coarse proxy for "something was drawn", but it separates those two cases.
     */
    private fun renderInspector(graph: GraphModel, nodeId: String, name: String, height: Int) =
        renderScene(name, width = 1400, height = height) { InspectorUnderTest(graph, nodeId) }

    /** The same, for a selection of several nodes — the only way to reach the comparison panel. */
    private fun renderInspector(graph: GraphModel, nodeIds: Set<String>, name: String, height: Int) =
        renderScene(name, width = 1400, height = height) {
            NodeDetailsContent(graphModel = graph, selectedNodeIds = nodeIds)
        }

    private fun renderScene(
        name: String,
        width: Int,
        height: Int,
        density: Float = 2f,
        frames: Int = 2,
        content: @Composable () -> Unit,
    ) {
        val png = renderPng(name, width, height, density, frames, content)

        outputDir.mkdirs()
        writeBands(png, name)

        assertTrue(png.size > 5_000, "$name rendered to ${png.size} bytes, which is a blank panel")
    }

    /**
     * Renders until [ready] says the content has settled, then captures.
     *
     * For content whose interesting state arrives from a coroutine rather than from layout. The
     * usual two-frame capture cannot reach it and neither can sixty: an `ImageComposeScene` only
     * advances its own dispatcher when it is rendered, so a tight render loop finishes long before
     * an off-thread read comes back, and the PNG shows the loading line. Polling with a deadline
     * is the fix — the sleep paces the poll, it is not the synchronisation.
     *
     * A timeout fails rather than capturing whatever was on screen, because a PNG of a spinner is
     * exactly what this is here to stop being mistaken for a capture of the result.
     */
    private fun renderUntil(
        name: String,
        width: Int,
        height: Int,
        density: Float = 2f,
        timeoutMs: Long = 20_000,
        ready: () -> Boolean,
        content: @Composable () -> Unit,
    ) {
        val scene = ImageComposeScene(width = width, height = height, density = Density(density)) {
            Themed(content)
        }
        val png = try {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (!ready() && System.nanoTime() < deadline) {
                scene.render()
                Thread.sleep(10)
            }
            assertTrue(ready(), "$name never settled within ${timeoutMs}ms")
            // Two more after it settled, for the same reason every capture renders twice: the
            // frame that delivers the value is not the frame that has laid it out.
            scene.render()
            scene.render().encodeToData()?.bytes
        } finally {
            scene.close()
        }

        outputDir.mkdirs()
        writeBands(assertNotNull(png, "scene produced no image for $name"), name)
    }

    private fun renderPng(
        name: String,
        width: Int,
        height: Int,
        density: Float,
        frames: Int = 2,
        content: @Composable () -> Unit,
    ): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(density)) {
            Themed(content)
        }
        val png = try {
            // Twice. Anything whose visibility is decided by state that layout writes — a
            // scrollbar that appears only once the content is known to overflow — is absent from
            // the first frame, because the recomposition that reads it has not run yet. A
            // single-frame capture would show a panel the running app never draws.
            repeat(frames - 1) { scene.render() }
            scene.render().encodeToData()?.bytes
        } finally {
            scene.close()
        }
        return assertNotNull(png, "scene produced no image for $name")
    }

    /**
     * Writes the render as one file per [BAND_HEIGHT] strip.
     *
     * A tall panel in a single PNG is unreadable once anything scales it to fit, and the point of
     * these files is that the text can be read. Strips keep every band at a size that survives
     * being opened side by side.
     */
    private fun writeBands(png: ByteArray, name: String) {
        val image = ImageIO.read(ByteArrayInputStream(png))
        val bands = (image.height + BAND_HEIGHT - 1) / BAND_HEIGHT
        (0 until bands).forEach { band ->
            val top = band * BAND_HEIGHT
            val strip = image.getSubimage(0, top, image.width, minOf(BAND_HEIGHT, image.height - top))
            ImageIO.write(strip, "png", File(outputDir, "$name-${band + 1}.png"))
        }
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) {
        MaterialTheme(colorScheme = IceLensLightColorScheme) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    content()
                }
            }
        }
    }

    @Composable
    private fun InspectorUnderTest(graph: GraphModel, nodeId: String) {
        NodeDetailsContent(graph, setOf(nodeId))
    }
}
