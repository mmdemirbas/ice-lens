-- Regenerates example/paimon/db.db/sgm — a Paimon primary-key table under
-- merge-engine = partial-update whose sequence group is versioned by TWO fields
-- (fields.g1,g2.sequence-group = a), and whose remove-record-on-sequence-group names one of
-- them. It is the shape paimon-sg.sql leaves out: a record's (g1, g2) is compared with the
-- row's as a tuple by the generated comparator, lexicographically with a null below every
-- value, and the option removes the key on a -D whichever field of the group it names.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-sg.sql. `bucket = 1` so every key is in one bucket.
--
--   1  CREATE   (k, a, g1, g2, b, gb); fields.g1,g2.sequence-group = a, fields.gb.sequence-group = b,
--               partial-update.remove-record-on-sequence-group = g2
--   2  INSERT   (1, 1, 1, NULL, 1, 10), (2, 1, 1, 1, 1, 10), (3, 1, 1, 1, 1, 10)   snapshot 1
--   3  INSERT   (1, 2, 1, 1, NULL, NULL)     snapshot 2: (1, 1) against the row's (1, NULL) — a null
--                                              is below 1, so the record is above: a = 2, and the
--                                              row's tuple is (1, 1) from here
--   4  INSERT   (2, 2, 1, NULL, NULL, NULL)  snapshot 3: (1, NULL) against (1, 1) is below: a stays 1
--   5  DELETE k = 2                           snapshot 4: Spark's DELETE takes the upsert path and
--                                              writes a -D carrying the row's own (1, 1), at or above
--                                              itself — g2 is named, so the key is removed
--   6  DELETE k = 1                           snapshot 5: the same on the key statement 3 moved
--   7  INSERT   (1, 9, 0, 0, 9, 0)            snapshot 7: key 1 again — the removal reset the row,
--                                              so a tuple lower than the one deleted still lands
--                                              (snapshot 6 is the COMPACT the fifth append triggered)
--
--   read: (1, 9, 0, 0, 9, 0), (3, 1, 1, 1, 1, 10) — 2 rows; by snapshot 3, 3, 3, 2, 1, 1, 2
--
-- Why the nulls matter to a count and not only to a value: were a null read as *above* every
-- value, statement 4 would move key 2's tuple to (1, NULL) and the -D's (1, 1) would then be
-- below it, leaving the key — three rows at snapshot 4 where Paimon reads two.
--
-- The last statements print what Paimon reads at every snapshot; the expected values above are
-- checked against that output before the table is copied in. A COMPACT may land between the
-- appends (num-sorted-run.compaction-trigger = 5), so the versions are printed past the count
-- of statements and the last probe may fail harmlessly.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-sgm.sql:/tmp/paimon-sgm.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-sgm.sql"
--   rm -rf example/paimon/db.db/sgm && cp -R "$WH/db.db/sgm" example/paimon/db.db/sgm

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.sgm (k INT, a INT, g1 INT, g2 INT, b INT, gb INT)
TBLPROPERTIES (
  'primary-key' = 'k',
  'bucket' = '1',
  'merge-engine' = 'partial-update',
  'fields.g1,g2.sequence-group' = 'a',
  'fields.gb.sequence-group' = 'b',
  'partial-update.remove-record-on-sequence-group' = 'g2'
);
INSERT INTO db.sgm VALUES (1, 1, 1, NULL, 1, 10), (2, 1, 1, 1, 1, 10), (3, 1, 1, 1, 1, 10);
INSERT INTO db.sgm VALUES (1, 2, 1, 1, NULL, NULL);
INSERT INTO db.sgm VALUES (2, 2, 1, NULL, NULL, NULL);
DELETE FROM db.sgm WHERE k = 2;
DELETE FROM db.sgm WHERE k = 1;
INSERT INTO db.sgm VALUES (1, 9, 0, 0, 9, 0);

SELECT '-- sgm' AS probe;
SELECT * FROM db.sgm ORDER BY k;
SELECT '-- sgm VERSION AS OF 1' AS probe;
SELECT * FROM db.sgm VERSION AS OF 1 ORDER BY k;
SELECT '-- sgm VERSION AS OF 2' AS probe;
SELECT * FROM db.sgm VERSION AS OF 2 ORDER BY k;
SELECT '-- sgm VERSION AS OF 3' AS probe;
SELECT * FROM db.sgm VERSION AS OF 3 ORDER BY k;
SELECT '-- sgm VERSION AS OF 4' AS probe;
SELECT * FROM db.sgm VERSION AS OF 4 ORDER BY k;
SELECT '-- sgm VERSION AS OF 5' AS probe;
SELECT * FROM db.sgm VERSION AS OF 5 ORDER BY k;
SELECT '-- sgm VERSION AS OF 6' AS probe;
SELECT * FROM db.sgm VERSION AS OF 6 ORDER BY k;
SELECT '-- sgm VERSION AS OF 7' AS probe;
SELECT * FROM db.sgm VERSION AS OF 7 ORDER BY k;
