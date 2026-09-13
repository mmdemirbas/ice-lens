-- Regenerates example/paimon/db.db/fb — a Paimon append table with a bitmap file index on two
-- columns, one of which also carries a bloom filter, over three files: one with a null and two
-- values of `c`, one where every row is `red`, one where every `c` is null. The bitmap index is
-- a dictionary — one Roaring bitmap per distinct value, plus one for null — so unlike the bloom
-- filter it answers `=` exactly, and it answers `<>`, `IS NULL` and `IS NOT NULL` too: a `<>` is
-- the value's bitmap flipped over the row count, empty only when every row holds the value.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-fa.sql.
--
-- Statements, in order — the values matter because PaimonFileIndexTest and the plan oracle in
-- paimon-scan-plans.scala read off them:
--
--   1  CREATE   no primary key; bitmap on c and n, bloom filter (100 items) on n as well
--   2  INSERT   (1 red 1) (2 green 2) (3 red 3) (4 null 4)     file 1: c has red, green, a null; index embedded
--   3  ALTER    file-index.in-manifest-threshold = 1b            every later index goes to a .index file
--   4  INSERT   (5 red 5) (6 red 6)                              file 2: every row red — the one `c <> 'red'` skips
--   5  INSERT   (7 null 7) (8 null 8)                            file 3: every c null — the one `c IS NOT NULL` skips
--
-- `orange` sits between green and red, so `c = 'orange'` is inside file 1's bounds and only
-- the dictionary rules it out.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-fb.sql:/tmp/paimon-fb.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-fb.sql"
--   rm -rf example/paimon/db.db/fb && cp -R "$WH/db.db/fb" example/paimon/db.db/fb

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.fb (k INT, c STRING, n INT)
TBLPROPERTIES (
  'file-index.bitmap.columns' = 'c,n',
  'file-index.bloom-filter.columns' = 'n',
  'file-index.bloom-filter.n.items' = '100'
);

INSERT INTO db.fb VALUES (1, 'red', 1), (2, 'green', 2), (3, 'red', 3), (4, NULL, 4);

ALTER TABLE db.fb SET TBLPROPERTIES ('file-index.in-manifest-threshold' = '1b');

INSERT INTO db.fb VALUES (5, 'red', 5), (6, 'red', 6);

INSERT INTO db.fb VALUES (7, NULL, 7), (8, NULL, 8);

SELECT k, c, n FROM db.fb ORDER BY k;
