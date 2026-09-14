-- Regenerates example/paimon/db.db/ppx and ppxa — one Paimon append table kept twice: as written
-- (`ppx`, copied on disk), and after expire_partitions dropped the partitions the table's own
-- `partition.expiration-time` says are past (`ppxa`, the original, expired in place). Same file
-- names in both, which is what makes the comparison in PaimonPartitionExpiryFixtureTest exact.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-pe.sql. Which partitions an expiry drops is
-- PartitionExpire.doExpire at release-1.3.1 under the strategy `partition.expiration-strategy`
-- names: `values-time` (the default) reads a time off the partition's values —
-- `partition.timestamp-pattern` with `$field` substituted, else the first partition field's
-- value alone — through `partition.timestamp-formatter`, else the default `yyyy-MM-dd[ HH:mm:ss]`,
-- and drops the partition when `now - partition.expiration-time` is after that time; a value the
-- formatter cannot parse is warned about and kept. `update-time` drops a partition whose newest
-- file (the greatest `_CREATION_TIME` over the manifests' entries, removals included) is older
-- than the cutoff. At most `partition.expiration-max-num` (100) go, smallest values first. The
-- drop is one OVERWRITE commit removing the partitions' files. Only a table with
-- `partition.expiration-time` set has a PartitionExpire at all, and a write checks it only
-- every `partition.expiration-check-interval` (1 h) — the procedure sets the interval to 0.
--
-- Statements, in order — the numbers matter because the expected values are read off them:
--
--   1  CREATE   partitioned by (region, dt), both STRING; partition.expiration-time = 1 d,
--               partition.timestamp-pattern = $dt — the time is the second partition field,
--               which the default (the first field, `region`) could not parse
--   2  INSERT   (eu, 2020-01-01) k 1; (eu, 2021-06-15) k 2       snapshot 1 APPEND, a file per partition
--   3  INSERT   (us, 2099-12-31) k 3; (us, n-a) k 4              snapshot 2 APPEND, a file per partition
--   copy → ppx
--   4  CALL     expire_partitions(table)                          snapshot 3 OVERWRITE: DELETE entries for the
--                                                                 two `eu` files; 2099-12-31 is after any cutoff;
--                                                                 `n-a` does not parse and is kept with a warning;
--                                                                 the procedure prints the two dropped partitions
--   5  CALL     expire_partitions(table, expire_strategy => 'update-time')
--                                                                 no expired partitions, no snapshot: every file
--                                                                 was written seconds ago
--   6  SELECT   the rows left                                     3 c us 2099-12-31 / 4 d us n-a
--
-- Expected on disk afterwards: `ppx` holds four data files under four partition directories and
-- snapshots 1..2; `ppxa` the same four files (an expiry drops from the manifests, not from disk —
-- snapshots 1 and 2 still list the two, and a snapshot expiry reaching snapshot 3's DELETE entries
-- is what deletes them) and snapshot 3, whose delta manifest holds two DELETE entries.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-ppx.sql:/tmp/paimon-ppx.sql:ro" \
--     -v "$PWD/docs/fixtures/paimon-ppx-expire.sql:/tmp/paimon-ppx-expire.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "S='/opt/spark/bin/spark-sql --master local[1] --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false'; \
--         \$S -f /tmp/paimon-ppx.sql && cp -R /wh/db.db/ppxa /wh/db.db/ppx && \$S -f /tmp/paimon-ppx-expire.sql"
--   for t in ppx ppxa; do rm -rf example/paimon/db.db/$t && cp -R "$WH/db.db/$t" example/paimon/db.db/$t; done

CREATE DATABASE IF NOT EXISTS db;
CREATE TABLE db.ppxa (k INT, v STRING, region STRING, dt STRING)
PARTITIONED BY (region, dt)
TBLPROPERTIES (
  'partition.expiration-time' = '1 d',
  'partition.timestamp-pattern' = '$dt'
);

INSERT INTO db.ppxa VALUES (1, 'a', 'eu', '2020-01-01'), (2, 'b', 'eu', '2021-06-15');
INSERT INTO db.ppxa VALUES (3, 'c', 'us', '2099-12-31'), (4, 'd', 'us', 'n-a');
