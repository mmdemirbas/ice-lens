-- dinl — inline deletion vectors (storageType 'i'), which delta-spark 3.2.1 reads and never writes
-- to the log. The DELETE below writes a vector file; dinl.scala then commits two inline vectors
-- through the writer's own classes — a RoaringBitmapArray serialized in the portable format,
-- DeletionVectorDescriptor.inlineInLog, AddFile.removeRows, a Manual Update commit — so the bytes
-- and the descriptor are Delta's, not assembled here, and delta-spark's reads after it are the
-- oracle.
--   v1   four rows in one file; v2 six rows in a second
--   v3   DELETE id = 2: a vector file marking its position in the first file
--   v4   dinl.scala: the first file's vector written again inline, the same position, with
--        dataChange false; and a new inline vector on the second file, marking ids 6, 8 and 10
--   v5   DELETE id = 3: delta-spark reads the first file's inline vector, adds the position and
--        writes the union to a vector file; the second file's inline vector stays
CREATE TABLE dinl (id INT, v STRING) USING delta TBLPROPERTIES ('delta.enableDeletionVectors' = 'true');
INSERT INTO dinl VALUES (1, 'a'), (2, 'b'), (3, 'c'), (4, 'd');
INSERT INTO dinl VALUES (5, 'e'), (6, 'f'), (7, 'g'), (8, 'h'), (9, 'i'), (10, 'j');
DELETE FROM dinl WHERE id = 2;
--! scala /scripts/dinl.scala
SELECT 'v4', * FROM dinl ORDER BY id;
DELETE FROM dinl WHERE id = 3;
SELECT 'rows', * FROM dinl ORDER BY id;
SELECT 'asof4', * FROM dinl VERSION AS OF 4 ORDER BY id;
DESCRIBE HISTORY dinl;
