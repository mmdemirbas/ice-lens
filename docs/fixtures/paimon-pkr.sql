-- Regenerates example/paimon/db.db/pkr — a Paimon primary-key table whose KEY column is renamed
-- between writes, so the bucket holds files whose key column is `_KEY_k` and files whose key
-- column is `_KEY_id`, for one key.
--
-- Paimon 1.3.1 allows a primary-key rename (`SchemaManager.commitChanges` guards partition keys
-- only, and `primaryKeys` follow the rename) and reads the old file by field id: a key field's
-- id is `KEY_FIELD_ID_START + field id` under either name. A reader that asks a bucket's files
-- for `_KEY_id` by name hits the old file with a column it does not have — the `pse` rule
-- (place by the file's own schema) applied to the `_KEY_*` columns rather than passed through.
--
--   1  CREATE   (k INT, v STRING) primary-key k, bucket 1                schema-0
--   2  INSERT   (1 a) (2 b)                                              file A: _KEY_k
--   3  ALTER    RENAME COLUMN k TO id                                    schema-1: field 0 is id, primary-key id
--   4  INSERT   (3 c)                                                    file B: _KEY_id
--   5  INSERT   (1 A)                                                    file C: _KEY_id — the update of key 1 across the rename
--   6  SELECT * ORDER BY id                                              the oracle: 1 A / 2 b / 3 c
--
-- Three one-row-ish inserts stay under num-sorted-run.compaction-trigger (5), so no compaction
-- rewrites file A under the new schema.
--
-- To regenerate:
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-pkr.sql:/tmp/paimon-pkr.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-pkr.sql"
--   rm -rf example/paimon/db.db/pkr && cp -R "$WH/db.db/pkr" example/paimon/db.db/pkr
--   find example/paimon/db.db/pkr -name '.*.crc' -delete

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.pkr (k INT, v STRING)
TBLPROPERTIES (
  'primary-key' = 'k',
  'bucket' = '1'
);

INSERT INTO db.pkr VALUES (1, 'a'), (2, 'b');

ALTER TABLE db.pkr RENAME COLUMN k TO id;

INSERT INTO db.pkr VALUES (3, 'c');

INSERT INTO db.pkr VALUES (1, 'A');

SELECT id, v FROM db.pkr ORDER BY id;
