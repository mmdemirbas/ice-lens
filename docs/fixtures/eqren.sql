-- Regenerates example/iceberg/default/eqren — `eqdel`'s shape with the column the equality
-- delete names RENAMED after the delete was written, and a row inserted after the rename.
--
-- Step 1 of 3. Run this, then docs/fixtures/eqren-equality-deletes.scala, then
-- docs/fixtures/eqren-rename.sql, then copy in (below).
--
-- Why this exists: an equality delete file is a Parquet file whose columns are named as the
-- schema named them when it was written, and a scan matches it to a row BY FIELD ID — so a
-- `RENAME COLUMN name TO label` leaves a delete file holding `name` that still removes rows by
-- their `label`. A reader that opens the delete file by the schema's current name asks for a
-- column the file does not have, and the row it should decide as deleted comes back as "could
-- not be read". The rename is what `evolved` does to a data file; this does it to a delete file.
-- The row inserted AFTER the rename is the contrast: its `label` is the deleted value and the
-- delete does not reach it — an equality delete applies only to files at a strictly lower
-- sequence number.
--
-- `--master local[1]` for the reason eqdel.sql gives: four rows in one file, so the positional
-- delete on id 3 is written rather than the file dropped.
--
-- To regenerate:
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
--   D=$PWD/docs/fixtures
--   RUN="docker run --rm --entrypoint bash -v $WH/wh:/wh -v $WH/spark.conf:/tmp/spark.conf:ro"
--
--   # 1 - the table, its rows, and the positional delete
--   $RUN -v $D/eqren.sql:/tmp/a.sql:ro tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[1]' --properties-file /tmp/spark.conf -f /tmp/a.sql"
--   # 2 - the equality delete on `name`, written with Iceberg's own EqualityDeleteWriter
--   $RUN -v $D/eqren-equality-deletes.scala:/tmp/b.scala:ro tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-shell --master 'local[1]' --properties-file /tmp/spark.conf -i /tmp/b.scala < /dev/null"
--   # 3 - the rename, the row after it, and the oracle
--   $RUN -v $D/eqren-rename.sql:/tmp/c.sql:ro tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[1]' --properties-file /tmp/spark.conf -f /tmp/c.sql"
--   # 4 - copy in
--   rm -rf example/iceberg/default/eqren
--   cp -R "$WH/wh/default/eqren" example/iceberg/default/eqren
--   find example/iceberg/default/eqren -name '.*.crc' -delete
--
-- Any new Iceberg fixture needs docs/fixtures/iceberg-scan-plans.scala rerun for
-- IcebergDeletePairingPlanTest's oracle.

CREATE TABLE lens.default.eqren (
  id     INT,
  name   STRING,
  amount DECIMAL(9,2)
) USING iceberg
TBLPROPERTIES (
  'format-version'    = '2',
  'write.delete.mode' = 'merge-on-read',
  'write.update.mode' = 'merge-on-read',
  'write.merge.mode'  = 'merge-on-read'
);

-- One data file holding four rows, so a single-row delete cannot drop the whole file.
INSERT INTO lens.default.eqren VALUES
  (1, 'alpha',   CAST(12.34 AS DECIMAL(9,2))),
  (2, 'bravo',   CAST(0.01  AS DECIMAL(9,2))),
  (3, 'charlie', CAST(-5.50 AS DECIMAL(9,2))),
  (4, 'delta',   CAST(99.99 AS DECIMAL(9,2)));

-- A second data file, so the equality delete spans more than one.
INSERT INTO lens.default.eqren VALUES
  (5, 'echo',    CAST(1.00 AS DECIMAL(9,2))),
  (6, 'foxtrot', CAST(2.50 AS DECIMAL(9,2))),
  (7, 'golf',    CAST(3.75 AS DECIMAL(9,2)));

-- Positional delete: id 3 shares its file with 1, 2 and 4, so the file survives and the
-- removal is recorded by position.
DELETE FROM lens.default.eqren WHERE id = 3;
