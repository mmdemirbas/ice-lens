-- dow — OPTIMIZE … WHERE on partition columns, in compaction and ZORDER BY, on one table: each
-- commit — or the absence of one — the oracle for the plan at the version before it under the
-- predicate the commit records.
--   v1–v8  partitioned by (p, d): two one-row files in each of (x, 03-05), (x, 03-06), (y, 03-05)
--          and (null, 03-06)
--   v9     a DELETE under deletion vectors leaves a vector on a (y, 03-05) file
--   v10    WHERE p = 'x': the two x partitions compacted, two files into one each; the y vector
--          is outside the predicate and not counted in numDeletionVectorsRemoved
--   —      WHERE p = 'x' again: each x partition is one file now, a bin of one — no commit
--   —      WHERE p = 'q': no partition matches — no commit
--   v11    WHERE d >= '2024-03-06': a string literal against a DATE partition; (x, 03-06) is one
--          file and left, (null, 03-06) compacted
--   v12    WHERE p <> 'x' ZORDER BY (a): the y partition alone — the null partition is not
--          p <> 'x', since a comparison with null is null — its vector counted
--   v13    WHERE NOT (p LIKE '%y') OR p IS NULL ZORDER BY (a, id): every partition but y, each
--          a lone file and each rewritten
CREATE TABLE dow (id INT, a INT, p STRING, d DATE) USING delta PARTITIONED BY (p, d)
  TBLPROPERTIES ('delta.enableDeletionVectors' = 'true');
INSERT INTO dow VALUES (1, 5, 'x', DATE'2024-03-05');
INSERT INTO dow VALUES (2, 3, 'x', DATE'2024-03-05');
INSERT INTO dow VALUES (3, 1, 'x', DATE'2024-03-06');
INSERT INTO dow VALUES (4, 4, 'x', DATE'2024-03-06');
INSERT INTO dow VALUES (5, 9, 'y', DATE'2024-03-05'), (6, 7, 'y', DATE'2024-03-05');
INSERT INTO dow VALUES (7, 2, 'y', DATE'2024-03-05');
INSERT INTO dow VALUES (8, 6, NULL, DATE'2024-03-06');
INSERT INTO dow VALUES (9, 8, NULL, DATE'2024-03-06');
DELETE FROM dow WHERE id = 6;
OPTIMIZE dow WHERE p = 'x';
OPTIMIZE dow WHERE p = 'x';
OPTIMIZE dow WHERE p = 'q';
OPTIMIZE dow WHERE d >= '2024-03-06';
OPTIMIZE dow WHERE p <> 'x' ZORDER BY (a);
OPTIMIZE dow WHERE NOT (p LIKE '%y') OR p IS NULL ZORDER BY (a, id);
SELECT 'rows', * FROM dow ORDER BY id;
DESCRIBE HISTORY dow;
