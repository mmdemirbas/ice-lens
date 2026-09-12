-- Regenerates example/paimon/db.db/br — a Paimon primary-key table with two branches: one created
-- from a tag and written to, and one created empty.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-tg.sql. A Paimon branch is a nested table layout under
-- branch/branch-<name>/ — its own snapshot/, schema/ and manifest/ — whose data files are written
-- into the main table's bucket directories beside main's own. Snapshot ids are per branch, so
-- main and dev both hold a snapshot-2 and they are different commits. A reader that walks
-- snapshot/ alone reports the branch's data file as an orphan and never sees its commits; this
-- table is what says so.
--
-- Statements, in order — the numbers matter because the expected values in PaimonBranchFixtureTest
-- are read off them:
--
--   1  CREATE   primary key k, one bucket
--   2  INSERT   k = 1, 2, 3                       main snapshot 1: 3 rows
--   3  INSERT   k = 4                             main snapshot 2: 1 row
--   4  CALL sys.create_tag  'base' on snapshot 1
--   5  CALL sys.create_branch  'dev' from tag 'base'   branch/branch-dev/: snapshot 1 copied
--   6  INSERT INTO br$branch_dev  k = 5           dev snapshot 2: 1 row, a file in bucket-0
--   7  INSERT   k = 6                             main snapshot 3: 1 row
--   8  CALL sys.create_branch  'empty'            branch/branch-empty/: a schema and no snapshot
--
-- Expected on disk afterwards: snapshot/ holds 1..3; branch/branch-dev/snapshot/ holds 1 and 2,
-- where 1 is main's snapshot 1 verbatim; branch/branch-empty/ holds schema/ only; bucket-0 holds
-- four data files, one of which only dev's snapshot 2 names.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-br.sql:/tmp/paimon-br.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-br.sql"
--   rm -rf example/paimon/db.db/br && cp -R "$WH/db.db/br" example/paimon/db.db/br

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.br (k INT, v STRING)
TBLPROPERTIES (
  'primary-key' = 'k',
  'bucket' = '1'
);

INSERT INTO db.br VALUES (1, 'a'), (2, 'b'), (3, 'c');

INSERT INTO db.br VALUES (4, 'd');

CALL sys.create_tag('db.br', 'base', 1);

CALL sys.create_branch('db.br', 'dev', 'base');

INSERT INTO db.`br$branch_dev` VALUES (5, 'e');

INSERT INTO db.br VALUES (6, 'f');

CALL sys.create_branch('db.br', 'empty');
