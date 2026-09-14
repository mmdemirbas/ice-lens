-- Regenerates example/iceberg/default/rgs — a data file with several Parquet row groups, so
-- that `split_offsets` is a list of more than one offset.
--
-- Written by Spark 3.5.5 / Iceberg 1.8.1 from the tabulario/spark-iceberg image, the way
-- docs/fixtures/stats.sql is. Every other fixture's files hold one row group, so their offsets
-- are all `[4]` — the first column chunk after the magic — which cannot tell a right reading of
-- the footer from a wrong one.
--
-- `write.parquet.row-group-size-bytes = 8192` makes Iceberg's ParquetWriter flush a row group
-- every few hundred rows (it checks the buffered size from the hundredth record on, then at a
-- pace it works out from the average record), so the 5,000-row insert lands as one file of
-- about ten row groups; the one-row insert is the contrast, a file of one. `name` is the first
-- column and has seven values, so its chunk opens with a dictionary page: a row group's
-- starting position is that page's offset where there is one before the data page, which is
-- `BlockMetaData.getStartingPos()` and what `ParquetUtil.getSplitOffsets` records.
--
-- The `.files` query at the end prints what was recorded — the oracle the check reads it
-- against is the file's own footer, through DuckDB's `parquet_metadata`.
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
--     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/rgs.sql:/tmp/rgs.sql:ro" \
--     -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[1]' --properties-file /tmp/spark.conf -f /tmp/rgs.sql"
--   rm -rf example/iceberg/default/rgs
--   cp -R "$WH/wh/default/rgs" example/iceberg/default/rgs
--   find example/iceberg/default/rgs -name '.*.crc' -delete

CREATE TABLE lens.default.rgs (
  name STRING,
  id   BIGINT,
  v    DOUBLE
) USING iceberg
TBLPROPERTIES ('write.parquet.row-group-size-bytes' = '8192');

INSERT INTO lens.default.rgs
SELECT CONCAT('n', CAST(id % 7 AS STRING)), id, id * 1.5 FROM range(5000);

INSERT INTO lens.default.rgs VALUES ('single', 5000, 0.5);

SELECT file_path, file_size_in_bytes, record_count, split_offsets FROM lens.default.rgs.files;
