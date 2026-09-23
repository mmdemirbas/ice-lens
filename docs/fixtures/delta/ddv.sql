-- ddv — deletion vectors. A thousand-row file and a three-row file; a DELETE marking one row in
-- each writes one vector per file into one deletion_vector_<uuid>.bin (storageType u, a relative
-- path from a Z85-encoded UUID) and re-adds each file with it — remove (path, no DV), add (path,
-- DV). A second DELETE on the big file replaces its vector: remove (path, old DV), add (path, new
-- DV), the new one holding both positions. The UPDATE marks 1001 and writes the new row to a new
-- file. Rows left: 1..1000 less 2 and 3, plus 1001 updated, 1003.
--
--   v0  CREATE (deletion vectors on)
--   v1  INSERT 1..1000                         one file
--   v2  INSERT 1001, 1002, 1003                one file
--   v3  DELETE id IN (2, 1002)                 a vector on each file
--   v4  DELETE id = 3                          the big file's vector replaced: {1, 2} by position
--   v5  UPDATE id = 1001                       the small file's vector grows; a new file for 1001
CREATE TABLE ddv (id INT, v STRING) USING delta
TBLPROPERTIES ('delta.enableDeletionVectors' = 'true');
INSERT INTO ddv SELECT CAST(id AS INT), concat('v', id) FROM range(1, 1001);
INSERT INTO ddv VALUES (1001, 'x'), (1002, 'y'), (1003, 'z');
DELETE FROM ddv WHERE id IN (2, 1002);
DELETE FROM ddv WHERE id = 3;
UPDATE ddv SET v = 'updated' WHERE id = 1001;
SELECT 'count', count(*) FROM ddv;
SELECT 'rows', * FROM ddv WHERE id <= 5 OR id > 1000 ORDER BY id;
DESCRIBE HISTORY ddv;
