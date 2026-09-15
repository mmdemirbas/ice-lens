-- Regenerates example/paimon/db.db/frb — a Paimon append table with a range bitmap
-- (`range-bitmap`, release-1.3.0's fourth file index) on seven columns of every kind the writer
-- takes: an INT, a STRING, a DECIMAL(10,2), a DATE, a TIMESTAMP(6), a DOUBLE and a BOOLEAN. A
-- range bitmap is a dictionary of the column's distinct values in the type's order, each with a
-- code by rank, and a bit-sliced index over the codes — so it answers `<`, `<=`, `>`, `>=`,
-- `BETWEEN`, `=`, `<>` and the null tests per row on a string, a float or a boolean, which the
-- `bsi` refuses, and on a decimal or a timestamp too.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-fbs.sql.
--
-- Statements, in order — the values matter because PaimonRangeBitmapIndexTest and the plan oracle
-- in paimon-scan-plans.scala read off them:
--
--   1  CREATE   no primary key; range-bitmap on n, s, amt, d, ts, x, b; a 64 KB in-manifest
--               threshold so file 1 embeds; `n` cut into 32-byte chunks (8 more INT keys behind
--               each first key) and `s` into 64-byte ones, so file 2's dictionaries run to a
--               hundred-odd chunks and the search across them is exercised where the 16 KB
--               default would put a thousand keys in one
--   2  INSERT   five rows: n -5, 3, 10, null, 3 — a negative, a gap between 3 and 10, a null, and
--               a value held twice (bravo / 0.75 / 2024-03-05 / 0.25 / false on rows 2 and 5),
--               so the dictionary has 3 keys over 4 non-null rows                   file 1, embedded
--   3  ALTER    file-index.in-manifest-threshold = 1b                every later index goes to a .index file
--   4  INSERT   a thousand rows: n 100..1099, s v0000..v0999, amt 1.00..10.99, d and ts a day
--               and a second apart, x id/8 - 10 (-10.0 .. 114.875), b even ids            file 2
--   5  INSERT   (1005 7 'seven' 7.00 2024-03-07 … 7.0 true) twice        file 3: one value — cardinality 1
--   6  INSERT   (1007 null …) (1008 null …) (1009 null …)                 file 4: every value null, three rows
--
-- File 4 has three rows because `RangeBitmap.isNull` at cardinality 0 answers `bitmapOf(0, rid - 1)`
-- — the first and last row, not the range — so Paimon counts 2 of its 3 rows as null; two rows
-- would hide it. `n BETWEEN 4 AND 6` sits inside file 1's bounds (-5..10) and only the index rules
-- it out; `s > 'bravo'` and `x < 0` are the string and float orders the bsi cannot give.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-frb.sql:/tmp/paimon-frb.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-frb.sql"
--   rm -rf example/paimon/db.db/frb && cp -R "$WH/db.db/frb" example/paimon/db.db/frb
--   find example/paimon/db.db/frb -name '.*.crc' -delete

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.frb (k INT, n INT, s STRING, amt DECIMAL(10,2), d DATE, ts TIMESTAMP_NTZ, x DOUBLE, b BOOLEAN)
TBLPROPERTIES (
  'file-index.range-bitmap.columns' = 'n,s,amt,d,ts,x,b',
  'file-index.range-bitmap.n.chunk-size' = '32b',
  'file-index.range-bitmap.s.chunk-size' = '64b',
  'file-index.in-manifest-threshold' = '64kb'
);

INSERT INTO db.frb VALUES
  (1, -5, 'alpha', -12.50, DATE '2024-03-01', TIMESTAMP_NTZ '2024-03-01 10:00:00.000001', -1.5, true),
  (2, 3, 'bravo', 0.75, DATE '2024-03-05', TIMESTAMP_NTZ '2024-03-01 10:00:00.000005', 0.25, false),
  (3, 10, 'charlie', 99.99, DATE '2024-03-09', TIMESTAMP_NTZ '2024-03-01 10:00:00.000009', 2.5, true),
  (4, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  (5, 3, 'bravo', 0.75, DATE '2024-03-05', TIMESTAMP_NTZ '2024-03-01 10:00:00.000005', 0.25, false);

ALTER TABLE db.frb SET TBLPROPERTIES ('file-index.in-manifest-threshold' = '1b');

INSERT INTO db.frb
SELECT CAST(id + 6 AS INT), CAST(id + 100 AS INT), concat('v', lpad(CAST(id AS STRING), 4, '0')),
       CAST((id + 100) / 100 AS DECIMAL(10,2)),
       DATE_ADD(DATE '2024-04-01', CAST(id AS INT)),
       TIMESTAMP_NTZ '2024-04-01 00:00:00' + make_interval(0, 0, 0, 0, 0, 0, id),
       CAST(id AS DOUBLE) / 8 - 10,
       id % 2 = 0
FROM range(1000);

INSERT INTO db.frb VALUES
  (1006, 7, 'seven', 7.00, DATE '2024-03-07', TIMESTAMP_NTZ '2024-03-07 00:00:00', 7.0, true),
  (1007, 7, 'seven', 7.00, DATE '2024-03-07', TIMESTAMP_NTZ '2024-03-07 00:00:00', 7.0, true);

INSERT INTO db.frb VALUES
  (1008, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  (1009, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  (1010, NULL, NULL, NULL, NULL, NULL, NULL, NULL);

SELECT count(*), min(n), max(n), min(s), max(s), min(amt), max(amt), min(d), max(d), min(ts), max(ts), min(x), max(x) FROM db.frb;
SELECT k, n, s, amt, d, ts, x, b FROM db.frb WHERE k < 6 OR k > 1005 ORDER BY k;
