-- Regenerates example/paimon/db.db/se — a Paimon primary-key table whose schema changed between
-- two writes, and was then compacted, so one manifest holds files written under two schemas.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-rt.sql. A data file's _VALUE_STATS is a BinaryRow
-- over the fields of the schema the FILE was written under, named by its own _SCHEMA_ID; a
-- manifest has a _SCHEMA_ID of its own, and the two differ as soon as a manifest written under
-- the new schema lists a file written under the old — which a compaction's delta manifest does,
-- because it records the files it removed. Decoding those stats against the manifest's schema
-- reads a two-field row as three and finds nothing; this table is what shows it.
--
-- Statements, in order — the numbers matter because the expected values in
-- PaimonSchemaEvolutionFixtureTest are read off them:
--
--   1  CREATE   primary key k, bucket = 1, compaction.min.file-num = 2      schema-0: k INT, v STRING
--   2  INSERT   (1, 'a'), (2, 'b')                                         snapshot 1: file A under schema 0 — k 1..2, v a..b
--   3  ALTER    ADD COLUMN w INT                                           schema-1: k, v, w
--   4  INSERT   (3, 'c', 30), (1, 'aa', 10)                                snapshot 2: file B under schema 1 — k 1..3, v aa..c, w 10..30; k = 1 shadows A's
--   5  CALL sys.compact  full                                              snapshot 3: delta manifest under schema 1 removes A (schema 0) and B, adds C —
--                                                                          three rows (1,'aa',10) (2,'b',null) (3,'c',30): k 1..3, v aa..c, w 10..30, w null count 1
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-se.sql:/tmp/paimon-se.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-se.sql"
--   rm -rf example/paimon/db.db/se && cp -R "$WH/db.db/se" example/paimon/db.db/se
--   find example/paimon/db.db/se -name ".*" -delete

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.se (k INT, v STRING)
TBLPROPERTIES (
  'primary-key' = 'k',
  'bucket' = '1',
  'compaction.min.file-num' = '2'
);

INSERT INTO db.se VALUES (1, 'a'), (2, 'b');

ALTER TABLE db.se ADD COLUMN w INT;

INSERT INTO db.se VALUES (3, 'c', 30), (1, 'aa', 10);

CALL sys.compact(table => 'db.se');
