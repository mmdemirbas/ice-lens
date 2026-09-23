-- dlog — the log's three other shapes, one table each.
--   dmp   a multi-part checkpoint: checkpoint.partSize 2 splits the checkpoint at 4 into parts
--         of two actions each, <v>.checkpoint.<part>.<parts>.parquet
--   dv2   a V2 checkpoint (delta.checkpointPolicy v2): <v>.checkpoint.<uuid>.json naming its
--         file actions in sidecars under _delta_log/_sidecars/
--   dlc   a log cleaned past a checkpoint: checkpoints at 2 and 4, the files up to 4 aged to
--         2020 on disk, and the checkpoint at 6 runs the expired-log cleanup, which deletes every
--         commit and checkpoint below the newest checkpoint's version whose file is past
--         delta.logRetentionDuration — 0 to 4 and both checkpoints, 5 kept for its age
--   dlcb  dlc copied (times kept) after the aging and before the commits that trigger the cleanup
SET spark.databricks.delta.checkpoint.partSize = 2;
CREATE TABLE dmp (id INT, v STRING) USING delta TBLPROPERTIES ('delta.checkpointInterval' = '4');
INSERT INTO dmp VALUES (1, 'a');
INSERT INTO dmp VALUES (2, 'b');
INSERT INTO dmp VALUES (3, 'c');
INSERT INTO dmp VALUES (4, 'd');
SELECT 'dmp rows', * FROM dmp ORDER BY id;
RESET spark.databricks.delta.checkpoint.partSize;
CREATE TABLE dv2 (id INT, v STRING) USING delta
TBLPROPERTIES ('delta.checkpointPolicy' = 'v2', 'delta.checkpointInterval' = '2');
INSERT INTO dv2 VALUES (1, 'a');
INSERT INTO dv2 VALUES (2, 'b');
INSERT INTO dv2 VALUES (3, 'c');
DELETE FROM dv2 WHERE id = 1;
SELECT 'dv2 rows', * FROM dv2 ORDER BY id;
CREATE TABLE dlc (id INT, v STRING) USING delta TBLPROPERTIES ('delta.checkpointInterval' = '2');
INSERT INTO dlc VALUES (1, 'a');
INSERT INTO dlc VALUES (2, 'b');
INSERT INTO dlc VALUES (3, 'c');
INSERT INTO dlc VALUES (4, 'd');
--! ls -la /wh/dlc/_delta_log
--! for f in /wh/dlc/_delta_log/0000000000000000000[0-4].*; do touch -d '2020-01-01 00:00:00' "$f"; done
--! cp -Rp /wh/dlc /wh/dlcb
INSERT INTO dlc VALUES (5, 'e');
INSERT INTO dlc VALUES (6, 'f');
--! ls -la /wh/dlc/_delta_log
SELECT 'dlc rows', * FROM dlc ORDER BY id;
DESCRIBE HISTORY dlc;
