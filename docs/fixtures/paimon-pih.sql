-- Regenerates example/paimon/db.db/pih and example/paimon/iceberg/db/pih — `pic` under
-- `metadata.iceberg.storage = hadoop-catalog`, which puts the Iceberg metadata in *catalog
-- storage* rather than under the table: `IcebergCommitCallback.catalogDatabasePath` at
-- release-1.3.1 takes the table's parent (`/wh/db.db`, which has to end in `.db`), infers
-- `storage-location = catalog-storage` for every storage type but `table-location` (or takes
-- `metadata.iceberg.storage-location` when set), and writes to `<warehouse>/iceberg/<db>/<table>/metadata`
-- — here `/wh/iceberg/db/pih/metadata/`, beside the warehouse's `db.db/`, so an Iceberg
-- HadoopCatalog at `/wh/iceberg` lists `db.pih`. The table directory carries no Iceberg
-- marker at all, and the export is a directory of its own that a workspace scan lists as an
-- Iceberg table — one whose `location` (`/wh/db.db/pih`) is not the directory its metadata
-- is in, and whose data files sit under that location. Both directories are checked in, side
-- by side the way they sat under /wh.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-pic.sql:
--
--   1  CREATE   (k INT, v STRING) primary-key k, bucket 1, metadata.iceberg.storage = hadoop-catalog
--   2  INSERT   (1 a) (2 b)                                  snapshot-1, and iceberg/db/pih/metadata/v1.metadata.json
--   3  INSERT   (2 B) (3 c)                                  snapshot-2, and v2.metadata.json
--   4  SELECT * ORDER BY k                                   the oracle: 1 a / 2 B / 3 c
--
-- To regenerate:
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-pih.sql:/tmp/paimon-pih.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-pih.sql"
--   rm -rf example/paimon/db.db/pih example/paimon/iceberg/db/pih
--   cp -R "$WH/db.db/pih" example/paimon/db.db/pih
--   mkdir -p example/paimon/iceberg/db && cp -R "$WH/iceberg/db/pih" example/paimon/iceberg/db/pih
--   find example/paimon/db.db/pih example/paimon/iceberg/db/pih -name '.*.crc' -delete

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.pih (k INT, v STRING)
TBLPROPERTIES ('primary-key' = 'k', 'bucket' = '1', 'metadata.iceberg.storage' = 'hadoop-catalog');

INSERT INTO db.pih VALUES (1, 'a'), (2, 'b');

INSERT INTO db.pih VALUES (2, 'B'), (3, 'c');

SELECT * FROM db.pih ORDER BY k;
