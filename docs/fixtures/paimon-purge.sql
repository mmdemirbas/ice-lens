-- sys.purge_files on Paimon: what it takes and what it keeps — the oracle for
-- model/PaimonPurgePlan.kt, the pe/pea shape: `brp` is example/paimon/db.db/br copied on disk
-- and purged in place, so every file `br` has that `brp` lacks is what the procedure took.
--
-- FileStoreTable.purgeFiles() (release-1.3.1, FileStoreTable.java lines 140–176), in order:
-- every branch dropped (`branchManager.dropBranch`), every tag deleted (`deleteTag`), every
-- consumer deleted; `newBatchWriteBuilder().newCommit().truncateTable()` — one OVERWRITE
-- commit, id latest + 1, commit_identifier Long.MAX_VALUE like every Spark batch commit, whose
-- delta manifest holds a DELETE entry per live file of the latest snapshot (deltaRecordCount
-- minus their rows, totalRecordCount 0) and whose base list carries the latest snapshot's own
-- base and delta manifests forward; `changelog/` deleted whole; `newExpireSnapshots()` under
-- retain max 1, retain min 1, time retained zero, max deletes Integer.MAX_VALUE — every
-- snapshot but the truncate expires whatever its age, which frees the data files the truncate's
-- delta removed; then `LocalOrphanFilesClean(table, now).clean()`, which takes what nothing
-- names any more — a dropped branch's data file and manifests, the old index manifest and
-- index file, a deleted tag's files. Nothing under `schema/` is touched, and the table reads as
-- empty and takes the next INSERT (snapshot 5 on the scratch run below). The Spark procedure
-- (PurgeFilesProcedure) prints `Success` and takes `table` alone.
--
-- Runs (2026-09-14/15) with Spark 3.5.5 and the paimon-spark-3.5 jar lakelab built from 1.3
-- (tabulario/spark-iceberg image, `--jars /opt/paimon-spark.jar`, filesystem catalog at /wh),
-- each statement its own `spark-sql -e` on copies of checked-in tables mounted under
-- /wh/db.db/<name>p. What each copy held afterwards, `find` on the host:
--
--   brp  (br: main snapshots 1..3 of k 1,2,3 / 4 / 6, tag base on 1, branch dev with its own
--         snapshot 2 of k 5, branch empty with a schema and no snapshot)
--        brp$branches before: empty, dev              after: nothing;  brp$tags after: nothing
--        SELECT * FROM db.brp                        (no rows)
--        brp$snapshots   4  OVERWRITE  9223372036854775807  delta -5  total 0
--                        base manifest-list-067eff8a-…-0, delta manifest-list-067eff8a-…-1
--        brp$manifests   the three manifests br's snapshots wrote (1 ADD each) and one new
--                        manifest-b02c3ddd-… of 3 DELETEs
--        on disk: no bucket-0, no tag/, branch/ empty, manifest/ = those four manifests and the
--        two new lists, snapshot/ = snapshot-4 with EARLIEST and LATEST rewritten, schema-0 kept.
--        br's four data files, its eight manifest lists, dev's own manifest, snapshots 1..3 and
--        tag-base: gone — the 17 files the plan on br names.
--   csp  (cs: a consumer at snapshot 2)  csp$consumers after: nothing; csp$snapshots: 4 OVERWRITE
--   clp  (cl: changelog lists, an ANALYZE with statistics)  clp$snapshots: 6 OVERWRITE, no
--        changelog list; statistics/stat-46a7cd67-… kept, named by the truncate snapshot
--   dvp  (dv: a deletion-vector index)  snapshot-7 alone; a new index-manifest-0f175e97-…
--        written by the truncate; index/ left empty — the old index manifest and index file gone
--   pclp (pcl: changelog/ holding 5 and 6 beside snapshots 7 and 8)  9 OVERWRITE delta -6 total 0;
--        changelog/ gone whole, its two changelog files with it
--   brp, first scratch run, then INSERT INTO db.brp VALUES (9, 'z'):  SELECT * → 9 z, snapshot 5 —
--        the purged table is writable; the checked-in brp is the clean run without that INSERT
--
-- Traps: `$snapshots` has no index_manifest column in 1.3 — read the snapshot JSON; and a copy
-- of a checked-in Paimon table is location-independent, so the copies were mounted under any
-- name. Hadoop's `.crc` sidecars are deleted before the copy and never committed.
--
-- To regenerate brp:
--
--   WH=$(mktemp -d); mkdir -p $WH/wh/db.db; cp -R example/paimon/db.db/br $WH/wh/db.db/brp
--   find $WH/wh -name '.*.crc' -delete
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash -v "$WH/wh:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     tabulario/spark-iceberg -c 'S="/opt/spark/bin/spark-sql --master local[1] \
--       --jars /opt/paimon-spark.jar \
--       --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--       --conf spark.sql.catalog.paimon.warehouse=/wh \
--       --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--       --conf spark.sql.defaultCatalog=paimon --conf spark.ui.enabled=false"; \
--       $S -e "CALL sys.purge_files(table => '"'"'db.brp'"'"')"'
--   cp -R $WH/wh/db.db/brp example/paimon/db.db/brp

CREATE DATABASE IF NOT EXISTS db;
-- host: cp -R br brp (and cs → csp, cl → clp, dv → dvp, pcl → pclp for the runs above)
SELECT * FROM db.`brp$branches`;
CALL sys.purge_files(table => 'db.brp');
SELECT * FROM db.brp;
SELECT snapshot_id, commit_kind, commit_identifier, delta_record_count, total_record_count, base_manifest_list, delta_manifest_list FROM db.`brp$snapshots`;
SELECT * FROM db.`brp$branches`;
SELECT * FROM db.`brp$tags`;
SELECT * FROM db.`brp$manifests`;
