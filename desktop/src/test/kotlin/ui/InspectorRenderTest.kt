@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Paths
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import model.GraphModel
import model.GraphSearch
import model.GraphNode
import model.ManifestEntryStatus
import model.PaimonUnifiedTableModel
import model.PredicateOp
import model.ScanPredicate
import model.SnapshotRefLabel
import model.UnifiedTableModel
import service.AggregationPolicy
import service.GraphLayoutService

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

        /**
         * Pixels of `primary` a focus ring is worth, above whatever the panel already draws.
         *
         * One dp of stroke at density 2 around a 20dp control is roughly 250 device pixels of it,
         * so this is well under a whole ring and well over the odd antialiased edge.
         */
        const val RING_INK_MINIMUM = 60

        /** Frames run after each Tab of a focus capture, at 16ms each. See [renderFocused]. */
        const val FOCUS_SETTLE_FRAMES = 40

        /** How far from `primary` a pixel may sit and still be counted as the ring. See [primaryInk]. */
        const val RING_INK_TOLERANCE = 40
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

    /**
     * The metadata file's panel, which had no capture at reading width at all.
     *
     * The latest version of `branched`, because it is the only fixture whose refs, snapshot log
     * and metadata log are all non-empty — a metadata.json's panel is mostly lists, and a capture
     * taken where the lists are empty checks the identity table and nothing else.
     */
    /**
     * The same panel where the statistics list is not empty, which is the only place it can be judged.
     *
     * `branched` above is chosen because its refs and logs are non-empty, and by the same rule this
     * one exists because every other fixture carries `"statistics": []`. The section used to print
     * one row per *file* with the whole record as a single cell; what has to be readable now is one
     * row per blob — the column, its distinct count, and the sketch that produced it — which is the
     * question a reader opened this panel with. Four blobs with four different columns and three
     * different counts, so a column of identical values cannot pass for a table.
     */
    @Test
    fun `the metadata inspector renders a table's statistics`() {
        val graph = graphFor("stats")
        val metadata = graph.nodes.filterIsInstance<GraphNode.MetadataNode>()
            .filter { it.data.statistics.isNotEmpty() }
            .maxByOrNull { it.simpleId }
        assertNotNull(metadata, "the stats fixture should carry a metadata version with statistics")
        renderInspector(graph, metadata.id, "metadata-node-statistics", height = 5600)
    }

    @Test
    fun `the metadata inspector renders`() {
        val graph = graphFor("branched")
        val metadata = graph.nodes.filterIsInstance<GraphNode.MetadataNode>().maxByOrNull { it.simpleId }
        assertNotNull(metadata, "the branched fixture should carry metadata versions")
        renderInspector(graph, metadata.id, "metadata-node", height = 6800)
        val folded = SectionCollapseState().apply { setAll(true) }
        renderScene("metadata-node-folded", width = 1400, height = 2000) {
            NodeDetailsContent(graph, setOf(metadata.id), sectionCollapse = folded)
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
        // Taller than the other panels because this one now carries the delete-reach table, and a
        // capture that stops above the rows it was taken for shows nothing.
        renderInspector(graph, withMain.id, "snapshot-node", height = 3600)

        // A commit that takes files out, which the one above does not. "What this commit did"
        // draws a removal in the error colour against additions in body colour, and a verdict
        // column with one kind of row in it cannot be judged for whether the exception is
        // findable — the same rule that put a pruned manifest next to an ordinary one.
        val compaction = graph.nodes.filterIsInstance<GraphNode.SnapshotNode>()
            .firstOrNull { it.change?.removed?.isNotEmpty() == true }
        assertNotNull(compaction, "the merge-on-read fixture should carry a commit that removes files")
        renderInspector(graph, compaction.id, "snapshot-node-compaction", height = 3600)
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

    /**
     * The same relationship from the data file's end, which is where a reader usually stands.
     *
     * `mor`'s compacted file is the one worth rendering: of the three delete files drawn for the
     * table one reaches it and the other two are ruled out for *different* reasons, so the "Why"
     * column has three different sentences in it. A capture where every row said the same thing
     * would show nothing about whether the column can be scanned.
     */
    @Test
    fun `a data file lists the deletes that reach it`() {
        val mor = graphFor("mor")
        val compacted = mor.nodes.filterIsInstance<GraphNode.FileNode>()
            .firstOrNull { node ->
                model.deleteKindOf(node.data) == null &&
                    node.data.filePath.orEmpty().contains("8bb56de0")
            }
        assertNotNull(compacted, "mor should draw the file its compaction wrote")
        renderInspector(mor, compacted.id, "data-file-deletes", height = 2600)
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
     * What focus looks like on the chrome, which no capture had ever shown.
     *
     * `KeyboardReachTest` settled that Tab *arrives* at the tool-window bar and a pane's close
     * button. Arriving and being visible are different claims, and only the second one is a
     * question about pixels: a control that takes focus and draws nothing for it is reachable and
     * unusable, because the reader cannot tell where they are.
     *
     * Two captures, each with one control focused among unfocused peers, because a focus ring with
     * nothing beside it cannot be judged. There are exactly three focusables here — two bar icons
     * and the close — so the first Tab lands on the first icon and the third on the close. A fourth
     * would wrap back to the first, which is how the first version of this test produced two
     * byte-identical captures and would have read as "focus draws nothing" a second time.
     */
    @Test
    fun `focus is visible on the tool-window chrome`() {
        @Composable
        fun chrome() {
            Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                ToolWindowBar(
                    anchor = ToolWindowAnchor.LEFT_TOP,
                    windows = listOf("workspace" to Icons.Default.Folder, "structure" to Icons.Default.AccountTree),
                    activeWindowId = "workspace",
                    onWindowClick = {},
                )
                Box(Modifier.width(360.dp).height(200.dp)) {
                    ToolWindowPane(title = "Workspace", onClose = {}) {
                        Text("the pane's body", fontSize = TypeScale.small)
                    }
                }
            }
        }
        renderFocused("focus-bar", width = 1000, height = 520, tabs = 1) { chrome() }
        renderFocused("focus-pane-close", width = 1000, height = 520, tabs = 3) { chrome() }
    }

    /**
     * The find bar over the canvas, and the halo on what it found.
     *
     * Two captures rather than one, because a halo with nothing beside it cannot be judged: the
     * matching query has to be readable *against* the same graph with nothing matched. The
     * assertion is the halo's own ink, which is amber and appears nowhere else on this surface —
     * the accent already means selection here, and a match drawn in it would make "which one am I
     * standing on" unanswerable.
     *
     * `parted` and a partition value, because that is the query this feature exists for: a string
     * the reader can see in the inspector and cannot find by any label the tree prints.
     */
    /**
     * The form for a location that cannot be browsed to.
     *
     * A native chooser can point at a directory; it cannot point at a bucket, so this form is the
     * only way into object storage and there is nothing else on screen to explain it. Both
     * credential states are captured because they are different forms — the chain hides the key
     * fields entirely — and what the picture is judged on is whether a reader can tell which of
     * the two they are in, and whether the sentence saying the secret is not written to disk is
     * where they will read it rather than under a control they have already passed.
     */
    /**
     * A workspace root that the store refused, at the width the panel actually opens at.
     *
     * The three rows are rendered together because that is the only view in which the judgement
     * can be made: whether the message reads as belonging to the root above it rather than to the
     * root below, and whether a four-line explanation at 10sp still leaves the panel a list of
     * roots rather than a wall of red. A row on its own answers neither.
     *
     * The narrow width is the point. A sidebar is around 250dp, the message wraps, and Material3's
     * body line height would give each of those lines 24dp — which is why the message goes through
     * `CompactText`, and why this is rendered rather than asserted.
     */
    @Test
    fun `a workspace root says why its store could not be read`() {
        renderScene("workspace-unreachable-1", width = 520, height = 420, density = 2f) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)) {
                Column {
                    WorkspaceRootItem(
                        item = model.WorkspaceItem.Warehouse("/data/warehouse", "warehouse", listOf("orders")),
                        isSelected = false, isExpanded = false,
                        onToggleExpand = {}, onSelect = {}, onRemove = {},
                    )
                    WorkspaceRootItem(
                        item = model.WorkspaceItem.Warehouse("s3://warehouse/db", "db", listOf("orders")),
                        isSelected = false, isExpanded = false,
                        unreachable = "Access denied by the store for 's3://warehouse/db'. " +
                            "Check the key for this bucket, and that it is allowed to list it.",
                        onFixCredentials = {},
                        onToggleExpand = {}, onSelect = {}, onRemove = {},
                    )
                    WorkspaceRootItem(
                        item = model.WorkspaceItem.SingleTable("s3://warehouse/db/orders", "orders"),
                        isSelected = false, isExpanded = false,
                        onToggleExpand = {}, onSelect = {}, onRemove = {},
                    )
                }
            }
        }
    }

    @Test
    fun `the remote location form draws both ways of authenticating`() {
        fun capture(name: String, useChain: Boolean, endpoint: String) {
            val png = renderPng(name, width = 1000, height = 1500, density = 2f) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
                    RemoteLocationForm(
                        url = "s3://warehouse/db",
                        onUrlChange = {},
                        problem = null,
                        useChain = useChain,
                        onUseChainChange = {},
                        keyId = if (useChain) "" else "minioadmin",
                        onKeyIdChange = {},
                        secret = if (useChain) "" else "minioadmin",
                        onSecretChange = {},
                        region = "us-east-1",
                        onRegionChange = {},
                        endpoint = endpoint,
                        onEndpointChange = {},
                        useSsl = endpoint.isBlank(),
                        onUseSslChange = {},
                    )
                }
            }
            File(outputDir, "$name.png").writeBytes(png)
        }
        capture("remote-location-chain-1", useChain = true, endpoint = "")
        capture("remote-location-key-1", useChain = false, endpoint = "127.0.0.1:9000")

        // And the state a reader reaches by typing something that is not a location: the message
        // has to replace the hint rather than appear beside it, or the field says two things.
        val png = renderPng("remote-location-invalid-1", width = 1000, height = 1100, density = 2f) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
                RemoteLocationForm(
                    url = "hdfs://namenode:8020/wh",
                    onUrlChange = {},
                    problem = RemoteLocation.validate("hdfs://namenode:8020/wh"),
                    useChain = true, onUseChainChange = {},
                    keyId = "", onKeyIdChange = {}, secret = "", onSecretChange = {},
                    region = "", onRegionChange = {}, endpoint = "", onEndpointChange = {},
                    useSsl = true, onUseSslChange = {},
                )
            }
        }
        File(outputDir, "remote-location-invalid-1.png").writeBytes(png)
    }

    @Test
    fun `the find bar draws on the canvas and haloes what it matched`() {
        val graph = partedGraph()
        val file = graph.nodes.filterIsInstance<GraphNode.FileNode>()
            .first { it.partition?.isUnpartitioned == false }
        val term = file.partition!!.values.first().let { "${it.field.name}=${it.human}" }

        val found = GraphSearch.search(graph, term)
        assertTrue(found.matches.isNotEmpty(), "the fixture should match \"$term\", or this checks nothing")

        fun capture(name: String, query: String, result: model.GraphSearchResult): ByteArray {
            val png = renderPng(name, width = 1800, height = 1000, density = 1f) {
                GraphCanvas(
                    graph = graph,
                    positions = NodePositions(graph),
                    selectedNodeIds = result.matches.take(1).toSet(),
                    isSelectMode = false,
                    zoom = 0.6f,
                    onZoomChange = {},
                    onSelectionChange = {},
                    matchedNodeIds = result.matches.toSet(),
                    searchOverlay = {
                        GraphSearchBar(
                            query = query,
                            result = result,
                            currentNodeId = result.matches.firstOrNull(),
                            onQueryChange = {},
                            onStep = {},
                            onClose = {},
                        )
                    },
                )
            }
            outputDir.mkdirs()
            writeBands(png, name)
            return png
        }

        val halo = MatchHighlightLight.toArgb()
        val matched = inkNear(capture("graph-search-found", term, found), halo)
        val nothing = inkNear(
            capture("graph-search-empty", "zzzz-no-such-thing", model.GraphSearchResult(emptyList(), 0)),
            halo,
        )

        assertTrue(
            matched > nothing + RING_INK_MINIMUM,
            "the ${found.matches.size} matched nodes should be haloed: $matched pixels of the match " +
                "colour against $nothing when nothing matched",
        )

        // The caveat line, which only exists when aggregation folded something away and which no
        // capture would otherwise reach. "No matches" is a claim about the whole table that is
        // only true of the part of it drawn, so this is the sentence that keeps it honest.
        val paged = GraphLayoutService.layoutGraph(
            UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/mor").absolutePath)),
            showRows = false,
            policy = AggregationPolicy(pageSize = 2),
        )
        val pagedResult = GraphSearch.search(paged, "parquet")
        assertTrue(pagedResult.notDrawn > 0, "mor at two siblings a parent must fold something")
        renderScene("graph-search-not-drawn", width = 1800, height = 700, density = 1f) {
            GraphCanvas(
                graph = paged,
                positions = NodePositions(paged),
                selectedNodeIds = emptySet(),
                isSelectMode = false,
                zoom = 0.6f,
                onZoomChange = {},
                onSelectionChange = {},
                matchedNodeIds = pagedResult.matches.toSet(),
                searchOverlay = {
                    GraphSearchBar(
                        query = "parquet",
                        result = pagedResult,
                        currentNodeId = pagedResult.matches.firstOrNull(),
                        onQueryChange = {},
                        onStep = {},
                        onClose = {},
                    )
                },
            )
        }
    }

    /**
     * The graph under each layout the reader can pick, and the two menus that pick and export.
     *
     * Four captures rather than an assertion about pixels, because what these are for is the
     * judgement a number cannot make: whether a layout is *readable* on a real table. The
     * assertions that each algorithm runs and turns the drawing are `GraphLayoutAlgorithmTest`'s.
     * The menus are rendered as items rather than opened, the same reason `GraphOptionsMenuItems`
     * is a composable of its own — a `DropdownMenu` is a popup and a popup is not what an offscreen
     * scene draws reliably.
     */
    @Test
    fun `each layout draws the branched table, and the menus that choose them render`() {
        val model = UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/branched").absolutePath))
        service.GraphLayoutAlgorithm.entries.forEach { algorithm ->
            val graph = GraphLayoutService.layoutGraph(model, showRows = false, algorithm = algorithm)
            renderScene("layout-${algorithm.name.lowercase()}", width = 2000, height = 1200, density = 1f) {
                GraphCanvas(
                    graph = graph,
                    positions = NodePositions(graph),
                    selectedNodeIds = emptySet(),
                    isSelectMode = false,
                    // Fitted to the scene so the whole graph is in the capture rather than a
                    // corner of it — each layout has a different extent, so a fixed zoom would
                    // show four different fractions of four graphs.
                    zoom = minOf(2000.0 / graph.width, 1200.0 / graph.height).toFloat() * 0.92f,
                    onZoomChange = {},
                    onSelectionChange = {},
                )
            }
        }

        renderScene("menu-layout", width = 640, height = 420) {
            Column(Modifier.padding(12.dp)) {
                LayoutMenuItems(service.GraphLayoutAlgorithm.LAYERED_RIGHT) {}
            }
        }
        renderScene("menu-export", width = 640, height = 420) {
            Column(Modifier.padding(12.dp)) { ExportMenuItems {} }
        }
    }

    /**
     * The Paimon manifest panel, and the replay trace that is new in it.
     *
     * The checked-in Paimon table is one snapshot with one manifest holding one ADD entry, so
     * rendering it produces a trace where every row says the same thing — which checks nothing, the
     * same reason the scan-pruning capture uses a literal that skips one manifest and reads
     * another. The node here carries a trace with all four effects so the colour, the signs and the
     * column order can be judged against each other: only the three that are *not* plain additions
     * are coloured, and a rewrite has to read as a difference rather than as the new file's whole
     * record count.
     *
     * Constructed rather than replayed, because what is being looked at is the panel. That the
     * trace is arithmetically right is `PaimonReplayTraceTest`'s job, against the real table and
     * against a delta over a base.
     */
    @Test
    fun `the paimon manifest panel renders its replay trace`() {
        fun row(effect: model.PaimonEntryEffect, name: String, wasLive: Boolean, prevRows: Long?, rows: Long, bytes: Long, files: Int) =
            model.PaimonEntryTrace(
                fileKey = name,
                fileName = name,
                kind = if (effect == model.PaimonEntryEffect.REMOVED || effect == model.PaimonEntryEffect.REMOVED_ABSENT) 1 else 0,
                wasLive = wasLive,
                previousRecordCount = prevRows,
                previousSizeBytes = prevRows?.let { it * 10 },
                effect = effect,
                liveFileDelta = files,
                recordDelta = rows,
                byteDelta = bytes,
            )

        val node = GraphNode.PaimonManifestNode(
            id = "pman_1",
            data = model.PaimonManifestFileMeta(
                fileName = "manifest-6f2c1a90-4c1f-4d0e-9a3b-7e5d2c8f0011-0",
                fileSize = 4_216,
                numAddedFiles = 2,
                numDeletedFiles = 2,
                schemaId = 0,
            ),
            simpleId = 1,
            localPath = "/wh/db.db/orders/manifest/manifest-6f2c1a90-4c1f-4d0e-9a3b-7e5d2c8f0011-0",
            replayTrace = model.DeferredRead.of {
                listOf(
                    row(model.PaimonEntryEffect.REMOVED, "data-88d1c0f7-0-0.parquet", true, 12_800, -12_800, -1_048_576, -1),
                    row(model.PaimonEntryEffect.REPLACED, "data-2b7e4a51-0-0.parquet", true, 9_600, 3_200, 262_144, 0),
                    row(model.PaimonEntryEffect.ADDED, "data-5c9f3d22-0-0.parquet", false, null, 20_480, 1_703_936, 1),
                    row(model.PaimonEntryEffect.REMOVED_ABSENT, "data-a04e71bb-0-0.parquet", false, null, 0, 0, 0),
                )
            },
        )
        val graph = GraphModel(
            nodes = listOf(node),
            edges = emptyList(),
            width = 200.0,
            height = 80.0,
            layoutPositions = mapOf(node.id to model.Point(0f, 0f)),
        )
        renderScene("paimon-manifest-node", width = 1400, height = 1400) {
            NodeDetailsContent(graph, setOf(node.id))
        }
        // And the same panel at the width the pane opens at, where the six columns have to
        // survive: the effect is the leading column precisely because it is the answer.
        renderScene("paimon-manifest-node-narrow", width = 620, height = 1400) {
            NodeDetailsContent(graph, setOf(node.id))
        }
    }

    /**
     * A Paimon snapshot's index files, read from the real Flink-written fixture.
     *
     * The snapshot named an index manifest and nothing opened it, so this panel is the first time
     * the contents are on screen at all. What the capture is for is the shape of the empty case
     * against the full one: the fixture has a `HASH` index and no deletion vectors, so the column
     * that would carry the deleted-row link has to read as "there are none" rather than as a gap.
     */
    @Test
    fun `a paimon snapshot lists its index files`() {
        val graph = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/test").absolutePath)),
            showRows = false,
        )
        val snapshot = graph.nodes.filterIsInstance<GraphNode.PaimonSnapshotNode>()
            .firstOrNull { it.indexFiles.isNotEmpty() }
        assertNotNull(snapshot, "the paimon fixture should carry a snapshot with an index file")
        renderInspector(graph, snapshot.id, "paimon-snapshot-index", height = 1800)

        // And the other kind, on the Spark-written table: a deletion-vector index whose
        // "Deleted rows" column is the answer rather than a dash, plus the one line above the
        // table that turns a total the format keeps into the number a scan returns.
        val dvGraph = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/dv").absolutePath)),
            showRows = false,
        )
        val vectored = dvGraph.nodes.filterIsInstance<GraphNode.PaimonSnapshotNode>()
            .firstOrNull { node -> node.indexFiles.any { it.isDeletionVectorIndex } }
        assertNotNull(vectored, "the dv fixture should carry a snapshot with a deletion-vector index")
        renderInspector(dvGraph, vectored.id, "paimon-snapshot-vectors", height = 1800)
    }

    /**
     * Focus on a copy button, which lives in a panel several screens tall.
     *
     * `KeyboardReachTest` settles that Tab arrives; the chrome capture above settles that a ring is
     * drawn. Neither says the ring is drawn anywhere the reader can *see*, and in a scrolling panel
     * that is a third question: a reader holding Tab walks focus off the bottom of the pane within
     * a dozen presses, and a ring painted below the fold is indistinguishable from one that is
     * never painted. It does stay in view — `Modifier.focusable` asks its scroll parent to bring it
     * into view and `verticalScroll` honours that — and this counts the ring's own ink in the
     * captured viewport at three depths rather than trusting the contract.
     *
     * Rows of the panel's own [DetailRow] rather than the whole panel, because the measure is a
     * colour: `primary` is also the colour of the panel's header actions and its links, and their
     * count changes as the content scrolls, so on the assembled panel the baseline would move with
     * the thing being measured. Here the ring is the only `primary` in the scene, which the at-rest
     * capture asserts before any of the rest is believed.
     */
    @Test
    fun `a copy button draws focus, and the panel scrolls to keep it in view`() {
        fun capture(tabs: Int) = renderFocused("focus-copy-$tabs", width = 640, height = 400, tabs = tabs) {
            val scroll = rememberScrollState()
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                repeat(60) { i ->
                    DetailRow("Path", "/wh/default/parted/data/00000-$i-a1b2c3.parquet", copyable = true)
                }
            }
        }

        val atRest = primaryInk(capture(0))
        assertTrue(
            atRest == 0,
            "nothing in an unfocused column of copy rows should be drawn in `primary`, and " +
                "$atRest pixels are — the ring is not the only thing this counts",
        )
        // Well past the roughly nine rows the 200dp of viewport holds, so the last depth is only
        // reachable by the panel having scrolled.
        listOf(1, 12, 30).forEach { tabs ->
            val ink = primaryInk(capture(tabs))
            assertTrue(
                ink > RING_INK_MINIMUM,
                "after $tabs tabs the focus ring should be inside the pane, and the capture holds " +
                    "$ink pixels of `primary` — a ring that scrolled out of view and one that is " +
                    "never drawn look identical from here",
            )
        }
    }

    /**
     * How many pixels of the focus ring's own colour a capture holds.
     *
     * Near it, not equal to it: the ring is one dp of stroke and every pixel along its edge is a
     * blend with whatever it sits on, so an exact match counts a stroke's core and nothing else —
     * which on the first run came to zero on a capture that plainly shows the ring. The tolerance
     * is far narrower than the distance to the only other blue in these rows, the label's
     * `onSurfaceVariant` slate, which sits about 106 away.
     */
    private fun primaryInk(png: ByteArray): Int = inkNear(png, IceLensLightColorScheme.primary.toArgb())

    private fun inkNear(png: ByteArray, ring: Int): Int {
        val image = ImageIO.read(ByteArrayInputStream(png))
        val (rr, rg, rb) = Triple((ring shr 16) and 0xFF, (ring shr 8) and 0xFF, ring and 0xFF)
        var count = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val pixel = image.getRGB(x, y)
                val dr = ((pixel shr 16) and 0xFF) - rr
                val dg = ((pixel shr 8) and 0xFF) - rg
                val db = (pixel and 0xFF) - rb
                if (dr * dr + dg * dg + db * db <= RING_INK_TOLERANCE * RING_INK_TOLERANCE) count++
            }
        }
        return count
    }

    /**
     * The choices the badge offers, which until now were drawn for nobody.
     *
     * The badge itself was captured in five states and its menu in none of them, because the menu
     * is a popup and a popup is not what an offscreen scene renders reliably — so the items are a
     * composable of their own and this renders them directly (see `GraphOptionsMenuItems`).
     *
     * Three states, because the two disabled rules pull in opposite directions and a capture where
     * every item is live checks neither. `badge-menu-partial` is the ordinary one: a check on the
     * current page size, "Draw all" live because there is more to draw, "Collapse every group" dead
     * because nothing is open. `badge-menu-paging-off` is the state where the paging item's own
     * wording changes and it carries the check. `badge-menu-collapsible` is the inverse of the
     * first — the graph is whole so "Draw all" is dead, and a group is open so the collapse is
     * live — and its page size is one the list does not offer, so the check sits on `Other`. What
     * the picture is for: whether a dead item reads as dead, and whether the check column keeps
     * the labels on one x.
     */
    @Test
    fun `the badge's menu renders the states its items change with`() {
        // Side by side rather than stacked: the three differ only in which items are dead and
        // which carries the check, and that is read across, not down.
        renderScene("badge-menu", width = 2000, height = 1120) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                MenuUnderTest("Nothing expanded, more to draw") {
                    GraphOptionsMenuItems(
                        total = 6_180, pageSize = 24,
                        pageSizeChoices = AppState.GRAPH_PAGE_SIZE_CHOICES,
                        hiddenByAggregation = 5_749,
                        hasExpandedGroups = false, drawEverything = false,
                        onPageSizeChange = {}, onCustomPageSize = {},
                        onDrawEverythingChange = {}, onCollapseAllGroups = {},
                    )
                }
                MenuUnderTest("Paging off") {
                    GraphOptionsMenuItems(
                        total = 6_180, pageSize = 8,
                        pageSizeChoices = AppState.GRAPH_PAGE_SIZE_CHOICES,
                        hiddenByAggregation = 0,
                        hasExpandedGroups = true, drawEverything = true,
                        onPageSizeChange = {}, onCustomPageSize = {},
                        onDrawEverythingChange = {}, onCollapseAllGroups = {},
                    )
                }
                MenuUnderTest("Whole graph, a group expanded, a typed size") {
                    GraphOptionsMenuItems(
                        total = 6_180, pageSize = 37,
                        pageSizeChoices = AppState.GRAPH_PAGE_SIZE_CHOICES,
                        hiddenByAggregation = 0,
                        hasExpandedGroups = true, drawEverything = false,
                        onPageSizeChange = {}, onCustomPageSize = {},
                        onDrawEverythingChange = {}, onCollapseAllGroups = {},
                    )
                }
            }
        }
    }

    /**
     * The typed page size's field, accepted and refused, side by side.
     *
     * A dialog is a window and a capture cannot open one, so the field is what is rendered. The
     * refused state is what the picture is for: whether the bounds line reads as the answer to
     * "then what is allowed", and whether the error colour reaches the label as well as the box.
     */
    @Test
    fun `the page size field renders accepted and refused`() {
        renderScene("page-size-field", width = 1200, height = 260) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                PageSizeField("37", {}, 2..2_000)
                PageSizeField("5000", {}, 2..2_000)
            }
        }
    }

    /**
     * The items in something menu-shaped, so the capture is read the way the menu is.
     *
     * The surface is the test's, not the app's — a `DropdownMenu` supplies its own — so it is kept
     * to a container and a caption and claims nothing about Material's shadow or corner radius.
     */
    @Composable
    private fun MenuUnderTest(caption: String, items: @Composable () -> Unit) {
        // A stated width, because a menu item fills what it is given and a `Row` hands the first
        // child everything before it measures the next one.
        Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(caption, fontSize = TypeScale.small, fontWeight = FontWeight.SemiBold)
            Surface(
                shape = RoundedCornerShape(4.dp),
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
            ) {
                Column(Modifier.padding(vertical = 8.dp)) { items() }
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
            NodeDetailsContent(graph, setOf(table.id), scanFilter = model.ScanFilter.of(predicates))
        }

        val manifest = graph.nodes.filterIsInstance<GraphNode.ManifestNode>().first()
        renderScene("scan-pruning-manifest", width = 1400, height = 1600) {
            NodeDetailsContent(graph, setOf(manifest.id), scanFilter = model.ScanFilter.of(predicates))
        }

        // A pattern, whose reasons are prose nothing else in these tables produces — and the one
        // filter where the same term reaches two partition fields of the same column with different
        // answers: `name` records `alpha … charlie` and `name_trunc` only its first three
        // characters, so what the reason has to name is which field, not which literal.
        renderScene("scan-pruning-like", width = 1400, height = 3200) {
            NodeDetailsContent(
                graph,
                setOf(table.id),
                scanFilter = model.ScanFilter.of(listOf(ScanPredicate("name", PredicateOp.LIKE, "b%"))),
            )
        }
    }

    /**
     * The clause editor, in the three states that matter: a filter the rows cannot express, one
     * that does not parse, and the sugar.
     *
     * The form above it can only build a conjunction, so a disjunction is the whole reason this
     * input exists — and the error line is the half worth looking at, because it is what a reader
     * sees while they are still typing. All three are states a keystroke produces, so the text is
     * passed in rather than driven, the same way `sectionCollapse` is. The `IN` capture is a width
     * question rather than a parsing one: the field is single-line and the help line under it now
     * names five keywords, so what it shows is whether either still fits.
     */
    @Test
    fun `the clause editor renders a disjunction and a parse error`() {
        val graph = graphFor("parted")
        val sugar = "name IN ('alpha', 'bravo') AND d BETWEEN 2024-03-05 AND 2024-03-07"
        val editor: @Composable (String) -> Unit = { text ->
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
                Column {
                    ClauseEditor(
                        graph = graph,
                        filter = model.ScanFilter.of(emptyList()),
                        onChange = {},
                        onUseForm = {},
                        initialText = text,
                    )
                }
            }
        }
        listOf(
            "clause-valid" to "d >= 2024-03-05 AND (name = 'alpha' OR name = 'bravo')",
            "clause-error" to "d >= 2024-03-05 AND name = ",
            "clause-in" to sugar,
        ).forEach { (name, text) ->
            renderScene("scan-pruning-$name", width = 900, height = 340) { editor(text) }
        }
        // And at 300dp — the width the panel opens at, draggable down to 200dp (`App.kt`). That is
        // where a clause worth writing stops fitting on one line, so it is the width this control's
        // shape is decided at, the same reason `collapse-pages-narrow` exists.
        renderScene("scan-pruning-clause-narrow", width = 300, height = 460, density = 1f) {
            editor(sugar)
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
            NodeDetailsContent(graph, setOf(table.id), scanFilter = model.ScanFilter.of(predicates))
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
     * Four lines at once, which is the drawing the column assignment was actually written for.
     *
     * One fork is satisfied by putting the second line anywhere else; three forks at three
     * different points, with commits on other lines in between, is where the reservations and the
     * header row have to hold. What to look for: four columns, each with its branch name above it,
     * the lineage edges running diagonally back to the commit each line forked from, and no name
     * printed over a column it does not belong to.
     */
    @Test
    fun `the canvas draws three branches as four columns`() {
        val graph = graphFor("branched3")
        val columns = graph.nodes.filterIsInstance<GraphNode.SnapshotNode>().map { it.x }.distinct()
        assertTrue(columns.size == 4, "main and three branches should occupy four columns, got $columns")
        renderCanvas("graph-canvas-branched3", graph, AggregationPolicy.DEFAULT_PAGE_SIZE, zoom = 0.28f)
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
     * The live row count, which is the sentence this whole pairing exists to make possible.
     *
     * `mor`'s compacted file records six rows and one of them is deleted, so five are live — and
     * the figure the panel above it prints, `record_count` minus the delete files' own record
     * counts, gives three. A capture rather than only an assertion because the two numbers sit
     * inches apart on one screen, and which of them a reader trusts is decided by how each is
     * worded.
     */
    @Test
    fun `a data file counts the rows its deletes remove`() {
        val graph = graphFor("mor")
        val files = graph.nodes.filterIsInstance<GraphNode.FileNode>()
        val compacted = files.firstOrNull { node ->
            model.deleteKindOf(node.data) == null && node.data.filePath.orEmpty().contains("8bb56de0")
        }
        assertNotNull(compacted, "mor should draw the file its compaction wrote")
        val candidates = model.deleteCandidatesFor(compacted, files)

        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("deleted-row-count", width = 1400, height = 200, ready = settled::get) {
            Column(Modifier.padding(16.dp)) {
                DeletedRowCount(compacted, candidates, startRequested = true) { settled.set(true) }
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

    /**
     * [zoom] is a parameter because a scene is a fixed number of pixels: a graph with more columns
     * needs a smaller one to fit, and a capture that cuts off the columns it was taken to show is
     * worse than none.
     */
    private fun renderCanvas(name: String, graph: GraphModel, pageSize: Int, zoom: Float = 0.6f) {
        renderScene(name, width = 2000, height = 1200, density = 1f) {
            GraphCanvas(
                graph = graph,
                positions = NodePositions(graph),
                selectedNodeIds = emptySet(),
                isSelectMode = false,
                zoom = zoom,
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
    /**
     * The hover tooltip, for every node kind, which nothing rendered before this.
     *
     * It shares `DetailTable` with the inspector and is the one caller that sizes itself with
     * `Modifier.width(IntrinsicSize.Max)` — so when `DetailTable` gained a `BoxWithConstraints` to
     * derive its label width, this became the place that could throw. Intrinsic measurement asks a
     * layout how wide it wants to be *without* measuring it, and a layout that reads its own
     * constraints has no answer to give; whether the implementation copes is not a thing to assume.
     *
     * Rendering it is also the check that the tooltip is not a second, drifting copy of the panel:
     * it is the only surface in the app that had no capture at all, so a defect in it was invisible
     * to everything here.
     *
     * Sweeping the kinds off `sealedSubclasses` the way `CardHeightTest` does would be better, but
     * the tooltip needs a real node of each kind and the fixtures do not draw all of them; what is
     * covered is what the fixtures hold, and the count is asserted so a kind going missing fails.
     */
    @Test
    fun `the tooltip renders for every node kind the fixtures draw`() {
        val paimon = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/test").absolutePath)),
            showRows = false,
        )
        val graphs = listOf(graphFor("branched"), graphFor("mor"), graphFor("v3"), paimon)
        val byKind = graphs
            .flatMap { it.nodes }
            .groupBy { it::class }
            .mapValues { (_, nodes) -> nodes.first() }

        assertTrue(
            byKind.size >= 8,
            "the fixtures should draw at least eight node kinds between them; got ${byKind.size}: " +
                byKind.keys.map { it.simpleName },
        )

        byKind.values.forEachIndexed { index, node ->
            val kind = node::class.simpleName ?: "node$index"
            renderScene("tooltip-$kind", width = 640, height = 460, density = 1f) {
                Box(Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp)) {
                    NodeTooltip(node)
                }
            }
        }
    }

    /**
     * Every inspector panel, at the width the pane actually opens at.
     *
     * **The rest of this file renders at 1400dp and the pane opens at 300dp** (`App.kt`,
     * `PREF_RIGHT_PANE_WIDTH`, clamped to a 200dp minimum), so every capture here has been checking
     * a panel 4.7x wider than the one on screen. That is not a small discrepancy: horizontal
     * overflow is invisible at any width where the content happens to fit, and Compose reports it
     * by drawing a control past the container edge rather than by failing — the header row had been
     * doing exactly that, with the title laid out one character per line underneath the buttons.
     *
     * So this sweeps the panel kinds at 300dp. It cannot assert what it finds; what it does is put
     * the narrow drawing on disk next to the wide one, which is the only place this class of defect
     * is visible at all. The heights are generous because everything wraps two to four times taller
     * here — a panel cut off at the bottom of the scene would hide the overflow further down it.
     */
    @Test
    fun `every panel renders at the width the pane opens at`() {
        val branched = graphFor("branched")
        val mor = graphFor("mor")
        val v3 = graphFor("v3")

        fun narrow(name: String, g: GraphModel, node: GraphNode?, height: Int = 2400) {
            assertTrue(node != null, "$name: the fixture should draw this node kind")
            renderScene("narrow-$name", width = 300, height = height, density = 1f) {
                InspectorUnderTest(g, node.id)
            }
        }

        narrow("table", branched, branched.nodes.filterIsInstance<GraphNode.TableNode>().firstOrNull())
        narrow("snapshot", branched, branched.nodes.filterIsInstance<GraphNode.SnapshotNode>().lastOrNull())
        narrow("metadata", branched, branched.nodes.filterIsInstance<GraphNode.MetadataNode>().firstOrNull())
        narrow("manifest", mor, mor.nodes.filterIsInstance<GraphNode.ManifestNode>().firstOrNull(), height = 3200)
        narrow("file", mor, mor.nodes.filterIsInstance<GraphNode.FileNode>().firstOrNull(), height = 3200)
        narrow(
            "delete-vector",
            v3,
            v3.nodes.filterIsInstance<GraphNode.FileNode>()
                .firstOrNull { it.data.content == model.DataFileContent.POSITION_DELETES },
        )
    }

    /**
     * Every table's leading column fits the panel it is drawn in, at the width the pane opens at.
     *
     * This is the half of "the rendered inspector is checked by eye" that a number can state. The
     * reader sees the leftmost columns and nothing else until they scroll, which is why the answer
     * goes first and why `columnWidths` is hand-chosen at each of the twenty-seven call sites — and
     * hand-chosen numbers spread over one file drift. A leading column wider than the panel means
     * the reader scrolls before reading anything at all.
     *
     * The panel's own width is measured rather than assumed: the inspector's padding is decided in
     * several places, so a hard-coded budget would assert against this test's arithmetic instead of
     * against the layout.
     *
     * The other half — that the scrollbar appears exactly when the table overflows — is not
     * asserted here, because `WideTable` shows it on `horizontalState.maxValue > 0` and that is
     * Compose's `horizontalScroll` contract rather than anything this repository decides.
     */
    @Test
    fun `every wide table's leading column fits the pane it opens in`() {
        val branched = graphFor("branched")
        val mor = graphFor("mor")
        val v3 = graphFor("v3")

        val seen = mutableListOf<Pair<String, WideTableMetrics>>()
        fun sweep(name: String, g: GraphModel, node: GraphNode?, height: Int = 2400) {
            assertNotNull(node, "$name: the fixture should draw this node kind")
            renderPng("fit-$name", width = 300, height = height, density = 1f) {
                CompositionLocalProvider(
                    LocalWideTableProbe provides { m -> synchronized(seen) { seen += name to m } },
                ) {
                    InspectorUnderTest(g, node.id)
                }
            }
        }

        sweep("table", branched, branched.nodes.filterIsInstance<GraphNode.TableNode>().firstOrNull())
        sweep("snapshot", branched, branched.nodes.filterIsInstance<GraphNode.SnapshotNode>().lastOrNull())
        sweep("metadata", branched, branched.nodes.filterIsInstance<GraphNode.MetadataNode>().firstOrNull())
        sweep("manifest", mor, mor.nodes.filterIsInstance<GraphNode.ManifestNode>().firstOrNull(), height = 3200)
        sweep("file", mor, mor.nodes.filterIsInstance<GraphNode.FileNode>().firstOrNull(), height = 3200)
        sweep(
            "delete-vector",
            v3,
            v3.nodes.filterIsInstance<GraphNode.FileNode>()
                .firstOrNull { it.data.content == model.DataFileContent.POSITION_DELETES },
        )

        // 28 at the time of writing — more than the call sites, because several draw once per
        // schema, spec or sort order. The floor is a guard against the sweep silently probing
        // nothing, which is how an assertion over an empty list passes.
        assertTrue(seen.size >= 20, "the six panels should between them draw a good many wide tables, not ${seen.size}")
        val tooWide = seen.filter { (_, m) -> m.columnWidths.first() > m.availableWidth }
        assertTrue(
            tooWide.isEmpty(),
            "a leading column wider than its panel means the reader scrolls before reading anything:\n" +
                tooWide.joinToString("\n") { (panel, m) ->
                    "  $panel: \"${m.headers.firstOrNull()}\" is ${m.columnWidths.first()} in ${m.availableWidth}"
                },
        )
    }

    private fun renderInspector(graph: GraphModel, nodeId: String, name: String, height: Int) =
        renderScene(name, width = 1400, height = height) { InspectorUnderTest(graph, nodeId) }

    /** The same, for a selection of several nodes — the only way to reach the comparison panel. */
    private fun renderInspector(graph: GraphModel, nodeIds: Set<String>, name: String, height: Int) =
        renderScene(name, width = 1400, height = height) {
            NodeDetailsContent(graphModel = graph, selectedNodeIds = nodeIds)
        }

    /**
     * A capture with focus somewhere, which is the only way to see what focus looks like.
     *
     * An offscreen scene has no pointer and nothing takes focus on its own, so a rendered control
     * is always the unfocused one. Keys go in before the last frame — the same skiko constructor
     * `KeyboardReachTest` uses, since the AWT-wrapping one is cast and throws — and the scene holds
     * several controls on purpose, so the focused one is captured beside its unfocused peers. A
     * picture of a focus ring with nothing to compare it against says nothing about whether it
     * reads.
     */
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    private fun renderFocused(
        name: String,
        width: Int,
        height: Int,
        tabs: Int,
        density: Float = 2f,
        content: @Composable () -> Unit,
    ): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(density)) {
            Themed(content)
        }
        val clock = FrameClock(scene)
        val png = try {
            clock.frame()
            repeat(tabs) {
                scene.sendKeyEvent(KeyEvent(Key.Tab, KeyEventType.KeyDown))
                scene.sendKeyEvent(KeyEvent(Key.Tab, KeyEventType.KeyUp))
                // Settled after *each* Tab, not once at the end. In a scrolling panel every Tab
                // starts a bring-into-view animation, and a Tab arriving mid-flight interrupts it
                // with whatever velocity it had; letting each one come to rest makes the next Tab
                // start from a known place, which is what took a twelve-deep capture from
                // differing every run to identical every run.
                //
                // A thirty-deep one still lands on one of two positions a pixel or so apart, so
                // the scroll's resting place is not fully deterministic under a driven clock. That
                // is left as it is: nothing here compares these files byte for byte — they are
                // opened and looked at — and the claim the test makes is the ring's ink being
                // present, which does not move with a pixel of scroll.
                clock.settle(FOCUS_SETTLE_FRAMES)
            }
            clock.image().encodeToData()?.bytes
        } finally {
            scene.close()
        }
        outputDir.mkdirs()
        val bytes = assertNotNull(png, "scene produced no image for $name")
        writeBands(bytes, name)
        return bytes
    }

    /**
     * A clock for a scene, because `ImageComposeScene.render()` renders every animation at zero.
     *
     * Its `nanoTime` parameter **defaults to the constant `0`**, so a hundred no-argument renders
     * are a hundred copies of the first instant. Nothing about that looks wrong: the frame is
     * valid, the layout is settled, and only the animated part of it is missing. What it cost here
     * was a wrong conclusion — two byte-identical captures were read as "Material draws nothing for
     * focus", when what they showed was a state-layer fade that had not been given a single
     * millisecond to run.
     *
     * So a capture that involves focus, an entrance, or a scroll passes an advancing time, and
     * [settle] runs the frames the animation needs to finish before the image is taken.
     */
    private class FrameClock(private val scene: ImageComposeScene, private val stepNanos: Long = 16_000_000L) {
        private var now = 0L

        fun frame() {
            now += stepNanos
            scene.render(now)
        }

        /** Long enough for Material's state-layer fades and a bring-into-view scroll to land. */
        fun settle(frames: Int = 60) = repeat(frames) { frame() }

        fun image() = scene.render(now)
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
        val clock = FrameClock(scene)
        val png = try {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (!ready() && System.nanoTime() < deadline) {
                clock.frame()
                Thread.sleep(10)
            }
            assertTrue(ready(), "$name never settled within ${timeoutMs}ms")
            // Two more after it settled, for the same reason every capture renders twice: the
            // frame that delivers the value is not the frame that has laid it out.
            clock.frame()
            clock.image().encodeToData()?.bytes
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
        val clock = FrameClock(scene)
        val png = try {
            // Twice. Anything whose visibility is decided by state that layout writes — a
            // scrollbar that appears only once the content is known to overflow — is absent from
            // the first frame, because the recomposition that reads it has not run yet. A
            // single-frame capture would show a panel the running app never draws.
            //
            // Through the clock rather than a bare `render()`, which would draw every frame at
            // time zero. No capture here was found to change when the clock started running, so
            // this is not a fix for anything visible — it is so that the next composable to
            // arrive with a fade or an entrance is not photographed before it has begun.
            repeat(frames - 1) { clock.frame() }
            clock.image().encodeToData()?.bytes
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
