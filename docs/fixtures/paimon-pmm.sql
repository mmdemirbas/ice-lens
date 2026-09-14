-- Regenerates example/paimon/db.db/pmm — an append table whose commits merge their manifests,
-- the oracle for what the next commit does to a Paimon base manifest list. Every commit
-- (FileStoreCommitImpl.tryCommitOnce, release-1.3.1) rebuilds the base list from the previous
-- snapshot's base and delta manifests through ManifestFileMerger.merge: a full compaction when
-- the manifests that must change (any with a DELETE entry, or smaller than
-- manifest.target-file-size, 8 MB) sum past manifest.full-compaction-threshold-size (16 MB),
-- else a minor one — the manifests taken in list order into bins that close at the target
-- size, and the leftover bin merged only when it holds at least manifest.merge-min-count (30)
-- manifests; a merge folds the entries, an ADD and a DELETE of one file cancelling
-- (FileEntry.mergeEntries), so a merged manifest can hold fewer entries than its inputs. A
-- small table never reaches either size, so the count is the rule a fixture can show, and it
-- is set to 5 here.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-ao.sql. Unaware-bucket, so a Spark write never
-- compacts and each INSERT is one data file in one delta manifest.
--
-- Statements, in order, and the base list each snapshot should carry — base_N is
-- merge(base_{N-1} + delta_{N-1}), so a merge shows one commit after the count is reached:
--
--   1  CREATE   bucket = -1, manifest.merge-min-count = 5   schema-0
--   2..6  INSERT ids 1..5                                    snapshots 1..5: bases of 0, 1, 2, 3, 4 manifests
--   7  DELETE   WHERE id = 2 — the whole file removed         snapshot 6: base = one merged manifest (5 ≥ 5),
--                                                             holding the five ADDs; delta = DELETE f2
--   8..13  INSERT ids 6..11                                  snapshots 7..9: bases of 2, 3, 4;
--                                                             snapshot 10: base = one merged manifest of 7
--                                                             entries — the DELETE of f2 cancelled the ADD it
--                                                             met; snapshots 11, 12: bases of 2, 3
--
-- Observed (2026-09-15): exactly that, with one correction to the first draft of this header —
-- the DELETE matched every row of its file, so Spark removed the file (delta = DELETE f2 alone,
-- deltaRecordCount -1, an APPEND commit) rather than rewriting it, and the second merge folds
-- nine entries to seven, not ten to eight. Base lists 0, 1, 2, 3, 4, 1, 2, 3, 4, 1, 2, 3; the
-- merged manifest at snapshot 6 holds five ADDs and the one at snapshot 10 seven ADDs with
-- numDeletedFiles 0; SELECT * returns ids 1, 3..11.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-pmm.sql:/tmp/paimon-pmm.sql:ro" \
--     tabulario/spark-iceberg:latest \
--     -c "/opt/spark/bin/spark-sql --master local[1] --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false -f /tmp/paimon-pmm.sql"
--   rm -rf example/paimon/db.db/pmm && cp -R "$WH/db.db/pmm" example/paimon/db.db/pmm
--   find example/paimon/db.db/pmm -name '.*.crc' -delete
--
-- pmma is pmm after CALL sys.compact_manifest — FileStoreCommitImpl.compactManifestOnce, the
-- same merge with manifest.merge-min-count and manifest.full-compaction-threshold-size both at
-- 1, committing a COMPACT snapshot with an empty delta list only when the set of manifests
-- changed. Regenerate it from the checked-in pmm (the copy keeps the file names the two share):
--
--   WH=$(mktemp -d); mkdir -p "$WH/db.db"; cp -R example/paimon/db.db/pmm "$WH/db.db/pmma"
--   docker run --rm --entrypoint bash -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     tabulario/spark-iceberg:latest \
--     -c "/opt/spark/bin/spark-sql --master local[1] --jars /opt/paimon-spark.jar <the same confs> \
--           -e \"CALL sys.compact_manifest(table => 'db.pmma'); SELECT count(*) FROM db.pmma; CALL sys.compact_manifest(table => 'db.pmma');\""
--   rm -rf example/paimon/db.db/pmma && cp -R "$WH/db.db/pmma" example/paimon/db.db/pmma
--   find example/paimon/db.db/pmma -name '.*.crc' -delete
--
-- Observed (2026-09-15): the first call returned true and wrote snapshot 13 — COMPACT, base list
-- of one manifest holding the ten ADDs (snapshot 12's merged manifest of seven and three of one),
-- numDeletedFiles 0, delta list empty, deltaRecordCount 0, totalRecordCount 10, the index
-- manifest, statistics and nextRowId of snapshot 12; count(*) 10; the second call returned true
-- and wrote nothing — LATEST stayed at 13.

CREATE DATABASE IF NOT EXISTS db;
CREATE TABLE db.pmm (id INT, v STRING) TBLPROPERTIES ('bucket' = '-1', 'manifest.merge-min-count' = '5');
INSERT INTO db.pmm VALUES (1, 'a');
INSERT INTO db.pmm VALUES (2, 'b');
INSERT INTO db.pmm VALUES (3, 'c');
INSERT INTO db.pmm VALUES (4, 'd');
INSERT INTO db.pmm VALUES (5, 'e');
DELETE FROM db.pmm WHERE id = 2;
INSERT INTO db.pmm VALUES (6, 'f');
INSERT INTO db.pmm VALUES (7, 'g');
INSERT INTO db.pmm VALUES (8, 'h');
INSERT INTO db.pmm VALUES (9, 'i');
INSERT INTO db.pmm VALUES (10, 'j');
INSERT INTO db.pmm VALUES (11, 'k');
SELECT * FROM db.pmm ORDER BY id;
