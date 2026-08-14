-- Regenerates example/iceberg/default/respec — the changed-partition-spec fixture.
--
-- The partition twin of docs/fixtures/evolved.sql. `partitionResultType` decodes a partition
-- value against the spec the **manifest** carries in its own Avro file metadata, and every other
-- partitioned fixture has one spec, so the manifest's spec and the table's current spec are the
-- same object and a decoder reading either passes.
--
-- The spec changes so that reading the wrong one is not a near miss:
--
--   * `d` goes from `days` to `months`. Both store four little-endian bytes. A day ordinal read
--     as a month ordinal puts 2024-03-05 in the year 3499; a month ordinal read as a day lands
--     in 1970. Neither errors.
--   * `bucket(4, id)` is replaced by `bucket(8, id)`, so the same row hashes into a different
--     partition and the field name changes from `id_bucket` to `id_bucket_8`.
--   * `identity(name)` is dropped, so files written under spec 0 carry a partition field the
--     current spec does not have at all.
--
-- Each write lands in a directory named by the spec in force at the time, which is what the test
-- compares against: Iceberg's own rendering of each tuple, not this codebase's.
--
-- `--master local[1]` keeps one data file per partition per insert.
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
--     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/respec.sql:/tmp/respec.sql:ro" \
--     -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[1]' --properties-file /tmp/spark.conf -f /tmp/respec.sql"
--   rm -rf example/iceberg/default/respec
--   cp -R "$WH/wh/default/respec" example/iceberg/default/respec
--   find example/iceberg/default/respec -name '.*.crc' -delete

CREATE TABLE lens.default.respec (
  id   INT,
  name STRING,
  d    DATE
) USING iceberg
PARTITIONED BY (identity(name), bucket(4, id), days(d))
TBLPROPERTIES ('format-version' = '2');

-- spec 0: identity(name), bucket(4, id), days(d)
INSERT INTO lens.default.respec VALUES
  (1, 'alpha', DATE'2024-03-05'),
  (2, 'bravo', DATE'2024-03-06');

ALTER TABLE lens.default.respec DROP PARTITION FIELD name;
ALTER TABLE lens.default.respec REPLACE PARTITION FIELD id_bucket WITH bucket(8, id);
ALTER TABLE lens.default.respec REPLACE PARTITION FIELD d_day WITH months(d);

-- spec 1: bucket(8, id), months(d)
INSERT INTO lens.default.respec VALUES
  (3, 'charlie', DATE'2025-11-20'),
  (4, 'delta',   DATE'1969-06-11');
