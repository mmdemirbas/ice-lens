// The `deletes` and `tasks` halves of iceberg-scan-plans.scala, run against `hdfsw` where it was
// written — on the HDFS lab — rather than against a copy under /wh: its metadata records
// `hdfs://icelens-hdfs:8020/...` for every manifest list and manifest, so Iceberg can only open it
// where those paths resolve. The two functions are copied verbatim; only where a table is found
// differs. The output is the `hdfsw` section of deletes.txt and tasks.txt under
// core/src/test/resources/iceberg-scan-plans/.
//
// To regenerate, with docs/fixtures/hdfs-lab.sh up and the spark.conf of docs/fixtures/hdfsw.sql:
//
//   docker run --rm --entrypoint bash --network container:icelens-hdfs -e HADOOP_USER_NAME=hadoop \
//     -v "$PWD/docs/fixtures/hdfsw-scan-plans.scala:/tmp/plans.scala:ro" -v "$WH/spark.conf:/tmp/spark.conf:ro" \
//     tabulario/spark-iceberg:latest -c \
//     "/opt/spark/bin/spark-shell --master 'local[1]' --properties-file /tmp/spark.conf -I /tmp/plans.scala <<< 'System.exit(0)'"

import org.apache.iceberg.expressions.{Expression, Expressions => E}
import org.apache.iceberg.hadoop.HadoopTables
import scala.collection.JavaConverters._

val tables = new HadoopTables(spark.sessionState.newHadoopConf())
def tablePath(name: String): String = s"hdfs://icelens-hdfs:8020/warehouse/spark/default/$name"
// Read by path, as the main script reads a table with no hint; the lens catalog's warehouse is the same directory.
def hinted(name: String): Boolean = false

// Every table's current snapshot: `<data file> -> <delete files>`, sorted. A table this Iceberg
// cannot open prints one `!` line instead, and the test leaves it out by name.
def deletes(name: String): Unit = {
  println(s"-- $name: deletes")
  try {
    val table = tables.load(tablePath(name))
    if (table.currentSnapshot() == null) { println("(no snapshot)"); return }
    val byFile = scala.collection.mutable.TreeMap[String, scala.collection.mutable.TreeSet[String]]()
    for (t <- table.newScan().planFiles().asScala) {
      val set = byFile.getOrElseUpdate(t.file().path().toString.split("/").last, scala.collection.mutable.TreeSet[String]())
      t.deletes().asScala.foreach(d => set += d.path().toString.split("/").last)
    }
    for ((f, ds) <- byFile) println(s"$f -> ${ds.mkString(",")}")
  } catch { case e: Throwable => println(s"! ${e.getClass.getSimpleName}: ${e.getMessage}") }
}
deletes("hdfsw")

// Every table's current snapshot: the tasks under three option sets — one line per task, its
// splits as `<file>:<start>+<length>` in packing order — printed twice under the defaults, since
// a plan over several manifests returns files in the worker pool's order and the packing follows
// it; then Spark's own partition count for a DataFrame read.
def tasks(name: String): Unit = {
  try {
    val table = tables.load(tablePath(name))
    if (table.currentSnapshot() == null) { println(s"-- $name: tasks (no snapshot)"); return }
    def run(label: String, scan: org.apache.iceberg.TableScan): Unit = {
      println(s"-- $name: tasks $label")
      val groups = scan.planTasks().asScala.toList
      for (g <- groups) println(g.files().asScala.map(t => s"${t.file().path().toString.split("/").last}:${t.start()}+${t.length()}").mkString(","))
      println(s"-- $name: tasks $label = ${groups.size} tasks")
    }
    run("default", table.newScan())
    run("default again", table.newScan())
    run("split=8MiB", table.newScan().option("read.split.target-size", (8L * 1024 * 1024).toString))
    run("split=32KiB cost=0", table.newScan().option("read.split.target-size", (32L * 1024).toString).option("read.split.open-file-cost", "0"))
    val df = if (hinted(name)) spark.table(s"lens.default.$name") else spark.read.format("iceberg").load(tablePath(name))
    val parallelism = math.max(spark.sparkContext.defaultParallelism, spark.sessionState.conf.numShufflePartitions)
    println(s"-- $name: spark partitions = ${df.rdd.getNumPartitions} (parallelism $parallelism)")
  } catch { case e: Throwable => println(s"! ${e.getClass.getSimpleName}: ${e.getMessage}") }
}
tasks("hdfsw")
