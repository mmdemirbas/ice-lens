// Prints which data files a Paimon batch scan opens for a filter, on the checked-in
// primary-key tables — the oracle PaimonPrimaryKeyScanPruningTest holds the pruning verdicts of a
// primary-key table to. Read-only: it plans, and reads nothing.
//
// Why a plan and not a query: what a filtered read RETURNS is decided after the merge, and the
// pruning section says which files the scan OPENS, which for a primary-key table is a different
// rule (KeyValueFileStoreScan at release-1.3.1): a predicate on the trimmed primary key prunes a
// file by its _KEY_STATS on its own; the whole predicate is applied to a file's _VALUE_STATS only
// when the bucket's files cannot overlap (all at one level above 0), or when the table skips
// level 0 (first-row, deletion vectors); otherwise a bucket is read whole if any of its files may
// match and skipped whole if none may — and never pruned by value at all under partial-update or
// aggregation without deletion vectors. PrimaryKeyFileStoreTable.nonPartitionFilterConsumer
// carries the reason: "data file 1: insert key = a, value = 1; data file 2: update key = a,
// value = 2; filter: value = 1 — if we perform filter push down on values, data file 1 will be
// chosen, but data file 2 will be ignored, and the final result will be key = a, value = 1 while
// the correct result is an empty set".
//
// Run with the Paimon Spark 3.5 runtime jar, version 1.3, over copies of the tables:
//
//   WH=$PWD/tmp/plans-wh; rm -rf "$WH"; mkdir -p "$WH/db.db"
//   for t in pc lk pu ag dv sm pt fa fi ft fb fbs frb pse pkr; do cp -R example/paimon/db.db/$t "$WH/db.db/$t"; done
//   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
//   docker run --rm --entrypoint bash \
//     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
//     -v "$PWD/docs/fixtures/paimon-scan-plans.scala:/tmp/plans.scala:ro" \
//     tabulario/spark-iceberg \
//     -c "/opt/spark/bin/spark-shell --master 'local[1]' --jars /opt/paimon-spark.jar \
//           --conf spark.ui.enabled=false -I /tmp/plans.scala <<< 'System.exit(0)'"
//
// Observed (2026-09-14), one line per file the plan opens, `<partition> b<bucket> L<level> <file>`:
// see PaimonPrimaryKeyScanPruningTest, which carries the file names per case.

import org.apache.paimon.catalog.CatalogContext
import org.apache.paimon.data.BinaryString
import org.apache.paimon.fileindex.FileIndexPredicate
import org.apache.paimon.fs.Path
import org.apache.paimon.options.Options
import org.apache.paimon.predicate.{Predicate, PredicateBuilder}
import org.apache.paimon.table.FileStoreTableFactory
import org.apache.paimon.table.source.DataSplit
import scala.collection.JavaConverters._

def plan(name: String, label: String, mk: (PredicateBuilder, org.apache.paimon.types.RowType) => Predicate): Unit = {
  val options = new Options()
  options.set("path", s"/wh/db.db/$name")
  val table = FileStoreTableFactory.create(CatalogContext.create(options))
  val rowType = table.rowType()
  val builder = new PredicateBuilder(rowType)
  val predicate = mk(builder, rowType)
  val readBuilder = table.newReadBuilder()
  val scan = (if (predicate == null) readBuilder else readBuilder.withFilter(predicate)).newScan()
  val splits = scan.plan().splits().asScala.map(_.asInstanceOf[DataSplit])
  println(s"-- $name: $label")
  for (s <- splits; f <- s.dataFiles().asScala) println(s"${s.partition()} b${s.bucket()} L${f.level()} ${f.fileName()}")
  println(s"-- $name: $label = ${splits.map(_.dataFiles().size()).sum} files")
}

