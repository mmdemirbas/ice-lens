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
//   for t in pc lk pu ag dv sm pt; do cp -R example/paimon/db.db/$t "$WH/db.db/$t"; done
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

def lit(v: Any): AnyRef = v match {
  case s: String => BinaryString.fromString(s)
  case i: Int => Int.box(i)
  case l: Long => Long.box(l)
  case other => other.asInstanceOf[AnyRef]
}

def isEq(name: String, v: Any) = (b: PredicateBuilder, t: org.apache.paimon.types.RowType) => b.equal(t.getFieldIndex(name), lit(v))
def and(ps: ((PredicateBuilder, org.apache.paimon.types.RowType) => Predicate)*) =
  (b: PredicateBuilder, t: org.apache.paimon.types.RowType) => PredicateBuilder.and(ps.map(_(b, t)).asJava)

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
