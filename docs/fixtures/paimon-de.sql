-- Regenerates example/paimon/db.db/de — a Paimon append table in data-evolution mode, where a
-- MERGE INTO that sets one column writes a file holding only that column.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-rt.sql. Data evolution sits on row tracking: a
-- partial-column file records the columns it writes in _WRITE_COLS and the same _FIRST_ROW_ID as
-- the file it patches, and a read stitches the two by row id. Every data file this app had met
-- carried every column, so _WRITE_COLS was never read and a file's _VALUE_STATS was always decoded
-- against the whole schema. This table is what a partial file looks like.
--
-- Statements, in order — the numbers matter because the expected values in
-- PaimonDataEvolutionFixtureTest are read off them:
--
--   1  CREATE   append table (id, b, c), bucket = -1, row-tracking + data-evolution enabled
--   2  INSERT   (1, 1, 1), (2, 2, 2)                snapshot 1: one file of three columns, rows 0..1
--   3  CREATE + INSERT the source table db.de_src   (1, 11), (2, 22), (3, 33)
--   4  MERGE INTO  matched: SET b = s.b             snapshot 2: a file of column b only, patching
--                  not matched: INSERT (id, b, 0)     rows 0..1, and a full file for the new row 3
--
-- The last statement prints the merged rows the reader is expected to produce.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-de.sql:/tmp/paimon-de.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-de.sql"
--   rm -rf example/paimon/db.db/de && cp -R "$WH/db.db/de" example/paimon/db.db/de

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.de (id INT, b INT, c INT)
TBLPROPERTIES (
  'bucket' = '-1',
  'row-tracking.enabled' = 'true',
  'data-evolution.enabled' = 'true'
);

INSERT INTO db.de VALUES (1, 1, 1), (2, 2, 2);

CREATE TABLE db.de_src (id INT, b INT);
INSERT INTO db.de_src VALUES (1, 11), (2, 22), (3, 33);

MERGE INTO db.de AS t
USING db.de_src AS s
ON t.id = s.id
WHEN MATCHED THEN UPDATE SET t.b = s.b
WHEN NOT MATCHED THEN INSERT (id, b, c) VALUES (s.id, s.b, 0);

SELECT id, b, c, _ROW_ID, _SEQUENCE_NUMBER FROM db.de ORDER BY id;