// The file index, asked the way the read asks it (FileIndexEvaluator → FileIndexPredicate over
// the embedded bytes or the .index file beside the data file) for every file the plan kept, with
// whether the split is read raw — the only read that consults an index. Printed per file as
// `<file> raw=<bool> index=<REMAIN|SKIP|none>`.
def indexes(name: String, label: String, mk: (PredicateBuilder, org.apache.paimon.types.RowType) => Predicate): Unit = {
  val options = new Options()
  options.set("path", s"/wh/db.db/$name")
  val table = FileStoreTableFactory.create(CatalogContext.create(options))
  val rowType = table.rowType()
  val predicate = mk(new PredicateBuilder(rowType), rowType)
  val readBuilder = table.newReadBuilder()
  val scan = (if (predicate == null) readBuilder else readBuilder.withFilter(predicate)).newScan()
  val splits = scan.plan().splits().asScala.map(_.asInstanceOf[DataSplit])
  println(s"-- $name: $label [index]")
  for (s <- splits; f <- s.dataFiles().asScala) {
    val embedded = f.embeddedIndex()
    val indexFile = f.extraFiles().asScala.find(_.endsWith(".index"))
    val verdict =
      if (embedded != null) { val p = new FileIndexPredicate(embedded, rowType); try { if (p.evaluate(predicate).remain()) "REMAIN" else "SKIP" } finally p.close() }
      else if (indexFile.isDefined) { val p = new FileIndexPredicate(new Path(s.bucketPath(), indexFile.get), table.fileIO(), rowType); try { if (p.evaluate(predicate).remain()) "REMAIN" else "SKIP" } finally p.close() }
      else "none"
    println(s"${f.fileName()} raw=${s.rawConvertible()} index=$verdict")
  }
}

// The index alone, asked for EVERY data file of the table whatever the plan did with it — the
// oracle for the index decoders, since the plan settles most negatives by the file's statistics
// first. Printed per file as `<file> index=<REMAIN|SKIP|none>`.
def indexAll(name: String, label: String, mk: (PredicateBuilder, org.apache.paimon.types.RowType) => Predicate): Unit = {
  val options = new Options()
  options.set("path", s"/wh/db.db/$name")
  val table = FileStoreTableFactory.create(CatalogContext.create(options))
  val rowType = table.rowType()
  val predicate = mk(new PredicateBuilder(rowType), rowType)
  val splits = table.newReadBuilder().newScan().plan().splits().asScala.map(_.asInstanceOf[DataSplit])
  println(s"-- $name: $label [index-all]")
  for (s <- splits; f <- s.dataFiles().asScala) {
    val embedded = f.embeddedIndex()
    val indexFile = f.extraFiles().asScala.find(_.endsWith(".index"))
    val verdict =
      if (embedded != null) { val p = new FileIndexPredicate(embedded, rowType); try { if (p.evaluate(predicate).remain()) "REMAIN" else "SKIP" } finally p.close() }
      else if (indexFile.isDefined) { val p = new FileIndexPredicate(new Path(s.bucketPath(), indexFile.get), table.fileIO(), rowType); try { if (p.evaluate(predicate).remain()) "REMAIN" else "SKIP" } finally p.close() }
      else "none"
    println(s"${f.fileName()} index=$verdict")
  }
}

// The index's own ROWS, asked for every data file whatever the plan did with it: a bitmap-shaped
// result (a bitmap or range-bitmap index, or a bsi) prints its cardinality, which is the oracle
// for a decoder that answers per row; any other result prints REMAIN or SKIP as `indexAll` does.
// Printed per file as `<file> rows=<n>` or `<file> index=<REMAIN|SKIP|none>`.
def indexRows(name: String, label: String, mk: (PredicateBuilder, org.apache.paimon.types.RowType) => Predicate): Unit = {
  val options = new Options()
  options.set("path", s"/wh/db.db/$name")
  val table = FileStoreTableFactory.create(CatalogContext.create(options))
  val rowType = table.rowType()
  val predicate = mk(new PredicateBuilder(rowType), rowType)
  val splits = table.newReadBuilder().newScan().plan().splits().asScala.map(_.asInstanceOf[DataSplit])
  println(s"-- $name: $label [index-rows]")
  def verdict(r: org.apache.paimon.fileindex.FileIndexResult): String = r match {
    case b: org.apache.paimon.fileindex.bitmap.BitmapIndexResult => s"rows=${b.get().getCardinality}"
    case other => if (other.remain()) "index=REMAIN" else "index=SKIP"
  }
  for (s <- splits; f <- s.dataFiles().asScala) {
    val embedded = f.embeddedIndex()
    val indexFile = f.extraFiles().asScala.find(_.endsWith(".index"))
    val out =
      if (embedded != null) { val p = new FileIndexPredicate(embedded, rowType); try verdict(p.evaluate(predicate)) finally p.close() }
      else if (indexFile.isDefined) { val p = new FileIndexPredicate(new Path(s.bucketPath(), indexFile.get), table.fileIO(), rowType); try verdict(p.evaluate(predicate)) finally p.close() }
      else "index=none"
    println(s"${f.fileName()} $out")
  }
}

