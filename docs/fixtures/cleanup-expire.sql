-- The second half of docs/fixtures/cleanup.sql — see its header. Runs after the two tables have
-- been copied on disk, so `sweep` and `sweepb` keep every file and `swept` and `sweptb` lose what
-- the expiry freed.

CALL system.expire_snapshots(table => 'default.swept', older_than => TIMESTAMP '2099-01-01 00:00:00', retain_last => 1);
CALL system.expire_snapshots(table => 'default.sweptb', older_than => TIMESTAMP '2099-01-01 00:00:00', retain_last => 1);

SELECT 'swept-after' AS t, snapshot_id, parent_id, operation FROM default.swept.snapshots ORDER BY committed_at;
SELECT 'swept-after' AS t, path FROM default.swept.all_manifests ORDER BY path;
SELECT 'swept-after' AS t, file_path FROM default.swept.files ORDER BY file_path;
SELECT 'sweptb-after' AS t, snapshot_id, parent_id, operation FROM default.sweptb.snapshots ORDER BY committed_at;
SELECT 'sweptb-after' AS t, path FROM default.sweptb.all_manifests ORDER BY path;
SELECT 'sweptb-after' AS t, file_path FROM default.sweptb.files ORDER BY file_path;
