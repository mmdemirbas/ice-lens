// Step 2 of 3 for example/iceberg/default/eqren — see docs/fixtures/eqren.sql for the full
// invocation. The same writer as eqdel-equality-deletes.scala, keyed on `name` rather than `id`,
// because the rename in step 3 is of `name`: the delete file it writes holds a column called
// `name` that the table will call `label`.
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
val table = tables.load("/wh/default/eqren")
val schema = table.schema()
val nameId = schema.findField("name").fieldId()
val deleteSchema = schema.select("name")

val writerFunc = new JFunction[MessageType, ParquetValueWriter[_]] {
  def apply(t: MessageType): ParquetValueWriter[_] = GenericParquetWriter.buildWriter(t)
}

val out = table.io().newOutputFile(table.location() + "/data/00000-eq-deletes.parquet")
val writer: EqualityDeleteWriter[Record] = Parquet.writeDeletes(out)
  .forTable(table)
  .rowSchema(deleteSchema)
  .createWriterFunc(writerFunc)
  .equalityFieldIds(nameId)
  .withSpec(table.spec())
  .overwrite()
  .buildEqualityWriter[Record]()

// Deletes `bravo` from the first data file and `foxtrot` from the second — ids 2 and 6, the same
// rows eqdel removes — so one equality delete file applies across both.
Seq("bravo", "foxtrot").foreach { n =>
  val rec: Record = GenericRecord.create(deleteSchema)
  rec.setField("name", n)
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