def lit(v: Any): AnyRef = v match {
  case s: String => BinaryString.fromString(s)
  case i: Int => Int.box(i)
  case l: Long => Long.box(l)
  case t: java.time.LocalDateTime => org.apache.paimon.data.Timestamp.fromLocalDateTime(t)
  case i: java.time.Instant => org.apache.paimon.data.Timestamp.fromInstant(i)
  case d: java.time.LocalDate => Int.box(d.toEpochDay.toInt)
  case d: Double => Double.box(d)
  case b: Boolean => Boolean.box(b)
  case other => other.asInstanceOf[AnyRef]
}
def ldt(s: String) = java.time.LocalDateTime.parse(s.replace(' ', 'T'))
def inst(s: String) = java.time.Instant.parse(s.replace(' ', 'T'))
def date(s: String) = java.time.LocalDate.parse(s)

def isEq(name: String, v: Any) = (b: PredicateBuilder, t: org.apache.paimon.types.RowType) => b.equal(t.getFieldIndex(name), lit(v))
def and(ps: ((PredicateBuilder, org.apache.paimon.types.RowType) => Predicate)*) =
  (b: PredicateBuilder, t: org.apache.paimon.types.RowType) => PredicateBuilder.and(ps.map(_(b, t)).asJava)
def isNotEq(name: String, v: Any) = (b: PredicateBuilder, t: org.apache.paimon.types.RowType) => b.notEqual(t.getFieldIndex(name), lit(v))
def isIn(name: String, vs: Any*) = (b: PredicateBuilder, t: org.apache.paimon.types.RowType) => b.in(t.getFieldIndex(name), vs.map(lit).asJava)
def isNull(name: String) = (b: PredicateBuilder, t: org.apache.paimon.types.RowType) => b.isNull(t.getFieldIndex(name))
def isNotNull(name: String) = (b: PredicateBuilder, t: org.apache.paimon.types.RowType) => b.isNotNull(t.getFieldIndex(name))
def isLt(name: String, v: Any) = (b: PredicateBuilder, t: org.apache.paimon.types.RowType) => b.lessThan(t.getFieldIndex(name), lit(v))
def isLte(name: String, v: Any) = (b: PredicateBuilder, t: org.apache.paimon.types.RowType) => b.lessOrEqual(t.getFieldIndex(name), lit(v))
def isGt(name: String, v: Any) = (b: PredicateBuilder, t: org.apache.paimon.types.RowType) => b.greaterThan(t.getFieldIndex(name), lit(v))
def isGte(name: String, v: Any) = (b: PredicateBuilder, t: org.apache.paimon.types.RowType) => b.greaterOrEqual(t.getFieldIndex(name), lit(v))
def between(name: String, lo: Any, hi: Any) = (b: PredicateBuilder, t: org.apache.paimon.types.RowType) => b.between(t.getFieldIndex(name), lit(lo), lit(hi))
def or(ps: ((PredicateBuilder, org.apache.paimon.types.RowType) => Predicate)*) =
  (b: PredicateBuilder, t: org.apache.paimon.types.RowType) => PredicateBuilder.or(ps.map(_(b, t)).asJava)
// A DECIMAL(10,2) literal, the column's own precision and scale — fbs's amt.
def dec(s: String) = org.apache.paimon.data.Decimal.fromBigDecimal(new java.math.BigDecimal(s), 10, 2)

