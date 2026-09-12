-- Regenerates example/paimon/db.db/cs — a Paimon primary-key table with a consumer, and an expiry
-- the consumer holds back.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-tg.sql. A Paimon consumer is a streaming reader's
-- bookmark: consumer/consumer-<id> is a JSON file holding the next snapshot the reader will
-- consume, and expire_snapshots will not expire a snapshot a consumer has not consumed yet. A
-- reader that walks snapshot/ alone sees an expiry that stopped short for no reason it can name;
-- this table is what says why.
--
-- Statements, in order — the numbers matter because the expected values in
-- PaimonConsumerFixtureTest are read off them:
--
--   1  CREATE   primary key k, one bucket
--   2  INSERT   k = 1, 2                          snapshot 1: 2 rows
--   3  INSERT   k = 3                             snapshot 2: 1 row
--   4  INSERT   k = 4                             snapshot 3: 1 row
--   5  CALL sys.reset_consumer  'reader' to next snapshot 2    consumer/consumer-reader
--   6  CALL sys.expire_snapshots  retain_max = 1  snapshot 1 expires; snapshot 2 stays because
--                                                 the consumer has not consumed it; 3 is the latest
--
-- Expected on disk afterwards: snapshot/ holds 2 and 3 (EARLIEST = 2, LATEST = 3);
-- consumer/consumer-reader is {"nextSnapshot": 2}; bucket-0 holds snapshot 2's and 3's data
-- files, and snapshot 1's is gone.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-cs.sql:/tmp/paimon-cs.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-cs.sql"
--   rm -rf example/paimon/db.db/cs && cp -R "$WH/db.db/cs" example/paimon/db.db/cs

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.cs (k INT, v STRING)
TBLPROPERTIES (
  'primary-key' = 'k',
  'bucket' = '1'
);

INSERT INTO db.cs VALUES (1, 'a'), (2, 'b');

INSERT INTO db.cs VALUES (3, 'c');

INSERT INTO db.cs VALUES (4, 'd');

CALL sys.reset_consumer('db.cs', 'reader', 2);

CALL sys.expire_snapshots(table => 'db.cs', retain_max => 1, retain_min => 1);
