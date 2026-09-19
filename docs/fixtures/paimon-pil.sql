-- Regenerates example/paimon/db.db/pil — `pic` compacted: a primary-key table with its Iceberg
-- export beside it (`metadata.iceberg.storage = table-location`) and **no** deletion vectors,
-- written until the writer's own compaction leaves one file at level 5 and one at level 4. It is
-- the table the export's level rule was read for and no fixture held: `shouldAddFileToIceberg`
-- at release-1.3.1 exports a primary-key table's file only at `num-levels - 1` (5 on the
-- defaults), so the level-4 file is left out and an Iceberg reader of the table never sees its
-- rows — `pic` reaches the rule with level-0 files alone, which `level > 0` (the rule under
-- exported vectors, `pid`) would leave out just the same, and `pid` exports its level-4 file
-- under that looser rule.
--
-- The two compactions are the writer's (`UniversalCompaction.pick`), which is what decides the
-- levels: the fifth run compacts by size amplification into level 5 when the four newer runs
-- weigh 200% of the oldest, else by size ratio — the newest runs within 1% of each other, into
-- the level below the first left out, which is level 4 when what is left out is the level-5
-- file. So a thousand-row first file is the whole design: four one-row files weigh a fraction
-- of it, size amplification never fires, and the size ratio's walk stops at it — at the fifth
-- run every file is still at level 0 and the pick lands on the top level, since the output level
-- may not be 0 and taking the level-0 file in takes every run; at the tenth the big file is at
-- level 5, the four small ones go into level 4, and it stays where it is.
--
-- Statements, in order:
--
--   1  CREATE   (k INT, v STRING) primary-key k, bucket 1, metadata.iceberg.storage = table-location
--   2  INSERT   a thousand rows, k = 1..1000, v = v<k>                snapshot-1 (rebuild: the export lists its file)
--   3..6 INSERT one row each, k = 1001..1004                          snapshots 2..5, then COMPACT snapshot-6 into level 5
--   7..10 INSERT one row each: k 1005, k 1 = V0001, k 1006, k 2 = V0002 snapshots 7..10, then COMPACT snapshot-11 into level 4
--   11 SELECT count(*), and the four keys                             Paimon: 1006; 1 V0001 / 2 V0002 / 1005 / 1006
--   12 the same through Iceberg's own reader, on the export           1004; 1 v1 / 2 v2 — the level-4 file unseen
--
-- Both readers printed what the header predicted. Two things the metadata shows besides: the
-- level-0 file snapshot 1's rebuild had listed is gone from `v11.metadata.json` — the
-- compaction's `DELETE` entries go through the incremental path too — so the export lists the
-- level-5 file alone; and the two `COMPACT` commits' manifest lists are `snap-2-…`, the second
-- write of their run under the callback's per-run counter, where every `APPEND`'s is `snap-1-…`.
--
-- To regenerate (the `ice` catalog is Iceberg 1.8.1's HadoopCatalog over the same warehouse,
-- for the last two statements; a namespace of `db.db` is what puts it at the table):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-pil.sql:/tmp/paimon-pil.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.sql.catalog.ice=org.apache.iceberg.spark.SparkCatalog \
--           --conf spark.sql.catalog.ice.type=hadoop \
--           --conf spark.sql.catalog.ice.warehouse=/wh \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-pil.sql"
--   rm -rf example/paimon/db.db/pil && cp -R "$WH/db.db/pil" example/paimon/db.db/pil
--   find example/paimon/db.db/pil -name '.*.crc' -delete

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.pil (k INT, v STRING)
TBLPROPERTIES ('primary-key' = 'k', 'bucket' = '1', 'metadata.iceberg.storage' = 'table-location');

INSERT INTO db.pil SELECT id AS k, concat('v', id) AS v FROM range(1, 1001);

INSERT INTO db.pil VALUES (1001, 'v1001');
INSERT INTO db.pil VALUES (1002, 'v1002');
INSERT INTO db.pil VALUES (1003, 'v1003');
INSERT INTO db.pil VALUES (1004, 'v1004');

INSERT INTO db.pil VALUES (1005, 'v1005');
INSERT INTO db.pil VALUES (1, 'V0001');
INSERT INTO db.pil VALUES (1006, 'v1006');
INSERT INTO db.pil VALUES (2, 'V0002');

SELECT count(*) FROM db.pil;
SELECT k, v FROM db.pil WHERE k IN (1, 2, 1005, 1006) ORDER BY k;

SELECT count(*) FROM ice.`db.db`.pil;
SELECT k, v FROM ice.`db.db`.pil WHERE k IN (1, 2, 1005, 1006) ORDER BY k;
