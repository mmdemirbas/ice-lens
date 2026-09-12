-- Regenerates example/paimon/db.db/ep and example/paimon/ep-files — a Paimon primary-key table
-- whose data files are written to an external path, outside the table directory.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-tg.sql. data-file.external-paths sends new data files
-- to a directory that is not the table's, and the manifest entry records where in _EXTERNAL_PATH;
-- a reader that builds a data file's path from the table root, the partition and the bucket
-- reports every such file missing and the table's own bucket directory empty. This table is
-- what says so, the Paimon counterpart of the Iceberg extdata fixture.
--
-- Statements, in order — the numbers matter because the expected values in
-- PaimonExternalPathFixtureTest are read off them:
--
--   1  CREATE   primary key k, one bucket, data-file.external-paths = file:///wh/ep-files
--   2  INSERT   k = 1, 2                          snapshot 1: one file, in the external path
--   3  INSERT   k = 3                             snapshot 2: one file, in the external path
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-ep.sql:/tmp/paimon-ep.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-ep.sql"
--   rm -rf example/paimon/db.db/ep example/paimon/ep-files
--   cp -R "$WH/db.db/ep" example/paimon/db.db/ep && cp -R "$WH/ep-files" example/paimon/ep-files

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.ep (k INT, v STRING)
TBLPROPERTIES (
  'primary-key' = 'k',
  'bucket' = '1',
  'data-file.external-paths' = 'file:///wh/ep-files',
  'data-file.external-paths.strategy' = 'round-robin'
);

INSERT INTO db.ep VALUES (1, 'a'), (2, 'b');

INSERT INTO db.ep VALUES (3, 'c');