// pc: deduplicate, a level-5 file (keys 1..5) and two level-0 files (6, 7) — overlapping levels
plan("pc", "no filter", (b, t) => null)
plan("pc", "v = 'g'", isEq("v", "g"))
plan("pc", "v = 'z'", isEq("v", "z"))
plan("pc", "k = 7", isEq("k", 7))
plan("pc", "k = 7 AND v = 'a'", and(isEq("k", 7), isEq("v", "a")))
plan("pc", "k = 3", isEq("k", 3))

// lk: deduplicate under changelog-producer = lookup — every write compacted up
plan("lk", "no filter", (b, t) => null)
plan("lk", "v = 'b'", isEq("v", "b"))
plan("lk", "v = 'B'", isEq("v", "B"))
plan("lk", "k = 4", isEq("k", 4))

// pu: partial-update without deletion vectors — a value bound is never consulted
plan("pu", "no filter", (b, t) => null)
plan("pu", "a = 'zzz'", isEq("a", "zzz"))
plan("pu", "k = 1", isEq("k", 1))

// ag: aggregation, the same rule
plan("ag", "no filter", (b, t) => null)
plan("ag", "latest = 'zzz'", isEq("latest", "zzz"))

// dv: deletion vectors — level 0 skipped, the value filter per file
plan("dv", "no filter", (b, t) => null)
plan("dv", "v = 'v2'", isEq("v", "v2"))
plan("dv", "k = 1500", isEq("k", 1500))
plan("dv", "v = 'zzz'", isEq("v", "zzz"))

// sm: fields.v.stats-mode = none — no value bound for v, key bounds still
plan("sm", "v = 'zzz'", isEq("v", "zzz"))
plan("sm", "k = 99", isEq("k", 99))

// pt: partitioned, k the trimmed key
plan("pt", "no filter", (b, t) => null)
plan("pt", "k = 3", isEq("k", 3))
plan("pt", "v = 'zzz'", isEq("v", "zzz"))

// fa: an append table with a bloom filter on k and v — the first file's index beside it, the
// second's embedded. The scan tests the embedded one; the read tests both.
plan("fa", "no filter", (b, t) => null)
plan("fa", "v = 'dog'", isEq("v", "dog"))
plan("fa", "k = 5", isEq("k", 5))
plan("fa", "v = 'delta'", isEq("v", "delta"))
plan("fa", "k = 2", isEq("k", 2))
plan("fa", "v = 'the quick brown fox jumps over the lazy dog'", isEq("v", "the quick brown fox jumps over the lazy dog"))
plan("fa", "v = 'bob'", isEq("v", "bob"))
plan("fa", "k = 5 AND v = 'delta'", and(isEq("k", 5), isEq("v", "delta")))
indexes("fa", "v = 'dog'", isEq("v", "dog"))
indexes("fa", "k = 5", isEq("k", 5))
indexes("fa", "v = 'delta'", isEq("v", "delta"))
indexes("fa", "k = 2", isEq("k", 2))
indexes("fa", "v = 'the quick brown fox jumps over the lazy dog'", isEq("v", "the quick brown fox jumps over the lazy dog"))
indexes("fa", "v = 'bob'", isEq("v", "bob"))

// fi: the primary-key twin without deletion vectors — the scan never tests its index, and its
// two level-0 files share no key range, so each is a raw split whose read does
plan("fi", "no filter", (b, t) => null)
plan("fi", "v = 'dog'", isEq("v", "dog"))
plan("fi", "k = 4 AND v = 'dog'", and(isEq("k", 4), isEq("v", "dog")))
plan("fi", "k = 2 AND v = 'bob'", and(isEq("k", 2), isEq("v", "bob")))
indexes("fi", "v = 'dog'", isEq("v", "dog"))
indexes("fi", "k = 4 AND v = 'dog'", and(isEq("k", 4), isEq("v", "dog")))
indexes("fi", "k = 2 AND v = 'bob'", and(isEq("k", 2), isEq("v", "bob")))
indexes("fi", "v = 'beta'", isEq("v", "beta"))

