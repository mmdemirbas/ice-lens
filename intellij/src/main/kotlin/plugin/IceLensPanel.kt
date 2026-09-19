package plugin

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel
import model.GraphModel
import service.GraphLayoutService
import model.readTableModel

private val logger = Logger.getInstance(IceLensPanel::class.java)

/**
 * The tool window: the table's structure on the left, what the selected artifact records on the
 * right.
 *
 * Drawn with the IDE's own components rather than the desktop shell's Compose cards, and that is
 * forced rather than chosen — see this module's `build.gradle.kts` for the Skiko version clash
 * that settles it. It is also the better fit: a tool window is tall and narrow, which is the shape
 * a node-and-edge drawing reads worst in and a tree reads best in, and using `Tree` and `JBTable`
 * means the panel follows the IDE's theme, font size and accessibility settings for free.
 */
class IceLensPanel(private val project: Project, parent: Disposable) : Disposable {

    private val root = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(root)
    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
    }

    private val detailsModel = object : DefaultTableModel(arrayOf("Field", "Value"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val details = JBTable(detailsModel).apply {
        setShowGrid(false)
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        // The value column is where a path goes, so the field column takes only what it needs.
        columnModel.getColumn(0).preferredWidth = 130
        columnModel.getColumn(1).preferredWidth = 400
    }

    private val message = JBLabel("", JBLabel.CENTER).apply { border = JBUI.Borders.empty(16) }

    /**
     * Follows the service's selection for as long as the tool window is open.
     *
     * Unregistered on disposal rather than left attached, so a closed tool window stops holding the
     * last graph it drew — on a large table that is the biggest object this plugin allocates.
     */
    private val onTableChosen: (String) -> Unit = { path -> loadInBackground(path) }
    private val cards = CardLayout()
    private val content = JPanel(cards)

    val component: JComponent get() = content

    init {
        val split = OnePixelSplitter(true, SPLIT_PROPORTION).apply {
            firstComponent = JBScrollPane(tree)
            secondComponent = JBScrollPane(details)
        }
        content.add(JPanel(BorderLayout()).apply { add(message, BorderLayout.CENTER) }, MESSAGE)
        content.add(split, GRAPH)
        show(
            MESSAGE,
            "<html><center>No table open.<br><br>Right-click an Iceberg or Paimon table " +
                "directory in the Project view and choose \"Open in Iceberg Lens\".</center></html>",
        )

        tree.addTreeSelectionListener {
            val item = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? GraphTree.Item
            showDetails(item)
        }

        Disposer.register(parent, this)
        IceLensService.of(project).addListener(onTableChosen)
    }

    /**
     * Reads the table on a pooled thread, under the IDE's own progress reporting.
     *
     * `Task.Backgroundable` rather than a bare thread: reading a table walks its whole metadata
     * tree, and a reader who clicks a warehouse-sized table deserves a progress bar and a cancel
     * button in the place the IDE always puts them, not a frozen tool window.
     */
    private fun loadInBackground(path: String) {
        ApplicationManager.getApplication().invokeLater {
            show(MESSAGE, "Reading ${Path.of(path).fileName}…")
            ProgressManager.getInstance().run(
                object : Task.Backgroundable(project, "Reading table metadata", true) {
                    private var graph: GraphModel? = null
                    private var failure: String? = null

                    override fun run(indicator: ProgressIndicator) {
                        indicator.text = path
                        runCatching {
                            GraphLayoutService.layoutGraph(readTableModel(Path.of(path)), showRows = false)
                        }.onSuccess { graph = it }
                            .onFailure {
                                logger.warn("Could not open $path", it)
                                failure = it.message ?: it::class.simpleName ?: "unknown failure"
                            }
                    }

                    override fun onSuccess() {
                        val model = graph
                        if (model == null) {
                            show(MESSAGE, "<html><center>Could not open this table.<br>${failure.orEmpty()}</center></html>")
                        } else {
                            populate(model)
                        }
                    }
                }
            )
        }
    }

    private fun populate(graph: GraphModel) {
        root.removeAllChildren()
        GraphTree.build(graph).forEach { root.add(it) }
        treeModel.reload()
        // The table and its metadata versions, open; everything below stays closed, because a
        // table of any size has more manifests than a docked panel has rows.
        for (row in 0 until minOf(tree.rowCount, INITIALLY_EXPANDED_ROWS)) tree.expandRow(row)
        if (tree.rowCount > 0) tree.setSelectionRow(0)
        show(GRAPH, null)
    }

    /** Which selection the details table is showing; a read that lands for an older one is dropped. */
    private var detailsGeneration = 0L

    private fun showDetails(item: GraphTree.Item?) {
        detailsModel.rowCount = 0
        val generation = ++detailsGeneration
        item ?: return
        GraphTree.details(item.node, newest = item.newest).forEach { (field, value) ->
            detailsModel.addRow(arrayOf(field, value))
        }
        if (!GraphTree.hasDeferredDetails(item.node)) return
        // The eager rows are on screen; the deferred row is drawn reading and filled in when the
        // read lands — off the EDT, since a file's history walks every retained snapshot's
        // entries and a row's projection opens its file's footer.
        val placeholder = detailsModel.rowCount
        val label = GraphTree.deferredLabel(item.node)
        detailsModel.addRow(arrayOf(label, "reading…"))
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Reading $label", false) {
                private var rows: List<Pair<String, String>> = emptyList()

                override fun run(indicator: ProgressIndicator) {
                    rows = runCatching { GraphTree.deferredDetails(item.node, item.newest) }
                        .onFailure { logger.warn("Could not read $label of ${item.node.id}", it) }
                        .getOrDefault(listOf(label to "could not be read"))
                }

                override fun onSuccess() {
                    if (generation != detailsGeneration || placeholder >= detailsModel.rowCount) return
                    rows.forEachIndexed { i, (field, value) ->
                        if (i == 0) {
                            detailsModel.setValueAt(field, placeholder, 0)
                            detailsModel.setValueAt(value, placeholder, 1)
                        } else {
                            detailsModel.insertRow(placeholder + i, arrayOf(field, value))
                        }
                    }
                }
            }
        )
    }

    private fun show(card: String, text: String?) {
        if (text != null) message.text = text
        cards.show(content, card)
    }

    override fun dispose() {
        IceLensService.of(project).removeListener(onTableChosen)
    }

    private companion object {
        const val MESSAGE = "message"
        const val GRAPH = "graph"
        const val SPLIT_PROPORTION = 0.55f
        /** Table plus its metadata versions. Deep enough to orient, shallow enough to read. */
        const val INITIALLY_EXPANDED_ROWS = 12
    }
}
