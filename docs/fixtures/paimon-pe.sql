-- Regenerates example/paimon/db.db/pe and pea — one Paimon primary-key table kept twice: as
-- written (`pe`, copied on disk), and after expire_snapshots deleted the files the expiry freed
-- (`pea`, the original, expired in place). Same file names in both, which is what makes the
-- comparison in PaimonExpiryFilePlanFixtureTest exact.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-pc.sql. Which snapshots an expiry removes is one rule
-- (PaimonExpiryPlan, checked on px/pxa); which files go with them is ExpireSnapshotsImpl.expireUntil
-- at release-1.3.1, in four passes over the range [begin, end): data files from the DELETE entries
-- of the delta lists of (begin, end] — a file a snapshot removed was live in the one before, which
-- is expired, and that includes the first retained snapshot's own removals — unless the nearest
-- earlier tag still holds the file; changelog files added by [begin, end); the manifest lists,
-- manifests, index manifests, index files and statistics of [begin, end) that neither the tags in
-- the range nor snapshot `end` name; then the snapshot files.
--
-- Statements, in order — the numbers matter because the expected values are read off them:
--
--   1  CREATE   primary key k, one bucket, changelog-producer = input
--   2..6  five INSERTs                      snapshots 1..5 APPEND, each a level-0 file f1..f5 and a changelog file c1..c5;
--                                             the fifth flush makes five sorted runs and
--                                           snapshot 6 COMPACT: DELETE f1..f5, ADD f6 at level 5; no changelog
--   7  INSERT                               snapshot 7 APPEND: f7, c7
--   8  INSERT                               snapshot 8 APPEND: f8, c8
--   9  CALL     create_tag('keep', snapshot => 3)   tag/tag-keep, a copy of snapshot 3, naming f1..f3
--   copy → pe
--  10  CALL     expire_snapshots(retain_max => 2, retain_min => 1)   keeps 7 and 8; expires [1, 7) — retain_min must come down with retain_max, or the call is refused:
--                                             data: the deltas of 2..7 hold DELETE entries only in 6 — f1..f5 — and the tag
--                                               before 6 is `keep` on 3, whose merged files are f1..f3, so f4 and f5 go;
--                                             changelog: c1..c5 go (6 has none); c7, c8 stay;
--                                             manifests: everything the lists of 1..6 name that neither the tag's
--                                               data lists nor snapshot 7's data lists, index manifest or statistics name —
--                                               the tag's changelog list is not in that set and goes;
--                                             snapshot files 1..6 go; tag-keep stays
--
-- Expected on disk afterwards: `pe` holds every file; `pea` holds snapshot-7, snapshot-8,
-- bucket-0 with f1, f2, f3, f6, f7, f8 and the changelog files of 7 and 8, and no manifest that
-- only snapshots 1..6 named.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-pe.sql:/tmp/paimon-pe.sql:ro" \
--     -v "$PWD/docs/fixtures/paimon-pe-expire.sql:/tmp/paimon-pe-expire.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "S='/opt/spark/bin/spark-sql --master local[1] --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false'; \
--         \$S -f /tmp/paimon-pe.sql && cp -R /wh/db.db/pea /wh/db.db/pe && \$S -f /tmp/paimon-pe-expire.sql"
--   for t in pe pea; do rm -rf example/paimon/db.db/$t && cp -R "$WH/db.db/$t" example/paimon/db.db/$t; done

CREATE DATABASE IF NOT EXISTS db;
CREATE TABLE db.pea (k INT, v STRING)
TBLPROPERTIES (
  'primary-key' = 'k',
  'bucket' = '1',
  'changelog-producer' = 'input'
);

INSERT INTO db.pea VALUES (1, 'a');
INSERT INTO db.pea VALUES (2, 'b');
INSERT INTO db.pea VALUES (3, 'c');
INSERT INTO db.pea VALUES (4, 'd');
INSERT INTO db.pea VALUES (5, 'e');
INSERT INTO db.pea VALUES (6, 'f');
INSERT INTO db.pea VALUES (7, 'g');
CALL sys.create_tag(table => 'db.pea', tag => 'keep', snapshot => 3);

SELECT 'pea-before' AS t, snapshot_id, commit_kind, total_record_count, delta_record_count FROM db.`pea$snapshots` ORDER BY snapshot_id;
SELECT 'pea-before' AS t, file_path, level FROM db.`pea$files` ORDER BY file_path;