// ft: an append table with a bloom filter on a TIMESTAMP(6), a TIMESTAMP(6) WITH LOCAL TIME ZONE
// and a DATE column, embedded — the temporal hashes. A value present on each column remains; the
// same instant at millisecond precision is a different microsecond count and is skipped.
plan("ft", "no filter", (b, t) => null)
plan("ft", "ts = 2024-03-05 10:00:00.123456", isEq("ts", ldt("2024-03-05 10:00:00.123456")))
plan("ft", "ts = 2024-03-05 10:00:00.123", isEq("ts", ldt("2024-03-05 10:00:00.123")))
plan("ft", "ts = 2024-03-06 11:30:00", isEq("ts", ldt("2024-03-06 11:30:00")))
plan("ft", "ts = 2024-03-07 00:00:00.000001", isEq("ts", ldt("2024-03-07 00:00:00.000001")))
plan("ft", "ts = 2024-03-07 00:00:00", isEq("ts", ldt("2024-03-07 00:00:00")))
plan("ft", "lz = 2024-03-05 10:00:00.123456Z", isEq("lz", inst("2024-03-05 10:00:00.123456Z")))
plan("ft", "lz = 2024-03-06 11:30:00Z", isEq("lz", inst("2024-03-06 11:30:00Z")))
plan("ft", "lz = 2024-03-07 00:00:00.000001Z", isEq("lz", inst("2024-03-07 00:00:00.000001Z")))
plan("ft", "lz = 2024-03-06 11:30:00.001Z", isEq("lz", inst("2024-03-06 11:30:00.001Z")))
plan("ft", "d = 2024-03-05", isEq("d", date("2024-03-05")))
plan("ft", "d = 2024-03-06", isEq("d", date("2024-03-06")))
plan("ft", "d = 2024-03-07", isEq("d", date("2024-03-07")))
indexes("ft", "d = 2024-03-08", isEq("d", date("2024-03-08")))
indexes("ft", "ts = 2024-03-05 10:00:00.123", isEq("ts", ldt("2024-03-05 10:00:00.123")))

// fb: an append table with a bitmap index on c and n and a bloom filter on n — file 1's index
// embedded (red, green, a null), files 2 (all red) and 3 (all null) with a .index beside them.
// The dictionary answers `=` exactly and `<>` / IS NULL / IS NOT NULL too.
// pse: an append table whose `v` became `label` and gained `w` after the first file — a filter
// on the new name is evolved to the old file's schema by field id, and one on a column the old
// file's schema lacks keeps the file (`w IS NULL` reads it; `w = 7` cannot rule it out).
plan("pse", "no filter", (b, t) => null)
plan("pse", "label = 'a'", isEq("label", "a"))
plan("pse", "label = 'd'", isEq("label", "d"))
plan("pse", "w = 7", isEq("w", 7))
plan("pse", "w IS NULL", isNull("w"))
plan("pse", "k = 4", isEq("k", 4))
// pkr: a primary key renamed between writes — `_KEY_k` in the first file, `_KEY_id` in the rest.
plan("pkr", "no filter", (b, t) => null)
plan("pkr", "id = 1", isEq("id", 1))
plan("pkr", "id = 3", isEq("id", 3))
plan("pkr", "v = 'a'", isEq("v", "a"))
plan("fb", "no filter", (b, t) => null)
plan("fb", "c = 'red'", isEq("c", "red"))
plan("fb", "c = 'green'", isEq("c", "green"))
plan("fb", "c = 'orange'", isEq("c", "orange"))
plan("fb", "c <> 'red'", isNotEq("c", "red"))
plan("fb", "c IS NULL", isNull("c"))
plan("fb", "c IS NOT NULL", isNotNull("c"))
plan("fb", "n = 5", isEq("n", 5))
plan("fb", "n = 9", isEq("n", 9))
plan("fb", "n IN (2, 6)", isIn("n", 2, 6))
plan("fb", "n = 5 AND c = 'green'", and(isEq("n", 5), isEq("c", "green")))
plan("fb", "n <> 7", isNotEq("n", 7))
indexes("fb", "c = 'red'", isEq("c", "red"))
indexes("fb", "c = 'orange'", isEq("c", "orange"))
indexes("fb", "c <> 'red'", isNotEq("c", "red"))
indexes("fb", "c IS NULL", isNull("c"))
indexes("fb", "c IS NOT NULL", isNotNull("c"))
indexes("fb", "n = 5", isEq("n", 5))
indexes("fb", "n IN (2, 6)", isIn("n", 2, 6))
indexes("fb", "n <> 7", isNotEq("n", 7))
indexAll("fb", "c = 'red'", isEq("c", "red"))
indexAll("fb", "c = 'orange'", isEq("c", "orange"))
indexAll("fb", "c <> 'red'", isNotEq("c", "red"))
indexAll("fb", "c <> 'green'", isNotEq("c", "green"))
indexAll("fb", "c IS NULL", isNull("c"))
indexAll("fb", "c IS NOT NULL", isNotNull("c"))
indexAll("fb", "n = 9", isEq("n", 9))
indexAll("fb", "n = 5", isEq("n", 5))
indexAll("fb", "n <> 7", isNotEq("n", 7))
indexAll("fb", "n <> 5", isNotEq("n", 5))
indexAll("fb", "n IN (2, 6)", isIn("n", 2, 6))
indexAll("fb", "n IS NULL", isNull("n"))
indexAll("fb", "c IN ('green', 'orange')", isIn("c", "green", "orange"))

