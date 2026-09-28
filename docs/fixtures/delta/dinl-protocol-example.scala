// What delta-spark 3.2.1 itself makes of PROTOCOL.md's inline deletion vector example, beside one
// of dinl's — run once, output below; no table is written. The same invocation as run.sh's, with
// this file mounted at /x:
//
//   docker run --rm --entrypoint bash --user 0 -v "$PWD/docs/fixtures/delta:/x:ro" \
//     -v "$CACHE/delta-spark_2.12-3.2.1.jar:/opt/delta-spark.jar:ro" \
//     -v "$CACHE/delta-storage-3.2.1.jar:/opt/delta-storage.jar:ro" apache/spark:3.5.4-java17 \
//     -c "cd /tmp && /opt/spark/bin/spark-shell --master 'local[1]' \
//         --jars /opt/delta-spark.jar,/opt/delta-storage.jar -i /x/dinl-protocol-example.scala < /dev/null | grep '^fixture: '"
//
// Output, 2026-09-28:
//   fixture: protocol bytes 64 39 d3 d0 00 00 00 01 00 00 00 1c 3a 30 00 00 01 00 00 00 00 00 05 00 10 00 00 00 03 00 04 00 07 00 0b 00 12 00 1d 00
//   fixture: protocol refused java.io.IOException: Unexpected RoaringBitmapArray magic number -791463580
//   fixture: dinl bytes d1 d3 39 64 01 00 00 00 00 00 00 00 00 00 00 00 3a 30 00 00 01 00 00 00 00 00 02 00 10 00 00 00 01 00 03 00 05 00
//   fixture: dinl reads 1,3,5
//
// The example's bytes are RoaringBitmapArray's Native layout (magic 1681511376, a bitmap count, a
// size, a 32-bit Roaring bitmap) written big-endian, and RoaringBitmapArray.readFrom reads
// little-endian, so the protocol's own example is one delta-spark 3.2.1 refuses — while the text
// beside it describes the portable layout, magic 1681511377, which is what every writer in this
// release serializes and dinl's vectors hold.
import org.apache.spark.sql.delta.actions.DeletionVectorDescriptor
import org.apache.spark.sql.delta.deletionvectors.RoaringBitmapArray
def show(label: String, text: String, size: Int, cardinality: Long): Unit = {
  val dv = DeletionVectorDescriptor(storageType = "i", pathOrInlineDv = text, offset = None, sizeInBytes = size, cardinality = cardinality)
  val bytes = dv.inlineData
  println(s"fixture: $label bytes ${bytes.map(b => f"$b%02x").mkString(" ")}")
  try println(s"fixture: $label reads ${RoaringBitmapArray.readFrom(bytes).values.mkString(",")}")
  catch { case e: Throwable => println(s"fixture: $label refused ${e.getClass.getName}: ${e.getMessage}") }
}
show("protocol", "wi5b=000010000siXQKl0rr91000f55c8Xg0@@D72lkbi5=-{L", 40, 6)
show("dinl", "^Bg9^0rr910000000000iXQKl0rr91000625c8Xg0rri41POJ5", 38, 3)
println("fixture: done")
System.exit(0)
