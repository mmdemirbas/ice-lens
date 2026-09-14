-- Regenerates example/paimon/db.db/pne — `pse` one level down: a Paimon append table with a
-- struct and a list of structs, whose schema moved on *inside* them after its first file was
-- written — a field renamed inside the struct, one added to it, and a field renamed inside the
-- list's element.
--
-- Paimon evolves nested rows by field id too (`SchemaEvolutionUtil.createRowCastExecutor` at
-- 1.3.1 maps a row's fields by `DataField.id()`, and arrays and maps through their element),
-- so a file written under schema 0 reads under the current schema with `addr.city` as
-- `addr.town`, `addr.country` null, and `items[].sku` as `items[].code`. An append table, for
-- the reason `pse` is one: a primary-key table's compaction would rewrite the old file under
-- the new schema and leave nothing to read across the evolution.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-pse.sql:
--
--   1  CREATE   (k INT, addr STRUCT<city, zip>, items ARRAY<STRUCT<sku, qty>>), no primary key   schema-0
--   2  INSERT   two rows                                                                          file A under schema 0
--   3  ALTER    RENAME COLUMN addr.city TO town                                                   schema-1
--   4  ALTER    ADD COLUMN addr.country STRING                                                    schema-2
--   5  ALTER    RENAME COLUMN items.element.sku TO code                                           schema-3
--   6  INSERT   one row under the new shape                                                       file B under schema 3
--   7  SELECT   the oracles: every row under the new names, and `addr.town = 'Ankara'` finding row 1
--
-- To regenerate:
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-pne.sql:/tmp/paimon-pne.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-pne.sql"
--   rm -rf example/paimon/db.db/pne && cp -R "$WH/db.db/pne" example/paimon/db.db/pne
--   find example/paimon/db.db/pne -name '.*.crc' -delete
--
-- Observed (2026-09-14), Paimon 1.3 / Spark 3.5.5:
--
--   1  {"town":"Ankara","zip":6000,"country":null}   [{"code":"A1","qty":2},{"code":"A2","qty":1}]
--   2  {"town":"Izmir","zip":35000,"country":null}   [{"code":"B1","qty":5}]
--   3  {"town":"Bursa","zip":16000,"country":"TR"}   [{"code":"C1","qty":7}]
--   addr.town = 'Ankara'   -> 1  Ankara  NULL
--   addr.country IS NULL   -> 1, 2

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.pne (
  k     INT,
  addr  STRUCT<city: STRING, zip: INT>,
  items ARRAY<STRUCT<sku: STRING, qty: INT>>
);

INSERT INTO db.pne VALUES
  (1, named_struct('city', 'Ankara', 'zip', 6000), array(named_struct('sku', 'A1', 'qty', 2), named_struct('sku', 'A2', 'qty', 1))),
  (2, named_struct('city', 'Izmir', 'zip', 35000), array(named_struct('sku', 'B1', 'qty', 5)));

ALTER TABLE db.pne RENAME COLUMN addr.city TO town;
ALTER TABLE db.pne ADD COLUMN addr.country STRING;
ALTER TABLE db.pne RENAME COLUMN items.element.sku TO code;

INSERT INTO db.pne VALUES
  (3, named_struct('town', 'Bursa', 'zip', 16000, 'country', 'TR'), array(named_struct('code', 'C1', 'qty', 7)));

SELECT * FROM db.pne ORDER BY k;
SELECT k, addr.town, addr.country FROM db.pne WHERE addr.town = 'Ankara';
SELECT k FROM db.pne WHERE addr.country IS NULL ORDER BY k;
