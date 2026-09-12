-- Regenerates example/paimon/db.db/rt — a Paimon append table with row tracking, so every data
-- file records the first row id it holds and every snapshot the next id to hand out.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-ao.sql. Row tracking gives each row a stable id:
-- a data file's _FIRST_ROW_ID is the id of its first row and the rest follow in order, and a
-- snapshot's nextRowId is where the next commit starts. Both were parsed and printed here with
-- nothing to check them against; this table is what checks them.
--
-- Statements, in order — the numbers matter because the expected values in
-- PaimonRowTrackingFixtureTest are read off them:
--
--   1  CREATE   append table (no primary key), bucket = -1, row-tracking.enabled = true,
--            compaction.min.file-num = 2 so two files are enough for the compaction to run
--   2  INSERT   k = 1, 2, 3                       snapshot 1: one file, rows 0..2, next 3
--   3  INSERT   k = 4, 5                          snapshot 2: one file, rows 3..4, next 5
--   4  CALL sys.compact  full                     snapshot 3: one file holding rows 0..4
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-rt.sql:/tmp/paimon-rt.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-rt.sql"
--   rm -rf example/paimon/db.db/rt && cp -R "$WH/db.db/rt" example/paimon/db.db/rt

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.rt (k INT, v STRING)
TBLPROPERTIES (
  'bucket' = '-1',
  'row-tracking.enabled' = 'true',
  'compaction.min.file-num' = '2'
);

INSERT INTO db.rt VALUES (1, 'a'), (2, 'b'), (3, 'c');

INSERT INTO db.rt VALUES (4, 'd'), (5, 'e');

CALL sys.compact(table => 'db.rt', compact_strategy => 'full');
