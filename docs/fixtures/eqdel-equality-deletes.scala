// Step 2 of 3 for example/iceberg/default/eqdel — see docs/fixtures/eqdel.sql for the full
// invocation and for why the SQL half must run under `--master local[1]`.
//
// Spark has no SQL that writes an equality delete, so this drives Iceberg's own
// EqualityDeleteWriter directly from spark-shell, which has iceberg-spark-runtime on its
// classpath. The file it produces is written by Iceberg, not hand-assembled here, so it remains
// a genuine oracle: everything about the encoding is Iceberg's decision.
//
// Two things about this image that cost a round trip each:
//
//   * iceberg-spark-runtime RELOCATES Parquet. `org.apache.parquet.schema.MessageType` is on the
//     classpath too (Spark's own copy) and is a DIFFERENT type from the one the builder wants;
//     the import below must be the shaded one or the createWriterFunc argument will not typecheck.
//   * `buildEqualityWriter()` is generic with nothing to infer from, so Scala resolves it to
//     Nothing and every write fails to compile. The type argument is required.

import org.apache.iceberg.hadoop.HadoopTables
import org.apache.iceberg.data.{GenericRecord, Record}
import org.apache.iceberg.data.parquet.GenericParquetWriter
import org.apache.iceberg.deletes.EqualityDeleteWriter
import org.apache.iceberg.parquet.{Parquet, ParquetValueWriter}
import org.apache.iceberg.shaded.org.apache.parquet.schema.MessageType
import java.util.function.{Function => JFunction}

val tables = new HadoopTables(spark.sessionState.newHadoopConf())
val table = tables.load("/wh/default/eqdel")
val schema = table.schema()
val idId = schema.findField("id").fieldId()
val deleteSchema = schema.select("id")

val writerFunc = new JFunction[MessageType, ParquetValueWriter[_]] {
  def apply(t: MessageType): ParquetValueWriter[_] = GenericParquetWriter.buildWriter(t)
}

val out = table.io().newOutputFile(table.location() + "/data/00000-eq-deletes.parquet")
val writer: EqualityDeleteWriter[Record] = Parquet.writeDeletes(out)
  .forTable(table)
  .rowSchema(deleteSchema)
  .createWriterFunc(writerFunc)
  .equalityFieldIds(idId)
  .withSpec(table.spec())
  .overwrite()
  .buildEqualityWriter[Record]()

// Deletes id 2 from the first data file and id 6 from the second, so one equality delete file
// applies across both — which is the property that makes it unlinkable to any single data file.
Seq(2, 6).foreach { i =>
  val rec: Record = GenericRecord.create(deleteSchema)
  rec.setField("id", Integer.valueOf(i))
  writer.write(rec)
}
writer.close()

val deleteFile = writer.toDeleteFile()
table.newRowDelta().addDeletes(deleteFile).commit()
println(
  "EQDELETE_OK content=" + deleteFile.content() +
    " records=" + deleteFile.recordCount() +
    " bytes=" + deleteFile.fileSizeInBytes() +
    " equalityFieldIds=" + deleteFile.equalityFieldIds()
)
