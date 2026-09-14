-- Regenerates example/paimon/db.db/po and poa — one Paimon primary-key table kept twice, before
-- and after `sys.remove_orphan_files`, so the files the procedure deleted are the oracle for
-- what this app plans it would delete (the pe/pea shape). The orphans are a rollback's leftovers
-- (see paimon-prb.sql: a rollback deletes snapshot files above the target and nothing they name)
-- plus five stray files written between the rollback and the copy, one per place the procedure
-- treats differently:
--
--   p=x/bucket-0/stray.parquet   a bucket directory — listed, deleted
--   manifest/stray               the manifest directory — listed, deleted
--   snapshot/junk                the snapshot directory, not named snapshot-N / EARLIEST /
--                                LATEST — cleanSnapshotDir deletes it
--   p=x/stray-in-partition       a partition directory above the buckets — never listed, kept
--   schema/junk                  never listed, kept
--   junk-at-root                 never listed, kept
--
-- At release-1.3.1 (OrphanFilesClean, LocalOrphanFilesClean and Spark's SparkOrphanFilesClean
-- share the rules): candidates are the files directly inside manifest/, index/, statistics/,
-- every bucket-* directory at partition depth (a partition directory is one whose name holds
-- '=') and the external data paths, modified before `older_than` (1 day ago by default; a
-- value must be in the past — "The arg olderThan must be less than now"); used files are every
-- name a snapshot in snapshot/, a tag or a long-lived changelog names on every branch — the
-- three manifest lists, their manifests, every entry's file and extra files whatever its
-- _KIND, the index manifest and its files, the statistics file — and the join is by file
-- NAME. A file in snapshot/ or changelog/ whose name is not the directory's is deleted on the
-- same age rule.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-prb.sql.
--
-- Statements, in order:
--
--   1  CREATE   primary key k, partitioned by p, bucket = 1        schema-0
--   2..4  three INSERTs into p=x                                    snapshots 1..3, a file each
--   5  CALL sys.rollback(version => '1')                          snapshots 2, 3 gone; f2, f3, their
--                                                                  manifests and lists left on disk
--   (shell) the six stray files; copy → po; sleep 2
--   6  CALL sys.remove_orphan_files(table)                        bare: older_than = 1 day ago,
--                                                                  0 files deleted (all fresh)
--   7  CALL sys.remove_orphan_files(table, older_than => now)     the deletion; count printed
--
-- Observed (2026-09-14): the bare call returned `0 0`; the dated call returned `11 10788` and
-- `poa` lacks exactly these eleven files of `po`'s twenty-two — the rollback's two data files,
-- two manifests and four manifest lists, and the three strays in bucket-0/, manifest/ and
-- snapshot/. `junk-at-root`, `p=x/stray-in-partition` and `schema/junk` are still there.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-po.sql:/tmp/paimon-po.sql:ro" \
--     tabulario/spark-iceberg:latest \
--     -c "S='/opt/spark/bin/spark-sql --master local[1] --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false'; \
--         \$S -f /tmp/paimon-po.sql && cd /wh/db.db/poa && \
--         echo stray > p=x/bucket-0/stray.parquet && echo stray > manifest/stray && echo stray > snapshot/junk && \
--         echo stray > p=x/stray-in-partition && echo stray > schema/junk && echo stray > junk-at-root && \
--         cp -R /wh/db.db/poa /wh/db.db/po && sleep 2 && \
--         \$S -e \"CALL sys.remove_orphan_files(table => 'db.poa')\" && \
--         \$S -e \"CALL sys.remove_orphan_files(table => 'db.poa', older_than => '\$(date -u +'%Y-%m-%d %H:%M:%S')')\""
--   for t in po poa; do rm -rf example/paimon/db.db/$t && cp -R "$WH/db.db/$t" example/paimon/db.db/$t; done
--   find example/paimon/db.db/po example/paimon/db.db/poa -name '.*.crc' -delete

CREATE DATABASE IF NOT EXISTS db;
CREATE TABLE db.poa (k INT, p STRING, v STRING) PARTITIONED BY (p) TBLPROPERTIES ('primary-key' = 'k,p', 'bucket' = '1');
INSERT INTO db.poa VALUES (1, 'x', 'a');
INSERT INTO db.poa VALUES (2, 'x', 'b');
INSERT INTO db.poa VALUES (3, 'x', 'c');
CALL sys.rollback(table => 'db.poa', version => '1');
SELECT * FROM db.poa ORDER BY k;
