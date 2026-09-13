-- Regenerates example/iceberg/default/sweep, swept, sweepb and sweptb — two Iceberg tables, each
-- kept twice: as written, and after expire_snapshots deleted the files the expiry freed.
--
-- Written by Spark 3.5.5 / Iceberg 1.8.1 from the tabulario/spark-iceberg image. Which
-- *snapshots* an expiry removes is one rule (ExpiryPlan, checked on `retained` and `expired`);
-- which *files* go with them is another, and it needs the manifests of the removed snapshots,
-- which the expiry deletes. So each table is copied on disk before the expiry runs on the
-- original in place: the copy (`sweep`, `sweepb`) is the table before, complete, and the original
-- (`swept`, `sweptb`) is the table after — same file names in both, which is what makes the
-- comparison exact. RemoveSnapshots picks the cleanup by the ref count: one ref runs
-- IncrementalFileCleanup, more run ReachableFileCleanup, and the two delete different things.
--
-- Statements, in order — the numbers matter because the expected values in
-- ExpiryFilePlanFixtureTest are read off them:
--
--   swept  (main only → incremental cleanup)
--   1  CREATE   format-version 2, copy-on-write deletes
--   2  INSERT (1, 'alpha')                       snapshot 1: file f1, manifest m1
--   3  INSERT (2, 'bravo')                       snapshot 2: f2, m2; list [m2, m1]
--   4  DELETE WHERE id = 1                       snapshot 3 (delete): f1 held one row, so no data file is written; m1 is rewritten
--                                                            as m1' holding f1 DELETED; list [m1', m2]
--   5  ALTER    CREATE TAG keep                  on snapshot 3
--   6  INSERT (3, 'charlie')                     snapshot 4: f3, m3; list [m3, m2] — m1' has no live file and leaves the list
--   7  CALL     set_current_snapshot(ref => 'keep')   main back to snapshot 3; snapshot 4 stays, on no ref, not an ancestor
--   8  ALTER    DROP TAG keep                    so main is the only ref again
--   9  INSERT (4, 'delta')                       snapshot 5, parent 3: f4, m4; list [m4, m2]
--   copy → sweep
--  10  CALL     expire_snapshots(older_than => 2099, retain_last => 1)
--                                                snapshots 1–4 removed; IncrementalFileCleanup, ancestors of main's tip = 5, 3, 2, 1:
--                                                  manifest lists of 1–4 deleted;
--                                                  m1 (only snapshots 1, 2 list it), m1' (only 3), m3 (only 4) deleted; m2 kept, snapshot 5 lists it;
--                                                  f1 deleted — m1' is from an ancestor and holds a DELETED entry whose snapshot (3) is gone;
--                                                  f3 deleted — m3 is from snapshot 4, expiring and not an ancestor, so its ADDED file is reverted;
--                                                  f2, f4 kept
--
--   sweptb  (a branch → reachable cleanup)
--   1  CREATE
--   2  INSERT (1, 'alpha')                       snapshot 1: f1, m1
--   3  INSERT (2, 'bravo')                       snapshot 2: f2, m2; list [m2, m1]
--   4  ALTER    CREATE BRANCH dev                on snapshot 2
--   5  INSERT (3, 'charlie')                     snapshot 3: f3, m3; list [m3, m2, m1]
--   6  DELETE WHERE id = 1                       snapshot 4 (delete): m1' with f1 DELETED; list [m1', m3, m2]
--   7  INSERT (4, 'delta')                       snapshot 5: f4, m4; list [m4, m3, m2]
--   copy → sweepb
--   8  CALL     expire_snapshots(older_than => 2099, retain_last => 1)
--                                                main keeps 5, dev keeps 2; snapshots 1, 3, 4 removed; ReachableFileCleanup:
--                                                  manifest lists of 1, 3, 4 deleted;
--                                                  candidates m1, m3, m2, m1' minus what 5 and 2 list (m4, m3, m2, m1) → only m1' deleted;
--                                                  m1' has no live entry, so no data file goes — f1 is still dev's, and every file stays
--
-- Expected afterwards: the listings at the end print the snapshots each `swept` table kept, and
-- the copies print all of them. --master local[1] makes each INSERT one file and one manifest.
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
--     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/cleanup.sql:/tmp/cleanup.sql:ro" \
--     -v "$PWD/docs/fixtures/cleanup-expire.sql:/tmp/cleanup-expire.sql:ro" \
--     -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[1]' --properties-file /tmp/spark.conf -f /tmp/cleanup.sql \
--      && cp -R /wh/default/swept /wh/default/sweep && cp -R /wh/default/sweptb /wh/default/sweepb \
--      && /opt/spark/bin/spark-sql --master 'local[1]' --properties-file /tmp/spark.conf -f /tmp/cleanup-expire.sql"
--   for t in sweep swept sweepb sweptb; do rm -rf example/iceberg/default/$t && cp -R "$WH/wh/default/$t" example/iceberg/default/$t; done

CREATE TABLE default.swept (id INT, name STRING)
USING iceberg
TBLPROPERTIES ('format-version' = '2');

INSERT INTO default.swept VALUES (1, 'alpha');
INSERT INTO default.swept VALUES (2, 'bravo');
DELETE FROM default.swept WHERE id = 1;
ALTER TABLE default.swept CREATE TAG `keep`;
INSERT INTO default.swept VALUES (3, 'charlie');
CALL system.set_current_snapshot(table => 'default.swept', ref => 'keep');
ALTER TABLE default.swept DROP TAG `keep`;
INSERT INTO default.swept VALUES (4, 'delta');

CREATE TABLE default.sweptb (id INT, name STRING)
USING iceberg
TBLPROPERTIES ('format-version' = '2');

INSERT INTO default.sweptb VALUES (1, 'alpha');
INSERT INTO default.sweptb VALUES (2, 'bravo');
ALTER TABLE default.sweptb CREATE BRANCH `dev`;
INSERT INTO default.sweptb VALUES (3, 'charlie');
DELETE FROM default.sweptb WHERE id = 1;
INSERT INTO default.sweptb VALUES (4, 'delta');

SELECT 'swept-before' AS t, snapshot_id, parent_id, operation FROM default.swept.snapshots ORDER BY committed_at;
SELECT 'swept-before' AS t, reference_snapshot_id, added_snapshot_id, added_data_files_count, existing_data_files_count, deleted_data_files_count, path FROM default.swept.all_manifests ORDER BY reference_snapshot_id, path;
SELECT 'sweptb-before' AS t, snapshot_id, parent_id, operation FROM default.sweptb.snapshots ORDER BY committed_at;
SELECT 'sweptb-before' AS t, reference_snapshot_id, added_snapshot_id, added_data_files_count, existing_data_files_count, deleted_data_files_count, path FROM default.sweptb.all_manifests ORDER BY reference_snapshot_id, path;
