-- Regenerates example/iceberg/default/migdeep — `migrated` with nested columns: a plain-Spark
-- Parquet file holding a struct, a list of structs and a map, brought in with `add_files`, then
-- a rename and an add *inside* the struct and a rename inside the list's element.
--
-- Such a file carries no field ids at any level, so a reader places every nested field through
-- `schema.name-mapping.default` too — the mapping is a tree, `fields` under a field, and
-- Iceberg's ApplyNameMapping walks it by the path of names with a list's element normalised to
-- `element` and a map's entries to `key` / `value`. `add_files` writes the whole tree
-- (MappingUtil.create over the schema), and a rename or an add inside a struct updates it.
--
-- Written by Spark 3.5.5 / Iceberg 1.8.1 from the tabulario/spark-iceberg image:
--
--   1  CREATE   spark_catalog.default.plaindeep USING parquet at /wh/deep-files — not Iceberg
--   2  INSERT   two rows                                                one plain Parquet file, no field ids
--   3  CREATE   lens.default.migdeep, the same columns, Iceberg v2
--   4  CALL     add_files(source_table => `parquet`.`/wh/deep-files`)   the name mapping set, nested fields included
--   5  ALTER    RENAME COLUMN addr.city TO town
--   6  ALTER    ADD COLUMN addr.country STRING
--   7  ALTER    RENAME COLUMN items.element.sku TO code
--   8  INSERT   one row under the new shape                              an Iceberg-written file, ids in its footer
--   9  SELECT   the oracles: every row under the new names, and `addr.town = 'Ankara'` finding row 1
--
-- The plain files are copied beside the table as example/iceberg/deep-files/, the way
-- migrated's plain-files are.
--
-- Observed (2026-09-14), the mapping after the three ALTERs — field 5 `["town", "city"]`, 12
-- `country` added under `addr`, the list's element field 7 named `element` with 8
-- `["code", "sku"]` under it, the map's 10 `key` and 11 `value` — and the reads:
--
--   1  {"town":"Ankara","zip":6000,"country":null}   [{"code":"A1","qty":2},{"code":"A2","qty":1}]  {"color":1}
--   2  {"town":"Izmir","zip":35000,"country":null}   [{"code":"B1","qty":5}]                        {"color":2,"size":3}
--   3  {"town":"Bursa","zip":16000,"country":"TR"}   [{"code":"C1","qty":7}]                        {"size":9}
--   addr.town = 'Ankara'      -> 1  Ankara  NULL
--   addr.country IS NULL      -> 1, 2
--
-- To regenerate (see docs/fixtures/mor.sql for the spark.conf and why --entrypoint bash):
--
--   WH=$(mktemp -d); mkdir -p "$WH/wh"      # spark.conf as in mor.sql, written into $WH
--   docker run --rm --entrypoint bash \
--     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/migdeep.sql:/tmp/migdeep.sql:ro" \
--     -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[1]' --properties-file /tmp/spark.conf -f /tmp/migdeep.sql"
--   rm -rf example/iceberg/default/migdeep example/iceberg/deep-files
--   cp -R "$WH/wh/default/migdeep" example/iceberg/default/migdeep
--   cp -R "$WH/wh/deep-files" example/iceberg/deep-files
--   find example/iceberg/default/migdeep example/iceberg/deep-files \( -name '.*.crc' -o -name '_SUCCESS' \) -delete

CREATE TABLE spark_catalog.default.plaindeep (
  id    INT,
  addr  STRUCT<city: STRING, zip: INT>,
  items ARRAY<STRUCT<sku: STRING, qty: INT>>,
  attrs MAP<STRING, INT>
) USING parquet LOCATION '/wh/deep-files';

INSERT INTO spark_catalog.default.plaindeep VALUES
  (1, named_struct('city', 'Ankara', 'zip', 6000), array(named_struct('sku', 'A1', 'qty', 2), named_struct('sku', 'A2', 'qty', 1)), map('color', 1)),
  (2, named_struct('city', 'Izmir', 'zip', 35000), array(named_struct('sku', 'B1', 'qty', 5)), map('color', 2, 'size', 3));

CREATE TABLE lens.default.migdeep (
  id    INT,
  addr  STRUCT<city: STRING, zip: INT>,
  items ARRAY<STRUCT<sku: STRING, qty: INT>>,
  attrs MAP<STRING, INT>
) USING iceberg TBLPROPERTIES ('format-version' = '2');

CALL lens.system.add_files(table => 'default.migdeep', source_table => '`parquet`.`/wh/deep-files`');

ALTER TABLE lens.default.migdeep RENAME COLUMN addr.city TO town;
ALTER TABLE lens.default.migdeep ADD COLUMN addr.country STRING;
ALTER TABLE lens.default.migdeep RENAME COLUMN items.element.sku TO code;

INSERT INTO lens.default.migdeep VALUES
  (3, named_struct('town', 'Bursa', 'zip', 16000, 'country', 'TR'), array(named_struct('code', 'C1', 'qty', 7)), map('size', 9));

SELECT * FROM lens.default.migdeep ORDER BY id;
SELECT id, addr.town, addr.country FROM lens.default.migdeep WHERE addr.town = 'Ankara';
SELECT id FROM lens.default.migdeep WHERE addr.country IS NULL ORDER BY id;
SELECT file_path, record_count FROM lens.default.migdeep.files ORDER BY file_path;
SHOW TBLPROPERTIES lens.default.migdeep;
