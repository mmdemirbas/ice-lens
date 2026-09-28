-- dzo — OPTIMIZE ZORDER BY four times on one table, each commit the oracle for the plan at the
-- version before it.
--   v1–v4  partitioned by p: three one-row files in p=x (the first holding two rows), one in p=y
--   v5     a DELETE under deletion vectors leaves a vector on the first p=x file
--   v6     ZORDER BY (a, b): every file of both partitions, the vector's file and the lone p=y
--          file included — one bin per partition, and a bin of one is rewritten too
--   v7     ZORDER BY (a, b) again: the two files v6 wrote, rewritten again — nothing is skipped
--   v8     one more row in p=x
--   v9     ZORDER BY (b): the three files, whatever columns ordered them before
--   v10    under optimize.maxFileSize = 300 bytes: a partition asks for size / 300 files, and gets
--          at most that many — as many as the range partitioning fills
CREATE TABLE dzo (id INT, a INT, b STRING, p STRING) USING delta PARTITIONED BY (p)
  TBLPROPERTIES ('delta.enableDeletionVectors' = 'true');
INSERT INTO dzo VALUES (1, 5, 'e', 'x'), (6, 9, 'i', 'x');
INSERT INTO dzo VALUES (2, 3, 'c', 'x');
INSERT INTO dzo VALUES (3, 1, 'a', 'x');
INSERT INTO dzo VALUES (4, 4, 'd', 'y');
DELETE FROM dzo WHERE id = 6;
OPTIMIZE dzo ZORDER BY (a, b);
OPTIMIZE dzo ZORDER BY (a, b);
INSERT INTO dzo VALUES (5, 2, 'b', 'x');
OPTIMIZE dzo ZORDER BY (b);
SET spark.databricks.delta.optimize.maxFileSize = 300;
OPTIMIZE dzo ZORDER BY (a);
SELECT 'rows', * FROM dzo ORDER BY id;
DESCRIBE HISTORY dzo;
