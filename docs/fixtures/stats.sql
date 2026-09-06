-- Regenerates example/iceberg/default/stats — the table statistics fixture.
--
-- Written by Spark 3.5.5 / Iceberg 1.8.1 from the tabulario/spark-iceberg image.
--
-- What it is for: every other fixture in this repository carries `"statistics": []` and
-- `"partition-statistics": []`, so the two lists were only ever exercised empty. A statistics
-- file is a **Puffin container** — the same format a v3 deletion vector lives in — holding one
-- blob per column, and `metadata.json` records where it is, which snapshot it describes, and
-- what each blob covers. That makes it the second thing `service/PuffinReader.kt` was built to
-- read, and the NDV a blob's own properties carry is the number a cost-based optimiser plans
-- against.
--
-- `CALL <catalog>.system.compute_table_stats` is what writes it. It is an Iceberg stored
-- procedure rather than Spark's own `ANALYZE TABLE`: Spark's writes into its session catalog,
-- which a Hadoop-catalog Iceberg table is not in, so it reports success and leaves the table's
-- metadata untouched.
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
--     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/stats.sql:/tmp/stats.sql:ro" \
--     -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[2]' --properties-file /tmp/spark.conf -f /tmp/stats.sql"
--   rm -rf example/iceberg/default/stats
--   cp -R "$WH/wh/default/stats" example/iceberg/default/stats
--   find example/iceberg/default/stats -name '.*.crc' -delete

CREATE TABLE lens.default.stats (
  id     INT,
  name   STRING,
  region STRING,
  amount DECIMAL(9,2)
) USING iceberg
PARTITIONED BY (region);

-- Two commits, so the statistics file names one snapshot out of several rather than the only one.
INSERT INTO lens.default.stats VALUES
  (1, 'alpha',   'eu', CAST(12.34 AS DECIMAL(9,2))),
  (2, 'bravo',   'eu', CAST(0.01  AS DECIMAL(9,2))),
  (3, 'charlie', 'us', CAST(99.99 AS DECIMAL(9,2)));

INSERT INTO lens.default.stats VALUES
  (4, 'delta',   'us', CAST(45.00 AS DECIMAL(9,2))),
  (5, 'echo',    'ap', CAST(7.50   AS DECIMAL(9,2))),
  (6, 'alpha',   'ap', CAST(1.00   AS DECIMAL(9,2)));

-- The distinct counts this is meant to record, predicted from the rows above before it was run:
-- id 6, name 5 (alpha twice), region 3, amount 6. Spark's own theta sketches agree with all four,
-- which is what makes this fixture an oracle rather than a recording of whatever came out.
CALL lens.system.compute_table_stats(table => 'default.stats');

-- There is deliberately no `compute_partition_stats` here. It does not exist in Iceberg 1.8.1 —
-- the image's version — and adding the call fails the whole script with a parse error at `CALL`.
-- So `partition-statistics` stays `[]` in this fixture, and the untyped-partition-statistics gap
-- in TODO.md still has no oracle. It needs a newer Iceberg, not a different statement.
