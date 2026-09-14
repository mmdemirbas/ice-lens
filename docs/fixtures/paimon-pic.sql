-- Regenerates example/paimon/db.db/pic — a Paimon primary-key table with Iceberg-compatible
-- metadata written beside its own: `metadata.iceberg.storage = table-location` makes every
-- commit also write Iceberg metadata under `<table>/metadata/` (`IcebergCommitCallback` at
-- release-1.3.1: `v<N>.metadata.json`, Iceberg manifest lists and manifests as Avro, a
-- `version-hint.text`), so an Iceberg reader opens the Paimon data files as an Iceberg table.
--
-- The directory then carries both formats' markers — `snapshot/` and `schema/`, and
-- `metadata/*.metadata.json` — which is the shape a detector has to decide: the table is
-- Paimon, and the Iceberg metadata is its export.
--
-- What the export lists is what this table was written for, and the header first predicted the
-- level rule alone — `shouldAddFileToIceberg`: a primary-key table exports a file only at
-- `num-levels - 1` (5 on the defaults), so neither commit's level-0 file should be there. The
-- metadata says otherwise, and the reason is that `createMetadata` has two paths. Snapshot 1
-- found no metadata for snapshot 0, so it rebuilt the export from the snapshot
-- (`createMetadataWithoutBase`), which lists every file of every **raw-convertible** split
-- whatever its level — and one level-0 file in a bucket is raw convertible. Snapshot 2 found
-- `v1.metadata.json` and went incremental (`createMetadataWithBase`), which is where the level
-- rule is applied, so its file is not listed and its manifest list re-lists snapshot 1's
-- manifest. An Iceberg reader of this table therefore sees one of its two live files, and
-- `v1.metadata.json` is gone: `metadata.iceberg.previous-versions-max` is 0 by default and
-- `metadata.iceberg.delete-after-commit.enabled` is on, so each commit deletes the versions
-- before its own. The manifest list is `snap-<count>-<uuid>.avro` with a counter the callback
-- keeps per run, not the snapshot id — both lists here are `snap-1-…` — and the manifest is
-- `<uuid>-m<count>.avro`.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-pse.sql:
--
--   1  CREATE   (k INT, v STRING) primary-key k, bucket 1, metadata.iceberg.storage = table-location
--   2  INSERT   (1 a) (2 b)                                  snapshot-1, and metadata/v1.metadata.json
--   3  INSERT   (2 B) (3 c)                                  snapshot-2, and metadata/v2.metadata.json
--   4  SELECT * ORDER BY k                                   the oracle: 1 a / 2 B / 3 c
--
-- To regenerate:
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-pic.sql:/tmp/paimon-pic.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-pic.sql"
--   rm -rf example/paimon/db.db/pic && cp -R "$WH/db.db/pic" example/paimon/db.db/pic
--   find example/paimon/db.db/pic -name '.*.crc' -delete

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.pic (k INT, v STRING)
TBLPROPERTIES ('primary-key' = 'k', 'bucket' = '1', 'metadata.iceberg.storage' = 'table-location');

INSERT INTO db.pic VALUES (1, 'a'), (2, 'b');

INSERT INTO db.pic VALUES (2, 'B'), (3, 'c');

SELECT * FROM db.pic ORDER BY k;
