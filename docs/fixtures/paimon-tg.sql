-- Regenerates example/paimon/db.db/tg — a Paimon primary-key table with a tag on a snapshot that
-- has since been expired.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-dv.sql. The first four statements are paimon-cl.sql's,
-- so the shape is known; the last two are the point. A Paimon tag is a copy of a snapshot file
-- under tag/, and expire_snapshots keeps every file a tag still reaches, so after the expiry the
-- bucket holds files that no snapshot under snapshot/ names and the tag alone does. A reader that
-- walks snapshot/ only reports those as orphans; they are not, and this table is what says so.
--
-- Statements, in order — the numbers matter because the expected values in PaimonTagFixtureTest
-- are read off them:
--
--   1  CREATE   primary key k, one bucket, changelog-producer = input
--   2  INSERT   k = 1, 2, 3                       snapshot 1: 3 rows, changelog of 3
--   3  INSERT   k = 2 (a new value), 4            snapshot 2: 2 rows, changelog of 2
--   4  DELETE   k = 3                             snapshot 3: one -D row, changelog of 1
--   5  INSERT OVERWRITE  k = 10, 11               snapshot 4: OVERWRITE, every file removed,
--                                                 one 2-row file added — and, as in cl, a
--                                                 changelog file written and never committed
--   6  CALL sys.create_tag  'first' on snapshot 1
--   7  CALL sys.expire_snapshots  retain_max = 1  snapshots 1, 2 and 3 expire; snapshot 1's data
--                                                 file, changelog file, manifests and manifest
--                                                 lists survive because the tag names them;
--                                                 snapshots 2 and 3's do not
--
-- Expected on disk afterwards: snapshot/ holds 4 only (EARLIEST = LATEST = 4); tag/tag-first is
-- snapshot 1 verbatim plus the tag's own fields; bucket-0 holds snapshot 1's data and changelog
-- file, snapshot 4's data file, and the overwrite's uncommitted changelog file — one true orphan,
-- and two files only the tag reaches.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-tg.sql:/tmp/paimon-tg.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-tg.sql"
--   rm -rf example/paimon/db.db/tg && cp -R "$WH/db.db/tg" example/paimon/db.db/tg

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.tg (k INT, v STRING)
TBLPROPERTIES (
  'primary-key' = 'k',
  'bucket' = '1',
  'changelog-producer' = 'input'
);

INSERT INTO db.tg VALUES (1, 'a'), (2, 'b'), (3, 'c');

INSERT INTO db.tg VALUES (2, 'B'), (4, 'd');

DELETE FROM db.tg WHERE k = 3;

INSERT OVERWRITE db.tg VALUES (10, 'x'), (11, 'y');

CALL sys.create_tag(table => 'db.tg', tag => 'first', snapshot => 1);

CALL sys.expire_snapshots(table => 'db.tg', retain_max => 1, retain_min => 1);
