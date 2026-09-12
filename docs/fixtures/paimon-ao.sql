-- Regenerates example/paimon/db.db/ao — a Paimon append-only table, partitioned, unaware-bucket.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-dv.sql. Every other Paimon fixture is a primary-key
-- table; an append table with `bucket = -1` is the other common shape — a log table — and it is
-- different in the places this tool reads: there is no key, so `_MIN_KEY` / `_MAX_KEY` are
-- rows over nothing, every file lands in `bucket-0` whatever its partition, and a DELETE has no
-- merge engine to write a `-D` row into, so it rewrites the files it touches.
--
-- Statements, in order — the numbers matter because the expected values in
-- PaimonAppendOnlyFixtureTest are read off them:
--
--   1  CREATE   no primary key, partitioned by dt, bucket = -1
--   2  INSERT   ids 1..3 on 2024-03-05, 4..5 on 2024-03-06 — one file per partition
--   3  INSERT   ids 6..7 on 2024-03-05 — a second file in that partition
--   4  DELETE   id = 2 — the file holding 1..3 is rewritten without it
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required, and why
-- the shell must be `bash -c` rather than `bash -lc`). The jar is a local build from lakelab;
-- any paimon-spark-3.5 runtime jar of version 1.x should do:
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-ao.sql:/tmp/paimon-ao.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-ao.sql"
--   rm -rf example/paimon/db.db/ao && cp -R "$WH/db.db/ao" example/paimon/db.db/ao

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.ao (id INT, dt DATE, msg STRING)
PARTITIONED BY (dt)
TBLPROPERTIES (
  'bucket' = '-1'
);

INSERT INTO db.ao VALUES
  (1, DATE'2024-03-05', 'alpha'),
  (2, DATE'2024-03-05', 'bravo'),
  (3, DATE'2024-03-05', 'charlie'),
  (4, DATE'2024-03-06', 'delta'),
  (5, DATE'2024-03-06', 'echo');

INSERT INTO db.ao VALUES
  (6, DATE'2024-03-05', 'foxtrot'),
  (7, DATE'2024-03-05', 'golf');

DELETE FROM db.ao WHERE id = 2;
