-- Regenerates example/paimon/db.db/pid — `dv` with its deletion vectors exported as Iceberg
-- v3 deletion vectors: `deletion-vectors.enabled` with `deletion-vectors.bitmap64`,
-- `metadata.iceberg.storage = table-location` and `metadata.iceberg.format-version = 3`. At
-- release-1.3.1 `IcebergCommitCallback.needAddDvToIceberg` is exactly those three, and with
-- them every commit that writes a deletion-vector index also writes a DELETES manifest
-- (`createDvManifestFileMetas`): one `POSITION_DELETES` entry per vector, format `puffin`,
-- whose `file_path` is Paimon's own **index file**, `referenced_data_file` the data file the
-- vector marks, and `content_offset` / `content_size_in_bytes` the range the index manifest
-- records — the bitmap64 vector being Iceberg's blob layout, an Iceberg reader opens the
-- index file at that range as a v3 deletion vector. With the vectors as Iceberg's, the level
-- rule loosens too: `shouldAddFileToIceberg` exports any file above level 0.
--
-- Statements, in order — the same as paimon-dv.sql, whose header says why the row counts are
-- what they are (a vector is written only when the L0 delete shadows a key in a higher-level
-- file the compaction leaves alone):
--
--   1  CREATE   primary key k, one bucket, deletion vectors on and 64-bit, Iceberg v3 export
--   2  INSERT   a thousand rows, k = 1..1000                       snapshot-1, COMPACT snapshot-2
--   3  INSERT   five hundred more, k = 1001..1500                  snapshot-3, COMPACT snapshot-4
--   4  DELETE   k IN (2, 1001, 1500)                                snapshot-5, COMPACT snapshot-6 with the index
--   5  SELECT count(*)                                              the oracle: 1497
--
-- To regenerate:
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-pid.sql:/tmp/paimon-pid.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-pid.sql"
--   rm -rf example/paimon/db.db/pid && cp -R "$WH/db.db/pid" example/paimon/db.db/pid
--   find example/paimon/db.db/pid -name '.*.crc' -delete

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.pid (k INT, v STRING)
TBLPROPERTIES (
  'primary-key' = 'k',
  'bucket' = '1',
  'deletion-vectors.enabled' = 'true',
  'deletion-vectors.bitmap64' = 'true',
  'metadata.iceberg.storage' = 'table-location',
  'metadata.iceberg.format-version' = '3'
);

INSERT INTO db.pid SELECT id AS k, concat('v', id) AS v FROM range(1, 1001);

INSERT INTO db.pid SELECT id AS k, concat('v', id) AS v FROM range(1001, 1501);

DELETE FROM db.pid WHERE k IN (2, 1001, 1500);

SELECT count(*) FROM db.pid;
