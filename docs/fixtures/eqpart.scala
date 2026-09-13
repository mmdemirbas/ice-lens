// Regenerates example/iceberg/default/eqpart — equality deletes whose reach is decided by their
// partition, and one whose reach is not: the oracle for the two rules DeleteFileIndex applies
// beyond sequence and target (Iceberg 1.8.1, `DeleteFileIndex.forDataFile`).
//
//   * An equality delete written under a PARTITIONED spec is keyed by (spec id, partition) and is
//     never weighed against a data file in another partition or under another spec — however
//     its bounds compare. `fupp` cannot show this: its partition column is an equality column,
//     so the bounds on it already differ across partitions and the bounds rule masks the
//     partition rule. Here the delete's equality column is `id` alone, and the same ids sit in
//     every partition.
//   * An equality delete written under the UNPARTITIONED spec is global: it is weighed against
//     every data file whatever its spec, and only sequence and bounds can rule it out.
//
// Spark never writes equality deletes (see docs/fixtures/eqdel.sql), so this drives Iceberg's own
// EqualityDeleteWriter from spark-shell, the way eqdel-equality-deletes.scala does; the SQL half
// runs in the same shell. Written by Spark 3.5.5 / Iceberg 1.8.1 from the tabulario/spark-iceberg
// image.
//
// The shape, oldest first:
//
//   c1  spec 0 (unpartitioned)   (1, x), (2, y)                 one file A, ids 1..2
//       ALTER TABLE ADD PARTITION FIELD p                       spec 1
//   c2  spec 1                   (1, x), (2, x) / (1, y), (2, y)   file B in p=x, file C in p=y, ids 1..2 each
//   c3  equality delete D1, spec 1, partition p=y, id = 1      reaches C only: A is spec 0, B is p=x,
//                                                              and both hold id 1
//   c4  equality delete D2, spec 0, id = 2                     global — reaches A, B and C
//
// Expected read: (1, x) from A and (1, x) from B — two rows, both id 1 in p=x. Iceberg's plan
// (`deletes.txt`) attaches D2 to A, D2 to B, and D1 and D2 to C.
//
// To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
//
//   WH=$(mktemp -d)
//   cat > "$WH/spark.conf" <<'EOF'
//   spark.sql.extensions              org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions
//   spark.sql.catalog.lens            org.apache.iceberg.spark.SparkCatalog
//   spark.sql.catalog.lens.type       hadoop
//   spark.sql.catalog.lens.warehouse  /wh
//   spark.sql.defaultCatalog          lens
//   spark.sql.catalogImplementation   in-memory
//   spark.ui.enabled                  false
//   spark.eventLog.enabled            false
//   EOF
//   mkdir -p "$WH/wh"
//   docker run --rm --entrypoint bash \
//     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/eqpart.scala:/tmp/eqpart.scala:ro" \
//     -v "$WH/spark.conf:/tmp/spark.conf:ro" \
//     tabulario/spark-iceberg:latest -c \
//     "/opt/spark/bin/spark-shell --master 'local[1]' --properties-file /tmp/spark.conf -I /tmp/eqpart.scala <<< 'System.exit(0)'"
//   rm -rf example/iceberg/default/eqpart
//   cp -R "$WH/wh/default/eqpart" example/iceberg/default/eqpart
//   find example/iceberg/default/eqpart -name '.*.crc' -delete

import org.apache.iceberg.{PartitionKey, PartitionSpec}
import org.apache.iceberg.hadoop.HadoopTables
import org.apache.iceberg.data.{GenericRecord, Record}
import org.apache.iceberg.data.parquet.GenericParquetWriter
import org.apache.iceberg.deletes.EqualityDeleteWriter
import org.apache.iceberg.parquet.{Parquet, ParquetValueWriter}
import org.apache.iceberg.shaded.org.apache.parquet.schema.MessageType
import java.util.function.{Function => JFunction}

val t = "lens.default.eqpart"

spark.sql(s"CREATE TABLE $t (id INT, p STRING) USING iceberg TBLPROPERTIES ('format-version' = '2', 'write.delete.mode' = 'merge-on-read')")
spark.sql(s"INSERT INTO $t VALUES (1, 'x'), (2, 'y')")
spark.sql(s"ALTER TABLE $t ADD PARTITION FIELD p")
spark.sql(s"INSERT INTO $t VALUES (1, 'x'), (2, 'x'), (1, 'y'), (2, 'y')")

val tables = new HadoopTables(spark.sessionState.newHadoopConf())
val writerFunc = new JFunction[MessageType, ParquetValueWriter[_]] {
  def apply(t: MessageType): ParquetValueWriter[_] = GenericParquetWriter.buildWriter(t)
}

// One equality delete on `id`, under the given spec and partition, committed as its own snapshot.
def equalityDelete(spec: PartitionSpec, partition: PartitionKey, path: String, ids: Seq[Int]): Unit = {
  val table = tables.load("/wh/default/eqpart")
  val schema = table.schema()
  val deleteSchema = schema.select("id")
  val out = table.io().newOutputFile(table.location() + path)
  val builder = Parquet.writeDeletes(out)
    .forTable(table)
    .rowSchema(deleteSchema)
    .createWriterFunc(writerFunc)
    .equalityFieldIds(schema.findField("id").fieldId())
    .withSpec(spec)
    .overwrite()
  val writer: EqualityDeleteWriter[Record] = (if (partition == null) builder else builder.withPartition(partition)).buildEqualityWriter[Record]()
  ids.foreach { i =>
    val rec: Record = GenericRecord.create(deleteSchema)
    rec.setField("id", Integer.valueOf(i))
    writer.write(rec)
  }
  writer.close()
  val deleteFile = writer.toDeleteFile()
  table.newRowDelta().addDeletes(deleteFile).commit()
  println(s"EQDELETE_OK spec=${deleteFile.specId()} partition=${deleteFile.partition()} records=${deleteFile.recordCount()} path=${deleteFile.path()}")
}

// c3: D1 under spec 1, in p=y, id = 1
{
  val table = tables.load("/wh/default/eqpart")
  val spec1 = table.spec()
  val key = new PartitionKey(spec1, table.schema())
  val row: Record = GenericRecord.create(table.schema())
  row.setField("id", Integer.valueOf(1)); row.setField("p", "y")
  key.partition(row)
  equalityDelete(spec1, key, "/data/p=y/00000-eq-delete-y.parquet", Seq(1))
}
// c4: D2 under spec 0, unpartitioned, id = 2
{
  val table = tables.load("/wh/default/eqpart")
  equalityDelete(table.specs().get(0), null, "/data/00000-eq-delete-global.parquet", Seq(2))
}

// The deletes were committed outside Spark's catalog, whose cached table would still read the rows they remove.
spark.sql(s"REFRESH TABLE $t")
println("-- rows")
spark.sql(s"SELECT id, p FROM $t ORDER BY p, id").show(false)
println("-- files")
spark.sql(s"SELECT sequence_number, data_file.content, data_file.spec_id, data_file.partition, data_file.file_path FROM $t.entries ORDER BY sequence_number, data_file.content").show(false)
