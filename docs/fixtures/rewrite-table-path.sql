-- rewrite_table_path on Iceberg, run against copies of checked-in tables — the oracle for
-- model/RewriteTablePathPlan.kt. The file-list CSV each run wrote and the files it staged are
-- saved under core/src/test/resources/rewrite-table-path/<run>.file-list.csv and
-- <run>.staging.txt, and RewriteTablePathFixtureTest holds the plan on the checked-in table
-- to both.
--
-- RewriteTablePathSparkAction (1.8.1) copies nothing into place: it rewrites the metadata into a
-- staging directory (staging_location, else <metadata dir>/copy-table-staging-<uuid>/) with
-- every path under source_prefix moved under target_prefix, and writes <staging>/file-list, a
-- CSV of (from, to) pairs for a copy tool. Rewritten: the end version (end_version, else the
-- current metadata) and every metadata-log entry back to start_version (exclusive; each must
-- exist), each as a JSON with its location, snapshots' manifest lists, log, write.*.path
-- properties and statistics paths replaced; the manifest list of every snapshot the end
-- version holds that the start does not; every manifest the end version's snapshots list
-- (all_manifests; with a start version only those whose added_snapshot_id the start lacks),
-- entry by entry; a positional delete's file_path column. Listed: the staged files from
-- staging, and for the live entries of the rewritten manifests a data file and an equality
-- delete as they are — a DELETED entry keeps its rewritten path and is not listed. Output
-- (latest_version, file_list_location).
--
-- Run (2026-09-15) with Spark 3.5.5 (the tabulario/spark-iceberg image, Iceberg 1.8.1) on
-- copies of example/iceberg/default/mor, eqdel, stats, pstats, extdata (with extdata-files
-- beside it), v3 and expired mounted at /wh/default/<name>, the spark.conf of orph.scala plus
-- spark.sql.session.timeZone UTC; each statement its own spark-sql -e.
--
--   rewrite_table_path(mor, /wh/default/mor → /copy/default/mor, staging /wh/staging/mor)
--                                  v7.metadata.json  /wh/staging/mor/file-list — staged 7 versions, 6 lists, 10 manifests (all_manifests of v7),
--                                  3 positional deletes rewritten; listed 32 lines: those 26 from staging and 6 data files as they are —
--                                  every file live in any rewritten manifest, the two the compaction removed included
--   … start_version => 'v4.metadata.json'      v7.metadata.json — versions v5..v7, the 3 snapshots v4 lacks, their 7 manifests, 2 data files, 2 positional deletes
--   … end_version => 'v3.metadata.json'        v3.metadata.json — versions v1..v3, 2 lists, 2 manifests, 4 data files
--   rewrite_table_path(eqdel, …)               v5.metadata.json — the equality delete listed as it is (/wh/default/eqdel/data/00000-eq-deletes.parquet), the positional delete from staging
--   rewrite_table_path(stats, …)               v4.metadata.json — the file-list names /wh/staging/stats/<id>-<uuid>.stats → /copy/default/stats/metadata/…, and the staging
--                                              directory holds no .stats file: the path is rewritten in metadata.json and the file is never staged
--   rewrite_table_path(pstats, …)              IllegalArgumentException: Partition statistics files are not supported yet.
--   rewrite_table_path(extdata, /wh/default/extdata → …)   IllegalArgumentException: Path /wh/extdata-files does not start with /wh/default/extdata/   (the write.data.path property)
--   rewrite_table_path(extdata, /wh → /copy, staging /wh/staging/extdata2)   v3.metadata.json — the data files listed /wh/extdata-files/… → /copy/extdata-files/…
--   rewrite_table_path(v3, …)                  IllegalArgumentException: Content offset is required for DV   (the delete-manifest rewrite cannot rebuild a deletion vector entry)
--   rewrite_table_path(expired, …)             v6.metadata.json — all 6 versions rewritten, the ones naming expired snapshots included; 1 list (the retained snapshot's),
--                                              its 3 manifests, 2 data files; the expired snapshots' lists are neither staged nor listed
--   rewrite_table_path(mor, /wh/default/mor → /wh/default/mor)   IllegalArgumentException: Source prefix cannot be the same as target prefix (/wh/default/mor)
--   rewrite_table_path(mor, /other → …)        IllegalArgumentException: Path /wh/default/mor/metadata/snap-2479080604789318646-1-14465ee4-06ff-4a51-a2f4-09545c681239.avro does not start with /other/
--
-- Every Spark procedure of 1.8.1 is in SparkProcedures; rewrite_table_path is the one that moves a table, and it moves the metadata alone.

CALL lens.system.rewrite_table_path(table => 'default.mor', source_prefix => '/wh/default/mor', target_prefix => '/copy/default/mor', staging_location => '/wh/staging/mor');
CALL lens.system.rewrite_table_path(table => 'default.eqdel', source_prefix => '/wh/default/eqdel', target_prefix => '/copy/default/eqdel', staging_location => '/wh/staging/eqdel');
CALL lens.system.rewrite_table_path(table => 'default.stats', source_prefix => '/wh/default/stats', target_prefix => '/copy/default/stats', staging_location => '/wh/staging/stats');
CALL lens.system.rewrite_table_path(table => 'default.pstats', source_prefix => '/wh/default/pstats', target_prefix => '/copy/default/pstats', staging_location => '/wh/staging/pstats');
CALL lens.system.rewrite_table_path(table => 'default.extdata', source_prefix => '/wh/default/extdata', target_prefix => '/copy/default/extdata', staging_location => '/wh/staging/extdata');
CALL lens.system.rewrite_table_path(table => 'default.extdata', source_prefix => '/wh', target_prefix => '/copy', staging_location => '/wh/staging/extdata2');
CALL lens.system.rewrite_table_path(table => 'default.v3', source_prefix => '/wh/default/v3', target_prefix => '/copy/default/v3', staging_location => '/wh/staging/v3');
CALL lens.system.rewrite_table_path(table => 'default.expired', source_prefix => '/wh/default/expired', target_prefix => '/copy/default/expired', staging_location => '/wh/staging/expired');
CALL lens.system.rewrite_table_path(table => 'default.mor', source_prefix => '/wh/default/mor', target_prefix => '/wh/default/mor');
CALL lens.system.rewrite_table_path(table => 'default.mor', source_prefix => '/wh/default/mor', target_prefix => '/copy/default/mor', start_version => 'v4.metadata.json', staging_location => '/wh/staging/mor-from-v4');
CALL lens.system.rewrite_table_path(table => 'default.mor', source_prefix => '/wh/default/mor', target_prefix => '/copy/default/mor', end_version => 'v3.metadata.json', staging_location => '/wh/staging/mor-to-v3');
CALL lens.system.rewrite_table_path(table => 'default.mor', source_prefix => '/other', target_prefix => '/copy/default/mor', staging_location => '/wh/staging/mor-bad');
