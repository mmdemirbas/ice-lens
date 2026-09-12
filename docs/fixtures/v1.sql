-- Regenerates example/iceberg/default/v1 — a format-version 1 table, then upgraded to 2.
--
-- Written by Spark 3.5.5 / Iceberg 1.8.1 from the tabulario/spark-iceberg image. Every other
-- Iceberg fixture is v2 or v3, and v1 is what a table migrated from Hive years ago still is:
-- no sequence numbers anywhere, no delete files, `manifest_file.content` absent, and the
-- manifest-list fields v2 added — `sequence_number`, `min_sequence_number`, `content` — either
-- missing from the Avro schema or written as their defaults. The reader's null-handling for all
-- of those was written from the spec and had never met bytes an engine wrote.
--
-- Then the upgrade, which is the shape worth having: `format-version` set to 2 rewrites nothing.
-- The manifests and manifest lists written under v1 are carried into v2 metadata as they are, so
-- one table holds both shapes, and a delete after the upgrade adds the first delete file the
-- table has ever had.
--
-- Statements, in order — the numbers matter because the expected values in
-- FormatV1FixtureTest are read off them:
--
--   1  CREATE   format-version 1, unpartitioned                       v1.metadata.json
--   2  INSERT   ids 1..3 — one file under --master local[1]           snapshot 1
--   3  INSERT   ids 4..6 — a second file                              snapshot 2
--   4  DELETE   id = 2 — v1 has no delete files, so this is copy-on-write whatever the mode
--               says: file 1 is rewritten without the row              snapshot 3 (overwrite)
--   5  ALTER    format-version = 2, and merge-on-read for the delete below
--   6  INSERT   ids 7..9                                              snapshot 4
--   7  DELETE   id = 5 — merge-on-read now, a positional delete file   snapshot 5
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
--     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/v1.sql:/tmp/v1.sql:ro" \
--     -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[1]' --properties-file /tmp/spark.conf -f /tmp/v1.sql"
--   rm -rf example/iceberg/default/v1
--   cp -R "$WH/wh/default/v1" example/iceberg/default/v1
--   find example/iceberg/default/v1 -name '.*.crc' -delete

CREATE TABLE lens.default.v1 (
  id   INT,
  name STRING
) USING iceberg
TBLPROPERTIES (
  'format-version' = '1'
);

-- 1
INSERT INTO lens.default.v1 VALUES (1, 'alpha'), (2, 'bravo'), (3, 'charlie');

-- 2
INSERT INTO lens.default.v1 VALUES (4, 'delta'), (5, 'echo'), (6, 'foxtrot');

-- 3  copy-on-write: v1 cannot write a delete file
DELETE FROM lens.default.v1 WHERE id = 2;

-- 4  the upgrade rewrites nothing
ALTER TABLE lens.default.v1 SET TBLPROPERTIES (
  'format-version'    = '2',
  'write.delete.mode' = 'merge-on-read'
);

-- 5
INSERT INTO lens.default.v1 VALUES (7, 'golf'), (8, 'hotel'), (9, 'india');

-- 6  the table's first delete file
DELETE FROM lens.default.v1 WHERE id = 5;
