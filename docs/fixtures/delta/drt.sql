-- drt — row tracking and in-commit timestamps. Every add records baseRowId and
-- defaultRowCommitVersion, the delta.rowTracking domain holds the high-water mark, and an UPDATE
-- rewrites a row into a file whose materialised row-id column keeps the id the row had. Each
-- commitInfo carries inCommitTimestamp, which a reader takes over the file's modification time.
--
--   v0  CREATE (row tracking, in-commit timestamps)
--   v1  INSERT 1, 2, 3                 baseRowId 0
--   v2  INSERT 4, 5                    baseRowId 3
--   v3  UPDATE id = 2                  the row keeps row id 1 in a new file
CREATE TABLE drt (id INT, v STRING) USING delta
TBLPROPERTIES ('delta.enableRowTracking' = 'true', 'delta.enableInCommitTimestamps-preview' = 'true');
INSERT INTO drt VALUES (1, 'a'), (2, 'b'), (3, 'c');
INSERT INTO drt VALUES (4, 'd'), (5, 'e');
UPDATE drt SET v = 'B' WHERE id = 2;
SELECT 'rows', id, v, _metadata.row_id, _metadata.row_commit_version FROM drt ORDER BY id;
DESCRIBE HISTORY drt;
