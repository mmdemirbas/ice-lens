-- dcl — liquid clustering: OPTIMIZE on a CLUSTER BY table, run six times on one table, each
-- commit — or the absence of one — the oracle for the plan at the version before it.
--   v1–v3  three inserts, the first of two rows: unclustered files, no clusteringProvider, no tags
--   v4     a DELETE under deletion vectors leaves a vector on v1's file
--   v5     OPTIMIZE: the three unclustered files into one cube, tagged ZCUBE_ID and
--          ZCUBE_ZORDER_BY ["a"], clusteringProvider "liquid"
--   —      OPTIMIZE again: one cube and no unclustered file — nothing, no commit
--   v6     one more row, unclustered
--   v7     OPTIMIZE: the cube, far under the 100 GiB threshold, merged with the new file
--   v8     a DELETE leaves a vector on the cube's file
--   v9     ALTER TABLE CLUSTER BY (b)
--   v10    one more row, unclustered
--   v11    OPTIMIZE: the new file alone, into a cube clustered by b; the cube clustered by a is
--          skipped, and its vector is counted in numDeletionVectorsRemoved all the same
--   —      OPTIMIZE again: the b cube is alone and the a cube skipped — nothing, no commit
CREATE TABLE dcl (id INT, a INT, b STRING) USING delta CLUSTER BY (a)
  TBLPROPERTIES ('delta.enableDeletionVectors' = 'true');
INSERT INTO dcl VALUES (1, 5, 'e'), (6, 9, 'i');
INSERT INTO dcl VALUES (2, 3, 'c');
INSERT INTO dcl VALUES (3, 1, 'a');
DELETE FROM dcl WHERE id = 6;
OPTIMIZE dcl;
OPTIMIZE dcl;
INSERT INTO dcl VALUES (4, 4, 'd');
OPTIMIZE dcl;
DELETE FROM dcl WHERE id = 2;
ALTER TABLE dcl CLUSTER BY (b);
INSERT INTO dcl VALUES (5, 2, 'b');
OPTIMIZE dcl;
OPTIMIZE dcl;
SELECT 'rows', * FROM dcl ORDER BY id;
DESCRIBE HISTORY dcl;
