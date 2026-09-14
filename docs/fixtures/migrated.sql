-- Regenerates example/iceberg/default/migrated — an Iceberg table whose first data files were
-- written by plain Spark, not by Iceberg, and brought in with `add_files`.
--
-- Such a file carries no Iceberg field ids in its Parquet footer, so a reader cannot place its
-- columns by id; `add_files` sets `schema.name-mapping.default` on the table, a JSON list of
-- `{field-id, names}` that maps a file's column *names* to the schema's ids, and every reader
-- resolves an id-less file through it. The mapping is what keeps the file readable after the
-- table renames a column: the mapping still says `name` is field 2, and field 2 is now `label`.
--
-- Written by Spark 3.5.5 / Iceberg 1.8.1 from the tabulario/spark-iceberg image:
--
--   1  CREATE   spark_catalog.default.plain, USING parquet at /wh/plain-files — not Iceberg
--   2  INSERT   (1 alpha 10.50) (2 bravo 20.25)                     one plain Parquet file, no field ids
--   3  CREATE   lens.default.migrated (id, name, amount), Iceberg v2
--   4  CALL     add_files(source_table => `parquet`.`/wh/plain-files`)   the file listed as it is, metrics from its footer, the name mapping set
--   5  ALTER    RENAME COLUMN name TO label
--   6  INSERT   (3 charlie 30.00)                                    a second file, Iceberg-written, ids in its footer
--   7  SELECT * ORDER BY id                                          the oracle: `label` reads alpha, bravo, charlie
--
-- The plain files are copied beside the table as example/iceberg/plain-files/, the way
-- extdata's are, because `add_files` records their absolute `file:/wh/plain-files/…` paths and
-- the resolver re-roots a recorded path by its trailing segments. The rename appended `label`
-- to the mapping's names for field 2, so the mapping reads `["name", "label"]` after it.
--
-- To regenerate (see docs/fixtures/mor.sql for the spark.conf and why --entrypoint bash):
--
--   WH=$(mktemp -d); mkdir -p "$WH/wh"      # spark.conf as in mor.sql, written into $WH
--   docker run --rm --entrypoint bash \
--     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/migrated.sql:/tmp/migrated.sql:ro" \
--     -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[1]' --properties-file /tmp/spark.conf -f /tmp/migrated.sql"
--   rm -rf example/iceberg/default/migrated example/iceberg/plain-files
--   cp -R "$WH/wh/default/migrated" example/iceberg/default/migrated
--   cp -R "$WH/wh/plain-files" example/iceberg/plain-files
--   find example/iceberg/default/migrated example/iceberg/plain-files \( -name '.*.crc' -o -name '_SUCCESS' \) -delete

CREATE TABLE spark_catalog.default.plain (id INT, name STRING, amount DECIMAL(9,2))
USING parquet LOCATION '/wh/plain-files';

INSERT INTO spark_catalog.default.plain VALUES (1, 'alpha', 10.50), (2, 'bravo', 20.25);

CREATE TABLE lens.default.migrated (id INT, name STRING, amount DECIMAL(9,2))
USING iceberg TBLPROPERTIES ('format-version' = '2');

CALL lens.system.add_files(table => 'default.migrated', source_table => '`parquet`.`/wh/plain-files`');

ALTER TABLE lens.default.migrated RENAME COLUMN name TO label;

INSERT INTO lens.default.migrated VALUES (3, 'charlie', 30.00);

SELECT * FROM lens.default.migrated ORDER BY id;
SELECT file_path, record_count FROM lens.default.migrated.files ORDER BY file_path;
SHOW TBLPROPERTIES lens.default.migrated;
