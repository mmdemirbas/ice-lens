-- dcmid — dcm in id mode: the same statements, and a reader places each Parquet column by the
-- field id the file records rather than by its physical name. The ids are the ones the schema
-- carries as delta.columnMapping.id, so a file written before a rename is still its column.
--
--   v0  CREATE (id, v, drop_me, addr<city, zip>, p) PARTITIONED BY (p), mode id
--   v1  INSERT (1, a, x) (2, b, y)
--   v2  RENAME v TO label
--   v3  RENAME addr.city TO town
--   v4  DROP drop_me
--   v5  ADD w
--   v6  INSERT (3, c, x, 30)
--   v7  RENAME p TO part
--   v8  INSERT (4, d, y, 40)
CREATE TABLE dcmid (id INT, v STRING, drop_me STRING, addr STRUCT<city: STRING, zip: INT>, p STRING)
USING delta PARTITIONED BY (p)
TBLPROPERTIES ('delta.columnMapping.mode' = 'id');
INSERT INTO dcmid VALUES (1, 'a', 'gone1', named_struct('city', 'Ankara', 'zip', 6000), 'x'),
                       (2, 'b', 'gone2', named_struct('city', 'Izmir', 'zip', 35000), 'y');
ALTER TABLE dcmid RENAME COLUMN v TO label;
ALTER TABLE dcmid RENAME COLUMN addr.city TO town;
ALTER TABLE dcmid DROP COLUMN drop_me;
ALTER TABLE dcmid ADD COLUMNS (w INT);
INSERT INTO dcmid VALUES (3, 'c', named_struct('town', 'Bursa', 'zip', 16000), 'x', 30);
ALTER TABLE dcmid RENAME COLUMN p TO part;
INSERT INTO dcmid VALUES (4, 'd', named_struct('town', 'Konya', 'zip', 42000), 'y', 40);
SELECT 'rows', id, label, addr.town, addr.zip, part, w FROM dcmid ORDER BY id;
SELECT 'label=b', id FROM dcmid WHERE label = 'b';
SELECT 'town=Bursa', id FROM dcmid WHERE addr.town = 'Bursa';
SELECT 'part=x', id FROM dcmid WHERE part = 'x' ORDER BY id;
SELECT 'w null', id FROM dcmid WHERE w IS NULL ORDER BY id;
DESCRIBE HISTORY dcmid;
