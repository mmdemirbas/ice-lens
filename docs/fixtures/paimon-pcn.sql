-- Regenerates example/paimon/db.db/pcn — the decoupled changelog lifecycle on a table that
-- produces no changelog (`changelog-producer = none`, the default), where the delta list is the
-- change stream: `SnapshotDeletion` (`produceChangelog` false) then keeps an expiring
-- snapshot's base and delta manifest lists and every `APPEND`-sourced data file for
-- `ExpireChangelogImpl`, and the long-lived changelog under `changelog/` replays whole. The
-- twin of paimon-pcl.sql; same statements, same retention, no producer.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-pcn.sql:/tmp/paimon-pcn.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-pcn.sql"
--   rm -rf example/paimon/db.db/pcn && cp -R "$WH/db.db/pcn" example/paimon/db.db/pcn
--   find example/paimon/db.db/pcn -name '.*.crc' -delete

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.pcn (k INT, v STRING)
TBLPROPERTIES (
  'primary-key'                = 'k',
  'bucket'                     = '1',
  'snapshot.num-retained.min'  = '1',
  'snapshot.num-retained.max'  = '2',
  'changelog.num-retained.min' = '1',
  'changelog.num-retained.max' = '4'
);

INSERT INTO db.pcn VALUES (1, 'a');
INSERT INTO db.pcn VALUES (2, 'b');
INSERT INTO db.pcn VALUES (3, 'c');
INSERT INTO db.pcn VALUES (1, 'A');
INSERT INTO db.pcn VALUES (4, 'd');
INSERT INTO db.pcn VALUES (5, 'e');
INSERT INTO db.pcn VALUES (2, 'B');

SELECT * FROM db.pcn ORDER BY k;
