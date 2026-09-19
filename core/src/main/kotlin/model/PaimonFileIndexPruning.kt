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
    val base = "Files here carry a file index, and a term on an indexed column is asked of it — a bloom filter " +
        "answers an equality as a maybe, a bitmap dictionary answers it exactly, a bit-sliced index or a range bitmap answers every comparison"
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
 * again — over the indexes' *rows*, the way `FileIndexPredicate` folds them.
 *
 * A bloom filter answers `=` only — `IN` is a disjunction of them by the time it arrives — with
 * a maybe; a bitmap index answers `=`, `<>`, `IS NULL` and `IS NOT NULL` with the rows that
 * hold the value; a bit-sliced index and a range bitmap answer those and every comparison with
 * rows too. Nothing
 * is asked where the bounds already settled the term, since a bound's proof needs no second
 * one. A column carrying several indexes is ruled out by any one of them, which is
 * `FileIndexPredicate` and-ing their results; and the terms' rows are then folded up the
 * filter — an `And` intersects, an `Or` unites, a term no index answers is every row — so
 * `n BETWEEN 4 AND 6` skips a file holding 3 and 10 though neither `n >= 4` nor `n <= 6` rules
 * it out alone ([IndexRows]). An index the writer left empty holds no value at all, which
 * `EmptyFileIndexReader` reads as a skip for an equality, a comparison and `IS NOT NULL`, and
 * so does this ([emptyIndexAnswer]). A term the indexes
 * cannot decide keeps the outcome the bounds gave it, except that an index which *may* hold the
 * value turns "no statistics" into an evaluation — the index looked, and that is the answer.
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
    val rowsByTerm = HashMap<ScanPredicate, IndexAnswer>()
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
        // One answer per index of the column that can answer the operator, and-ed the way the
        // leaf visit and-s its readers: a proof, rows, or "may".
        val answers = indexes.mapNotNull { ix -> indexAnswer(ix, op, column, type, value, literal, where, consulted) }
        if (answers.isEmpty()) return@map o
        rowsByTerm[o.predicate] = IndexAnswer(
            answers.fold(IndexRows.Remain as IndexRows) { acc, a -> acc and a.rows },
            (answers.firstOrNull { it.rows is IndexRows.Rows } ?: answers.first()).reason,
        )
        val proof = answers.firstOrNull { it.proves }
        when {
            proof != null -> { decided = true; o.copy(effect = TermEffect.SKIPS, fieldName = column, reason = proof.reason) }
            o.effect == TermEffect.NOT_EVALUATED -> o.copy(effect = TermEffect.KEEPS, fieldName = column, reason = answers.first().reason)
            else -> o
        }
    }
    val normalized = filter.pushNegation()
    var folded = foldFileOutcomes(normalized, outcomes)
    // The rows across the terms: a file no single term ruled out is still skipped when the
    // terms' rows meet in none — a term the bounds settled contributes no rows and stands as
    // proved, one no index answered as every row. The terms then carry the index's count
    // rather than the bounds' range, since the count is what the skip was decided on.
    val together = normalized.indexRows { p ->
        rowsByTerm[p]?.rows ?: if (outcomes.any { it.predicate == p && it.effect == TermEffect.SKIPS }) IndexRows.Skip else IndexRows.Remain
    }
    var joint: String? = null
    if (folded.fate != FileFate.SKIPPED && !together.remains) {
        decided = true
        joint = "the terms' index rows meet in none, though no term rules the file out alone"
        val counted = outcomes.map { o ->
            val answer = rowsByTerm[o.predicate]
            if (answer?.rows is IndexRows.Rows) o.copy(fieldName = o.predicate.column.trim(), reason = answer.reason) else o
        }
        folded = folded.copy(outcomes = counted, fate = FileFate.SKIPPED)
    }
    val byIndex = decided && folded.fate == FileFate.SKIPPED && own.fate != FileFate.SKIPPED
    val note = when {
        byIndex && use == FileIndexUse.AT_READ ->
            "skipped when read, not when planned: the plan lists the file, the read opens its index and none of its rows"
        else -> null
    }
    return folded.copy(note = joinNotes(own.note, joint, note), byIndex = byIndex)
}

