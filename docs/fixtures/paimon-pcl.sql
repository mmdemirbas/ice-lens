-- Regenerates example/paimon/db.db/pcl — a changelog whose lifecycle is decoupled from its
-- snapshots', so `changelog/changelog-<id>` files outlive the snapshots that wrote them.
--
-- Under `changelog.num-retained.max` above `snapshot.num-retained.max` (or the same for `min`
-- or `time-retained`; `CoreOptions.changelogLifecycleDecoupled` at release-1.3.1) an expiring
-- snapshot is not simply deleted: `ExpireSnapshotsImpl.expireUntil` keeps its changelog
-- manifest list and changelog files, writes the snapshot's JSON again as
-- `changelog/changelog-<id>` (`Changelog.fromPath`, a `Snapshot` with the same fields), and
-- deletes the snapshot file — so a streaming reader can still consume the change stream of
-- commits the table no longer holds. `ExpireChangelogImpl` then retires those under
-- `changelog.num-retained.*`. Both run at commit time (`TableCommitImpl.expire`), so seven
-- inserts under min 1 / max 2 snapshots and min 1 / max 4 changelogs leave two snapshots, a
-- `changelog/` directory of long-lived changelogs, their manifest lists and files still on
-- disk — every one of them a false orphan to a reader that walks `snapshot/` and `tag/` alone,
-- in the direction that gets a file deleted.
--
-- `changelog-producer = input` so each commit has a changelog manifest list of its own (an
-- append table's changelog is its delta list, which the decoupled expiry then keeps too —
-- `produceChangelog` false in `SnapshotDeletion`; that shape is not written here).
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-pcl.sql:/tmp/paimon-pcl.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-pcl.sql"
--   rm -rf example/paimon/db.db/pcl && cp -R "$WH/db.db/pcl" example/paimon/db.db/pcl
--   find example/paimon/db.db/pcl -name '.*.crc' -delete

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.pcl (k INT, v STRING)
TBLPROPERTIES (
  'primary-key'                = 'k',
  'bucket'                     = '1',
  'changelog-producer'         = 'input',
  'snapshot.num-retained.min'  = '1',
  'snapshot.num-retained.max'  = '2',
  'changelog.num-retained.min' = '1',
  'changelog.num-retained.max' = '4'
);

INSERT INTO db.pcl VALUES (1, 'a');
INSERT INTO db.pcl VALUES (2, 'b');
INSERT INTO db.pcl VALUES (3, 'c');
INSERT INTO db.pcl VALUES (1, 'A');
INSERT INTO db.pcl VALUES (4, 'd');
INSERT INTO db.pcl VALUES (5, 'e');
INSERT INTO db.pcl VALUES (2, 'B');

SELECT * FROM db.pcl ORDER BY k;
