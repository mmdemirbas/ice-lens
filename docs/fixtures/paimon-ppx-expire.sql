-- The second half of paimon-ppx.sql, run after `ppxa` has been copied to `ppx`: the expiry that
-- separates the two, and the check that the update-time strategy expires nothing on files
-- written seconds ago.

CALL sys.expire_partitions(table => 'db.ppxa');
CALL sys.expire_partitions(table => 'db.ppxa', expire_strategy => 'update-time');
SELECT * FROM db.ppxa ORDER BY k;
