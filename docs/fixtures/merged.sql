-- Regenerates example/iceberg/default/merged, mergedel and mergespec — three Iceberg tables
-- that show when a commit merges the manifests already in the manifest list.
--
-- Written by Spark 3.5.5 / Iceberg 1.8.1 from the tabulario/spark-iceberg image. Every batch
-- write Spark makes — INSERT, DELETE, UPDATE, MERGE, rewrite_data_files — commits through
-- MergingSnapshotProducer, and its ManifestMergeManager runs on every one of them: the
-- manifests the commit is about to list are grouped by partition spec, bin-packed from the
-- oldest end into commit.manifest.target-size-bytes (8 MB) bins, and a bin is rewritten as one
-- manifest unless it holds a single manifest, or it holds the *first* manifest (the one this
-- commit wrote, else the newest) and fewer than commit.manifest.min-count-to-merge (100). No
-- other fixture has a hundred manifests, so none of them ever merged; these lower the count to
-- two, and mergespec does not lower it at all.
--
-- Statements, in order — the numbers matter because the expected values in
-- ManifestMergeFixtureTest are read off them:
--
--   merged  (min-count-to-merge = 2, copy-on-write deletes)
--   1  CREATE                                    v1
--   2  INSERT (1, 'alpha')                       snapshot 1: one manifest, 1 ADDED
--   3  INSERT (2, 'beta')                        snapshot 2: [new, m1] is a bin of two holding the first → merged: one manifest, 1 ADDED + 1 EXISTING
--   4  INSERT (3, 'gamma')                       snapshot 3: merged again: one manifest, 1 ADDED + 2 EXISTING
--   5  DELETE WHERE id = 1                       snapshot 4 (delete): the file held one row, so it goes whole and no data file is written;
--                                                            the filter rewrites the manifest with 1 DELETED + 2 EXISTING, and a bin of one is left as it is
--   6  INSERT (4, 'delta')                       snapshot 5: [new, m4] merged — the DELETED entry belongs to an earlier snapshot and is dropped: 1 ADDED + 2 EXISTING
--   7  ALTER  commit.manifest-merge.enabled = false
--   8  INSERT (5, 'epsilon')                     snapshot 6: nothing is merged: two manifests, the new one (1 ADDED) and m5 (1 ADDED + 2 EXISTING)
--
--   mergedel  (min-count-to-merge = 2, merge-on-read deletes)
--   1  CREATE
--   2  INSERT (1, 'alpha'), (2, 'beta'), (3, 'gamma')   snapshot 1: one data manifest, one file of three rows
--   3  DELETE WHERE id = 1                       snapshot 2 (delete): a positional delete file; the data side lists no new manifest, so the
--                                                            first is the existing one and its bin of one stays; one delete manifest, 1 ADDED
--   4  DELETE WHERE id = 2                       snapshot 3 (delete): Spark 3.5 on 1.8.1 deletes at file granularity, so it reads the file's existing
--                                                            delete file, writes one holding both positions and removes the old one; the delete side is
--                                                            [new, d2 rewritten with 1 DELETED], a bin of two → merged: one delete manifest, written by
--                                                            snapshot 3, 1 ADDED + 1 DELETED (its own removal is kept, an earlier snapshot's would be dropped);
--                                                            the data manifest is still the one from snapshot 1
--
--   mergespec  (every default — min-count-to-merge stays 100)
--   1  CREATE   unpartitioned
--   2  INSERT (1, 'alpha')                       snapshot 1: one manifest under spec 0
--   3  INSERT (2, 'beta')                        snapshot 2: [new, m1] holds the first and 2 < 100 → two manifests under spec 0
--   4  ALTER    ADD PARTITION FIELD bucket(4, id)   v4: spec 1
--   5  INSERT (3, 'gamma')                       snapshot 3: the new manifest is under spec 1, so the spec-0 bin [m2, m1] does not hold the first,
--                                                            and a bin without it merges whatever the count → two manifests: the new one (spec 1) and one merged (spec 0, 2 EXISTING)
--
-- Expected afterwards: the all_manifests listings at the end print, per snapshot, exactly the
-- manifest counts and the added/existing/deleted figures above. --master local[1] makes each
-- INSERT one file and one manifest. The header first predicted 1 ADDED + 1 EXISTING for mergedel's
-- third snapshot; the manifest settled it as 1 ADDED + 1 DELETED, because the second DELETE
-- rewrote the first delete file rather than adding beside it (write.delete.granularity is `file`
-- under Spark 3.5 since 1.8.0, #11478) — the merge is the same, its input is not.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   cat > "$WH/spark.conf" <<'EOF'
--   spark.sql.extensions              org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions
--   spark.sql.catalog.lens            org.apache.iceberg.spark.SparkCatalog
--   spark.sql.catalog.lens.type       hadoop
--   spark.sql.catalog.lens.warehouse  /wh
--   spark.sql.defaultCatalog          lens
--   spark.sql.catalogImplementation   in-memory
--   spark.ui.enabled                  false
--   spark.eventLog.enabled            false
--   EOF
--   mkdir -p "$WH/wh"
--   docker run --rm --entrypoint bash \
--     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/merged.sql:/tmp/merged.sql:ro" \
--     -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[1]' --properties-file /tmp/spark.conf -f /tmp/merged.sql"
--   for t in merged mergedel mergespec; do rm -rf example/iceberg/default/$t && cp -R "$WH/wh/default/$t" example/iceberg/default/$t; done

CREATE TABLE default.merged (id INT, name STRING)
USING iceberg
TBLPROPERTIES ('format-version' = '2', 'commit.manifest.min-count-to-merge' = '2');

INSERT INTO default.merged VALUES (1, 'alpha');
INSERT INTO default.merged VALUES (2, 'beta');
INSERT INTO default.merged VALUES (3, 'gamma');
DELETE FROM default.merged WHERE id = 1;
INSERT INTO default.merged VALUES (4, 'delta');
ALTER TABLE default.merged SET TBLPROPERTIES ('commit.manifest-merge.enabled' = 'false');
INSERT INTO default.merged VALUES (5, 'epsilon');

CREATE TABLE default.mergedel (id INT, name STRING)
USING iceberg
TBLPROPERTIES (
  'format-version'                    = '2',
  'commit.manifest.min-count-to-merge' = '2',
  'write.delete.mode'                 = 'merge-on-read'
);

INSERT INTO default.mergedel VALUES (1, 'alpha'), (2, 'beta'), (3, 'gamma');
DELETE FROM default.mergedel WHERE id = 1;
DELETE FROM default.mergedel WHERE id = 2;

CREATE TABLE default.mergespec (id INT, name STRING)
USING iceberg
TBLPROPERTIES ('format-version' = '2');

INSERT INTO default.mergespec VALUES (1, 'alpha');
INSERT INTO default.mergespec VALUES (2, 'beta');
ALTER TABLE default.mergespec ADD PARTITION FIELD bucket(4, id);
INSERT INTO default.mergespec VALUES (3, 'gamma');

-- The oracle: every snapshot's manifest list, one row per manifest.
SELECT 'merged' AS t, snapshot_id, operation FROM default.merged.snapshots ORDER BY committed_at;
SELECT 'merged' AS t, reference_snapshot_id, content, partition_spec_id, added_snapshot_id, added_data_files_count, existing_data_files_count, deleted_data_files_count, added_delete_files_count, existing_delete_files_count, length, path
FROM default.merged.all_manifests ORDER BY reference_snapshot_id, path;
SELECT 'mergedel' AS t, snapshot_id, operation FROM default.mergedel.snapshots ORDER BY committed_at;
SELECT 'mergedel' AS t, reference_snapshot_id, content, partition_spec_id, added_snapshot_id, added_data_files_count, existing_data_files_count, deleted_data_files_count, added_delete_files_count, existing_delete_files_count, length, path
FROM default.mergedel.all_manifests ORDER BY reference_snapshot_id, path;
SELECT 'mergespec' AS t, snapshot_id, operation FROM default.mergespec.snapshots ORDER BY committed_at;
SELECT 'mergespec' AS t, reference_snapshot_id, content, partition_spec_id, added_snapshot_id, added_data_files_count, existing_data_files_count, deleted_data_files_count, length, path
FROM default.mergespec.all_manifests ORDER BY reference_snapshot_id, path;
