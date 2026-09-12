-- Regenerates example/paimon/db.db/fi — a Paimon primary-key table with a bloom-filter file index,
-- written once as a file beside the data file and once embedded in the manifest entry.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-tg.sql. A file index is a per-data-file structure a
-- scan consults before opening the file; where it lives depends on its size against
-- file-index.in-manifest-threshold (500 bytes by default): larger goes to <data file>.index
-- beside the data file and is named in the entry's _EXTRA_FILES, smaller is carried in the entry
-- itself as _EMBEDDED_FILE_INDEX. A reader that references data files alone reports the index
-- file as an orphan; this table is what says so, and what shows both shapes.
--
-- Statements, in order — the numbers matter because the expected values in
-- PaimonFileIndexFixtureTest are read off them:
--
--   1  CREATE   primary key k, one bucket, bloom filter on v with the default 1,000,000 items
--   2  INSERT   k = 1, 2, 3                       snapshot 1: one data file, one .index file
--   3  ALTER    file-index.bloom-filter.v.items = 100      schema 1
--   4  INSERT   k = 4, 5                          snapshot 2: one data file, index embedded
--
-- Expected on disk afterwards: bucket-0 holds two data files and one .index file, named in the
-- first entry's _EXTRA_FILES; the second entry carries _EMBEDDED_FILE_INDEX bytes and no extra
-- files.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-fi.sql:/tmp/paimon-fi.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-fi.sql"
--   rm -rf example/paimon/db.db/fi && cp -R "$WH/db.db/fi" example/paimon/db.db/fi

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.fi (k INT, v STRING)
TBLPROPERTIES (
  'primary-key' = 'k',
  'bucket' = '1',
  'file-index.bloom-filter.columns' = 'v'
);

INSERT INTO db.fi VALUES (1, 'alpha'), (2, 'beta'), (3, 'gamma');

ALTER TABLE db.fi SET TBLPROPERTIES ('file-index.bloom-filter.v.items' = '100');

INSERT INTO db.fi VALUES (4, 'delta'), (5, 'epsilon');
