package model

/**
 * A data file's index as the node carries it: decoded on first use, from the bytes the manifest
 * entry embeds or from the `.index` file beside the data file — see [service.PaimonFileIndexReader].
 * [error] is set where the bytes could not be read or decoded, because "no index" and "an index
 * this could not read" are two answers and the second is the one worth saying.
 */
data class PaimonFileIndexRead(val index: PaimonFileIndex?, val error: String? = null)

/**
 * Where a batch read consults a file's index, read off release-1.3.1.
 *
 * An append table's scan (`AppendOnlyFileStoreScan.filterByStats`) tests the **embedded** index
 * after the bounds, so a file it rules out is not in the plan; the `.index` file beside a data
 * file is opened by the read (`RawFileSplitRead.createFileReader`, through `FileIndexEvaluator`),
 * so a file it rules out is in the plan and yields no row — its index was read, its data was
 * not. A primary-key table's scan tests the embedded index only under deletion vectors
 * (`KeyValueFileStore.newScan` passes `fileIndexReadEnabled && deletionVectorsEnabled`), and its
 * read consults either index only on a split it reads raw, without merging — see
 * [paimonRawConvertible]; a merge read consults none.
 */
enum class FileIndexUse { AT_PLAN, AT_READ }

/** The rule in a sentence, for the panel's file stage, where some drawn data file carries an index; null where none does. */
fun paimonFileIndexRule(graph: GraphModel, rule: PaimonScanRule?): String? {
    if (graph.nodes.none { it is GraphNode.PaimonDataFileNode && it.hasFileIndex }) return null
    val base = "Files here carry a file index, and an equality on an indexed column is asked of its bloom filter"
    return when {
        rule == null -> "$base: an index embedded in the entry is tested when the scan plans, and a .index file beside the " +
            "data file when the read opens it — a file the latter rules out is still listed by the plan, and yields no row"
        else -> "$base — but a primary-key table's scan tests an embedded index only under deletion vectors" +
            (if (rule.deletionVectors) " (on)" else " (off here)") + ", and its read consults an index only on a split it " +
            "reads without merging: one file alone in its bucket's split, or every file above level 0 under deletion " +
            "vectors or first-row"
    }
}

/** The column types a literal is hashed under — the newest schema's, `name` to Paimon type text. */
fun paimonColumnTypes(graph: GraphModel): Map<String, String> =
    graph.nodes.asSequence().filterIsInstance<GraphNode.PaimonSchemaNode>().maxByOrNull { it.data.id ?: -1 }?.data?.fields
        .orEmpty().mapNotNull { f -> f.name?.let { n -> f.type?.let { t -> n to t } } }.toMap()

/** Whether the entry names an index at all — embedded, or a `.index` file beside the data file. */
val GraphNode.PaimonDataFileNode.hasFileIndex: Boolean
    get() = entry.file?.embeddedFileIndex != null || entry.file?.extraFiles.orEmpty().any { it.endsWith(".index") }

/**
 * [own] with every term the file's indexes rule out marked as proved, and the verdict folded
 * again.
 *
 * A bloom filter answers `=` only — `IN` is a disjunction of them by the time it arrives — and
 * a bitmap index answers `=`, `<>`, `IS NULL` and `IS NOT NULL`, the four `BitmapFileIndex`'s
 * reader visits; nothing is asked where the bounds already settled the term, since a bound's
 * proof needs no second one. A column carrying several indexes is ruled out by any one of them,
 * which is `FileIndexPredicate` and-ing their results. An index the writer left empty holds no
 * non-null value, which `EmptyFileIndexReader` reads as a skip for any equality, and so does
 * this. A term the indexes cannot decide keeps the outcome the bounds gave it, except that an
 * index which *may* hold the value turns "no statistics" into an evaluation — the index looked,
 * and that is the answer.
 */
