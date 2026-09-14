-- Regenerates example/paimon/db.db/pbk and pbka — one Paimon primary-key table whose `bucket`
-- option was raised from 1 to 2 after three writes, kept twice: with the change made and no data
-- rescaled (`pbk`, copied on disk), and after the INSERT OVERWRITE that rescales it (`pbka`, the
-- original). Same file names for the three original files in both.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-pe.sql. Every manifest entry records `_TOTAL_BUCKETS`,
-- the bucket count its file was written under, and at release-1.3.1 a write restores a bucket's
-- files and refuses when that count differs from the table's `bucket`
-- (AbstractFileStoreWrite.scanExistingFileMetas: "Try to write table with a new bucket num 2, but
-- the previous bucket num is 1. Please switch to batch mode, and perform INSERT OVERWRITE to
-- rescale current data layout first."), and a commit refuses entries of one partition under two
-- counts unless it is an OVERWRITE (FileStoreCommitImpl, "Total buckets of partition … changed
-- from 1 to 2 without overwrite"). SchemaManager lets the option change (never from or to -1, and
-- never through a dynamic option) and nothing else checks it, so the table reads fine and every
-- write fails until the rescale.
--
-- Statements, in order — the numbers matter because the expected values are read off them:
--
--   1  CREATE   primary key k, bucket = 1                       schema-0
--   2..4  three INSERTs                                          snapshots 1..3, a file each in bucket-0, _TOTAL_BUCKETS 1
--   5  ALTER    SET TBLPROPERTIES ('bucket' = '2')              schema-1; no snapshot
--   copy → pbk
--   (scratch, not checked in: INSERT INTO db.pbka VALUES (4, 'd') fails with the message above)
--   6  INSERT OVERWRITE db.pbka SELECT * FROM db.pbka           snapshot 4 OVERWRITE: DELETE the three files, ADD
--                                                                two files under bucket-0 and bucket-1, _TOTAL_BUCKETS 2
--   7  INSERT   (4, 'd')                                        snapshot 5 APPEND, accepted now
--   8  SELECT   the rows                                        1 a / 2 b / 3 c / 4 d
--
-- Expected on disk afterwards: `pbk` holds schema-0 and schema-1, snapshots 1..3, three files in
-- bucket-0; `pbka` adds snapshots 4 and 5 and a bucket-1 directory.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-pbk.sql:/tmp/paimon-pbk.sql:ro" \
--     -v "$PWD/docs/fixtures/paimon-pbk-rescale.sql:/tmp/paimon-pbk-rescale.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "S='/opt/spark/bin/spark-sql --master local[1] --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false'; \
--         \$S -f /tmp/paimon-pbk.sql && cp -R /wh/db.db/pbka /wh/db.db/pbk && \$S -f /tmp/paimon-pbk-rescale.sql"
--   for t in pbk pbka; do rm -rf example/paimon/db.db/$t && cp -R "$WH/db.db/$t" example/paimon/db.db/$t; done

CREATE DATABASE IF NOT EXISTS db;
CREATE TABLE db.pbka (k INT, v STRING)
TBLPROPERTIES (
  'primary-key' = 'k',
  'bucket' = '1'
);

INSERT INTO db.pbka VALUES (1, 'a');
INSERT INTO db.pbka VALUES (2, 'b');
INSERT INTO db.pbka VALUES (3, 'c');

ALTER TABLE db.pbka SET TBLPROPERTIES ('bucket' = '2');
