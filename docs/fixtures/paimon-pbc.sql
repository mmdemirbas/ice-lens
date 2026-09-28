-- Regenerates example/paimon/db.db/pbc — a changelog on a branch: a primary-key table under
-- `changelog-producer = input` with a branch created from a tag and written to, so main and the
-- branch each publish a change stream of their own.
--
-- A branch's commits are snapshot files under branch/branch-dev/snapshot/, and each names a
-- changelog manifest list of its own in the table's manifest/, beside main's; a streaming reader
-- of `pbc$branch_dev` is handed those, and one of `pbc` main's. The branch's snapshot 1 is main's
-- snapshot 1 copied from the tag, changelog list included.
--
--   1  CREATE   primary key k, one bucket, changelog-producer input
--   2  INSERT   (1, a), (2, b)                    main snapshot 1: publishes +I 1 a, +I 2 b
--   3  CALL sys.create_tag  'base' on snapshot 1
--   4  CALL sys.create_branch  'dev' from 'base'  dev snapshot 1: main's copied
--   5  INSERT INTO pbc$branch_dev  (2, B), (3, c)  dev snapshot 2: +I 2 B, +I 3 c
--   6  DELETE FROM pbc$branch_dev WHERE k = 1      dev snapshot 3: -D 1 a
--   7  INSERT   (2, x)                            main snapshot 2: +I 2 x
--
-- The oracle is Paimon's own batch read of the changelog: `paimon_incremental_query` over the
-- `$audit_log` table under `incremental-between-scan-mode = changelog`, one snapshot at a time on
-- each line, printed with the line and the snapshot range. The `SET` key is back-quoted, since
-- spark-sql will not parse a hyphen in an unquoted one.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-pbc.sql:/tmp/paimon-pbc.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-pbc.sql" 2>/dev/null | grep -v '^Time taken\|^Spark master'
--   rm -rf example/paimon/db.db/pbc && cp -R "$WH/db.db/pbc" example/paimon/db.db/pbc
--   find example/paimon/db.db/pbc -name '.*.crc' -delete
--
-- Output of the run the checked-in table came from, 2026-09-28:
--   main  1  a
--   main  2  x
--   dev  2  B
--   dev  3  c
--   spark.paimon.incremental-between-scan-mode  changelog
--   main 0..1  +I  1  a
--   main 0..1  +I  2  b
--   main 1..2  +I  2  x
--   dev 0..1  +I  1  a
--   dev 0..1  +I  2  b
--   dev 1..2  +I  2  B
--   dev 1..2  +I  3  c
--   dev 2..3  -D  1  a

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.pbc (k INT, v STRING)
TBLPROPERTIES (
  'primary-key'        = 'k',
  'bucket'             = '1',
  'changelog-producer' = 'input'
);

INSERT INTO db.pbc VALUES (1, 'a'), (2, 'b');
CALL sys.create_tag('db.pbc', 'base', 1);
CALL sys.create_branch('db.pbc', 'dev', 'base');
INSERT INTO db.`pbc$branch_dev` VALUES (2, 'B'), (3, 'c');
DELETE FROM db.`pbc$branch_dev` WHERE k = 1;
INSERT INTO db.pbc VALUES (2, 'x');

SELECT 'main', * FROM db.pbc ORDER BY k;
SELECT 'dev', * FROM db.`pbc$branch_dev` ORDER BY k;

SET `spark.paimon.incremental-between-scan-mode` = changelog;
SELECT 'main 0..1', * FROM paimon_incremental_query('db.`pbc$audit_log`', 0, 1) ORDER BY k;
SELECT 'main 1..2', * FROM paimon_incremental_query('db.`pbc$audit_log`', 1, 2) ORDER BY k;
SELECT 'dev 0..1', * FROM paimon_incremental_query('db.`pbc$branch_dev$audit_log`', 0, 1) ORDER BY k;
SELECT 'dev 1..2', * FROM paimon_incremental_query('db.`pbc$branch_dev$audit_log`', 1, 2) ORDER BY k;
SELECT 'dev 2..3', * FROM paimon_incremental_query('db.`pbc$branch_dev$audit_log`', 2, 3) ORDER BY k;
