package model

import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import service.AggregationPolicy
import service.GraphLayoutService

/**
 * Finding a node by typing what you know about it.
 *
 * Against the checked-in tables rather than a constructed graph, because the whole claim is about
 * vocabulary: whether the words a reader would actually have — a format, a partition value, a
 * commit operation, a manifest's file name — reach the artifact they describe. A hand-built node
 * would be built from the same vocabulary the search reads, and would agree with itself.
 */
class GraphSearchTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun graphOf(name: String, policy: AggregationPolicy = AggregationPolicy.NONE): GraphModel {
        val tableDir = File(repoRoot, "example/iceberg/default/$name")
        assertTrue(tableDir.isDirectory, "fixture missing at $tableDir")
        return GraphLayoutService.layoutGraph(
            UnifiedTableModel(Paths.get(tableDir.absolutePath)),
            showRows = false,
            policy = policy,
        )
    }

    private fun GraphModel.search(query: String) = GraphSearch.search(this, query)

    private fun GraphModel.matched(query: String) = search(query).matches.map { nodeById.getValue(it) }

    @Test
    fun `a blank query matches nothing rather than everything`() {
        val graph = graphOf("test")
        assertTrue(graph.search("").isEmpty)
        assertTrue(graph.search("   ").isEmpty)
    }

    /**
     * The case the tree's label search cannot serve, and the reason this file exists.
     *
     * A manifest's label is `Manifest (N adds)`, so its path — the string a reader copies out of
     * `.manifests` or a log line and comes here with — matches nothing at all in the tree.
     */
    @Test
    fun `a manifest is found by its file name`() {
        val graph = graphOf("mor")
        val manifest = graph.nodes.filterIsInstance<GraphNode.ManifestNode>().first()
        val fileName = manifest.data.manifestPath!!.substringAfterLast('/')

        val found = graph.matched(fileName)
        assertTrue(
            found.any { it.id == manifest.id },
            "searching a manifest's own file name should find it; got ${found.map { it.id }}",
        )
    }

    @Test
    fun `a data file is found by its format and by its content type`() {
        val graph = graphOf("mor")

        val parquet = graph.matched("parquet").filterIsInstance<GraphNode.FileNode>()
        assertTrue(parquet.isNotEmpty(), "mor's files are Parquet")

        // `mor` carries positional deletes, which is what makes this table the one to ask.
        val deletes = graph.matched("position deletes").filterIsInstance<GraphNode.FileNode>()
        assertTrue(deletes.isNotEmpty(), "mor has positional delete files")
        assertTrue(
            deletes.all { it.data.content == DataFileContent.POSITION_DELETES },
            "the content word must not match ordinary data files",
        )
        assertTrue(deletes.size < parquet.size, "the delete files are a subset of the Parquet files")
    }

    /**
     * A partition value reaches the files in that partition.
     *
     * `parted` decodes eight partition fields, and the rendered tuple is what the inspector prints,
     * so this is the string the reader is looking at when they decide to search for it.
     */
    @Test
    fun `a file is found by a partition value`() {
        val graph = graphOf("parted")
        val partitioned = graph.nodes.filterIsInstance<GraphNode.FileNode>()
            .first { it.partition?.isUnpartitioned == false }
        val firstValue = partitioned.partition!!.values.first()
        val term = "${firstValue.field.name}=${firstValue.human}"

        val found = graph.matched(term).filterIsInstance<GraphNode.FileNode>()
        assertTrue(found.isNotEmpty(), "searching \"$term\" should reach the files in that partition")
        assertTrue(
            found.all { it.partition?.path?.contains(term, ignoreCase = true) == true },
            "and nothing outside it",
        )
    }

    /** A commit is found by what it did, which is in the summary and on no label. */
    @Test
    fun `a snapshot is found by its operation and by a branch name`() {
        val graph = graphOf("branched")

        val operations = graph.nodes.filterIsInstance<GraphNode.SnapshotNode>()
            .mapNotNull { it.data.summary["operation"] }.toSet()
        assertTrue(operations.isNotEmpty(), "the fixture's snapshots record an operation")
        operations.forEach { operation ->
            val found = graph.matched(operation).filterIsInstance<GraphNode.SnapshotNode>()
            assertTrue(found.isNotEmpty(), "searching \"$operation\" should reach the commits that did it")
        }

        val ref = graph.nodes.filterIsInstance<GraphNode.SnapshotNode>()
            .flatMap { it.refs }.first { it.isBranch }
        val byRef = graph.matched(ref.name).filterIsInstance<GraphNode.SnapshotNode>()
        assertTrue(byRef.any { node -> node.refs.any { it.name == ref.name } }, "a branch name finds its tip")
    }

    @Test
    fun `matching is case-insensitive both ways`() {
        val graph = graphOf("mor")
        assertEquals(graph.search("PARQUET").matches, graph.search("parquet").matches)
        assertEquals(graph.search("Parquet").matches, graph.search("parquet").matches)
    }

    /**
     * Matches come back in the order the graph is read: down a column, then on to the next.
     *
     * "Find next" is only useful if the walk is the one the eye would make. ELK lays a layer out at
     * a single x, so ordering by column and then by height is that walk.
     */
    @Test
    fun `matches are ordered by column, then down the column`() {
        val graph = graphOf("branched")
        val matches = graph.search("append").matches
        assertTrue(matches.size > 2, "the fixture should give several matching commits")

        val positions = matches.map { graph.layoutPositions.getValue(it) }
        positions.zipWithNext().forEach { (a, b) ->
            val sameColumn = Math.abs(a.x - b.x) < 8.0
            assertTrue(
                b.x > a.x || (sameColumn && b.y >= a.y),
                "matches should run down a column before moving right: $a then $b",
            )
        }
    }

    /**
     * A search over a paged graph says how much it could not look at.
     *
     * The nodes aggregation folded away are not in the graph to be matched, so "no matches" is only
     * ever true of what is drawn. Reporting it without the count is the same silence the group
     * cards exist to prevent.
     */
    @Test
    fun `a paged graph reports what the search could not reach`() {
        val whole = graphOf("mor", AggregationPolicy.NONE)
        val paged = graphOf("mor", AggregationPolicy(pageSize = 2))

        assertEquals(0, whole.search("parquet").notDrawn, "nothing is folded when everything is drawn")

        val pagedResult = paged.search("parquet")
        assertTrue(pagedResult.notDrawn > 0, "paging mor at two siblings a parent must fold something")
        assertTrue(
            pagedResult.size < whole.search("parquet").size,
            "and the folded nodes are the ones missing from the matches",
        )
    }

    /**
     * A group card is the absence of nodes, so it contributes no vocabulary at all.
     *
     * Asserted as the property rather than by searching for the words a group prints, which is how
     * this test was written first and why it proved nothing: `more`, `files` and `not drawn` match
     * no node in any of these fixtures, so "no group matched" was true before the rule existed.
     * The failure this must catch is somebody adding the group's own text — its kind's plural, its
     * count — to the searchable vocabulary, which would answer a search for a file name with the
     * card that says the file is not drawn.
     */
    @Test
    fun `a group node contributes no searchable text`() {
        val paged = graphOf("mor", AggregationPolicy(pageSize = 2))
        val groups = paged.nodes.filterIsInstance<GraphNode.GroupNode>()
        assertTrue(groups.isNotEmpty(), "the fixture should page, or this checks nothing")

        groups.forEach { group ->
            assertEquals(
                emptyList(), GraphSearch.searchableText(group),
                "a group stands for nodes rather than being one, so it must not be findable",
            )
            // And the words it prints reach nothing, which is the same claim from the other side.
            assertTrue(group.id !in paged.search(group.kind.plural).matches.toSet())
        }
    }

    @Test
    fun `next and previous walk the matches and wrap`() {
        val graph = graphOf("mor")
        val result = graph.search("parquet")
        assertTrue(result.size >= 3, "need several matches to see a wrap")

        assertEquals(result.matches[0], result.next(null), "with nothing selected, next is the first")
        assertEquals(result.matches[1], result.next(result.matches[0]))
        assertEquals(result.matches[0], result.next(result.matches.last()), "next wraps to the front")

        assertEquals(result.matches.last(), result.previous(null), "with nothing selected, previous is the last")
        assertEquals(result.matches[0], result.previous(result.matches[1]))
        assertEquals(result.matches.last(), result.previous(result.matches[0]), "previous wraps to the back")
    }

    @Test
    fun `stepping from a node that is not a match starts at the first one`() {
        val graph = graphOf("mor")
        val result = graph.search("parquet")
        assertEquals(result.matches.first(), result.next("table_root"))
    }

    @Test
    fun `an empty result steps nowhere rather than throwing`() {
        val graph = graphOf("test")
        val result = graph.search("a string no table contains: zzzz-qqqq")
        assertTrue(result.isEmpty)
        assertEquals(null, result.next(null))
        assertEquals(null, result.previous("table_root"))
    }

    /** Every kind is reachable, so a node type added without search vocabulary fails here. */
    @Test
    fun `every drawn node kind can be searched by something`() {
        val graphs = listOf(graphOf("mor"), graphOf("parted"), graphOf("branched"))
        val byKind = graphs.flatMap { it.nodes }.groupBy { it::class }

        byKind.forEach { (kind, nodes) ->
            if (kind == GraphNode.GroupNode::class) return@forEach
            assertTrue(
                nodes.any { GraphSearch.searchableText(it).any { text -> text.isNotBlank() } },
                "${kind.simpleName} contributes nothing to search, so it can never be found",
            )
        }
    }
}
