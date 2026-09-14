// Prints which data files an Iceberg scan opens for a filter, on the checked-in tables — the
// oracle IcebergScanPlanTest holds the pruning verdicts to. Read-only: it plans, and reads
// nothing. `planFiles()` runs Iceberg's own two stages — ManifestEvaluator over the manifest
// list's partition summaries, then InclusiveMetricsEvaluator over each file's column bounds —
// which are the two `evaluateScan` reproduces, so a file the app skips that Iceberg opens is a
// wrong skip, the one pruning bug that loses rows.
//
// Run with the image's Iceberg 1.8.1 over copies of the tables at the paths their manifests
// record (/wh/default/<t>), so the manifests resolve where they say they are:
//
//   WH=$PWD/tmp/iplans-wh; rm -rf "$WH"; mkdir -p "$WH/default"
//   cp -R example/iceberg/default/* "$WH/default/"; cp -R example/iceberg/extdata-files "$WH/"
//   docker run --rm --entrypoint bash \
//     -v "$WH:/wh" -v "$PWD/docs/fixtures/iceberg-scan-plans.scala:/tmp/plans.scala:ro" \
//     tabulario/spark-iceberg:latest \
//     -c "/opt/spark/bin/spark-shell --master 'local[1]' --conf spark.ui.enabled=false -I /tmp/plans.scala <<< 'System.exit(0)'"
//
// Observed (2026-09-14), one line per file the plan opens; the file names per case are carried
// by IcebergScanPlanTest.
//
// The second half prints, for every table, which delete files the unfiltered plan attaches to
// each data file of the current snapshot (`FileScanTask.deletes()` — DeleteFileIndex's pairing
// by sequence number, partition, and a positional delete's file_path bounds or a vector's
// referenced_data_file), which IcebergDeletePairingPlanTest holds `deleteReach` to: a delete
// Iceberg applies must be reached or unsettled here, and a reach proved here must be one
// Iceberg applies.

import org.apache.iceberg.expressions.{Expression, Expressions => E}
import org.apache.iceberg.hadoop.HadoopTables
import scala.collection.JavaConverters._

val tables = new HadoopTables(spark.sessionState.newHadoopConf())

def plan(name: String, label: String, expr: Expression): Unit = {
  val table = tables.load(s"/wh/default/$name")
  val scan = if (expr == null) table.newScan() else table.newScan().filter(expr)
  val files = scan.planFiles().asScala.map(_.file().path().toString.split("/").last).toList.sorted
  println(s"-- $name: $label")
  files.foreach(println)
  println(s"-- $name: $label = ${files.size} files")
}

// parted: four rows in four files under eight partition fields — every transform shape. The
// timestamp columns are timestamptz (Spark's TIMESTAMP), so a literal needs its offset.
plan("parted", "no filter", null)
plan("parted", "name = 'alpha'", E.equal("name", "alpha"))
plan("parted", "amount < 0", E.lessThan("amount", new java.math.BigDecimal("0.00")))
plan("parted", "id = 3", E.equal("id", 3))
plan("parted", "id IN (1, 2)", E.in("id", 1, 2))
plan("parted", "id > 2", E.greaterThan("id", 2))
plan("parted", "name LIKE 'br%'", E.startsWith("name", "br"))
plan("parted", "name LIKE 'alp%'", E.startsWith("name", "alp"))
plan("parted", "d = 2024-03-05", E.equal("d", "2024-03-05"))
plan("parted", "d < 1970-01-01", E.lessThan("d", "1970-01-01"))
plan("parted", "ts_h >= 2024-03-05 07:00:00", E.greaterThanOrEqual("ts_h", "2024-03-05T07:00:00+00:00"))
plan("parted", "ts_m = 2024-03-31 07:15:00", E.equal("ts_m", "2024-03-31T07:15:00+00:00"))
plan("parted", "ts_y < 2000-01-01 00:00:00", E.lessThan("ts_y", "2000-01-01T00:00:00+00:00"))
plan("parted", "NOT name = 'alpha'", E.not(E.equal("name", "alpha")))
plan("parted", "name = 'alpha' OR id = 2", E.or(E.equal("name", "alpha"), E.equal("id", 2)))
plan("parted", "name IS NULL", E.isNull("name"))
plan("parted", "name IS NOT NULL", E.notNull("name"))
plan("parted", "amount BETWEEN 0 AND 100", E.and(E.greaterThanOrEqual("amount", new java.math.BigDecimal("0.00")), E.lessThanOrEqual("amount", new java.math.BigDecimal("100.00"))))
plan("parted", "name = 'alpha' AND amount > 0", E.and(E.equal("name", "alpha"), E.greaterThan("amount", new java.math.BigDecimal("0.00"))))
plan("parted", "name NOT IN ('alpha', 'bravo')", E.notIn("name", "alpha", "bravo"))
plan("parted", "name = 'zulu'", E.equal("name", "zulu"))

