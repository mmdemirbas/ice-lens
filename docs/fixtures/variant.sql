-- Regenerates example/iceberg/default/variant — a format-version 3 table with a `variant` column,
-- written by Iceberg 1.10.0's Spark 4.0 module (Spark 3.5 has no VARIANT type, so the image is
-- apache/spark:4.0.2 with the released 4.0 runtime jar). The rows are chosen to put every shape
-- the Variant binary encoding has into one table: an object with a key dictionary, a nested
-- object and array, a short string (under 64 bytes, its length in the header byte) and a long
-- one, integers at every width parse_json narrows to (int8, int16, int32, int64), a decimal, a
-- double, booleans, a JSON null, a SQL NULL, an empty object and an empty array — and, through
-- CAST(… AS VARIANT) from typed values, the typed primitives parse_json never produces: a date,
-- a timestamp with and without zone, a float, a decimal at a stated scale and a binary.
--
-- Statements, in order:
--
--   1  CREATE   (id INT, v VARIANT), format-version 3
--   2  INSERT   ids 1..5 — an object, a nested object, a long string, an array of numbers, a NULL
--   3  INSERT   ids 6..18 — `{}`, `[]`, then one typed value cast to VARIANT per row: a date, a
--               timestamp with and without zone, a decimal, a float, a binary, an int64, an
--               int8, an int16, an array, and a struct through to_variant_object — Spark 4.0.2
--               refuses CAST(<struct> AS VARIANT) with `DATATYPE_MISMATCH.CAST_WITHOUT_SUGGESTION`,
--               a struct of typed values included, which is why the typed values go one a row
--   4  SELECT   id, to_json(v)                            the oracle for the decoder, verbatim
--   5  SELECT   id, variant_get(…)                        two paths, as a reader queries a variant
--   6  the files' metrics, and the snapshots
--
-- To regenerate:
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/.cache/iceberg-spark-runtime-4.0_2.13-1.10.0.jar
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
--   mkdir -p "$WH/wh" && chmod -R a+rwx "$WH"
--   docker run --rm --entrypoint bash \
--     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/variant.sql:/tmp/variant.sql:ro" \
--     -v "$WH/spark.conf:/tmp/spark.conf:ro" -v "$JAR:/opt/iceberg-1.10.jar:ro" \
--     apache/spark:4.0.2-scala2.13-java17-ubuntu -c \
--     "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/iceberg-1.10.jar --properties-file /tmp/spark.conf -f /tmp/variant.sql"
--   rm -rf example/iceberg/default/variant && cp -R "$WH/wh/default/variant" example/iceberg/default/variant
--   find example/iceberg/default/variant -name '.*' -delete

CREATE TABLE lens.default.variant (id INT, v VARIANT)
USING iceberg
TBLPROPERTIES ('format-version' = '3');

INSERT INTO lens.default.variant VALUES
  (1, parse_json('{"name":"alpha","n":1,"ok":true,"tags":["x","y"]}')),
  (2, parse_json('{"name":"bravo","n":-7,"ratio":1.5,"nested":{"k":null,"d":[1,{"e":"f"}]}}')),
  (3, parse_json('"a string of more than sixty-three characters, long enough to leave the short-string encoding behind"')),
  (4, parse_json('[1, 2.5, 30000, 3000000000, 1e300, false, null]')),
  (5, NULL);

INSERT INTO lens.default.variant VALUES
  (6, parse_json('{}')),
  (7, parse_json('[]')),
  (8, CAST(DATE '2024-03-05' AS VARIANT)),
  (9, CAST(TIMESTAMP '2024-03-05 10:00:00' AS VARIANT)),
  (10, CAST(TIMESTAMP_NTZ '2024-03-05 10:00:00' AS VARIANT)),
  (11, CAST(CAST(12.34 AS DECIMAL(10, 2)) AS VARIANT)),
  (12, CAST(CAST(1.25 AS FLOAT) AS VARIANT)),
  (13, CAST(X'CAFE' AS VARIANT)),
  (14, CAST(CAST(12345678901234 AS BIGINT) AS VARIANT)),
  (15, CAST(CAST(-1 AS TINYINT) AS VARIANT)),
  (16, CAST(CAST(300 AS SMALLINT) AS VARIANT)),
  (17, CAST(array(1, 2) AS VARIANT)),
  (18, to_variant_object(named_struct('a', 1, 'b', 'two')));

SELECT id, to_json(v) FROM lens.default.variant ORDER BY id;

SELECT id, variant_get(v, '$.name', 'string') AS name, variant_get(v, '$.nested.d[1].e', 'string') AS e FROM lens.default.variant ORDER BY id;

SELECT file_path, record_count, column_sizes, value_counts, null_value_counts, nan_value_counts, lower_bounds, upper_bounds
FROM lens.default.variant.files ORDER BY file_path;

SELECT snapshot_id, operation, summary FROM lens.default.variant.snapshots ORDER BY committed_at;
