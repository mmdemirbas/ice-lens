-- dcdf — the change data feed. With delta.enableChangeDataFeed a commit that rewrites rows writes
-- the change rows beside the data: a cdc action naming a file under _change_data/<partition>/,
-- whose rows carry _change_type (update_preimage, update_postimage, delete, insert). A commit that
-- only adds or only removes whole files writes none — the feed reads those files as inserts or
-- deletes — so the INSERT and the DELETE by partition have no cdc action and the UPDATE and the
-- MERGE do. A DELETE by a data column is a rewrite even when no row of the file survives, and
-- writes a cdc file: only a predicate on partition columns alone removes files without reading
-- them (a first run deleted `id = 3` and got one).
--
--   v0  CREATE PARTITIONED BY (p), change data feed on
--   v1  INSERT (1, a, x) (2, b, x) (3, c, y)      one file per partition
--   v2  UPDATE id = 2 → B                          p=x rewritten; a cdc file: pre and post image
--   v3  DELETE p = 'y'                             the p=y file removed whole; no cdc file
--   v4  MERGE (1 → A) (insert 4, d, y)             a cdc file per partition touched
CREATE TABLE dcdf (id INT, v STRING, p STRING) USING delta PARTITIONED BY (p)
TBLPROPERTIES ('delta.enableChangeDataFeed' = 'true');
INSERT INTO dcdf VALUES (1, 'a', 'x'), (2, 'b', 'x'), (3, 'c', 'y');
UPDATE dcdf SET v = 'B' WHERE id = 2;
DELETE FROM dcdf WHERE p = 'y';
MERGE INTO dcdf t USING (SELECT * FROM VALUES (1, 'A', 'x'), (4, 'd', 'y') AS s(id, v, p)) s
ON t.id = s.id
WHEN MATCHED THEN UPDATE SET v = s.v
WHEN NOT MATCHED THEN INSERT *;
SELECT 'rows', * FROM dcdf ORDER BY id;
SELECT 'change', _commit_version, _change_type, id, v, p FROM table_changes('dcdf', 0)
ORDER BY _commit_version, id, _change_type;
DESCRIBE HISTORY dcdf;
