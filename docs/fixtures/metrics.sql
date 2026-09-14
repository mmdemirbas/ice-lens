-- Regenerates example/iceberg/default/metrics and example/iceberg/default/metricsw — the two
-- tables whose column statistics are shaped by `write.metadata.metrics.*`, which every other
-- fixture leaves at the default (`truncate(16)` on every column).
--
-- Written by Spark 3.5.5 / Iceberg 1.8.1 from the tabulario/spark-iceberg image, the way
-- docs/fixtures/rgs.sql is. `MetricsConfig.from` (1.8.1) decides a column's mode in this order:
-- the configured default (`write.metadata.metrics.default`), else `truncate(16)` for a table of
-- at most `write.metadata.metrics.max-inferred-column-defaults` (100) top-level columns and,
-- past that many, `truncate(16)` for the first that many columns (nested leaves included) and
-- `none` for the rest; a sort column is promoted to `truncate(16)` where the default is `none`
-- or `counts`; then `write.metadata.metrics.column.<dotted name>` overrides. ParquetUtil then
-- records nothing at all for `none`, sizes and counts (NaNs included) but no bounds for
-- `counts`, and bounds truncated to N code points for a string or binary under `truncate(N)`
-- — the upper one with its last code point incremented — and full otherwise.
--
-- `metrics`: default `counts`; `name` truncate(4); `note` full; `tag` none, then `counts` from
-- the second insert on (a file records the config in force when it was written, so file 1
-- has nothing for `tag` and file 2 a count); `addr.zip` full, the dotted alias; `score` the
-- sort column, promoted to truncate(16), which for a DOUBLE is the full bound.
--
-- `metricsw`: no default and `max-inferred-column-defaults = 3`, so `c1`, `c2` and both leaves
-- of `c3` record `truncate(16)` and `c4`, `c5`, `c6` record nothing — the shape a wide table
-- takes past its hundredth column, on six.
--
-- The `.files` queries at the end print what was recorded, the oracle the check reads against.
-- What they printed, per column (`readable_metrics`):
--   metrics 00000-2-b56e71…:
--     addr.city  values 3, nulls 0, no bounds
--     addr.zip   values 3, nulls 0, bounds 6000 .. 35000
--     id         values 3, nulls 0, no bounds
--     name       values 3, nulls 0, bounds 'alph' .. 'braw'
--     note       values 3, nulls 1, bounds 'a note well past sixteen characters long' .. 'short'
--     score      values 3, nulls 0, NaNs 0, bounds 0.5 .. 2.5
--     tag        nothing recorded
--   metrics 00000-5-b9e081…:
--     addr.city  values 1, nulls 0, no bounds
--     addr.zip   values 1, nulls 0, bounds 16000 .. 16000
--     id         values 1, nulls 0, no bounds
--     name       values 1, nulls 0, bounds 'char' .. 'chas'
--     note       values 1, nulls 0, bounds 'note four' .. 'note four'
--     score      values 1, nulls 0, NaNs 0, bounds 3.5 .. 3.5
--     tag        values 1, nulls 0, no bounds
--   metricsw 00000-6-c28b1b…:
--     c1         values 2, nulls 0, bounds 1 .. 2
--     c2         values 2, nulls 0, bounds 'one' .. 'two'
--     c3.a       values 2, nulls 0, bounds 'p' .. 'q'
--     c3.b       values 2, nulls 0, bounds 1 .. 2
--     c4         nothing recorded
--     c5         nothing recorded
--     c6         nothing recorded
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
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
--     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/metrics.sql:/tmp/metrics.sql:ro" \
--     -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[1]' --properties-file /tmp/spark.conf -f /tmp/metrics.sql"
--   rm -rf example/iceberg/default/metrics example/iceberg/default/metricsw
--   cp -R "$WH/wh/default/metrics" example/iceberg/default/metrics
--   cp -R "$WH/wh/default/metricsw" example/iceberg/default/metricsw

CREATE TABLE lens.default.metrics (
  id    INT,
  name  STRING,
  note  STRING,
  tag   STRING,
  score DOUBLE,
  addr  STRUCT<city: STRING, zip: INT>
) USING iceberg
TBLPROPERTIES (
  'format-version'                        = '2',
  'write.metadata.metrics.default'        = 'counts',
  'write.metadata.metrics.column.name'    = 'truncate(4)',
  'write.metadata.metrics.column.note'    = 'full',
  'write.metadata.metrics.column.tag'     = 'none',
  'write.metadata.metrics.column.addr.zip' = 'full'
);

ALTER TABLE lens.default.metrics WRITE ORDERED BY score;

INSERT INTO lens.default.metrics VALUES
  (1, 'alphabet-soup',     'a note well past sixteen characters long', 'x',  1.5,  named_struct('city', 'Ankara', 'zip', 6000)),
  (2, 'bravo',             'short',                                   'y',  0.5,  named_struct('city', 'Izmir',  'zip', 35000)),
  (3, 'alphabet-soup-two', NULL,                                      NULL, 2.5,  named_struct('city', 'Ankara', 'zip', 6800));

ALTER TABLE lens.default.metrics SET TBLPROPERTIES ('write.metadata.metrics.column.tag' = 'counts');

INSERT INTO lens.default.metrics VALUES
  (4, 'charlie', 'note four', 'z', 3.5, named_struct('city', 'Bursa', 'zip', 16000));

CREATE TABLE lens.default.metricsw (
  c1 INT,
  c2 STRING,
  c3 STRUCT<a: STRING, b: INT>,
  c4 STRING,
  c5 INT,
  c6 STRING
) USING iceberg
TBLPROPERTIES (
  'format-version'                                     = '2',
  'write.metadata.metrics.max-inferred-column-defaults' = '3'
);

INSERT INTO lens.default.metricsw VALUES
  (1, 'one', named_struct('a', 'p', 'b', 1), 'four',   5, 'six'),
  (2, 'two', named_struct('a', 'q', 'b', 2), 'four-b', 6, 'six-b');

SELECT file_path, value_counts, null_value_counts, nan_value_counts, lower_bounds, upper_bounds FROM lens.default.metrics.files ORDER BY file_path;
SELECT file_path, readable_metrics FROM lens.default.metrics.files ORDER BY file_path;
SELECT file_path, value_counts, null_value_counts, lower_bounds, upper_bounds FROM lens.default.metricsw.files ORDER BY file_path;
SELECT file_path, readable_metrics FROM lens.default.metricsw.files ORDER BY file_path;
