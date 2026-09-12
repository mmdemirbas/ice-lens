-- Regenerates example/paimon/db.db/cl — a Paimon primary-key table with a changelog, an
-- overwrite and an ANALYZE commit.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-dv.sql. It exists because three things the model
-- draws had never met real bytes: the *changelog* manifest list (a third list beside base and
-- delta, carrying the change stream rather than the table's contents — excluded from `current`
-- on purpose), and the OVERWRITE and ANALYZE commit kinds. ANALYZE also writes a `statistics`
-- field into the snapshot that names a file under statistics/, which nothing here read.
--
-- Statements, in order — the numbers matter because the expected values in
-- PaimonChangelogFixtureTest are read off them:
--
--   1  CREATE   primary key k, one bucket, changelog-producer = input
--   2  INSERT   k = 1, 2, 3                       APPEND, 3 rows, changelog of 3
--   3  INSERT   k = 2 (a new value), 4            APPEND, 2 rows, changelog of 2 — the input
--                                                 producer records what was written, not what
--                                                 it replaced, so the update is one +I row
--   4  DELETE   k = 3                             APPEND, one -D row, changelog of 1
--   5  INSERT OVERWRITE  k = 10, 11               OVERWRITE: every file the table held is
--                                                 removed and two rows are written
--   6  ANALYZE TABLE … FOR ALL COLUMNS            ANALYZE: no data change, a statistics file
--                                                 with table figures and per-column stats
--
-- `changelog-producer = input` rather than `lookup` or `full-compaction` because it is the one
-- producer that writes a changelog file on *every* commit without a compaction behind it, so the
-- changelog manifest list appears on the same snapshots as the delta list and the two can be
-- compared entry for entry. No deletion vectors, no forced compaction: three sorted runs stay
-- below the default trigger of five, so the level-0 files are left as written.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-cl.sql:/tmp/paimon-cl.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-cl.sql"
--   rm -rf example/paimon/db.db/cl && cp -R "$WH/db.db/cl" example/paimon/db.db/cl

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.cl (k INT, v STRING)
TBLPROPERTIES (
  'primary-key' = 'k',
  'bucket' = '1',
  'changelog-producer' = 'input'
);

INSERT INTO db.cl VALUES (1, 'a'), (2, 'b'), (3, 'c');

INSERT INTO db.cl VALUES (2, 'B'), (4, 'd');

DELETE FROM db.cl WHERE k = 3;

INSERT OVERWRITE db.cl VALUES (10, 'x'), (11, 'y');

ANALYZE TABLE db.cl COMPUTE STATISTICS FOR ALL COLUMNS;
