-- Regenerates example/paimon/db.db/paz — a Paimon primary-key table whose data files are Avro
-- under the default compression, which is zstd: the `avro.codec` in each file's header is
-- `zstandard`, and DuckDB 1.4's Avro reader answers "File header contains an unknown codec"
-- to it. paimon-pav.sql is the same table under `file.compression = deflate`, which DuckDB
-- reads; this one exists so the app is seen naming the codec, on every row card, in the merged
-- count and in the statistics sweep, instead of repeating DuckDB's line.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-fb.sql:
--
--   1  CREATE   primary key k, one bucket, file.format = avro, compression left at its default
--   2  INSERT   (1 alpha 10) (2 bravo 20)
--
-- To regenerate:
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-paz.sql:/tmp/paimon-paz.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-paz.sql"
--   rm -rf example/paimon/db.db/paz && cp -R "$WH/db.db/paz" example/paimon/db.db/paz

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.paz (k INT, v STRING, n INT)
TBLPROPERTIES (
  'primary-key' = 'k',
  'bucket' = '1',
  'file.format' = 'avro'
);

INSERT INTO db.paz VALUES (1, 'alpha', 10), (2, 'bravo', 20);

SELECT k, v, n FROM db.paz ORDER BY k;
