-- Regenerates example/paimon/db.db/sm — a Paimon primary-key table with statistics switched off
-- for one column, so every entry's _VALUE_STATS covers a subset of the schema and names it in
-- _VALUE_STATS_COLS.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-tg.sql. By default a data file's _VALUE_STATS holds
-- one bound per schema field in schema order and _VALUE_STATS_COLS is absent; with
-- fields.<col>.stats-mode = none for one column and the dense store on (the default), the stats
-- row shrinks to the columns that have statistics and _VALUE_STATS_COLS says which. A decoder
-- that lays the stats row over the schema in order puts every bound after the gap on the wrong
-- column; this table is what checks that it does not.
--
-- Statements, in order — the numbers matter because the expected values in
-- PaimonStatsModeFixtureTest are read off them:
--
--   1  CREATE   primary key k, one bucket, columns k INT, v STRING, w INT; fields.v.stats-mode = none
--   2  INSERT   (1, 'alpha', 10), (2, 'beta', 20), (3, 'gamma', 30)    snapshot 1: one file
--
-- Expected: the entry's _VALUE_STATS_COLS is [k, w]; its bounds are k 1..3 and w 10..30, no
-- nulls, and nothing recorded for v.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-sm.sql:/tmp/paimon-sm.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-sm.sql"
--   rm -rf example/paimon/db.db/sm && cp -R "$WH/db.db/sm" example/paimon/db.db/sm

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.sm (k INT, v STRING, w INT)
TBLPROPERTIES (
  'primary-key' = 'k',
  'bucket' = '1',
  'fields.v.stats-mode' = 'none'
);

INSERT INTO db.sm VALUES (1, 'alpha', 10), (2, 'beta', 20), (3, 'gamma', 30);
