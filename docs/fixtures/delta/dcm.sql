-- dcm — column mapping in name mode. Every column gets a physical name (col-<uuid>) and an id in
-- its field metadata; the Parquet files, the stats and partitionValues are keyed by the physical
-- name, so a rename or a drop rewrites nothing. Renamed after the first file: a top-level column,
-- a field inside a struct, and the partition column itself; one column dropped, one added.
--
--   v0  CREATE (id, v, drop_me, addr<city, zip>, p) PARTITIONED BY (p), mode name
--   v1  INSERT (1, a, x) (2, b, y)
--   v2  RENAME v TO label
--   v3  RENAME addr.city TO town
--   v4  DROP drop_me
--   v5  ADD w
--   v6  INSERT (3, c, x, 30)
--   v7  RENAME p TO part
--   v8  INSERT (4, d, y, 40)
CREATE TABLE dcm (id INT, v STRING, drop_me STRING, addr STRUCT<city: STRING, zip: INT>, p STRING)
USING delta PARTITIONED BY (p)
TBLPROPERTIES ('delta.columnMapping.mode' = 'name');
INSERT INTO dcm VALUES (1, 'a', 'gone1', named_struct('city', 'Ankara', 'zip', 6000), 'x'),
                       (2, 'b', 'gone2', named_struct('city', 'Izmir', 'zip', 35000), 'y');
ALTER TABLE dcm RENAME COLUMN v TO label;
ALTER TABLE dcm RENAME COLUMN addr.city TO town;
ALTER TABLE dcm DROP COLUMN drop_me;
ALTER TABLE dcm ADD COLUMNS (w INT);
INSERT INTO dcm VALUES (3, 'c', named_struct('town', 'Bursa', 'zip', 16000), 'x', 30);
ALTER TABLE dcm RENAME COLUMN p TO part;
INSERT INTO dcm VALUES (4, 'd', named_struct('town', 'Konya', 'zip', 42000), 'y', 40);
SELECT 'rows', id, label, addr.town, addr.zip, part, w FROM dcm ORDER BY id;
SELECT 'label=b', id FROM dcm WHERE label = 'b';
SELECT 'town=Bursa', id FROM dcm WHERE addr.town = 'Bursa';
SELECT 'part=x', id FROM dcm WHERE part = 'x' ORDER BY id;
SELECT 'w null', id FROM dcm WHERE w IS NULL ORDER BY id;
DESCRIBE HISTORY dcm;
