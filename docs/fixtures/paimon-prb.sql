-- Regenerates example/paimon/db.db/prb and prba — one Paimon primary-key table kept twice: as
-- written (`prb`, copied on disk), and after `sys.rollback` set it back to snapshot 2 (`prba`, the
-- original, rolled back in place). Same file names in both, which is what makes the comparison in
-- PaimonRollbackFixtureTest exact.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-pe.sql. What a rollback removes is
-- RollbackHelper.cleanLargerThan at release-1.3.1, and it is less than an Iceberg reader expects
-- of the word: the snapshot files above the target (`snapshot/snapshot-<id>`, LATEST moved to the
-- target), the long-lived changelog files above it, and every tag whose snapshot is above it —
-- and nothing else. The data files, manifests and manifest lists those commits wrote stay on
-- disk, named by nothing, until remove_orphan_files. Rolling back to a tag whose snapshot has
-- expired writes the snapshot file back from the tag (createSnapshotFileIfNeeded).
--
-- Statements, in order — the numbers matter because the expected values are read off them:
--
--   1  CREATE   primary key k, one bucket
--   2..5  four INSERTs                         snapshots 1..4 APPEND, a level-0 file each (f1..f4)
--   6  CALL     create_tag('two', snapshot => 2)
--   7  CALL     create_tag('three', snapshot => 3)
--   copy → prb
--   8  CALL     rollback(version => '2')       deletes snapshot-3, snapshot-4 and tag-three; LATEST = 2
--   9  SELECT   the rows                       1 a / 2 b
--  10  SELECT   the tags                       two
--
-- Expected on disk afterwards: `prb` holds snapshots 1..4, tags two and three, four data files;
-- `prba` holds snapshots 1..2 and tag two, and still every data file, manifest and manifest list
-- `prb` has — f3 and f4 and the lists and manifests of snapshots 3 and 4 named by nothing.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-prb.sql:/tmp/paimon-prb.sql:ro" \
--     -v "$PWD/docs/fixtures/paimon-prb-rollback.sql:/tmp/paimon-prb-rollback.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "S='/opt/spark/bin/spark-sql --master local[1] --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false'; \
--         \$S -f /tmp/paimon-prb.sql && cp -R /wh/db.db/prba /wh/db.db/prb && \$S -f /tmp/paimon-prb-rollback.sql"
--   for t in prb prba; do rm -rf example/paimon/db.db/$t && cp -R "$WH/db.db/$t" example/paimon/db.db/$t; done

CREATE DATABASE IF NOT EXISTS db;
CREATE TABLE db.prba (k INT, v STRING)
TBLPROPERTIES (
  'primary-key' = 'k',
  'bucket' = '1'
);

INSERT INTO db.prba VALUES (1, 'a');
INSERT INTO db.prba VALUES (2, 'b');
INSERT INTO db.prba VALUES (3, 'c');
INSERT INTO db.prba VALUES (4, 'd');

CALL sys.create_tag(table => 'db.prba', tag => 'two', snapshot => 2);
CALL sys.create_tag(table => 'db.prba', tag => 'three', snapshot => 3);
