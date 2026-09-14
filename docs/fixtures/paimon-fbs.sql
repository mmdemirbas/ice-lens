-- Regenerates example/paimon/db.db/fbs — a Paimon append table with a bit-sliced index (`bsi`)
-- on four columns of the four kinds the writer accepts: an INT, a DECIMAL(10,2), a DATE and a
-- TIMESTAMP(6). A BSI holds one Roaring bitmap per bit of the value (two sets, one for the
-- non-negative values and one for the magnitudes of the negative ones), so unlike a bloom filter
-- or a bitmap dictionary it answers `<`, `<=`, `>`, `>=` and `BETWEEN` exactly, per row — which
-- is what lets it skip a file whose bounds bracket the literal and whose rows still miss it.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-fb.sql.
--
-- Statements, in order — the values matter because PaimonFileIndexTest and the plan oracle in
-- paimon-scan-plans.scala read off them:
--
--   1  CREATE   no primary key; bsi on n, amt, d, ts; a 64 KB in-manifest threshold so file 1 embeds
--   2  INSERT   four rows: n -5, 3, 10, null — a negative, a gap between 3 and 10, a null   file 1, embedded
--   3  ALTER    file-index.in-manifest-threshold = 1b                every later index goes to a .index file
--   4  INSERT   a thousand rows, n 100..1099, amt 1.00..10.99, d and ts a day and a second apart  file 2
--   5  INSERT   (1005 7 7.00 2024-03-07 …) (1006 7 7.00 …)            file 3: every row 7 — the one `n <> 7` skips
--   6  INSERT   (1007 null …) (1008 null …)                           file 4: every value null
--
-- `n BETWEEN 4 AND 6` sits inside file 1's bounds (-5..10) and only the slices rule it out;
-- `ts` values a few microseconds apart are what tell a microsecond mapping from a millisecond
-- one. `n = -5` and `amt = -12.50` are the negative set; `n < 0` reads it whole.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-fbs.sql:/tmp/paimon-fbs.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-fbs.sql"
--   rm -rf example/paimon/db.db/fbs && cp -R "$WH/db.db/fbs" example/paimon/db.db/fbs
--   find example/paimon/db.db/fbs -name '.*.crc' -delete

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.fbs (k INT, n INT, amt DECIMAL(10,2), d DATE, ts TIMESTAMP_NTZ)
TBLPROPERTIES (
  'file-index.bsi.columns' = 'n,amt,d,ts',
  'file-index.in-manifest-threshold' = '64kb'
);

INSERT INTO db.fbs VALUES
  (1, -5, -12.50, DATE '2024-03-01', TIMESTAMP_NTZ '2024-03-01 10:00:00.000001'),
  (2, 3, 0.75, DATE '2024-03-05', TIMESTAMP_NTZ '2024-03-01 10:00:00.000005'),
  (3, 10, 99.99, DATE '2024-03-09', TIMESTAMP_NTZ '2024-03-01 10:00:00.000009'),
  (4, NULL, NULL, NULL, NULL);

ALTER TABLE db.fbs SET TBLPROPERTIES ('file-index.in-manifest-threshold' = '1b');

INSERT INTO db.fbs
SELECT CAST(id + 5 AS INT), CAST(id + 100 AS INT), CAST((id + 100) / 100 AS DECIMAL(10,2)),
       DATE_ADD(DATE '2024-04-01', CAST(id AS INT)),
       TIMESTAMP_NTZ '2024-04-01 00:00:00' + make_interval(0, 0, 0, 0, 0, 0, id)
FROM range(1000);

INSERT INTO db.fbs VALUES
  (1005, 7, 7.00, DATE '2024-03-07', TIMESTAMP_NTZ '2024-03-07 00:00:00'),
  (1006, 7, 7.00, DATE '2024-03-07', TIMESTAMP_NTZ '2024-03-07 00:00:00');

INSERT INTO db.fbs VALUES
  (1007, NULL, NULL, NULL, NULL),
  (1008, NULL, NULL, NULL, NULL);

SELECT count(*), min(n), max(n), min(amt), max(amt), min(d), max(d), min(ts), max(ts) FROM db.fbs;
SELECT k, n, amt, d, ts FROM db.fbs WHERE k < 5 OR k > 1004 ORDER BY k;
