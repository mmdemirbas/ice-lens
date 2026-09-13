-- Regenerates example/iceberg/default/retained — refs with retention settings, and an expiry
-- that honours each ref's own rather than the table's.
--
-- Written by Spark 3.5.5 / Iceberg 1.8.1 from the tabulario/spark-iceberg image.
--
-- What it is for: every other fixture's refs carry no `max-ref-age-ms`, `max-snapshot-age-ms`
-- or `min-snapshots-to-keep`, so the refs table printed N/A in those three columns on every
-- table and nothing here had seen what an expiry does with them. A branch created with
-- `WITH SNAPSHOT RETENTION 2 SNAPSHOTS` keeps its last two commits through an expiry whose
-- `older_than` is in the future, while main keeps only its tip; a tag with `RETAIN 90 DAYS`
-- keeps its snapshot; and a snapshot only a metadata version still names is drawn as expired.
--
-- The branch deliberately sets no snapshot age. The first run gave it `2 SNAPSHOTS 7 DAYS`, and
-- the expiry removed nothing: a branch's own `max-snapshot-age-ms` replaces the procedure's
-- `older_than` for the snapshots that branch reaches, none of them was seven days old, and every
-- snapshot of the table — 1 and 2 on main included — is an ancestor of the branch tip. Without
-- an age the branch falls back to `older_than`, keeps its two, and the expiry has something to do.
--
-- Statements, in order — the numbers matter because the expected values in RetainedFixtureTest
-- are read off them:
--
--   1  CREATE   v2, unpartitioned
--   2  INSERT   (1, 'alpha')                                   snapshot 1, main
--   3  INSERT   (2, 'bravo')                                   snapshot 2, main
--   4  CREATE TAG release RETAIN 90 DAYS                       at snapshot 2: max-ref-age-ms 90 days
--   5  CREATE BRANCH audit RETAIN 30 DAYS
--        WITH SNAPSHOT RETENTION 2 SNAPSHOTS                   at snapshot 2: max-ref-age-ms 30 days,
--                                                              min-snapshots-to-keep 2, no max-snapshot-age-ms
--   6  INSERT INTO branch_audit  (10, 'x')                     snapshot 3, audit, parent 2
--   7  INSERT INTO branch_audit  (11, 'y')                     snapshot 4, audit, parent 3
--   8  INSERT INTO branch_audit  (12, 'z')                     snapshot 5, audit, parent 4
--   9  INSERT   (3, 'charlie')                                 snapshot 6, main, parent 2
--  10  CALL expire_snapshots(older_than => far future)         main keeps 6 (its tip); audit keeps 5 and 4
--                                                              (2 snapshots); release keeps 2; snapshots 1 and 3 expire
--  11  SELECT   refs, snapshots, and the rows on each ref       the oracle, printed by the writer
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
--     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/retained.sql:/tmp/retained.sql:ro" \
--     -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[1]' --properties-file /tmp/spark.conf -f /tmp/retained.sql"
--   rm -rf example/iceberg/default/retained && cp -R "$WH/wh/default/retained" example/iceberg/default/retained
--   find example/iceberg/default/retained -name '.*' -delete

CREATE TABLE lens.default.retained (id INT, name STRING)
USING iceberg
TBLPROPERTIES ('format-version' = '2');

INSERT INTO lens.default.retained VALUES (1, 'alpha');

INSERT INTO lens.default.retained VALUES (2, 'bravo');

ALTER TABLE lens.default.retained CREATE TAG release RETAIN 90 DAYS;

ALTER TABLE lens.default.retained CREATE BRANCH audit RETAIN 30 DAYS WITH SNAPSHOT RETENTION 2 SNAPSHOTS;

INSERT INTO lens.default.retained.branch_audit VALUES (10, 'x');

INSERT INTO lens.default.retained.branch_audit VALUES (11, 'y');

INSERT INTO lens.default.retained.branch_audit VALUES (12, 'z');

INSERT INTO lens.default.retained VALUES (3, 'charlie');

CALL lens.system.expire_snapshots(table => 'default.retained', older_than => TIMESTAMP '2099-01-01 00:00:00');

SELECT name, type, snapshot_id, max_reference_age_in_ms, min_snapshots_to_keep, max_snapshot_age_in_ms FROM lens.default.retained.refs ORDER BY name;

SELECT snapshot_id, parent_id, operation FROM lens.default.retained.snapshots ORDER BY committed_at;

SELECT * FROM lens.default.retained ORDER BY id;

SELECT * FROM lens.default.retained.branch_audit ORDER BY id;
