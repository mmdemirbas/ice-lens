-- drs — RESTORE. Three inserts, a delete of the first file, then RESTORE TO VERSION AS OF 2:
-- the restore commit re-adds the file the delete removed and removes the file version 3 added,
-- and records numRestoredFiles and numRemovedFiles for them.
--
--   v0  CREATE
--   v1  INSERT (1, a)
--   v2  INSERT (2, b)
--   v3  INSERT (3, c)
--   v4  DELETE id = 1                   the v1 file removed whole
--   v5  RESTORE TO VERSION AS OF 2      the v1 file back, the v3 file removed
CREATE TABLE drs (id INT, v STRING) USING delta;
INSERT INTO drs VALUES (1, 'a');
INSERT INTO drs VALUES (2, 'b');
INSERT INTO drs VALUES (3, 'c');
DELETE FROM drs WHERE id = 1;
RESTORE TABLE drs TO VERSION AS OF 2;
SELECT 'rows', * FROM drs ORDER BY id;
DESCRIBE HISTORY drs;
