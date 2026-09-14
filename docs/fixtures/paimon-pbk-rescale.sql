-- The second half of paimon-pbk.sql, run after `pbka` has been copied to `pbk`: the rescale, and
-- the write it makes possible again.

INSERT OVERWRITE db.pbka SELECT * FROM db.pbka;
INSERT INTO db.pbka VALUES (4, 'd');
SELECT * FROM db.pbka ORDER BY k;
