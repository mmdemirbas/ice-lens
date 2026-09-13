-- Regenerates example/paimon/db.db/fa — a Paimon append table with a bloom-filter file index on
-- both of its columns, written once as a file beside the data file and once embedded in the
-- manifest entry. `fi` is the primary-key twin; this table exists because at release-1.3.1 an
-- append table is where a batch read actually consults the index: AppendOnlyFileStoreScan tests
-- the embedded index when it plans, and RawFileSplitRead tests the embedded index or the .index
-- file when it opens the file — while a primary-key table's scan tests the embedded index only
-- under deletion vectors, and its read only on a split it can read raw (one file alone in its
-- key range, or every file above level 0).
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-fi.sql.
--
-- Statements, in order — the values matter because PaimonFileIndexPruningTest and the plan oracle
-- in paimon-scan-plans.scala read off them:
--
--   1  CREATE   no primary key, bloom filter on k and v with 1,000 items each — 600 bytes a
--               column, over file-index.in-manifest-threshold (500 B), so the index is a file
--   2  INSERT   k = 1, 2, 3; v = alpha, beta, and a 43-byte value — xxHash64 walks a 32-byte
--               stripe only past 32 bytes, so a short value alone never exercises it
--               snapshot 1: one data file, one .index beside it
--   3  ALTER    file-index.bloom-filter.k.items and .v.items = 100    schema 1: 60 bytes a column
--   4  INSERT   k = 4, 6; v = delta, epsilon                snapshot 2: one data file, index embedded
--
-- The gap at k = 5 and the string values between delta and epsilon are the point: a literal
-- inside a file's bounds that the file does not hold is what only the bloom filter can rule out.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-fa.sql:/tmp/paimon-fa.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-fa.sql"
--   rm -rf example/paimon/db.db/fa && cp -R "$WH/db.db/fa" example/paimon/db.db/fa

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.fa (k INT, v STRING)
TBLPROPERTIES (
  'file-index.bloom-filter.columns' = 'k,v',
  'file-index.bloom-filter.k.items' = '1000',
  'file-index.bloom-filter.v.items' = '1000'
);

INSERT INTO db.fa VALUES (1, 'alpha'), (2, 'beta'), (3, 'the quick brown fox jumps over the lazy dog');

ALTER TABLE db.fa SET TBLPROPERTIES ('file-index.bloom-filter.k.items' = '100', 'file-index.bloom-filter.v.items' = '100');

INSERT INTO db.fa VALUES (4, 'delta'), (6, 'epsilon');
