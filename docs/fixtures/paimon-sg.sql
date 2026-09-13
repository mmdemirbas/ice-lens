-- Regenerates example/paimon/db.db/sg and example/paimon/db.db/sgd — two Paimon primary-key
-- tables under merge-engine = partial-update with sequence groups, the shape a multi-stream
-- join is written in: each group of columns is versioned by its own sequence field, and a
-- record updates a group only when its sequence value is at or above the row's.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-me.sql. `bucket = 1` so every key is in one bucket.
--
-- sg — sequence groups alone. Spark refuses a DELETE here (validatePKUpsertDeletable: no
-- remove-record-on-delete and no remove-record-on-sequence-group), so the table is inserts only:
--
--   1  CREATE   (k, a, ga, b, gb); fields.ga.sequence-group = a, fields.gb.sequence-group = b
--   2  INSERT   (1, 1, 10, 1, 10), (2, 1, 10, 1, 10)         snapshot 1
--   3  INSERT   (1, 2, 5, 2, 20)                             snapshot 2: ga 5 < 10 leaves a = 1;
--                                                             gb 20 >= 10 makes b = 2
--   4  INSERT   (3, NULL, NULL, 3, 1)                        snapshot 3: a key with group a empty
--
--   read: (1, 1, 10, 2, 20), (2, 1, 10, 1, 10), (3, NULL, NULL, 3, 1) — 3 rows
--
-- sgd — the same, plus partial-update.remove-record-on-sequence-group = ga, which is what lets
-- Spark's DELETE take the upsert path and write a -D record carrying the row's current values:
--
--   1..4 as sg                                                snapshots 1..3
--   5  DELETE k = 2                                           snapshot 4: a -D with ga 10, at or
--                                                             above the row's 10, removes the key
--   6  INSERT   (2, 9, 11, 9, 11)                             snapshot 5: the key again, one record
--
--   read: (1, 1, 10, 2, 20), (2, 9, 11, 9, 11), (3, NULL, NULL, 3, 1) — 3 rows
--
-- The last statements print what Paimon reads; the expected values above are checked against
-- that output before the tables are copied in.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-sg.sql:/tmp/paimon-sg.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-sg.sql"
--   for t in sg sgd; do rm -rf example/paimon/db.db/$t && cp -R "$WH/db.db/$t" example/paimon/db.db/$t; done

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.sg (k INT, a INT, ga INT, b INT, gb INT)
TBLPROPERTIES (
  'primary-key' = 'k',
  'bucket' = '1',
  'merge-engine' = 'partial-update',
  'fields.ga.sequence-group' = 'a',
  'fields.gb.sequence-group' = 'b'
);
INSERT INTO db.sg VALUES (1, 1, 10, 1, 10), (2, 1, 10, 1, 10);
INSERT INTO db.sg VALUES (1, 2, 5, 2, 20);
INSERT INTO db.sg VALUES (3, NULL, NULL, 3, 1);

CREATE TABLE db.sgd (k INT, a INT, ga INT, b INT, gb INT)
TBLPROPERTIES (
  'primary-key' = 'k',
  'bucket' = '1',
  'merge-engine' = 'partial-update',
  'fields.ga.sequence-group' = 'a',
  'fields.gb.sequence-group' = 'b',
  'partial-update.remove-record-on-sequence-group' = 'ga'
);
INSERT INTO db.sgd VALUES (1, 1, 10, 1, 10), (2, 1, 10, 1, 10);
INSERT INTO db.sgd VALUES (1, 2, 5, 2, 20);
INSERT INTO db.sgd VALUES (3, NULL, NULL, 3, 1);
DELETE FROM db.sgd WHERE k = 2;
INSERT INTO db.sgd VALUES (2, 9, 11, 9, 11);

SELECT '-- sg' AS probe;
SELECT * FROM db.sg ORDER BY k;
SELECT '-- sgd' AS probe;
SELECT * FROM db.sgd ORDER BY k;
SELECT '-- sgd VERSION AS OF 4' AS probe;
SELECT * FROM db.sgd VERSION AS OF 4 ORDER BY k;
