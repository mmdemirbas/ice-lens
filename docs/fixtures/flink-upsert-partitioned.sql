-- Regenerates example/iceberg/default/fupp — `fup`'s partitioned twin: the same Flink upsert
-- sink over a table partitioned by `p`, with `p` in the primary key (the sink requires the
-- partition columns among the equality fields). Every commit's deletes land per partition, and
-- an equality delete in `p=y` must not reach a file in `p=x` however low its sequence number is.
-- What this table holds the pairing to is the BOUNDS rule: commit 2's equality delete in `p=x`
-- holds id 4 alone, and commit 1's `p=x` data file holds ids 1..1, so Iceberg attaches nothing
-- to that file but its own positional delete. It cannot separate the partition rule from that:
-- `p` is an equality column, so the bounds on it already differ across partitions and the
-- `p=y` delete is ruled out for the `p=x` file by bounds as soon as the partition is not
-- compared. docs/fixtures/eqpart.scala is the table where the partition alone decides.
--
-- Written the same way as flink-upsert.sql (see its header for the container and the sink).
--
--   1  INSERT (1,x,a), (2,y,b), (1,x,a2)   commit 1: p=x data [1 a, 1 a2] + equality delete [1]
--                                          + positional delete (x data, 0); p=y data [2 b] +
--                                          equality delete [2]; all at sequence 1
--   2  INSERT (2,y,b2), (4,x,d)            commit 2: p=y data [2 b2] + equality delete [2];
--                                          p=x data [4 d] + equality delete [4]; sequence 2
--
-- Rows afterwards: (1, x, a2), (2, y, b2), (4, x, d).
--
-- To regenerate: the flink-upsert.sql recipe with this file mounted as /tmp/upsert.sql, then
--   rm -rf example/iceberg/default/fupp && cp -R "$WH/wh/default/fupp" example/iceberg/default/fupp
--   find example/iceberg/default/fupp -name '.*.crc' -delete

CREATE CATALOG lens WITH ('type' = 'iceberg', 'catalog-type' = 'hadoop', 'warehouse' = 'file:/wh');
USE CATALOG lens;
CREATE DATABASE IF NOT EXISTS `default`;
USE `default`;

SET 'execution.runtime-mode' = 'streaming';
SET 'execution.checkpointing.interval' = '2s';
SET 'parallelism.default' = '1';
SET 'table.exec.sink.upsert-materialize' = 'NONE';
SET 'table.dml-sync' = 'true';

CREATE TABLE fupp (id INT, p STRING, name STRING, PRIMARY KEY (id, p) NOT ENFORCED)
PARTITIONED BY (p)
WITH ('format-version' = '2', 'write.upsert.enabled' = 'true');

INSERT INTO fupp VALUES (1, 'x', 'a'), (2, 'y', 'b'), (1, 'x', 'a2');
INSERT INTO fupp VALUES (2, 'y', 'b2'), (4, 'x', 'd');

SET 'execution.runtime-mode' = 'batch';
SET 'sql-client.execution.result-mode' = 'TABLEAU';
SELECT * FROM fupp ORDER BY id;
