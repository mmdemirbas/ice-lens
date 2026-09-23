-- dpart — partitioned by (region STRING, dt DATE), a null region among them, and
-- delta.checkpoint.writeStatsAsStruct on so the checkpoint carries partitionValues_parsed and
-- stats_parsed beside the JSON strings. Two inserts, then a checkpoint at version 2.
--
--   v0  CREATE
--   v1  INSERT four rows over four partitions  (eu, 03-05) (eu, 03-06) (us, 03-05) (null, 03-07)
--   v2  INSERT (5, 50.0, us, 03-07)            checkpoint 2
CREATE TABLE dpart (id INT, amount DOUBLE, region STRING, dt DATE) USING delta
PARTITIONED BY (region, dt)
TBLPROPERTIES ('delta.checkpointInterval' = '2', 'delta.checkpoint.writeStatsAsStruct' = 'true');
INSERT INTO dpart VALUES (1, 10.5, 'eu', DATE'2024-03-05'), (2, 20.0, 'eu', DATE'2024-03-06'),
                         (3, 30.0, 'us', DATE'2024-03-05'), (4, 40.0, NULL, DATE'2024-03-07');
INSERT INTO dpart VALUES (5, 50.0, 'us', DATE'2024-03-07');
SELECT 'rows', * FROM dpart ORDER BY id;
DESCRIBE HISTORY dpart;