// fbs: an append table with a bit-sliced index on n (INT), amt (DECIMAL(10,2)), d (DATE) and ts
// (TIMESTAMP(6)) — file 1 embedded (-5, 3, 10, null), file 2 a thousand rows in a .index
// (n 100..1099), file 3 all 7 and file 4 all null with .index files. A BSI answers every
// comparison per row, so `n BETWEEN 4 AND 6` skips file 1 whose bounds are -5..10, and
// `FileIndexPredicate` ands the terms' bitmaps, so `n = 3 AND amt = 99.99` skips it too.
plan("fbs", "no filter", (b, t) => null)
plan("fbs", "n < 0", isLt("n", 0))
plan("fbs", "n BETWEEN 4 AND 6", between("n", 4, 6))
plan("fbs", "n IN (4, 5, 6)", isIn("n", 4, 5, 6))
plan("fbs", "n BETWEEN 4 AND 8", between("n", 4, 8))
plan("fbs", "n <> 7", isNotEq("n", 7))
plan("fbs", "n IS NULL", isNull("n"))
plan("fbs", "amt BETWEEN 1 AND 50", between("amt", dec("1"), dec("50")))
plan("fbs", "d BETWEEN 2024-03-02 AND 2024-03-04", between("d", date("2024-03-02"), date("2024-03-04")))
plan("fbs", "ts BETWEEN 2024-03-01 10:00:00.000002 AND 2024-03-01 10:00:00.000003", between("ts", ldt("2024-03-01 10:00:00.000002"), ldt("2024-03-01 10:00:00.000003")))
plan("fbs", "n = 3 AND amt = 99.99", and(isEq("n", 3), isEq("amt", dec("99.99"))))
plan("fbs", "n = 3 OR amt = 99.99", or(isEq("n", 3), isEq("amt", dec("99.99"))))
indexAll("fbs", "n < 0", isLt("n", 0))
indexAll("fbs", "n < -5", isLt("n", -5))
indexAll("fbs", "n <= -5", isLte("n", -5))
indexAll("fbs", "n BETWEEN 4 AND 6", between("n", 4, 6))
indexAll("fbs", "n >= 4 AND n <= 6", and(isGte("n", 4), isLte("n", 6)))
indexAll("fbs", "n BETWEEN 4 AND 8", between("n", 4, 8))
indexAll("fbs", "n > 1000", isGt("n", 1000))
indexAll("fbs", "n >= 1099", isGte("n", 1099))
indexAll("fbs", "n > 1099", isGt("n", 1099))
indexAll("fbs", "n = 7", isEq("n", 7))
indexAll("fbs", "n <> 7", isNotEq("n", 7))
indexAll("fbs", "n IS NULL", isNull("n"))
indexAll("fbs", "n IS NOT NULL", isNotNull("n"))
indexAll("fbs", "n IN (3, 10)", isIn("n", 3, 10))
indexAll("fbs", "n IN (4, 5, 6)", isIn("n", 4, 5, 6))
indexAll("fbs", "n = 3 AND amt = 99.99", and(isEq("n", 3), isEq("amt", dec("99.99"))))
indexAll("fbs", "n = 3 OR amt = 99.99", or(isEq("n", 3), isEq("amt", dec("99.99"))))
indexAll("fbs", "n = 7 AND amt = 7.00", and(isEq("n", 7), isEq("amt", dec("7.00"))))
indexAll("fbs", "amt > 50", isGt("amt", dec("50")))
indexAll("fbs", "amt < 0", isLt("amt", dec("0")))
indexAll("fbs", "amt = 7.00", isEq("amt", dec("7.00")))
indexAll("fbs", "amt BETWEEN 1 AND 50", between("amt", dec("1"), dec("50")))
indexAll("fbs", "d < 2024-03-05", isLt("d", date("2024-03-05")))
indexAll("fbs", "d > 2024-03-07", isGt("d", date("2024-03-07")))
indexAll("fbs", "d BETWEEN 2024-03-02 AND 2024-03-04", between("d", date("2024-03-02"), date("2024-03-04")))
indexAll("fbs", "ts < 2024-03-01 10:00:00.000005", isLt("ts", ldt("2024-03-01 10:00:00.000005")))
indexAll("fbs", "ts >= 2024-03-01 10:00:00.000009", isGte("ts", ldt("2024-03-01 10:00:00.000009")))
indexAll("fbs", "ts BETWEEN 2024-03-01 10:00:00.000002 AND 2024-03-01 10:00:00.000003", between("ts", ldt("2024-03-01 10:00:00.000002"), ldt("2024-03-01 10:00:00.000003")))

