-- dcdfdv — the change data feed on a table with deletion vectors. A DELETE or an UPDATE marks
-- rows in a vector rather than rewriting the file. The DELETE writes no cdc file — its commit is
-- the file removed and re-added under the new vector, and the feed reads the newly marked rows as
-- deletes. The UPDATE and the MERGE write cdc files beside their vectors (one and two), and the
-- metrics count the UPDATE's among the bytes it removed (UpdateCommand at 3.2.1 splits its new
-- actions into AddFile and the rest).
--
--   v2  DELETE     remove + add, no cdc
--   v3  UPDATE     remove + 2 adds + 1 cdc
--   v4  MERGE      2 removes + 3 adds + 2 cdc
--
--   v0  CREATE PARTITIONED BY (p), change data feed and deletion vectors on
--   v1  INSERT (1, a, x) (2, b, x) (3, c, x) (4, d, y) (5, e, y)
--   v2  DELETE id = 2                  a vector on the p=x file
--   v3  UPDATE id = 4 → D              a vector on the p=y file, a new file for the row
--   v4  MERGE (1 → A) (delete 5) (insert 6, f, y)
CREATE TABLE dcdfdv (id INT, v STRING, p STRING) USING delta PARTITIONED BY (p)
TBLPROPERTIES ('delta.enableChangeDataFeed' = 'true', 'delta.enableDeletionVectors' = 'true');
INSERT INTO dcdfdv VALUES (1, 'a', 'x'), (2, 'b', 'x'), (3, 'c', 'x'), (4, 'd', 'y'), (5, 'e', 'y');
DELETE FROM dcdfdv WHERE id = 2;
UPDATE dcdfdv SET v = 'D' WHERE id = 4;
MERGE INTO dcdfdv t USING (SELECT * FROM VALUES (1, 'A', 'x', false), (5, 'e', 'y', true), (6, 'f', 'y', false) AS s(id, v, p, del)) s
ON t.id = s.id
WHEN MATCHED AND s.del THEN DELETE
WHEN MATCHED THEN UPDATE SET v = s.v
WHEN NOT MATCHED THEN INSERT (id, v, p) VALUES (s.id, s.v, s.p);
SELECT 'rows', * FROM dcdfdv ORDER BY id;
SELECT 'change', _commit_version, _change_type, id, v, p FROM table_changes('dcdfdv', 0)
ORDER BY _commit_version, id, _change_type;
DESCRIBE HISTORY dcdfdv;
