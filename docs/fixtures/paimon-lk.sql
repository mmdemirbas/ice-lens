-- Regenerates example/paimon/db.db/lk — a Paimon primary-key table whose changelog is produced
-- by lookup, so an upsert of an existing key writes the -U / +U pair.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-cl.sql. That table's `changelog-producer = input`
-- copies the input rows as they came, so its changelog holds only +I and -D and the two update
-- kinds were named from RowKind's byte values without a file to read them from. A `lookup`
-- producer computes the change at commit time by looking the key up in the levels below: the
-- second INSERT of k = 2 becomes -U (2, 'b') and +U (2, 'B') in the changelog file, and the
-- DELETE becomes -D with the value it removed.
--
-- Statements, in order — the numbers matter because the expected values in PaimonRowKindTest
-- are read off them:
--
--   1  CREATE   primary key k, one bucket, changelog-producer = lookup
--   2  INSERT   k = 1, 2, 3                       snapshot 1 APPEND (+ a COMPACT if lookup ran), changelog +I ×3
--   3  INSERT   k = 2 (a new value), 4            changelog -U (2,'b'), +U (2,'B'), +I (4,'d')
--   4  DELETE   k = 3                             changelog -D (3,'c')
--   5  SELECT   the rows                          the oracle: (1,'a'), (2,'B'), (4,'d')
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-lk.sql:/tmp/paimon-lk.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-lk.sql"
--   rm -rf example/paimon/db.db/lk && cp -R "$WH/db.db/lk" example/paimon/db.db/lk

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.lk (k INT, v STRING)
TBLPROPERTIES (
  'primary-key' = 'k',
  'bucket' = '1',
  'changelog-producer' = 'lookup'
);

INSERT INTO db.lk VALUES (1, 'a'), (2, 'b'), (3, 'c');

INSERT INTO db.lk VALUES (2, 'B'), (4, 'd');

DELETE FROM db.lk WHERE k = 3;

SELECT * FROM db.lk ORDER BY k;
