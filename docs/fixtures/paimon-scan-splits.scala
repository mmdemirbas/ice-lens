// Prints the splits a Paimon batch scan makes of every checked-in table's latest snapshot, and
// the Spark input partitions the same read plans — the oracle PaimonSplitPlanTest holds
// `planPaimonSplits` and `paimonSparkPartitions` to. Read-only: it plans, and reads nothing.
//
// A split is what `SnapshotReaderImpl.generateSplits` (release-1.3.1) makes of one bucket's live
// files through the table's `SplitGenerator`: `MergeTreeSplitGenerator` packs the files whole
// when every one is above level 0 without a `-D` row and the table has deletion vectors, is
// `first-row`, or holds them all at one level, and otherwise cuts them into sections of
// intersecting key ranges and packs the sections; `AppendOnlySplitGenerator` sorts by minimum
// sequence number and packs; `DataEvolutionSplitGenerator` groups by first row id and packs the
// groups. Packing is `BinPacking.packForOrdered` to `source.split.target-size`, an item weighing
// its bytes or `source.split.open-file-cost`, whichever is more. Spark then reshuffles the raw
// splits (`ScanHelper.getInputPartitions`): every raw-convertible split's files walked in order,
// a partition closing when `fileSize + openCost + vector length` would carry it past
// `min(target, max(openCost, Σ(fileSize + openCost) / minPartitionNum))`, with `minPartitionNum`
// `spark.sql.files.minPartitionNum` or the leaf-node parallelism; a split that is not raw is a
// partition of its own. Printed at parallelism 1 (`local[1]`) and at 200
// (`spark.sql.leafNodeDefaultParallelism`).
//
// Run with the Paimon Spark 3.5 runtime jar, version 1.3, over copies of the tables:
//
//   WH=$PWD/tmp/splits-wh; rm -rf "$WH"; mkdir -p "$WH/db.db"
//   cp -R example/paimon/db.db/* "$WH/db.db/"; cp -R example/paimon/ep-files "$WH/ep-files"
//   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
//   docker run --rm --entrypoint bash \
//     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
//     -v "$PWD/docs/fixtures/paimon-scan-splits.scala:/tmp/splits.scala:ro" \
//     tabulario/spark-iceberg \
//     -c "/opt/spark/bin/spark-shell --master 'local[1]' --jars /opt/paimon-spark.jar \
//           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
//           --conf spark.sql.catalog.paimon.warehouse=/wh \
//           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
//           --conf spark.sql.defaultCatalog=paimon \
//           --conf spark.ui.enabled=false -I /tmp/splits.scala <<< 'System.exit(0)'" \
//     2>&1 | grep -E '^(-- |s[0-9]+ |! )' > core/src/test/resources/paimon-scan-plans/splits.txt
//
// The catalog confs matter: the image's default catalog is an Iceberg REST catalog at a host
// that is not there, and a DataFrame read by path resolves through the default catalog first.
//
// Output, per table: `-- <t>: splits`, one line per split `s<i> <partition> b<bucket> raw=<b>
// <file>,<file>…`, `-- <t>: splits = N`, then `-- <t>: spark partitions = P (parallelism 1)` and
// the same at 200. A table that cannot be planned prints `! <t>: <error>`.

import org.apache.paimon.catalog.CatalogContext
import org.apache.paimon.fs.Path
import org.apache.paimon.options.Options
import org.apache.paimon.table.FileStoreTableFactory
import org.apache.paimon.table.source.DataSplit
import scala.collection.JavaConverters._

val tables = new java.io.File("/wh/db.db").listFiles().filter(_.isDirectory).map(_.getName).sorted

def splits(name: String): Unit = {
  try {
    val options = new Options()
    options.set("path", s"/wh/db.db/$name")
    val table = FileStoreTableFactory.create(CatalogContext.create(options))
    val plan = table.newReadBuilder().newScan().plan().splits().asScala.map(_.asInstanceOf[DataSplit])
    println(s"-- $name: splits")
    plan.zipWithIndex.foreach { case (s, i) =>
      println(s"s${i + 1} ${s.partition()} b${s.bucket()} raw=${s.rawConvertible()} ${s.dataFiles().asScala.map(_.fileName()).mkString(",")}")
    }
    println(s"-- $name: splits = ${plan.size}")
  } catch {
    case e: Throwable => println(s"! $name: ${e.getClass.getSimpleName}: ${e.getMessage.takeWhile(_ != '\n')}")
  }
}

def partitions(name: String, parallelism: Int): Unit = {
  try {
    val n = spark.read.format("paimon").load(s"/wh/db.db/$name").rdd.getNumPartitions
    println(s"-- $name: spark partitions = $n (parallelism $parallelism)")
  } catch {
    case e: Throwable => println(s"! $name: spark: ${e.getClass.getSimpleName}: ${e.getMessage.takeWhile(_ != '\n')}")
  }
}

tables.foreach(splits)
// local[1]: defaultParallelism is 1, and nothing sets the leaf-node parallelism or minPartitionNum.
tables.foreach(t => partitions(t, 1))
spark.conf.set("spark.sql.leafNodeDefaultParallelism", "200")
tables.foreach(t => partitions(t, 200))
