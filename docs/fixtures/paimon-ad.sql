-- Regenerates example/paimon/db.db/ad — a Paimon append table with deletion vectors, so a DELETE
-- marks rows in place instead of rewriting the file.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-ao.sql. That table showed a DELETE on an append table
-- rewriting the file it touched — an APPEND commit removing the file and adding it back without
-- the row. With `deletion-vectors.enabled` there is a second answer, and it is the one a large
-- table wants: the file stays, and a DELETION_VECTORS index names the positions gone. The `dv`
-- fixture has a vector on a primary-key table, where getting one written took a lookup
-- compaction and files large enough not to be merged; an append table has no merge engine, so
-- the question this table answers is what a DELETE writes when there is nothing to compact.
--
-- Statements, in order — the numbers matter because the expected values in
-- PaimonAppendDeletionVectorFixtureTest are read off them:
--
--   1  CREATE   no primary key, unpartitioned, bucket = -1, deletion-vectors.enabled = true
--   2  INSERT   ids 1..5                                     snapshot 1: one file, 5 rows
--   3  INSERT   ids 6..7                                     snapshot 2: a second file, 2 rows
--   4  DELETE   ids 2 and 6 — one row in each file           what does it write?
--   5  SELECT   the rows                                     the oracle: 1, 3, 4, 5, 7
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-ad.sql:/tmp/paimon-ad.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-ad.sql"
--   rm -rf example/paimon/db.db/ad && cp -R "$WH/db.db/ad" example/paimon/db.db/ad

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.ad (id INT, msg STRING)
TBLPROPERTIES (
  'bucket' = '-1',
  'deletion-vectors.enabled' = 'true'
);

INSERT INTO db.ad VALUES (1, 'alpha'), (2, 'bravo'), (3, 'charlie'), (4, 'delta'), (5, 'echo');

INSERT INTO db.ad VALUES (6, 'foxtrot'), (7, 'golf');

DELETE FROM db.ad WHERE id IN (2, 6);

SELECT * FROM db.ad ORDER BY id;
