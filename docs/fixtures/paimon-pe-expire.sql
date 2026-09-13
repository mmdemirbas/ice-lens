-- The second half of docs/fixtures/paimon-pe.sql — see its header. Runs after the table has been
-- copied on disk, so `pe` keeps every file and `pea` loses what the expiry freed.

CALL sys.expire_snapshots(table => 'db.pea', retain_max => 2, retain_min => 1);

SELECT 'pea-after' AS t, snapshot_id, commit_kind FROM db.`pea$snapshots` ORDER BY snapshot_id;
SELECT 'pea-after' AS t, file_path, level FROM db.`pea$files` ORDER BY file_path;
SELECT 'pea-after' AS t, tag_name, snapshot_id FROM db.`pea$tags`;
