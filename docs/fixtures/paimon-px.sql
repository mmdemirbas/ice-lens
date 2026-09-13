-- Regenerates example/paimon/db.db/px and example/paimon/db.db/pxa — one Paimon table written
-- twice, so that what expire_snapshots will do to the first can be checked against what it did
-- to the second.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-cs.sql. Paimon's ExpireSnapshotsImpl.expire() decides
-- an expiry from six things — the newest snapshot.num-retained.min are kept, anything beyond
-- snapshot.num-retained.max goes whatever its age, a consumer's next snapshot and everything after
-- it stay, at most snapshot.expire.limit go in one run, and between those bounds the run stops at
-- the first snapshot younger than snapshot.time-retained. model/PaimonExpiryPlan.kt applies the
-- same rules to a table that has not been expired yet; this script is where they meet real bytes.
-- The two tables get identical statements up to the expiry, so their snapshot ids, consumer and
-- tag agree, and the plan for px is checked against the survivors in pxa.
--
-- Statements, in order — the numbers matter because the expected values in
-- PaimonExpiryFixtureTest are read off them:
--
--   1  CREATE   px: primary key k, one bucket, write-only (no compaction, so the six
--            inserts are snapshots 1..6 and nothing else)
--   2..7  six INSERTs                              snapshots 1..6, one row each
--   8  CALL sys.create_tag   'keep' on snapshot 2     tag/tag-keep
--   9  CALL sys.reset_consumer  'reader' to next snapshot 4    consumer/consumer-reader
--  10  CALL sys.expire_snapshots  retain_min = 1, every other option at its default
--                                                  Removes nothing: retain_max is unbounded, so
--                                                  the walk starts at snapshot 1, and snapshot 1
--                                                  is seconds old against a one-hour
--                                                  time-retained. snapshot/ still holds 1..6.
--  11..19  the same for pxa, up to the consumer
--  20  CALL sys.expire_snapshots  retain_max = 2, retain_min = 1
--                                                  Snapshots 1, 2 and 3 go — they are beyond
--                                                  retain_max and their age is never consulted.
--                                                  The consumer stops the run at 4; retain_min
--                                                  alone would have allowed 5. Snapshot 2 lives
--                                                  on as tag/tag-keep.
--
-- Expected on disk afterwards: px/snapshot holds 1..6 (EARLIEST = 1, LATEST = 6); pxa/snapshot
-- holds 4, 5 and 6 (EARLIEST = 4); pxa/tag/tag-keep is snapshot 2; pxa/consumer/consumer-reader
-- is {"nextSnapshot": 4}; and pxa/bucket-0 still holds all six data files. That last one is the
-- trap: an expiry removes a data file only once a later snapshot has stopped listing it, and with
-- no compaction snapshot 4 still lists every file 1..3 wrote — so the tag on 2 has nothing to hold
-- here beyond the snapshot file itself, which is what tag/tag-keep is.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-px.sql:/tmp/paimon-px.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-px.sql"
--   for t in px pxa; do rm -rf example/paimon/db.db/$t && cp -R "$WH/db.db/$t" example/paimon/db.db/$t; done

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.px (k INT, v STRING)
TBLPROPERTIES (
  'primary-key' = 'k',
  'bucket' = '1',
  'write-only' = 'true'
);

INSERT INTO db.px VALUES (1, 'a');
INSERT INTO db.px VALUES (2, 'b');
INSERT INTO db.px VALUES (3, 'c');
INSERT INTO db.px VALUES (4, 'd');
INSERT INTO db.px VALUES (5, 'e');
INSERT INTO db.px VALUES (6, 'f');

CALL sys.create_tag(table => 'db.px', tag => 'keep', snapshot => 2);

CALL sys.reset_consumer('db.px', 'reader', 4);

CALL sys.expire_snapshots(table => 'db.px', retain_min => 1);

CREATE TABLE db.pxa (k INT, v STRING)
TBLPROPERTIES (
  'primary-key' = 'k',
  'bucket' = '1',
  'write-only' = 'true'
);

INSERT INTO db.pxa VALUES (1, 'a');
INSERT INTO db.pxa VALUES (2, 'b');
INSERT INTO db.pxa VALUES (3, 'c');
INSERT INTO db.pxa VALUES (4, 'd');
INSERT INTO db.pxa VALUES (5, 'e');
INSERT INTO db.pxa VALUES (6, 'f');

CALL sys.create_tag(table => 'db.pxa', tag => 'keep', snapshot => 2);

CALL sys.reset_consumer('db.pxa', 'reader', 4);

CALL sys.expire_snapshots(table => 'db.pxa', retain_max => 2, retain_min => 1);

SELECT snapshot_id, commit_time, commit_kind FROM db.`px$snapshots` ORDER BY snapshot_id;
SELECT snapshot_id, commit_time, commit_kind FROM db.`pxa$snapshots` ORDER BY snapshot_id;
SELECT tag_name, snapshot_id FROM db.`pxa$tags`;
SELECT consumer_id, next_snapshot_id FROM db.`pxa$consumers`;