// frb: an append table with a range bitmap on n (INT), s (STRING), amt (DECIMAL(10,2)), d (DATE),
// ts (TIMESTAMP(6)), x (DOUBLE) and b (BOOLEAN) — file 1 embedded (n -5, 3, 10, null, 3; s alpha,
// bravo, charlie, null, bravo), file 2 a thousand rows in a .index (n 100..1099, s v0000..v0999,
// the n and s dictionaries cut into 32- and 64-byte chunks), file 3 every row 7 / 'seven' and
// file 4 every value null over three rows. A range bitmap orders any of these types, so `s >
// 'bravo'` and `x < 0` are answered per row where a bsi refuses the column; the rows are printed
// so the decoder is held to the count, not only to the verdict — and file 4 under `IS NULL` is
// where `RangeBitmap.isNull` at cardinality 0 answers two rows of three.
plan("frb", "no filter", (b, t) => null)
plan("frb", "n BETWEEN 4 AND 6", between("n", 4, 6))
plan("frb", "s > 'bravo'", isGt("s", "bravo"))
plan("frb", "s BETWEEN 'b' AND 'c'", between("s", "b", "c"))
plan("frb", "x < 0", isLt("x", 0.0))
plan("frb", "b = false", isEq("b", false))
plan("frb", "n = 3 AND s = 'charlie'", and(isEq("n", 3), isEq("s", "charlie")))
plan("frb", "n <> 7", isNotEq("n", 7))
plan("frb", "s IS NULL", isNull("s"))
indexRows("frb", "n < 0", isLt("n", 0))
indexRows("frb", "n <= -5", isLte("n", -5))
indexRows("frb", "n < -5", isLt("n", -5))
indexRows("frb", "n = 3", isEq("n", 3))
indexRows("frb", "n <> 3", isNotEq("n", 3))
indexRows("frb", "n = 4", isEq("n", 4))
indexRows("frb", "n > 3", isGt("n", 3))
indexRows("frb", "n >= 4", isGte("n", 4))
indexRows("frb", "n > 4", isGt("n", 4))
indexRows("frb", "n <= 6", isLte("n", 6))
indexRows("frb", "n < 4", isLt("n", 4))
indexRows("frb", "n BETWEEN 4 AND 6", between("n", 4, 6))
indexRows("frb", "n BETWEEN 4 AND 8", between("n", 4, 8))
indexRows("frb", "n > 1000", isGt("n", 1000))
indexRows("frb", "n >= 1099", isGte("n", 1099))
indexRows("frb", "n > 1099", isGt("n", 1099))
indexRows("frb", "n BETWEEN 250 AND 749", between("n", 250, 749))
indexRows("frb", "n = 7", isEq("n", 7))
indexRows("frb", "n <> 7", isNotEq("n", 7))
indexRows("frb", "n IS NULL", isNull("n"))
indexRows("frb", "n IS NOT NULL", isNotNull("n"))
indexRows("frb", "n IN (3, 10)", isIn("n", 3, 10))
indexRows("frb", "n IN (4, 5, 6)", isIn("n", 4, 5, 6))
indexRows("frb", "n = 3 AND s = 'charlie'", and(isEq("n", 3), isEq("s", "charlie")))
indexRows("frb", "n = 3 OR s = 'charlie'", or(isEq("n", 3), isEq("s", "charlie")))
indexRows("frb", "s = 'bravo'", isEq("s", "bravo"))
indexRows("frb", "s <> 'bravo'", isNotEq("s", "bravo"))
indexRows("frb", "s > 'bravo'", isGt("s", "bravo"))
indexRows("frb", "s >= 'bravo'", isGte("s", "bravo"))
indexRows("frb", "s < 'bravo'", isLt("s", "bravo"))
indexRows("frb", "s > 'b'", isGt("s", "b"))
indexRows("frb", "s < 'b'", isLt("s", "b"))
indexRows("frb", "s BETWEEN 'b' AND 'c'", between("s", "b", "c"))
indexRows("frb", "s > 'v0500'", isGt("s", "v0500"))
indexRows("frb", "s >= 'v0500a'", isGte("s", "v0500a"))
indexRows("frb", "s BETWEEN 'v0123' AND 'v0456'", between("s", "v0123", "v0456"))
indexRows("frb", "s = 'v0999'", isEq("s", "v0999"))
indexRows("frb", "s = 'seven'", isEq("s", "seven"))
indexRows("frb", "s <> 'seven'", isNotEq("s", "seven"))
indexRows("frb", "s IS NULL", isNull("s"))
indexRows("frb", "s IS NOT NULL", isNotNull("s"))
indexRows("frb", "amt > 50", isGt("amt", dec("50")))
indexRows("frb", "amt < 0", isLt("amt", dec("0")))
indexRows("frb", "amt = 0.75", isEq("amt", dec("0.75")))
indexRows("frb", "amt = 7.00", isEq("amt", dec("7.00")))
indexRows("frb", "amt BETWEEN 1 AND 5", between("amt", dec("1"), dec("5")))
indexRows("frb", "d < 2024-03-05", isLt("d", date("2024-03-05")))
indexRows("frb", "d = 2024-03-05", isEq("d", date("2024-03-05")))
indexRows("frb", "d > 2024-03-07", isGt("d", date("2024-03-07")))
indexRows("frb", "d BETWEEN 2024-03-02 AND 2024-03-04", between("d", date("2024-03-02"), date("2024-03-04")))
indexRows("frb", "ts < 2024-03-01 10:00:00.000005", isLt("ts", ldt("2024-03-01 10:00:00.000005")))
indexRows("frb", "ts = 2024-03-01 10:00:00.000005", isEq("ts", ldt("2024-03-01 10:00:00.000005")))
indexRows("frb", "ts >= 2024-03-01 10:00:00.000009", isGte("ts", ldt("2024-03-01 10:00:00.000009")))
indexRows("frb", "ts BETWEEN 2024-03-01 10:00:00.000002 AND 2024-03-01 10:00:00.000003", between("ts", ldt("2024-03-01 10:00:00.000002"), ldt("2024-03-01 10:00:00.000003")))
indexRows("frb", "x < 0", isLt("x", 0.0))
indexRows("frb", "x <= 0.25", isLte("x", 0.25))
indexRows("frb", "x = 0.25", isEq("x", 0.25))
indexRows("frb", "x > 100", isGt("x", 100.0))
indexRows("frb", "x BETWEEN -1 AND 1", between("x", -1.0, 1.0))
indexRows("frb", "x <> 7.0", isNotEq("x", 7.0))
indexRows("frb", "b = true", isEq("b", true))
indexRows("frb", "b = false", isEq("b", false))
indexRows("frb", "b <> true", isNotEq("b", true))
indexRows("frb", "b > false", isGt("b", false))
indexRows("frb", "b IS NULL", isNull("b"))