/**
 * What an index says about a term, as `FileIndexResult` says it: nothing decided ([Remain],
 * every row may match), no row ([Skip]), or the rows that do ([Rows]). Folded the way the
 * interface's `and` and `or` fold — rows with rows meet or unite, rows with a maybe stay rows
 * under `And` and become a maybe under `Or`, a skip is absorbing under `And` and neutral under
 * `Or` — which is what makes `n >= 4 AND n <= 6` a skip on rows holding 3 and 10.
 */
internal sealed interface IndexRows {
    val remains: Boolean

    object Remain : IndexRows { override val remains get() = true }
    object Skip : IndexRows { override val remains get() = false }
    class Rows(val set: java.util.BitSet) : IndexRows { override val remains get() = !set.isEmpty }

    infix fun and(other: IndexRows): IndexRows = when {
        this is Rows && other is Rows -> Rows((set.clone() as java.util.BitSet).also { it.and(other.set) })
        this is Remain -> other
        this is Skip -> this
        other.remains -> this
        else -> Skip
    }

    infix fun or(other: IndexRows): IndexRows = when {
        this is Rows && other is Rows -> Rows((set.clone() as java.util.BitSet).also { it.or(other.set) })
        this is Remain -> this
        this is Skip -> other
        other.remains -> Remain
        else -> this
    }
}

/** [IndexRows] folded up the tree as `FileIndexPredicate.visit(CompoundPredicate)` folds it; a `Not` nobody rewrote is every row. */
internal fun ScanFilter.indexRows(leaf: (ScanPredicate) -> IndexRows): IndexRows = when (this) {
    is ScanFilter.Term -> leaf(predicate)
    is ScanFilter.And -> terms.fold(IndexRows.Remain as IndexRows) { acc, t -> if (!acc.remains) acc else acc and t.indexRows(leaf) }
    is ScanFilter.Or -> terms.fold(IndexRows.Skip as IndexRows) { acc, t -> acc or t.indexRows(leaf) }
    is ScanFilter.Not -> IndexRows.Remain
}

private val INDEXED_OPS = setOf(
    PredicateOp.EQ, PredicateOp.NOT_EQ, PredicateOp.IS_NULL, PredicateOp.IS_NOT_NULL,
    PredicateOp.LT, PredicateOp.LTE, PredicateOp.GT, PredicateOp.GTE,
)

/** One index's answer to one term: its [rows], the reason in a sentence, and whether it alone proves the file holds no matching row. */
private class IndexAnswer(val rows: IndexRows, val reason: String) {
    val proves: Boolean get() = !rows.remains
}

/**
 * What an index the writer left empty says: `EmptyFileIndexReader` skips the file for `=`, `IN`,
 * every comparison and `IS NOT NULL` — no value was ever written to the column — and answers
 * `<>` and `IS NULL` with the default, a maybe.
 */
private fun emptyIndexAnswer(op: PredicateOp, column: String, where: String): IndexAnswer? =
    if (op == PredicateOp.NOT_EQ || op == PredicateOp.IS_NULL) null
    else IndexAnswer(IndexRows.Skip, "$column's file index ($where) is empty: no $column was ever written here")

/**
 * What one index says about one term, or null when it cannot answer the operator or the type.
 * A bloom filter answers `=` with a maybe or a skip; a bitmap index answers `=`, `<>` and the
 * null tests with rows, the way `BitmapFileIndex.Reader` does — `<>` is the value's rows flipped
 * over the row count, so it is empty only when every row holds the value, nulls included in the
 * count; a bit-sliced index answers those and the four comparisons with rows too
 * (`BitSliceIndexBitmapFileIndex.Reader`), its `<>` over the non-null rows alone; a range bitmap
 * answers the same set, on every type the writer takes (`RangeBitmapFileIndex.Reader`).
 */
