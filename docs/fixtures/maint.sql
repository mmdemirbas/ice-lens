-- Regenerates example/iceberg/default/maint — a merge-on-read Iceberg table after the two
-- maintenance procedures that rewrite delete files and manifests.
--
-- Written by Spark 3.5.5 / Iceberg 1.8.1 from the tabulario/spark-iceberg image. `mor` shows a
-- compaction leaving two delete files dangling and nothing in the suite has ever seen the
-- procedure whose job is to remove them, nor a manifest rewrite — the commit that exercises
-- `manifest_file.added_snapshot_id` from the other side, since it writes manifests whose every
-- entry is EXISTING and the summary counts the manifests rather than the files.
--
-- Statements, in order — the numbers matter because the expected values in
-- MaintenanceFixtureTest are read off them:
--
--   1  INSERT   ids 1..4 — one file under --master local[1]
--   2  INSERT   ids 5..8 — a second file, so the compaction has two to merge
--   3  DELETE   id = 2 — merge-on-read, a positional delete file against file 1
--   4  DELETE   id = 6 — a positional delete file against file 2
--   5  rewrite_data_files       a compaction: the two files become one, the deletes above are
--                               applied, and their files are left dangling — they name data files
--                               no longer live
--   6  DELETE   id = 8 — a positional delete file against the compacted file, so a live delete
--                               exists beside the two dangling ones
--   7  rewrite_position_delete_files   rewrites the live delete file and drops the dangling
--                               records; the summary says how many delete files and positions
--                               went in and came out
--   8  INSERT   ids 9, 10 — a second data manifest, two rows so the next delete cannot
--                               drop the whole file
--   9  DELETE   id = 9 — a second delete manifest. Without these two the rewrite finds one
--                               manifest of one kind and two of the other, and its created and
--                               kept figures both come out 1 — an oracle that cannot tell the
--                               two counts apart. With them it rewrites both kinds: created 2,
--                               kept 0, replaced 4. (A one-row INSERT here was tried first: the
--                               DELETE then removed the file outright instead of writing a
--                               positional delete, and the figures were 1 and 1 again.)
--  10  rewrite_manifests        rewrites the manifests; the summary counts manifests created,
--                               kept and replaced, and every entry in a new manifest is EXISTING.
--                               The manifests snapshot 7 wrote holding only DELETED entries are
--                               already gone from the list by then: the next commit drops a
--                               manifest with nothing live in it
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
--     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/maint.sql:/tmp/maint.sql:ro" \
--     -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[1]' --properties-file /tmp/spark.conf -f /tmp/maint.sql"
--   rm -rf example/iceberg/default/maint
--   cp -R "$WH/wh/default/maint" example/iceberg/default/maint
--   find example/iceberg/default/maint -name '.*.crc' -delete

CREATE TABLE lens.default.maint (
  id   INT,
  name STRING
) USING iceberg
TBLPROPERTIES (
  'format-version'    = '2',
  'write.delete.mode' = 'merge-on-read',
  'write.update.mode' = 'merge-on-read',
  'write.merge.mode'  = 'merge-on-read'
);

-- 1
INSERT INTO lens.default.maint VALUES (1, 'alpha'), (2, 'bravo'), (3, 'charlie'), (4, 'delta');

-- 2
INSERT INTO lens.default.maint VALUES (5, 'echo'), (6, 'foxtrot'), (7, 'golf'), (8, 'hotel');

-- 3  positional delete against file 1
DELETE FROM lens.default.maint WHERE id = 2;

-- 4  positional delete against file 2
DELETE FROM lens.default.maint WHERE id = 6;

-- 5  compaction: both data files rewritten into one; the two delete files above now dangle
CALL lens.system.rewrite_data_files(table => 'default.maint', options => map('min-input-files', '2'));

-- 6  a live positional delete against the compacted file
DELETE FROM lens.default.maint WHERE id = 8;

-- 7  rewrite the delete files: the live one is rewritten, the dangling records dropped
CALL lens.system.rewrite_position_delete_files(table => 'default.maint', options => map('rewrite-all', 'true'));

-- 8  a second data manifest for the rewrite to merge
INSERT INTO lens.default.maint VALUES (9, 'india'), (10, 'juliet');

-- 9  a second delete manifest for the rewrite to merge
DELETE FROM lens.default.maint WHERE id = 9;

-- 10  rewrite the manifests
CALL lens.system.rewrite_manifests(table => 'default.maint');
