-- Regenerates example/iceberg/default/v3 — the format-version 3 fixture.
--
-- Written by Spark 3.5.5 / Iceberg 1.8.1 from the tabulario/spark-iceberg image. Iceberg 1.8.1
-- writes v3 tables, and under merge-on-read it writes a **deletion vector** — a Puffin blob
-- (`-deletes.puffin`) referenced from the manifest entry by `referenced_data_file`,
-- `content_offset` and `content_size_in_bytes` — rather than the v2 positional delete parquet
-- file that example/iceberg/default/mor carries. That was verified by running it, not assumed:
-- the same statements against a v2 table produce `-deletes.parquet`.
--
-- None of those three fields is modelled yet (see TODO.md, "Iceberg v3 is unmodelled"). This
-- fixture exists so that work has something real to be written against, and so the reader is
-- pinned against silently breaking on a v3 table in the meantime — unknown Avro fields are
-- dropped and unknown JSON keys ignored, which is a design choice that needs a test rather than
-- a hope.
--
--   1  INSERT   three rows
--   2  INSERT   two more, so a second data file exists
--   3  DELETE   merge-on-read, so this writes a deletion vector
--   4  UPDATE   merge-on-read, so this writes another vector plus a new data file
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required, and why
-- the shell must be `bash -c` rather than `bash -lc`):
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
--   docker run --rm --entrypoint bash \
--     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/v3.sql:/tmp/v3.sql:ro" \
--     -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[2]' --properties-file /tmp/spark.conf -f /tmp/v3.sql"
--   rm -rf example/iceberg/default/v3
--   cp -R "$WH/wh/default/v3" example/iceberg/default/v3
--   find example/iceberg/default/v3 -name '.*.crc' -delete

CREATE TABLE lens.default.v3 (
  id     INT,
  name   STRING,
  amount DECIMAL(9,2)
) USING iceberg
TBLPROPERTIES (
  'format-version'    = '3',
  'write.delete.mode' = 'merge-on-read',
  'write.update.mode' = 'merge-on-read',
  'write.merge.mode'  = 'merge-on-read'
);

-- 1
INSERT INTO lens.default.v3 VALUES
  (1, 'alpha',   CAST(12.34 AS DECIMAL(9,2))),
  (2, 'bravo',   CAST(0.01  AS DECIMAL(9,2))),
  (3, 'charlie', CAST(-5.50 AS DECIMAL(9,2)));

-- 2
INSERT INTO lens.default.v3 VALUES
  (4, 'delta', CAST(99.99 AS DECIMAL(9,2))),
  (5, 'echo',  CAST(1.00  AS DECIMAL(9,2)));

-- 3  deletion vector
DELETE FROM lens.default.v3 WHERE id = 2;

-- 4  another vector plus a new data file
UPDATE lens.default.v3 SET name = 'delta-updated' WHERE id = 4;
