-- Regenerates example/iceberg/default/parted — the partitioned Iceberg fixture.
--
-- Written by Spark 3.5.5 / Iceberg 1.8.1 from the tabulario/spark-iceberg image. The table is
-- shaped to exercise the partition decodings that fail silently rather than loudly:
--
--   * identity on a string and on decimal(9,2) — the decimal arrives as a big-endian Avro
--     `fixed`, unlike every little-endian numeric value elsewhere in a manifest;
--   * bucket[4] and truncate[3], whose result types differ from each other (int vs source);
--   * all four time transforms on separate columns, because Iceberg rejects two time
--     transforms over one source column. `day` yields a date; `year`, `month` and `hour` yield
--     int ordinals in the same four bytes.
--
-- Row 3 is pre-epoch (1969) so every ordinal goes negative, and row 4 carries a negative
-- decimal, so truncating division and an unsigned read both produce visibly wrong answers.
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
--   docker run --rm --entrypoint bash \
--     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/parted.sql:/tmp/parted.sql:ro" \
--     -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[2]' --properties-file /tmp/spark.conf -f /tmp/parted.sql"
--   rm -rf example/iceberg/default/parted
--   cp -R "$WH/wh/default/parted" example/iceberg/default/parted
--   find example/iceberg/default/parted -name '.*.crc' -delete
--
-- Two traps in that invocation, both of which fail in ways that do not name the cause:
--
--   * `--entrypoint bash` is required. The image's entrypoint.sh ends in `eval "$1"`, so only
--     the first argument survives — every flag is dropped and the run falls back to the
--     image's spark-defaults.conf, which registers a `demo` REST catalog at http://rest:8181
--     and dies with UnknownHostException before reaching any SQL.
--   * `bash -c`, not `bash -lc`. A login shell resets PATH and loses /opt/spark/bin, so
--     `spark-sql` is not found; the full path is used above regardless.
--
-- The recorded file paths will read /wh/default/parted/... — the container's warehouse. That
-- is deliberate: resolveForceRelative re-resolves them against the local metadata directory,
-- which is the behaviour that lets this tool open a table handed over as a bare directory, and
-- keeping a fixture whose paths do not exist locally is what keeps that behaviour under test.

CREATE TABLE lens.default.parted (
  id        INT,
  name      STRING,
  amount    DECIMAL(9,2),
  d         DATE,
  ts_h      TIMESTAMP,
  ts_m      TIMESTAMP,
  ts_y      TIMESTAMP
) USING iceberg
PARTITIONED BY (
  identity(name),
  identity(amount),
  bucket(4, id),
  truncate(3, name),
  days(d),
  hours(ts_h),
  months(ts_m),
  years(ts_y)
);

INSERT INTO lens.default.parted VALUES
  (1, 'alpha',  CAST(12.34   AS DECIMAL(9,2)), DATE'2024-03-05', TIMESTAMP'2024-03-05 07:15:00', TIMESTAMP'2024-03-05 07:15:00', TIMESTAMP'2024-03-05 07:15:00'),
  (2, 'bravo',  CAST(0.01    AS DECIMAL(9,2)), DATE'2025-11-20', TIMESTAMP'2025-11-20 23:45:00', TIMESTAMP'2025-11-20 23:45:00', TIMESTAMP'2025-11-20 23:45:00'),
  (3, 'charlie',CAST(98765.43 AS DECIMAL(9,2)), DATE'1969-06-11', TIMESTAMP'1969-06-11 01:05:00', TIMESTAMP'1969-06-11 01:05:00', TIMESTAMP'1969-06-11 01:05:00');

-- A second commit, so the fixture has two snapshots and two manifests.
INSERT INTO lens.default.parted VALUES
  (4, 'alpha',  CAST(-5.50   AS DECIMAL(9,2)), DATE'2024-03-05', TIMESTAMP'2024-03-05 07:59:00', TIMESTAMP'2024-03-31 07:15:00', TIMESTAMP'2024-12-31 07:15:00');
