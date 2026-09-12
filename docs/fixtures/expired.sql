-- Regenerates example/iceberg/default/expired — an Iceberg table whose snapshots have been expired.
--
-- Written by Spark 3.5.5 / Iceberg 1.8.1 from the tabulario/spark-iceberg image. Every other
-- Iceberg fixture retains every snapshot it ever made, which no production table does: expiry
-- runs on a schedule, and afterwards the older metadata.json versions — kept on disk by
-- write.metadata.previous-versions-max, a hundred by default — still list snapshots whose manifest
-- lists have been deleted. Opening such a table is the ordinary case, and what this tool shows for
-- it had never been looked at.
--
-- Statements, in order — the numbers matter because the expected values in
-- ExpiredSnapshotsFixtureTest are read off them:
--
--   1  CREATE   format-version 2, unpartitioned                          v1.metadata.json
--   2  INSERT   id = 1                                                    snapshot 1, v2
--   3  INSERT   id = 2                                                    snapshot 2, v3
--   4  INSERT   id = 3                                                    snapshot 3, v4
--   5  DELETE   id = 1 — copy-on-write, and the file holds only that row, so the file is
--               dropped outright rather than rewritten                   snapshot 4, v5
--   6  expire_snapshots  retain_last = 1, older_than far in the future    v6
--
-- Expected afterwards: metadata/ holds v1..v6 and the version hint; v6's metadata-log names
-- v1..v5; snapshots 1, 2 and 3 are gone from v6 and their manifest lists are deleted; v2..v5
-- still list them. Snapshot 1's data file is deleted too — no retained snapshot names it — while
-- snapshots 2 and 3's files stay, because snapshot 4 still carries them. --master local[1] so a
-- one-row INSERT is one file.
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
--     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/expired.sql:/tmp/expired.sql:ro" \
--     -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[1]' --properties-file /tmp/spark.conf -f /tmp/expired.sql"
--   rm -rf example/iceberg/default/expired
--   cp -R "$WH/wh/default/expired" example/iceberg/default/expired
--   find example/iceberg/default/expired -name '.*.crc' -delete

CREATE TABLE lens.default.expired (
  id   INT,
  name STRING
) USING iceberg
TBLPROPERTIES (
  'format-version' = '2'
);

INSERT INTO lens.default.expired VALUES (1, 'alpha');

INSERT INTO lens.default.expired VALUES (2, 'bravo');

INSERT INTO lens.default.expired VALUES (3, 'charlie');

DELETE FROM lens.default.expired WHERE id = 1;

CALL lens.system.expire_snapshots(
  table => 'default.expired',
  older_than => TIMESTAMP '2099-01-01 00:00:00',
  retain_last => 1
);
