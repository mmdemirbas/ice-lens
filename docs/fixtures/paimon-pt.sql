-- Regenerates example/paimon/db.db/pt — a partitioned Paimon primary-key table.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-dv.sql. Every other Paimon fixture is unpartitioned,
-- and a partitioned table is the ordinary one: its data files live under
-- `<key>=<value>/…/bucket-N/`, and a manifest entry names the file by `_FILE_NAME` and the
-- partition by `_PARTITION` — a serialised BinaryRow, which this tool did not decode, so every
-- data file of a partitioned table resolved to a path that does not exist. This fixture is where
-- the decoder meets bytes Paimon wrote, and the directory layout is the oracle for it: the
-- partition read out of the entry has to be the directory the file is in.
--
-- Two partition keys of the two encodings a BinaryRow uses: a DATE (a fixed-width int, days
-- since the epoch) and a STRING, which is stored inline in the 8-byte slot when it is 7 bytes or
-- shorter and in the variable-length tail when it is longer. `eu` is the short one and
-- `north-america` the long one, so both branches meet real bytes.
--
-- Statements, in order — the numbers matter because the expected values in
-- PaimonPartitionFixtureTest are read off them:
--
--   1  CREATE   primary key (dt, region, k), partitioned by (dt, region), one bucket
--   2  INSERT   six rows over 2024-03-05 × {eu, north-america} and 2024-03-06 × {eu, north-america}
--               — four partitions, one file each
--   3  INSERT   two more rows into 2024-03-06 / eu — a second file in one partition
--   4  INSERT   one row into 2024-03-07 / eu and one into 2024-03-05 / north-america, in one
--               commit — so the manifest's `_PARTITION_STATS` has to say whether its minimum is
--               a *row* (2024-03-05, north-america: the lexicographically first entry) or a
--               *column-wise* minimum (2024-03-05, eu: a partition no entry of it has). The
--               first two inserts cannot tell the two apart
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required, and why
-- the shell must be `bash -c` rather than `bash -lc`). The jar is a local build from lakelab;
-- any paimon-spark-3.5 runtime jar of version 1.x should do:
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-pt.sql:/tmp/paimon-pt.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-pt.sql"
--   rm -rf example/paimon/db.db/pt && cp -R "$WH/db.db/pt" example/paimon/db.db/pt

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.pt (k INT, dt DATE, region STRING, v STRING)
PARTITIONED BY (dt, region)
TBLPROPERTIES (
  'primary-key' = 'dt,region,k',
  'bucket' = '1'
);

INSERT INTO db.pt VALUES
  (1, DATE'2024-03-05', 'eu',            'alpha'),
  (2, DATE'2024-03-05', 'eu',            'bravo'),
  (3, DATE'2024-03-05', 'north-america', 'charlie'),
  (4, DATE'2024-03-06', 'eu',            'delta'),
  (5, DATE'2024-03-06', 'north-america', 'echo'),
  (6, DATE'2024-03-06', 'north-america', 'foxtrot');

INSERT INTO db.pt VALUES
  (7, DATE'2024-03-06', 'eu',            'golf'),
  (8, DATE'2024-03-06', 'eu',            'hotel');

INSERT INTO db.pt VALUES
  (9,  DATE'2024-03-07', 'eu',            'india'),
  (10, DATE'2024-03-05', 'north-america', 'juliet');
