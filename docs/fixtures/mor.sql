-- Regenerates example/iceberg/default/mor — the merge-on-read Iceberg fixture.
--
-- Written by Spark 3.5.5 / Iceberg 1.8.1 from the tabulario/spark-iceberg image. Delete files
-- are the largest Iceberg issue theme upstream, and nothing else in the suite has ever seen
-- one: posDeleteFileCount, deleteRecordCount, deleteSizeBytes and the DELETES manifest-content
-- branch are all decided by code no real table had exercised.
--
-- The commit order is the whole design, because a compaction applies the deletes that precede
-- it and removes their delete files. Deleting only before the compaction would leave a history
-- containing delete files and a *current* snapshot containing none, so the figure the inspector
-- shows first would still be zero:
--
--   1  INSERT           four rows
--   2  INSERT           three more, so more than one data file exists to delete from
--   3  DELETE           merge-on-read, so this writes a positional delete file
--   4  UPDATE           merge-on-read, so this writes a positional delete AND a new data file
--   5  rewrite_data_files   a compaction: operation=replace, deletes applied and dropped
--   6  DELETE           after the compaction, so the current snapshot carries a live delete file
--
-- What this fixture does NOT contain: equality deletes. Spark never writes them - they are
-- produced by Flink's upsert path and by other engines keyed on identifier fields - so
-- eqDeleteFileCount stays 0 here and remains unexercised by any real table. Do not read a
-- passing suite as covering it.
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
--     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/mor.sql:/tmp/mor.sql:ro" \
--     -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[2]' --properties-file /tmp/spark.conf -f /tmp/mor.sql"
--   rm -rf example/iceberg/default/mor
--   cp -R "$WH/wh/default/mor" example/iceberg/default/mor
--   find example/iceberg/default/mor -name '.*.crc' -delete
--
-- As with parted, the recorded paths read /wh/default/mor/... — the container's warehouse — and
-- that is deliberate: resolveForceRelative re-resolves them locally, which is the behaviour that
-- lets this tool open a table handed over as a bare directory.

CREATE TABLE lens.default.mor (
  id     INT,
  name   STRING,
  amount DECIMAL(9,2),
  d      DATE
) USING iceberg
TBLPROPERTIES (
  'format-version'    = '2',
  'write.delete.mode' = 'merge-on-read',
  'write.update.mode' = 'merge-on-read',
  'write.merge.mode'  = 'merge-on-read'
);

-- 1
INSERT INTO lens.default.mor VALUES
  (1, 'alpha',   CAST(12.34 AS DECIMAL(9,2)), DATE'2024-03-05'),
  (2, 'bravo',   CAST(0.01  AS DECIMAL(9,2)), DATE'2024-03-06'),
  (3, 'charlie', CAST(-5.50 AS DECIMAL(9,2)), DATE'2024-03-07'),
  (4, 'delta',   CAST(99.99 AS DECIMAL(9,2)), DATE'2024-03-08');

-- 2
INSERT INTO lens.default.mor VALUES
  (5, 'echo',    CAST(1.00  AS DECIMAL(9,2)), DATE'2024-04-01'),
  (6, 'foxtrot', CAST(2.50  AS DECIMAL(9,2)), DATE'2024-04-02'),
  (7, 'golf',    CAST(3.75  AS DECIMAL(9,2)), DATE'2024-04-03');

-- 3  positional delete
DELETE FROM lens.default.mor WHERE id = 2;

-- 4  positional delete plus a new data file
UPDATE lens.default.mor SET name = 'echo-updated' WHERE id = 5;

-- 5  compaction: a replace snapshot that applies and drops the delete files above
CALL lens.system.rewrite_data_files(table => 'default.mor', options => map('min-input-files', '2'));

-- 6  after the compaction, so the current snapshot carries a live positional delete file
DELETE FROM lens.default.mor WHERE id = 7;