// respec: two specs — spec 0 (name, bucket(4, id), day(d)), spec 1 (bucket(8, id), month(d))
plan("respec", "no filter", null)
plan("respec", "name = 'alpha'", E.equal("name", "alpha"))
plan("respec", "id = 3", E.equal("id", 3))
plan("respec", "id = 1", E.equal("id", 1))
plan("respec", "d = 2024-03-06", E.equal("d", "2024-03-06"))
plan("respec", "d >= 2025-01-01", E.greaterThanOrEqual("d", "2025-01-01"))
plan("respec", "d < 1970-01-01", E.lessThan("d", "1970-01-01"))

// evolved: three files under three manifest schemas — int → long, float → double, a rename, a drop
plan("evolved", "no filter", null)
plan("evolved", "id > 1000", E.greaterThan("id", 1000L))
plan("evolved", "id = 2", E.equal("id", 2L))
plan("evolved", "amount < 2", E.lessThan("amount", 2.0d))
plan("evolved", "note = 'fifth'", E.equal("note", "fifth"))
plan("evolved", "note IS NULL", E.isNull("note"))
// eqren: `name` renamed to `label` after two files and an equality delete were written — a
// filter on `label` binds to field 2 and prunes the files whose manifest still calls it `name`.
plan("eqren", "no filter", null)
plan("eqren", "label = 'alpha'", E.equal("label", "alpha"))
plan("eqren", "label = 'bravo'", E.equal("label", "bravo"))
plan("eqren", "label > 'f'", E.greaterThan("label", "f"))
plan("eqren", "label IS NULL", E.isNull("label"))
// deep: a struct, a list and a map; `addr.city` renamed to `addr.town` and `addr.country`
// added after the first file — bounds are per leaf id, and a filter names a leaf by its path.
plan("deep", "no filter", null)
plan("deep", "addr.town = 'Izmir'", E.equal("addr.town", "Izmir"))
plan("deep", "addr.town = 'Ankara'", E.equal("addr.town", "Ankara"))
plan("deep", "addr.zip < 7000", E.lessThan("addr.zip", 7000))
plan("deep", "addr.country = 'TR'", E.equal("addr.country", "TR"))
plan("deep", "addr.country IS NULL", E.isNull("addr.country"))
plan("deep", "name = 'delta'", E.equal("name", "delta"))

// pstats: partitioned by p, a positional delete on eu
plan("pstats", "no filter", null)
plan("pstats", "p = 'eu'", E.equal("p", "eu"))
plan("pstats", "p IN ('us', 'apac')", E.in("p", "us", "apac"))
plan("pstats", "id > 3", E.greaterThan("id", 3))

// mor: unpartitioned, a compaction and positional deletes — the data files a filter opens
plan("mor", "no filter", null)
plan("mor", "id = 7", E.equal("id", 7))
plan("mor", "id < 3", E.lessThan("id", 3))

// Every table's current snapshot: `<data file> -> <delete files>`, sorted. A table this Iceberg
// cannot open prints one `!` line instead, and the test leaves it out by name.
def deletes(name: String): Unit = {
  println(s"-- $name: deletes")
  try {
    val table = tables.load(s"/wh/default/$name")
    if (table.currentSnapshot() == null) { println("(no snapshot)"); return }
    val byFile = scala.collection.mutable.TreeMap[String, scala.collection.mutable.TreeSet[String]]()
    for (t <- table.newScan().planFiles().asScala) {
      val set = byFile.getOrElseUpdate(t.file().path().toString.split("/").last, scala.collection.mutable.TreeSet[String]())
      t.deletes().asScala.foreach(d => set += d.path().toString.split("/").last)
    }
    for ((f, ds) <- byFile) println(s"$f -> ${ds.mkString(",")}")
  } catch { case e: Throwable => println(s"! ${e.getClass.getSimpleName}: ${e.getMessage}") }
}
new java.io.File("/wh/default").listFiles().filter(_.isDirectory).map(_.getName).sorted.foreach(deletes)