private fun indexAnswer(
    ix: PaimonColumnIndex,
    op: PredicateOp,
    column: String,
    type: String,
    value: Any?,
    literal: String,
    where: String,
    consulted: String,
): IndexAnswer? = when (ix.type) {
    PaimonFileIndex.BLOOM_FILTER -> {
        if (ix.bytes == null) emptyIndexAnswer(op, column, where)
        else if (op != PredicateOp.EQ) null
        else {
            val hash = paimonFastHash(type, value!!)
            val filter = ix.bytes.let(PaimonBloomFilter::decode)
            if (hash == null || filter == null) null
            else if (filter.mightContain(hash)) IndexAnswer(IndexRows.Remain, "$column's bloom filter ($where) may hold $literal")
            else IndexAnswer(IndexRows.Skip, "$column's bloom filter ($where, $consulted) has no $literal")
        }
    }
    PaimonFileIndex.BITMAP -> {
        if (ix.bytes == null) emptyIndexAnswer(op, column, where)
        else {
            val bitmap = PaimonBitmapIndex.decode(ix.bytes, type)
            val key = value?.let { paimonBitmapKey(type, it) }
            val rows = if (bitmap == null || (op.takesLiteral && key == null)) null else bitmap.rowsMatching(op, key)
            when {
                rows == null -> null
                op == PredicateOp.EQ ->
                    if (rows.isEmpty) IndexAnswer(IndexRows.Rows(rows), "$column's bitmap index ($where, $consulted) lists no $literal: its ${bitmap!!.distinctValues} values are not it")
                    else IndexAnswer(IndexRows.Rows(rows), "$column's bitmap index ($where) lists $literal")
                op == PredicateOp.NOT_EQ ->
                    if (rows.isEmpty) IndexAnswer(IndexRows.Rows(rows), "$column's bitmap index ($where, $consulted): every one of its ${bitmap!!.rowCount} rows is $literal")
                    else IndexAnswer(IndexRows.Rows(rows), "$column's bitmap index ($where) has rows that are not $literal")
                op == PredicateOp.IS_NULL ->
                    if (rows.isEmpty) IndexAnswer(IndexRows.Rows(rows), "$column's bitmap index ($where, $consulted) has no null bitmap")
                    else IndexAnswer(IndexRows.Rows(rows), "$column's bitmap index ($where) has a null bitmap")
                else -> // IS_NOT_NULL
                    if (rows.isEmpty) IndexAnswer(IndexRows.Rows(rows), "$column's bitmap index ($where, $consulted) lists no value: every row is null")
                    else IndexAnswer(IndexRows.Rows(rows), "$column's bitmap index ($where) lists ${bitmap!!.distinctValues} values")
            }
        }
    }
    PaimonFileIndex.BSI -> {
        if (ix.bytes == null) emptyIndexAnswer(op, column, where)
        else {
            val bsi = PaimonBsiIndex.decode(ix.bytes)
            val long = value?.let { paimonBsiValue(type, it) }
            val rows = if (bsi == null || (op.takesLiteral && long == null)) null else bsi.rowsMatching(op, long)
            rowsAnswer(rows, bsi?.rowCount, "bit-sliced index", op, column, literal, where, consulted)
        }
    }
    PaimonFileIndex.RANGE_BITMAP -> {
        if (ix.bytes == null) emptyIndexAnswer(op, column, where)
        else {
            val range = PaimonRangeBitmapIndex.decode(ix.bytes, type)
            val key = value?.let { paimonRangeBitmapKey(type, it) }
            val rows = if (range == null || (op.takesLiteral && key == null)) null else range.rowsMatching(op, key)
            rowsAnswer(rows, range?.rowCount, "range bitmap", op, column, literal, where, consulted)
        }
    }
    else -> null
}

