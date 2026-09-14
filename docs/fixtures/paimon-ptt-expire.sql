-- The second half of paimon-ptt.sql, run after `ptta` has been copied to `ptt`: the bare expiry
-- that separates the two.

CALL sys.expire_tags(table => 'db.ptta');
SELECT tag_name, snapshot_id, create_time, time_retained FROM db.`ptta$tags` ORDER BY tag_name;
