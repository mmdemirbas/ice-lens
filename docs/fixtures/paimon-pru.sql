-- sys.remove_unexisting_files on Paimon: what it commits for a data file the latest snapshot
-- names that is not there, and what it never lists — the oracle for
-- model/PaimonUnexistingFilesPlan.kt, the pe/pea shape: `pru` is the table with two data files
-- deleted from disk, `prua` the same table after the procedure ran on it.
--
-- ListUnexistingFiles (release-1.3.1): per partition of the latest snapshot, the data files of
-- every split `table.newScan().withPartitionFilter(partition).plan()` returns are stat-ed and
-- the absent ones collected; RemoveUnexistingFilesProcedure (Spark) commits them as one
-- CommitMessage per bucket whose DataIncrement holds them as deletedFiles — an APPEND commit
-- with a DELETE entry per file in its delta manifest, deltaRecordCount minus their rows —
-- through `table.newCommit(UUID)` with commitIdentifier Long.MAX_VALUE, which is the identifier
-- every Spark batch commit carries; `dry_run => true` lists and commits nothing; nothing
-- listed commits nothing. `newScan()` is the batch scan, so on a primary-key table under
-- deletion vectors or first-row (`batchScanSkipLevel0`) a level-0 file is neither read nor
-- listed. Only data files: a missing manifest, manifest list, index or changelog file is not
-- reached, nor a data file only an older snapshot, a tag or a branch names — a time travel to
-- an older snapshot still fails after the fix.
--
-- Run (2026-09-15) with Spark 3.5.5 and the paimon-spark-3.5 jar lakelab built from 1.3
-- (tabulario/spark-iceberg image, `--jars /opt/paimon-spark.jar`, filesystem catalog at /wh):
-- the statements below, then on the host `rm` of the newest data file in each of
-- `pru/p=x/bucket-0` and `pru/p=y/bucket-0` (the files snapshots 2 and 3 wrote, one row each,
-- `data-c9f662ef-…` and `data-e4c400b8-…`), `cp -R pru prua`, and each of these as its own
-- `spark-sql -e`:
--
--   SELECT * FROM db.prua ORDER BY k                              FileNotFoundException: File '/wh/db.db/prua/p=x/bucket-0/data-c9f662ef-59a9-4d80-b020-624f03395256-0.parquet' not found, Possible causes: 1.snapshot expires too fast, you can configure 'snapshot.time-retained' option with a larger value. 2.consumption is too slow, ...
--   CALL sys.remove_unexisting_files(table => 'db.prua', dry_run => true)
--                                                                 /wh/db.db/prua/p=x/bucket-0/data-c9f662ef-59a9-4d80-b020-624f03395256-0.parquet
--                                                                 /wh/db.db/prua/p=y/bucket-0/data-e4c400b8-7c3d-4716-b390-33fdaa40a03e-0.parquet
--   prua$snapshots afterwards                                     1..3 unchanged (APPEND, commit_identifier 9223372036854775807, delta 2 / 1 / 1, total 2 / 3 / 4)
--   CALL sys.remove_unexisting_files(table => 'db.prua')          the same two paths
--   SELECT * FROM db.prua ORDER BY k                              1 x a / 2 y b
--   prua$snapshots afterwards                                     4  APPEND  9223372036854775807  commit_user ce9c3894-…  delta_record_count -2  total_record_count 2
--   CALL sys.remove_unexisting_files(table => 'db.prua')          (nothing listed; snapshot/ still ends at 4)
--   SELECT * FROM db.prua VERSION AS OF 3 ORDER BY k              FileNotFoundException on the same file — the older snapshots still name it
--
-- The level rule, on two copies of example/paimon/db.db/fr (first-row, live files
-- data-3dd96d5b-… at level 4 and data-d0ebee7f-… at level 0):
--   frl0, its level-0 file deleted:  dry_run lists nothing; SELECT * → 3 first (the batch read never opened it)
--   frl5, its level-4 file deleted:  dry_run lists /wh/db.db/frl5/bucket-0/data-3dd96d5b-6c54-4e2d-a679-98a1eb8ed4d2-0.parquet; SELECT * → FileNotFoundException
--
-- Traps: `db.pru$files` is a parse error in spark-sql — the system table needs backticks,
-- db.`pru$files` — and it lists the batch scan's files, so a level-0 file of a first-row
-- table is absent from it too.

CREATE DATABASE IF NOT EXISTS db;
CREATE TABLE db.pru (k INT, p STRING, v STRING) PARTITIONED BY (p) TBLPROPERTIES ('primary-key' = 'k,p', 'bucket' = '1');
INSERT INTO db.pru VALUES (1, 'x', 'a'), (2, 'y', 'b');
INSERT INTO db.pru VALUES (3, 'x', 'c');
INSERT INTO db.pru VALUES (4, 'y', 'd');
-- host: rm the newest data file under p=x/bucket-0 and p=y/bucket-0; cp -R pru prua
SELECT * FROM db.prua ORDER BY k;
CALL sys.remove_unexisting_files(table => 'db.prua', dry_run => true);
CALL sys.remove_unexisting_files(table => 'db.prua');
SELECT * FROM db.prua ORDER BY k;
SELECT snapshot_id, commit_kind, commit_identifier, commit_user, delta_record_count, total_record_count FROM db.`prua$snapshots` ORDER BY snapshot_id;
CALL sys.remove_unexisting_files(table => 'db.prua');
SELECT * FROM db.prua VERSION AS OF 3 ORDER BY k;
