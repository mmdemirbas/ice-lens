-- Regenerates example/iceberg/default/deep — the first fixture with a STRUCT, an ARRAY and a
-- MAP column, and the schema evolved inside the struct after the first file was written.
--
-- Why this exists: every other checked-in table is flat, so nothing had ever exercised what
-- Iceberg records for a nested column. Bounds and counts are recorded per LEAF field id
-- (`addr.city`, `addr.zip`, the list's element, the map's key and value), a rename or an add
-- inside a struct is a schema change like any other, and a row's struct cell is one value to
-- DuckDB. The file written before `addr.country` existed and before `city` became `town` is
-- what the projection and the lookup are held to.
--
--   1  CREATE   (id, name, addr STRUCT<city, zip>, tags ARRAY<STRING>, props MAP<STRING, INT>), v2, merge-on-read
--   2  INSERT   ids 1..3, one file                                       file A under schema 0
--   3  ALTER    ADD COLUMN addr.country STRING                           schema 1
--   4  ALTER    RENAME COLUMN addr.city TO town                          schema 2
--   5  INSERT   id 4, one file                                           file B under schema 2: addr has town, zip, country
--   6  DELETE   id 2                                                     a positional delete on file A
--   7  SELECT   the oracle: 1 alpha {Ankara, 6000, null} [a, b] {x -> 1} / 3 … / 4 delta {Izmir, 35000, TR} …
--
-- `--master local[1]` so the three rows share one file and the delete is positional.
--
-- To regenerate (the eqdel.sql recipe, one SQL step):
--
--   WH=$(mktemp -d)
--   cat > "$WH/spark.conf" <<'EOF'
--   spark.sql.extensions              org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions
--   spark.sql.catalog.lens            org.apache.iceberg.spark.SparkCatalog
--   spark.sql.catalog.lens.type       hadoop
--   spark.sql.catalog.lens.warehouse  /wh
--   spark.sql.defaultCatalog          lens
--   spark.sql.catalogImplementation   in-memory
--   spark.ui.enabled                  false
--   spark.eventLog.enabled            false
--   EOF
--   mkdir -p "$WH/wh"
--   docker run --rm --entrypoint bash -v "$WH/wh:/wh" -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     -v "$PWD/docs/fixtures/deep.sql:/tmp/a.sql:ro" tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[1]' --properties-file /tmp/spark.conf -f /tmp/a.sql"
--   rm -rf example/iceberg/default/deep && cp -R "$WH/wh/default/deep" example/iceberg/default/deep
--   find example/iceberg/default/deep -name '.*.crc' -delete
--
-- Any new Iceberg fixture needs docs/fixtures/iceberg-scan-plans.scala rerun for
-- IcebergDeletePairingPlanTest's oracle.

CREATE TABLE lens.default.deep (
  id    INT,
  name  STRING,
  addr  STRUCT<city: STRING, zip: INT>,
  tags  ARRAY<STRING>,
  props MAP<STRING, INT>
) USING iceberg
TBLPROPERTIES (
  'format-version'    = '2',
  'write.delete.mode' = 'merge-on-read',
  'write.update.mode' = 'merge-on-read',
  'write.merge.mode'  = 'merge-on-read'
);

INSERT INTO lens.default.deep VALUES
  (1, 'alpha',   named_struct('city', 'Ankara',   'zip', 6000),  array('a', 'b'), map('x', 1)),
  (2, 'bravo',   named_struct('city', 'Istanbul', 'zip', 34000), array('c'),      map('y', 2, 'z', 3)),
  (3, 'charlie', named_struct('city', 'Ankara',   'zip', 6800),  array(),         map());

ALTER TABLE lens.default.deep ADD COLUMN addr.country STRING;

ALTER TABLE lens.default.deep RENAME COLUMN addr.city TO town;

INSERT INTO lens.default.deep VALUES
  (4, 'delta', named_struct('town', 'Izmir', 'zip', 35000, 'country', 'TR'), array('d', 'e', 'f'), map('w', 4));

DELETE FROM lens.default.deep WHERE id = 2;

SELECT id, name, addr, tags, props FROM lens.default.deep ORDER BY id;
