-- Regenerates example/paimon/db.db/ft — a Paimon append table with a bloom-filter file index on
-- a TIMESTAMP, a TIMESTAMP WITH LOCAL TIME ZONE and a DATE column, embedded in each entry. It is
-- the oracle for the temporal half of FastHash (release-1.3.1): a DATE is hashed over its epoch
-- day, a timestamp of either kind over its microseconds since the epoch at precision 6 (its
-- milliseconds at precision 3 and below, which Spark cannot write — see the test), through the
-- same 64-bit integer hash as an INT. `fa` covers the string and integer hashes.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-fa.sql. Two things about the types: Spark's
-- TIMESTAMP_NTZ arrives as Paimon TIMESTAMP(6) and Spark's TIMESTAMP as TIMESTAMP(6) WITH LOCAL
-- TIME ZONE (SparkTypeUtils at 1.3.1), so both are written from one statement; and the session
-- time zone is the container's UTC, so the `+00:00` literals below are the instants the file holds.
--
-- Statements, in order — the values matter because PaimonFileIndexTest reads off them:
--
--   1  CREATE   no primary key, bloom filter on ts, lz and d with 100 items each — 60 bytes a
--               column, under file-index.in-manifest-threshold (500 B), so the index is embedded
--   2  INSERT   k = 1, 2, 3 at 2024-03-05 10:00:00.123456, 2024-03-06 11:30:00 and
--               2024-03-07 00:00:00.000001 on both timestamp columns, d the same three dates
--               snapshot 1: one data file, index embedded in its entry
--
-- The microsecond parts are the point: a hash over milliseconds and one over microseconds agree
-- on a whole-second value, and only a fractional value tells the two readings apart.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-ft.sql:/tmp/paimon-ft.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.sql.session.timeZone=UTC \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-ft.sql"
--   rm -rf example/paimon/db.db/ft && cp -R "$WH/db.db/ft" example/paimon/db.db/ft

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.ft (k INT, ts TIMESTAMP_NTZ, lz TIMESTAMP, d DATE)
TBLPROPERTIES (
  'file-index.bloom-filter.columns' = 'ts,lz,d',
  'file-index.bloom-filter.ts.items' = '100',
  'file-index.bloom-filter.lz.items' = '100',
  'file-index.bloom-filter.d.items' = '100'
);

INSERT INTO db.ft VALUES
  (1, TIMESTAMP_NTZ '2024-03-05 10:00:00.123456', TIMESTAMP '2024-03-05 10:00:00.123456+00:00', DATE '2024-03-05'),
  (2, TIMESTAMP_NTZ '2024-03-06 11:30:00',        TIMESTAMP '2024-03-06 11:30:00+00:00',        DATE '2024-03-06'),
  (3, TIMESTAMP_NTZ '2024-03-07 00:00:00.000001', TIMESTAMP '2024-03-07 00:00:00.000001+00:00', DATE '2024-03-07');

SELECT k, ts, lz, d FROM db.ft ORDER BY k;
