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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import model.GraphModel
import model.currentSchemaModel
import model.fieldRows
import model.WorkspaceItem
import model.WorkspaceTableStatus
import model.GraphSearch
import model.DeferredRead
import model.GraphNode
import model.partitionBreakdown
import model.stepComparableSnapshot
import model.publishedWapId
import model.wapId
import model.describe
import model.metadataVersionFromFileName
import model.ManifestEntryStatus
import model.ScanFilter
import model.snapshotAsOf
import model.PaimonUnifiedTableModel
import model.PredicateOp
import model.ScanPredicate
import model.ScanFilterParse
import model.parseScanFilter
import model.deleteKindOf
import model.recordedColumnStats
import model.SnapshotRefLabel
import model.UnifiedTableModel
import service.AggregationPolicy
import service.GraphLayoutService
import service.PaimonRowLookup
import service.IcebergGraphBuilder

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

    /**
     * A file's history, on the two file nodes whose story is the one a reader opens the panel for:
     * on `mor`, a file the compaction took out — removed by the replace and still listed live by
     * the snapshots before it, which is why it is still on disk — and on `dv`, the file a
     * compaction moved to another level, removed and re-added in one commit. Both nodes are the
     * removed entries themselves, so the panel above the section says `DELETED` and the section
     * says by whom.
     */
    @Test
    fun `a file panel tells the file's history`() {
        val mor = graphFor("mor")
        val removed = mor.nodes.filterIsInstance<GraphNode.FileNode>()
            .first { it.entry.status == ManifestEntryStatus.DELETED && it.history.value?.removedBy != null }
        renderInspector(mor, removed.id, "file-node-history", height = 2600)
        // The same shape where the expiry line says no: `sweepb`'s branch keeps the snapshot that
        // lists the removed file live, so an expiry run now leaves it on disk.
        val sweepb = graphFor("sweepb")
        val kept = sweepb.nodes.filterIsInstance<GraphNode.FileNode>()
            .first { it.entry.status == ManifestEntryStatus.DELETED && it.history.value?.removedBy != null }
        renderInspector(sweepb, kept.id, "file-node-history-kept", height = 2600)
        // And a file added by a commit the expiry removed: credited, marked expired, never live.
        val expired = graphFor("expired")
        val credited = expired.nodes.filterIsInstance<GraphNode.FileNode>()
            .first { n -> n.history.value?.snapshots?.any { it.expired } == true }
        renderInspector(expired, credited.id, "file-node-history-expired", height = 2600)
        val dv = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/dv").absolutePath)),
            showRows = false,
        )
        val rewritten = dv.nodes.filterIsInstance<GraphNode.PaimonDataFileNode>()
            .first { n -> n.history.value?.snapshots?.any { it.event == model.FileEvent.REWRITTEN } == true }
        renderInspector(dv, rewritten.id, "paimon-file-node-history", height = 2600)
        // The same table's 1000-row file, the one its vector marks one row of: the Deleted Rows
        // section under the history, decoded from the index file.
        val marked = dv.nodes.filterIsInstance<GraphNode.PaimonDataFileNode>()
            .first { n -> n.vectorRange != null && n.entry.file?.rowCount == 1000L }
        renderInspector(dv, marked.id, "paimon-file-node-vector", height = 2600)
        // And a level-0 file of the same table, which a batch read skips: the LSM Level row says so.
        val unread = dv.nodes.filterIsInstance<GraphNode.PaimonDataFileNode>().first { it.unreadByBatchRead }
        renderInspector(dv, unread.id, "paimon-file-node-level0", height = 1800)
    }

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
     * The Iceberg table a Paimon table's catalog-storage export is, opened from its own
     * directory: its identity rows carry a `Metadata Kept At` line, because the metadata says it
     * sits under `/wh/iceberg/db/pih` and the table it describes is at `/wh/db.db/pih` — the one
     * fact that tells this directory from an ordinary Iceberg table. The head of the panel is
     * enough; the rest is the ordinary table panel.
     */
    @Test
    fun `an Iceberg table whose metadata is kept apart from its location says where`() {
        val export = UnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/iceberg/db/pih").absolutePath))
        val graph = GraphLayoutService.layoutGraph(export, showRows = false)
        val table = graph.nodes.filterIsInstance<GraphNode.TableNode>().single()
        assertEquals("/wh/iceberg/db/pih", table.summary.metadataKeptApartAt)
        renderInspector(graph, table.id, "table-node-metadata-apart", height = 1000)
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
        // `ndv`: the four shapes a sketch takes in one table — an estimate past the nominal
        // entries, an exact count, a single value, an empty one — with the caption that says
        // which figure is a projection, since a column of `exact` would leave it unjudged.
        val ndv = graphFor("ndv")
        val ndvMetadata = assertNotNull(ndv.nodes.filterIsInstance<GraphNode.MetadataNode>().filter { it.data.statistics.isNotEmpty() }.maxByOrNull { it.simpleId })
        renderInspector(ndv, ndvMetadata.id, "metadata-node-statistics-ndv", height = 6600)
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
     * The two maintenance commits, which are the two shapes "what this commit did" had never drawn.
     *
     * The delete rewrite removes three delete files and adds one, so the file table has more
     * removals than additions and the position-delete rows appear beside the file rows; the
     * manifest rewrite changes no file at all, and what it did is the manifest line and the two
     * manifest tallies — a panel that has to read as "this commit did something" with an empty
     * file list under it.
     */
    @Test
    fun `maintenance commits render what they rewrote`() {
        val graph = graphFor("maint")
        val snapshots = graph.nodes.filterIsInstance<GraphNode.SnapshotNode>().sortedBy { it.data.sequenceNumber }
        val deleteRewrite = snapshots.single { it.change?.tallies?.any { t -> t.label == "Position deletes removed" && t.recorded == 3L } == true }
        val manifestRewrite = snapshots.single { it.change?.tallies?.any { t -> t.label == "Manifests written" && t.recorded != null } == true }
        assertTrue(manifestRewrite.change?.files.orEmpty().isEmpty(), "a manifest rewrite changes no file")

        renderInspector(graph, deleteRewrite.id, "snapshot-node-delete-rewrite", height = 3200)
        renderInspector(graph, manifestRewrite.id, "snapshot-node-manifest-rewrite", height = 2600)
    }

    /**
     * A partitioned Paimon table's data file: the partition decoded from the entry beside the
     * directory text Paimon wrote, which for a date is its epoch day rather than the date.
     */
    @Test
    fun `a partitioned paimon file names its partition and the directory it is in`() {
        val graph = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/pt").absolutePath)),
            showRows = false,
        )
        val file = graph.nodes.filterIsInstance<GraphNode.PaimonDataFileNode>()
            .firstOrNull { it.partition?.values?.any { v -> v.value == "north-america" } == true }
        assertNotNull(file, "the pt fixture should carry a file in the north-america partition")
        renderInspector(graph, file.id, "paimon-file-node-partitioned", height = 2200)

        // The manifest whose recorded minimum is a partition none of its entries has: the
        // "Recorded Summary" rows are where a reader would see the per-column rule at work.
        val manifest = graph.nodes.filterIsInstance<GraphNode.PaimonManifestNode>()
            .firstOrNull { it.entries.size == 2 && it.partitionMin?.display == "dt=2024-03-05, region=eu" }
        assertNotNull(manifest, "the pt fixture's third commit should carry the two-partition manifest")
        renderInspector(graph, manifest.id, "paimon-manifest-node-partitioned", height = 2600)
    }

    /**
     * A v1-written manifest and one of its files, after the table's upgrade to v2.
     *
     * Neither records a sequence number and both panels have to say the number is 0 *and* why —
     * "read as 0" beside a number the writer never wrote is a different fact from a recorded 0,
     * and the row that used to print N/A is the one a reader would take for a broken file.
     */
    @Test
    fun `a v1 manifest and its file say their sequence number is the default`() {
        val graph = graphFor("v1")
        val manifest = graph.nodes.filterIsInstance<GraphNode.ManifestNode>().firstOrNull { it.data.sequenceNumber == null }
        assertNotNull(manifest, "the v1 fixture should carry a manifest with no sequence number")
        val file = graph.nodes.filterIsInstance<GraphNode.FileNode>().firstOrNull { it.sequenceDefaulted }
        assertNotNull(file, "and a file under it")
        renderInspector(graph, manifest.id, "manifest-node-v1", height = 1600)
        renderInspector(graph, file.id, "file-node-v1", height = 1600)
    }

    /**
     * A data file written outside its table, found by re-rooting the recorded path. The
     * `Resolved` row is the one to read: it has to say the file sits outside the table by the
     * table's own choice, and where it was looked for, because a reader told "found" about a
     * path the table never mentioned needs to know which rule produced it.
     */
    @Test
    fun `a write-data-path file says it was found beside the table`() {
        val graph = graphFor("extdata")
        val file = graph.nodes.filterIsInstance<GraphNode.FileNode>()
            .firstOrNull { it.pathResolution == model.PathResolution.REBUILT_BESIDE_TABLE }
        assertNotNull(file, "the extdata fixture's files should be re-rooted beside the table")
        renderInspector(graph, file.id, "file-node-external-data", height = 1600)
    }

    /**
     * A data file of a table with `WRITE ORDERED BY`, and the metadata that defines three orders.
     * The file panel has to show two rows, not one: the order the file claims (0, unsorted — what
     * Spark records on every file it writes) and the table's default beside it, as
     * `WRITE ORDERED BY` stated it, because a bare `0` reads as "unordered rows" and is not. The
     * metadata panel's table has to name the column beside the source id, because a reader does
     * not carry field ids in their head.
     */
    @Test
    fun `a file of a sorted table shows its claim beside the table's default`() {
        val graph = graphFor("sorted")
        // The compacted file: nine rows a sort rewrite put in the default order, claiming none.
        val file = graph.nodes.filterIsInstance<GraphNode.FileNode>().firstOrNull { it.data.recordCount == 9L }
        assertNotNull(file, "the sorted fixture's rewrite left one nine-row file")
        assertEquals(0L, file.data.sortOrderId)
        assertEquals("id DESC NULLS LAST, name ASC NULLS FIRST", file.defaultSortOrder?.describe { file.schema?.nameOf(it) })
        renderInspector(graph, file.id, "file-node-sorted", height = 1600)
        val metadata = graph.nodes.filterIsInstance<GraphNode.MetadataNode>().first { it.fileName == "v7.metadata.json" }
        renderInspector(graph, metadata.id, "metadata-node-sorted", height = 3200)
    }

    /**
     * A data file listed by a manifest rewritten after its columns were widened and one dropped.
     * Three things the Column Statistics table has to say at once: `1 (written as int)` for a
     * four-byte bound under a long, the same for a float under a double, and `label (dropped)`
     * with `alpha..bravo` decoded for a field the manifest's schema does not have — where the
     * panel used to print a decode failure and a bare `field 2`.
     */
    @Test
    fun `a file under a rewritten manifest reads its promoted and dropped bounds`() {
        val graph = graphFor("promoted")
        val file = graph.nodes.filterIsInstance<GraphNode.FileNode>()
            .firstOrNull { node -> node.columnStats.any { it.dropped } }
        assertNotNull(file, "the promoted fixture's rewritten manifest lists a file with a dropped column's bound")
        assertEquals(model.IcebergType.IntType, file.columnStats.single { it.fieldId == 1 }.lowerBound?.writtenAs)
        renderInspector(graph, file.id, "file-node-promoted", height = 2400)
    }

    /**
     * v3 row lineage, at the three places it is read by inheritance. The metadata panel's
     * `Next Row ID`; the file panel's `Row IDs` range, which for the update's rewritten file has
     * to say the first id was inherited though its one row carries its own; and that row's panel,
     * where `_row_id` is the column the file wrote and `_last_updated_sequence_number` is the
     * file's sequence number standing in for the null the rewrite wrote.
     */
    @Test
    fun `row lineage reads through the metadata, the file and the row`() {
        val graph = GraphLayoutService.layoutGraph(
            UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/lineage").absolutePath)),
            showRows = true,
        )
        val metadata = graph.nodes.filterIsInstance<GraphNode.MetadataNode>().first { it.fileName == "v6.metadata.json" }
        assertEquals(9L, metadata.data.nextRowId)
        renderInspector(graph, metadata.id, "metadata-node-row-lineage", height = 1400)
        val update = graph.nodes.filterIsInstance<GraphNode.SnapshotNode>().first { it.data.summary["operation"] == "overwrite" }
        assertEquals(3L, update.data.addedRows)
        renderInspector(graph, update.id, "snapshot-node-row-lineage", height = 1400)
        val rewritten = graph.nodes.filterIsInstance<GraphNode.FileNode>()
            .first { it.firstRowId == 6L && it.entry.status == model.ManifestEntryStatus.ADDED }
        renderInspector(graph, rewritten.id, "file-node-row-lineage", height = 1400)
        val row = graph.nodes.filterIsInstance<GraphNode.RowNode>().first { it.id.startsWith("row_${rewritten.id}_") && "id" in it.resolvedData }
        assertEquals(2L, (row.resolvedData[IcebergGraphBuilder.ROW_ID_COLUMN] as Number).toLong())
        renderInspector(graph, row.id, "row-node-row-lineage", height = 1200)
    }

    /**
     * A row as a read returns it, where that differs from the file: `defaults`' first file
     * predates `region` and `score`, so the section lists them from their initial defaults;
     * `evolved`'s first file holds `name`, which the table has dropped, and lacks `note`. The
     * metadata panel's schema table gains the two default columns on `defaults` alone.
     */
    @Test
    fun `a row says what a read returns for it when the schema has moved on`() {
        val defaults = GraphLayoutService.layoutGraph(
            UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/defaults").absolutePath)),
            showRows = true,
        )
        val old = defaults.nodes.filterIsInstance<GraphNode.RowNode>().first { it.resolvedData["id"]?.toString() == "1" }
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("row-node-read-as", width = 1400, height = 700, ready = settled::get) {
            Column(Modifier.padding(16.dp)) { ReadAsSection(old) { settled.set(true) } }
        }
        val evolved = GraphLayoutService.layoutGraph(
            UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/evolved").absolutePath)),
            showRows = true,
        )
        val renamed = evolved.nodes.filterIsInstance<GraphNode.RowNode>().first { it.resolvedData["name"]?.toString() == "alpha" }
        val evolvedSettled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("row-node-read-as-dropped", width = 1400, height = 700, ready = evolvedSettled::get) {
            Column(Modifier.padding(16.dp)) { ReadAsSection(renamed) { evolvedSettled.set(true) } }
        }

        // `deep`'s first row was written when `addr.town` was `city` and before `addr.country`:
        // the struct is rebuilt by id, so the cell reads `town` and a NULL `country`.
        val deep = GraphLayoutService.layoutGraph(UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/deep").absolutePath)), showRows = true)
        val nested = deep.nodes.filterIsInstance<GraphNode.RowNode>().first { it.resolvedData["id"]?.toString() == "1" }
        val deepSettled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("row-node-read-as-nested", width = 1400, height = 700, ready = deepSettled::get) {
            Column(Modifier.padding(16.dp)) { ReadAsSection(nested) { deepSettled.set(true) } }
        }
        val metadata = defaults.nodes.filterIsInstance<GraphNode.MetadataNode>().first { it.fileName == "v6.metadata.json" }
        renderInspector(defaults, metadata.id, "metadata-node-defaults", height = 5200)
        // The schema table with nested fields as rows of their own: `deep`'s newest schema,
        // where `addr.town` (field 6, renamed from `city`) and `addr.country` (11, added since)
        // sit under `addr` with the ids a file's columns are placed by.
        val deepModel = UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/deep").absolutePath))
        val deepSchema = assertNotNull(deepModel.metadatas.last().metadata.currentSchemaModel())
        renderScene("schema-fields-nested", width = 1400, height = 900) {
            Column(Modifier.padding(16.dp)) { SchemaFieldsTable(deepSchema.fieldRows(), identifierIds = deepSchema.identifierFieldIds) }
        }

        // The Paimon twin: `pse`'s first file holds `v`, read as `label`, and no `w`; the
        // latest schema's panel carries the write default its SET DEFAULT stored.
        val pse = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/pse").absolutePath)),
            showRows = true,
        )
        val paimonRow = pse.nodes.filterIsInstance<GraphNode.RowNode>().first { it.resolvedData["v"]?.toString() == "a" }
        val pseSettled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("paimon-row-node-read-as", width = 1400, height = 700, ready = pseSettled::get) {
            Column(Modifier.padding(16.dp)) { ReadAsSection(paimonRow) { pseSettled.set(true) } }
        }
        val pseSchema = pse.nodes.filterIsInstance<GraphNode.PaimonSchemaNode>().first { it.data.id == 3 }
        renderInspector(pse, pseSchema.id, "paimon-schema-node-defaults", height = 1200)
    }

    /**
     * The schema evolution as one table of changes: `evolved`'s two promotions, rename, add and
     * drop, each on its step with the insert first written under it — a drop in the error colour
     * and a promotion not, since the column marks what can lose a reader data. The Paimon twin is
     * on the schema node that made the change: `pse`'s schema 2, the rename.
     */
    @Test
    fun `a table lists its schema changes by field id, and a Paimon schema node its own`() {
        val evolved = graphFor("evolved")
        val table = evolved.nodes.filterIsInstance<GraphNode.TableNode>().single()
        renderScene("schema-evolution", width = 1400, height = 560) {
            Column(Modifier.padding(16.dp)) { SchemaEvolutionSection(table.schemaEvolution) }
        }
        val pse = GraphLayoutService.layoutGraph(PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/pse").absolutePath)), showRows = false)
        val renamed = pse.nodes.filterIsInstance<GraphNode.PaimonSchemaNode>().first { it.data.id == 2 }
        renderInspector(pse, renamed.id, "paimon-schema-node-changes", height = 900)
        // A nested type on the field table, and a rename inside a list's element as its own row.
        val pne = GraphLayoutService.layoutGraph(PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/pne").absolutePath)), showRows = false)
        val nested = pne.nodes.filterIsInstance<GraphNode.PaimonSchemaNode>().first { it.data.id == 3 }
        renderInspector(pne, nested.id, "paimon-schema-node-nested", height = 1300)
    }

    /**
     * A partition statistics file, read: the record's table gains the size on disk beside the
     * size it claims, and below it one row per partition with the figures a planner reads. The
     * delete columns have to be judged against a row that has one, which `eu` is.
     */
    @Test
    fun `a partition statistics file is read below its record`() {
        val graph = graphFor("pstats")
        val metadata = graph.nodes.filterIsInstance<GraphNode.MetadataNode>().single { it.data.partitionStatistics.isNotEmpty() }
        assertEquals(3, metadata.partitionStatistics.value?.values?.single()?.rows?.size)
        renderInspector(graph, metadata.id, "metadata-node-partition-stats", height = 7200)
    }

    /**
     * Write-audit-publish. What to look for on the canvas: the staged commit in a column of its
     * own with no name over it, its lineage edge back to the commit it forked from, and a second
     * dashed edge from it to the published commit at the bottom of `main` — the relationship the
     * parent edge does not carry. In the panels: the staged commit's `Refs` row saying why it is
     * on none, and the published commit's `Published From` naming the snapshot and the audit id.
     */
    @Test
    fun `a write-audit-publish flow draws the source of a published commit`() {
        val graph = graphFor("wap")
        val staged = graph.nodes.filterIsInstance<GraphNode.SnapshotNode>().single { it.data.wapId == "audit-1" }
        val published = graph.nodes.filterIsInstance<GraphNode.SnapshotNode>().single { it.data.publishedWapId == "audit-1" }
        assertEquals(1, graph.edges.count { it.id.startsWith("e_source_") })
        renderCanvas("graph-canvas-wap", graph, pageSize = AggregationPolicy.DEFAULT_PAGE_SIZE)
        renderInspector(graph, staged.id, "snapshot-node-wap-staged", height = 1400)
        renderInspector(graph, published.id, "snapshot-node-wap-published", height = 1400)
    }

    /**
     * The partition breakdown on a snapshot, for a partitioned table of both formats: the table
     * has to read largest-first with the exception coloured only where a tuple did not decode.
     */
    @Test
    fun `a snapshot lists its live files by partition`() {
        val iceberg = GraphLayoutService.layoutGraph(
            UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/pstats").absolutePath)),
            showRows = false,
        )
        val current = iceberg.nodes.filterIsInstance<GraphNode.SnapshotNode>().first { it.refs.any { r -> r.name == "main" } }
        assertEquals(3, current.liveFiles!!.partitionBreakdown().size)
        renderInspector(iceberg, current.id, "snapshot-node-partitions", height = 3800)
        val paimon = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/pt").absolutePath)),
            showRows = false,
        )
        val last = paimon.nodes.filterIsInstance<GraphNode.PaimonSnapshotNode>().maxBy { it.data.id ?: 0L }
        renderInspector(paimon, last.id, "paimon-snapshot-node-partitions", height = 2600)
    }

    /**
     * `mor` before its compaction: five small files, two with a delete over half their rows, so
     * the group is rewritten by both rules and the section has a verdict to colour. The `sorted`
     * table before its rewrite is the other case — three candidates, left alone under the
     * defaults — so the two verdicts are seen side by side across two captures.
     */
    @Test
    fun `a snapshot says what rewrite_data_files would rewrite`() {
        val mor = graphFor("mor")
        val overwrite = mor.nodes.filterIsInstance<GraphNode.SnapshotNode>().first { it.data.summary["operation"] == "overwrite" }
        renderInspector(mor, overwrite.id, "snapshot-node-rewrite", height = 4200)
        val sorted = graphFor("sorted")
        val third = sorted.nodes.filterIsInstance<GraphNode.SnapshotNode>()
            .filter { it.data.summary["operation"] == "append" }.maxBy { it.data.sequenceNumber ?: 0L }
        renderInspector(sorted, third.id, "snapshot-node-rewrite-left-alone", height = 3400)
    }

    /**
     * `pe` is `pea` before its expiry, complete: the `retain_min = 1, older_than = now` column
     * removes snapshots 1..6, and the file plan frees the compaction's two unprotected removals,
     * five changelog files and the manifests — while the tag on 3 holds three removed files on
     * disk, which is the line the section exists to print.
     */
    @Test
    fun `a paimon table says what its expiry would free and what a tag holds`() {
        val pe = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/pe").absolutePath)),
            showRows = false,
        )
        val table = pe.nodes.filterIsInstance<GraphNode.TableNode>().single()
        renderInspector(pe, table.id, "paimon-table-node-expiry-files", height = 5200)
    }

    /**
     * The table panel's maintenance lines, on the two tables where the most would act: `mor`
     * before nothing — its current snapshot's rewrite plan is left alone under the defaults, the
     * next append merges nothing, and the expiry would remove five of six — and `pc`, whose
     * latest snapshot sits at three level-0 files under the trigger with a bare expiry keeping
     * everything. Two captures so a coloured verdict and a plain one are both seen in the column.
     */
    @Test
    fun `the table panel sums the maintenance procedures to a line each`() {
        val mor = graphFor("mor")
        val table = mor.nodes.filterIsInstance<GraphNode.TableNode>().single()
        renderInspector(mor, table.id, "table-node-maintenance", height = 1800)
        val pc = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/pc").absolutePath)),
            showRows = false,
        )
        val pcTable = pc.nodes.filterIsInstance<GraphNode.TableNode>().single()
        renderInspector(pc, pcTable.id, "paimon-table-node-maintenance", height = 1800)
    }

    /**
     * `sweep` is `swept` before its expiry, complete: under `older_than = now` the plan removes the
     * four older snapshots and frees the two data files the incremental cleanup frees — one
     * removed on the live line, one added off it — beside the manifests and lists, so the
     * section has both a coloured row and a plain one. `sweepb` is the reachable case, where the
     * same removal frees no data file.
     */
    @Test
    fun `a metadata file says what its expiry would free`() {
        val sweep = graphFor("sweep")
        val latest = sweep.nodes.filterIsInstance<GraphNode.MetadataNode>().maxBy { metadataVersionFromFileName(it.fileName) ?: -1 }
        renderInspector(sweep, latest.id, "metadata-node-expiry-files", height = 5400)
        val sweepb = graphFor("sweepb")
        val latestB = sweepb.nodes.filterIsInstance<GraphNode.MetadataNode>().maxBy { metadataVersionFromFileName(it.fileName) ?: -1 }
        renderInspector(sweepb, latestB.id, "metadata-node-expiry-files-reachable", height = 5400)
    }

    /**
     * `mergespec` at its second commit: the table's spec has changed since, so the next append's
     * manifest goes under spec 1 and the two spec-0 manifests merge under the default count of a
     * hundred while the bin holding the new manifest is kept — two verdicts in one table.
     * `mergedel` at its second commit is the other capture, where `min-count-to-merge` is two and
     * both an append and a merge-on-read delete would merge. `merged` is not captured: its last
     * commit turned merging off, and the section reads the table's current options.
     */
    @Test
    fun `a snapshot says what the next commit's manifest merge would do`() {
        val mergespec = graphFor("mergespec")
        val second = mergespec.nodes.filterIsInstance<GraphNode.SnapshotNode>().first { it.data.sequenceNumber == 2L }
        renderInspector(mergespec, second.id, "snapshot-node-manifest-merge", height = 4600)
        val mergedel = graphFor("mergedel")
        val delete = mergedel.nodes.filterIsInstance<GraphNode.SnapshotNode>().first { it.data.sequenceNumber == 2L }
        renderInspector(mergedel, delete.id, "snapshot-node-manifest-merge-deletes", height = 5200)
    }

    /**
     * `pc` at snapshot 5 is the one tree in the fixtures a batch writer compacts: five level-0
     * files, size amplification, into level 5 — and the snapshot after it is the COMPACT that
     * proves it. Rendered beside the append table `ao`, whose verdict is about `sys.compact`.
     */
    @Test
    fun `a snapshot says what the next flush would compact`() {
        val pc = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/pc").absolutePath)),
            showRows = false,
        )
        val fifth = pc.nodes.filterIsInstance<GraphNode.PaimonSnapshotNode>().first { it.data.id == 5L }
        assertEquals("L0×5", fifth.bucketLsms!!.single().describeLevels())
        renderInspector(pc, fifth.id, "paimon-snapshot-node-compaction", height = 2600)
        val ao = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/ao").absolutePath)),
            showRows = false,
        )
        val aoLast = ao.nodes.filterIsInstance<GraphNode.PaimonSnapshotNode>().maxBy { it.data.id ?: 0L }
        renderInspector(ao, aoLast.id, "paimon-snapshot-node-compaction-append", height = 2600)
    }

    /** The refs table with retention set on two of three refs, as ages rather than milliseconds. */
    @Test
    fun `refs with retention render their ages`() {
        val graph = GraphLayoutService.layoutGraph(
            UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/retained").absolutePath)),
            showRows = false,
        )
        val newest = graph.nodes.filterIsInstance<GraphNode.MetadataNode>().maxBy { it.data.snapshotLog.size }
        assertEquals(3, newest.data.refs.size)
        renderInspector(graph, newest.id, "metadata-node-retained", height = 4400)
    }

    /**
     * A rollback is drawn from the log: the metadata panel's snapshot log has to mark the entry
     * that set main back and say what it left behind, the abandoned commit's panel has to name the
     * rollback, and the canvas has to give it a column beside the trunk.
     */
    @Test
    fun `a rolled-back table shows the rollback in the log and on the commit it abandoned`() {
        val graph = GraphLayoutService.layoutGraph(
            UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/rolled").absolutePath)),
            showRows = false,
        )
        val abandoned = graph.nodes.filterIsInstance<GraphNode.SnapshotNode>().single { it.leftBehindAt != null }
        val newest = graph.nodes.filterIsInstance<GraphNode.MetadataNode>().maxBy { it.data.snapshotLog.size }
        renderCanvas("graph-canvas-rolled", graph, pageSize = AggregationPolicy.DEFAULT_PAGE_SIZE)
        renderInspector(graph, abandoned.id, "snapshot-node-rolled-back", height = 1400)
        renderInspector(graph, newest.id, "metadata-node-rolled", height = 4400)

        // The time-travel section at the two moments a rolled-back table answers unexpectedly:
        // one tick before the reset, which lands on the abandoned commit, and at the reset, which
        // lands on its target through the reset's own entry. A typed time is a state a capture
        // never reaches, so the section is seeded with each.
        val meta = newest.data
        val reset = meta.snapshotLog[3]
        for ((suffix, at) in listOf("abandoned" to reset.timestampMs!! - 1, "reset" to reset.timestampMs!!)) {
            renderScene("time-travel-$suffix", width = 1400, height = 520) {
                Column(Modifier.padding(16.dp)) {
                    TimeTravelSection(
                        intro = "Which snapshot TIMESTAMP AS OF lands on.",
                        initialMs = at,
                        resolve = { meta.snapshotAsOf(it) },
                        operationOf = { id -> meta.snapshots.firstOrNull { it.snapshotId == id }?.summary?.get("operation") },
                        currentSnapshotId = meta.currentSnapshotId,
                    )
                }
            }
        }
    }

    /**
     * A data-evolution patch file and the file it patches: the canvas has to draw the `e_patch_*`
     * edge dashed between two files of one layer, and each file's panel has to name the other —
     * "holds b only, stitched with …" on one side and "patched by … (b)" on the other.
     */
    @Test
    fun `a Paimon patch file is drawn against the file it patches`() {
        val graph = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/de").absolutePath)),
            showRows = true,
        )
        val edge = graph.edges.single { it.id.startsWith("e_patch_") }
        renderCanvas("graph-canvas-data-evolution", graph, pageSize = AggregationPolicy.DEFAULT_PAGE_SIZE)
        renderInspector(graph, edge.fromId, "paimon-file-node-patch", height = 2200)
        renderInspector(graph, edge.toId, "paimon-file-node-patched", height = 2200)
        val merge = graph.nodes.filterIsInstance<GraphNode.PaimonSnapshotNode>().first { it.data.id == 2L }
        renderInspector(graph, merge.id, "paimon-snapshot-node-data-evolution", height = 2600)
    }

    /**
     * A Paimon `-D` row beside a `+I` one: the card has to fade and strike the retraction the
     * way an Iceberg row under a deletion vector is drawn, with `-D` in its title line, and the
     * panel's `Row Kind` row has to say the word rather than the byte.
     */
    @Test
    fun `a Paimon delete row is drawn as a retraction`() {
        val graph = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/dv").absolutePath)),
            showRows = true,
        )
        val rows = graph.nodes.filterIsInstance<GraphNode.RowNode>()
        val deleteRow = rows.first { it.paimonRowKind == model.PaimonRowKind.DELETE }
        val insertRow = rows.first { it.paimonRowKind == model.PaimonRowKind.INSERT }
        // The update pair comes from lk, whose lookup producer writes -U / +U into its changelog.
        val lkRows = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/lk").absolutePath)),
            showRows = true,
        ).nodes.filterIsInstance<GraphNode.RowNode>()
        val before = lkRows.first { it.paimonRowKind == model.PaimonRowKind.UPDATE_BEFORE }
        val after = lkRows.first { it.paimonRowKind == model.PaimonRowKind.UPDATE_AFTER }
        renderScene("paimon-row-cards-kinds", width = 700, height = 900) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                RowCard(insertRow)
                RowCard(deleteRow)
                RowCard(before)
                RowCard(after)
            }
        }
        renderInspector(graph, deleteRow.id, "paimon-row-node-delete", height = 1200)
    }

    /**
     * A snapshot expiry dropped, drawn as what it is rather than as a read error.
     *
     * The card says so in its eyebrow and the panel says so in an identity row, above a summary
     * that is the writer's and below which there is nothing to read. Three expired cards next to
     * the retained one, because the word has to be findable against cards that do not carry it.
     */
    @Test
    fun `an expired snapshot renders as a state, not an error`() {
        val graph = graphFor("expired")
        val snapshots = graph.nodes.filterIsInstance<GraphNode.SnapshotNode>().sortedBy { it.data.sequenceNumber }
        assertEquals(listOf(true, true, true, false), snapshots.map { it.expired })
        assertTrue(graph.nodes.none { it is GraphNode.ErrorNode }, "expiry is not an error")

        renderScene("snapshot-cards-expired", width = 700, height = 920) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                snapshots.forEach { SnapshotCard(it) }
            }
        }
        renderInspector(graph, snapshots.first().id, "snapshot-node-expired", height = 1400)
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
    /**
     * The workspace list with a restored warehouse before its first sweep lands, beside one the
     * sweep has covered and one the store refused — the three states a root can be in, so the
     * "scanning" line is judged against the two it must not be mistaken for: a warehouse with no
     * tables, and one that could not be listed.
     */
    @Test
    fun `a restored warehouse says it is scanning until its first sweep lands`() {
        // Real directories: a local root the panel cannot stat is drawn as deleted, which is a
        // fourth state and not the one under test.
        val wh = kotlin.io.path.createTempDirectory("ws-render").toFile()
        try {
            val swept = WorkspaceItem.Warehouse(File(wh, "swept").apply { mkdirs() }.path, "swept", listOf("orders", "customers"))
            val unswept = WorkspaceItem.Warehouse(File(wh, "unswept").apply { mkdirs() }.path, "unswept", emptyList())
            val refused = WorkspaceItem.Warehouse("s3://bucket/wh", "wh", emptyList())
            renderScene("workspace-scanning", width = 600, height = 900) {
                WorkspacePanel(
                    workspaceItems = listOf(swept, unswept, refused),
                    warehouseTableStatuses = mapOf(swept.path to swept.tables.associateWith { WorkspaceTableStatus.EXISTING }),
                    singleTableStatuses = emptyMap(),
                    unreachableRoots = mapOf(refused.path to "HTTP 403 (Forbidden): the key was refused"),
                    unsweptRoots = setOf(unswept.path),
                    selectedTablePath = null,
                    expandedPaths = setOf(swept.path, unswept.path),
                    onExpandedPathsChange = {},
                    searchQuery = "",
                    onSearchQueryChange = {},
                    lastBrowseDirectory = null,
                    onLastBrowseDirectoryChange = {},
                    onTableSelect = {},
                    onAddRoot = {},
                    onAddRemote = {},
                    onRemoveRoot = {},
                    onMoveRoot = { _, _ -> },
                )
            }
        } finally {
            wh.deleteRecursively()
        }
    }

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

        // And the statistics an ANALYZE commit wrote, on the one snapshot of the changelog table
        // that names them: the merged row count leads, and the column table has a string column
        // with no bounds beside an int column with both.
        val clGraph = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/cl").absolutePath)),
            showRows = false,
        )
        val analyzed = clGraph.nodes.filterIsInstance<GraphNode.PaimonSnapshotNode>()
            .firstOrNull { it.statistics != null }
        assertNotNull(analyzed, "the cl fixture should carry an ANALYZE snapshot with statistics")
        renderInspector(clGraph, analyzed.id, "paimon-snapshot-analyze", height = 2000)

        // And a snapshot only a tag retains: the identity says so, and the read error for the
        // changelog list the tag names and expiry deleted is on the same panel.
        val tgGraph = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/tg").absolutePath)),
            showRows = false,
        )
        val tagged = tgGraph.nodes.filterIsInstance<GraphNode.PaimonSnapshotNode>().firstOrNull { it.retainedByTagOnly }
        assertNotNull(tagged, "the tg fixture should carry a snapshot retained by its tag only")
        // Tall enough for "Recorded Records" below the identity: the one NO in the corpus — the
        // tag's changelog count against a changelog list expiry deleted — is what to look for.
        renderInspector(tgGraph, tagged.id, "paimon-snapshot-tag", height = 2400)
        renderScene("paimon-cards-tag", width = 700, height = 460) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                tgGraph.nodes.filterIsInstance<GraphNode.PaimonSnapshotNode>().sortedBy { it.data.id }.forEach { PaimonNodeCard(it) }
            }
        }
    }

    /**
     * A Paimon branch: the branch's commit, the table's branch list, and the canvas with the
     * branch in a column of its own.
     *
     * What to look for on the snapshot panel: the `Branch` row saying `dev` beside a snapshot id
     * main also has. On the table panel: `Branches (2)`, one written to and one created empty,
     * with the empty one saying so rather than showing a blank. On the canvas: `main` over the
     * left column of three commits, `dev` over the right column of two, the branch's first commit
     * hanging its manifest list beside main's first — it is the same manifest — and the branch
     * chip on the two right-hand cards.
     */
    @Test
    fun `a paimon branch is a column, a chip and a row`() {
        val brGraph = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/br").absolutePath)),
            showRows = false,
        )
        val onBranch = brGraph.nodes.filterIsInstance<GraphNode.PaimonSnapshotNode>().firstOrNull { it.branch == "dev" && it.data.id == 2L }
        assertNotNull(onBranch, "the br fixture should carry dev's second snapshot")
        renderInspector(brGraph, onBranch.id, "paimon-snapshot-branch", height = 1400)
        val table = brGraph.nodes.filterIsInstance<GraphNode.TableNode>().single()
        renderInspector(brGraph, table.id, "paimon-table-node-branches", height = 2200)
        // And the consumer, on the table that has one: `Consumers (1)` under `Branches (0)`, the
        // reader's next snapshot, and the one word saying it is still there.
        val csGraph = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/cs").absolutePath)),
            showRows = false,
        )
        val csTable = csGraph.nodes.filterIsInstance<GraphNode.TableNode>().single()
        renderInspector(csGraph, csTable.id, "paimon-table-node-consumer", height = 2200)
        // And the expiry plan, on the table whose survivors are known: `px` has six snapshots, a
        // tag on 2 and a consumer at 4, and `pxa` is what the same call left of it. The clock is
        // pinned within the hour of the last commit, so the bare call's column keeps everything
        // and the older_than column removes 1..3 — the two verdicts a reader has to be able to
        // tell apart at a glance.
        val pxGraph = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/px").absolutePath)),
            showRows = false,
        )
        val pxTable = pxGraph.nodes.filterIsInstance<GraphNode.TableNode>().single()
        renderInspector(pxGraph, pxTable.id, "paimon-table-node-expiry", height = 2600)
        assertTrue(
            brGraph.nodes.filterIsInstance<GraphNode.PaimonSnapshotNode>().map { it.x }.distinct().size == 2,
            "main and dev should occupy two columns",
        )
        renderCanvas("graph-canvas-paimon-branched", brGraph, pageSize = AggregationPolicy.DEFAULT_PAGE_SIZE)
    }

    /**
     * A Paimon data file with a file index, both ways: the `File Index` row has to say where the
     * index is — beside the file, named, or in the entry with its size — because a scan consults
     * it before opening the file and an orphan scan that misses the one beside the file deletes it.
     */
    @Test
    fun `a paimon file says where its file index lives`() {
        val fiGraph = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/fi").absolutePath)),
            showRows = false,
        )
        val files = fiGraph.nodes.filterIsInstance<GraphNode.PaimonDataFileNode>()
        val beside = files.firstOrNull { !it.entry.file?.extraFiles.isNullOrEmpty() }
        val embedded = files.firstOrNull { it.entry.file?.embeddedFileIndex != null }
        assertNotNull(beside, "the fi fixture's first file should name its .index file")
        assertNotNull(embedded, "and its second should carry the index in the entry")
        renderInspector(fiGraph, beside.id, "paimon-file-node-index-beside", height = 1800)
        renderInspector(fiGraph, embedded.id, "paimon-file-node-index-embedded", height = 1800)

        // And a file written outside the table: `External Path` as recorded, `Reading` where it
        // was found, and `Resolved` saying it was the recorded path's tail under the local
        // warehouse that found it.
        val epGraph = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/ep").absolutePath)),
            showRows = false,
        )
        val external = epGraph.nodes.filterIsInstance<GraphNode.PaimonDataFileNode>()
            .firstOrNull { it.pathResolution == model.PaimonPathResolution.EXTERNAL_REROOTED }
        assertNotNull(external, "the ep fixture's files should be re-rooted under the local warehouse")
        renderInspector(epGraph, external.id, "paimon-file-node-external", height = 1800)

        // And a file written under an older schema than the manifest that lists it: `Schema ID`
        // says 0 and the Column Bounds section has two columns where the manifest's schema has
        // three, because the stats were decoded against the file's own schema.
        val seGraph = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/se").absolutePath)),
            showRows = false,
        )
        val oldSchemaRemoved = seGraph.nodes.filterIsInstance<GraphNode.PaimonDataFileNode>()
            .firstOrNull { it.entry.file?.schemaId == 0L && it.operationKind == model.PaimonEntryKind.DELETE }
        assertNotNull(oldSchemaRemoved, "the se fixture's compaction removes a schema-0 file")
        assertEquals(listOf("k", "v"), oldSchemaRemoved.columnBounds?.map { it.name })
        renderInspector(seGraph, oldSchemaRemoved.id, "paimon-file-node-old-schema", height = 2200)

        // And row tracking, both shapes: an appended file's `Row IDs` row states the range its
        // first id implies, and a compaction's output says the ids are in the file.
        val rtGraph = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/rt").absolutePath)),
            showRows = false,
        )
        val rtFiles = rtGraph.nodes.filterIsInstance<GraphNode.PaimonDataFileNode>()
        val tracked = rtFiles.firstOrNull { it.entry.file?.firstRowId == 3L && it.operationKind == model.PaimonEntryKind.ADD }
        val compacted = rtFiles.firstOrNull { it.entry.file?.fileSource == model.PaimonFileSource.COMPACT }
        assertNotNull(tracked, "the rt fixture's second append should record a first row id of 3")
        assertNotNull(compacted, "and its compaction should have written a file with none")
        renderInspector(rtGraph, tracked.id, "paimon-file-node-row-tracked", height = 1800)
        renderInspector(rtGraph, compacted.id, "paimon-file-node-row-tracked-compacted", height = 1800)
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

        // `deep`: the columns are the current schema's leaves by path — `addr.town`, not the
        // `city` its first manifest records — and a filter on the renamed leaf skips the file
        // whose bound was written under the old name.
        val deep = GraphLayoutService.layoutGraph(
            UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/deep").absolutePath)),
            showRows = false,
        )
        val deepTable = deep.nodes.filterIsInstance<GraphNode.TableNode>().single()
        renderScene("scan-pruning-nested", width = 1400, height = 2600) {
            NodeDetailsContent(deep, setOf(deepTable.id), scanFilter = model.ScanFilter.of(listOf(ScanPredicate("addr.town", PredicateOp.EQ, "Izmir"))))
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
     * The same section over a Paimon table, where until the bridge existed every row said "would
     * be read" because it saw no manifests and no files.
     *
     * `dt = 2024-03-07` is the filter the fixture was built to answer: two manifests skipped by
     * their partition range, five files never reached, one file skipped by its own bound inside
     * the manifest that survives, one read — all three verdicts on one screen.
     */
    @Test
    fun `the scan pruning section renders a paimon table's verdicts`() {
        val graph = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/pt").absolutePath)),
            showRows = false,
        )
        val table = graph.nodes.filterIsInstance<GraphNode.TableNode>().first()
        renderScene("scan-pruning-paimon", width = 1400, height = 3000) {
            NodeDetailsContent(
                graph,
                setOf(table.id),
                scanFilter = model.ScanFilter.of(listOf(ScanPredicate("dt", PredicateOp.EQ, "2024-03-07"))),
            )
        }
        // And on `de`, where the file stage stands down: the reason once above the table, and
        // every file "not evaluated" rather than skipped by a bound a patch replaced.
        val de = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/de").absolutePath)),
            showRows = false,
        )
        renderScene("scan-pruning-paimon-de", width = 1400, height = 5600) {
            NodeDetailsContent(
                de,
                setOf(de.nodes.filterIsInstance<GraphNode.TableNode>().first().id),
                scanFilter = model.ScanFilter.of(listOf(ScanPredicate("b", PredicateOp.EQ, "11"))),
            )
        }
        // And on `pc`, a primary-key table whose bucket overlaps: `v = 'g'` is held by one
        // level-0 file, so the level-5 file its own bounds rule out is read with its bucket —
        // the rule once above the table, the bucket's reason leading that row's cell.
        val pc = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/pc").absolutePath)),
            showRows = false,
        )
        // The section alone: on the whole table panel it sits below an expiry list of 26 rows,
        // and the placement was judged on `pt` above.
        renderScene("scan-pruning-paimon-pk", width = 1400, height = 5600) {
            Column(Modifier.padding(16.dp)) {
                ScanPruningSection(pc, model.ScanFilter.of(listOf(ScanPredicate("v", PredicateOp.EQ, "g")))) {}
            }
        }
        // And on `fa`, whose files carry a bloom filter: `v = 'delta'` is inside both files'
        // bounds and held by one — the other is ruled out by its index file when read, which
        // its reason cell and note both say, under the rule stated once above the table.
        val fa = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/fa").absolutePath)),
            showRows = false,
        )
        renderScene("scan-pruning-paimon-fa", width = 1400, height = 2400) {
            Column(Modifier.padding(16.dp)) {
                ScanPruningSection(fa, model.ScanFilter.of(listOf(ScanPredicate("v", PredicateOp.EQ, "delta")))) {}
            }
        }
        // fbs: the bit-sliced index. `n BETWEEN 4 AND 8` is inside file 1's -5..10 bounds and
        // neither `n >= 4` nor `n <= 8` rules it out alone; the two terms' rows are different
        // rows, which the fold across terms sees — one file skipped by its index with the count
        // per term, one by its bounds, one all null, and the all-7 file read.
        val fbs = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/fbs").absolutePath)),
            showRows = false,
        )
        renderScene("scan-pruning-paimon-fbs", width = 1400, height = 3800) {
            Column(Modifier.padding(16.dp)) {
                ScanPruningSection(fbs, (parseScanFilter("n BETWEEN 4 AND 8") as ScanFilterParse.Parsed).filter) {}
            }
        }
    }

    /**
     * A file's recorded statistics against its rows, behind a click — captured settled on one
     * of `parted`'s files, every type the corpus carries in one table, and once more with a
     * bound moved past a row and a null count off, since a verdict column is judged by the
     * exception it has to make findable. A section that only ever says "agrees" checks nothing.
     */
    @Test
    fun `the statistics check renders agreement and a planted disagreement`() {
        val graph = graphFor("parted")
        val file = graph.nodes.filterIsInstance<GraphNode.FileNode>().first { deleteKindOf(it.data) == null && it.localPath?.let { p -> File(p).isFile } == true }
        val recorded = file.recordedColumnStats()
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("stats-check", width = 1400, height = 700, ready = settled::get) {
            Column(Modifier.padding(16.dp)) {
                StatsCheckSection(file.id, file.localPath, recorded, file.data.recordCount, recordedSize = file.data.fileSizeInBytes, recordedSplitOffsets = file.data.splitOffsets, startRequested = true) { settled.set(true) }
            }
        }
        val moved = recorded.map {
            when (it.name) {
                "id" -> it.copy(lower = (it.lower as Number).toLong() + 1, lowerShown = "${(it.lower as Number).toLong() + 1}")
                "name" -> it.copy(nullCount = (it.nullCount ?: 0L) + 1)
                else -> it
            }
        }
        val settledMoved = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("stats-check-disagreeing", width = 1400, height = 700, ready = settledMoved::get) {
            Column(Modifier.padding(16.dp)) {
                StatsCheckSection(file.id + "-moved", file.localPath, moved, (file.data.recordCount ?: 0L) + 1, recordedSize = file.data.fileSizeInBytes, recordedSplitOffsets = file.data.splitOffsets, startRequested = true) { settledMoved.set(true) }
            }
        }
    }

    /**
     * The same section on the one file with several row groups — `rgs`'s 5,000 rows in
     * thirteen — where the row-groups line has thirteen offsets to put beside the thirteen
     * recorded, and once more with the recorded size off by one and one offset moved, the two
     * layout figures a reader takes on trust drawn in the error colour.
     */
    @Test
    fun `the statistics check renders the row groups beside the recorded split offsets`() {
        val graph = graphFor("rgs")
        val file = graph.nodes.filterIsInstance<GraphNode.FileNode>().first { it.data.recordCount == 5000L }
        val recorded = file.recordedColumnStats()
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("stats-check-row-groups", width = 1400, height = 900, ready = settled::get) {
            Column(Modifier.padding(16.dp)) {
                StatsCheckSection(file.id, file.localPath, recorded, file.data.recordCount, recordedSize = file.data.fileSizeInBytes, recordedSplitOffsets = file.data.splitOffsets, startRequested = true) { settled.set(true) }
            }
        }
        val offsets = file.data.splitOffsets!!.toMutableList().also { it[1] = it[1] + 1 }
        val settledMoved = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("stats-check-row-groups-disagreeing", width = 1400, height = 900, ready = settledMoved::get) {
            Column(Modifier.padding(16.dp)) {
                StatsCheckSection(file.id + "-moved", file.localPath, recorded, file.data.recordCount, recordedSize = file.data.fileSizeInBytes!! + 1, recordedSplitOffsets = offsets, startRequested = true) { settledMoved.set(true) }
            }
        }
    }

    /**
     * The delete pairing from a data file's end, on the one table where all four ruling-out
     * rules appear in one section: `fupp`'s commit-1 `p=x` file has its own commit's positional
     * delete reaching it, two equality deletes below it by sequence, one keyed to the other
     * partition and one whose bounds miss its ids. Every reason cell has to explain a different
     * `no`, which is what this capture is for.
     */
    @Test
    fun `the delete pairing section names each of the four rules that rule a delete out`() {
        val graph = graphFor("fupp")
        val file = graph.nodes.filterIsInstance<GraphNode.FileNode>().single {
            deleteKindOf(it.data) == null && it.sequenceNumber == 1L && it.partition?.path == "p=x"
        }
        renderScene("deletes-reaching-fupp", width = 1400, height = 1300) {
            Column(Modifier.padding(16.dp)) {
                DeletesReachingSection(file, graph)
            }
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
     * A branch cut from a branch. What to look for: `b1`'s two commits in one column under its
     * name, `b2`'s two in a column of their own with a lineage edge back into `b1`'s first, and
     * `main`'s three commits under `main` — not under `b2`, which is where a reused column put
     * them before `SnapshotTracks` stopped reusing one.
     */
    @Test
    fun `the canvas draws a branch forked from a branch beside it`() {
        val graph = graphFor("nested")
        val columns = graph.nodes.filterIsInstance<GraphNode.SnapshotNode>().map { it.x }.distinct()
        assertTrue(columns.size == 3, "main and two branches should occupy three columns, got $columns")
        renderCanvas("graph-canvas-nested", graph, AggregationPolicy.DEFAULT_PAGE_SIZE, zoom = 0.35f)
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
        // Stepping is selecting: the newer side stepped back once selects the pair with that side
        // moved, and the panel drawn for it says so in its own header rows.
        val stepped = graph.stepComparableSnapshot(snapshots.last().id, -1)!!
        assertTrue(stepped.nodeId != snapshots.first().id && stepped.nodeId != snapshots.last().id)
        renderInspector(graph, setOf(snapshots.first().id, stepped.nodeId), "snapshot-compare-stepped", height = 1000)
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
     * The one table with a file its metadata does not name, and the clean case beside it.
     *
     * Rendered against `cl`, whose overwrite left a changelog file Paimon declined to commit, so
     * the table has one row and the line above it says what the row costs; and against `mor`, so
     * the sentence for a table with nothing unreferenced is seen once. Both wait for the walk the
     * same way the delete-file reads do.
     */
    @Test
    fun `a table names the files on disk its metadata does not`() {
        val cl = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/cl").absolutePath)),
            showRows = false,
        )
        val clTable = cl.nodes.filterIsInstance<GraphNode.TableNode>().single()
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("unreferenced-files", width = 1400, height = 460, ready = settled::get) {
            Column(Modifier.padding(16.dp)) {
                UnreferencedFilesSection(clTable, startRequested = true) { settled.set(true) }
            }
        }

        val morTable = graphFor("mor").nodes.filterIsInstance<GraphNode.TableNode>().single()
        val cleanSettled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("unreferenced-files-none", width = 1400, height = 260, ready = cleanSettled::get) {
            Column(Modifier.padding(16.dp)) {
                UnreferencedFilesSection(morTable, startRequested = true) { cleanSettled.set(true) }
            }
        }
    }

    /**
     * The Iceberg metadata a Paimon table writes beside its own, on `pic`.
     *
     * The section has three things to say at once and this is the table that says all three: the
     * export is current, one live file is in it, and the other is not — one by the rebuild path
     * that ignores the level rule, one by the level rule itself. A capture where the two lines
     * read as the same kind of absence would be the defect, so it is rendered rather than only
     * asserted. Waits for the read the way the delete-file sections do.
     */
    @Test
    fun `a Paimon table's Iceberg export is drawn against the table it exports`() {
        val pic = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/pic").absolutePath)),
            showRows = false,
        )
        val picTable = pic.nodes.filterIsInstance<GraphNode.TableNode>().single()
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("paimon-iceberg-export", width = 1400, height = 660, ready = settled::get) {
            Column(Modifier.padding(16.dp)) {
                IcebergExportSection(picTable, startRequested = true) { settled.set(true) }
            }
        }

        // And `pid`, whose vectors go out as Iceberg v3 deletion vectors: the line that says so
        // reads as ordinary, beside the amber notes the level rule earns on `pic`.
        val pid = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/pid").absolutePath)),
            showRows = false,
        )
        val pidTable = pid.nodes.filterIsInstance<GraphNode.TableNode>().single()
        val pidSettled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("paimon-iceberg-export-vectors", width = 1400, height = 560, ready = pidSettled::get) {
            Column(Modifier.padding(16.dp)) {
                IcebergExportSection(pidTable, startRequested = true) { pidSettled.set(true) }
            }
        }
    }

    /**
     * Rows looked up on `eqdel` under `id >= 1`: seven rows across two files, one deleted by
     * position, two by equality, four live — every fate the lookup decides in one column, which
     * is what a verdict column has to be judged against. Waits for the read like the others.
     */
    @Test
    fun `a table looks rows up and says each one's fate`() {
        val eqdel = graphFor("eqdel")
        val table = eqdel.nodes.filterIsInstance<GraphNode.TableNode>().single()
        val filter = ScanFilter.Term(model.ScanPredicate("id", model.PredicateOp.GTE, "1"))
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("row-lookup", width = 1400, height = 1000, ready = settled::get) {
            Column(Modifier.padding(16.dp)) {
                RowLookupSection(table, eqdel, filter, startRequested = true) { settled.set(true) }
            }
        }
        // The same at one file a page: the headline counts what is not read yet and the control
        // under the table offers the next page, sized to what is left.
        val pagedSettled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("row-lookup-paged", width = 1400, height = 900, ready = pagedSettled::get) {
            Column(Modifier.padding(16.dp)) {
                RowLookupSection(table, eqdel, filter, startRequested = true, pageSize = 1) { pagedSettled.set(true) }
            }
        }
    }

    /**
     * `mor`'s row 5 traced through its six commits: put in by the second append, changed by the
     * update, kept by the compaction, untouched by both deletes — two marked steps in a column
     * of `unchanged`, which is the shape the change column has to read in. The trace runs
     * behind the lookup, so both reads are waited for.
     */
    @Test
    fun `a table traces a row through the snapshots on main`() {
        val mor = graphFor("mor")
        val table = mor.nodes.filterIsInstance<GraphNode.TableNode>().single()
        val filter = ScanFilter.Term(model.ScanPredicate("id", model.PredicateOp.EQ, "5"))
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        val traced = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("row-lookup-history", width = 1400, height = 1250, ready = { settled.get() && traced.get() }) {
            Column(Modifier.padding(16.dp)) {
                RowLookupSection(table, mor, filter, startRequested = true, historyRequested = true, onHistorySettled = { traced.set(true) }) { settled.set(true) }
            }
        }
    }

    /**
     * The live row count on `eqdel`'s current snapshot — 4 of 7, an equality delete and a positional
     * delete reaching two files — behind the click; and on `test`, whose snapshot lists no delete
     * manifest and is answered at once.
     */
    @Test
    fun `an Iceberg snapshot says what a read of it returns`() {
        fun current(name: String): GraphNode.SnapshotNode {
            val graph = graphFor(name)
            val id = graph.nodes.filterIsInstance<GraphNode.MetadataNode>().maxBy { it.simpleId }.data.currentSnapshotId
            return graph.nodes.filterIsInstance<GraphNode.SnapshotNode>().single { it.data.snapshotId == id }
        }
        val eqdel = current("eqdel")
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("live-rows-eqdel", width = 1400, height = 640, ready = settled::get) {
            Column(Modifier.padding(16.dp)) {
                LiveRowsSection(eqdel, startRequested = true) { settled.set(true) }
            }
        }
        val plain = current("test")
        val plainSettled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("live-rows-plain", width = 1400, height = 300, ready = plainSettled::get) {
            Column(Modifier.padding(16.dp)) {
                LiveRowsSection(plain) { plainSettled.set(true) }
            }
        }
    }

    /**
     * The merged row count on `dv`'s latest snapshot — 1,497 of 1,500, three keys marked by
     * vectors, read from the bucket's files — and on `pt`, partitioned, where the per-bucket table
     * is drawn; both wait for the merge. `ad`'s is the metadata's and needs no click.
     */
    @Test
    fun `a Paimon snapshot says what a read of it returns`() {
        fun latest(name: String): Pair<GraphModel, GraphNode.PaimonSnapshotNode> {
            val graph = GraphLayoutService.layoutGraph(
                PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/$name").absolutePath)),
                showRows = false,
            )
            return graph to graph.nodes.filterIsInstance<GraphNode.PaimonSnapshotNode>().filter { it.branch == null }.maxBy { it.data.id ?: 0L }
        }
        // `fr` is the first-row table whose DELETE left a level-0 file a batch read skips: the count says so.
        listOf("dv" to 420, "pt" to 900, "fr" to 520).forEach { (name, height) ->
            val (_, node) = latest(name)
            val settled = java.util.concurrent.atomic.AtomicBoolean(false)
            renderUntil("paimon-merged-rows-$name", width = 1400, height = height, ready = settled::get) {
                Column(Modifier.padding(16.dp)) {
                    PaimonMergedCountSection(node, startRequested = true) { settled.set(true) }
                }
            }
        }
        val (_, ad) = latest("ad")
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("paimon-merged-rows-ad", width = 1400, height = 300, ready = settled::get) {
            Column(Modifier.padding(16.dp)) {
                PaimonMergedCountSection(ad) { settled.set(true) }
            }
        }
        // `sgd` at snapshot 4: partial-update with sequence groups, the key a -D removed at or
        // above the row's `ga` counted as retracted, the rule stating the field.
        val (sgd, _) = latest("sgd")
        val four = sgd.nodes.filterIsInstance<GraphNode.PaimonSnapshotNode>().single { it.branch == null && it.data.id == 4L }
        val sgdSettled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("paimon-merged-rows-sgd", width = 1400, height = 620, ready = sgdSettled::get) {
            Column(Modifier.padding(16.dp)) {
                PaimonMergedCountSection(four, startRequested = true) { sgdSettled.set(true) }
            }
        }
        // `sgm` at snapshot 4: the group versioned by two fields, the rule naming both.
        val (sgm, _) = latest("sgm")
        val sgmFour = sgm.nodes.filterIsInstance<GraphNode.PaimonSnapshotNode>().single { it.branch == null && it.data.id == 4L }
        val sgmSettled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("paimon-merged-rows-sgm", width = 1400, height = 620, ready = sgmSettled::get) {
            Column(Modifier.padding(16.dp)) {
                PaimonMergedCountSection(sgmFour, startRequested = true) { sgmSettled.set(true) }
            }
        }
    }

    /**
     * The Paimon lookup on `lk`, whose six records show every fate the format has at once: the
     * updated key's old record superseded by the file holding the new one, the deleted key's
     * record superseded by its `-D`, that `-D` as a retraction, and three live rows.
     */
    @Test
    fun `a Paimon row lookup names what shadows each record`() {
        val lk = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/lk").absolutePath)),
            showRows = false,
        )
        val table = lk.nodes.filterIsInstance<GraphNode.TableNode>().single()
        val filter = ScanFilter.Term(model.ScanPredicate("k", model.PredicateOp.GTE, "1"))
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("paimon-row-lookup", width = 1400, height = 900, ready = settled::get) {
            Column(Modifier.padding(16.dp)) {
                RowLookupSection(table, lk, filter, startRequested = true) { settled.set(true) }
            }
        }
        // And the changelog for one key: under `lookup` the change is published by the COMPACT
        // after the append — `+I`, then the `-U` / `+U` pair, the retraction coloured — which is
        // the other side of the history's "changed at the APPEND".
        val two = ScanFilter.Term(model.ScanPredicate("k", model.PredicateOp.EQ, "2"))
        val changelogSettled = java.util.concurrent.atomic.AtomicBoolean(false)
        val lookupSettled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("paimon-row-changelog", width = 1400, height = 1700, ready = { lookupSettled.get() && changelogSettled.get() }) {
            Column(Modifier.padding(16.dp)) {
                RowLookupSection(table, lk, two, startRequested = true, changelogRequested = true, onChangelogSettled = { changelogSettled.set(true) }) { lookupSettled.set(true) }
            }
        }
        // And on `pu`, partial-update: records folded into one row, a key removed by a -D and
        // re-inserted, the rule stated under the headline.
        val pu = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/pu").absolutePath)),
            showRows = false,
        )
        val puTable = pu.nodes.filterIsInstance<GraphNode.TableNode>().single()
        val puSettled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("paimon-row-lookup-pu", width = 1400, height = 1100, ready = puSettled::get) {
            Column(Modifier.padding(16.dp)) {
                RowLookupSection(puTable, pu, ScanFilter.Term(model.ScanPredicate("k", model.PredicateOp.LTE, "3")), startRequested = true) { puSettled.set(true) }
            }
        }
        // And a sampled row's own panel on `mor`, the row a positional delete names: the Delete
        // Files section, asked and answered.
        val mor = GraphLayoutService.layoutGraph(
            UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/mor").absolutePath)),
            showRows = true,
        )
        // The id-7 row under the compacted file — the one the delete names; the file the
        // compaction removed draws the same row, and no delete reaches that copy.
        val seven = mor.nodes.filterIsInstance<GraphNode.RowNode>().filter { it.content == 0 && it.resolvedData["id"]?.toString() == "7" }.first { row ->
            val parent = mor.edges.first { it.toId == row.id }.let { mor.nodeById[it.fromId] as GraphNode.FileNode }
            model.deleteCandidatesFor(parent, mor.nodes.filterIsInstance<GraphNode.FileNode>()).any { it.verdict == model.DeleteReachVerdict.REACHES }
        }
        renderInspector(mor, seven.id, "row-node-deleted", height = 1300)
        val rowSettled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("row-node-deleted-asked", width = 1400, height = 460, ready = rowSettled::get) {
            Column(Modifier.padding(16.dp)) {
                RowDeletesSection(seven, mor, startRequested = true) { rowSettled.set(true) }
            }
        }
        // And a Paimon record's own panel on `lk`: the updated key's old record, superseded by
        // the file holding the update, with the key's other records under it.
        val lkRows = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/lk").absolutePath)),
            showRows = true,
        )
        val lkInput = requireNotNull(lkRows.nodes.filterIsInstance<GraphNode.TableNode>().single().paimonRowLookup.value)
        val superseded = lkRows.nodes.filterIsInstance<GraphNode.RowNode>().first { row ->
            val parent = lkRows.edges.first { it.toId == row.id }.let { lkRows.nodeById[it.fromId] as GraphNode.PaimonDataFileNode }
            val file = lkInput.files.firstOrNull { it.fileName == parent.entry.file?.fileName } ?: return@first false
            val key = row.resolvedData["_KEY_k"]?.toString() ?: return@first false
            val hits = PaimonRowLookup.lookup(lkInput, ScanFilter.Term(model.ScanPredicate("k", model.PredicateOp.EQ, key)), emptySet()).hits
            hits.any { it.filePath == file.fileName && it.position == row.filePosition && it.fate == model.RowFate.SUPERSEDED }
        }
        val mergeSettled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("paimon-row-node-merge", width = 1400, height = 620, ready = mergeSettled::get) {
            Column(Modifier.padding(16.dp)) {
                PaimonRowMergeSection(superseded, lkRows, startRequested = true) { mergeSettled.set(true) }
            }
        }
        // `pkr`: the row of the file written before the key was renamed — `_KEY_k` on its card,
        // `id` in the schema — asked under the schema's name, and superseded by the write after.
        val pkrRows = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/pkr").absolutePath)),
            showRows = true,
        )
        val renamedKey = pkrRows.nodes.filterIsInstance<GraphNode.RowNode>().first { it.resolvedData["_KEY_k"]?.toString() == "1" }
        val renamedSettled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("paimon-row-node-merge-renamed-key", width = 1400, height = 620, ready = renamedSettled::get) {
            Column(Modifier.padding(16.dp)) {
                PaimonRowMergeSection(renamedKey, pkrRows, startRequested = true) { renamedSettled.set(true) }
            }
        }
        // And a row of `de`'s patched file: its own cells say b = 1, the read says 11.
        val deRows = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/de").absolutePath)),
            showRows = true,
        )
        val patchedRow = deRows.nodes.filterIsInstance<GraphNode.RowNode>().first { row ->
            val parent = deRows.edges.first { it.toId == row.id }.let { deRows.nodeById[it.fromId] as GraphNode.PaimonDataFileNode }
            !parent.partial && row.resolvedData["id"]?.toString() == "1"
        }
        val stitchSettled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("paimon-row-node-stitched", width = 1400, height = 420, ready = stitchSettled::get) {
            Column(Modifier.padding(16.dp)) {
                PaimonRowMergeSection(patchedRow, deRows, startRequested = true) { stitchSettled.set(true) }
            }
        }
        // And on `de`, data evolution: a filter on the patched value finds the row, stitched from
        // the patch and the file it patches, and the note names where `b` came from.
        val de = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/de").absolutePath)),
            showRows = false,
        )
        val deTable = de.nodes.filterIsInstance<GraphNode.TableNode>().single()
        val deSettled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("paimon-row-lookup-de", width = 1400, height = 700, ready = deSettled::get) {
            Column(Modifier.padding(16.dp)) {
                RowLookupSection(deTable, de, ScanFilter.Term(model.ScanPredicate("b", model.PredicateOp.GTE, "11")), startRequested = true) { deSettled.set(true) }
            }
        }
    }

    /**
     * The whole-table check, on the one table whose metadata disagrees with itself — `tg`'s tag
     * records a changelog count against a list the expiry deleted — and on `mor`, where every
     * comparison agrees, so the line for a clean table is seen once. Both wait for the run.
     */
    @Test
    fun `a table checks every recorded figure at once`() {
        val tg = GraphLayoutService.layoutGraph(
            PaimonUnifiedTableModel(Paths.get(File(repoRoot, "example/paimon/db.db/tg").absolutePath)),
            showRows = false,
        )
        val tgTable = tg.nodes.filterIsInstance<GraphNode.TableNode>().single()
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("integrity", width = 1400, height = 460, ready = settled::get) {
            Column(Modifier.padding(16.dp)) {
                IntegritySection(tgTable, startRequested = true) { settled.set(true) }
            }
        }

        val morTable = graphFor("mor").nodes.filterIsInstance<GraphNode.TableNode>().single()
        val cleanSettled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("integrity-agrees", width = 1400, height = 300, ready = cleanSettled::get) {
            Column(Modifier.padding(16.dp)) {
                IntegritySection(morTable, startRequested = true) { cleanSettled.set(true) }
            }
        }
    }

    /**
     * The second click under the report: the data files read. `mor` agrees on every file; the
     * disagreeing capture hands `parted`'s table node targets with one file's `id` lower bound
     * moved past its smallest row and its row count off by one, so the findings table is seen
     * with rows in it — nothing checked in disagrees with itself.
     */
    @Test
    fun `a table reads its data files behind a second click`() {
        val morTable = graphFor("mor").nodes.filterIsInstance<GraphNode.TableNode>().single()
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("integrity-files", width = 1400, height = 480, ready = settled::get) {
            Column(Modifier.padding(16.dp)) {
                IntegritySection(morTable, startRequested = true, readFilesRequested = true) { settled.set(true) }
            }
        }

        val partedTable = graphFor("parted").nodes.filterIsInstance<GraphNode.TableNode>().single()
        val targets = requireNotNull(partedTable.fileStats.value)
        val moved = targets.mapIndexed { i, t ->
            if (i != 0) t else t.copy(
                recorded = t.recorded.map { s ->
                    if (s.name != "id") s else s.copy(lower = (s.lower as Number).toLong() + 1, lowerShown = "${(s.lower as Number).toLong() + 1}")
                },
                recordedRows = (t.recordedRows ?: 0L) + 1,
            )
        }
        val planted = partedTable.copy(fileStats = DeferredRead.of { moved })
        val plantedSettled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("integrity-files-disagreeing", width = 1400, height = 820, ready = plantedSettled::get) {
            Column(Modifier.padding(16.dp)) {
                IntegritySection(planted, startRequested = true, readFilesRequested = true) { plantedSettled.set(true) }
            }
        }

        // A table past the cap is read a page at a time: at a page of two, `parted`'s first
        // click reads two of its four files and offers the next two, with the count left.
        val pagedSettled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("integrity-files-paged", width = 1400, height = 560, ready = pagedSettled::get) {
            Column(Modifier.padding(16.dp)) {
                IntegritySection(partedTable, startRequested = true, readFilesRequested = true, pageSize = 2) { pagedSettled.set(true) }
            }
        }

        // `pstats` names a partition statistics file: the same click opens it against its record
        // and the live files of its snapshot, and the stage says so on a line of its own; the
        // disagreeing one hands the node a check whose file is gone.
        val pstatsTable = graphFor("pstats").nodes.filterIsInstance<GraphNode.TableNode>().single()
        val statsSettled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("integrity-files-statistics", width = 1400, height = 600, ready = statsSettled::get) {
            Column(Modifier.padding(16.dp)) {
                IntegritySection(pstatsTable, startRequested = true, readFilesRequested = true) { statsSettled.set(true) }
            }
        }
        val gone = pstatsTable.copy(statisticsFiles = DeferredRead.of {
            requireNotNull(pstatsTable.statisticsFiles.value).map { it.copy(figures = 0, findings = emptyList(), problem = "NoSuchFileException: ${it.name}") }
        })
        val goneSettled = java.util.concurrent.atomic.AtomicBoolean(false)
        renderUntil("integrity-files-statistics-gone", width = 1400, height = 600, ready = goneSettled::get) {
            Column(Modifier.padding(16.dp)) {
                IntegritySection(gone, startRequested = true, readFilesRequested = true) { goneSettled.set(true) }
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
        val unreadRow = GraphLayoutService.layoutGraph(
            UnifiedTableModel(Paths.get(File(repoRoot, "example/iceberg/default/orcfmt").absolutePath)),
            showRows = true,
        ).nodes.filterIsInstance<GraphNode.RowNode>().first { it.content == 0 }
        assertTrue(unreadRow.readError != null, "orcfmt's rows are not readable: ${unreadRow.resolvedData}")

        renderScene("graph-cards", width = 700, height = 2500) {
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
                // A row whose file DuckDB cannot read — orcfmt's, ORC — which drew as a blank card
                // before the reason was carried; the reason has to fit the card's three lines.
                RowCard(unreadRow)
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
        // The expiry plan measures ages from a clock; pinned to the table's own last write so the
        // capture is the same whichever day it is taken.
        // A Paimon table has no metadata node; its last commit's time is the same clock, plus a
        // second so the newest snapshot is already in the past.
        val lastWrite = graph.nodes.filterIsInstance<GraphNode.MetadataNode>().mapNotNull { it.data.lastUpdatedMs }.maxOrNull()
            ?: graph.nodes.filterIsInstance<GraphNode.PaimonSnapshotNode>().mapNotNull { it.data.timeMillis }.maxOrNull()?.plus(1_000)
            ?: System.currentTimeMillis()
        CompositionLocalProvider(LocalExpiryClock provides { lastWrite }) {
            NodeDetailsContent(graph, setOf(nodeId))
        }
    }
}
