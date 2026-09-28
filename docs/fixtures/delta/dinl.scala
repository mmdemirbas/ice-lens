// Step 2 of 3 for example/delta/dinl — see dinl.sql for the whole and run.sh for the invocation.
//
// delta-spark 3.2.1 writes no inline deletion vector from SQL (DeletionVectorDescriptor.inlineInLog
// has one caller, the change feed's reader), so this commits two through the writer's classes:
// the bitmap, its portable serialization, the descriptor with its Z85 text and sizeInBytes, and the
// remove/add pair AddFile.removeRows builds are all Delta's. What is chosen here is only which rows.
import org.apache.spark.sql.delta.{DeltaLog, DeltaOperations}
import org.apache.spark.sql.delta.actions.{AddFile, DeletionVectorDescriptor}
import org.apache.spark.sql.delta.deletionvectors.{RoaringBitmapArray, RoaringBitmapArrayFormat}

val table = "/wh/dinl"
val log = DeltaLog.forTable(spark, table)
val files = log.update().allFiles.collect()

// The row's position in its file as Parquet numbers it, which is what a vector marks.
def positionOf(add: AddFile, id: Int): Option[Long] =
  spark.read.parquet(s"$table/${add.path}").select("id", "_metadata.row_index").where(s"id = $id")
    .collect().headOption.map(_.getLong(1))

def holding(id: Int): AddFile = files.find(f => positionOf(f, id).isDefined).get

def inlined(add: AddFile, ids: Seq[Int], dataChange: Boolean) = {
  val positions = ids.map(id => positionOf(add, id).get)
  val bitmap = RoaringBitmapArray(positions: _*)
  val dv = DeletionVectorDescriptor.inlineInLog(bitmap.serializeAsByteArray(RoaringBitmapArrayFormat.Portable), bitmap.cardinality)
  println(s"fixture: ${add.path} ids ${ids.mkString(",")} at ${positions.mkString(",")} -> ${dv.storageType} ${dv.pathOrInlineDv} sizeInBytes ${dv.sizeInBytes} cardinality ${dv.cardinality}")
  add.removeRows(dv, updateStats = true, dataChange = dataChange)
}

val first = holding(2)
require(first.deletionVector != null && first.deletionVector.storageType == "u" && first.deletionVector.cardinality == 1,
  s"the DELETE was to leave a one-position vector file on ${first.path}")
val (firstAdd, firstRemove) = inlined(first, Seq(2), dataChange = false)
val (secondAdd, secondRemove) = inlined(holding(6), Seq(6, 8, 10), dataChange = true)
val version = log.startTransaction().commit(Seq(firstAdd, firstRemove, secondAdd, secondRemove), DeltaOperations.ManualUpdate)
println(s"fixture: committed version $version")
println("fixture: done")
System.exit(0)
