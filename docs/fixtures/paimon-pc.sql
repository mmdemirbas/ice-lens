-- Regenerates example/paimon/db.db/pc — a Paimon primary-key table left on every default, written
-- to seven times, so that the compaction Paimon's own writer triggers can be checked against what
-- model/PaimonCompaction.kt says a bucket's sorted runs call for.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-cs.sql. A primary-key bucket is an LSM tree: every
-- flush is a level-0 file and its own sorted run, each higher level is one run, and
-- MergeTreeWriter asks UniversalCompaction.pick() on every flush. Below
-- num-sorted-run.compaction-trigger (5) runs it picks nothing. At the trigger it picks by size:
-- when the runs newer than the oldest add up to more than compaction.max-size-amplification-percent
-- (200%) of the oldest, everything goes into the top level; else when the newest runs are within
-- compaction.size-ratio (1%) of each other, those go up one level. Above the trigger it picks
-- regardless. Seven one-row inserts make seven near-identical files, so the fifth flush is where
-- size amplification bites: four files against one.
--
-- Statements, in order — the numbers matter because the expected values in
-- PaimonCompactionFixtureTest are read off them:
--
--   1  CREATE   primary key k, one bucket, nothing else set
--   2..5  four INSERTs                       snapshots 1..4 APPEND: level 0 holds 1, 2, 3, 4 files
--                                             — 4 sorted runs, under the trigger, nothing picked
--   6  INSERT                               snapshot 5 APPEND: five level-0 files, 5 runs = trigger;
--                                             the four newer files outweigh the oldest by 400%,
--                                             so pick() returns a full compaction, and
--                                           snapshot 6 COMPACT: one file at level 5 (num-levels
--                                             defaults to trigger + 1 = 6) holding all five rows
--   7  INSERT                               snapshot 7 APPEND: level 0 one file, level 5 one — 2 runs
--   8  INSERT                               snapshot 8 APPEND: level 0 two files, level 5 one — 3 runs
--
-- Expected on disk afterwards: snapshot/ holds 1..8; the COMPACT is 6 and no other; bucket-0
-- holds eight data files (five the compaction removed from the tree are still on disk, listed by
-- snapshots 1..5); the file at level 5 has 5 rows.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-pc.sql:/tmp/paimon-pc.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-pc.sql"
--   rm -rf example/paimon/db.db/pc && cp -R "$WH/db.db/pc" example/paimon/db.db/pc

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.pc (k INT, v STRING)
TBLPROPERTIES (
  'primary-key' = 'k',
  'bucket' = '1'
);

INSERT INTO db.pc VALUES (1, 'a');
INSERT INTO db.pc VALUES (2, 'b');
INSERT INTO db.pc VALUES (3, 'c');
INSERT INTO db.pc VALUES (4, 'd');
INSERT INTO db.pc VALUES (5, 'e');
INSERT INTO db.pc VALUES (6, 'f');
INSERT INTO db.pc VALUES (7, 'g');

SELECT snapshot_id, commit_kind, total_record_count, delta_record_count FROM db.`pc$snapshots` ORDER BY snapshot_id;
SELECT file_path, level, record_count, file_size_in_bytes, min_sequence_number, max_sequence_number FROM db.`pc$files` ORDER BY level, file_path;
