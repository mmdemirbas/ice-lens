-- Regenerates example/paimon/db.db/ptt and ptta — one Paimon primary-key table with three tags kept
-- twice: as written (`ptt`, copied on disk), and after a bare expire_tags removed the tag whose
-- retention had run out (`ptta`, the original, expired in place).
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-pe.sql. Which tags an expiry removes is
-- TagTimeExpire.expire at release-1.3.1: a tag records `tagCreateTime` and `tagTimeRetained`
-- only when created with a retention (`time_retained`, or `tag.default-time-retained`) — without
-- one it is the snapshot's JSON verbatim, so readers at 0.7 and below can still open it — and a
-- bare call removes a tag whose create time plus retention is before now, while `older_than`
-- removes any tag created before it, a tag recording no create time going by its file's
-- modification time. Removing a tag whose snapshot is still in snapshot/ deletes the tag file
-- alone; one whose snapshot has expired also frees the data files that neither the tag before
-- it nor the nearest of the earliest snapshot and the tag after it still lists (TagManager.doClean).
--
-- Statements, in order — the numbers matter because the expected values are read off them:
--
--   1  CREATE   primary key k, one bucket
--   2..4  three INSERTs                          snapshots 1..3 APPEND, a level-0 file each
--   5  CALL     create_tag('short', snapshot => 1, time_retained => '1 s')   tag/tag-short with tagCreateTime and tagTimeRetained PT1S
--   6  CALL     create_tag('long',  snapshot => 2, time_retained => '1 d')   tag/tag-long, PT24H
--   7  CALL     create_tag('plain', snapshot => 3)                           tag/tag-plain: snapshot 3's JSON, no create time
--   copy → ptt
--   8  CALL     expire_tags(table)               removes `short` alone — its second has passed by the time the
--                                               second spark-sql starts; `long` has a day and `plain` records
--                                               nothing a bare call reads; the procedure prints `short`
--   9  SELECT   ptta$tags                        long, plain
--
-- Expected on disk afterwards: `ptt` holds tag-short, tag-long and tag-plain; `ptta` tag-long and
-- tag-plain, every data file still there (snapshot 1 is retained, so removing its tag frees nothing).
--
-- A scratch copy (not checked in) was run further in the same container to see the rest of the
-- rule: `expire_tags(table, older_than => '2099-01-01 00:00:00')` on a copy of ptta removed `long`
-- and `plain` both, `plain` by its file's modification time; and on a copy of `tg`, whose tag-first
-- names an expired snapshot with a data file only the tag reaches, the same call removed the tag
-- and deleted that data file.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-ptt.sql:/tmp/paimon-ptt.sql:ro" \
--     -v "$PWD/docs/fixtures/paimon-ptt-expire.sql:/tmp/paimon-ptt-expire.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "S='/opt/spark/bin/spark-sql --master local[1] --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false'; \
--         \$S -f /tmp/paimon-ptt.sql && cp -R /wh/db.db/ptta /wh/db.db/ptt && \$S -f /tmp/paimon-ptt-expire.sql"
--   for t in ptt ptta; do rm -rf example/paimon/db.db/$t && cp -R "$WH/db.db/$t" example/paimon/db.db/$t; done

CREATE DATABASE IF NOT EXISTS db;
CREATE TABLE db.ptta (k INT, v STRING)
TBLPROPERTIES (
  'primary-key' = 'k',
  'bucket' = '1'
);

INSERT INTO db.ptta VALUES (1, 'a');
INSERT INTO db.ptta VALUES (2, 'b');
INSERT INTO db.ptta VALUES (3, 'c');

CALL sys.create_tag(table => 'db.ptta', tag => 'short', snapshot => 1, time_retained => '1 s');
CALL sys.create_tag(table => 'db.ptta', tag => 'long', snapshot => 2, time_retained => '1 d');
CALL sys.create_tag(table => 'db.ptta', tag => 'plain', snapshot => 3);
SELECT tag_name, snapshot_id, create_time, time_retained FROM db.`ptta$tags` ORDER BY tag_name;
