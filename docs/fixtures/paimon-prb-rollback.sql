-- The second half of paimon-prb.sql, run after `prba` has been copied to `prb`: the rollback that
-- separates the two.

CALL sys.rollback(table => 'db.prba', version => '2');
SELECT * FROM db.prba ORDER BY k;
SELECT tag_name, snapshot_id FROM db.`prba$tags` ORDER BY tag_name;
