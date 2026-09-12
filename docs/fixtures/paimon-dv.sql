-- Regenerates example/paimon/db.db/dv — a Paimon primary-key table with deletion vectors.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3. The other Paimon fixture, example/paimon/db.db/test, was written by Flink
-- and carries a HASH index and nothing else; this one exists because the index manifest's
-- `_DELETIONS_VECTORS_RANGES` branch was modelled from that file's own Avro schema and had never
-- met real bytes. Whether a DELETE on a primary-key table with `deletion-vectors.enabled`
-- writes a vector rather than a `-D` row into the LSM is exactly the kind of thing to find out by
-- running it rather than reading about it, and the index manifest says which happened.
--
-- Statements, in order — the numbers matter because the expected values in
-- PaimonIndexManifestTest are read off them:
--
--   1  CREATE   primary key k, one bucket, deletion vectors on
--   2  INSERT   a thousand rows, k = 1..1000
--   3  INSERT   five hundred more, k = 1001..1500 — a second data file, so a vector has to
--               name which one
--   4  DELETE   k IN (2, 1001, 1500): one row from the first file, two from the second
--
-- The row counts are not decoration. The first attempt inserted four rows and two, and the
-- compaction that follows every write on a deletion-vector table merged the whole bucket into one
-- new file instead of marking anything: with three runs of near-identical size, universal
-- compaction's size-ratio rule picked all of them. A vector is written only when an L0 row
-- shadows a key in a *higher-level file that the compaction leaves alone*, so the higher-level
-- files have to be large next to the L0 file carrying the deletes. A thousand rows against three
-- is that.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required, and why
-- the shell must be `bash -c` rather than `bash -lc`). The jar is a local build from lakelab;
-- any paimon-spark-3.5 runtime jar of version 1.x should do:
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-dv.sql:/tmp/paimon-dv.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-dv.sql"
--   rm -rf example/paimon/db.db/dv && cp -R "$WH/db.db/dv" example/paimon/db.db/dv

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.dv (k INT, v STRING)
TBLPROPERTIES (
  'primary-key' = 'k',
  'bucket' = '1',
  'deletion-vectors.enabled' = 'true'
);

INSERT INTO db.dv SELECT id AS k, concat('v', id) AS v FROM range(1, 1001);

INSERT INTO db.dv SELECT id AS k, concat('v', id) AS v FROM range(1001, 1501);

DELETE FROM db.dv WHERE k IN (2, 1001, 1500);