internal fun applyPaimonFileIndex(
    own: FilePruneResult,
    filter: ScanFilter,
    node: GraphNode.PaimonDataFileNode,
    use: FileIndexUse,
    types: Map<String, String>,
): FilePruneResult {
    val file = node.entry.file ?: return own
    val where = if (file.embeddedFileIndex != null) "embedded in the entry" else "in the .index beside it"
    val read = node.fileIndex.value ?: return own
    val index = read.index ?: return own.copy(note = joinNotes(own.note, "its file index was not read: ${read.error ?: "no index decoded"}"))
    val consulted = if (use == FileIndexUse.AT_PLAN) "tested when the scan plans" else "opened by the read"
    var decided = false
    val outcomes = own.outcomes.map { o ->
        val op = o.predicate.op
        if (o.effect == TermEffect.SKIPS || op !in INDEXED_OPS) return@map o
        val column = o.predicate.column.trim()
        val indexes = index.columns.entries.firstOrNull { it.key.equals(column, ignoreCase = true) }?.value ?: return@map o
        val literal = "'" + o.predicate.literal.trim().removeSurrounding("'").removeSurrounding("\"") + "'"
        val type = types.entries.firstOrNull { it.key.equals(column, ignoreCase = true) }?.value
            ?: node.columnBounds?.firstOrNull { it.name.equals(column, ignoreCase = true) }?.type
            ?: return@map o
        val iceberg = paimonTypeAsIceberg(type) ?: return@map o
        val value = if (op.takesLiteral) parseLiteral(o.predicate.literal, iceberg) ?: return@map o else null
        // One verdict per index of the column that can answer the operator: a proof, or "may".
        val verdicts = indexes.mapNotNull { ix -> indexVerdict(ix, op, column, type, value, literal, where, consulted) }
        val proof = verdicts.firstOrNull { it.first }
        when {
            proof != null -> { decided = true; o.copy(effect = TermEffect.SKIPS, fieldName = column, reason = proof.second) }
            verdicts.isNotEmpty() && o.effect == TermEffect.NOT_EVALUATED -> o.copy(effect = TermEffect.KEEPS, fieldName = column, reason = verdicts.first().second)
            else -> o
        }
    }
    val folded = foldFileOutcomes(filter.pushNegation(), outcomes)
    val byIndex = decided && folded.fate == FileFate.SKIPPED && own.fate != FileFate.SKIPPED
    val note = when {
        byIndex && use == FileIndexUse.AT_READ ->
            "skipped when read, not when planned: the plan lists the file, the read opens its index and none of its rows"
        else -> null
    }
    return folded.copy(note = joinNotes(own.note, note), byIndex = byIndex)
}

private val INDEXED_OPS = setOf(PredicateOp.EQ, PredicateOp.NOT_EQ, PredicateOp.IS_NULL, PredicateOp.IS_NOT_NULL)

/**
 * What one index says about one term: `true` and the reason when it proves the file holds no
 * matching row, `false` and the reason when it looked and could not, null when it cannot answer
 * the operator or the type. A bloom filter answers `=`; a bitmap index answers all four, the
 * way `BitmapFileIndex.Reader` does — `<>` is the value's rows flipped over the row count, so it
 * is empty only when every row holds the value, nulls included in the count.
 */
private fun indexVerdict(
    ix: PaimonColumnIndex,
    op: PredicateOp,
    column: String,
    type: String,
    value: Any?,
    literal: String,
    where: String,
    consulted: String,
): Pair<Boolean, String>? = when (ix.type) {
    PaimonFileIndex.BLOOM_FILTER -> {
        if (op != PredicateOp.EQ) null
        else if (ix.bytes == null) true to "$column's file index ($where) is empty: no non-null $column here"
        else {
            val hash = paimonFastHash(type, value!!)
            val filter = ix.bytes.let(PaimonBloomFilter::decode)
            if (hash == null || filter == null) null
            else if (filter.mightContain(hash)) false to "$column's bloom filter ($where) may hold $literal"
            else true to "$column's bloom filter ($where, $consulted) has no $literal"
        }
    }
    PaimonFileIndex.BITMAP -> {
        if (ix.bytes == null) {
            if (op == PredicateOp.EQ) true to "$column's file index ($where) is empty: no non-null $column here" else null
        } else {
            val bitmap = PaimonBitmapIndex.decode(ix.bytes, type)
            val key = value?.let { paimonBitmapKey(type, it) }
            when {
                bitmap == null -> null
                op == PredicateOp.EQ -> if (key == null) null
                    else if (bitmap.contains(key)) false to "$column's bitmap index ($where) lists $literal"
                    else true to "$column's bitmap index ($where, $consulted) lists no $literal: its ${bitmap.distinctValues} values are not it"
                op == PredicateOp.NOT_EQ -> if (key == null) null
                    else if (bitmap.cardinalityOf(key) == bitmap.rowCount) true to "$column's bitmap index ($where, $consulted): every one of its ${bitmap.rowCount} rows is $literal"
                    else false to "$column's bitmap index ($where) has rows that are not $literal"
                op == PredicateOp.IS_NULL ->
                    if (bitmap.hasNull) false to "$column's bitmap index ($where) has a null bitmap"
                    else true to "$column's bitmap index ($where, $consulted) has no null bitmap"
                else -> // IS_NOT_NULL
                    if (bitmap.distinctValues > 0) false to "$column's bitmap index ($where) lists ${bitmap.distinctValues} values"
                    else true to "$column's bitmap index ($where, $consulted) lists no value: every row is null"
            }
        }
    }
    else -> null
}

