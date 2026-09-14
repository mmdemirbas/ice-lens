-- Regenerates example/paimon/db.db/pav — a Paimon primary-key table whose data files are Avro.
--
-- Every other Paimon fixture is Parquet, and the readers had never chosen a table function
-- by format (see avrofmt.sql, the Iceberg twin). On Paimon the Avro reading reaches further:
-- the merged count and the row lookup union a bucket's files with `arg_max` by
-- `_SEQUENCE_NUMBER`, which needs no row position and so works on Avro as on Parquet; the
-- file's `_VALUE_STATS` are matched to its columns by name, since Avro carries no field ids;
-- and a sampled record has no position, which only a deletion vector would have needed — none
-- here, so every record's fate is decided by its `_VALUE_KIND` and the key's later writes.
--
-- `file.compression = deflate`, because Paimon's default is zstd and DuckDB's Avro reader
-- reads `null` and `deflate` only — paimon-paz.sql is the same table on the default, the one
-- the app cannot read and has to say why.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-fb.sql:
--
--   1  CREATE   primary key k, one bucket, file.format = avro, file.compression = deflate
--   2  INSERT   (1 alpha 10) (2 bravo 20) (3 charlie 30)
--   3  INSERT   (2 bravo-updated 21)                 the key's later write, in a second file
--   4  DELETE   k = 3                                a -D record in a third file
--
-- A read returns (1 alpha 10) and (2 bravo-updated 21); the files hold five records.
--
-- To regenerate:
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-pav.sql:/tmp/paimon-pav.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-pav.sql"
--   rm -rf example/paimon/db.db/pav && cp -R "$WH/db.db/pav" example/paimon/db.db/pav

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.pav (k INT, v STRING, n INT)
TBLPROPERTIES (
  'primary-key' = 'k',
  'bucket' = '1',
  'file.format' = 'avro',
  'file.compression' = 'deflate'
);

INSERT INTO db.pav VALUES (1, 'alpha', 10), (2, 'bravo', 20), (3, 'charlie', 30);

INSERT INTO db.pav VALUES (2, 'bravo-updated', 21);

DELETE FROM db.pav WHERE k = 3;

SELECT k, v, n FROM db.pav ORDER BY k;
