-- dplain — an unpartitioned Delta table with a classic checkpoint, a copy-on-write DELETE and an
-- UPDATE. delta.checkpointInterval = 3 puts a checkpoint at version 3 and _last_checkpoint beside
-- it, so versions 4 and 5 are replayed from that checkpoint plus two JSON commits.
--
--   v0  CREATE TABLE
--   v1  INSERT (1, alpha), (2, bravo)          one file
--   v2  INSERT (3, charlie)
--   v3  INSERT (4, delta)                      checkpoint 3
--   v4  DELETE id = 2                          v1's file removed, rewritten with (1, alpha)
--   v5  UPDATE id = 3 -> charlie-updated       v2's file removed, rewritten
CREATE TABLE dplain (id INT, name STRING) USING delta
TBLPROPERTIES ('delta.checkpointInterval' = '3', 'delta.enableDeletionVectors' = 'false');
INSERT INTO dplain VALUES (1, 'alpha'), (2, 'bravo');
INSERT INTO dplain VALUES (3, 'charlie');
INSERT INTO dplain VALUES (4, 'delta');
DELETE FROM dplain WHERE id = 2;
UPDATE dplain SET name = 'charlie-updated' WHERE id = 3;
SELECT 'rows', * FROM dplain ORDER BY id;
DESCRIBE HISTORY dplain;