internal fun joinNotes(vararg notes: String?): String? = notes.filterNotNull().takeIf { it.isNotEmpty() }?.joinToString("; ")

/**
 * Which of a bucket's files a primary-key batch read reads raw — without merging, and so
 * through the reader that consults a file index — the way `MergeTreeSplitGenerator.splitForBatch`
 * decides it at release-1.3.1 over the files the plan kept.
 *
 * Every file when all are above level 0 with no `-D` row inside and the table has deletion
 * vectors, or is `first-row`, or they all sit at one level. Otherwise the files are cut into
 * sections of intersecting key ranges (`IntervalPartition`: sorted by minimum then maximum key,
 * a file opens a new section when its minimum lies past every maximum so far), the sections are
 * packed in that order into splits of `source.split.target-size` — each weighing its bytes or
 * `source.split.open-file-cost`, whichever is more — and a split is raw only when it holds one
 * file with no `-D` row. **Packed, not sectioned**: `fi`'s two level-0 files share no key and
 * still read as one merge split, because two small sections fit one 128 MiB split; a file reads
 * raw there only when the plan left it alone in its bucket. A file whose key range this cannot
 * order is left in the section it would have joined, which reads more rather than less.
 */
fun paimonRawConvertible(files: List<GraphNode.PaimonDataFileNode>, rule: PaimonScanRule): Set<String> {
    if (files.isEmpty()) return emptySet()
    fun noDeleteRow(f: GraphNode.PaimonDataFileNode) = (f.entry.file?.deleteRowCount ?: 0L) == 0L
    val allAbove0 = files.all { (it.level ?: 0) != 0 && noDeleteRow(it) }
    val oneLevel = files.map { it.level ?: 0 }.toSet().size == 1
    if (allAbove0 && (rule.deletionVectors || rule.engine == "first-row" || oneLevel)) return files.map { it.id }.toSet()

    fun tuple(f: GraphNode.PaimonDataFileNode, min: Boolean): List<Any?>? =
        f.keyBounds?.takeIf { it.isNotEmpty() && it.all { b -> b.decoded } }?.map { if (min) it.min else it.max }
    fun compare(a: List<Any?>?, b: List<Any?>?): Int? {
        if (a == null || b == null || a.size != b.size) return null
        for (i in a.indices) {
            val c = compareValues(a[i], b[i]) ?: return null
            if (c != 0) return c
        }
        return 0
    }
    // Sorted as the generator sorts; a pair this cannot order keeps its input order, which can
    // only merge sections and never split one.
    val sorted = files.sortedWith { x, y ->
        (compare(tuple(x, true), tuple(y, true)) ?: 0).takeIf { it != 0 } ?: (compare(tuple(x, false), tuple(y, false)) ?: 0)
    }
    val sections = mutableListOf<List<GraphNode.PaimonDataFileNode>>()
    var section = mutableListOf<GraphNode.PaimonDataFileNode>()
    var bound: List<Any?>? = null
    for (f in sorted) {
        // Past every maximum so far: the section is closed. An unorderable pair never closes one.
        if (section.isNotEmpty() && (compare(tuple(f, true), bound) ?: 0) > 0) {
            sections += section
            section = mutableListOf()
            bound = null
        }
        section += f
        val max = tuple(f, false)
        if (bound == null || (compare(max, bound) ?: 0) > 0) bound = max ?: bound
    }
    if (section.isNotEmpty()) sections += section
    // BinPacking.packForOrdered: a section joins the open split unless it would overflow it and
    // the split already holds something.
    val splits = mutableListOf<List<GraphNode.PaimonDataFileNode>>()
    var split = mutableListOf<GraphNode.PaimonDataFileNode>()
    var weight = 0L
    for (sec in sections) {
        val w = maxOf(sec.sumOf { it.entry.file?.fileSize ?: 0L }, rule.openFileCostBytes)
        if (weight + w > rule.splitTargetBytes && split.isNotEmpty()) {
            splits += split
            split = mutableListOf()
            weight = 0L
        }
        weight += w
        split += sec
    }
    if (split.isNotEmpty()) splits += split
    return splits.filter { it.size == 1 && noDeleteRow(it[0]) }.map { it[0].id }.toSet()
}
