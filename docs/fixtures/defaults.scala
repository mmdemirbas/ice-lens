// Regenerates example/iceberg/default/defaults — a format-version 3 table whose schema carries
// column defaults: `initial-default`, the value a read returns for rows written before the
// column existed, and `write-default`, the value a writer stores when a row omits the column.
//
// Written by Spark 3.5.5 with the Iceberg 1.10.0 Spark runtime (the jar swap lineage.sql
// describes). Spark 3.5 cannot state a default in DDL — `ALTER TABLE … ADD COLUMN … DEFAULT`
// answers `UnsupportedOperationException: setting default values in Spark is currently
// unsupported` (run 2026-09-13) — so the schema changes go through Iceberg's own
// `UpdateSchema` API from spark-shell, on the same table Spark writes to. A table loaded with
// `HadoopTables` is a second handle, so the Spark side needs `REFRESH TABLE` after each commit
// (see the zihin node on the cached catalog).
//
//   1  CREATE   (id INT, name STRING), format-version 3
//   2  INSERT   (1 alpha) (2 bravo)                                 file 1: two columns
//   3  addColumn region STRING, default 'eu'                        initial-default = write-default = eu
//   4  addColumn score INT, default 0                               initial-default = write-default = 0
//   5  updateColumnDefault region → 'us'                            write-default us; initial-default stays eu
//   6  INSERT   (3 charlie us 7)                                    file 2: four columns
//   7  SELECT * ORDER BY id                                         the oracle — what a 1.10 read returns for file 1's rows
//
// To regenerate:
//
//   WH=$(mktemp -d); mkdir -p "$WH/wh"
//   JAR=~/code/spark-kit/lakelab/.cache/iceberg-spark-runtime-3.5_2.12-1.10.0.jar
//   docker run --rm --entrypoint bash \
//     -v "$WH/wh:/wh" -v "$JAR:/opt/iceberg-1.10.jar:ro" \
//     -v "$PWD/docs/fixtures/defaults.scala:/tmp/defaults.scala:ro" \
//     tabulario/spark-iceberg:latest -c \
//     "rm /opt/spark/jars/iceberg-spark-runtime-3.5_2.12-1.8.1.jar /opt/spark/jars/iceberg-*-bundle-1.8.1.jar; \
//      /opt/spark/bin/spark-shell --master 'local[1]' --jars /opt/iceberg-1.10.jar \
//        --conf spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions \
//        --conf spark.sql.catalog.lens=org.apache.iceberg.spark.SparkCatalog \
//        --conf spark.sql.catalog.lens.type=hadoop --conf spark.sql.catalog.lens.warehouse=/wh \
//        --conf spark.sql.defaultCatalog=lens --conf spark.ui.enabled=false \
//        -I /tmp/defaults.scala <<< 'System.exit(0)'"
//   rm -rf example/iceberg/default/defaults && cp -R "$WH/wh/default/defaults" example/iceberg/default/defaults
//   find example/iceberg/default/defaults -name '.*' -delete

import org.apache.iceberg.hadoop.HadoopTables
import org.apache.iceberg.expressions.Literal
import org.apache.iceberg.types.Types

spark.sql("CREATE TABLE lens.default.defaults (id INT, name STRING) USING iceberg TBLPROPERTIES ('format-version' = '3')")
spark.sql("INSERT INTO lens.default.defaults VALUES (1, 'alpha'), (2, 'bravo')")

val tables = new HadoopTables(spark.sessionState.newHadoopConf())
val table = tables.load("/wh/default/defaults")
table.updateSchema().addColumn("region", Types.StringType.get(), Literal.of("eu")).commit()
table.updateSchema().addColumn("score", Types.IntegerType.get(), Literal.of(0)).commit()
table.updateSchema().updateColumnDefault("region", Literal.of("us")).commit()
println("-- schema: " + table.schema())

spark.sql("REFRESH TABLE lens.default.defaults")
spark.sql("INSERT INTO lens.default.defaults VALUES (3, 'charlie', 'us', 7)")
println("-- read:")
spark.sql("SELECT * FROM lens.default.defaults ORDER BY id").collect().foreach(r => println(r.mkString("\t")))
println("-- files:")
spark.sql("SELECT record_count, file_path FROM lens.default.defaults.files ORDER BY file_path").collect().foreach(r => println(r.mkString("\t")))
