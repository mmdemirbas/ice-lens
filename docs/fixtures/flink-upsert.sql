-- Regenerates example/iceberg/default/fup — a merge-on-read table (`upsert` is a Flink keyword, hence the name) written by Flink's upsert
-- sink, which is the one writer that puts a delete file in the same commit as the data file it
-- can apply to. Every Spark-written fixture deletes in a commit of its own, so the sequence
-- rule's boundary — a positional delete applies at or below its number, an equality delete
-- strictly below — was pinned by hand-built entries only; this table separates the two on
-- engine-written bytes.
--
-- Written by Flink 1.20 (flink:1.20-scala_2.12-java17) with iceberg-flink-runtime-1.20 1.10.0
-- and Hadoop 3.3.6 on the classpath, hadoop catalog at /wh, one checkpoint per statement
-- (table.dml-sync waits for each job, and a bounded job in streaming mode commits on finish).
--
-- What the upsert sink writes (BaseTaskWriter.BaseEqualityDeltaWriter at 1.10.0): for every row
-- it first deletes the key — a POSITIONAL delete when the key was already written to the open
-- data file in this checkpoint, an EQUALITY delete otherwise — then writes the row. So:
--
--   1  INSERT (1, a), (2, b), (1, a2)   commit 1, sequence 1: data file [1 a, 2 b, 1 a2];
--                                       equality delete [1, 2] at sequence 1 — which must NOT
--                                       apply to the data file of the same commit — and a
--                                       positional delete (data file, position 0) at sequence 1,
--                                       which MUST, having been written for it
--   2  INSERT (2, b2), (4, d)           commit 2, sequence 2: data file [2 b2, 4 d]; equality
--                                       delete [2, 4] at sequence 2, which reaches commit 1's file
--                                       (2 b goes) and not its own
--
-- Rows a read returns afterwards: (1, a2), (2, b2), (4, d). Flink's own batch read of the table
-- is printed at the end of the run, and the pairing is printed by iceberg-scan-plans.scala.
--
-- To regenerate:
--
--   WH=$(mktemp -d); mkdir -p "$WH/wh"
--   C=~/code/spark-kit/lakelab/.cache
--   docker run --rm -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/flink-upsert.sql:/tmp/upsert.sql:ro" \
--     -v "$C/iceberg-flink-runtime-1.20-1.10.0.jar:/opt/flink/lib/iceberg-flink-runtime.jar:ro" \
--     -v "$C/hadoop-common-3.3.6.jar:/opt/flink/lib/hadoop-common.jar:ro" \
--     -v "$C/hadoop-client-api-3.3.6.jar:/opt/flink/lib/hadoop-client-api.jar:ro" \
--     -v "$C/hadoop-client-runtime-3.3.6.jar:/opt/flink/lib/hadoop-client-runtime.jar:ro" \
--     -v "$C/hadoop-auth-3.3.6.jar:/opt/flink/lib/hadoop-auth.jar:ro" \
--     -v "$C/commons-logging-1.2.jar:/opt/flink/lib/commons-logging.jar:ro" \
--     flink:1.20-scala_2.12-java17 bash -c \
--     "bin/start-cluster.sh && until (echo > /dev/tcp/127.0.0.1/8081) 2>/dev/null; do :; done; bin/sql-client.sh -f /tmp/upsert.sql"
--   rm -rf example/iceberg/default/fup && cp -R "$WH/wh/default/fup" example/iceberg/default/fup
--   find example/iceberg/default/fup -name '.*.crc' -delete

CREATE CATALOG lens WITH ('type' = 'iceberg', 'catalog-type' = 'hadoop', 'warehouse' = 'file:/wh');
USE CATALOG lens;
CREATE DATABASE IF NOT EXISTS `default`;
USE `default`;

SET 'execution.runtime-mode' = 'streaming';
SET 'execution.checkpointing.interval' = '2s';
SET 'parallelism.default' = '1';
SET 'table.exec.sink.upsert-materialize' = 'NONE';
SET 'table.dml-sync' = 'true';

CREATE TABLE fup (id INT, name STRING, PRIMARY KEY (id) NOT ENFORCED)
WITH ('format-version' = '2', 'write.upsert.enabled' = 'true');

INSERT INTO fup VALUES (1, 'a'), (2, 'b'), (1, 'a2');
INSERT INTO fup VALUES (2, 'b2'), (4, 'd');

SET 'execution.runtime-mode' = 'batch';
SET 'sql-client.execution.result-mode' = 'TABLEAU';
SELECT * FROM fup ORDER BY id;
