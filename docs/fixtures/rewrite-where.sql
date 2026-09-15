-- rewrite_data_files(where => …) and options => map('remove-dangling-deletes', 'true') — what
-- the filter does to the plan and what the dangling-delete pass removes after it: the oracles
-- for RewritePlan.filteredOut and planDanglingDeletes in model/RewritePlan.kt, recorded rather
-- than checked in. RewriteWhereFixtureTest holds the plan on the checked-in tables to these runs.
--
-- The rules, read at Iceberg 1.8.1:
--   - RewriteDataFilesSparkAction.planFileGroups (199–206) plans
--     table.newScan().useSnapshot(start).filter(where).ignoreResiduals().planFiles(), so a where
--     picks files the way a scan does — ManifestEvaluator over the partition summaries, then
--     InclusiveMetricsEvaluator over each file's bounds — and every file left is rewritten whole.
--     A file the filter rules out is no candidate and fills no group.
--   - execute (160–197): nothing found to rewrite returns EMPTY_RESULT before the dangling pass;
--     otherwise, under remove-dangling-deletes, RemoveDanglingDeletesSparkAction runs after the
--     rewrite commit. Its execute() returns nothing at once on an unpartitioned table with one
--     spec ("ManifestFilterManager already performs this table-wide delete on each commit");
--     findDanglingDeletes (118–170) otherwise groups the live data entries by (partition, spec_id)
--     for the minimum sequence_number, left-joins the live delete entries, and removes a
--     positional delete with sequence_number < the minimum, an equality delete with <=, and any
--     delete whose partition has no data file — by sequence alone, never by target.
--   - The commit-time drop the unpartitioned case defers to — MergingSnapshotProducer.apply
--     (911–927) calls deleteFilterManager.dropDeleteFilesOlderThan(min over the kept data
--     manifests' min_sequence_number, unassigned ones skipped, from lastSequenceNumber) — is
--     applied only inside a delete manifest the commit opens (ManifestFilterManager
--     canContainDeletedFiles, 366–376: a dropped path, an expression, or a dropped partition).
--     A data-file rewrite drops no delete file, so no delete manifest is opened and nothing goes.
--   - The rewritten groups' output is committed at the starting snapshot's sequence number
--     (use-starting-sequence-number, true by default), which is what moves a partition's floor.
--
-- Run (2026-09-15) on tabulario/spark-iceberg (Spark 3.5.5, Iceberg 1.8.1), each statement its
-- own `spark-sql -e` under the spark.conf of orph.scala plus spark.sql.session.timeZone UTC,
-- copies mounted at /wh/default/<same name>, options => map('min-input-files', '2') throughout:
--
--   evolved   where => 'id = 2'            0 0 0 0 — the scan opens one file (00000-0-a6c9cccf…), one candidate, no group of two
--             where => 'note = \'fifth\''  2 1 1869 0 — 00000-0-a6c9cccf… and 00000-2-d97e21b7… (the two the scan opens) into
--                                          one file of 3 rows; 00000-1-8796d368… untouched; replace: deleted-data-files 2,
--                                          added-data-files 1, deleted-records 3, added-records 3
--   evolved   where => 'id > 1000'         2 1 2198 0 — 00000-1-8796d368… and 00000-2-d97e21b7… into one of 3 rows;
--   (a second copy)                        00000-0-a6c9cccf… untouched; the same call again 0 0 0 0
--   eqren     where => 'label > \'f\''     0 0 0 0 — one file
--             where => 'label = \'bravo\'' 2 1 1884 0 — 00000-0-06075099… (4 rows, both deletes paired) and 00000-0-533395c9…
--                                          (1 row, the bravo written after the rename) into one of 3 rows: deleted-records 5,
--                                          added-records 3 — the equality and positional deletes applied in the rewrite;
--                                          total-delete-files still 2, both delete files listed; SELECT 1 alpha / 4 delta /
--                                          5 echo / 7 golf / 8 bravo
--   eqren     the same with 'remove-dangling-deletes' => 'true'   2 1 1884 0, both delete files still listed, no second
--   (a second copy)                                                commit: the action stands down on an unpartitioned table
--   mor       map('remove-dangling-deletes', 'true')               0 0 0 0 — nothing to rewrite, the pass never runs
--             map('rewrite-all', 'true', 'remove-dangling-deletes', 'true')
--                                          1 1 1286 0 — the one data file rewritten, landing at sequence_number 6 (the
--                                          starting snapshot's); the delete entries at 3, 4 and 6 all still live,
--                                          total-delete-files 3, no second commit — the two below 6 that the rule would
--                                          take are kept, because the action returns nothing on an unpartitioned table and
--                                          the commit opened no delete manifest
--   fupp      map('rewrite-all', 'true', 'remove-dangling-deletes', 'true')
--                                          4 2 3524 0 — p=x's two files into one, p=y's two into one, both at sequence 2;
--                                          then a second replace with removed-delete-files 5, total-delete-files 0: every
--                                          delete (positional at 1 < 2; equality at 1 and 2 <= 2) gone; SELECT 1 x a2 /
--                                          2 y b2 / 4 x d
--
-- Result columns at 1.8.1: rewritten_data_files_count, added_data_files_count, rewritten_bytes_count,
-- failed_data_files_count (removed_delete_files_count is a later addition).
--
-- Trap: in spark-sql a string literal inside the where value is escaped \', not '' — Spark's
-- lexer has no doubled-quote escape and reads 'note = ''fifth''' as three adjacent literals
-- joined, which arrives as `note = fifth` and fails `A column or function parameter with name
-- `fifth` cannot be resolved` (RewriteDataFilesProcedure: "Cannot parse predicates in where
-- option"). A column the table lacks fails the same way.
--
-- To reproduce:
--
--   WH=$(mktemp -d); mkdir -p $WH/wh/default
--   for t in evolved eqren mor fupp; do cp -R example/iceberg/default/$t $WH/wh/default/$t; done
--   find $WH/wh -name '.*.crc' -delete
--   (the spark.conf heredoc of orph.scala into $WH/spark.conf, plus `spark.sql.session.timeZone UTC`)
--   docker run --rm --entrypoint bash -v "$WH/wh:/wh" -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg -c 'S="/opt/spark/bin/spark-sql --master local[1] --properties-file /tmp/spark.conf"; \
--       $S -e "CALL lens.system.rewrite_data_files(table => '"'"'default.evolved'"'"', where => '"'"'note = \'"'"'fifth\'"'"''"'"', options => map('"'"'min-input-files'"'"', '"'"'2'"'"'))"'

CALL lens.system.rewrite_data_files(table => 'default.evolved', where => 'id = 2', options => map('min-input-files', '2'));
CALL lens.system.rewrite_data_files(table => 'default.evolved', where => 'note = \'fifth\'', options => map('min-input-files', '2'));
SELECT file_path, record_count FROM lens.default.evolved.files ORDER BY file_path;
SELECT snapshot_id, operation, summary['deleted-data-files'], summary['added-data-files'], summary['deleted-records'], summary['added-records'] FROM lens.default.evolved.snapshots ORDER BY committed_at DESC LIMIT 1;
CALL lens.system.rewrite_data_files(table => 'default.evolvedb', where => 'id > 1000', options => map('min-input-files', '2'));
CALL lens.system.rewrite_data_files(table => 'default.eqren', where => 'label > \'f\'', options => map('min-input-files', '2'));
CALL lens.system.rewrite_data_files(table => 'default.eqren', where => 'label = \'bravo\'', options => map('min-input-files', '2'));
SELECT file_path, content, record_count FROM lens.default.eqren.files ORDER BY content, file_path;
SELECT * FROM lens.default.eqren ORDER BY id;
CALL lens.system.rewrite_data_files(table => 'default.eqrenb', where => 'label = \'bravo\'', options => map('min-input-files', '2', 'remove-dangling-deletes', 'true'));
SELECT data_file.file_path, data_file.content, sequence_number, status FROM lens.default.mor.entries WHERE status < 2 ORDER BY data_file.content, data_file.file_path;
CALL lens.system.rewrite_data_files(table => 'default.mor', options => map('remove-dangling-deletes', 'true'));
CALL lens.system.rewrite_data_files(table => 'default.mor', options => map('rewrite-all', 'true', 'remove-dangling-deletes', 'true'));
SELECT data_file.file_path, data_file.content, sequence_number, status FROM lens.default.mor.entries WHERE status < 2 ORDER BY data_file.content, data_file.file_path;
CALL lens.system.rewrite_data_files(table => 'default.fupp', options => map('rewrite-all', 'true', 'remove-dangling-deletes', 'true'));
SELECT data_file.file_path, data_file.content, sequence_number, status FROM lens.default.fupp.entries WHERE status < 2 ORDER BY data_file.content, data_file.file_path;
SELECT snapshot_id, operation, summary['deleted-data-files'], summary['added-data-files'], summary['removed-delete-files'], summary['total-delete-files'] FROM lens.default.fupp.snapshots ORDER BY committed_at DESC LIMIT 2;
