-- Regenerates example/iceberg/default/eqdel — the fixture carrying BOTH delete kinds.
--
-- Step 1 of 3. Run this, then docs/fixtures/eqdel-equality-deletes.scala, then step 3 below.
--
-- Why this exists: Spark never writes equality deletes. They come from Flink's upsert path and
-- from other engines keyed on identifier fields, so `example/iceberg/default/mor` — written
-- entirely by Spark — leaves eqDeleteFileCount at zero and unexercised. Equality deletes are
-- also the case the format cannot resolve: they apply by predicate over identifier fields, so
-- nothing in the metadata links one to the data files it affects. A UI has to say that rather
-- than draw nothing, which needs a real one to say it about.
--
-- **`--master local[1]` is required, not incidental.** With local[2] Spark writes one row per
-- data file for inserts this small, and a DELETE whose predicate matches every row of a file
-- removes the file outright instead of writing a positional delete — so the table ends up with
-- no positional deletes at all and the fixture silently covers half of what it claims. That was
-- observed twice before the cause was found. One partition means the four rows below share a
-- data file, so deleting one of them must write a positional delete.
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
--   $RUN -v $D/eqdel.sql:/tmp/a.sql:ro tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[1]' --properties-file /tmp/spark.conf -f /tmp/a.sql"
--   # 2 - the equality delete, written with Iceberg's own EqualityDeleteWriter
--   $RUN -v $D/eqdel-equality-deletes.scala:/tmp/b.scala:ro tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-shell --master 'local[1]' --properties-file /tmp/spark.conf -i /tmp/b.scala < /dev/null"
--   # 3 - copy in
--   rm -rf example/iceberg/default/eqdel
--   cp -R "$WH/wh/default/eqdel" example/iceberg/default/eqdel
--   find example/iceberg/default/eqdel -name '.*.crc' -delete

CREATE TABLE lens.default.eqdel (
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
INSERT INTO lens.default.eqdel VALUES
  (1, 'alpha',   CAST(12.34 AS DECIMAL(9,2))),
  (2, 'bravo',   CAST(0.01  AS DECIMAL(9,2))),
  (3, 'charlie', CAST(-5.50 AS DECIMAL(9,2))),
  (4, 'delta',   CAST(99.99 AS DECIMAL(9,2)));

-- A second data file, so the equality delete spans more than one.
INSERT INTO lens.default.eqdel VALUES
  (5, 'echo',    CAST(1.00 AS DECIMAL(9,2))),
  (6, 'foxtrot', CAST(2.50 AS DECIMAL(9,2))),
  (7, 'golf',    CAST(3.75 AS DECIMAL(9,2)));

-- Positional delete: id 3 shares its file with 1, 2 and 4, so the file survives and the
-- removal is recorded by position.
DELETE FROM lens.default.eqdel WHERE id = 3;
