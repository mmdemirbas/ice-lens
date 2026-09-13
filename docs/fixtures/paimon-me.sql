-- Regenerates example/paimon/db.db/pu, example/paimon/db.db/ag and example/paimon/db.db/fr —
-- one primary-key table per merge engine other than the default.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-dv.sql. Every other primary-key fixture merges under
-- `deduplicate`, where the latest record for a key is the row; these three exist because the
-- merged row count and the row lookup apply a merge engine's rule to the files, and a rule read
-- off `PartialUpdateMergeFunction`, `AggregateMergeFunction` and `FirstRowMergeFunction` at
-- release-1.3.1 had never met a table written under it. What a read returns is printed by the
-- SELECT that ends each table, and PaimonMergeEngineFixtureTest holds the count and the lookup
-- to those rows.
--
-- Statements, in order — the numbers matter because the expected values are read off them:
--
--   pu  CREATE   primary key k, one bucket, merge-engine = partial-update,
--                partial-update.remove-record-on-delete = true (without it Spark refuses the DELETE)
--       INSERT   (1, a1, null) (2, a2, null) (3, a3, b3)
--       INSERT   (1, null, b1) (2, A2, null) (4, a4, b4)   — a null leaves the column as it was
--       DELETE   k = 3                                       — a -D record; the whole row goes
--       INSERT   (3, a3-again, null)                         — the key is a row again: the latest is +I
--       reads    1 (a1, b1), 2 (A2, null), 3 (a3-again, null), 4 (a4, b4) — four rows
--
--   ag  CREATE   primary key k, one bucket, merge-engine = aggregation, total sum, latest last_value,
--                aggregation.remove-record-on-delete = true
--       INSERT   (1, 10, x) (2, 5, y)
--       INSERT   (1, 5, z) (3, 1, w)
--       DELETE   k = 2
--       reads    1 (15, z), 3 (1, w) — two rows
--
--   fr  CREATE   primary key k, one bucket, merge-engine = first-row
--       INSERT   (1, first) (2, first)                       — snapshot 1; the forced compaction
--                                                              that follows is snapshot 2
--       INSERT   (1, second) (3, first)                     — snapshot 3; the compaction (4) drops
--                                                              the second write of k 1
--       DELETE   k = 2                                       — snapshot 5: first-row cannot take a -D,
--                                                              so Spark rewrites the file without the
--                                                              row — at level 0, with no compaction
--       reads    3 (first) — ONE row. Not the two the statements describe: a batch read of a
--                first-row table skips level-0 files (DataTableBatchScan, batchScanSkipLevel0 —
--                the writer's forced compaction is supposed to have moved everything up), and the
--                rewrite that kept k 1 sits at level 0. Paimon's own reads, run afterwards:
--                snapshot 1 returns no rows at all, snapshot 3 returns `1 first, 2 first` (k 3 is
--                at level 0), snapshot 4 all three, snapshot 5 `3 first` alone. The header first
--                predicted two rows; the read corrected it, which is the reason the fixture is
--                an oracle.
--
-- To regenerate:
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-me.sql:/tmp/paimon-me.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-me.sql"
--   for t in pu ag fr; do rm -rf example/paimon/db.db/$t && cp -R "$WH/db.db/$t" example/paimon/db.db/$t; done

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.pu (k INT, a STRING, b STRING)
TBLPROPERTIES (
  'primary-key' = 'k',
  'bucket' = '1',
  'merge-engine' = 'partial-update',
  'partial-update.remove-record-on-delete' = 'true'
);

INSERT INTO db.pu VALUES (1, 'a1', NULL), (2, 'a2', NULL), (3, 'a3', 'b3');

INSERT INTO db.pu VALUES (1, NULL, 'b1'), (2, 'A2', NULL), (4, 'a4', 'b4');

DELETE FROM db.pu WHERE k = 3;

INSERT INTO db.pu VALUES (3, 'a3-again', NULL);

SELECT * FROM db.pu ORDER BY k;

CREATE TABLE db.ag (k INT, total INT, latest STRING)
TBLPROPERTIES (
  'primary-key' = 'k',
  'bucket' = '1',
  'merge-engine' = 'aggregation',
  'fields.total.aggregate-function' = 'sum',
  'fields.latest.aggregate-function' = 'last_value',
  'aggregation.remove-record-on-delete' = 'true'
);

INSERT INTO db.ag VALUES (1, 10, 'x'), (2, 5, 'y');

INSERT INTO db.ag VALUES (1, 5, 'z'), (3, 1, 'w');

DELETE FROM db.ag WHERE k = 2;

SELECT * FROM db.ag ORDER BY k;

CREATE TABLE db.fr (k INT, v STRING)
TBLPROPERTIES (
  'primary-key' = 'k',
  'bucket' = '1',
  'merge-engine' = 'first-row'
);

INSERT INTO db.fr VALUES (1, 'first'), (2, 'first');

INSERT INTO db.fr VALUES (1, 'second'), (3, 'first');

DELETE FROM db.fr WHERE k = 2;

SELECT * FROM db.fr ORDER BY k;