/** An index that answered [op] with the rows it keeps, or null where it could not answer. */
private fun rowsAnswer(
    rows: java.util.BitSet?,
    rowCount: Int?,
    kind: String,
    op: PredicateOp,
    column: String,
    literal: String,
    where: String,
    consulted: String,
): IndexAnswer? {
    if (rows == null || rowCount == null) return null
    val term = when (op) {
        PredicateOp.IS_NULL -> "null"
        PredicateOp.IS_NOT_NULL -> "non-null"
        else -> "${op.symbol} $literal"
    }
    return if (rows.isEmpty) IndexAnswer(IndexRows.Rows(rows), "$column's $kind ($where, $consulted) has no row $term among its $rowCount")
    else IndexAnswer(IndexRows.Rows(rows), "$column's $kind ($where) has ${rows.cardinality()} of its $rowCount rows $term")
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
/**
 * `IntervalPartition.partition()` at release-1.3.1, up to its sections: the files sorted by
 * minimum key then maximum, and a file opening a new section when its minimum lies past every
 * maximum so far — so a section is a chain of intersecting key ranges, and a section of one file
 * is a file no other overlaps. A pair this cannot order — a range missing, or a type
 * `compareValues` does not read — keeps its input order and never closes a section, which can
 * only merge sections and never split one: more read, more rewritten, never less.
 */
internal fun <T> paimonIntervalSections(files: List<T>, minKey: (T) -> List<Any?>?, maxKey: (T) -> List<Any?>?): List<List<T>> {
    fun compare(a: List<Any?>?, b: List<Any?>?): Int? {
        if (a == null || b == null || a.size != b.size) return null
        for (i in a.indices) {
            val c = compareValues(a[i], b[i]) ?: return null
            if (c != 0) return c
        }
        return 0
    }
    val sorted = files.sortedWith { x, y ->
        (compare(minKey(x), minKey(y)) ?: 0).takeIf { it != 0 } ?: (compare(maxKey(x), maxKey(y)) ?: 0)
    }
    val sections = mutableListOf<List<T>>()
    var section = mutableListOf<T>()
    var bound: List<Any?>? = null
    for (f in sorted) {
        if (section.isNotEmpty() && (compare(minKey(f), bound) ?: 0) > 0) {
            sections += section
            section = mutableListOf()
            bound = null
        }
        section += f
        val max = maxKey(f)
        if (bound == null || (compare(max, bound) ?: 0) > 0) bound = max ?: bound
    }
    if (section.isNotEmpty()) sections += section
    return sections
}

fun paimonRawConvertible(files: List<GraphNode.PaimonDataFileNode>, rule: PaimonScanRule): Set<String> {
    if (files.isEmpty()) return emptySet()
    fun noDeleteRow(f: GraphNode.PaimonDataFileNode) = (f.entry.file?.deleteRowCount ?: 0L) == 0L
    val allAbove0 = files.all { (it.level ?: 0) != 0 && noDeleteRow(it) }
    val oneLevel = files.map { it.level ?: 0 }.toSet().size == 1
    if (allAbove0 && (rule.deletionVectors || rule.engine == "first-row" || oneLevel)) return files.map { it.id }.toSet()

    fun tuple(f: GraphNode.PaimonDataFileNode, min: Boolean): List<Any?>? =
        f.keyBounds?.takeIf { it.isNotEmpty() && it.all { b -> b.decoded } }?.map { if (min) it.min else it.max }
    val sections = paimonIntervalSections(files, { tuple(it, true) }, { tuple(it, false) })
    val splits = packForOrdered(sections, rule.splitTargetBytes) { sec -> maxOf(sec.sumOf { it.entry.file?.fileSize ?: 0L }, rule.openFileCostBytes) }
        .map { it.flatten() }
    return splits.filter { it.size == 1 && noDeleteRow(it[0]) }.map { it[0].id }.toSet()
}
