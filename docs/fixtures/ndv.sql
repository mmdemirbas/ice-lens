-- Regenerates example/iceberg/default/ndv — a table statistics fixture whose four theta
-- sketches take the four shapes a compact sketch can be serialised in.
--
-- Written by Spark 3.5.5 / Iceberg 1.8.1 from the tabulario/spark-iceberg image, the way
-- docs/fixtures/stats.sql is; `stats` holds four small exact sketches and nothing else.
--
-- `compute_table_stats` builds one DataSketches theta sketch per column (`ThetaSketchAgg`, an
-- Alpha update sketch at the default 4,096 nominal entries, compacted) and records
-- `(long) sketch.getEstimate()` as the blob's `ndv` property. A compact sketch's preamble is
-- 1, 2 or 3 longs by what the sketch holds (`CompactOperations.computeCompactPreLongs`):
--
--   id       20,000 distinct   past the nominal entries, so theta < 1: an ESTIMATE, 3 preamble longs, theta recorded
--   bucket   7 distinct        exact: 2 preamble longs, the seven hashes, no theta (1.0)
--   one      1 distinct        a single-item sketch: 1 preamble long with the SINGLEITEM flag, then the one hash
--   nothing  every row null    an empty sketch: 1 preamble long with the EMPTY flag and nothing after it
--
-- The `ndv` each blob records is the oracle for the decoder; `SELECT count(DISTINCT …)` below
-- prints the truth the estimate is to be read against — the point of the fixture is that the
-- first figure is not the second and nothing in the record says so.
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
--     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/ndv.sql:/tmp/ndv.sql:ro" \
--     -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[1]' --properties-file /tmp/spark.conf -f /tmp/ndv.sql"
--   rm -rf example/iceberg/default/ndv
--   cp -R "$WH/wh/default/ndv" example/iceberg/default/ndv
--   find example/iceberg/default/ndv -name '.*.crc' -delete

CREATE TABLE lens.default.ndv (
  id      INT,
  bucket  STRING,
  one     STRING,
  nothing STRING
) USING iceberg;

INSERT INTO lens.default.ndv
SELECT CAST(id AS INT), CONCAT('b', CAST(id % 7 AS STRING)), 'only', CAST(NULL AS STRING)
FROM range(20000);

SELECT count(*), count(DISTINCT id), count(DISTINCT bucket), count(DISTINCT one), count(DISTINCT nothing) FROM lens.default.ndv;

CALL lens.system.compute_table_stats(table => 'default.ndv');
