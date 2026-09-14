-- Regenerates example/paimon/db.db/pse — a Paimon append table whose schema moved on after its
-- first file was written: a column added, one renamed, and a default set for the new one.
--
-- Paimon evolves by field id like Iceberg, so a file written under schema 0 reads under the
-- current schema with its `v` as `label` and its missing `w` as null. A Paimon default is a
-- write-time value — `DataField.defaultValue` in the schema JSON, filled in when a write omits
-- the column — and not a read-time one: the first run of this script set
-- `fields.w.default-value = 7` as a table option and the read still returned null for every
-- row the option could have applied to (Paimon 1.3, Spark 3.5), which is what `SELECT` at the
-- end prints for A's rows, beside the 7 the DDL default put into B's row that omitted `w`. An
-- append table, because a primary-key table's compaction would rewrite the old file under the
-- new schema and leave nothing to read across the evolution (`se`).
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-fb.sql:
--
--   1  CREATE   (k INT, v STRING), no primary key                       schema-0
--   2  INSERT   (1 a) (2 b)                                              file A under schema 0: k, v
--   3  ALTER    ADD COLUMN w INT                                         schema-1: k, v, w
--   4  ALTER    RENAME COLUMN v TO label                                 schema-2: k, label, w — field 1 renamed
--   5  ALTER    ALTER COLUMN w SET DEFAULT 7                             schema-3: field 2 carries defaultValue 7
--   6  INSERT   (3 c 30); (k, label) (4 d)                               file B under schema 3: k, label, w — the second row's w written as 7
--   7  SELECT * ORDER BY k                                               the oracle: 1 a null / 2 b null / 3 c 30 / 4 d 7
--
-- To regenerate:
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-pse.sql:/tmp/paimon-pse.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-pse.sql"
--   rm -rf example/paimon/db.db/pse && cp -R "$WH/db.db/pse" example/paimon/db.db/pse

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.pse (k INT, v STRING);

INSERT INTO db.pse VALUES (1, 'a'), (2, 'b');

ALTER TABLE db.pse ADD COLUMN w INT;

ALTER TABLE db.pse RENAME COLUMN v TO label;

ALTER TABLE db.pse ALTER COLUMN w SET DEFAULT 7;

INSERT INTO db.pse VALUES (3, 'c', 30);

INSERT INTO db.pse (k, label) VALUES (4, 'd');

SELECT k, label, w FROM db.pse ORDER BY k;
